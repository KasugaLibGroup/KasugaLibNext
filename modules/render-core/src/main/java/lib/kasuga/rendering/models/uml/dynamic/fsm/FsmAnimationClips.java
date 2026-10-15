package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The clip registry bucket: {@link AnimationSampler}/{@code data} pairs by {@link Id}, resolved by the
 * data-driven factory when a state definition references a clip. Split from the definition bucket
 * ({@link FsmDefinitions}) on purpose — clips are format-specific animation data (e.g. {@code AnimationClip}),
 * not state-machine structure, and may be registered independently of any machine definition.
 *
 * <p>Clips are <strong>source-bucketed</strong>, mirroring {@link FsmDefinitions}: {@link #register} is the
 * code/scripting path (survives a resource reload), {@link #registerResource} is the reload path (dropped by
 * {@link #clearResource()}). Code clips win over resource clips for the same id ("script wins"): a
 * {@link #register} call replaces a RESOURCE entry, and a later {@link #registerResource} call does not
 * clobber a SCRIPT entry. This keeps a reload from wiping animation registered by scripts, content tests or
 * game tests, and keeps the resource copy from shadowing the code-owned clip it was reloaded against.
 *
 * <p>Script-wins is a <em>write</em>-side invariant enforced by the single {@code clipsById} map plus the
 * {@link #registerResource} guard, so {@link #get} is a plain lookup with a deterministic outcome regardless
 * of registration order — there is no per-source view to merge at read time.
 *
 * <p>Concurrent and dependency-free, mirroring {@link FsmDefinitions}'s simple bucket style.
 */
public final class FsmAnimationClips {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Where a clip came from — drives reload / overwrite semantics. */
    public enum ClipSource {
        /** Registered by the resource-reload path; dropped on {@link #clearResource()}. */
        RESOURCE,
        /** Registered at runtime (code / scripting APIs); survives {@link #clearResource()}. */
        SCRIPT
    }

    /** An animation sampler and the data it interpolates, registered together under one {@link Id}. */
    public record Entry(AnimationSampler<?> sampler, Object data, ClipSource source) {
    }

    private final Map<Id, Entry> clipsById = new ConcurrentHashMap<>();

    /**
     * Register (or overwrite) a code/scripting clip under {@code id}. Rejects a null id / sampler / data.
     * Script clips win over resource clips: if the id is already taken by a RESOURCE entry it is replaced,
     * with a warning — script wins.
     */
    public void register(Id id, AnimationSampler<?> sampler, Object data) {
        validate(id, sampler, data);
        Entry previous = clipsById.put(id, new Entry(sampler, data, ClipSource.SCRIPT));
        if (previous != null && previous.source() == ClipSource.RESOURCE) {
            LOGGER.warn("Script animation clip '{}' overwrote a resource clip; script wins", id);
        }
    }

    /**
     * Register (or overwrite) a reload-sourced clip under {@code id}. Rejects a null id / sampler / data.
     * Does not clobber a SCRIPT clip of the same id (script wins); later RESOURCE writes overwrite earlier
     * ones. {@link #clearResource()} drops everything registered through this method.
     */
    public void registerResource(Id id, AnimationSampler<?> sampler, Object data) {
        validate(id, sampler, data);
        clipsById.compute(id, (k, existing) -> {
            if (existing != null && existing.source() == ClipSource.SCRIPT) {
                return existing;
            }
            return new Entry(sampler, data, ClipSource.RESOURCE);
        });
    }

    private static void validate(Id id, AnimationSampler<?> sampler, Object data) {
        if (id == null) {
            throw new IllegalArgumentException("clip id required");
        }
        if (sampler == null || data == null) {
            throw new IllegalArgumentException("clip sampler/data required");
        }
    }

    /** The clip registered under {@code id}, or {@code null} when absent (or the id is null). */
    @Nullable
    public Entry get(Id id) {
        return id == null ? null : clipsById.get(id);
    }

    /** Remove the clip under {@code id} regardless of source; returns true when an entry was present. */
    public boolean remove(Id id) {
        return id != null && clipsById.remove(id) != null;
    }

    /**
     * Drop every reload-sourced clip; code/scripting clips survive. This is the reload path's clear —
     * {@link #clearResource()} is its explicit name. To drop everything, use {@link #clearAll()}.
     */
    public void clearResource() {
        clipsById.entrySet().removeIf(entry -> entry.getValue().source() == ClipSource.RESOURCE);
    }

    /**
     * Drop every registered clip, regardless of source. Equivalent to the pre-bucketing {@code clear()}
     * behaviour; reserved for teardown/tests, never for a reload.
     */
    public void clearAll() {
        clipsById.clear();
    }

    /**
     * Drop every reload-sourced clip; code/scripting clips survive — an alias of {@link #clearResource()}.
     * Note the semantics changed with source bucketing: this no longer wipes code-registered clips. Use
     * {@link #clearAll()} for a full wipe.
     */
    public void clear() {
        clearResource();
    }
}
