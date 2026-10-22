package lib.kasuga.rendering.models.mc.backend.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.core.rendering.IBlurControl;
import lib.kasuga.rendering.models.mc.util.RayTracingHelper;
import lib.kasuga.structure.Pair;
import lombok.Getter;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.Input;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.*;
import org.lwjgl.glfw.GLFW;

import java.lang.Math;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@EventBusSubscriber(value = Dist.CLIENT)
public class UIBackend {

    public static GuiGraphics constructGuiGraphics(PoseStack stack) {
        GuiGraphics guiGraphics = new GuiGraphics(Minecraft.getInstance(),
                Minecraft.getInstance().renderBuffers().bufferSource());
        guiGraphics.pose().mulPose(stack.last().pose());
        return guiGraphics;
    }

    public static GuiGraphics constructGuiGraphics(@Nullable Vector3f pos,
                                                   @Nullable Quaternionf rot,
                                                   @Nullable Vector3f scale) {
        PoseStack p = new PoseStack();
        if (pos != null) {
            p.translate(pos.x(), pos.y(), pos.z());
        }
        if (rot != null) {
            p.mulPose(rot);
        }
        if (scale != null) {
            p.scale(scale.x(), scale.y(), scale.z());
        }
        return constructGuiGraphics(p);
    }

    public static void suppressZAxis(PoseStack stack) {
        stack.scale(1f, 1f, .1f / (GuiGraphics.MAX_GUI_Z - GuiGraphics.MIN_GUI_Z));
    }

    @Getter
    private final List<Renderable> renderables;

    @Getter
    private final Map<GuiEventListener, UIInstanceData> interactData;

    protected UIInstanceData currentCallbackData;
    /** Pressed button → listener that received the press, kept until the matching release. */
    private final Map<Integer, UIInstanceData> pressedCallbacks;

    /** Backend whose UI was rendered most recently; receives the client input events. */
    @Nullable
    private static UIBackend activeBackend;

    @SubscribeEvent
    public static void onMouseClick(InputEvent.MouseButton.Pre event) {
        UIBackend backend = activeBackend;
        if (backend == null || Minecraft.getInstance().screen != null) return;
        Entity player = Minecraft.getInstance().player;
        if (player == null) {
            backend.clearFocusState();
            return;
        }

        int button = event.getButton();
        if (event.getAction() == GLFW.GLFW_PRESS) {
            UIInstanceData target = backend.focusedData(player);
            if (target == null) return;
            if (target.operate(player, (op, dat) -> dat.mouseClicked(target.getListener(), button))) {
                backend.pressedCallbacks.put(button, target);
                event.setCanceled(true);
            }
        } else if (event.getAction() == GLFW.GLFW_RELEASE) {
            UIInstanceData target = backend.pressedCallbacks.remove(button);
            if (target == null) return;
            if (target.operate(player, (op, dat) -> dat.mouseReleased(target.getListener(), button))) {
                event.setCanceled(true);
            }
        }
    }

    @SubscribeEvent
    public static void onMouseScroll(InputEvent.MouseScrollingEvent event) {
        UIBackend backend = activeBackend;
        if (backend == null || Minecraft.getInstance().screen != null) return;
        Entity player = Minecraft.getInstance().player;
        if (player == null) {
            backend.clearFocusState();
            return;
        }
        UIInstanceData target = backend.focusedData(player);
        if (target == null) return;
        if (target.operate(player, (op, dat) ->
                dat.mouseScrolled(target.getListener(), event.getScrollDeltaX(), event.getScrollDeltaY()))) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onClientLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        UIBackend backend = activeBackend;
        if (backend != null) backend.clearFocusState();
    }

    /** Returns the focused listener, dropping state left over from a previous player entity. */
    private UIInstanceData focusedData(Entity player) {
        UIInstanceData data = currentCallbackData;
        if (data == null) return null;
        if (data.isFocused(player)) return data;
        clearFocusState();
        return null;
    }

    /** Drops focus and per-operator state so a stale UI cannot swallow input across sessions. */
    private void clearFocusState() {
        currentCallbackData = null;
        pressedCallbacks.clear();
        for (UIInstanceData data : interactData.values()) {
            data.clearOperators();
        }
    }

