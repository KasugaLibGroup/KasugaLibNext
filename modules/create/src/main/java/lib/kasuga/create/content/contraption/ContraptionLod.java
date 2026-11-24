package lib.kasuga.create.content.contraption;

import lib.kasuga.KasugaLib;
import lib.kasuga.create.CreateConfig;
import lib.kasuga.create.client.rendering.ContraptionLodClient;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Optional wire and lifetime hooks, with Create-specific classes loaded only when Create is present. */
@EventBusSubscriber(modid = KasugaLib.MODID)
public final class ContraptionLod {
    public static final float RANGE = 512f;
    public static final int UPDATE_TICKS = 4;

    private ContraptionLod() {}

    @SubscribeEvent
    public static void register(RegisterPayloadHandlersEvent event) {
        if (!ModList.get().isLoaded("create")) return;
        event.registrar("1").optional().playToClient(ContraptionLodPayload.TYPE, ContraptionLodPayload.CODEC,
                (payload, context) -> ContraptionLodClient.apply(context.player().level(), payload));
    }

    @SubscribeEvent
    public static void joined(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && ModList.get().isLoaded("create")) {
            ContraptionLodServer.joined(level, event.getEntity());
        }
    }

    @SubscribeEvent
    public static void left(EntityLeaveLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && ModList.get().isLoaded("create")) {
            ContraptionLodServer.left(level, event.getEntity());
        }
    }

    @SubscribeEvent
    public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ModList.get().isLoaded("create")) {
            ContraptionLodServer.forgetPlayer(player);
        }
    }

    @SubscribeEvent
    public static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && ModList.get().isLoaded("create")) {
            ContraptionLodServer.forgetPlayer(player);
        }
    }

    @SubscribeEvent
    public static void tick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && ModList.get().isLoaded("create")
                && level.getGameTime() % UPDATE_TICKS == 0) {
            ContraptionLodServer.tick(level, CreateConfig.isContraptionLodEnabled());
        }
    }

    @SubscribeEvent
    public static void unload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level && ModList.get().isLoaded("create")) {
            ContraptionLodServer.unload(level);
        }
    }
}
