package lib.kasuga.uip.ldlib;

import com.google.gson.GsonBuilder;
import com.lowdragmc.lowdraglib2.gui.holder.ModularUIScreen;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.lowdragmc.lowdraglib2.gui.ui.elements.Label;
import com.lowdragmc.lowdraglib2.gui.ui.style.StylesheetManager;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.GuiModelPreview;
import lib.kasuga.rendering.models.mc.backend.ui.GuiRenderScope;
import lib.kasuga.rendering.models.mc.backend.ui.ModelPreviewWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Opt-in previews using a real model published by model_loader, never a world-mounted model. */
@EventBusSubscriber(modid = LdLibUiProvider.MOD_ID, value = Dist.CLIENT)
public final class ModelPreviewDemo {
    private static final String TOOLKIT = System.getProperty("kasuga.modelDemo.toolkit");
    private static GuiModelPreview preview;
    private static LdElement world;
    private static boolean opened, finished;
    private static int frames, stage;
    private static int worldWarmup;
    private static long firstHash;
    private static UIElement rootElement, footerElement;
    private static long footerPixels;
    private static lib.kasuga.rendering.output.FrameTexture beforeResize;
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (TOOLKIT == null || finished) return;
        var mc = Minecraft.getInstance();
        mc.setWindowActive(true);
        if (opened || mc.getOverlay() != null) return;
        if (TOOLKIT.equals("world") ? mc.level == null : !(mc.screen instanceof TitleScreen)) return;
        if (TOOLKIT.equals("world") && (mc.player == null || ++worldWarmup < 90)) return;
        mc.options.guiScale().set(2);
        mc.options.pauseOnLostFocus = false;
        mc.resizeDisplay();
        preview = new GuiModelPreview(ResourceLocation.parse(System.getProperty("kasuga.modelDemo.model",
                "kasuga_lib:models/gltf/maribel.glb")), System.getProperty("kasuga.modelDemo.modelName"));
        opened = true;
        if (TOOLKIT.equals("vanilla")) mc.setScreen(new VanillaScreen());
        else {
            var root = new UIElement() {
                @Override public void drawBackgroundOverlay(GUIContext context) {
                    super.drawBackgroundOverlay(context);
                    sampleFooter(context);
                }
            }.addClass("panel_bg").layout(l -> l.width(360).height(300).paddingAll(12).gapAll(8));
            rootElement = root;
            root.addChild(new Label().setText("model_loader / " + TOOLKIT, false)
                    .textStyle(s -> s.font(ResourceLocation.withDefaultNamespace("default"))));
            root.addChild(new ModelPreviewElement(preview).layout(l -> l.flex(1).widthPercent(100)));
            footerElement = new Label().setText("glTF model / 独立模型预览", false)
                    .textStyle(s -> s.font(ResourceLocation.withDefaultNamespace("default")));
            root.addChild(footerElement);
            var ui = ModularUI.of(UI.of(root, StylesheetManager.MODERN));
            if (TOOLKIT.equals("world")) {
                var camera = mc.gameRenderer.getMainCamera();
                var center = camera.getPosition().add(camera.getLookVector().x * 2.5, camera.getLookVector().y * 2.5,
                        camera.getLookVector().z * 2.5).add(0, .5, 0);
                var transform = new Matrix4f().translation((float) center.x, (float) center.y, (float) center.z)
                        .rotate(camera.rotation()).scale(.005f, -.005f, 1).translate(-205, -175, 0);
                world = LdElement.attach(Constants.UI_BACKEND, ui, 410, 350, transform);
                mc.setScreen(null);
                Constants.UI_BACKEND.openPointer();
            } else mc.setScreen(new ModularUIScreen(ui, Component.literal("LDLib2 model preview")) {
                @Override public void removed() { modularUI.onRemoved(); modularUI.setScreen(null); super.removed(); }
                @Override public void renderBackground(GuiGraphics g, int x, int y, float dt) { g.fill(0, 0, width, height, 0xff17202c); }
            });
        }
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (TOOLKIT == null || finished || preview == null || System.getProperty("kasuga.modelDemo.capture") == null) return;
        try {
            if (++frames > 900) throw new IllegalStateException("Model preview timed out waiting for loaded geometry");
            if (preview.renderedFrames() < 15 || frames % 30 != 0) return;
            var frame = preview.currentFrame();
            if (frame == null) return;
            long hash = 1, pixels = 0;
            try (var ignored = new GuiRenderScope()) {
                var bytes = MemoryUtil.memAlloc(frame.width() * frame.height() * 4);
                try {
                    GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, frame.framebufferId());
                    GL11.glReadPixels(0, 0, frame.width(), frame.height(), GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, bytes);
                    for (int i = 0; i < bytes.limit(); i += 4) {
                        if ((bytes.get(i + 3) & 255) > 32) pixels++;
                        hash = hash * 31 + bytes.getInt(i);
                    }
                } finally { MemoryUtil.memFree(bytes); }
            }
            if (pixels < 100) throw new IllegalStateException("Model texture contains no geometry: " + pixels);
            REPORT.put("toolkit", TOOLKIT); REPORT.put("modelPixels", pixels);
            if (rootElement != null) {
                REPORT.put("rootBounds", java.util.List.of(rootElement.getPositionX(), rootElement.getPositionY(), rootElement.getSizeWidth(), rootElement.getSizeHeight()));
                REPORT.put("footerBounds", java.util.List.of(footerElement.getPositionX(), footerElement.getPositionY(), footerElement.getSizeWidth(), footerElement.getSizeHeight()));
            }
            if (stage == 0) { stage++; firstHash = hash; preview.setView(.8f, .25f, 1); return; }
            if (hash == firstHash) throw new IllegalStateException("Rotating the model did not change its pixels");
            REPORT.put("rotationChangesPixels", true);
            if (stage == 1) {
                stage++;
                beforeResize = frame;
                var mc = Minecraft.getInstance();
                mc.options.guiScale().set(3);
                mc.resizeDisplay();
                return;
            }
            if (frame.width() == beforeResize.width() && frame.height() == beforeResize.height())
                throw new IllegalStateException("Preview texture did not follow GUI scale resize");
            if (GL30.glIsFramebuffer(beforeResize.framebufferId()) || GL11.glIsTexture(beforeResize.textureId()))
                throw new IllegalStateException("Resizing retained the previous preview canvas");
            REPORT.put("resizeReleasesOldCanvas", true);
            if (rootElement != null) {
                REPORT.put("footerPixels", footerPixels);
                if (footerPixels < 20) throw new IllegalStateException("Model preview lost the following UI text: " + footerPixels);
            }
            finish(null);
        } catch (Exception failure) { finish(failure); }
    }

    private static void sampleFooter(GUIContext context) {
        if (footerElement == null || System.getProperty("kasuga.modelDemo.capture") == null) return;
        context.graphics.flush();
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        float sx = (float) viewport[2] / context.graphics.guiWidth(), sy = (float) viewport[3] / context.graphics.guiHeight();
        int x = (int) (footerElement.getPositionX() * sx), y = viewport[3] - (int) Math.ceil((footerElement.getPositionY() + footerElement.getSizeHeight()) * sy);
        int w = Math.max(1, (int) (footerElement.getSizeWidth() * sx)), h = Math.max(1, (int) Math.ceil(footerElement.getSizeHeight() * sy));
        var pixels = MemoryUtil.memAlloc(w * h * 4);
        try {
            GL11.glReadPixels(x, y, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            footerPixels = 0;
            for (int i = 0; i < pixels.limit(); i += 4) {
                if ((pixels.get(i) & 255) > 180 && (pixels.get(i + 1) & 255) > 180
                        && (pixels.get(i + 2) & 255) > 180 && (pixels.get(i + 3) & 255) > 180) footerPixels++;
            }
            REPORT.put("canvasViewport", java.util.List.of(viewport[0], viewport[1], viewport[2], viewport[3]));
        } finally { MemoryUtil.memFree(pixels); }
    }

    private static void finish(Exception failure) {
        finished = true;
        var mc = Minecraft.getInstance();
        try {
            var path = Path.of(System.getProperty("kasuga.modelDemo.capture")).toAbsolutePath();
            Files.createDirectories(path.getParent());
            try (var ignored = new GuiRenderScope(); var image = Screenshot.takeScreenshot(mc.getMainRenderTarget())) { image.writeToFile(path); }
            var last = preview.currentFrame();
            if (world != null) world.close();
            mc.setScreen(null);
            preview.close(); preview.close();
            boolean cleanup = preview.isClosed() && preview.currentFrame() == null
                    && (last == null || !GL30.glIsFramebuffer(last.framebufferId()) && !GL11.glIsTexture(last.textureId()));
            REPORT.put("cleanup", cleanup);
            if (!cleanup && failure == null) failure = new IllegalStateException("Preview canvas survived close");
            REPORT.put("passed", failure == null);
            REPORT.put("failure", failure == null ? "" : failure.toString());
            Files.writeString(path.resolveSibling("model-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure != null) LogUtils.getLogger().error("MODEL_UI_SMOKE_FAIL", failure);
            else LogUtils.getLogger().info("MODEL_UI_SMOKE_PASS {}", path);
        } catch (Exception e) { LogUtils.getLogger().error("Cannot write model preview report", e); }
        finally { mc.stop(); }
    }

    private static final class VanillaScreen extends Screen {
        private VanillaScreen() { super(Component.literal("Vanilla model preview")); }
        @Override protected void init() { addRenderableWidget(new ModelPreviewWidget((width - 300) / 2, (height - 240) / 2, 300, 240, preview)); }
        @Override public void render(GuiGraphics g, int x, int y, float dt) {
            g.fill(0, 0, width, height, 0xff17202c);
            super.render(g, x, y, dt);
            g.drawCenteredString(font, "model_loader / Vanilla / 独立模型预览", width / 2, (height - 240) / 2 - 16, -1);
        }
        @Override public void removed() { preview.close(); }
    }
}
