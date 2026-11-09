package lib.kasuga.rendering.models.mc.scene;

import lib.kasuga.KasugaLib;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;

/** Native renderer caches must follow baked model/atlas replacement, including F3+T. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class ModelPreviewResources {
    private static volatile long generation;
    private ModelPreviewResources() {}
    public static long generation() { return generation; }
    @SubscribeEvent public static void baked(ModelEvent.BakingCompleted event) { generation++; }
}
