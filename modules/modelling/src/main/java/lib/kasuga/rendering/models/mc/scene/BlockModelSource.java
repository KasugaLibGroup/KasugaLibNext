package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.PoseStack;

/** Native baked-block and block-entity renderers with a private, non-ticking neighbor view. */
public class BlockModelSource extends LayeredModelSource {
    private final List<Element> elements;
    protected final Map<BlockPos, BlockState> blocks;
    protected final Map<BlockPos, BlockEntity> blockEntities = new LinkedHashMap<>();
    protected final PreviewLevel level;
    private final BlockPos origin;
    private final Map<SliceKey,CachedBlockGeometry> geometry = new java.util.HashMap<>();
    private final Map<SliceKey,List<Element>> entitySlices = new java.util.HashMap<>();
    private long bakedGeneration = -1;
    private LayerLayout.Axis cachedAxis;
    private record SliceKey(LayerLayout.Axis axis,int layer,boolean isolated) {
        static SliceKey of(Slice slice) { return new SliceKey(slice.isolated()?slice.axis():LayerLayout.Axis.Y, slice.isolated()?slice.coordinate():0,slice.isolated()); }
    }
    private long geometryBuilds;
    /** Diagnostic count: camera, layout spacing and time changes must not retessellate static geometry. */
    public long geometryBuilds() { return geometryBuilds; }

    public BlockModelSource(Level host, List<StructureBlockInfo> infos) {
        this(host, infos, BlockPos.ZERO);
    }

    /** Keeps world-based block-entity references intact while rendering geometry close to zero. */
    public BlockModelSource(Level host, List<StructureBlockInfo> infos, BlockPos origin) {
        this.origin = origin.immutable();
        if (infos.size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Too many structure blocks");
        var states = new LinkedHashMap<BlockPos, BlockState>();
        for (var info : infos) if (!info.state().isAir()) {
            if (states.put(info.pos().immutable(), info.state()) != null)
                throw new IllegalArgumentException("Duplicate structure position " + info.pos());
        }
        // BlockPos hashes are highly clustered for dense structures. MapN's linear probing can
        // turn neighbor reads into long scans; keep an immutable facade over a bucketed map.
        blocks = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(states));
        elements = blocks.keySet().stream().map(p -> p.subtract(origin)).map(p -> new Element(p.getX(), p.getY(), p.getZ(),
                new ModelBounds(p.getX(), p.getY(), p.getZ(), (double) p.getX() + 1, (double) p.getY() + 1, (double) p.getZ() + 1))).toList();
        validateSpan(elements);
        level = new PreviewLevel(host, blocks);
        try {
            for (var info : infos) if (blocks.containsKey(info.pos()) && info.state().getBlock() instanceof EntityBlock factory) {
                BlockEntity entity = factory.newBlockEntity(info.pos(), info.state());
                if (entity != null) {
                    level.addPreviewBlockEntity(entity);
                    if (info.nbt() != null) entity.loadWithComponents(info.nbt().copy(), host.registryAccess());
                    blockEntities.put(info.pos().immutable(), entity);
                }
            }
        } catch (RuntimeException | Error failure) { close(); throw failure; }
    }

    private static void validateSpan(List<Element> elements) {
        if (elements.isEmpty()) return;
        var bounds = elements.stream().map(Element::bounds).reduce(ModelBounds::union).orElseThrow();
        if (bounds.maxX() - bounds.minX() > MinecraftModelSources.MAX_SPAN
                || bounds.maxY() - bounds.minY() > MinecraftModelSources.MAX_SPAN
                || bounds.maxZ() - bounds.minZ() > MinecraftModelSources.MAX_SPAN)
            throw new IllegalArgumentException("Structure span exceeds limit");
    }

    @Override protected List<Element> elements() { return elements; }
    @Override protected Object geometryKey() { return elements; }
    private final java.util.EnumMap<LayerLayout.Axis, Map<Integer, Map<net.minecraft.resources.ResourceLocation, Integer>>> statistics =
            new java.util.EnumMap<>(LayerLayout.Axis.class);

