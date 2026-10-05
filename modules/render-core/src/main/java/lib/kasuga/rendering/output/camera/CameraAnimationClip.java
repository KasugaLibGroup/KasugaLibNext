package lib.kasuga.rendering.output.camera;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.output.WorldCameraView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Independent world-position, Euler-angle and optical-zoom tracks. Times are seconds, angles degrees. */
public record CameraAnimationClip(Id id, float durationSeconds, List<Track> tracks) {
    public CameraAnimationClip {
        Objects.requireNonNull(id, "id");
        if (!Float.isFinite(durationSeconds) || durationSeconds < 0)
            throw new IllegalArgumentException("Duration must be finite and non-negative");
        tracks = List.copyOf(tracks);
        var channels = EnumSet.noneOf(Channel.class);
        for (var track : tracks) {
            if (!channels.add(track.channel()))
                throw new IllegalArgumentException("Duplicate camera channel: " + track.channel());
            if (track.keyframes().getLast().time() > durationSeconds)
                throw new IllegalArgumentException("Keyframe exceeds clip duration");
        }
    }

    public enum Channel {
        X, Y, Z, YAW, PITCH, ROLL, ZOOM;

        public static final Codec<Channel> CODEC = Codec.STRING.comapFlatMap(name -> {
            try { return DataResult.success(valueOf(name.toUpperCase(Locale.ROOT))); }
            catch (IllegalArgumentException failure) { return DataResult.error(() -> "Unknown camera channel: " + name); }
        }, channel -> channel.name().toLowerCase(Locale.ROOT));
    }

    /** Easing belongs to the outgoing segment, matching model animation clips. */
    public record Keyframe(float time, double value, Easing easing) {
        public Keyframe {
            if (!Float.isFinite(time) || time < 0 || !Double.isFinite(value))
                throw new IllegalArgumentException("Keyframe time/value must be finite; time must be non-negative");
            easing = easing == null ? Easing.linear() : easing;
        }

