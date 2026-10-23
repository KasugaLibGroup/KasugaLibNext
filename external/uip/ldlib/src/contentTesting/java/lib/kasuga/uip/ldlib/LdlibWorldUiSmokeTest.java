package lib.kasuga.uip.ldlib;

import com.google.gson.GsonBuilder;
import com.lowdragmc.lowdraglib2.gui.ui.UIElement;
import com.lowdragmc.lowdraglib2.gui.ui.rendering.GUIContext;
import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.mc.backend.ui.UIBackend;
import lib.kasuga.rendering.models.mc.backend.ui.WorldUiInputScreen;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Screenshot;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector2f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryStack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;

/** Opt-in real-world regression: actual LDLib2 widgets, world picking and native keyboard hooks. */
@EventBusSubscriber(modid = LdLibUiProvider.MOD_ID, value = Dist.CLIENT)
public final class LdlibWorldUiSmokeTest {
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.ldlibWorldSmoke");
    private static final long START = System.nanoTime();
    private static final LinkedHashMap<String, Object> REPORT = new LinkedHashMap<>();
    private static int stage;
    private static int frames;
    private static int finalFrames;
    private static boolean finished;
    private static Vector2f pointer;
    private static Matrix4f originalTransform;
    private static long titlePixels;
    private static int[] textBackground;

    private LdlibWorldUiSmokeTest() {}

    @SubscribeEvent
    public static void before(RenderFrameEvent.Pre event) {
        if (!ENABLED || finished) return;
        Minecraft.getInstance().setWindowActive(true);
        if (System.nanoTime() - START > 120_000_000_000L) finish(new IllegalStateException("World UI smoke timed out"));
    }

