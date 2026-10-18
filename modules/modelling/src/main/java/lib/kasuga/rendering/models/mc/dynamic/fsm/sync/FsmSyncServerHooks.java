package lib.kasuga.rendering.models.mc.dynamic.fsm.sync;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * Wires {@link FsmSyncServer#removePlayer} to player logout so the per-{@code (key, player)}
 * dedup table does not grow unboundedly on long-running servers. A plain {@code @EventBusSubscriber}
 * static listener (same pattern as {@code UIBackend}); deliberately NOT a Micronaut {@code @Context}
 * bean — this class needs no DI and a private constructor would break eager bean instantiation.
 */
@EventBusSubscriber
public final class FsmSyncServerHooks {

    private FsmSyncServerHooks() {
    }

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer serverPlayer) {
            FsmSyncServer.GLOBAL.removePlayer(serverPlayer);
        }
    }
}
