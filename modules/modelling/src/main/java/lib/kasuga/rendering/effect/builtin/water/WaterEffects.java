package lib.kasuga.rendering.effect.builtin.water;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.PostProcessTargetPool;
import lib.kasuga.rendering.effect.post.graph.*;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.joml.Matrix4f;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Built-in clear cobalt liquid: depth-tested refraction, Beer-Lambert absorption and Fresnel sky reflection. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class WaterEffects {
    private static final Set<WaterSurface> SURFACES = new LinkedHashSet<>();
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE = PostProcessGraphTarget.managed(id("scene"));
    private static RenderShaderHandle shader;
    private static PostProcessGraph graph;
    private static final Matrix4f VIEW_PROJECTION = new Matrix4f(), INVERSE = new Matrix4f();
    private WaterEffects() {}

    public static void initialize(RenderPipelineRegistrar registrar) {
        shader = registrar.shader(RenderShaderDescriptor.builder(id("clear_surface"), DefaultVertexFormat.BLIT_SCREEN)
                .resource().build()).handle();
        graph = PostProcessGraph.builder(id("graph"))
                .target(PostProcessTargetDescriptor.builder(SCENE.id()).screenScale(1).useDepth(true)
                        .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR).build())
                .prepare((context, frame) -> {
                    var water = surface(context.world().level());
                    return shader.isReady() && water != null && water.renderEnabled();
                })
                .pass(PostProcessGraphPassDescriptor.builder(id("capture"), context -> {
                    context.copyColor(MAIN, SCENE, true); context.copyDepth(MAIN, SCENE);
                }).reads(MAIN).writes(SCENE).build())
                .pass(PostProcessGraphPassDescriptor.builder(id("surface"), WaterEffects::draw)
                        .reads(SCENE).writes(MAIN).build()).build();
        registrar.graph(graph, 800);
    }
    public static boolean ready() { return shader != null && shader.isReady(); }
    /** One unbounded water plane per level. Bed elevation must match the host's physical floor. */
    public static WaterSurface open(ClientLevel level, double centerX, float bedY, double centerZ,
                                     int resolution, float cellSize, float depth) {
        if (!ready()) throw new IllegalStateException("Built-in water shaders are not ready");
        if (surface(level) != null) throw new IllegalStateException("Level already owns a water surface");
        var surface = new WaterSurface(level, centerX, bedY, centerZ, resolution, cellSize, depth);
        SURFACES.add(surface); return surface;
    }
    public static int activeCount() { return SURFACES.size(); }
    static void remove(WaterSurface surface) {
        SURFACES.remove(surface);
        if (SURFACES.isEmpty() && graph != null) {
            Runnable release = () -> graph.allocatedTargetIds().forEach(PostProcessTargetPool.getInstance()::release);
            if (RenderSystem.isOnRenderThread()) release.run();
            else RenderSystem.recordRenderCall(release::run);
        }
    }
    private static WaterSurface surface(ClientLevel level) {
        return SURFACES.stream().filter(s -> s.level == level && !s.closed()).findFirst().orElse(null);
    }
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        for (var surface : List.copyOf(SURFACES)) {
            if (mc.level != surface.level) surface.close();
            else if (!mc.isPaused()) surface.tick();
        }
    }
    private static void draw(PostProcessGraphContext context) {
        var world = context.world();
        var water = surface(world.level());
        if (water == null || !water.renderEnabled()) return;
        VIEW_PROJECTION.set(world.projectionMatrix()).mul(world.modelViewMatrix());
        INVERSE.set(VIEW_PROJECTION).invert();
        var eye = world.camera().getPosition();
        context.fullscreen(MAIN, shader).colorSampler("SceneSampler", context.read(SCENE))
                .depthSampler("DepthSampler", context.read(SCENE)).textureSampler("HeightSampler", water.texture())
                .uniforms(s -> {
                    s.safeGetUniform("InverseViewProjection").set(INVERSE);
                    s.safeGetUniform("ViewProjection").set(VIEW_PROJECTION);
                    s.safeGetUniform("Eye").set((float) eye.x, (float) eye.y, (float) eye.z);
                    s.safeGetUniform("WaterDomain").set((float) water.centerX, (float) water.centerZ, water.grid.extent(), water.surfaceY());
                    s.safeGetUniform("Time").set(water.seconds);
                    s.safeGetUniform("CellSize").set(water.grid.cellSize());
                    var optics = water.optics();
                    s.safeGetUniform("WaterAbsorption").set(optics.absorptionRed(), optics.absorptionGreen(), optics.absorptionBlue());
                    s.safeGetUniform("WaterScattering").set(optics.scatterRed(), optics.scatterGreen(), optics.scatterBlue());
                    s.safeGetUniform("ShorelineFoam").set(optics.shorelineFoam());
                }).draw(false);
    }
    private static ResourceLocation id(String path) { return ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "water/" + path); }
}
