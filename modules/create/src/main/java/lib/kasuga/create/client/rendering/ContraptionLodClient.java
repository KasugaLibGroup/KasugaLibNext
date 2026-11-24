package lib.kasuga.create.client.rendering;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import lib.kasuga.KasugaLib;
import lib.kasuga.create.content.contraption.ContraptionLod;
import lib.kasuga.create.content.contraption.ContraptionLodMesh;
import lib.kasuga.create.content.contraption.ContraptionLodPayload;
import lib.kasuga.rendering.effect.RenderPipelineScope;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.mc.backend.ui.PreviewRenderBuffers;
import lib.kasuga.rendering.models.mc.scene.BlockModelSource;
import lib.kasuga.rendering.models.mc.scene.LayerLayout;
import lib.kasuga.rendering.models.mc.scene.MinecraftModelSource;
import lib.kasuga.rendering.models.mc.scene.ModelBounds;
import lib.kasuga.rendering.output.mc.DistantWorldRendering;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Renders the server's simplified snapshots after native entity rendering and against the same terrain depth. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ContraptionLodClient implements AutoCloseable {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(KasugaLib.MODID, "create/contraption_lod");
    private static ContraptionLodClient active;

    private final ClientLevel level;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final RenderPipelineScope pipeline = RenderPipelineScope.create(ID);
    private final PreviewRenderBuffers buffers = new PreviewRenderBuffers();

    private ContraptionLodClient(ClientLevel level) {
        this.level = level;
        pipeline.world(RenderPipelineDescriptor.builder(ID, RenderPhase.AFTER_BLOCK_ENTITIES).build(), this::draw);
    }

    public static void apply(Level level, ContraptionLodPayload packet) {
        if (!(level instanceof ClientLevel client) || !client.dimension().location().equals(packet.dimension())) return;
        if (active != null && active.level != level) active.close();
        if (active == null) {
            if (packet.mesh() == null) return;
            active = new ContraptionLodClient(client);
        }
        active.update(packet);
    }

    private void update(ContraptionLodPayload packet) {
        Entry entry = entries.get(packet.entity());
        if (packet.pose() == null) {
            if (entry != null) {
                entries.remove(packet.entity());
                entry.close();
                if (entries.isEmpty()) close();
            }
            return;
        }
        if (packet.mesh() != null) {
            Entry replacement = new Entry(level, packet.mesh(), packet.pose());
            entries.put(packet.entity(), replacement);
            if (entry != null) entry.close();
            entry = replacement;
            DistantWorldRendering.setRange(ID, ContraptionLod.RANGE);
        }
        if (entry != null) entry.pose(packet.pose(), level.getGameTime());
    }

    @SubscribeEvent
    public static void levelUnloaded(LevelEvent.Unload event) {
        if (active != null && active.level == event.getLevel()) active.close();
    }

    private void draw(WorldRenderPipelineContext context) {
        if (context.level() != level || !MinecraftFrameOutputs.MAIN_VIEW.equals(context.viewId())) return;
        buffers.begin(context.mainRenderTarget().frameBufferId, context.mainRenderTarget().width, context.mainRenderTarget().height);
        Vec3 camera = context.camera().getPosition();
        float partial = context.partialTick().getGameTimeDeltaPartialTick(false);
        for (Map.Entry<UUID, Entry> item : entries.entrySet()) {
            Entry entry = item.getValue();
            Entity entity = level.getEntity(entry.current.entityId());
            if (entity instanceof AbstractContraptionEntity contraption && item.getKey().equals(entity.getUUID())
                    && context.minecraft().getEntityRenderDispatcher().shouldRender(contraption,
                    context.frustum(), camera.x, camera.y, camera.z)) continue;
            entry.draw(context, buffers, camera, partial, level.getGameTime());
        }
        DistantWorldRendering.withFog(ContraptionLod.RANGE, buffers::flush);
    }

    @Override public void close() {
        if (active == this) active = null;
        DistantWorldRendering.setRange(ID, 0);
        pipeline.close();
        for (Entry entry : entries.values()) entry.close();
        entries.clear();
        buffers.close();
    }

    private static final class Entry implements AutoCloseable {
        private final BlockModelSource source;
        private final @Nullable MinecraftModelSource.Snapshot snapshot;
        private final int scale;
        private ContraptionLodPayload.Pose previous;
        private ContraptionLodPayload.Pose current;
        private long receivedAt;

        Entry(ClientLevel level, ContraptionLodMesh mesh, ContraptionLodPayload.Pose pose) {
            scale = mesh.scale();
            List<StructureBlockInfo> blocks = new ArrayList<>(mesh.cells().size());
            for (ContraptionLodMesh.Cell cell : mesh.cells()) {
                blocks.add(new StructureBlockInfo(cell.position(), Block.stateById(cell.state()), null));
            }
            source = new BlockModelSource(level, blocks, false);
            snapshot = source.snapshot(LayerLayout.ASSEMBLED, 0f);
            previous = current = pose;
        }

        void pose(ContraptionLodPayload.Pose pose, long tick) {
            if (pose.equals(current)) return;
            previous = current.origin().distanceToSqr(pose.origin()) > 64 * 64 ? pose : current;
            current = pose;
            receivedAt = tick;
        }

        void draw(WorldRenderPipelineContext context, PreviewRenderBuffers buffers, Vec3 camera, float partial, long tick) {
            if (snapshot == null) return;
            float progress = Math.clamp((tick - receivedAt + partial) / ContraptionLod.UPDATE_TICKS, 0f, 1f);
            Vec3 origin = previous.origin().lerp(current.origin(), progress);
            if (origin.distanceToSqr(camera) > ContraptionLod.RANGE * ContraptionLod.RANGE) return;
            Quaternionf rotation = previous.rotation().slerp(current.rotation(), progress);
            Matrix4f transform = new Matrix4f().rotation(rotation).scale(scale);
            ModelBounds bounds = snapshot.bounds().transform(transform).move(origin.x, origin.y, origin.z);
            if (!context.frustum().isVisible(new AABB(bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ()))) return;
            context.poseStack().pushPose();
            try {
                context.poseStack().translate(origin.x - camera.x, origin.y - camera.y, origin.z - camera.z);
                context.poseStack().mulPose(transform);
                snapshot.render(new MinecraftModelSource.Context(context.poseStack(), buffers, context.projectionMatrix(),
                        partial, current.light(), context.modelViewMatrix()));
            } finally {
                context.poseStack().popPose();
            }
        }

        @Override public void close() {
            source.close();
        }
    }
}
