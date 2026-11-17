package lib.kasuga.create.content.train.sync;

import com.simibubi.create.Create;
import com.simibubi.create.content.trains.bogey.AbstractBogeyBlock;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageBogey;
import com.simibubi.create.content.trains.entity.Train;
import lib.kasuga.KasugaLibApplication;
import lib.kasuga.core.networking.rpc.Rpc;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.content.train.CarriageAccess;
import lib.kasuga.create.mixins.CarriageBogeyAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pushes the interior bogeys of multi-bogey carriages to clients over KasugaLibNext's RPC channel.
 *
 * <p>A separate channel is required because client carriages are decoded from
 * {@code Train.STREAM_CODEC}, which only carries the two real bogeys and {@code bogeySpacing}; the
 * lists added by {@code CarriageMixin} arrive empty regardless of what {@code write} saved.
 *
 * <p>One payload per carriage with extras: {@code (trainId, carriageIndex, List<Entry>)}, with
 * carriage-local positions (same frame as {@code Contraption#toLocalPos}). The block type travels
 * as a {@link ResourceLocation} because the RPC codec only has a {@link FriendlyByteBuf}. Both
 * senders run after the train itself was sent, so the client already knows it. The handler replaces
 * both lists, making repeated or late syncs idempotent.
 *
 * <p>Registration is forced by {@link CarriageExtraBogeySyncRegistrar} during mod construction.
 */
public final class CarriageExtraBogeySync {

    private CarriageExtraBogeySync() {
    }

    /** One interior bogey: carriage-local position, type, orientation and style data. */
    public record Entry(BlockPos position, ResourceLocation type, boolean upsideDown, CompoundTag bogeyData) {

        public static final StreamCodec<FriendlyByteBuf, Entry> CODEC = StreamCodec.composite(
                BlockPos.STREAM_CODEC, Entry::position,
                ResourceLocation.STREAM_CODEC, Entry::type,
                ByteBufCodecs.BOOL, Entry::upsideDown,
                ByteBufCodecs.COMPOUND_TAG, Entry::bogeyData,
                Entry::new);

        public static final StreamCodec<FriendlyByteBuf, List<Entry>> LIST_CODEC =
                CODEC.apply(ByteBufCodecs.list());
    }

    /** Server → client RPC, handled on the client main thread. */
    public static final Rpc.RpcV3<UUID, Integer, List<Entry>> SYNC = Rpc.wrap(
                    "train/carriage_extra_bogeys",
                    CarriageExtraBogeySync::handle,
                    UUIDUtil.STREAM_CODEC,
                    ByteBufCodecs.VAR_INT,
                    Entry.LIST_CODEC)
            .executeOnClient()
            .runOnMainThread()
            .register(KasugaLibApplication.REGISTRY);

    /** Sends every carriage of {@code train} that has interior bogeys to one player. */
    public static void send(ServerPlayer player, Train train) {
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        for (int carriageIndex = 0; carriageIndex < train.carriages.size(); carriageIndex++) {
            List<Entry> entries = entriesOf(train.carriages.get(carriageIndex));
            if (!entries.isEmpty())
                SYNC.call(player, train.id, carriageIndex, entries);
        }
    }

    /** Snapshots one carriage's interior bogeys into wire entries. */
    private static List<Entry> entriesOf(Carriage carriage) {
        CarriageAccess access = (CarriageAccess) (Object) carriage;
        List<CarriageBogey> bogeys = access.kasuga$getRedundantBogeys();
        List<BlockPos> positions = access.kasuga$getRedundantBogeyPositions();
        int count = Math.min(bogeys.size(), positions.size());
        if (count == 0)
            return List.of();

        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            CarriageBogey bogey = bogeys.get(i);
            entries.add(new Entry(
                    positions.get(i),
                    BuiltInRegistries.BLOCK.getKey(((CarriageBogeyAccessor) (Object) bogey).kasuga$getType()),
                    bogey.isUpsideDown(),
                    bogey.bogeyData.copy()));
        }
        return entries;
    }

    /**
     * Client side: replaces the carriage's interior bogeys with the server's data. Unknown block
     * types are skipped together with their positions so the lists stay aligned.
     */
    private static void handle(IPayloadContext context, UUID trainId, int carriageIndex, List<Entry> entries) {
        if (!CreateConfig.isMultiBogeyCarriageEnabled())
            return;
        Player player = context.player();
        if (player == null || !player.level().isClientSide())
            return;

        Level level = player.level();
        Train train = Create.RAILWAYS.sided(level).trains.get(trainId);
        if (train == null || carriageIndex < 0 || carriageIndex >= train.carriages.size())
            return;

        Carriage carriage = train.carriages.get(carriageIndex);
        CarriageAccess access = (CarriageAccess) (Object) carriage;
        access.kasuga$getRedundantBogeys().clear();
        access.kasuga$getRedundantBogeyPositions().clear();

        for (Entry entry : entries) {
            if (!(BuiltInRegistries.BLOCK.get(entry.type()) instanceof AbstractBogeyBlock<?> type))
                continue;
            CarriageBogey bogey = new CarriageBogey(type, entry.upsideDown(), entry.bogeyData().copy());
            bogey.carriage = carriage;
            access.kasuga$addRedundantBogeys(List.of(bogey));
            access.kasuga$addRedundantBogeyPositions(List.of(entry.position()));
        }
    }
}
