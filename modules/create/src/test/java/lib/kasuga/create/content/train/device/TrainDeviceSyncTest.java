package lib.kasuga.create.content.train.device;

import io.netty.buffer.Unpooled;
import lib.kasuga.create.content.train.device.sync.TrainDeviceSyncClient;
import lib.kasuga.create.content.train.device.sync.TrainDeviceSyncDiff;
import lib.kasuga.create.content.train.device.sync.TrainDeviceSyncPayload;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-02：payload 编解码、字段级 diff、客户端镜像（全量 / 增量 / 清空 / 过期包）。
 */
class TrainDeviceSyncTest {

    private static final ResourceLocation SYSTEM = ResourceLocation.fromNamespaceAndPath("kasuga_tds_test", "system");
    private static final UUID TRAIN = UUID.nameUUIDFromBytes("sync-train".getBytes());

    @BeforeEach
    void clearMirror() {
        TrainDeviceSyncClient.clearAll();
    }

    private static CompoundTag snapshot(String... keyValues) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("SchemaVersion", 1);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            tag.putString(keyValues[i], keyValues[i + 1]);
        }
        return tag;
    }

    @Test
    void fullPayloadSurvivesACodecRoundtrip() {
        CompoundTag data = snapshot("a", "1", "b", "2");
        TrainDeviceSyncPayload payload = TrainDeviceSyncPayload.full(TRAIN, 3, SYSTEM, data);

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        TrainDeviceSyncPayload.CODEC.encode(buffer, payload);
        TrainDeviceSyncPayload decoded = TrainDeviceSyncPayload.CODEC.decode(buffer);

        assertEquals(TRAIN, decoded.trainId());
        assertEquals(SYSTEM, decoded.systemId());
        assertEquals(TrainDeviceSyncPayload.Op.FULL, decoded.op());
        assertEquals(3, decoded.sequence());
        assertEquals("1", decoded.changed().getString("a"));
    }

    @Test
    void deltaPayloadCarriesChangedAndRemovedKeys() {
        TrainDeviceSyncPayload payload = TrainDeviceSyncPayload.delta(
                TRAIN, 4, SYSTEM, snapshot("a", "9"), List.of("b"));

        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        TrainDeviceSyncPayload.CODEC.encode(buffer, payload);
        TrainDeviceSyncPayload decoded = TrainDeviceSyncPayload.CODEC.decode(buffer);

        assertEquals(TrainDeviceSyncPayload.Op.DELTA, decoded.op());
        assertEquals(List.of("b"), decoded.removedKeys());
        assertEquals("9", decoded.changed().getString("a"));
    }

    @Test
    void clearPayloadRoundtripsWithoutASystemId() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        TrainDeviceSyncPayload.CODEC.encode(buffer, TrainDeviceSyncPayload.clear(TRAIN, 7));
        TrainDeviceSyncPayload decoded = TrainDeviceSyncPayload.CODEC.decode(buffer);

        assertEquals(TrainDeviceSyncPayload.Op.CLEAR, decoded.op());
        assertNull(decoded.systemId());
    }

    @Test
    void identicalSnapshotsProduceNoDiff() {
        CompoundTag current = snapshot("a", "1");
        assertTrue(TrainDeviceSyncDiff.between(current, snapshot("a", "1")).isEmpty(),
                "状态没变就不能发包");
    }

    @Test
    void diffOnlyCarriesChangedFieldsAndRemovalMarker() {
        CompoundTag current = snapshot("a", "2", "c", "3");
        CompoundTag previous = snapshot("a", "1", "b", "9");

        TrainDeviceSyncDiff diff = TrainDeviceSyncDiff.between(current, previous);

        assertEquals("2", diff.changed().getString("a"));
        assertEquals("3", diff.changed().getString("c"));
        assertFalse(diff.changed().contains("b"), "被移除的字段不放进 changed");
        assertEquals(List.of("b"), diff.removedKeys());
        assertFalse(diff.isEmpty());
    }

    @Test
    void diffAppliedOnClientReproducesTheServerSnapshot() {
        CompoundTag previous = snapshot("a", "1", "b", "9");
        CompoundTag current = snapshot("a", "2", "c", "3");
        TrainDeviceSyncDiff diff = TrainDeviceSyncDiff.between(current, previous);

        CompoundTag merged = TrainDeviceSyncDiff.apply(previous.copy(), diff);

        assertEquals("2", merged.getString("a"));
        assertEquals("3", merged.getString("c"));
        assertFalse(merged.contains("b"));
    }

    @Test
    void clientMirrorKeepsLatestFullSnapshotAndDropsStalePackets() {
        TrainDeviceSyncClient.apply(TrainDeviceSyncPayload.full(TRAIN, 5, SYSTEM, snapshot("a", "1")));
        assertEquals(Optional.of("1"), TrainDeviceSyncClient.getOrNull(TRAIN, SYSTEM) == null
                ? Optional.empty()
                : Optional.of(TrainDeviceSyncClient.getOrNull(TRAIN, SYSTEM).getString("a")));

        TrainDeviceSyncClient.apply(TrainDeviceSyncPayload.full(TRAIN, 4, SYSTEM, snapshot("a", "stale")));
        assertEquals("1", TrainDeviceSyncClient.getOrNull(TRAIN, SYSTEM).getString("a"),
                "sequence 更小的过期包必须被丢弃");
    }

    @Test
    void clientMirrorMergesDeltasAndAppliesClear() {
        TrainDeviceSyncClient.apply(TrainDeviceSyncPayload.full(TRAIN, 1, SYSTEM, snapshot("a", "1", "b", "2")));
        TrainDeviceSyncClient.apply(TrainDeviceSyncPayload.delta(TRAIN, 2, SYSTEM, snapshot("a", "7"), List.of("b")));

        CompoundTag mirrored = TrainDeviceSyncClient.getOrNull(TRAIN, SYSTEM);
        assertEquals("7", mirrored.getString("a"), "增量字段被合并");
        assertFalse(mirrored.contains("b"), "增量里的 removedKeys 要被删掉");

        TrainDeviceSyncClient.apply(TrainDeviceSyncPayload.clear(TRAIN, 3));
        assertNull(TrainDeviceSyncClient.getOrNull(TRAIN, SYSTEM), "拆车清空后镜像必须消失");
        assertFalse(TrainDeviceSyncClient.isTracking(TRAIN));
    }
}
