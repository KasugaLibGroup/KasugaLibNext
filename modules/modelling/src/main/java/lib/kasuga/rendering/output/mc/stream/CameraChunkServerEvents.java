package lib.kasuga.rendering.output.mc.stream;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
@EventBusSubscriber public final class CameraChunkServerEvents {
    @SubscribeEvent public static void tick(ServerTickEvent.Post event) { CameraChunkServer.tick(event.getServer()); }
    @SubscribeEvent public static void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) CameraChunkServer.removePlayer(p.getUUID());
    }
    @SubscribeEvent public static void stop(ServerStoppingEvent event) { CameraChunkServer.stop(event.getServer()); }
}
