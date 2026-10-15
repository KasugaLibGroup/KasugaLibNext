package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Named sampler/data bindings shared by a model's instances. Register while loading, before playback. */
public final class AnimationLibrary {
    private final Map<String, Clip<?>> clips = new LinkedHashMap<>();

    /** A format adapter remains behind its sampler; the runtime only sees duration and Pose. */
    public record Clip<T>(String name, AnimationSampler<T> sampler, T data) {
        public Clip {
            Objects.requireNonNull(sampler, "sampler");
            Objects.requireNonNull(data, "data");
        }
        public float duration() { return sampler.duration(data); }
        public Pose sample(float seconds) { return sampler.sample(data, seconds); }
        public Pose sample(float seconds, @Nullable Namespace namespace) { return sampler.sample(data, seconds, namespace); }
    }

    public <T> Clip<T> register(String name, AnimationSampler<T> sampler, T data) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("empty animation name");
        Clip<T> clip = new Clip<>(name, sampler, data);
        clips.put(name, clip);
        return clip;
    }

    public Clip<?> get(String name) { return clips.get(name); }
    public boolean contains(String name) { return clips.containsKey(name); }
    public Map<String, Clip<?>> clips() { return Collections.unmodifiableMap(clips); }

    public static final AnimationSampler<Clip<?>> SAMPLER = new AnimationSampler<>() {
        @Override public float duration(Clip<?> clip) { return clip.duration(); }
        @Override public Pose sample(Clip<?> clip, float seconds) { return clip.sample(seconds); }
        @Override public Pose sample(Clip<?> clip, float seconds, @Nullable Namespace namespace) {
            return clip.sample(seconds, namespace);
        }
    };

    private static final AnimationSampler<Pose> STATIC_POSE = new AnimationSampler<>() {
        @Override public float duration(Pose pose) { return 0; }
        @Override public Pose sample(Pose pose, float seconds) { return pose; }
    };

    /** An unnamed constant clip reuses the same playback and channel-reset path as animations. */
    public static Clip<Pose> pose(Pose pose) { return new Clip<>(null, STATIC_POSE, pose); }
}
