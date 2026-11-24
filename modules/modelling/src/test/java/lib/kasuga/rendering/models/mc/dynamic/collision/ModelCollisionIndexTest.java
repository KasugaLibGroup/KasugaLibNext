package lib.kasuga.rendering.models.mc.dynamic.collision;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ModelCollisionIndexTest {
    @Test
    void movingAcrossChunkBoundaryUpdatesBothChunksAndDeduplicatesMovementQueries() {
        ModelCollisionIndex index = new ModelCollisionIndex();
        Object host = new Object();
        AABB crossing = new AABB(15, 0, 0, 17, 2, 1);
        long left = ChunkPos.asLong(0, 0);
        long right = ChunkPos.asLong(1, 0);
        index.replace(host, List.of(crossing));
        assertEquals(Set.of(left, right), index.drainDirtyChunks());
        assertEquals(List.of(crossing), index.snapshot(right));
        assertEquals(1, index.collisions(crossing, null, List.of()).size());

        index.replace(host, List.of(crossing));
        assertTrue(index.drainDirtyChunks().isEmpty());
        AABB moved = crossing.move(2, 0, 0);
        index.replace(host, List.of(moved));
        assertEquals(Set.of(left, right), index.drainDirtyChunks());
        assertTrue(index.snapshot(left).isEmpty());
        assertEquals(List.of(moved), index.snapshot(right));

        index.replace(host, List.of());
        assertTrue(index.collisions(moved, null, List.of()).isEmpty());
        assertEquals(Set.of(right), index.drainDirtyChunks());
    }

    @Test
    void chunkSnapshotPreservesWorldPrecisionWithoutAnAvailableModelAndCanBeUnloaded() {
        AABB box = new AABB(25_000_000.25, 8, -15.875, 25_000_000.75, 9, -15.25);
        long chunk = ChunkPos.asLong(1_562_500, -1);
        ModelCollisionPayload original = new ModelCollisionPayload(ResourceLocation.parse("minecraft:overworld"), chunk, List.of(box));
        FriendlyByteBuf data = new FriendlyByteBuf(Unpooled.buffer());
        try {
            ModelCollisionPayload.CODEC.encode(data, original);
            ModelCollisionPayload decoded = ModelCollisionPayload.CODEC.decode(data);
            assertEquals(original, decoded);
            ModelCollisionIndex client = new ModelCollisionIndex();
            client.receive(decoded.chunk(), decoded.boxes());
            assertEquals(box, client.collisions(box, null, List.of()).getFirst().bounds());
            client.receive(chunk, List.of());
            assertTrue(client.collisions(box, null, List.of()).isEmpty());
        } finally {
            data.release();
        }
    }
}
