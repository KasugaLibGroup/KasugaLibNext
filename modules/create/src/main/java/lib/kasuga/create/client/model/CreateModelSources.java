package lib.kasuga.create.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllDataComponents;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.StructureTransform;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.content.schematics.SchematicItem;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import lib.kasuga.rendering.models.mc.scene.*;
import net.createmod.catnip.levelWrappers.SchematicLevel;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.SuperRenderTypeBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Optional Create 6 client adapters. No Create class is linked by modelling's public source API. */
public final class CreateModelSources {
    private CreateModelSources() {}

    /** Borrows the client entity. Geometry, block entities and actors use Create's own renderers. */
    public static MinecraftModelSource contraption(AbstractContraptionEntity entity) {
        if (!entity.level().isClientSide()) throw new IllegalArgumentException("Contraption preview requires a client entity");
        return new ContraptionSource(Objects.requireNonNull(entity), true);
    }
    /** Source-local geometry for mounting with CreateSceneHosts.contraption(entity).
     * Unlike the independent preview, the source does not apply the host rotation a second time.
     */
    public static MinecraftModelSource contraptionLocal(AbstractContraptionEntity entity) {
        Objects.requireNonNull(entity);
        if (!entity.level().isClientSide()) throw new IllegalArgumentException("Contraption source requires a client entity");
        return new ContraptionSource(entity, false);
    }
    /** Uses Create's own Contraption -> entity backlink; no parallel entity registry is needed. */
    public static MinecraftModelSource contraption(Contraption contraption) {
        Objects.requireNonNull(contraption);
        var entity = contraption.entity;
        if (entity == null || entity.getContraption() != contraption)
            throw new IllegalArgumentException("Contraption is not attached to its Create entity");
        return contraption(entity);
    }

    /** Copies the item, loads through Create's loader, and applies its mirror/rotation to a private schematic level. */
    public static MinecraftModelSource schematic(Level host, ItemStack schematic) {
        ItemStack copy = schematic.copy();
        if (!copy.has(AllDataComponents.SCHEMATIC_FILE)) throw new IllegalArgumentException("Item has no Create schematic file");
        StructureTemplate template = SchematicItem.loadSchematic(host, copy);
        if (template.getSize().equals(net.minecraft.core.Vec3i.ZERO)) throw new IllegalArgumentException("Create schematic could not be loaded");
        var settings = SchematicItem.getSettings(copy);
        return new SchematicSource(host, template, settings);
    }

    public static MinecraftModelSource schematic(Level host, StructureTemplate template) {
        return new SchematicSource(host, template, new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings());
    }
    /** Read-only Create/structure NBT import with the same bounded palette validation as other sources. */
    public static MinecraftModelSource schematic(Level host, java.nio.file.Path path) throws java.io.IOException {
        var tag = net.minecraft.nbt.NbtIo.readCompressed(path, net.minecraft.nbt.NbtAccounter.create(SchematicModelData.MAX_NBT_BYTES));
        SchematicModelData.structure(tag, host.holderLookup(net.minecraft.core.registries.Registries.BLOCK), 0);
        tag = net.minecraft.util.datafix.DataFixTypes.STRUCTURE.updateToCurrentVersion(net.minecraft.util.datafix.DataFixers.getDataFixer(),
                tag, net.minecraft.nbt.NbtUtils.getDataVersion(tag, 500));
        var template = new StructureTemplate(); template.load(host.holderLookup(net.minecraft.core.registries.Registries.BLOCK), tag);
        return schematic(host, template);
    }

    /** Create machines already in the world retain their state/speed in private block entities. */
    public static BlockModelSource machine(Level host, BlockPos min, BlockPos max) { return MinecraftModelSources.capture(host, min, max); }

    private static final class ContraptionSource extends LayeredModelSource {
        private AbstractContraptionEntity entity;
        private final boolean includeHostTransform;
        private Contraption contraption;
        private BlockModelSource blocks;
        private long sampledTick = Long.MIN_VALUE;
        private long generation = -1;
        private List<Actor> actors = List.of();
        private VirtualRenderWorld actorWorld;
        private ContraptionSource(AbstractContraptionEntity entity, boolean includeHostTransform) {
            this.entity = entity; this.includeHostTransform = includeHostTransform;
        }