    public UIBackend() {
        this.renderables = new ArrayList<>();
        this.interactData = new HashMap<>();
        this.pressedCallbacks = new HashMap<>();
        currentCallbackData = null;
    }

    public void addRenderable(Renderable renderable) {
        if (this.renderables.contains(renderable)) return;
        if (renderable instanceof Screen screen && Minecraft.getInstance().screen == screen) {
            // A screen rendered in world space must not also stay the active HUD
            // screen, or vanilla renders the same instance a second time and the
            // translucent background is composited twice.
            Minecraft.getInstance().setScreen(null);
        }
        this.renderables.add(renderable);
        if (renderable instanceof GuiEventListener listener) {
            this.getInteractData().put(listener, new UIInstanceData(listener));
        }
    }

    public void removeRenderable(Renderable renderable) {
        this.renderables.remove(renderable);
        if (renderable instanceof GuiEventListener listener) {
            this.getInteractData().remove(listener);
        }
    }

    public boolean containsRenderable(Renderable renderable) {
        return this.renderables.contains(renderable);
    }

    public boolean containsListener(Renderable renderable) {
        return this.interactData.containsKey(renderable);
    }

    public int renderableSize() {
        return this.renderables.size();
    }

    public int listenerSize() {
        return this.interactData.size();
    }

    public void renderAllUis(GuiGraphics gui, float partialTicks) {
        activeBackend = this;
        float minDistance = Float.MAX_VALUE;
        Vector2f nearestMousePos = null;
        GuiEventListener currentListener = null;
        UIInstanceData lastCallbackData = currentCallbackData;
        UIInstanceData currentData = null;
        Entity player = Minecraft.getInstance().player;
        Screen activeScreen = Minecraft.getInstance().screen;
        for (Renderable renderable : new ArrayList<>(this.renderables)) {
            // The active screen is already drawn by vanilla in the HUD layer.
            if (renderable == activeScreen) continue;
            Pair<Vector2f, Float> posAndDist = renderRenderable(gui, partialTicks, renderable);
            if (renderable instanceof GuiEventListener listener) {
                UIInstanceData data = interactData.computeIfAbsent(listener,
                        k -> new UIInstanceData(listener));
                if (posAndDist.getSecond() < minDistance) {
                    minDistance = posAndDist.getSecond();
                    currentListener = listener;
                    currentData = data;
                    nearestMousePos = posAndDist.getFirst();
                }
            }
        }
        if (currentListener != null && player != null) {
            currentCallbackData = currentData;
            if (lastCallbackData != currentData && lastCallbackData != null) {
                lastCallbackData.removeFocused(player);
            }
            currentData.setFocused(player, nearestMousePos);
        } else if (currentCallbackData != null) {
            if (player != null) {
                currentCallbackData.removeFocused(player);
            }
            currentCallbackData = null;
        }
        // Submit the batched UI geometry while the caller's world projection and
        // model view are still active; deferring it to the HUD pass would project
        // the world-space vertices with the wrong matrices.
        gui.flush();
    }

