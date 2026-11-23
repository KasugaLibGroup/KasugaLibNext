package lib.kasuga.rendering.models.uml.dynamic.proxy;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TimelineEventDefinition;

import java.util.List;
import java.util.Objects;

/** One playback root installed when a Proxy Session constructs its animation Scope. */
public record ProxyPlaybackRoot(
        String definitionId,
        PlaybackBinding binding,
        List<TimelineEventDefinition> events
) {

    public ProxyPlaybackRoot {
        Objects.requireNonNull(definitionId, "definitionId");
        if (definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        Objects.requireNonNull(binding, "binding");
        events = List.copyOf(Objects.requireNonNull(events, "events"));
    }

    public ProxyPlaybackRoot(String definitionId, PlaybackBinding binding) {
        this(definitionId, binding, List.of());
    }
}
