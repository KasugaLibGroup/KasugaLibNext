package lib.kasuga.create.content.contraption;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ContraptionLodTest {
    @Test
    void coarseGridKeepsNegativeLocalCoordinatesAndDominantState() {
        ContraptionLodMesh lod = ContraptionLodMesh.simplify(List.of(
                new ContraptionLodMesh.Cell(new BlockPos(-2, 0, 0), 3),
                new ContraptionLodMesh.Cell(new BlockPos(-1, 0, 0), 2),
                new ContraptionLodMesh.Cell(new BlockPos(-1, 1, 0), 3),
                new ContraptionLodMesh.Cell(new BlockPos(0, 0, 0), 7)));
        assertEquals(2, lod.scale());
        assertEquals(2, lod.cells().size());
        assertTrue(lod.cells().contains(new ContraptionLodMesh.Cell(new BlockPos(-1, 0, 0), 3)));
        assertTrue(lod.cells().contains(new ContraptionLodMesh.Cell(BlockPos.ZERO, 7)));
    }

    @Test
    void largeContraptionUsesACoarserGrid() {
        List<ContraptionLodMesh.Cell> blocks = new ArrayList<>();
        for (int x = 0; x < 90; x += 2) {
            for (int y = 0; y < 10; y += 2) {
                for (int z = 0; z < 90; z += 2) {
                    blocks.add(new ContraptionLodMesh.Cell(new BlockPos(x, y, z), 1));
                }
            }
        }
        ContraptionLodMesh lod = ContraptionLodMesh.simplify(blocks);
        assertEquals(4, lod.scale());
        assertTrue(lod.cells().size() < blocks.size());
        assertTrue(lod.cells().size() <= ContraptionLodMesh.MAX_CELLS);
    }

    @Test
    void fullSnapshotPoseUpdateAndRemovalRoundTrip() {
        ResourceLocation dimension = ResourceLocation.parse("minecraft:overworld");
        UUID uuid = UUID.randomUUID();
        ContraptionLodPayload.Pose pose = new ContraptionLodPayload.Pose(77,
                new Vec3(-25_000_000.375, 123, 25_000_000.625), 0, 0, 0, 1, 0);
        ContraptionLodMesh mesh = new ContraptionLodMesh(2,
                List.of(new ContraptionLodMesh.Cell(new BlockPos(0, -2, 0), 5)));
        for (ContraptionLodPayload packet : List.of(new ContraptionLodPayload(dimension, uuid, pose, mesh),
                new ContraptionLodPayload(dimension, uuid, pose, null), new ContraptionLodPayload(dimension, uuid, null, null))) {
            FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
            try {
                ContraptionLodPayload.CODEC.encode(buffer, packet);
                assertEquals(packet, ContraptionLodPayload.CODEC.decode(buffer));
                assertEquals(0, buffer.readableBytes());
            } finally {
                buffer.release();
            }
        }
    }
}
