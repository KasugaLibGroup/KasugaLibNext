package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.mixins.modelling.WorldViewMinecraftAccessor;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.iris.CameraIrisSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.neoforged.fml.ModList;
import java.io.IOException;
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.mc.stream.CameraChunkClient;
import net.minecraft.client.multiplayer.ClientLevel;

/** Render-thread ownership of one terrain renderer and its optional Iris session. */
final class WorldViewRenderSession implements AutoCloseable {
    private final RenderBuffers buffers;
    private LevelRenderer renderer;
    private CameraSceneRenderers scene;
    private final CameraIrisSession iris;
    private final CameraAssets assets;
    private CameraChunkClient.Session world;
    private final CameraRenderSettings settings;
    private boolean closed;

    WorldViewRenderSession(CameraRenderSettings settings) throws IOException {
        this.settings = settings;
        if (!ModList.get().isLoaded("iris") && settings.shader().pack() != null)
            throw new IllegalStateException("This camera requires Iris");
        iris = ModList.get().isLoaded("iris") ? new CameraIrisSession(settings.shader()) : null;
        CameraAssets acquired = null;
        try {
            acquired = CameraAssets.acquire(settings);
            buffers = new RenderBuffers(1);
            ((CameraBufferPool) buffers.sectionBufferPool()).kasuga$own();
            assets = acquired;
        } catch (RuntimeException failure) {
            if (acquired != null) acquired.close();
            if (iris != null) try { iris.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
    boolean ready() {
        if (!assets.ready()) return false;
        if (renderer == null) {
            scene = new CameraSceneRenderers(assets);
            renderer = new LevelRenderer(Minecraft.getInstance(), scene.entities, scene.blockEntities, buffers);
        }
        return true;
    }
    Scope enter(WorldCameraView view) {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Camera render session closed");
        return new Scope(view);
    }
    final class Scope implements AutoCloseable {
        private final Minecraft mc = Minecraft.getInstance();
        private final WorldViewMinecraftAccessor access = (WorldViewMinecraftAccessor) mc;
        private final LevelRenderer previousRenderer = mc.levelRenderer;
        private final ClientLevel previousLevel = mc.level;
        private final RenderBuffers previousBuffers = access.kasuga$getRenderBuffers();
        private final CameraIrisSession.Scope irisScope;
        private final CameraAssets.Scope assetScope;
        private final CameraRenderContext context;
        private final CameraSceneRenderers.Scope sceneScope;
        private boolean restored;
        private Scope(WorldCameraView view) {
            access.kasuga$setLevelRenderer(renderer);
            access.kasuga$setRenderBuffers(buffers);
            context = new CameraRenderContext(settings);
            assetScope = assets.enter();
            sceneScope = CameraSceneRenderers.use(scene);
            CameraIrisSession.Scope entered;
            try { entered = iris == null ? null : iris.enter(); }
            catch (RuntimeException failure) {
                sceneScope.close(); assetScope.close(); context.close();
                access.kasuga$setRenderBuffers(previousBuffers);
                access.kasuga$setLevelRenderer(previousRenderer);
                throw failure;
            }
            irisScope = entered;
            try {
                if (world == null && view != null) {
                    world = new CameraChunkClient.Session(previousLevel, renderer, settings.renderDistance());
                    renderer.setLevel(world.level);
                }
                if (view != null) world.update(view, previousLevel);
                if (world != null) mc.level = world.level;
                if (irisScope != null) irisScope.beginFrame();
            } catch (RuntimeException failure) { close(); throw failure; }
        }
        void finish() {
            if (iris != null) iris.pipelines().getPipeline().ifPresent(p -> p.finalizeGameRendering());
        }
        @Override public void close() {
            if (restored) return;
            restored = true;
            try { if (irisScope != null) irisScope.close(); }
            finally {
                sceneScope.close(); assetScope.close(); context.close();
                mc.level = previousLevel;
                access.kasuga$setRenderBuffers(previousBuffers);
                access.kasuga$setLevelRenderer(previousRenderer);
            }
        }
    }
    void pause() { if (world != null) world.pause(); }
    @Override public void close() throws IOException {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        IOException failure = null;
        Scope scope = null;
        try {
            try { scope = enter(null); } catch (Exception cause) { failure = cleanupFailure(failure, cause); }
            // Stop terrain workers first; borrowed vanilla builder packs are freed on return.
            if (renderer != null) {
                try { renderer.setLevel(null); } catch (Exception cause) { failure = cleanupFailure(failure, cause); }
                try { renderer.close(); } catch (Exception cause) { failure = cleanupFailure(failure, cause); }
            }
            if (world != null) try { world.close(); } catch (Exception cause) { failure = cleanupFailure(failure, cause); }
            if (iris != null) try { iris.close(); } catch (Exception cause) { failure = cleanupFailure(failure, cause); }
            try { assets.close(); } catch (Exception cause) { failure = cleanupFailure(failure, cause); }
        } finally {
            closed = true;
            if (scope != null) try { scope.close(); } catch (Exception cause) { failure = cleanupFailure(failure, cause); }
            try { CameraNativeResources.close(renderer, buffers); } catch (Exception cause) { failure = cleanupFailure(failure, cause); }
        }
        if (failure != null) throw failure;
    }
    private static IOException cleanupFailure(IOException first, Exception cause) {
        if (first == null) return new IOException("Cannot release camera render session", cause);
        first.addSuppressed(cause); return first;
    }
}