    @SubscribeEvent
    public static void after(RenderFrameEvent.Post event) {
        if (!ENABLED || finished) return;
        if (LdlibUiDemo.worldPanel == null || ++frames < 15) return;
        var mc = Minecraft.getInstance();
        var c = LdlibUiDemo.worldContents;
        var panel = LdlibUiDemo.worldPanel;
        try {
            switch (stage) {
                case 0 -> {
                    check(mc.level != null && mc.screen instanceof WorldUiInputScreen, "Missing world or pointer input layer");
                    check(panel.modularUI.getTickCounter() > 0, "World panel is not ticking");
                    check(!panel.modularUI.isTickWhileRending(), "World panel would tick during rendering too");
                    check(c.counter.getSizeWidth() > 0 && c.text.getSizeHeight() > 0, "LDLib2 layout is empty");
                    checkTextBackground("default");
                    LdlibSdfBlendSmokeTest.run();
                    REPORT.put("sdfBlendAndCompatibilityIsolation", true);
                    save("initial-world.png");
                    titlePixels = countTitlePixels();
                    check(titlePixels > 100, "World title has no readable glyph pixels");
                    move(c.counter);
                }
                case 1 -> {
                    click();
                    check(c.clicks == 1, "Projected button click missed: " + c.clicks);
                    REPORT.put("worldButtonClick", true);
                    move(c.toggle);
                }
                case 2 -> {
                    click();
                    check(!c.toggle.getValue(), "World switch did not toggle");
                    move(c.slider.scrollBar);
                }
                case 3 -> {
                    mc.screen.mouseClicked(pointer.x, pointer.y, 0);
                    move(c.slider.scrollContainer, .2f, .5f);
                }
                case 4 -> {
                    mc.screen.mouseReleased(pointer.x, pointer.y, 0);
                    check(Math.abs(c.slider.getValue() - .65f) > .1f, "Projected slider drag did not change its value");
                    check(Math.abs(c.slider.getValue() - c.progress.getValue()) < .001f, "Progress bar did not follow slider");
                    REPORT.put("worldSwitchAndDrag", true);
                    c.text.setText("");
                    move(c.text);
                }
                case 5 -> {
                    check(c.text.isSelfOrChildHover() && !c.text.isFocused(), "Expected hover without text focus");
                    save("hover-world.png");
                    checkTextBackground("hover");
                    click();
                    check(panel.hasTextInputFocus(), "LDLib2 field did not receive focus");
                    key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
                    character('w');
                    character('中');
                    key(GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
                    check(c.text.getValue().equals("w中"), "Pointer text input was lost: " + c.text.getValue());
                    check(!mc.options.keyUp.isDown(), "Pointer text input leaked into movement");
                    REPORT.put("pointerUnicodeInput", true);
                    move(theme("MC"));
                }
                case 6 -> {
                    check(c.text.isFocused() && !c.text.isSelfOrChildHover(), "Expected text focus without hover");
                    save("focused-world.png");
                    checkTextBackground("focused");
                    click();
                    check(c.theme.equals("MC") && c.clicks == 1 && c.text.getValue().equals("w中"), "Theme switch discarded widget state");
                }
                case 7 -> {
                    save("mc-theme-world.png");
                    move(theme("MODERN"));
                }
                case 8 -> {
                    click();
                    check(c.theme.equals("MODERN"), "MODERN theme did not return");
                    REPORT.put("worldThemeSwitch", true);
                    mc.setScreen(null);
                    c.text.setText("");
                    originalTransform = new Matrix4f(LdlibUiDemo.worldTransform);
                    float x = c.text.getPositionX() + c.text.getSizeWidth() * .5f;
                    float y = c.text.getPositionY() + c.text.getSizeHeight() * .5f;
                    LdlibUiDemo.worldTransform = new Matrix4f(originalTransform).translate(205 - x, 135 - y, 0);
                    Constants.UI_BACKEND.setTransform(panel, LdlibUiDemo.worldTransform);
                }
                case 9 -> { /* Let the world pipeline publish the moved picking surface and LDLib2 hover. */ }
                case 10 -> {
                    var down = new InputEvent.MouseButton.Pre(0, GLFW.GLFW_PRESS, 0);
                    UIBackend.onMouseClick(down);
                    UIBackend.onMouseClick(new InputEvent.MouseButton.Pre(0, GLFW.GLFW_RELEASE, 0));
                    check(down.isCanceled() && Constants.UI_BACKEND.isKeyboardCaptured(), "Crosshair did not capture LDLib2 text focus");
                    key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
                    character('w');
                    character('中');
                    check(c.text.getValue().equals("w中"), "Crosshair Unicode input failed: " + c.text.getValue());
                    check(!mc.options.keyUp.isDown(), "Crosshair typing moved the player");
                    key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_PRESS);
                    check(!Constants.UI_BACKEND.isKeyboardCaptured() && !panel.hasTextInputFocus()
                            && panel.modularUI.getFocusedElement() == null && mc.screen == null, "Escape left LDLib2 text focus behind");
                    key(GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
                    key(GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_RELEASE);
                    key(GLFW.GLFW_KEY_W, GLFW.GLFW_PRESS);
                    check(mc.options.keyUp.isDown(), "Fresh gameplay input did not resume");
                    key(GLFW.GLFW_KEY_W, GLFW.GLFW_RELEASE);
                    REPORT.put("crosshairUnicodeAndEscape", true);
                    LdlibUiDemo.worldTransform = new Matrix4f(originalTransform);
                    Constants.UI_BACKEND.setTransform(panel, originalTransform);
                    Constants.UI_BACKEND.openPointer();
                }
                case 11 -> {
                    // Give stylesheet/layout updates and glyph uploads time to settle after switching hosts.
                    if (++finalFrames < 30) return;
                }
                case 12 -> {
                    check(GL11.glGetError() == GL11.GL_NO_ERROR, "World LDLib2 rendering produced a GL error");
                    long finalTitlePixels = countTitlePixels();
                    REPORT.put("initialTitlePixels", titlePixels);
                    REPORT.put("finalTitlePixels", finalTitlePixels);
                    check(finalTitlePixels >= titlePixels * .95, "Theme switch lost world title glyph pixels: "
                            + finalTitlePixels + " / " + titlePixels);
                    var path = output();
                    try (var image = snapshot()) { image.writeToFile(path); }
                    REPORT.put("screenshot", path.toString());
                    REPORT.put("tickCount", panel.modularUI.getTickCounter());
                    LdlibUiDemo.closeWorld();
                    panel.close();
                    check(panel.isClosed() && !Constants.UI_BACKEND.containsRenderable(panel)
                            && panel.modularUI.getScreen() == null && panel.modularUI.getFocusedElement() == null,
                            "Closing world panel left a registration, screen host or text focus");
                    check(panel.modularUI.isTickWhileRending(), "Previous render-tick policy was not restored on close");
                    REPORT.put("idempotentCleanup", true);
                    finish(null);
                }
                default -> throw new IllegalStateException("Unexpected smoke stage " + stage);
            }
            stage++;
        } catch (Exception failure) {
            REPORT.put("hoveredWidget", panel.modularUI.getLastHoveredElement() == null ? "none"
                    : panel.modularUI.getLastHoveredElement().getElementName());
            finish(failure);
        }
    }

    private static UIElement theme(String name) {
        return LdlibUiDemo.worldContents.ui.ui.select("#theme-" + name).findFirst().orElseThrow();
    }

    /** Read the actual panel attachment before it is composited over the opaque world. */
    static void sampleTextBackground(GUIContext context, LdlibUiDemo.Contents contents) {
        if (!ENABLED || finished || contents != LdlibUiDemo.worldContents) return;
        context.graphics.flush();
        try (var stack = MemoryStack.stackPush()) {
            var viewport = stack.mallocInt(4);
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
            var field = contents.text;
            float x = field.getPositionX() + field.getSizeWidth() * .9f;
            float y = field.getPositionY() + field.getSizeHeight() * .5f;
            var pixel = stack.malloc(4);
            GL11.glReadPixels(viewport.get(0) + (int) (x * viewport.get(2) / context.graphics.guiWidth()),
                    viewport.get(1) + viewport.get(3) - 1 - (int) (y * viewport.get(3) / context.graphics.guiHeight()),
                    1, 1, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixel);
            textBackground = new int[]{pixel.get(0) & 255, pixel.get(1) & 255, pixel.get(2) & 255, pixel.get(3) & 255};
        }
    }

    private static void checkTextBackground(String state) {
        check(textBackground != null, "Text background was not sampled");
        REPORT.put(state + "TextBackgroundRgba", textBackground.clone());
        check(textBackground[3] == 255 && Math.abs(textBackground[0] - 30) <= 1
                        && Math.abs(textBackground[1] - 31) <= 1 && Math.abs(textBackground[2] - 34) <= 1,
                state + " text background changed or became transparent: " + java.util.Arrays.toString(textBackground));
    }

    private static void move(UIElement element) throws Exception { move(element, .5f, .5f); }

    private static void move(UIElement element, float fx, float fy) throws Exception {
        var mc = Minecraft.getInstance();
        pointer = project(element, fx, fy);
        var move = MouseHandler.class.getDeclaredMethod("onMove", long.class, double.class, double.class);
        move.setAccessible(true);
        move.invoke(mc.mouseHandler, mc.getWindow().getWindow(),
                pointer.x * mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth(),
                pointer.y * mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight());
    }

    private static Vector2f project(UIElement element, float fx, float fy) {
        var mc = Minecraft.getInstance();
        var camera = mc.gameRenderer.getMainCamera();
        // LDLib2 positions already include the root's centered offset in its host screen.
        var point = new Vector4f(element.getPositionX() + element.getSizeWidth() * fx,
                element.getPositionY() + element.getSizeHeight() * fy, 0, 1);
        LdlibUiDemo.worldTransform.transform(point);
        point.sub((float) camera.getPosition().x, (float) camera.getPosition().y, (float) camera.getPosition().z, 0);
        point.rotate(new Quaternionf(camera.rotation()).conjugate());
        mc.gameRenderer.getProjectionMatrix(mc.options.fov().get()).transform(point);
        return new Vector2f((point.x / point.w + 1) * .5f * mc.getWindow().getGuiScaledWidth(),
                (1 - point.y / point.w) * .5f * mc.getWindow().getGuiScaledHeight());
    }

    private static long countTitlePixels() {
        var mc = Minecraft.getInstance();
        var title = LdlibUiDemo.worldContents.ui.ui.select("#demo-title").findFirst().orElseThrow();
        var a = project(title, 0, 0);
        var b = project(title, 1, 1);
        long count = 0;
        try (var image = snapshot()) {
            float sx = (float) image.getWidth() / mc.getWindow().getGuiScaledWidth();
            float sy = (float) image.getHeight() / mc.getWindow().getGuiScaledHeight();
            int left = Math.max(0, (int) Math.floor(Math.min(a.x, b.x) * sx) - 2);
            int right = Math.min(image.getWidth(), (int) Math.ceil(Math.max(a.x, b.x) * sx) + 2);
            int top = Math.max(0, (int) Math.floor(Math.min(a.y, b.y) * sy) - 2);
            int bottom = Math.min(image.getHeight(), (int) Math.ceil(Math.max(a.y, b.y) * sy) + 2);
            for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
                int rgba = image.getPixelRGBA(x, y);
                if ((rgba & 255) >= 210 && (rgba >>> 8 & 255) >= 210 && (rgba >>> 16 & 255) >= 210) count++;
            }
        }
        return count;
    }

