package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The clip registry bucket: {@link AnimationSampler}/{@code data} pairs by {@link Id}, resolved by the
 * data-driven factory when a state definition references a clip. Split from the definition bucket
 * ({@link FsmDefinitions}) on purpose — clips are format-specific animation data (e.g. {@code AnimationClip}),
 * not state-machine structure, and may be registered independently of any machine definition.
 *
 * <p>Concurrent and dependency-free, mirroring {@link FsmDefinitions}'s simple bucket style.
 */
public final class FsmAnimationClips {

    /** An animation sampler and the data it interpolates, registered together under one {@link Id}. */
    public record Entry(AnimationSampler<?> sampler, Object data) {
    }

    private final Map<Id, Entry> clipsById = new ConcurrentHashMap<>();

    /** Register (or overwrite) a clip under {@code id}. Rejects a null id / sampler / data. */
    public void register(Id id, AnimationSampler<?> sampler, Object data) {
        if (id == null) {
            throw new IllegalArgumentException("clip id required");
        }
        if (sampler == null || data == null) {
            throw new IllegalArgumentException("clip sampler/data required");
        }
        clipsById.put(id, new Entry(sampler, data));
    }

    /** The clip registered under {@code id}, or {@code null} when absent (or the id is null). */
    @Nullable
    public Entry get(Id id) {
        return id == null ? null : clipsById.get(id);
    }

    /** Remove the clip under {@code id}; returns true when an entry was present. */
    public boolean remove(Id id) {
        return id != null && clipsById.remove(id) != null;
    }

    /** Drop every registered clip. */
    public void clear() {
        clipsById.clear();
    }
}