    public Pair<Vector2f, Float> renderRenderable(GuiGraphics guiGraphics, float partialTicks,
                                   Renderable renderable) {
        Vector2i size = getRenderableSize(renderable);
        IBlurControl blurControl = renderable instanceof IBlurControl control ? control : null;
        boolean previousBlur = blurControl != null && blurControl.ksgB$isBlurEnabled();
        if (blurControl != null) {
            blurControl.ksg$SetBlurEnabled(false);
        }
        float distance = Float.MAX_VALUE;
        boolean mouseOutOfRange = false;
        Vector2f mousePos = new Vector2f();
        PoseStack pose = guiGraphics.pose();
        Camera camera = Minecraft.getInstance().gameRenderer.getMainCamera();
        Vec3 pos = camera.getPosition();
        Matrix4f poseMat =  new Matrix4f(pose.last().pose());
        pose.setIdentity();
        pose.translate(- pos.x(), - pos.y(), - pos.z());
        pose.mulPose(poseMat);
        pose.scale(1f, -1f, 1f);
        pose.pushPose();
        try {
            if (size == null) {
                renderable.render(guiGraphics, -1, -1, partialTicks);
            } else {
                int mouseX = -1, mouseY = -1;
                pose.scale(1f / (float) guiGraphics.guiHeight(), 1f / (float) guiGraphics.guiHeight(), 1f);
                // Center the pixel-space panel on the pose origin (the ray/plane anchor).
                pose.translate(-0.5f * size.x, -0.5f * size.y, 0);
                suppressZAxis(pose);
                Vector3f rayOrigin = new Vector3f(), rayDirection = new Vector3f(), dstHitPoint = new Vector3f();
                RayTracingHelper.getRayFromCamera(rayOrigin, rayDirection);
                // The pose is translated by -cameraPos, so the UI plane lives in
                // camera-relative world space. Move the world-space ray into that
                // same space; otherwise the plane is effectively mirrored to -camera.
                rayOrigin.sub((float) pos.x(), (float) pos.y(), (float) pos.z());
//                rayOrigin.add(0f, -0.05f, 0f);
                distance = RayTracingHelper.intersectRayWithPlane(
                        rayOrigin, rayDirection,
                        pose.last().pose(), pose.last().normal(),
                        dstHitPoint, true
                );
                if (distance >= 0f && distance < 5f) {
                    Matrix4f inv = new Matrix4f(pose.last().pose()).invert();
                    inv.transformPosition(dstHitPoint);
                    // The inverse already restores the renderable's pixel-space coordinates.
                    mouseX = Math.round(dstHitPoint.x);
                    mouseY = Math.round(dstHitPoint.y);
                }
                mouseOutOfRange =
                        mouseX < 0 || mouseY < 0 ||
                        mouseX >= size.x() || mouseY >= size.y();
                mousePos.set(mouseX, mouseY);
                renderable.render(guiGraphics, mouseX, mouseY, partialTicks);
            }
        } finally {
            pose.popPose();
            if (blurControl != null) {
                blurControl.ksg$SetBlurEnabled(previousBlur);
            }
        }
        return Pair.of(mousePos, mouseOutOfRange ? Float.MAX_VALUE : distance);
    }

    public static Vector2i getRenderableSize(Renderable renderable) {
        if (renderable instanceof Screen screen) {
            return new Vector2i(screen.width, screen.height);
        } else if (renderable instanceof LayoutElement element) {
            return new Vector2i(element.getWidth(), element.getHeight());
        } else {
            return null;
        }
    }

    public void mouseMoved(Entity operator, long window, double xPos, double yPos) {
        UIInstanceData target = currentCallbackData;
        if (target == null) return;
        target.operate(operator, (op, dat) -> {
            dat.updatePosition(new Vector2f((float) xPos, (float) yPos));
        });
    }

    public void mouseClicked(Entity operator, long window, int button, int action, int mods) {
        boolean press = action == GLFW.GLFW_PRESS;
        if (Minecraft.ON_OSX && button == 0 && press) {
            if ((mods & 2) == 2) {
                button = 1;
            }
        }
        final int fb = button;

        if (press) {
            UIInstanceData target = currentCallbackData;
            if (target == null) return;
            if (target.operate(operator, (op, dat) -> dat.mouseClicked(target.getListener(), fb))) {
                pressedCallbacks.put(fb, target);
            }
        } else if (action == GLFW.GLFW_RELEASE) {
            UIInstanceData target = pressedCallbacks.remove(fb);
            if (target != null) {
                target.operate(operator, (op, dat) -> dat.mouseReleased(target.getListener(), fb));
            }
        }
    }

    public void mouseScrolled(Entity operator, long window, double xOffset, double yOffset) {
        UIInstanceData target = currentCallbackData;
        if (target == null) return;
        target.operate(operator, (op, dat) -> {
            boolean flag = Minecraft.getInstance().options.discreteMouseScroll().get();
            double d0 = Minecraft.getInstance().options.mouseWheelSensitivity().get();
            double d1 = (flag ? Math.signum(xOffset) : xOffset) * d0;
            double d2 = (flag ? Math.signum(yOffset) : yOffset) * d0;
            dat.mouseScrolled(target.getListener(), d1, d2);
        });
    }

    public void mouseDragged(Entity operator, long window, int count, long names) {
        if (currentCallbackData == null) return;
        currentCallbackData.operate(operator, (op, dat) -> {

        });
    }
}
