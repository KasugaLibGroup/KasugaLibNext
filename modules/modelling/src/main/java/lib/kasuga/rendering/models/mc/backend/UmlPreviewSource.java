package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.models.mc.scene.*;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix4f;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ForkJoinPool;
import java.util.function.Supplier;

/** Private UML instance for composition with Minecraft geometry in a shared depth target. */
public final class UmlPreviewSource implements MinecraftModelSource {
    private final Supplier<Model> source;
    private Model asset;
    private ModelInstance instance;
    private BackendInstance gpu;
    private ModelBounds bounds;
    private boolean closed;

    public UmlPreviewSource(Supplier<Model> source) { this.source = Objects.requireNonNull(source); }
    @Nullable public ModelInstance instance() { return instance; }

    @Override public Snapshot snapshot(LayerLayout layout, float partialTick) {
        if (closed) return null;
        Model next = source.get();
        if (next != asset) {
            release();
            if (next != null) {
                asset = next;
                instance = new ModelInstance(asset, new Transform(), null, null,
                        new MaterialSetInstance(asset.getMaterialSet()), null);
                instance.updateImmediate();
                for (var vertex : asset.getVertices()) {
                    var p = vertex.getPosition();
                    if (!p.isFinite()) throw new IllegalArgumentException("Non-finite UML vertex");
                    var point = new ModelBounds(p.x, p.y, p.z, p.x, p.y, p.z);
                    bounds = bounds == null ? point : bounds.union(point);
                }
                if (bounds == null) bounds = new ModelBounds(-.5, -.5, -.5, .5, .5, .5);
            }
        }
        if (instance == null) return null;
        return new Snapshot(bounds, List.of(new Part("uml", 0, bounds, context -> {
            if (gpu == null) gpu = new BackendInstance(instance, ForkJoinPool.commonPool(), false, true);
            instance.sample(context.partialTick());
            gpu.updateLightData(context.packedLight(), OverlayTexture.NO_OVERLAY, 1);
            for (ModelRenderPass pass : ModelRenderPass.values())
                gpu.drawBuffer(pass, context.pose().last(), Types.TYPES[pass.ordinal()],
                        context.modelView(), context.projection(), 0, instance.getAmbientLightEnhancement());
        })));
    }

    private void release() {
        var previousGpu = gpu; var previousInstance = instance;
        gpu = null; instance = null; asset = null; bounds = null;
        try { if (previousGpu != null) previousGpu.close(); }
        catch (Exception e) { throw new IllegalStateException("Cannot release UML preview", e); }
        finally { if (previousInstance != null) previousInstance.close(); }
    }
    @Override public void close() {
        if (closed) return;
        closed = true; release();
    }

    private static final class Types {
        private static final RenderStateShard.OutputStateShard TARGET =
                new RenderStateShard.OutputStateShard("kasuga_ui_model_target", () -> {}, () -> {});
        private static final RenderStateShard.TransparencyStateShard ALPHA = new RenderStateShard.TransparencyStateShard(
                "kasuga_ui_model_alpha", () -> {
                    RenderSystem.enableBlend();
                    RenderSystem.blendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                            GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
                }, () -> { RenderSystem.disableBlend(); RenderSystem.defaultBlendFunc(); });
        private static final RenderType[] TYPES = java.util.Arrays.stream(ModelRenderPass.values()).<RenderType>map(pass ->
                RenderType.create("kasuga_ui_model_" + pass.name().toLowerCase(java.util.Locale.ROOT),
                        RenderState.UML_VERTEX_FORMAT, VertexFormat.Mode.QUADS, 256, true, pass == ModelRenderPass.TRANSLUCENT,
                        RenderType.CompositeState.builder().setShaderState(RenderState.UML_SHADER)
                                .setTextureState(RenderState.UML_TEXTURE_STATE)
                                .setTransparencyState(pass == ModelRenderPass.TRANSLUCENT ? ALPHA : RenderStateShard.NO_TRANSPARENCY)
                                .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST).setCullState(RenderStateShard.CULL)
                                .setLightmapState(RenderStateShard.LIGHTMAP).setOverlayState(RenderStateShard.OVERLAY)
                                .setOutputState(TARGET).setWriteMaskState(RenderStateShard.COLOR_DEPTH_WRITE)
                                .createCompositeState(false))).toArray(RenderType[]::new);
    }
}
