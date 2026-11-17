package lib.kasuga.rendering.effect.builtin.light;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import lib.kasuga.rendering.effect.EffectRenderer;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.post.PostProcessTargetDescriptor;
import lib.kasuga.rendering.effect.post.PostProcessTargetPool;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraph;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphContext;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphPassDescriptor;
import lib.kasuga.rendering.effect.post.graph.PostProcessGraphTarget;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import lib.kasuga.rendering.lighting.LightBuffer;
import lib.kasuga.rendering.lighting.LightFrame;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo.TBOBuffer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL15;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

final class WorldLightRenderer implements EffectRenderer<WorldLight> {
    private static final PostProcessGraphTarget MAIN = PostProcessGraphTarget.main();
    private static final PostProcessGraphTarget SCENE = PostProcessGraphTarget.managed(WorldLights.id("scene"));
    private final List<LightFrame> lights = new ArrayList<>();
    private final RenderShaderHandle shader;
    private final Matrix4f inverse = new Matrix4f();
    private WorldRenderPipelineContext collected;
    private PostProcessGraph graph;
    private TBOBuffer buffer;
    private ByteBuffer data;

    WorldLightRenderer(RenderPipelineRegistrar registrar) {
        shader = registrar.shader(RenderShaderDescriptor.builder(WorldLights.id("world_lights"), DefaultVertexFormat.BLIT_SCREEN)
                .resource().build()).handle();
    }

    void registerGraph(RenderPipelineRegistrar registrar) {
        graph = PostProcessGraph.builder(WorldLights.id("graph"))
                .target(PostProcessTargetDescriptor.builder(SCENE.id()).screenScale(1).useDepth(true).build())
                .prepare((context, frame) -> ready() && WorldLights.visibleCount() > 0 && !lights.isEmpty()
                        && collected != null && collected.level() == context.world().level()
                        && collected.viewId().equals(context.world().viewId()))
                .pass(PostProcessGraphPassDescriptor.builder(WorldLights.id("capture"), context -> {
                    context.copyColor(MAIN, SCENE, false);
                    context.copyDepth(MAIN, SCENE);
                }).reads(MAIN).writes(SCENE).build())
                .pass(PostProcessGraphPassDescriptor.builder(WorldLights.id("illuminate"), this::draw)
                        .reads(SCENE).writes(MAIN).build()).build();
        registrar.graph(graph, 701);
    }

    boolean ready() { return shader.isReady(); }

    @Override public void begin(WorldRenderPipelineContext context) {
        lights.clear();
        collected = context;
    }

    @Override public void render(WorldLight effect, WorldRenderPipelineContext context) {
        lights.add(effect.frame());
    }

    private void draw(PostProcessGraphContext context) {
        WorldRenderPipelineContext world = context.world();
        Vec3 eye = world.camera().getPosition();
        int bytes = Math.multiplyExact(lights.size(), LightBuffer.BYTES_PER_LIGHT);
        if (data == null || data.capacity() < bytes) {
            ByteBuffer replacement = MemoryUtil.memAlloc(bytes);
            if (data != null) MemoryUtil.memFree(data);
            data = replacement;
        }
        data.clear();
        for (LightFrame light : lights) LightBuffer.write(data, light, eye.x, eye.y, eye.z);
        data.flip();
        if (buffer == null) buffer = new TBOBuffer(() -> 8, bytes, GL15.GL_DYNAMIC_DRAW);
        else if (buffer.getSize() < bytes) buffer.resize(bytes, false);
        buffer.updateAll(data);
        inverse.set(world.projectionMatrix()).mul(world.modelViewMatrix()).invert();
        context.fullscreen(MAIN, shader)
                .colorSampler("SceneSampler", context.read(SCENE))
                .depthSampler("DepthSampler", context.read(SCENE))
                .bufferSampler("LightSampler", buffer)
                .uniforms(instance -> {
                    instance.safeGetUniform("InverseViewProjection").set(inverse);
                    instance.safeGetUniform("LightCount").set(lights.size());
                }).draw(false);
    }

    void release() {
        lights.clear();
        collected = null;
        if (data != null) {
            MemoryUtil.memFree(data);
            data = null;
        }
        if (buffer != null) {
            try {
                buffer.close();
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to release world light buffer", exception);
            } finally {
                buffer = null;
            }
        }
        for (ResourceLocation target : graph.allocatedTargetIds()) PostProcessTargetPool.getInstance().release(target);
    }
}
