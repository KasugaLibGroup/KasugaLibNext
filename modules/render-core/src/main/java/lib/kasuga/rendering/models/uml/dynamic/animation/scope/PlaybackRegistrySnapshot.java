package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable playback graph input for time resolution, event scanning and diagnostics. */
public record PlaybackRegistrySnapshot(List<PlaybackInstanceState> instances) {

    public PlaybackRegistrySnapshot {
        Objects.requireNonNull(instances, "instances");
        instances = List.copyOf(instances);
        Set<PlaybackInstanceId> ids = new HashSet<>();
        for (PlaybackInstanceState instance : instances) {
            Objects.requireNonNull(instance, "instance");
            if (!ids.add(instance.id())) {
                throw new IllegalArgumentException("duplicate playback instance id: " + instance.id());
            }
        }
    }

    public Optional<PlaybackInstanceState> find(PlaybackInstanceId id) {
        Objects.requireNonNull(id, "id");
        return instances.stream()
                .filter(instance -> instance.id().equals(id))
                .findFirst();
    }
}
