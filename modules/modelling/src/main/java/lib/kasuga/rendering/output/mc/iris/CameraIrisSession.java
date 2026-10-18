package lib.kasuga.rendering.output.mc.iris;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.mixins.modelling.WorldViewIrisCounterAccessor;
import lib.kasuga.mixins.modelling.WorldViewIrisStateAccessor;
import lib.kasuga.mixins.modelling.WorldViewIrisTimeAccessor;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.VanillaRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import java.io.IOException;
import java.nio.file.*;

/** Optional Iris adapter. Each instance owns its pack, pipelines, uniforms and material map. */
public final class CameraIrisSession implements AutoCloseable {
    private static final ThreadLocal<CameraIrisSession> ACTIVE = new ThreadLocal<>();
    private static final ThreadLocal<WorldRenderingSettings> MATERIALS = new ThreadLocal<>();
    private final WorldRenderingSettings materials = new WorldRenderingSettings();
    private final CapturedRenderingState captured = WorldViewIrisStateAccessor.kasuga$create();
    private final SystemTimeUniforms.Timer timer = new SystemTimeUniforms.Timer();
    private final SystemTimeUniforms.FrameCounter counter = WorldViewIrisCounterAccessor.kasuga$create();
    private final ShaderPack pack;
    private final String packName;
    private final PipelineManager pipelines;
    private FileSystem archive;
    private CameraIrisShadowState shadows = CameraIrisShadowState.initial();
    private boolean closed;

    public CameraIrisSession(CameraRenderSettings.Shader shader) throws IOException {
        RenderSystem.assertOnRenderThread();
        Path path = shader.pack();
        if (path == null) {
            pack = null; packName = "(camera shaders disabled)";
        } else {
            path = path.toAbsolutePath().normalize();
            packName = path.getFileName().toString();
            try {
                Path root;
                if (Files.isDirectory(path)) root = path;
                else {
                    // Path overload creates a separate filesystem even when another camera uses this zip.
                    archive = FileSystems.newFileSystem(path);
                    root = archive.getPath("/");
                }
                Path shaders = root.resolve("shaders");
                if (!Files.isDirectory(shaders)) {
                    try (var children = Files.list(root)) {
                        shaders = children.filter(Files::isDirectory).map(p -> p.resolve("shaders"))
                                .filter(Files::isDirectory).findFirst()
                                .orElseThrow(() -> new IOException("Shader pack has no shaders directory: " + shader.pack()));
                    }
                }
                pack = new ShaderPack(shaders, shader.options(), StandardMacros.createStandardEnvironmentDefines(), false);
            } catch (IOException | RuntimeException failure) {
                if (archive != null) try { archive.close(); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                throw failure;
            }
        }
        pipelines = new PipelineManager(dimension -> pack == null ? new VanillaRenderingPipeline()
                : new IrisRenderingPipeline(pack.getProgramSet(dimension)));
    }
    public static CameraIrisSession current() { return ACTIVE.get(); }
    public static WorldRenderingSettings currentMaterials() { return MATERIALS.get(); }
    public PipelineManager pipelines() { return pipelines; }
    public ShaderPack pack() { return pack; }
    public String packName() { return packName; }

    /** Worker scopes only select materials. They never mutate process-wide rendering singletons. */
    public static AutoCloseable useMaterials(WorldRenderingSettings settings) {
        var previous = MATERIALS.get();
        if (settings == null) MATERIALS.remove(); else MATERIALS.set(settings);
        return () -> { if (previous == null) MATERIALS.remove(); else MATERIALS.set(previous); };
    }

    public Scope enter() {
        RenderSystem.assertOnRenderThread();
        if (closed) throw new IllegalStateException("Camera Iris session closed");
        return new Scope();
    }
    public final class Scope implements AutoCloseable {
        private final CameraIrisSession previous = ACTIVE.get();
        private final CameraIrisShadowState previousShadows = CameraIrisShadowState.capture();
        private final WorldRenderingSettings previousMaterials = MATERIALS.get();
        private final CapturedRenderingState previousCaptured = CapturedRenderingState.INSTANCE;
        private final SystemTimeUniforms.Timer previousTimer = SystemTimeUniforms.TIMER;
        private final SystemTimeUniforms.FrameCounter previousCounter = SystemTimeUniforms.COUNTER;
        private boolean restored;
        private Scope() {
            ProgramUniforms.clearActiveUniforms();
            ACTIVE.set(CameraIrisSession.this); MATERIALS.set(materials); shadows.install();
            WorldViewIrisStateAccessor.kasuga$setInstance(captured);
            WorldViewIrisTimeAccessor.kasuga$setTimer(timer);
            WorldViewIrisTimeAccessor.kasuga$setCounter(counter);
        }
        public void beginFrame() { counter.beginFrame(); timer.beginFrame(net.minecraft.Util.getNanos()); }
        @Override public void close() {
            if (restored) return;
            restored = true;
            shadows = CameraIrisShadowState.capture(); previousShadows.install();
            ProgramUniforms.clearActiveUniforms();
            WorldViewIrisStateAccessor.kasuga$setInstance(previousCaptured);
            WorldViewIrisTimeAccessor.kasuga$setTimer(previousTimer);
            WorldViewIrisTimeAccessor.kasuga$setCounter(previousCounter);
            if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous);
            if (previousMaterials == null) MATERIALS.remove(); else MATERIALS.set(previousMaterials);
        }
    }
    @Override public void close() throws IOException {
        RenderSystem.assertOnRenderThread();
        if (closed) return;
        // Destruction can call Iris helpers; run it under the owning session too.
        try (var ignored = enter()) { pipelines.destroyPipeline(); }
        finally { closed = true; if (archive != null) archive.close(); }
    }
}
