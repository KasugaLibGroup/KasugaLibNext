package lib.kasuga.rendering.output.mc;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.FrameOutputMode;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import org.lwjgl.glfw.GLFW;

/** Launch-time policy. Rendering and ticking remain on Minecraft's normal client thread. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class HeadlessClient {
    private static final String BACKEND = System.getProperty("kasuga.headless", "disabled");
    private static boolean initialized;
    private HeadlessClient() {}
    public static boolean enabled() { return !BACKEND.equals("disabled"); }
    public static boolean egl() { return BACKEND.equals("egl"); }
    public static String backend() { return BACKEND; }

    /** Called before glfwInit, and before any native window can be created. */
    public static void initializePlatform() {
        if (!enabled()) return;
        if (!BACKEND.equals("hidden") && !egl()) throw new IllegalArgumentException("Unknown kasuga.headless backend: " + BACKEND);
        if (egl()) {
            if (!System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("linux"))
                throw new IllegalStateException("The EGL headless backend requires Linux");
            if (System.getProperty("org.lwjgl.glfw.libname") == null)
                throw new IllegalStateException("EGL requires the pbuffer GLFW library; use scripts/run-headless-client.sh");
            GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_NULL);
        }
    }
    @SubscribeEvent public static void before(RenderFrameEvent.Pre event) {
        if (!enabled()) return;
        var mc = Minecraft.getInstance();
        if (!initialized) {
            // Keep a final-composition target alive even without a user consumer (including menus/loading).
            MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, frame -> {});
            mc.options.pauseOnLostFocus = false;
            initialized = true;
        }
    }
}
