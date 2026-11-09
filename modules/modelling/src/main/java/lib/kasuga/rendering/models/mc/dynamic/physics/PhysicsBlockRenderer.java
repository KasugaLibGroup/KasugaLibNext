package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.RenderPipelineRegistrar;
import lib.kasuga.rendering.effect.shader.RenderShaderDescriptor;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.Sheets;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ColorResolver;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Objects;

/**
 * Terrain tessellation for moving blocks. The world tint, per-vertex AO and
 * lightmap are retained instead of substituting the item/entity lighting model.
 */
public final class PhysicsBlockRenderer {
    private static final ResourceLocation SHADER_ID =
            ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "physics_block");
    private static volatile RenderShaderHandle shader;
    private static volatile RenderType cutout;
    private static volatile RenderType solid;
    private static volatile RenderType mipped;
    private static volatile RenderType translucent;

    private PhysicsBlockRenderer() {}

    public static void initialize(RenderPipelineRegistrar registrar) {
        if (shader != null) return;
        shader = registrar.shader(RenderShaderDescriptor.builder(SHADER_ID, DefaultVertexFormat.BLOCK)
                .resource().build()).handle();
        solid = create("kasuga_lib:physics_block_solid", RenderStateShard.NO_TRANSPARENCY,
                new RenderStateShard.TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, true),
                RenderStateShard.MAIN_TARGET, RenderStateShard.COLOR_DEPTH_WRITE, false);
        mipped = create("kasuga_lib:physics_block_mipped", RenderStateShard.NO_TRANSPARENCY,
                new RenderStateShard.TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, true),
                RenderStateShard.MAIN_TARGET, RenderStateShard.COLOR_DEPTH_WRITE, false);
        // renderSingleBlock() uses the entity block sheets for both opaque and
        // cutout models. They deliberately use the non-mipped atlas state and
        // a 0.1 alpha discard, so the fallback keeps that same contract while
        // switching only the shader and vertex format.
        cutout = create("kasuga_lib:physics_block_cutout", RenderStateShard.NO_TRANSPARENCY,
                new RenderStateShard.TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, false),
                RenderStateShard.MAIN_TARGET, RenderStateShard.COLOR_DEPTH_WRITE, false);
        translucent = create("kasuga_lib:physics_block_translucent", RenderStateShard.TRANSLUCENT_TRANSPARENCY,
                new RenderStateShard.TextureStateShard(InventoryMenu.BLOCK_ATLAS, false, false),
                RenderStateShard.MAIN_TARGET, RenderStateShard.COLOR_DEPTH_WRITE, true);
    }

    public static boolean ready() {
        return shader != null && shader.isReady() && cutout != null && translucent != null;
    }

    /** The supplied pose has already translated the model's minimum corner. */
    public static void renderTerrain(BlockRenderDispatcher dispatcher, BlockState state, PoseStack poses,
                                     MultiBufferSource.BufferSource buffers, PhysicsLightSampler.Sample sample,
                                     int overlay, ClientLevel level, Vec3 center, Quaternionf rotation, BlockPos modelAnchor) {
        if (state.getRenderShape() != RenderShape.MODEL || !ready()) {
            render(dispatcher, state, poses, buffers, sample, overlay);
            return;
        }
        BlockPos pos = BlockPos.containing(center.x - .5, center.y - .5, center.z - .5);
        var lightingWorld = new LightingWorld(level, pos, state, sample, rotation);
        var model = dispatcher.getBlockModel(state);
        long seed = state.getSeed(modelAnchor);
        var random = RandomSource.create(seed);
        var instance = shader.require();
        setProbe(instance, sample);
        set(instance, "GIStrength", sample.fallback() ? 1f : 0f);
        for (var type : model.getRenderTypes(state, random, ModelData.EMPTY)) {
            RenderType target = type == RenderType.translucent() ? translucent
                    : type == RenderType.solid() ? solid : type == RenderType.cutoutMipped() ? mipped : cutout;
            var alpha = instance.getUniform("AlphaCutoff");
            if (alpha != null) alpha.set(target == solid ? 0f : target == mipped ? .5f : .1f);
            poses.pushPose();
            var fixedOffset = state.getOffset(level, modelAnchor);
            var movingOffset = state.getOffset(lightingWorld, pos);
            poses.translate(fixedOffset.x - movingOffset.x, fixedOffset.y - movingOffset.y, fixedOffset.z - movingOffset.z);
            dispatcher.getModelRenderer().tesselateBlock(lightingWorld, model, state, pos, poses,
                    buffers.getBuffer(target), false, random, seed, overlay, ModelData.EMPTY, type);
            poses.popPose();
            // Uniforms belong to this body/material, so flush before the next
            // body changes its probe. No global renderer or light state changes.
            buffers.endBatch(target);
        }
    }

    private record LightingWorld(ClientLevel level, BlockPos origin, BlockState state,
                                 PhysicsLightSampler.Sample sample, Quaternionf rotation) implements BlockAndTintGetter {
        @Override public BlockState getBlockState(BlockPos pos) { return pos.equals(origin) ? state : level.getBlockState(pos); }
        @Override public FluidState getFluidState(BlockPos pos) { return level.getFluidState(pos); }
        @Override public BlockEntity getBlockEntity(BlockPos pos) { return level.getBlockEntity(pos); }
        @Override public int getHeight() { return level.getHeight(); }
        @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
        @Override public LevelLightEngine getLightEngine() { return level.getLightEngine(); }
        @Override public int getBlockTint(BlockPos pos, ColorResolver resolver) { return level.getBlockTint(pos, resolver); }
        @Override public int getBrightness(LightLayer layer, BlockPos pos) {
            int light = level.getBrightness(layer, pos);
            // Reconstruct light only for probes swallowed by a solid. Open
            // vertices keep the exact terrain lightmap, including real darkness.
            if (sample.fallback() && level.getBlockState(pos).isSolidRender(level, pos)) {
                light = Math.max(light, layer == LightLayer.BLOCK ? PhysicsLightSampler.lightBlock(sample.packedLight())
                        : PhysicsLightSampler.lightSky(sample.packedLight()));
            }
            return light;
        }
        @Override public float getShade(Direction direction, boolean shade) {
            if (!shade || rotation == null) return level.getShade(direction, shade);
            var n = new Vector3f(direction.getStepX(), direction.getStepY(), direction.getStepZ()).rotate(rotation);
            // Squared direction cosines interpolate the host's six directional
            // shade values continuously, and equal them exactly at identity.
            return n.x * n.x * level.getShade(n.x < 0 ? Direction.WEST : Direction.EAST, true)
                    + n.y * n.y * level.getShade(n.y < 0 ? Direction.DOWN : Direction.UP, true)
                    + n.z * n.z * level.getShade(n.z < 0 ? Direction.NORTH : Direction.SOUTH, true);
        }
    }

    public static void render(BlockRenderDispatcher dispatcher, BlockState state, PoseStack poses,
                              MultiBufferSource.BufferSource buffers, PhysicsLightSampler.Sample sample,
                              int overlay) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(poses, "poses");
        Objects.requireNonNull(buffers, "buffers");
        Objects.requireNonNull(sample, "sample");
        if (!sample.fallback() || !ready()) {
            dispatcher.renderSingleBlock(state, poses, buffers, sample.packedLight(), overlay);
            return;
        }

        // BlockRenderDispatcher.renderSingleBlock() adapts the block render
        // type to Sheets.cutoutBlockSheet()/translucent*BlockSheet() before
        // asking for a buffer. Mapping only BLOCK-format types therefore
        // silently bypasses this shader and leaves the fallback lightmap
        // exposed to a zero sample. Map the adapted sheets back to our BLOCK
        // format so the vertex and lightmap contract remains vanilla-exact.
        MultiBufferSource mapped = renderType -> buffers.getBuffer(map(renderType));
        dispatcher.renderSingleBlock(state, poses, mapped, sample.packedLight(), overlay);
        var instance = shader.require();
        setProbe(instance, sample);
        set(instance, "GIStrength", 1f);
        set(instance, "AlphaCutoff", .1f);
        buffers.endBatch(cutout);
        buffers.endBatch(translucent);
    }

    private static RenderType map(RenderType original) {
        if (original == Sheets.translucentCullBlockSheet()
                || original == Sheets.translucentItemSheet()
                || original == RenderType.translucent()
                || original == RenderType.translucentMovingBlock()) return translucent;
        // NeoForge's renderSingleBlock adapts every opaque/cutout model to the
        // cutout block sheet. Alpha discard is harmless for opaque atlas texels
        // and preserves the entity-sheet semantics for leaves and plants.
        return cutout;
    }

    private static RenderType create(String name, RenderStateShard.TransparencyStateShard transparency,
                                     RenderStateShard.TextureStateShard texture,
                                     RenderStateShard.OutputStateShard output,
                                     RenderStateShard.WriteMaskStateShard writeMask, boolean sort) {
        return RenderType.create(name, DefaultVertexFormat.BLOCK, VertexFormat.Mode.QUADS, 64 * 1024,
                true, sort, RenderType.CompositeState.builder()
                        .setShaderState(shader.shaderState())
                        .setTextureState(texture)
                        .setTransparencyState(transparency)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setCullState(RenderStateShard.CULL)
                        .setLightmapState(RenderStateShard.LIGHTMAP)
                        .setLayeringState(RenderStateShard.NO_LAYERING)
                        .setOutputState(output)
                        .setTexturingState(RenderStateShard.DEFAULT_TEXTURING)
                        .setWriteMaskState(writeMask)
                        .setLineState(RenderStateShard.DEFAULT_LINE)
                        .setColorLogicState(RenderStateShard.NO_COLOR_LOGIC)
                        .createCompositeState(sort));
    }

    private static void set(net.minecraft.client.renderer.ShaderInstance instance, String name, Vector3f value) {
        var uniform = instance.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void setProbe(net.minecraft.client.renderer.ShaderInstance instance, PhysicsLightSampler.Sample sample) {
        String[] names = {"BouncePX", "BounceNX", "BouncePY", "BounceNY", "BouncePZ", "BounceNZ"};
        for (int i = 0; i < names.length; i++) set(instance, names[i], sample.irradiance().lobe(i));
    }

    private static void set(net.minecraft.client.renderer.ShaderInstance instance, String name, float value) {
        var uniform = instance.getUniform(name);
        if (uniform != null) uniform.set(value);
    }

    private static void set(net.minecraft.client.renderer.ShaderInstance instance, String name, int value) {
        var uniform = instance.getUniform(name);
        if (uniform != null) uniform.set(value);
    }
}
