package lib.kasuga.scene;

import lib.kasuga.KasugaLib;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import org.lwjgl.glfw.GLFW;

import java.util.HashMap;
import java.util.Map;
import java.util.function.DoubleConsumer;
import java.util.function.IntConsumer;

/** Single scoped immersive input profile. Vanilla movement, camera and Escape remain available. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class SceneControls implements AutoCloseable {
    private static SceneControls active;
    private final Object level;
    private final boolean previousGui;
    private final Map<Integer, Runnable> keys = new HashMap<>();
    private IntConsumer mouse = ignored -> {};
    private DoubleConsumer scroll = ignored -> {};
    private boolean closed;

    private SceneControls() {
        var mc = Minecraft.getInstance(); level = mc.level; previousGui = mc.options.hideGui;
        mc.options.hideGui = true; KeyMapping.releaseAll();
    }
    public static SceneControls open() {
        if (active != null) active.close();
        return active = new SceneControls();
    }
    public SceneControls key(int key, Runnable pressed) { keys.put(key, pressed); return this; }
    /** Receives GLFW_PRESS/RELEASE for the right mouse button; original interaction is consumed. */
    public SceneControls mouse(IntConsumer action) { mouse = action; return this; }
    public SceneControls scroll(DoubleConsumer action) { scroll = action; return this; }
    public static boolean active() { return active != null; }

    /** Called by the existing Kasuga keyboard HEAD hook, before vanilla hotkeys change state. */
    public static boolean intercept(long window, int key, int action) {
        var mc = Minecraft.getInstance();
        var controls = active;
        if (controls == null || controls.closed || mc.level != controls.level || mc.screen != null
                || !mc.isWindowActive() || window != mc.getWindow().getWindow()) return false;
        var pressed = controls.keys.get(key);
        if (pressed != null) { if (action == GLFW.GLFW_PRESS) pressed.run(); return true; }
        return key == GLFW.GLFW_KEY_E || key == GLFW.GLFW_KEY_Q || key == GLFW.GLFW_KEY_F1
                || key == GLFW.GLFW_KEY_F5 || key == GLFW.GLFW_KEY_F || key >= GLFW.GLFW_KEY_1 && key <= GLFW.GLFW_KEY_9;
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void mouse(InputEvent.MouseButton.Pre event) {
        var mc = Minecraft.getInstance();
        if (active == null || mc.level != active.level || mc.screen != null) return;
        if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) active.mouse.accept(event.getAction());
        event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST) public static void wheel(InputEvent.MouseScrollingEvent event) {
        if (active != null && Minecraft.getInstance().screen == null) {
            active.scroll.accept(event.getScrollDeltaY()); event.setCanceled(true);
        }
    }
    @SubscribeEvent public static void hand(RenderHandEvent event) { if (active != null) event.setCanceled(true); }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (active != null && Minecraft.getInstance().level != active.level) active.close();
    }
    @Override public void close() {
        if (closed) return;
        closed = true; Minecraft.getInstance().options.hideGui = previousGui;
        KeyMapping.releaseAll(); if (active == this) active = null;
    }
}
