package lib.kasuga.rendering.effect.shader;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;

/** Immutable public state of one exact shader handle. */
public record ShaderStatus(
        ShaderLoadState state,
        ShaderLoadOrigin origin,
        int queuePosition,
        long queueWaitNanos,
        long generation,
        long preparationNanos,
        boolean translationCacheHit,
        long compileNanos,
        @Nullable String error
) {
    public Optional<String> failure() {
        return Optional.ofNullable(error);
    }
}