        public static final Codec<Keyframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.validate(value -> Float.isFinite(value) && value >= 0
                        ? DataResult.success(value) : DataResult.error(() -> "Invalid keyframe time"))
                        .fieldOf("time").forGetter(Keyframe::time),
                Codec.DOUBLE.validate(value -> Double.isFinite(value)
                        ? DataResult.success(value) : DataResult.error(() -> "Invalid keyframe value"))
                        .fieldOf("value").forGetter(Keyframe::value),
                Easing.CODEC.optionalFieldOf("easing", Easing.linear()).forGetter(Keyframe::easing)
        ).apply(instance, Keyframe::new));
    }

    public record Track(Channel channel, List<Keyframe> keyframes) {
        public Track {
            Objects.requireNonNull(channel, "channel");
            keyframes = keyframes.stream().sorted(Comparator.comparingDouble(Keyframe::time)).toList();
            if (keyframes.isEmpty()) throw new IllegalArgumentException("Camera track needs keyframes");
            float previous = -1;
            for (var keyframe : keyframes) {
                if (keyframe.time() == previous) throw new IllegalArgumentException("Duplicate keyframe time");
                if (channel == Channel.ZOOM && keyframe.value() <= 0)
                    throw new IllegalArgumentException("Zoom keyframes must be positive");
                if ((channel == Channel.YAW || channel == Channel.PITCH || channel == Channel.ROLL)
                        && !Float.isFinite((float) keyframe.value()))
                    throw new IllegalArgumentException("Camera angles must fit a finite float");
                previous = keyframe.time();
            }
        }

        /** Hold endpoint values; binary search the segment and apply its easing. */
        public double sample(float time) {
            if (!Float.isFinite(time)) throw new IllegalArgumentException("Sample time must be finite");
            if (time <= keyframes.getFirst().time()) return keyframes.getFirst().value();
            if (time >= keyframes.getLast().time()) return keyframes.getLast().value();
            int low = 0, high = keyframes.size() - 1;
            while (high - low > 1) {
                int middle = (low + high) >>> 1;
                if (keyframes.get(middle).time() <= time) low = middle;
                else high = middle;
            }
            var from = keyframes.get(low);
            var to = keyframes.get(high);
            float progress = (time - from.time()) / (to.time() - from.time());
            double value = Math.fma(to.value() - from.value(), from.easing().apply(progress), from.value());
            // Back/elastic easing can overshoot past zero; optical zoom must remain positive.
            return channel == Channel.ZOOM ? Math.max(1e-6, value) : value;
        }

        private record Data(Channel channel, List<Keyframe> keyframes) {}
        public static final Codec<Track> CODEC = RecordCodecBuilder.<Data>create(instance -> instance.group(
                Channel.CODEC.fieldOf("channel").forGetter(Data::channel),
                Keyframe.CODEC.listOf().fieldOf("keyframes").forGetter(Data::keyframes)
        ).apply(instance, Data::new)).comapFlatMap(data -> {
            try { return DataResult.success(new Track(data.channel(), data.keyframes())); }
            catch (IllegalArgumentException failure) { return DataResult.error(failure::getMessage); }
        }, track -> new Data(track.channel(), track.keyframes()));
    }

    /** Missing channels retain the provider's values. Zoom is relative to its FOV, never accumulated per frame. */
    public WorldCameraView sample(WorldCameraView base, float time) {
        Objects.requireNonNull(base, "base");
        return CameraClipSampler.INSTANCE.sample(this, time).apply(base);
    }

    /** Select a named camera track from the native model animation clip. */
    public static CameraAnimationClip from(AnimationClip clip, String camera) {
        for (var track : clip.cameras())
            if (track.camera().equals(camera)) return new CameraAnimationClip(clip.id(), clip.durationSeconds(), track.tracks());
        throw new IllegalArgumentException("No camera track '" + camera + "' in clip " + clip.id());
    }

    private record Data(Id id, float duration, List<Track> tracks) {}
    public static final Codec<CameraAnimationClip> CODEC = RecordCodecBuilder.<Data>create(instance -> instance.group(
            Id.CODEC.fieldOf("id").forGetter(Data::id),
            Codec.FLOAT.fieldOf("duration_seconds").forGetter(Data::duration),
            Track.CODEC.listOf().optionalFieldOf("tracks", List.of()).forGetter(Data::tracks)
    ).apply(instance, Data::new)).comapFlatMap(data -> {
        try { return DataResult.success(new CameraAnimationClip(data.id(), data.duration(), data.tracks())); }
        catch (IllegalArgumentException failure) { return DataResult.error(failure::getMessage); }
    }, clip -> new Data(clip.id(), clip.durationSeconds(), clip.tracks()));

    public static Builder builder(Id id, float durationSeconds) { return new Builder(id, durationSeconds); }

    public static final class Builder {
        private final Id id;
        private final float duration;
        private final EnumMap<Channel, List<Keyframe>> channels = new EnumMap<>(Channel.class);

        private Builder(Id id, float duration) { this.id = id; this.duration = duration; }

        public Builder keyframe(Channel channel, float time, double value, Easing easing) {
            channels.computeIfAbsent(Objects.requireNonNull(channel), ignored -> new ArrayList<>())
                    .add(new Keyframe(time, value, easing));
            return this;
        }

        public Builder move(float time, double x, double y, double z, Easing easing) {
            keyframe(Channel.X, time, x, easing);
            keyframe(Channel.Y, time, y, easing);
            return keyframe(Channel.Z, time, z, easing);
        }

        public Builder rotate(float time, float yaw, float pitch, float roll, Easing easing) {
            keyframe(Channel.YAW, time, yaw, easing);
            keyframe(Channel.PITCH, time, pitch, easing);
            return keyframe(Channel.ROLL, time, roll, easing);
        }

        public Builder zoom(float time, double factor, Easing easing) {
            return keyframe(Channel.ZOOM, time, factor, easing);
        }

        public CameraAnimationClip build() {
            return new CameraAnimationClip(id, duration, channels.entrySet().stream()
                    .map(entry -> new Track(entry.getKey(), entry.getValue())).toList());
        }
    }
}
