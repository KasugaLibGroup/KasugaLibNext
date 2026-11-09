package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;
import java.util.Optional;

/** Immutable snapshot of one runtime playback instance. */
public record PlaybackInstanceState(
        PlaybackInstanceId id,
        String definitionId,
        Optional<PlaybackInstanceId> parentId,
        PlaybackBinding binding,
        boolean paused,
        Optional<Double> pausedAtRootTime,
        double accumulatedPauseSeconds
) {

    public PlaybackInstanceState {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(parentId, "parentId");
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(pausedAtRootTime, "pausedAtRootTime");
        pausedAtRootTime.ifPresent(value -> {
            if (!Double.isFinite(value) || value < 0.0) {
                throw new IllegalArgumentException(
                        "pausedAtRootTime must be finite and non-negative");
            }
        });
        if (!Double.isFinite(accumulatedPauseSeconds) || accumulatedPauseSeconds < 0.0) {
            throw new IllegalArgumentException(
                    "accumulatedPauseSeconds must be finite and non-negative");
        }
        if (paused != pausedAtRootTime.isPresent()) {
            throw new IllegalArgumentException(
                    "paused and pausedAtRootTime must describe the same state");
        }
        if (definitionId.isBlank()) {
            throw new IllegalArgumentException("definitionId must not be blank");
        }
        parentId.ifPresent(parent -> {
            if (parent.equals(id)) {
                throw new IllegalArgumentException("a playback instance cannot parent itself");
            }
            if (!parent.authorityScopeId().equals(id.authorityScopeId())) {
                throw new IllegalArgumentException(
                        "parent and child playback instances must share one authority scope");
            }
        });
    }

    public PlaybackInstanceState(
            PlaybackInstanceId id,
            String definitionId,
            Optional<PlaybackInstanceId> parentId,
            PlaybackBinding binding,
            boolean paused
    ) {
        this(id, definitionId, parentId, binding, paused,
                paused ? Optional.of(0.0) : Optional.empty(), 0.0);
    }

    public PlaybackInstanceState withDefinitionId(String newDefinitionId) {
        return new PlaybackInstanceState(
                id, newDefinitionId, parentId, binding,
                paused, pausedAtRootTime, accumulatedPauseSeconds);
    }

    public PlaybackInstanceState withBinding(PlaybackBinding newBinding) {
        return new PlaybackInstanceState(
                id, definitionId, parentId, newBinding,
                paused, pausedAtRootTime, accumulatedPauseSeconds);
    }

    public PlaybackInstanceState withPaused(boolean newPaused, double rootTime) {
        if (!Double.isFinite(rootTime) || rootTime < 0.0) {
            throw new IllegalArgumentException("rootTime must be finite and non-negative");
        }
        if (newPaused == paused) {
            return this;
        }
        if (newPaused) {
            return new PlaybackInstanceState(
                    id, definitionId, parentId, binding,
                    true, Optional.of(rootTime), accumulatedPauseSeconds);
        }
        double pausedDuration = pausedAtRootTime
                .map(time -> Math.max(0.0, rootTime - time))
                .orElse(0.0);
        return new PlaybackInstanceState(
                id, definitionId, parentId, binding,
                false, Optional.empty(), accumulatedPauseSeconds + pausedDuration);
    }
}
