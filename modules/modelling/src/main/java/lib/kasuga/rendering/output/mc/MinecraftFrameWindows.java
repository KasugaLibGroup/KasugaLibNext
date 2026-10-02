package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.CameraState;
import lib.kasuga.rendering.output.gl.FramePreviewWindow;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.function.Supplier;

/** Preview completed views in independent native windows; all operations belong to the MC render thread. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class MinecraftFrameWindows {
    private static final LinkedHashSet<WindowOutput> WINDOWS = new LinkedHashSet<>();
    private MinecraftFrameWindows() {}

    /** Closing this window only detaches its subscription; it does not own the source camera. */
    public static WindowOutput open(String viewId, FramePreviewWindow.Options options) {
        RenderSystem.assertOnRenderThread();
        var output = new WindowOutput(options);
        try {
            output.source = MinecraftFrameOutputs.open(viewId, FrameOutputMode.MIRROR, frame -> output.display.present(frame.resource()));
            WINDOWS.add(output); return output;
        } catch (RuntimeException failure) { output.display.close(); throw failure; }
    }

    /** Owns both camera and window. The provider's output size follows the window framebuffer, including DPI changes. */
    public static WindowCamera createCamera(String viewId, Supplier<WorldCameraView> pose,
                                             CameraRenderSettings settings, FramePreviewWindow.Options options) {
        RenderSystem.assertOnRenderThread();
        java.util.Objects.requireNonNull(pose, "pose");
        var output = new WindowCamera(options);
        try {
            output.camera = MinecraftCameras.create(viewId, () -> output.windowPose(pose.get()), settings,
                    frame -> output.display.present(frame.resource()));
            output.source = output.camera; WINDOWS.add(output); return output;
        } catch (RuntimeException failure) { output.display.close(); throw failure; }
    }
    @SubscribeEvent public static void pump(RenderFrameEvent.Pre event) {
        for (var output : WINDOWS.toArray(WindowOutput[]::new)) {
            try {
                if (output.display.closeRequested() || output.sourceClosed()) output.close();
            } catch (Exception failure) { LogUtils.getLogger().error("Cannot close camera preview window", failure); }
        }
    }
    public static void shutdown() {
        RenderSystem.assertOnRenderThread();
        for (var output : WINDOWS.toArray(WindowOutput[]::new)) {
            try { output.close(); } catch (Exception failure) { LogUtils.getLogger().error("Cannot release preview window", failure); }
        }
    }
    public static class WindowOutput implements AutoCloseable {
        protected final FramePreviewWindow display;
        protected AutoCloseable source;
        private boolean closed;
        private WindowOutput(FramePreviewWindow.Options options) {
            if (HeadlessClient.egl()) throw new IllegalStateException("Native preview windows require a display server; EGL headless exports through FBO consumers");
            long parent = Minecraft.getInstance().getWindow().getWindow();
            if (!net.neoforged.fml.loading.FMLConfig.getBoolConfigValue(net.neoforged.fml.loading.FMLConfig.ConfigValue.EARLY_WINDOW_CONTROL))
                display = new FramePreviewWindow(parent, options, 3, 2);
            else display = new FramePreviewWindow(parent, options);
        }
        public long windowHandle() { RenderSystem.assertOnRenderThread(); return display.handle(); }
        public int[] framebufferSize() { RenderSystem.assertOnRenderThread(); return display.framebufferSize(); }
        public long presentedFrames() { RenderSystem.assertOnRenderThread(); return display.presentations(); }
        public boolean isClosed() { RenderSystem.assertOnRenderThread(); return closed; }
        public void resize(int width, int height) { RenderSystem.assertOnRenderThread(); display.resize(width, height); }
        public void requestClose() { RenderSystem.assertOnRenderThread(); display.requestClose(); }
        protected boolean sourceClosed() {
            return source instanceof lib.kasuga.rendering.output.FrameOutputRouter<?>.Registration output && output.isClosed();
        }
        @Override public void close() throws Exception {
            RenderSystem.assertOnRenderThread();
            if (closed) return; closed = true; WINDOWS.remove(this);
            try { if (source != null) source.close(); } finally { display.close(); }
        }
    }
    public static final class WindowCamera extends WindowOutput implements CameraHandle {
        private CameraHandle camera;
        private WindowCamera(FramePreviewWindow.Options options) { super(options); }
        private WorldCameraView windowPose(WorldCameraView pose) {
            var size = framebufferSize();
            return new WorldCameraView(pose.x(), pose.y(), pose.z(), pose.yaw(), pose.pitch(), pose.roll(), pose.verticalFov(),
                    size[0] > 0 ? size[0] : pose.width(), size[1] > 0 ? size[1] : pose.height());
        }
        @Override protected boolean sourceClosed() { return camera.state() == CameraState.CLOSED || camera.state() == CameraState.FAILED; }
        public String viewId() { return camera.viewId(); }
        public CameraState state() { return camera.state(); }
        public Optional<Throwable> failure() { return camera.failure(); }
        public void updatePose(Supplier<WorldCameraView> pose) {
            java.util.Objects.requireNonNull(pose, "pose");
            camera.updatePose(() -> windowPose(pose.get()));
        }
        public void pause() { camera.pause(); }
        public void resume() { camera.resume(); }
    }
}
