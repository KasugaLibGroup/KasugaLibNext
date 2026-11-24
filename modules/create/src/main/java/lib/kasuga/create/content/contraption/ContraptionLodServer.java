package lib.kasuga.create.content.contraption;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.joml.Matrix3f;
import org.joml.Quaternionf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class ContraptionLodServer {
    private static final int MAX_PER_PLAYER = 64;
    private static final int MAX_MESH_UPDATES_PER_TICK = 4;
    private static final int MESH_REFRESH_TICKS = 100;
    private static final Map<ServerLevel, World> WORLDS = new IdentityHashMap<>();

    private ContraptionLodServer() {}

    private static final class World {
        final Map<UUID, Tracked> entities = new HashMap<>();
        final Map<UUID, Map<UUID, Sent>> players = new HashMap<>();
    }

    private record Sent(ContraptionLodMesh mesh, ContraptionLodPayload.Pose pose) {}

    private static final class Tracked {
        final AbstractContraptionEntity entity;
        Contraption contraption;
        ContraptionLodMesh mesh;
        long refreshAt;
        long posedAt = Long.MIN_VALUE;
        ContraptionLodPayload.Pose pose;

        Tracked(AbstractContraptionEntity entity) { this.entity = entity; }

        void refresh(ServerLevel level) {
            long tick = level.getGameTime();
            Contraption current = entity.getContraption();
            if (contraption != current || tick >= refreshAt) {
                List<ContraptionLodMesh.Cell> blocks = new ArrayList<>(current.getBlocks().size());
                for (StructureBlockInfo block : current.getBlocks().values()) {
                    if (!block.state().isAir()) blocks.add(new ContraptionLodMesh.Cell(block.pos(), Block.getId(block.state())));
                }
                ContraptionLodMesh next = ContraptionLodMesh.simplify(blocks);
                if (!next.equals(mesh)) mesh = next;
                contraption = current;
                refreshAt = tick + MESH_REFRESH_TICKS;
            }
            if (posedAt == tick) return;
            posedAt = tick;
            Vec3 origin = entity.toGlobalVector(Vec3.ZERO, 1f);
            Vec3 x = entity.toGlobalVector(new Vec3(1, 0, 0), 1f).subtract(origin);
            Vec3 y = entity.toGlobalVector(new Vec3(0, 1, 0), 1f).subtract(origin);
            Vec3 z = entity.toGlobalVector(new Vec3(0, 0, 1), 1f).subtract(origin);
            Matrix3f basis = new Matrix3f((float) x.x, (float) x.y, (float) x.z,
                    (float) y.x, (float) y.y, (float) y.z, (float) z.x, (float) z.y, (float) z.z);
            Quaternionf rotation = new Quaternionf().setFromNormalized(basis).normalize();
            BlockPos pos = entity.blockPosition();
            int light = level.getBrightness(LightLayer.BLOCK, pos) << 4 | level.getBrightness(LightLayer.SKY, pos) << 20;
            pose = new ContraptionLodPayload.Pose(entity.getId(), origin, rotation.x, rotation.y, rotation.z, rotation.w, light);
        }
    }

    static void joined(ServerLevel level, Entity entity) {
        if (entity instanceof AbstractContraptionEntity contraption) {
            WORLDS.computeIfAbsent(level, ignored -> new World()).entities.put(entity.getUUID(), new Tracked(contraption));
        }
    }

    static void left(ServerLevel level, Entity entity) {
        World world = WORLDS.get(level);
        if (world == null || !(entity instanceof AbstractContraptionEntity)) return;
        Tracked tracked = world.entities.get(entity.getUUID());
        if (tracked != null && tracked.entity == entity) world.entities.remove(entity.getUUID());
    }

    static void forgetPlayer(ServerPlayer player) {
        for (World world : WORLDS.values()) world.players.remove(player.getUUID());
    }

    static void unload(ServerLevel level) {
        WORLDS.remove(level);
    }

    static void tick(ServerLevel level, boolean enabled) {
        World world = WORLDS.get(level);
        if (world == null) return;
        world.entities.values().removeIf(tracked -> tracked.entity.isRemoved());
        ResourceLocation dimension = level.dimension().location();
        for (ServerPlayer player : level.players()) {
            if (!NetworkRegistry.hasChannel(player.connection, ContraptionLodPayload.TYPE.id())) continue;
            Map<UUID, Sent> sent = world.players.computeIfAbsent(player.getUUID(), ignored -> new HashMap<>());
            List<Tracked> visible = new ArrayList<>();
            if (enabled) {
                for (Tracked candidate : world.entities.values()) {
                    if (candidate.entity.getContraption() != null && candidate.entity.isAlive()
                            && candidate.entity.distanceToSqr(player) <= ContraptionLod.RANGE * ContraptionLod.RANGE) {
                        visible.add(candidate);
                    }
                }
                visible.sort(Comparator.comparingDouble(candidate -> candidate.entity.distanceToSqr(player)));
            }
            Set<UUID> remaining = new HashSet<>();
            int meshUpdates = 0;
            for (int index = 0; index < Math.min(MAX_PER_PLAYER, visible.size()); index++) {
                Tracked candidate = visible.get(index);
                candidate.refresh(level);
                if (candidate.mesh.cells().isEmpty()) continue;
                UUID uuid = candidate.entity.getUUID();
                Sent previous = sent.get(uuid);
                boolean meshChanged = previous == null || previous.mesh() != candidate.mesh;
                if (meshChanged && meshUpdates == MAX_MESH_UPDATES_PER_TICK) {
                    if (previous != null) remaining.add(uuid);
                    continue;
                }
                remaining.add(uuid);
                if (!meshChanged && candidate.pose.equals(previous.pose())) continue;
                if (meshChanged) meshUpdates++;
                PacketDistributor.sendToPlayer(player, new ContraptionLodPayload(dimension, uuid, candidate.pose,
                        meshChanged ? candidate.mesh : null));
                sent.put(uuid, new Sent(candidate.mesh, candidate.pose));
            }
            sent.keySet().removeIf(uuid -> {
                if (remaining.contains(uuid)) return false;
                PacketDistributor.sendToPlayer(player, new ContraptionLodPayload(dimension, uuid, null, null));
                return true;
            });
        }
    }
}
