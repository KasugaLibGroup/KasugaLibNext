package lib.kasuga.rendering.output.mc.stream;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
@EventBusSubscriber(value = Dist.CLIENT) public final class CameraWorldClientEvents {
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) { CameraChunkClient.tickEntities(); }
}