        @Override protected void prepare(float partialTick) {
            if (entity == null || entity.isRemoved() || entity.getContraption() == null) { release(); return; }
            Contraption current = entity.getContraption();
            long tick = entity.level().getGameTime();
            long resourceGeneration = ModelPreviewResources.generation();
            if (blocks != null && current == contraption && sampledTick == tick && generation == resourceGeneration) return;
            generation = resourceGeneration;
            List<StructureBlockInfo> infos = new ArrayList<>();
            if (current.getBlocks().size() > MinecraftModelSources.MAX_BLOCKS) throw new IllegalArgumentException("Contraption exceeds preview limit");
            for (var info : current.getBlocks().values()) {
                var live = CreateContraptionBuffers.blockEntity(current, info.pos());
                infos.add(new StructureBlockInfo(info.pos(), info.state(), live == null ? info.nbt()
                        : live.saveWithFullMetadata(entity.level().registryAccess())));
            }
            BlockModelSource next = new BlockModelSource(entity.level(), infos) {
                @Override protected boolean shouldRenderBlockEntity(BlockEntity entity) {
                    var movement = MovementBehaviour.REGISTRY.get(entity.getBlockState());
                    return movement == null || !movement.disableBlockEntityRendering();
                }
            };
            VirtualRenderWorld nextWorld = CreatePreviewFactories.world(next.previewLevel(), next.previewLevel().getMinBuildHeight(),
                    next.previewLevel().getHeight(), BlockPos.ZERO);
            for (var info : infos) nextWorld.setBlock(info.pos(), info.state(), 0);
            nextWorld.setBlockEntities(infos.stream().map(info -> next.blockEntity(info.pos())).filter(Objects::nonNull).toList());
            nextWorld.setExternalLight(net.minecraft.client.renderer.LightTexture.FULL_BRIGHT);
            List<Actor> nextActors = new ArrayList<>();
            try {
                for (var pair : current.getActors()) {
                    if (pair.getRight() == null) continue;
                    var original = pair.getRight();
                    var info = pair.getLeft();
                    var local = new MovementContext(nextWorld, info, current);
                    local.position = original.position; local.motion = original.motion; local.relativeMotion = original.relativeMotion;
                    local.rotation = original.rotation; local.stall = original.stall; local.disabled = original.disabled;
                    local.firstMovement = original.firstMovement; local.data = original.data.copy();
                    local.blockEntityData = original.blockEntityData == null ? new net.minecraft.nbt.CompoundTag() : original.blockEntityData.copy();
                    nextActors.add(new Actor(info, local));
                }
            } catch (RuntimeException | Error failure) { nextWorld.clear(); next.close(); throw failure; }
            if (blocks != null) blocks.close();
            if (actorWorld != null) actorWorld.clear();
            actorWorld = nextWorld;
            blocks = next; contraption = current; sampledTick = tick; actors = List.copyOf(nextActors);
        }
        @Override protected List<Element> elements() { return blocks == null ? List.of() : blocks.geometryElements(); }
        @Override protected Matrix4f localTransform(float partialTick) {
            var pose = new PoseStack();
            if (includeHostTransform && entity != null) entity.applyLocalTransforms(pose, partialTick);
            return new Matrix4f(pose.last().pose());
        }
        @Override protected void renderAssembled(Context context) {
            // This is the structure buffer used by ContraptionEntityRenderer itself. Explicitly drawing
            // its native path also works while the world entity uses Flywheel instancing.
            for (RenderType type : RenderType.chunkBufferLayers()) {
                SuperByteBuffer buffer = CreateContraptionBuffers.get(contraption, actorWorld, type);
                if (!buffer.isEmpty()) buffer.light(context.packedLight()).renderInto(context.pose(), context.buffers().getBuffer(type));
            }
            Slice all = new Slice(LayerLayout.Axis.Y, 0, blocks.geometryElements(), false);
            blocks.renderFluidsOnly(all, context);
            blocks.renderEntitiesOnly(all, context);
            renderActors(all, context);
        }
        @Override protected void renderSlice(Slice slice, Context context) {
            blocks.renderNativeSlice(slice, context);
            renderActors(slice, context);
        }
        @Override protected java.util.Optional<BlockHit> pickSlice(Slice slice, net.minecraft.world.phys.Vec3 from, net.minecraft.world.phys.Vec3 to) {
            return blocks.pickNativeSlice(slice, from, to);
        }
        private void renderActors(Slice slice, Context context) {
            ContraptionMatrices matrices = new ContraptionMatrices();
            ContraptionMatrices.transform(matrices.getViewProjection(), context.pose());
            ContraptionMatrices.transform(matrices.getModelViewProjection(), context.pose());
            for (Actor actor : actors) {
                BlockPos pos = actor.info().pos();
                if (slice.isolated() && slice.axis().coordinate(pos.getX(), pos.getY(), pos.getZ()) != slice.coordinate()) continue;
                if (contraption.isHiddenInPortal(pos)) continue;
                MovementBehaviour renderer = MovementBehaviour.REGISTRY.get(actor.info().state());
                if (renderer == null) continue;
                matrices.getModel().pushPose();
                try {
                    matrices.getModel().translate(pos.getX(), pos.getY(), pos.getZ());
                    renderer.renderInContraption(actor.context(), actorWorld, matrices, context.buffers());
                } finally { matrices.getModel().popPose(); }
            }
        }
        private void release() {
            if (blocks != null) blocks.close();
            if (actorWorld != null) actorWorld.clear();
            actorWorld = null;
            blocks = null; contraption = null; actors = List.of(); sampledTick = Long.MIN_VALUE;
        }
        @Override public void close() { if (!closed) { super.close(); release(); entity = null; } }
        private record Actor(StructureBlockInfo info, MovementContext context) {}
    }

