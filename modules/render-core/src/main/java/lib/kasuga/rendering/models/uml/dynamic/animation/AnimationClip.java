package lib.kasuga.rendering.models.uml.dynamic.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.output.camera.CameraAnimationClip;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Data-driven keyframe animation clip: bone, morph, material and named camera tracks on one timeline.
 *
 * <p>JSON shape mirrors the FSM's {@code state_machines/*.json} conventions ({@link TransformDefinition}
 * for transforms, angles in degrees, easing referenced by canonical name — see {@link Easing#byName}):
 * <pre>{@code
 * {
 *   "id": "kasuga_lib:wheel_spin",
 *   "duration_seconds": 1.0,
 *   "bones": [
 *     { "bone": "wheel_r", "keyframes": [
 *       { "time": 0.0, "transform": { "rotate": [0, 0, 0] }, "easing": "linear" },
 *       { "time": 1.0, "transform": { "rotate": [0, 360, 0] }, "easing": "ease_in_out_cubic" }
 *     ]}
 *   ]
 * }
 * }</pre>
 */
public record AnimationClip(
        Id id,
        float durationSeconds,
        List<BoneTrack> bones,
        List<MorphTrack> morphs,
        List<FrameTrack> frames,
        List<FunctionTrack> functions,
        List<CameraTrack> cameras
) {

    /** Existing model-only constructor remains source-compatible. */
    public AnimationClip(Id id, float durationSeconds, List<BoneTrack> bones, List<MorphTrack> morphs,
                         List<FrameTrack> frames, List<FunctionTrack> functions) {
        this(id, durationSeconds, bones, morphs, frames, functions, List.of());
    }

    public AnimationClip {
        cameras = List.copyOf(cameras);
        var names = new HashSet<String>();
        for (var camera : cameras) {
            if (!names.add(camera.camera())) throw new IllegalArgumentException("Duplicate camera target: " + camera.camera());
            new CameraAnimationClip(id, durationSeconds, camera.tracks());
        }
    }

    /** Easing name {@code ↔} built-in instance; unknown names decode to {@link Easing#linear()}. */
    public static final Codec<Easing> EASING_CODEC = Easing.CODEC;

    private record Data(Id id, float durationSeconds, List<BoneTrack> bones, List<MorphTrack> morphs,
                        List<FrameTrack> frames, List<FunctionTrack> functions, List<CameraTrack> cameras) {}

    public static final Codec<AnimationClip> CODEC = RecordCodecBuilder.<Data>create(instance -> instance.group(
            Id.CODEC.fieldOf("id").forGetter(Data::id),
            Codec.FLOAT.optionalFieldOf("duration_seconds", 1f).forGetter(Data::durationSeconds),
            BoneTrack.CODEC.listOf().optionalFieldOf("bones", List.of()).forGetter(Data::bones),
            MorphTrack.CODEC.listOf().optionalFieldOf("morphs", List.of()).forGetter(Data::morphs),
            FrameTrack.CODEC.listOf().optionalFieldOf("frames", List.of()).forGetter(Data::frames),
            FunctionTrack.CODEC.listOf().optionalFieldOf("functions", List.of()).forGetter(Data::functions),
            CameraTrack.CODEC.listOf().optionalFieldOf("cameras", List.of()).forGetter(Data::cameras)
    ).apply(instance, Data::new)).comapFlatMap(data -> {
        try { return DataResult.success(new AnimationClip(data.id(), data.durationSeconds(), data.bones(),
                data.morphs(), data.frames(), data.functions(), data.cameras())); }
        catch (IllegalArgumentException failure) { return DataResult.error(failure::getMessage); }
    }, clip -> new Data(clip.id(), clip.durationSeconds(), clip.bones(), clip.morphs(), clip.frames(), clip.functions(), clip.cameras()));

    /** Camera names are logical binding targets; they need not equal the output view ID. */
    public record CameraTrack(String camera, List<CameraAnimationClip.Track> tracks) {
        public CameraTrack {
            Objects.requireNonNull(camera, "camera");
            if (camera.isBlank()) throw new IllegalArgumentException("Camera target must not be blank");
            tracks = List.copyOf(tracks);
        }

        public static final Codec<CameraTrack> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.validate(name -> name.isBlank() ? DataResult.error(() -> "Camera target must not be blank")
                        : DataResult.success(name)).fieldOf("camera").forGetter(CameraTrack::camera),
                CameraAnimationClip.Track.CODEC.listOf().fieldOf("tracks").forGetter(CameraTrack::tracks)
        ).apply(instance, CameraTrack::new));
    }

    /** One bone's keyframe track: {@code time → transform}, interpolated with the segment's easing. */
    public record BoneTrack(String bone, List<Keyframe> keyframes) {

        public static final Codec<BoneTrack> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("bone").forGetter(BoneTrack::bone),
                Keyframe.CODEC.listOf().fieldOf("keyframes").forGetter(BoneTrack::keyframes)
        ).apply(instance, BoneTrack::new));
    }

    public record Keyframe(float time, TransformDefinition transform, Easing easing) {

        public Keyframe {
            easing = easing != null ? easing : Easing.linear();
        }

        public static final Codec<Keyframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("time").forGetter(Keyframe::time),
                TransformDefinition.CODEC.fieldOf("transform").forGetter(Keyframe::transform),
                EASING_CODEC.optionalFieldOf("easing", Easing.linear()).forGetter(Keyframe::easing)
        ).apply(instance, Keyframe::new));
    }

    /** One morph's value track; interpolated linearly (time axis shaped by easing). */
    public record MorphTrack(String morph, List<MorphKeyframe> keyframes) {

        public static final Codec<MorphTrack> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("morph").forGetter(MorphTrack::morph),
                MorphKeyframe.CODEC.listOf().fieldOf("keyframes").forGetter(MorphTrack::keyframes)
        ).apply(instance, MorphTrack::new));
    }

    public record MorphKeyframe(float time, float value, Easing easing) {

        public MorphKeyframe {
            easing = easing != null ? easing : Easing.linear();
        }

        public static final Codec<MorphKeyframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("time").forGetter(MorphKeyframe::time),
                Codec.FLOAT.fieldOf("value").forGetter(MorphKeyframe::value),
                EASING_CODEC.optionalFieldOf("easing", Easing.linear()).forGetter(MorphKeyframe::easing)
        ).apply(instance, MorphKeyframe::new));
    }

    /** One material's sprite-frame track; snaps to the nearer keyframe (frame indices have no meaning to lerp). */
    public record FrameTrack(String material, List<FrameKeyframe> keyframes) {

        public static final Codec<FrameTrack> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("material").forGetter(FrameTrack::material),
                FrameKeyframe.CODEC.listOf().fieldOf("keyframes").forGetter(FrameTrack::keyframes)
        ).apply(instance, FrameTrack::new));
    }

    public record FrameKeyframe(float time, int frame) {

        public static final Codec<FrameKeyframe> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.FLOAT.fieldOf("time").forGetter(FrameKeyframe::time),
                Codec.INT.fieldOf("frame").forGetter(FrameKeyframe::frame)
        ).apply(instance, FrameKeyframe::new));
    }

    /** Which {@link TransformDefinition} channel a function track drives. */
    public enum FunctionChannel { ROTATE, TRANSLATE, SCALE }

    /**
     * One bone's formula-driven track: each axis of the chosen channel is a formula
     * expression string evaluated per-frame against a {@code lib.kasuga.formula} namespace.
     * A blank axis string means "leave that component at its identity value"
     * (rotate 0 / translate 0 / scale 1).
     */
    public record FunctionTrack(
            String bone,
            FunctionChannel channel,
            String x,
            String y,
            String z
    ) {
        public static final Codec<FunctionChannel> CHANNEL_CODEC = Codec.STRING.xmap(
                name -> switch (name) {
                    case "rotate" -> FunctionChannel.ROTATE;
                    case "translate" -> FunctionChannel.TRANSLATE;
                    case "scale" -> FunctionChannel.SCALE;
                    default -> FunctionChannel.ROTATE;
                },
                channel -> switch (channel) {
                    case ROTATE -> "rotate";
                    case TRANSLATE -> "translate";
                    case SCALE -> "scale";
                }
        );

        public static final Codec<FunctionTrack> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("bone").forGetter(FunctionTrack::bone),
                CHANNEL_CODEC.fieldOf("channel").forGetter(FunctionTrack::channel),
                Codec.STRING.optionalFieldOf("x", "").forGetter(FunctionTrack::x),
                Codec.STRING.optionalFieldOf("y", "").forGetter(FunctionTrack::y),
                Codec.STRING.optionalFieldOf("z", "").forGetter(FunctionTrack::z)
        ).apply(instance, FunctionTrack::new));
    }
}
