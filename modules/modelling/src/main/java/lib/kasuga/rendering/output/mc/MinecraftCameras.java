package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.FrameTexture;
import lib.kasuga.rendering.output.OutputFrame;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraHandle;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.camera.OwnedCamera;
import lib.kasuga.rendering.output.camera.CameraSource;
import lib.kasuga.rendering.output.camera.CameraTarget;
import lib.kasuga.rendering.output.camera.CameraProjection;
import lib.kasuga.rendering.output.camera.CameraFollowSettings;
import lib.kasuga.mixins.modelling.PlayerCameraAccessor;
import net.minecraft.world.entity.Entity;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** One-call camera creation; the returned handle owns both the producer and its output. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class MinecraftCameras {
    private static final LinkedHashMap<String, OwnedCamera> CAMERAS = new LinkedHashMap<>();
    private static float playerFov = Float.NaN;
    private MinecraftCameras() {}

    /** Player output shares the native final frame and all of the player's current rendering settings. */
    public static CameraHandle createPlayer(Consumer<OutputFrame<FrameTexture>> consumer) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(consumer);
        String viewId = MinecraftFrameOutputs.MAIN_VIEW;
        if (CAMERAS.containsKey(viewId)) throw new IllegalArgumentException("Duplicate camera: " + viewId);
        OwnedCamera[] owner = new OwnedCamera[1];
        var output = new PlayerOutput(frame -> {
            try { consumer.accept(frame); }
            catch (RuntimeException failure) { owner[0].fail(failure); throw failure; }
        });
        var camera = new OwnedCamera(viewId, new CameraSource.Player(MinecraftCameras::playerPose),
                output, output, RenderSystem::assertOnRenderThread, () -> CAMERAS.remove(viewId, owner[0]));
        owner[0] = camera;
        CAMERAS.put(viewId, camera);
        return camera;
    }

    public static CameraHandle createFree(String viewId, WorldCameraView pose, CameraRenderSettings settings,
                                          Consumer<OutputFrame<FrameTexture>> consumer) {
        Objects.requireNonNull(pose);
        return createFree(viewId, () -> pose, settings, consumer);
    }
    public static CameraHandle createFree(String viewId, Supplier<WorldCameraView> pose, CameraRenderSettings settings,
                                          Consumer<OutputFrame<FrameTexture>> consumer) {
        return createDetached(viewId, new CameraSource.Free(pose), settings, consumer);
    }
    public static CameraHandle createFree(String viewId, WorldCameraView pose, Consumer<OutputFrame<FrameTexture>> consumer) {
        return createFree(viewId, pose, CameraRenderSettings.defaults(), consumer);
    }
    public static CameraHandle createFree(String viewId, Supplier<WorldCameraView> pose, Consumer<OutputFrame<FrameTexture>> consumer) {
        return createFree(viewId, pose, CameraRenderSettings.defaults(), consumer);
    }

    public static CameraHandle createFixed(String viewId, Entity entity, CameraProjection projection,
                                           CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer) {
        return createFixed(viewId, MinecraftCameraTargets.entity(entity), projection, CameraFollowSettings.defaults(), settings, consumer);
    }
    public static CameraHandle createFixed(String viewId, Supplier<? extends Entity> entity, CameraProjection projection,
                                           CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer) {
        return createFixed(viewId, MinecraftCameraTargets.entity(entity), projection, CameraFollowSettings.defaults(), settings, consumer);
    }
    public static CameraHandle createFixed(String viewId, CameraTarget target, CameraProjection projection,
                                           CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer) {
        return createFixed(viewId, target, projection, CameraFollowSettings.defaults(), settings, consumer);
    }
    public static CameraHandle createFixed(String viewId, CameraTarget target, CameraProjection projection,
                                           CameraFollowSettings follow, CameraRenderSettings settings,
                                           Consumer<OutputFrame<FrameTexture>> consumer) {
        return createDetached(viewId, new CameraSource.Fixed(target, projection, follow), settings, consumer);
    }

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
        return createFree(viewId, pose, settings, consumer);
    }

    private static CameraHandle createDetached(String viewId, CameraSource source,
                                               CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(source); Objects.requireNonNull(consumer);
        if (CAMERAS.containsKey(viewId)) throw new IllegalArgumentException("Duplicate camera: " + viewId);
        OwnedCamera[] owner = new OwnedCamera[1];
        var producer = MinecraftWorldViews.registerWhenAvailable(viewId,
                () -> owner[0].sampleAvailablePose(Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true)).orElse(null), settings);
        try {
            var output = MinecraftFrameOutputs.open(viewId, FrameOutputMode.OFFSCREEN_ONLY, frame -> {
                try { consumer.accept(frame); }
                catch (RuntimeException failure) { owner[0].fail(failure); throw failure; }
            });
            var camera = new OwnedCamera(viewId, source, new OwnedCamera.Producer() {
                public void setEnabled(boolean enabled) { producer.setEnabled(enabled); }
                public boolean isClosed() { return producer.isClosed(); }
                public java.util.Optional<Throwable> failure() { return producer.failure(); }
                public java.util.Optional<CameraRenderSettings> renderSettings() { return java.util.Optional.of(producer.renderSettings()); }
                public void updateRenderSettings(CameraRenderSettings settings) { producer.updateRenderSettings(settings); }
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

    private static WorldCameraView playerPose(float partialTick) {
        var mc = Minecraft.getInstance();
        var access = (PlayerCameraAccessor) mc.gameRenderer;
        var camera = access.kasuga$playerCamera();
        var position = camera.getPosition();
        float fov = Float.isNaN(playerFov) ? mc.options.fov().get() : playerFov;
        return new WorldCameraView(position.x, position.y, position.z, camera.getYRot(), camera.getXRot(), camera.getRoll(),
                Math.clamp(fov, Math.nextUp(0f), Math.nextDown(180f)),
                Math.max(1, mc.getWindow().getWidth()), Math.max(1, mc.getWindow().getHeight()));
    }

    /** Mixin entry: retain the actual native projection FOV without firing the host's FOV event twice. */
    public static void capturePlayerFov(double fov) { playerFov = (float) fov; }

    /** Pausing removes this subscription; it leaves the player's normal presentation running. */
    private static final class PlayerOutput implements OwnedCamera.Producer, OwnedCamera.Output {
        private final Consumer<OutputFrame<FrameTexture>> consumer;
        private lib.kasuga.rendering.output.FrameOutputRouter<FrameTexture>.Registration subscription;
        private boolean closed;
        PlayerOutput(Consumer<OutputFrame<FrameTexture>> consumer) { this.consumer = consumer; setEnabled(true); }
        public void setEnabled(boolean enabled) {
            if (closed) throw new IllegalStateException("Player output closed");
            if (enabled) {
                if (subscription == null) subscription = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, consumer);
            } else if (subscription != null) {
                var previous = subscription;
                subscription = null;
                try { previous.close(); } catch (Exception failure) { throw new IllegalStateException("Cannot pause player output", failure); }
            }
        }
        public boolean isClosed() { return closed || (subscription != null && subscription.isClosed()); }
        public void close() throws Exception {
            if (closed) return;
            closed = true;
            if (subscription != null) { var previous = subscription; subscription = null; previous.close(); }
        }
    }

    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || mc.isPaused()) return;
        for (var camera : CAMERAS.values().toArray(OwnedCamera[]::new)) camera.tick(1f / 20f);
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
