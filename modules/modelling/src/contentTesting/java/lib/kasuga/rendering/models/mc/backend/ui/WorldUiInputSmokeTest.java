package lib.kasuga.rendering.models.mc.backend.ui;

import com.google.gson.GsonBuilder;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.HeadlessClient;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;

/** Opt-in hidden real-client regression; requires an isolated quick-play world. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WorldUiInputSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testWorldUiInput");
    private static final long START = System.nanoTime();
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static Panel panel;
    private static int warmup, stage;
    private static byte[] baseline;
    private static boolean finished;
    private WorldUiInputSmokeTest() {}

    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        var mc = Minecraft.getInstance();
        if (System.nanoTime() - START > 120_000_000_000L) {
            finish(new IllegalStateException("World UI smoke timeout at stage " + stage)); return;
        }
        if (mc.level == null || mc.player == null || mc.getOverlay() != null || ++warmup < 60) return;
        try {
            check(HeadlessClient.enabled(), "Use the hidden client launcher");
            check(GLFW.glfwGetWindowAttrib(mc.getWindow().getWindow(), GLFW.GLFW_VISIBLE) == GLFW.GLFW_FALSE,
                    "Test window became visible");
            // Hidden windows do not receive OS focus. This affects only this isolated client.
            mc.setWindowActive(true);
            if (panel != null) return;
            mc.setScreen(null);
            panel = new Panel();
            panel.init(mc, 320, 180);
            var camera = mc.gameRenderer.getMainCamera();
            var center = camera.getPosition().add(camera.getLookVector().x * 2.5,
                    camera.getLookVector().y * 2.5, camera.getLookVector().z * 2.5);
            Constants.UI_BACKEND.addRenderable(panel, new Matrix4f()
                    .translation((float) center.x, (float) center.y, (float) center.z)
                    .rotate(camera.rotation()).scale(.006f, -.006f, 1).translate(-160, -90, 0));
        } catch (Exception failure) { finish(failure); }
    }

    @SubscribeEvent public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished || panel == null) return;
        try {
            if (stage == 0) {
                verifyKeyboard();
                panel.capture = true;
            } else if (panel.pixels != null) {
                byte[] pixels = panel.pixels;
                panel.pixels = null;
                if (stage == 1) { baseline = pixels; save("zero", pixels); panel.z = GuiGraphics.MIN_GUI_Z; }
                else if (stage == 2) {
                    check(Arrays.equals(baseline, pixels), "MIN_GUI_Z changes widget/font pixels");
                    save("min", pixels); panel.z = GuiGraphics.MAX_GUI_Z;
                } else {
                    check(Arrays.equals(baseline, pixels), "MAX_GUI_Z changes widget/font pixels");
                    save("max", pixels);
                    REPORT.put("guiDepthPixelsIdentical", true);
                    REPORT.put("testedGuiZ", new float[]{GuiGraphics.MIN_GUI_Z, 0, GuiGraphics.MAX_GUI_Z});
                    check(GL11.glGetError() == GL11.GL_NO_ERROR, "World UI GL error");
                    finish(null); return;
                }
                panel.capture = true;
            } else return;
            stage++;
        } catch (Exception failure) { finish(failure); }
    }

    private static void verifyKeyboard() throws Exception {
        var mc = Minecraft.getInstance();
        var backend = Constants.UI_BACKEND;
        boolean grabbed = mc.mouseHandler.isMouseGrabbed();
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
        check(mc.options.keyUp.isDown(), "Pre-focus gameplay key did not reach vanilla");
        var down = new InputEvent.MouseButton.Pre(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_PRESS, 0);
        UIBackend.onMouseClick(down);
        UIBackend.onMouseClick(new InputEvent.MouseButton.Pre(GLFW.GLFW_MOUSE_BUTTON_LEFT, GLFW.GLFW_RELEASE, 0));
        check(down.isCanceled() && panel.text.isFocused() && backend.isKeyboardCaptured(), "Crosshair did not focus text");
        key(GLFW.GLFW_KEY_S, GLFW.GLFW_PRESS);
        check(!mc.options.keyUp.isDown() && !mc.options.keyDown.isDown(), "Text key leaked into movement");
        var charTyped = KeyboardHandler.class.getDeclaredMethod("charTyped", long.class, int.class, int.class);
        charTyped.setAccessible(true);
        charTyped.invoke(mc.keyboardHandler, mc.getWindow().getWindow(), (int) 'w', 0);
        charTyped.invoke(mc.keyboardHandler, mc.getWindow().getWindow(), (int) '中', 0);
        check(panel.text.getValue().equals("w中"), "Native character hook did not edit the text field");
        key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS);
        check(!backend.isKeyboardCaptured() && !panel.text.isFocused() && mc.screen == null, "Escape did not leave text mode");
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_REPEAT);
        key(GLFW.GLFW_KEY_S, GLFW.GLFW_REPEAT);
        check(!mc.options.keyUp.isDown() && !mc.options.keyDown.isDown(), "Held repeat resumed movement after Escape");
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
        key(GLFW.GLFW_KEY_S, GLFW.GLFW_RELEASE);
        key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_RELEASE);
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
        check(mc.options.keyUp.isDown(), "Fresh gameplay press did not resume");
        key(GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
        check(!mc.options.keyUp.isDown() && mc.mouseHandler.isMouseGrabbed() == grabbed && mc.screen == null,
                "Keyboard capture changed mouse ownership or left a key held");
        REPORT.put("nativeKeyboardHooks", true);
        REPORT.put("crosshairFocus", true);
        REPORT.put("escapeAndHeldKeys", true);
        REPORT.put("mouseOwnershipUnchanged", true);
    }

    private static void key(int key, int action) {
        var mc = Minecraft.getInstance();
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, GLFW.glfwGetKeyScancode(key), action, 0);
    }

    private static void save(String name, byte[] rgba) throws Exception {
        var image = new BufferedImage(panel.pixelWidth, panel.pixelHeight, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++) {
            int p = (y * image.getWidth() + x) * 4;
            image.setRGB(x, y, (rgba[p + 3] & 255) << 24 | (rgba[p] & 255) << 16
                    | (rgba[p + 1] & 255) << 8 | rgba[p + 2] & 255);
        }
        var path = Minecraft.getInstance().gameDirectory.toPath().resolve("debug/world-ui-input/" + name + ".png");
        Files.createDirectories(path.getParent());
        ImageIO.write(image, "PNG", path.toFile());
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish(Exception failure) {
        if (finished) return;
        finished = true;
        var mc = Minecraft.getInstance();
        try {
            if (panel != null) Constants.UI_BACKEND.removeRenderable(panel);
            Constants.UI_BACKEND.resetInput();
            REPORT.put("passed", failure == null);
            REPORT.put("stage", stage);
            REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
            REPORT.put("failure", failure == null ? "" : failure.toString());
            var path = mc.gameDirectory.toPath().resolve("debug/world-ui-input/report.json");
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("WORLD_UI_INPUT_SMOKE_PASS {}", path);
            else LogUtils.getLogger().error("WORLD_UI_INPUT_SMOKE_FAIL", failure);
        } catch (Exception cleanup) { LogUtils.getLogger().error("World UI smoke cleanup/report failed", cleanup); }
        mc.stop();
    }

    private static final class Panel extends Screen {
        private EditBox text;
        private float z;
        private boolean capture;
        private byte[] pixels;
        private int pixelWidth, pixelHeight;
        private Panel() { super(Component.literal("World UI regression")); }
        @Override protected void init() {
            addRenderableWidget(Button.builder(Component.literal("Button border"), button -> {})
                    .bounds(20, 45, 120, 20).build());
            text = addRenderableWidget(new EditBox(font, 110, 80, 100, 20, Component.literal("Text")));
        }
        @Override public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            gui.pose().pushPose();
            gui.pose().translate(0, 0, z);
            gui.fill(0, 0, width, height, 0xFF18232D);
            gui.drawString(font, "World UI / depth boundary", 20, 16, 0xFFFFFFFF);
            gui.drawString(font, "Chinese: 中文输入与阴影", 20, 120, 0xFF9DD8CE);
            super.render(gui, mouseX, mouseY, partialTick);
            gui.pose().popPose();
            if (capture) {
                gui.flush();
                int fbo = GL11.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING);
                int texture = GL30.glGetFramebufferAttachmentParameteri(GL30.GL_DRAW_FRAMEBUFFER,
                        GL30.GL_COLOR_ATTACHMENT0, GL30.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
                int[] viewport = new int[4];
                GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
                pixelWidth = viewport[2]; pixelHeight = viewport[3];
                pixels = RgbaReadback.copy(new FrameTexture(fbo, texture, pixelWidth, pixelHeight));
                capture = false;
            }
        }
        @Override public void renderBackground(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {}
    }
}
