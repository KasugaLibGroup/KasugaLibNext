package lib.kasuga.rendering.models.mc.multiplexer;

import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Blackboard;
import lib.kasuga.rendering.models.uml.dynamic.multiplexer.Context;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A Minecraft block-context implementation of {@link Context}.
 *
 * <p>It carries the property map and the most common Minecraft channels (neighbor block ids, redstone
 * power, time-of-day, tags) plus an open {@link Blackboard} for custom sensors.
 */
public record McContext(
        Map<String, String> properties,
        List<String> neighbors,
        int redstonePower,
        long dayTime,
        Set<ResourceLocation> tags,
        Blackboard data
) implements Context {

    public McContext {
        properties = Map.copyOf(properties);
        neighbors = List.copyOf(neighbors);
        tags = Set.copyOf(tags);
        data = data != null ? data : Blackboard.empty();
    }

    public McContext(Map<String, String> properties, List<String> neighbors,
                     int redstonePower, long dayTime, Set<ResourceLocation> tags) {
        this(properties, neighbors, redstonePower, dayTime, tags, Blackboard.empty());
    }

    @Override
    public String property(String name) {
        return properties.get(name);
    }

    @Override
    public Blackboard data() {
        return data;
    }
}
