package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.mc.backend.BackendInstance;
import lib.kasuga.rendering.models.uml.framework.schedule.ModelRenderScheduling;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.gl.FramebufferScope;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.function.Supplier;

/** Sequential detached world views, rendered before the ordinary main view. */
public final class MinecraftWorldViews {
    private static final LinkedHashMap<String, Registration> VIEWS = new LinkedHashMap<>();
    private static Registration active;
    private static Object frameToken;
    private static boolean lightUpdated;
    private static boolean shutdown;

    private MinecraftWorldViews() {}

    /** Register a producer; attach consumers with MinecraftFrameOutputs.open(viewId, ...). */
    public static Registration register(String viewId, Supplier<WorldCameraView> camera) {
        return register(viewId, camera, CameraRenderSettings.defaults());
    }

    public static Registration register(String viewId, Supplier<WorldCameraView> camera, CameraRenderSettings settings) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(settings);
        Objects.requireNonNull(viewId, "viewId");
        Objects.requireNonNull(camera, "camera");
        if (shutdown) throw new IllegalStateException("World views shut down");
        if (viewId.isBlank() || viewId.equals(MinecraftFrameOutputs.MAIN_VIEW))
            throw new IllegalArgumentException("Use a non-main view ID");
        if (VIEWS.containsKey(viewId)) throw new IllegalArgumentException("Duplicate view: " + viewId);
        Registration registration = new Registration(viewId, camera, settings);
        VIEWS.put(viewId, registration);
        return registration;
    }

    public static CameraRenderSettings currentSettings() {
        var context = CameraRenderContext.current();
        if (context != null) return context;
        Registration view = active;
        return RenderSystem.isOnRenderThread() && view != null ? view.settings : null;
    }
    public static WorldCameraView currentView() { return active == null ? null : active.view; }
    public static Camera currentCamera() { return active == null ? null : active.camera; }
    public static RenderTarget currentTarget() { return active == null ? null : active.target; }
    public static String currentViewId() { return active == null ? MinecraftFrameOutputs.MAIN_VIEW : active.id; }
    /** Shared across all world views in one host frame; null outside a multi-view frame. */
    public static Object currentFrameToken() { return frameToken; }

    /** Mixin entry. The ordinary world render always runs, including unsupported modes. */
    public static void renderViewsAndMain(GameRenderer renderer, DeltaTracker delta, Runnable main) {
        RenderSystem.assertOnRenderThread();
        Minecraft mc = Minecraft.getInstance();
        if (frameToken != null) throw new IllegalStateException("Recursive world frame");
        if (VIEWS.values().stream().noneMatch(v -> !v.closed && v.enabled && MinecraftFrameOutputs.hasOutputs(v.id))) {
            main.run();
            return;
        }
        frameToken = new Object();
        lightUpdated = false;
        try {
            for (Registration registration : VIEWS.values().toArray(Registration[]::new)) {
                if (registration.closed || !registration.enabled || !MinecraftFrameOutputs.hasOutputs(registration.id)) continue;
                WorldCameraView view;
                try { view = Objects.requireNonNull(registration.provider.get(), "Camera provider returned null"); }
                catch (RuntimeException failure) {
                    registration.failure = failure;
                    registration.close();
                    LogUtils.getLogger().error("Camera provider failed and was detached for {}", registration.id, failure);
                    continue;
                }
                if (registration.closed) continue;
                try (var ignored = new WorldRenderScope()) {
                    try { registration.resize(view); }
                    catch (RuntimeException failure) {
                        registration.failure = failure;
                        registration.close();
                        LogUtils.getLogger().error("Camera target failed and was detached for {}", registration.id, failure);
                        continue;
                    }
                    try {
                        registration.view = view;
                        active = registration;
                        registration.fog.install();
                        registration.target.bindWrite(true);
                        if (registration.session == null) registration.session = new WorldViewRenderSession(registration.settings);
                        if (!registration.session.ready()) continue;
                        try (var session = registration.session.enter(view)) {
                            ModelRenderScheduling.scheduler().clearFrameMarks();
                            var performance = lib.kasuga.rendering.output.profile.CameraRenderPassEvent.begin(registration.id, view.width(), view.height());
                            try { renderer.renderLevel(delta); session.finish(); }
                            finally { if (performance != null) performance.finish(mc.level.getChunkSource().getLoadedChunksCount()); }
                            registration.fog = WorldViewFog.capture();
                            // Keep the owning session active until borrowed-frame callbacks finish.
                            if (!registration.closed)
                                MinecraftFrameOutputs.publish(registration.id, view.width(), view.height(), registration.target::blitToScreen);
                        }
                    } catch (Exception failure) {
                        registration.failure = failure;
                        registration.close();
                        LogUtils.getLogger().error("Camera render session failed for {}", registration.id, failure);
                    } finally {
                        active = null;
                        if (registration.closed) registration.release();
                    }
                }
            }
            ModelRenderScheduling.scheduler().clearFrameMarks();
            main.run();
        } finally {
            active = null;
            frameToken = null;
        }
    }

    /** Update the light texture once for the entire world frame. */
    public static boolean shouldUpdateLight() {
        if (frameToken == null) return true;
        if (lightUpdated) return false;
        lightUpdated = true;
        return true;
    }

    public static Matrix4f projection(float farPlane) {
        WorldCameraView view = currentView();
        return new Matrix4f().perspective((float) Math.toRadians(view.verticalFov()), view.aspectRatio(), 0.05f, farPlane);
    }

    /** Drop world references and camera attachments on disconnect/dimension change; retain subscriptions. */
    public static void worldChanged() {
        RenderSystem.assertOnRenderThread();
        try (var ignored = new FramebufferScope(MinecraftFrameOutputs.restorer())) {
            for (Registration view : VIEWS.values()) {
                view.release();
                view.fog = WorldViewFog.initial();
            }
        }
    }

    public static void shutdown() {
        RenderSystem.assertOnRenderThread();
        shutdown = true;
        for (Registration view : VIEWS.values().toArray(Registration[]::new)) view.close();
    }

    public static final class Registration implements AutoCloseable {
        private final String id;
        private final Supplier<WorldCameraView> provider;
        private final CameraRenderSettings settings;
        private WorldViewRenderSession session;
        private final WorldViewCamera camera = new WorldViewCamera();
        private WorldCameraView view;
        private WorldViewFog fog = WorldViewFog.initial();
        private TextureTarget target;
        private boolean enabled = true;
        private boolean closed;
        private Throwable failure;

        private Registration(String id, Supplier<WorldCameraView> provider, CameraRenderSettings settings) {
            this.id = id; this.provider = provider; this.settings = settings;
        }
        public String viewId() { return id; }
        public boolean isClosed() { return closed; }
        public java.util.Optional<Throwable> failure() { return java.util.Optional.ofNullable(failure); }
        public boolean isEnabled() { return enabled && !closed; }
        public void setEnabled(boolean enabled) {
            RenderSystem.assertOnRenderThread();
            if (closed) throw new IllegalStateException("Camera closed");
            this.enabled = enabled;
            if (!enabled && session != null) session.pause();
        }

        private void resize(WorldCameraView view) {
            if (target != null && target.width == view.width() && target.height == view.height()) return;
            int maximum = GL11.glGetInteger(GL11.GL_MAX_TEXTURE_SIZE);
            if (view.width() > maximum || view.height() > maximum)
                throw new IllegalArgumentException("Camera exceeds GPU texture size: " + maximum);
            TextureTarget replacement = new TextureTarget(view.width(), view.height(), true, Minecraft.ON_OSX);
            TextureTarget previous = target;
            target = replacement;
            if (previous != null) previous.destroyBuffers();
        }

        private void configureCamera(float partialTick) {
            Minecraft mc = Minecraft.getInstance();
            camera.configure(mc.level, mc.getCameraEntity() == null ? mc.player : mc.getCameraEntity(), partialTick, view);
        }

        @Override public void close() {
            RenderSystem.assertOnRenderThread();
            if (closed) return;
            closed = true;
            VIEWS.remove(id, this);
            if (active != this) {
                try (var ignored = new FramebufferScope(MinecraftFrameOutputs.restorer())) {
                    release();
                }
            }
        }

        private void release() {
            if (session != null) {
                try { session.close(); }
                catch (Exception failure) { LogUtils.getLogger().error("Cannot release camera renderer {}", id, failure); }
                finally { session = null; }
            }
            if (target != null) { target.destroyBuffers(); target = null; }
            camera.reset();
        }
    }

    public static void configureCamera(float partialTick) { active.configureCamera(partialTick); }
}