    private static final class SchematicSource extends BlockModelSource {
        private final CreatePreviewFactories.SchematicView renderer;
        private long generation = -1;
        private SchematicSource(Level host, StructureTemplate template,
                net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings) {
            this(host, placed(host, template, settings));
        }
        private SchematicSource(Level host, SchematicLevel level) {
            super(host, data(level, host));
            renderer = CreatePreviewFactories.schematic(level);
        }
        @Override protected void renderAssembled(Context context) {
            if (generation != ModelPreviewResources.generation()) {
                renderer.update(); generation = ModelPreviewResources.generation();
            }
            renderer.render(context.pose(), new SuperRenderTypeBuffer() {
                @Override public VertexConsumer getEarlyBuffer(RenderType type) { return context.buffers().getBuffer(type); }
                @Override public VertexConsumer getBuffer(RenderType type) { return context.buffers().getBuffer(type); }
                @Override public VertexConsumer getLateBuffer(RenderType type) { return context.buffers().getBuffer(type); }
                @Override public void draw() {}
                @Override public void draw(RenderType type) {}
            });
            // Create's shaded SBB colors depend on the current view/light directions. Keep that
            // native pass live, while the static fluids and layer fallback use the shared cache.
            super.renderAssembled(context);
        }
        @Override protected void renderStaticAssembled(Context context) {
            // Upstream helper distance-culls against the game camera; UI uses its own camera.
            renderFluids(new Slice(LayerLayout.Axis.Y, 0, geometryElements(), false), context);
        }
        @Override public void close() {
            if (closed) return;
            try { if (renderer != null) renderer.close(); } finally { super.close(); }
        }
    }

    private static SchematicLevel placed(Level host, StructureTemplate template,
            net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings settings) {
        // Reject huge bounding volumes before calling upstream's volume-based tessellator.
        var size = template.getSize();
        if (size.getX() <= 0 || size.getY() <= 0 || size.getZ() <= 0 || size.getX() > MinecraftModelSources.MAX_SPAN
                || size.getY() > MinecraftModelSources.MAX_SPAN || size.getZ() > MinecraftModelSources.MAX_SPAN
                || (long) size.getX() * size.getY() * size.getZ() > 1048576)
            throw new IllegalArgumentException("Invalid Create schematic size");
        SchematicLevel level = new SchematicLevel(host) {
            @Override public Iterable<BlockEntity> getRenderedBlockEntities() { return List.of(); }
        };
        settings = settings.copy().setIgnoreEntities(true);
        template.placeInWorld(level, BlockPos.ZERO, BlockPos.ZERO, settings, net.minecraft.util.RandomSource.create(0), Block.UPDATE_CLIENTS);
        var transform = new StructureTransform(settings.getRotationPivot(), Direction.Axis.Y, settings.getRotation(), settings.getMirror());
        for (BlockEntity entity : level.getBlockEntities()) transform.apply(entity);
        return level;
    }
    private static List<StructureBlockInfo> data(SchematicLevel level, Level host) {
        return level.getBlockMap().entrySet().stream().map(entry -> {
            var entity = level.getBlockEntity(entry.getKey());
            return new StructureBlockInfo(entry.getKey(), entry.getValue(), entity == null ? null : entity.saveWithFullMetadata(host.registryAccess()));
        }).toList();
    }
}
