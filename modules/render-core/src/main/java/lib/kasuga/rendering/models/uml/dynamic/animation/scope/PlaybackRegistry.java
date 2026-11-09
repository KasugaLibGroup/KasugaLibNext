package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Owns active playback identities and lifecycle state for one authority scope.
 *
 * <p>It allocates instance IDs and manages parent/child relationships. It does not calculate local
 * time, scan events or sample content.
 */
public final class PlaybackRegistry {

    private final UUID authorityScopeId;
    private final Map<PlaybackInstanceId, PlaybackInstanceState> instances;
    private long nextSequence = 1;

    public PlaybackRegistry(UUID authorityScopeId) {
        this.authorityScopeId = Objects.requireNonNull(authorityScopeId, "authorityScopeId");
        this.instances = new LinkedHashMap<>();
    }

    public PlaybackInstanceId addRoot(String definitionId, PlaybackBinding binding) {
        return add(definitionId, binding, Optional.empty());
    }

    public PlaybackInstanceId addChild(
            PlaybackInstanceId parentId,
            String definitionId,
            PlaybackBinding binding
    ) {
        Objects.requireNonNull(parentId, "parentId");
        requireKnown(parentId);
        requireScope(parentId);
        return add(definitionId, binding, Optional.of(parentId));
    }

    public void stopSubtree(PlaybackInstanceId rootId) {
        requireKnown(rootId);
        for (PlaybackInstanceId id : subtreeIds(rootId)) {
            instances.remove(id);
        }
    }

    public void pauseSubtree(PlaybackInstanceId rootId) {
        pauseSubtree(rootId, 0.0);
    }

    public void resumeSubtree(PlaybackInstanceId rootId) {
        resumeSubtree(rootId, 0.0);
    }

    public void pauseSubtree(PlaybackInstanceId rootId, double rootTime) {
        updateSubtree(rootId, state -> state.withPaused(true, rootTime));
    }

    public void resumeSubtree(PlaybackInstanceId rootId, double rootTime) {
        updateSubtree(rootId, state -> state.withPaused(false, rootTime));
    }

    public void setSpeed(PlaybackInstanceId id, double speed) {
        update(id, state -> state.withBinding(state.binding().withSpeed(speed)));
    }

    public void setBoundaryPolicy(
            PlaybackInstanceId id,
            PlaybackBoundaryPolicy boundaryPolicy
    ) {
        update(id, state ->
                state.withBinding(state.binding().withBoundaryPolicy(boundaryPolicy)));
    }

    public void replace(PlaybackInstanceId id, String definitionId) {
        update(id, state -> state.withDefinitionId(definitionId));
    }

    public PlaybackInstanceState state(PlaybackInstanceId id) {
        Objects.requireNonNull(id, "id");
        PlaybackInstanceState state = instances.get(id);
        if (state == null) {
            throw new IllegalArgumentException("unknown playback instance: " + id);
        }
        return state;
    }

    public PlaybackRegistrySnapshot snapshot() {
        return new PlaybackRegistrySnapshot(List.copyOf(instances.values()));
    }

    public List<PlaybackView> playbacks() {
        return instances.values().stream()
                .map(state -> new PlaybackView(
                        state.id(),
                        state.definitionId(),
                        state.parentId().orElse(null),
                        0.0,
                        state.binding().speed(),
                        state.paused()))
                .toList();
    }

    public List<PlaybackView> views() {
        return playbacks();
    }

    private PlaybackInstanceId add(
            String definitionId,
            PlaybackBinding binding,
            Optional<PlaybackInstanceId> parentId
    ) {
        Objects.requireNonNull(definitionId, "definitionId");
        Objects.requireNonNull(binding, "binding");
        if (nextSequence == Long.MAX_VALUE) {
            throw new IllegalStateException("playback instance sequence exhausted");
        }
        PlaybackInstanceId id = new PlaybackInstanceId(authorityScopeId, nextSequence++);
        PlaybackInstanceState state =
                new PlaybackInstanceState(
                        id, definitionId, parentId, binding,
                        false, Optional.empty(), 0.0);
        PlaybackInstanceState previous = instances.putIfAbsent(id, state);
        if (previous != null) {
            throw new IllegalStateException("duplicate playback instance id: " + id);
        }
        return id;
    }

    private void updateSubtree(
            PlaybackInstanceId rootId,
            StateUpdate update
    ) {
        requireKnown(rootId);
        for (PlaybackInstanceId id : subtreeIds(rootId)) {
            update(id, update);
        }
    }

    private void update(PlaybackInstanceId id, StateUpdate update) {
        PlaybackInstanceState current = state(id);
        instances.put(id, update.apply(current));
    }

    private List<PlaybackInstanceId> subtreeIds(PlaybackInstanceId rootId) {
        requireKnown(rootId);
        List<PlaybackInstanceId> result = new ArrayList<>();
        Deque<PlaybackInstanceId> pending = new ArrayDeque<>();
        pending.add(rootId);
        while (!pending.isEmpty()) {
            PlaybackInstanceId id = pending.removeFirst();
            result.add(id);
            for (PlaybackInstanceState state : instances.values()) {
                if (state.parentId().filter(id::equals).isPresent()) {
                    pending.addLast(state.id());
                }
            }
        }
        return List.copyOf(result);
    }

    private void requireKnown(PlaybackInstanceId id) {
        Objects.requireNonNull(id, "id");
        if (!instances.containsKey(id)) {
            throw new IllegalArgumentException("unknown playback instance: " + id);
        }
    }

    private void requireScope(PlaybackInstanceId id) {
        if (!authorityScopeId.equals(id.authorityScopeId())) {
            throw new IllegalArgumentException(
                    "playback instance belongs to another authority scope: " + id);
        }
    }

    @FunctionalInterface
    private interface StateUpdate {
        PlaybackInstanceState apply(PlaybackInstanceState state);
    }
}
