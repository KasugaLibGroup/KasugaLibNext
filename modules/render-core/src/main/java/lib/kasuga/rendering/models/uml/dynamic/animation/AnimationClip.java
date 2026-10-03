package lib.kasuga.rendering.models.uml.dynamic.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;

import java.util.List;

/**
 * Data-driven keyframe animation clip: per-bone / morph / material-frame tracks over time.
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
        List<FunctionTrack> functions
) {

    /** Easing name {@code ↔} built-in instance; unknown names decode to {@link Easing#linear()}. */
    public static final Codec<Easing> EASING_CODEC = Codec.STRING.xmap(
            name -> {
                Easing easing = Easing.byName(name);
                return easing != null ? easing : Easing.linear();
            },
            Easing::nameOf
    );

    public static final Codec<AnimationClip> CODEC = RecordCodecBuilder.create(instance -> instance.group(
            Id.CODEC.fieldOf("id").forGetter(AnimationClip::id),
            Codec.FLOAT.optionalFieldOf("duration_seconds", 1f).forGetter(AnimationClip::durationSeconds),
            BoneTrack.CODEC.listOf().optionalFieldOf("bones", List.of()).forGetter(AnimationClip::bones),
            MorphTrack.CODEC.listOf().optionalFieldOf("morphs", List.of()).forGetter(AnimationClip::morphs),
            FrameTrack.CODEC.listOf().optionalFieldOf("frames", List.of()).forGetter(AnimationClip::frames),
            FunctionTrack.CODEC.listOf().optionalFieldOf("functions", List.of()).forGetter(AnimationClip::functions)
    ).apply(instance, AnimationClip::new));

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