    private static void click() {
        var screen = Minecraft.getInstance().screen;
        screen.mouseClicked(pointer.x, pointer.y, 0);
        screen.mouseReleased(pointer.x, pointer.y, 0);
    }

    private static void key(int key, int action) {
        var mc = Minecraft.getInstance();
        mc.keyboardHandler.keyPress(mc.getWindow().getWindow(), key, GLFW.glfwGetKeyScancode(key), action, 0);
    }

    private static void character(char character) throws Exception {
        var mc = Minecraft.getInstance();
        var method = KeyboardHandler.class.getDeclaredMethod("charTyped", long.class, int.class, int.class);
        method.setAccessible(true);
        method.invoke(mc.keyboardHandler, mc.getWindow().getWindow(), (int) character, 0);
    }

    private static void save(String name) throws Exception {
        try (var image = snapshot()) {
            image.writeToFile(output().resolveSibling(name));
        }
    }

    private static NativeImage snapshot() {
        // Screenshot.takeScreenshot binds the target's texture on the active unit; do not leak it into world drawing.
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int binding = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            return Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
        } finally {
            GlStateManager._activeTexture(active);
            GlStateManager._bindTexture(binding);
        }
    }

    private static Path output() throws Exception {
        var path = Path.of(System.getProperty("kasuga.ldlibDemo.capture", "debug/ldlib-world-ui/world.png")).toAbsolutePath();
        Files.createDirectories(path.getParent());
        return path;
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static void finish(Exception failure) {
        if (finished) return;
        finished = true;
        try {
            if (failure != null) save("failed-world.png");
            REPORT.put("passed", failure == null);
            REPORT.put("stage", stage);
            REPORT.put("failure", failure == null ? "" : failure.toString());
            REPORT.put("renderer", GL11.glGetString(GL11.GL_RENDERER));
            Files.writeString(output().resolveSibling("world-report.json"), new GsonBuilder().setPrettyPrinting().create().toJson(REPORT));
            if (failure == null) LogUtils.getLogger().info("LDLIB_WORLD_UI_SMOKE_PASS {}", output());
            else LogUtils.getLogger().error("LDLIB_WORLD_UI_SMOKE_FAIL", failure);
        } catch (Exception reportingFailure) {
            LogUtils.getLogger().error("LDLib2 world UI smoke report failed", reportingFailure);
        } finally {
            LdlibUiDemo.closeWorld();
            Minecraft.getInstance().stop();
        }
    }
}