    @Override public Map<Integer, Map<net.minecraft.resources.ResourceLocation, Integer>> blockStatistics(LayerLayout.Axis axis) {
        return statistics.computeIfAbsent(axis, key -> {
            var counts = new java.util.TreeMap<Integer, Map<net.minecraft.resources.ResourceLocation, Integer>>();
            blocks.forEach((position, state) -> {
                var p = position.subtract(origin);
                counts.computeIfAbsent(axis.coordinate(p.getX(), p.getY(), p.getZ()), ignored -> new java.util.TreeMap<>())
                        .merge(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()), 1, Integer::sum);
            });
            var result = new java.util.TreeMap<Integer, Map<net.minecraft.resources.ResourceLocation, Integer>>();
            counts.forEach((layer, values) -> result.put(layer, java.util.Collections.unmodifiableMap(values)));
            return java.util.Collections.unmodifiableMap(result);
        });
    }
    @Override protected java.util.Optional<BlockHit> pickSlice(Slice slice, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        var base = new net.minecraft.world.phys.Vec3(origin.getX(), origin.getY(), origin.getZ());
        var start = from.add(base); var end = to.add(base);
        BlockHit nearest = null; double distance = Double.POSITIVE_INFINITY;
        for (var element : slice.elements()) {
            var pos = new BlockPos(element.x(), element.y(), element.z()).offset(origin);
            var state = blocks.get(pos);
            var shape = state.getShape(level, pos);
            if (shape.isEmpty()) shape = net.minecraft.world.phys.shapes.Shapes.block();
            var hit = shape.clip(start, end, pos);
            if (hit == null) continue;
            double next = hit.getLocation().distanceToSqr(start);
            if (next < distance) {
                distance = next; nearest = new BlockHit(pos, state, hit.getLocation().subtract(base), hit.getDirection());
            }
        }
        return java.util.Optional.ofNullable(nearest);
    }
    /** Adapter bridge: source-local rays and original source block coordinates. */
    public java.util.Optional<BlockHit> pickNativeSlice(Slice slice, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
        return pickSlice(slice, from, to);
    }
    public PreviewLevel previewLevel() { return level; }
    @Nullable public BlockEntity blockEntity(BlockPos position) { return blockEntities.get(position); }

    @Override protected void renderSlice(Slice slice, Context context) {
        drawStatic(slice,context,false);
        renderBlockEntities(slice, context);
    }
    @Override protected void renderAssembled(Context context) {
        var slice = new Slice(LayerLayout.Axis.Y,0,elements,false);
        drawStatic(slice,context,true); renderBlockEntities(slice,context);
    }
    protected void renderStaticAssembled(Context context) {
        var slice = new Slice(LayerLayout.Axis.Y,0,elements,false);
        renderBlocks(slice,context);renderFluids(slice,context);
    }
    private void drawStatic(Slice slice,Context context,boolean assembled) {
        if (!(context.buffers() instanceof CachedBlockGeometry.Sink)) {
            if (assembled) renderStaticAssembled(context); else {renderBlocks(slice,context);renderFluids(slice,context);} return;
        }
        long generation=ModelPreviewResources.generation();
        if (generation!=bakedGeneration || slice.isolated() && cachedAxis!=null && cachedAxis!=slice.axis()) {
            clearGeometry();bakedGeneration=generation;
        }
        if (slice.isolated()) cachedAxis=slice.axis();
        var key=SliceKey.of(slice);
        var cached=geometry.get(key);
        if (cached==null) {
            cached=CachedBlockGeometry.build(local -> {
                if (assembled) renderStaticAssembled(local); else {renderBlocks(slice,local);renderFluids(slice,local);}
            });
            geometry.put(key,cached);geometryBuilds++;
        }
        cached.render(context);
    }
    private void clearGeometry() {
        geometry.values().forEach(CachedBlockGeometry::close);geometry.clear();cachedAxis=null;
    }

    protected void renderBlocks(Slice slice, Context context) {
        var dispatcher = Minecraft.getInstance().getBlockRenderer();
        var random = RandomSource.create(42);
        var view = renderView(slice);
        var appearance = appearanceView(slice);
        for (Element element : slice.elements()) {
            var pos = new BlockPos(element.x(), element.y(), element.z()).offset(origin);
            BlockState state = blocks.get(pos);
            BlockEntity entity = blockEntities.get(pos);
            context.pose().pushPose();
            try {
                context.pose().translate(element.x(), element.y(), element.z());
                if (state.getRenderShape() == RenderShape.MODEL) {
                    var model = dispatcher.getBlockModel(state);
                    var data = model.getModelData(appearance, pos, state, entity == null ? ModelData.EMPTY : entity.getModelData());
                    random.setSeed(state.getSeed(pos));
                    for (RenderType type : model.getRenderTypes(state, random, data)) {
                        dispatcher.renderBatched(state, pos, view, context.pose(), context.buffers().getBuffer(type), true,
                                random, data, type);
                    }
                }
            } finally { context.pose().popPose(); }
        }
    }
    /** Model appearance is distinct from the view used to reveal cut faces. */
    protected net.minecraft.world.level.BlockAndTintGetter appearanceView(Slice slice) { return level; }

    private net.minecraft.world.level.BlockAndTintGetter renderView(Slice slice) {
        // Neighbor culling sees just this slice, so faces hidden in the assembled structure become visible.
        return new net.minecraft.world.level.BlockAndTintGetter() {
            @Override public BlockState getBlockState(BlockPos pos) {
                if (slice.isolated() && slice.axis().coordinate(pos.getX() - origin.getX(), pos.getY() - origin.getY(), pos.getZ() - origin.getZ()) != slice.coordinate())
                    return net.minecraft.world.level.block.Blocks.AIR.defaultBlockState();
                return level.getBlockState(pos);
            }
            @Override public net.minecraft.world.level.material.FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
            @Override public BlockEntity getBlockEntity(BlockPos pos) { return level.getBlockEntity(pos); }
            @Override public float getShade(net.minecraft.core.Direction direction, boolean shade) { return level.getShade(direction, shade); }
            @Override public net.minecraft.world.level.lighting.LevelLightEngine getLightEngine() { return level.getLightEngine(); }
            @Override public int getBrightness(net.minecraft.world.level.LightLayer layer, BlockPos pos) { return 15; }
            @Override public int getRawBrightness(BlockPos pos, int amount) { return 15; }
            @Override public int getBlockTint(BlockPos pos, net.minecraft.world.level.ColorResolver resolver) { return level.getBlockTint(pos, resolver); }
            @Override public int getHeight() { return level.getHeight(); }
            @Override public int getMinBuildHeight() { return level.getMinBuildHeight(); }
        };
    }

    protected void renderFluids(Slice slice, Context context) {
        var view = renderView(slice);
        for (Element element : slice.elements()) {
            var pos = new BlockPos(element.x(), element.y(), element.z()).offset(origin);
            var state = blocks.get(pos);
            if (state.getFluidState().isEmpty()) continue;
            context.pose().pushPose();
            try {
                context.pose().translate(element.x(), element.y(), element.z());
                Minecraft.getInstance().getBlockRenderer().renderLiquid(pos, view, new FluidVertices(context.buffers().getBuffer(
                        net.minecraft.client.renderer.ItemBlockRenderTypes.getRenderLayer(state.getFluidState())),
                        context.pose().last(), pos.getX() & 15, pos.getY() & 15, pos.getZ() & 15), state, state.getFluidState());
            } finally { context.pose().popPose(); }
        }
    }

    protected void renderBlockEntities(Slice slice, Context context) {
        var selected=entitySlices.computeIfAbsent(SliceKey.of(slice),ignored -> slice.elements().stream()
                .filter(element -> blockEntities.containsKey(new BlockPos(element.x(),element.y(),element.z()).offset(origin))).toList());
        for (var element : selected) {
            var pos = new BlockPos(element.x(), element.y(), element.z()).offset(origin);
            var entity = blockEntities.get(pos);
            if (entity == null || !shouldRenderBlockEntity(entity)) continue;
            var renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(entity);
            if (renderer == null) continue;
            context.pose().pushPose();
            try {
                context.pose().translate(element.x(), element.y(), element.z());
                renderer.render(entity, context.partialTick(), context.pose(), context.buffers(),
                        context.packedLight(), OverlayTexture.NO_OVERLAY);
            } finally { context.pose().popPose(); }
        }
    }

    /** Native adapters may draw their own cached structure and reuse these private block entities. */
    public void renderEntitiesOnly(Slice slice, Context context) { renderBlockEntities(slice, context); }
    public void renderFluidsOnly(Slice slice, Context context) { renderFluids(slice, context); }
    public void renderNativeSlice(Slice slice, Context context) { renderSlice(slice, context); }
    public List<Element> geometryElements() { return elements; }
    protected boolean shouldRenderBlockEntity(BlockEntity entity) { return true; }

    /** Vanilla fluid vertices use section-local coordinates and bypass PoseStack. */
    record FluidVertices(VertexConsumer target, PoseStack.Pose pose, int x, int y, int z) implements VertexConsumer {
        @Override public VertexConsumer addVertex(float vx, float vy, float vz) { target.addVertex(pose, vx - x, vy - y, vz - z); return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { target.setColor(r, g, b, a); return this; }
        @Override public VertexConsumer setUv(float u, float v) { target.setUv(u, v); return this; }
        @Override public VertexConsumer setUv1(int u, int v) { target.setUv1(u, v); return this; }
        @Override public VertexConsumer setUv2(int u, int v) { target.setUv2(u, v); return this; }
        @Override public VertexConsumer setNormal(float nx, float ny, float nz) { target.setNormal(pose, nx, ny, nz); return this; }
    }

    @Override public void close() {
        if (closed) return;
        super.close();
        clearGeometry();entitySlices.clear();
        if (level != null) level.close();
        blockEntities.clear();
    }
}
