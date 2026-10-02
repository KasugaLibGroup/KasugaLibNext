package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.OwnedCamera;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One-call camera creation; the returned handle owns both the producer and its output. */
public final class MinecraftCameras {
    private static final LinkedHashMap<String, OwnedCamera> CAMERAS = new LinkedHashMap<>();
    private MinecraftCameras() {}

    public static CameraHandle create(String viewId, WorldCameraView pose, Consumer<OutputFrame<FrameTexture>> consumer) {
        Objects.requireNonNull(pose);
        return create(viewId, () -> pose, consumer);
    }

    public static CameraHandle create(String viewId, Supplier<WorldCameraView> pose,
                                      Consumer<OutputFrame<FrameTexture>> consumer) {
        return create(viewId, pose, CameraRenderSettings.defaults(), consumer);
    }

    public static CameraHandle create(String viewId, WorldCameraView pose,
                                      CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer) {
        Objects.requireNonNull(pose);
        return create(viewId, () -> pose, settings, consumer);
    }

    public static CameraHandle create(String viewId, Supplier<WorldCameraView> pose,
                                      CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(pose); Objects.requireNonNull(consumer);
        if (CAMERAS.containsKey(viewId)) throw new IllegalArgumentException("Duplicate camera: " + viewId);
        OwnedCamera[] owner = new OwnedCamera[1];
        var producer = MinecraftWorldViews.register(viewId, () -> owner[0].samplePose(), settings);
        try {
            var output = MinecraftFrameOutputs.open(viewId, FrameOutputMode.OFFSCREEN_ONLY, frame -> {
                try { consumer.accept(frame); }
                catch (RuntimeException failure) { owner[0].fail(failure); throw failure; }
            });
            var camera = new OwnedCamera(viewId, pose, new OwnedCamera.Producer() {
                public void setEnabled(boolean enabled) { producer.setEnabled(enabled); }
                public boolean isClosed() { return producer.isClosed(); }
                public java.util.Optional<Throwable> failure() { return producer.failure(); }
                public void close() { producer.close(); }
            }, new OwnedCamera.Output() {
                public boolean isClosed() { return output.isClosed(); }
                public void close() throws Exception { output.close(); }
            }, RenderSystem::assertOnRenderThread, () -> CAMERAS.remove(viewId, owner[0]));
            owner[0] = camera;
            CAMERAS.put(viewId, camera);
            return camera;
        } catch (RuntimeException failure) {
            producer.close();
            throw failure;
        }
    }

    /** Close every camera before renderer/window teardown. */
    public static void shutdown() {
        RenderSystem.assertOnRenderThread();
        for (var camera : CAMERAS.values().toArray(OwnedCamera[]::new)) {
            try { camera.close(); }
            catch (Exception failure) { LogUtils.getLogger().error("Cannot release camera {}", camera.viewId(), failure); }
        }
    }
}
