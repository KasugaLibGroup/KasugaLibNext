package lib.kasuga.rendering.output.mc;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
/** Captured by terrain tasks; workers must not read the render thread's mutable active registration. */
public final class CameraRenderContext implements AutoCloseable {
    private static final ThreadLocal<CameraRenderSettings> CURRENT = new ThreadLocal<>();
    private final CameraRenderSettings previous = CURRENT.get();
    public static CameraRenderSettings current() { return CURRENT.get(); }
    public CameraRenderContext(CameraRenderSettings settings) {
        if (settings == null) CURRENT.remove(); else CURRENT.set(settings);
    }
    @Override public void close() { if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
}
