package lib.kasuga.create.client.rendering;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Advances the proxies hosted by {@link ProxyBogeyVisual} on the game tick, not inside Flywheel's
 * frame.
 *
 * <p>Create calls {@code BogeyVisual#update} once per <b>frame</b>, from Flywheel's {@code beginFrame}
 * (render phase). Binding models there would create pipeline instances mid-frame and would advance a
 * host's logic at frame rate. This ticker keeps that split: the visual only publishes angle and
 * pose, the tick runs where the block-entity host runs it, once per game tick.
 *
 * <p>Registration is by visual instance; visuals delete themselves when they are disposed, so the
 * set stays bounded by the loaded carriages.
 */
@EventBusSubscriber(value = Dist.CLIENT)
public final class ContraptionBogeyTickers {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Set<ProxyBogeyVisual> LIVE = ConcurrentHashMap.newKeySet();

    private ContraptionBogeyTickers() {
    }

    static void register(ProxyBogeyVisual visual) {
        LIVE.add(visual);
    }

    static void unregister(ProxyBogeyVisual visual) {
        LIVE.remove(visual);
    }

    /** Number of visuals currently ticking; diagnostics. */
    public static int liveVisuals() {
        return LIVE.size();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        if (LIVE.isEmpty()) {
            return;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }
        for (ProxyBogeyVisual visual : List.copyOf(LIVE)) {
            if (visual.session() == null) {
                continue;
            }
            try {
                visual.tickProxy(level);
            } catch (RuntimeException failure) {
                // One broken bogey must not take the whole client tick down with it.
                LOGGER.error("[proxy] contraption bogey tick failed", failure);
            }
        }
    }
}
