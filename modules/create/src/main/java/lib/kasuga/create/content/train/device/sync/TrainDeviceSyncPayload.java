package lib.kasuga.create.content.train.device.sync;

import com.mojang.logging.LogUtils;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 设备状态同步包：一个包只携带一个设备（system）的状态变化。
 *
 * <ul>
 *   <li>{@link Op#FULL}：玩家刚进入视野 / 新加入，发送该设备的完整同步快照；</li>
 *   <li>{@link Op#DELTA}：只有字段发生变化时才发，{@code changed} 只含变化的字段，
 *       {@code removedKeys} 是本轮被移除的字段名；</li>
 *   <li>{@link Op#CLEAR}：列车拆解 / 注销，客户端清掉该列车的整份镜像。</li>
 * </ul>
 */
public record TrainDeviceSyncPayload(
        UUID trainId,
        @Nullable ResourceLocation systemId,
        Op op,
        int sequence,
        CompoundTag changed,
        List<String> removedKeys
) implements CustomPacketPayload {

    private static final Logger LOGGER = LogUtils.getLogger();

    public enum Op {
        FULL,
        DELTA,
        CLEAR
    }

    public static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("kasuga_lib", "train_device_sync");
    public static final CustomPacketPayload.Type<TrainDeviceSyncPayload> TYPE = new CustomPacketPayload.Type<>(ID);

    public static final StreamCodec<FriendlyByteBuf, TrainDeviceSyncPayload> CODEC =
            StreamCodec.of(TrainDeviceSyncPayload::encode, TrainDeviceSyncPayload::decode);

    public TrainDeviceSyncPayload {
        changed = changed == null ? new CompoundTag() : changed;
        removedKeys = removedKeys == null ? List.of() : List.copyOf(removedKeys);
    }

    public static TrainDeviceSyncPayload full(UUID trainId, int sequence, ResourceLocation systemId, CompoundTag snapshot) {
        return new TrainDeviceSyncPayload(trainId, systemId, Op.FULL, sequence, snapshot.copy(), List.of());
    }

    public static TrainDeviceSyncPayload delta(UUID trainId, int sequence, ResourceLocation systemId,
                                               CompoundTag changed, List<String> removedKeys) {
        return new TrainDeviceSyncPayload(trainId, systemId, Op.DELTA, sequence, changed, removedKeys);
    }

    public static TrainDeviceSyncPayload clear(UUID trainId, int sequence) {
        return new TrainDeviceSyncPayload(trainId, null, Op.CLEAR, sequence, new CompoundTag(), List.of());
    }

    private static void encode(FriendlyByteBuf buffer, TrainDeviceSyncPayload payload) {
        buffer.writeUUID(payload.trainId);
        buffer.writeVarInt(payload.op.ordinal());
        buffer.writeVarInt(payload.sequence);
        if (payload.op == Op.CLEAR) {
            return;
        }
        buffer.writeResourceLocation(payload.systemId);
        buffer.writeNbt(payload.changed);
        buffer.writeVarInt(payload.removedKeys.size());
        for (String key : payload.removedKeys) {
            buffer.writeUtf(key);
        }
    }

    private static TrainDeviceSyncPayload decode(FriendlyByteBuf buffer) {
        UUID trainId = buffer.readUUID();
        int ordinal = buffer.readVarInt();
        Op[] values = Op.values();
        Op op = ordinal >= 0 && ordinal < values.length ? values[ordinal] : Op.CLEAR;
        int sequence = buffer.readVarInt();
        if (op == Op.CLEAR) {
            return clear(trainId, sequence);
        }
        ResourceLocation systemId = buffer.readResourceLocation();
        CompoundTag changed = buffer.readNbt();
        int removed = buffer.readVarInt();
        List<String> removedKeys = new ArrayList<>(removed);
        for (int i = 0; i < removed; i++) {
            removedKeys.add(buffer.readUtf());
        }
        return new TrainDeviceSyncPayload(trainId, systemId, op, sequence, changed, removedKeys);
    }

    /** 客户端处理入口：只接受客户端方向的包，服务端收到就丢弃并记 debug。 */
    public static void handle(TrainDeviceSyncPayload payload, IPayloadContext context) {
        if (context.player() == null || !context.player().level().isClientSide) {
            LOGGER.debug("忽略非客户端方向的设备同步包 {}", payload.trainId);
            return;
        }
        context.enqueueWork(() -> TrainDeviceSyncClient.apply(payload));
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
