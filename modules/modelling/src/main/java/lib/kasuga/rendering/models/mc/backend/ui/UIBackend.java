package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import lib.kasuga.core.rendering.IBlurControl;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import lib.kasuga.rendering.output.mc.MinecraftWorldViews;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2i;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(value = Dist.CLIENT)
public class UIBackend implements AutoCloseable {
    @Getter private final List<Renderable> renderables = new ArrayList<>();
    @Getter private final Map<GuiEventListener, UIInstanceData> interactData = new HashMap<>();
    @Getter private final WorldUiInputRouter inputRouter = new WorldUiInputRouter();
    private final WorldUiKeyboardCapture keyboardCapture = new WorldUiKeyboardCapture(inputRouter, KeyMapping::releaseAll);
    private final Map<Renderable, Matrix4f> transforms = new LinkedHashMap<>();
    private final Map<Renderable, WorldUiCanvas> canvases = new HashMap<>();
    protected UIInstanceData currentCallbackData;
    private Object lastLevel;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        UIBackend backend = Constants.UI_BACKEND;
        if (backend == null) return;
        if (backend.lastLevel != mc.level) backend.worldChanged();
        if (!mc.isWindowActive() || mc.player == null
                || mc.screen != null && !(mc.screen instanceof WorldUiInputScreen screen && screen.backend() == backend)) {
            backend.resetInput();
        }
        backend.lastLevel = mc.level;
        backend.refreshKeyboardCapture();
    }

    /** HEAD hook: consume before vanilla updates KeyMapping, opens chat, or handles Escape. */
    public static boolean interceptKey(long window, int key, int scanCode, int action, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        UIBackend backend = Constants.UI_BACKEND;
        return backend != null && window == mc.getWindow().getWindow()
                && backend.keyboardCapture.key(key, scanCode, action, modifiers, backend.allowsKeyboardCapture());
    }

    public static boolean interceptCharacter(long window, int codePoint, int modifiers) {
        Minecraft mc = Minecraft.getInstance();
        UIBackend backend = Constants.UI_BACKEND;
        return backend != null && window == mc.getWindow().getWindow()
                && backend.keyboardCapture.character(codePoint, modifiers, backend.allowsKeyboardCapture());
    }

    private boolean allowsKeyboardCapture() {
        Minecraft mc = Minecraft.getInstance();
        return mc.screen == null && mc.getOverlay() == null && mc.level != null && mc.player != null && mc.isWindowActive();
    }

    private void refreshKeyboardCapture() { keyboardCapture.refresh(allowsKeyboardCapture()); }
    public boolean isKeyboardCaptured() { return keyboardCapture.active(); }

    public static GuiGraphics constructGuiGraphics(PoseStack stack) {
        GuiGraphics gui = new GuiGraphics(Minecraft.getInstance(),
                Minecraft.getInstance().renderBuffers().bufferSource());
        gui.pose().mulPose(stack.last().pose());
        return gui;
    }

    public static GuiGraphics constructGuiGraphics() { return constructGuiGraphics(new PoseStack()); }

    public static void suppressZAxis(PoseStack stack) {
        stack.scale(1, 1, .1f / (GuiGraphics.MAX_GUI_Z - GuiGraphics.MIN_GUI_Z));
    }

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        UIBackend backend = Constants.UI_BACKEND;
        if (mc.screen != null || mc.player == null || backend == null) return;
        double x = mc.getWindow().getGuiScaledWidth() * .5;
        double y = mc.getWindow().getGuiScaledHeight() * .5;
        boolean handled = event.getAction() == GLFW.GLFW_PRESS
                ? backend.inputRouter.press(x, y, event.getButton())
                : backend.inputRouter.release(x, y, event.getButton());
        backend.refreshKeyboardCapture();
        if (handled) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        UIBackend backend = Constants.UI_BACKEND;
        if (mc.screen != null || mc.player == null || backend == null) return;
        if (backend.inputRouter.scroll(mc.getWindow().getGuiScaledWidth() * .5,
                mc.getWindow().getGuiScaledHeight() * .5, event.getScrollDeltaX(), event.getScrollDeltaY())) {
            event.setCanceled(true);
        }
    }

    public void addRenderable(Renderable renderable) {
        if (renderables.contains(renderable)) return;
        renderables.add(renderable);
        if (renderable instanceof GuiEventListener listener) interactData.put(listener, new UIInstanceData(listener));
    }

    /** Register a pixel-local to absolute-world transform. Local +X is right; local +Y is down. */
    public void addRenderable(Renderable renderable, Matrix4fc localToWorld) {
        setTransform(renderable, localToWorld);
        addRenderable(renderable);
    }

    public void setTransform(Renderable renderable, Matrix4fc localToWorld) {
        if (!localToWorld.isFinite() || !localToWorld.isAffine() || localToWorld.determinant() == 0) {
            throw new IllegalArgumentException("UI transform must be finite, affine and invertible");
        }
        transforms.put(renderable, new Matrix4f(localToWorld));
    }

    public void removeRenderable(Renderable renderable) {
        renderables.remove(renderable);
        transforms.remove(renderable);
        WorldUiCanvas canvas = canvases.remove(renderable);
        if (canvas != null) canvas.close();
        if (renderable instanceof GuiEventListener listener) {
            inputRouter.remove(listener);
            UIInstanceData data = interactData.remove(listener);
            if (data == currentCallbackData) currentCallbackData = null;
        }
    }

    public boolean containsRenderable(Renderable renderable) { return renderables.contains(renderable); }
    public boolean containsListener(Renderable renderable) { return interactData.containsKey(renderable); }
    public int renderableSize() { return renderables.size(); }
    public int listenerSize() { return interactData.size(); }

    /** Open only from the client thread while in a world. Escape returns to mouse-look. */
    public WorldUiInputScreen openPointer() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.screen != null) {
            throw new IllegalStateException("Open world UI input from gameplay with no other screen active");
        }
        WorldUiInputScreen screen = new WorldUiInputScreen(this);
        mc.setScreen(screen);
        return screen;
    }

    /** Built-in main-view pipeline. Offscreen cameras must never replace the player's picking geometry. */
    public void renderWorld(WorldRenderPipelineContext context) {
        if (!MinecraftFrameOutputs.MAIN_VIEW.equals(context.viewId())) return;
        if (renderables.isEmpty()) { resetInput(); return; }
        Matrix4f previousProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorting previousSorting = RenderSystem.getVertexSorting();
        RenderSystem.setProjectionMatrix(context.projectionMatrix(), VertexSorting.DISTANCE_TO_ORIGIN);
        var modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix().identity();
        RenderSystem.applyModelViewMatrix();
        try {
            PoseStack pose = new PoseStack();
            pose.mulPose(context.modelViewMatrix());
            GuiGraphics gui = constructGuiGraphics(pose);
            renderAllUis(gui, context.partialTick().getGameTimeDeltaPartialTick(false));
            gui.flush();
        } finally {
            RenderSystem.setProjectionMatrix(previousProjection, previousSorting);
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
        }
    }

    public void renderAllUis(GuiGraphics gui, float partialTicks) {
        if (!MinecraftFrameOutputs.MAIN_VIEW.equals(MinecraftWorldViews.currentViewId())) return;
        Minecraft mc = Minecraft.getInstance();
        boolean enabled = mc.player != null && mc.isWindowActive()
                && (mc.screen == null || mc.screen instanceof WorldUiInputScreen screen && screen.backend() == this);
        double x = gui.guiWidth() * .5;
        double y = gui.guiHeight() * .5;
        if (mc.screen instanceof WorldUiInputScreen) {
            x = mc.mouseHandler.xpos() * gui.guiWidth() / mc.getWindow().getScreenWidth();
            y = mc.mouseHandler.ypos() * gui.guiHeight() / mc.getWindow().getScreenHeight();
        }
        inputRouter.beginFrame(RenderSystem.getProjectionMatrix(), gui.guiWidth(), gui.guiHeight(), x, y);
        for (Renderable renderable : List.copyOf(renderables)) {
            if (renderables.contains(renderable)) renderRenderable(gui, partialTicks, renderable, null, enabled);
        }
        if (enabled) {
            inputRouter.endFrame();
            UIInstanceData previous = currentCallbackData;
            currentCallbackData = interactData.get(inputRouter.hovered());
            if (previous != null && previous != currentCallbackData) previous.removeFocused(mc.player);
            if (currentCallbackData != null) currentCallbackData.setFocused(mc.player, inputRouter.hoverPosition());
        } else resetInput();
    }

    public Pair<Vector2f, Float> renderRenderable(GuiGraphics gui, float partialTicks,
                                                 Renderable renderable, @Nullable UIInstanceData interactions) {
        return renderRenderable(gui, partialTicks, renderable, interactions, true);
    }

    private Pair<Vector2f, Float> renderRenderable(GuiGraphics gui, float partialTicks,
            Renderable renderable, @Nullable UIInstanceData interactions, boolean enabled) {
        Vector2i size = getRenderableSize(renderable);
        IBlurControl blur = renderable instanceof IBlurControl control ? control : null;
        boolean previousBlur = blur != null && blur.ksgB$isBlurEnabled();
        if (blur != null) blur.ksg$SetBlurEnabled(false);
        PoseStack pose = gui.pose();
        pose.pushPose();
        Vector2f mouse = null;
        try {
            Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            pose.translate(-camera.x, -camera.y, -camera.z);
            Matrix4f transform = transforms.get(renderable);
            if (transform != null) pose.mulPose(transform);
            else {
                pose.scale(1f / gui.guiHeight(), -1f / gui.guiHeight(), 1);
                if (size != null) {
                    int left = renderable instanceof LayoutElement element ? element.getX() : 0;
                    int top = renderable instanceof LayoutElement element ? element.getY() : 0;
                    pose.translate(-left - size.x * .5f, -top - size.y * .5f, 0);
                }
            }
            if (size != null && renderable instanceof GuiEventListener listener && enabled) {
                Matrix4f localToView = new Matrix4f(RenderSystem.getModelViewMatrix()).mul(pose.last().pose());
                int left = renderable instanceof LayoutElement element ? element.getX() : 0;
                int top = renderable instanceof LayoutElement element ? element.getY() : 0;
                inputRouter.addSurface(listener, localToView, left, top, size.x, size.y);
                if (inputRouter.distanceOn(listener) < Float.MAX_VALUE) mouse = inputRouter.positionOn(listener);
            }
            int mouseX = mouse == null ? -1 : (int) Math.floor(mouse.x);
            int mouseY = mouse == null ? -1 : (int) Math.floor(mouse.y);
            if (size != null && size.x > 0 && size.y > 0) {
                int left = renderable instanceof LayoutElement element ? element.getX() : 0;
                int top = renderable instanceof LayoutElement element ? element.getY() : 0;
                canvases.computeIfAbsent(renderable, key -> new WorldUiCanvas())
                        .render(gui, renderable, left, top, size.x, size.y, mouseX, mouseY, partialTicks);
            } else renderable.render(gui, mouseX, mouseY, partialTicks);
        } finally {
            pose.popPose();
            if (blur != null) blur.ksg$SetBlurEnabled(previousBlur);
        }
        return Pair.of(mouse == null ? new Vector2f(-1, -1) : mouse,
                renderable instanceof GuiEventListener listener ? inputRouter.distanceOn(listener) : Float.MAX_VALUE);
    }

    public static @Nullable Vector2i getRenderableSize(Renderable renderable) {
        if (renderable instanceof Screen screen) return new Vector2i(screen.width, screen.height);
        if (renderable instanceof LayoutElement element) return new Vector2i(element.getWidth(), element.getHeight());
        return null;
    }

    public void resetInput() {
        keyboardCapture.cancel();
        inputRouter.reset();
        for (UIInstanceData data : interactData.values()) data.getOperators().clear();
        currentCallbackData = null;
    }

    /** Drop GPU canvases at a world boundary; callers retain ownership of panel registrations. */
    public void worldChanged() {
        resetInput();
        for (WorldUiCanvas canvas : List.copyOf(canvases.values())) canvas.close();
        canvases.clear();
    }

    @Override public void close() {
        worldChanged();
        renderables.clear();
        interactData.clear();
        transforms.clear();
    }

    // Compatibility entry points. Mouse coordinates here are GUI pixels, never world-UI-local pixels.
    public void mouseMoved(Entity operator, long window, double xPos, double yPos) { inputRouter.move(xPos, yPos); }

    public void mouseClicked(Entity operator, long window, int button, int action, int mods) {
        Minecraft mc = Minecraft.getInstance();
        double x = mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth();
        double y = mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight();
        if (Minecraft.ON_OSX && button == 0 && (mods & GLFW.GLFW_MOD_CONTROL) != 0) button = 1;
        if (action == GLFW.GLFW_PRESS) inputRouter.press(x, y, button);
        else if (action == GLFW.GLFW_RELEASE) inputRouter.release(x, y, button);
        refreshKeyboardCapture();
    }

    public void mouseScrolled(Entity operator, long window, double xOffset, double yOffset) {
        Minecraft mc = Minecraft.getInstance();
        inputRouter.scroll(mc.mouseHandler.xpos() * mc.getWindow().getGuiScaledWidth() / mc.getWindow().getScreenWidth(),
                mc.mouseHandler.ypos() * mc.getWindow().getGuiScaledHeight() / mc.getWindow().getScreenHeight(), xOffset, yOffset);
    }

    public void mouseDragged(Entity operator, long window, int count, long names) {
        // Local deltas are calculated from projected positions by mouseMoved/renderAllUis.
    }
}
