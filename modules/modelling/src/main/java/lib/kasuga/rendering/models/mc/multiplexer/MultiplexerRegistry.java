package lib.kasuga.rendering.models.mc.multiplexer;

import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Multiplexer;
import net.minecraft.resources.ResourceLocation;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Per-block-type cache of Minecraft multiplexers. Because a multiplexer definition is stateless,
 * one shared instance safely serves every placed block of that type.
 */
public final class MultiplexerRegistry {

    private final ConcurrentHashMap<ResourceLocation, Multiplexer<McContext, McVariant>> byBlockType = new ConcurrentHashMap<>();

    public Multiplexer<McContext, McVariant> resolve(
            ResourceLocation blockId,
            Function<ResourceLocation, Multiplexer<McContext, McVariant>> factory) {
        return byBlockType.computeIfAbsent(blockId, factory);
    }

    public void register(ResourceLocation blockId, Multiplexer<McContext, McVariant> multiplexer) {
        byBlockType.put(blockId, multiplexer);
    }

    public void clear() {
        byBlockType.clear();
    }
}
