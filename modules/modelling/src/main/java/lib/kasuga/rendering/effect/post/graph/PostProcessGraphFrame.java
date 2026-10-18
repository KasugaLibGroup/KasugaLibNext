package lib.kasuga.rendering.effect.post.graph;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Per-execution state shared by a graph's preparation step and passes. */
public final class PostProcessGraphFrame {
    private final Map<ResourceLocation, Object> values = new HashMap<>();

    public <T> PostProcessGraphFrame put(ResourceLocation key, T value) {
        values.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
        return this;
    }

    public <T> Optional<T> find(ResourceLocation key, Class<T> type) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(type, "type");
        Object value = values.get(key);
        if (value == null) return Optional.empty();
        if (!type.isInstance(value)) {
            throw new IllegalStateException("Graph frame value " + key + " is not a " + type.getName());
        }
        return Optional.of(type.cast(value));
    }

    public <T> T require(ResourceLocation key, Class<T> type) {
        return find(key, type).orElseThrow(() ->
                new IllegalStateException("Missing graph frame value: " + key));
    }
}
