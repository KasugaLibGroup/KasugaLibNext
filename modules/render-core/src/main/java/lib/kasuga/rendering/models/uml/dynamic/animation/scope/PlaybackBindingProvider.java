package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.jetbrains.annotations.Nullable;

/** Resolves a named playback definition to its current immutable time binding. */
@FunctionalInterface
public interface PlaybackBindingProvider {

    @Nullable
    PlaybackBinding binding(String definitionId);
}
