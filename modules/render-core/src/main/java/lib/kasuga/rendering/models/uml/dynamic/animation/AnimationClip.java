package lib.kasuga.rendering.models.uml.dynamic.animation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.math.Easing;
import lib.kasuga.rendering.output.camera.CameraAnimationClip;
import org.jetbrains.annotations.Nullable;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Data-driven animation clip: keyframes, formulas, morphs, material frames and named camera tracks.
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
 *
 * <p>Formula variables are evaluated in declaration order before any formula transform. A formula
 * transform targets either an entire rigid model part or one bone inside a model instance. Both
 * target scopes share one expression namespace and one complete transform shape.
 */
public record AnimationClip(
        Id id,
        float durationSeconds,
        List<BoneTrack> bones,
        List<MorphTrack> morphs,
        List<FrameTrack> frames,
        List<CameraTrack> cameras,
        List<FormulaVariable> variables,
        List<FormulaTransformTrack> formulaTransforms
) {

    /** Creates a clip with no cameras, variables or formula transforms. */
    public AnimationClip(Id id, float durationSeconds, List<BoneTrack> bones, List<MorphTrack> morphs,
                         List<FrameTrack> frames) {
        this(id, durationSeconds, bones, morphs, frames, List.of(), List.of(), List.of());
    }

    /** Creates a clip without variables or formula transforms. */
    public AnimationClip(Id id, float durationSeconds, List<BoneTrack> bones, List<MorphTrack> morphs,
                         List<FrameTrack> frames, List<CameraTrack> cameras) {
        this(id, durationSeconds, bones, morphs, frames, cameras, List.of(), List.of());
    }

    public AnimationClip {
        cameras = List.copyOf(cameras);
        variables = List.copyOf(variables);
        formulaTransforms = List.copyOf(formulaTransforms);
        var names = new HashSet<String>();
        for (var camera : cameras) {
            if (!names.add(camera.camera())) throw new IllegalArgumentException("Duplicate camera target: " + camera.camera());
            new CameraAnimationClip(id, durationSeconds, camera.tracks());
        }
        names.clear();
        for (var variable : variables) {
            if (!names.add(variable.name())) {
                throw new IllegalArgumentException("Duplicate formula variable: " + variable.name());
            }
        }
        names.clear();
        for (var track : formulaTransforms) {
            if (!names.add(track.target().key())) {
                throw new IllegalArgumentException("Duplicate formula transform target: " + track.target());
            }
        }
    }

    /** Easing name {@code ↔} built-in instance; unknown names decode to {@link Easing#linear()}. */
    public static final Codec<Easing> EASING_CODEC = Easing.CODEC;

    private record Data(Id id, float durationSeconds, List<BoneTrack> bones, List<MorphTrack> morphs,
                        List<FrameTrack> frames, List<CameraTrack> cameras,
                        List<FormulaVariable> variables, List<FormulaTransformTrack> formulaTransforms) {}

    public static final Codec<AnimationClip> CODEC = RecordCodecBuilder.<Data>create(instance -> instance.group(
            Id.CODEC.fieldOf("id").forGetter(Data::id),
            Codec.FLOAT.optionalFieldOf("duration_seconds", 1f).forGetter(Data::durationSeconds),
            BoneTrack.CODEC.listOf().optionalFieldOf("bones", List.of()).forGetter(Data::bones),
            MorphTrack.CODEC.listOf().optionalFieldOf("morphs", List.of()).forGetter(Data::morphs),
            FrameTrack.CODEC.listOf().optionalFieldOf("frames", List.of()).forGetter(Data::frames),
            CameraTrack.CODEC.listOf().optionalFieldOf("cameras", List.of()).forGetter(Data::cameras),
            FormulaVariable.CODEC.listOf().optionalFieldOf("variables", List.of()).forGetter(Data::variables),
            FormulaTransformTrack.CODEC.listOf().optionalFieldOf("formula_transforms", List.of())
                    .forGetter(Data::formulaTransforms)
    ).apply(instance, Data::new)).comapFlatMap(data -> {
        try { return DataResult.success(new AnimationClip(data.id(), data.durationSeconds(), data.bones(),
                data.morphs(), data.frames(), data.cameras(), data.variables(),
                data.formulaTransforms())); }
        catch (IllegalArgumentException failure) { return DataResult.error(failure::getMessage); }
    }, clip -> new Data(clip.id(), clip.durationSeconds(), clip.bones(), clip.morphs(), clip.frames(),
            clip.cameras(), clip.variables(), clip.formulaTransforms()));

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

    /**
     * One reusable formula value evaluated before formula tracks.
     *
     * <p>Expressions may read {@code query.*} values and variables declared earlier in the list.
     * The evaluated value is assigned to {@code name} in the clip namespace.
     *
     * @param name       formula variable name, for example {@code qj.angle}
     * @param expression arithmetic expression evaluated for each logical step
     */
    public record FormulaVariable(String name, String expression) {

        /** Validates that both fields are present. */
        public FormulaVariable {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("Formula variable name must not be blank");
            }
            if (!name.matches(
                    "[a-z][a-z1-9]*(?:_[a-z1-9]+)*(?:\\.[a-z][a-z1-9]*(?:_[a-z1-9]+)*)*"
            )) {
                throw new IllegalArgumentException("Invalid formula variable name: " + name);
            }
            if (name.startsWith("query.")) {
                throw new IllegalArgumentException(
                        "Formula variable must not replace a query input: " + name
                );
            }
            if (expression == null || expression.isBlank()) {
                throw new IllegalArgumentException("Formula variable expression must not be blank: " + name);
            }
        }

        /** JSON codec for one formula variable. */
        public static final Codec<FormulaVariable> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.fieldOf("name").forGetter(FormulaVariable::name),
                Codec.STRING.fieldOf("expression").forGetter(FormulaVariable::expression)
        ).apply(instance, FormulaVariable::new));
    }

    /**
     * Three optional formula expressions for one transform vector.
     *
     * <p>A blank axis uses the identity value of its containing channel: zero for translation and
     * rotation, one for scale.
     */
    public record FormulaVector(String x, String y, String z) {

        /** An all-axis identity expression vector. */
        public static final FormulaVector EMPTY = new FormulaVector("", "", "");

        /** Normalizes missing fields to blank expressions. */
        public FormulaVector {
            x = x == null ? "" : x;
            y = y == null ? "" : y;
            z = z == null ? "" : z;
        }

        /** JSON codec for one optional three-axis formula vector. */
        public static final Codec<FormulaVector> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("x", "").forGetter(FormulaVector::x),
                Codec.STRING.optionalFieldOf("y", "").forGetter(FormulaVector::y),
                Codec.STRING.optionalFieldOf("z", "").forGetter(FormulaVector::z)
        ).apply(instance, FormulaVector::new));
    }

    /**
     * Formula transform target.
     *
     * <p>A target with only {@code part} drives the entire rigid model part. A target with
     * {@code bone} drives that bone inside the selected part instance. Omitting {@code part} while
     * setting {@code bone} addresses a bone in the clip's single model instance.
     *
     * @param part optional model part or instance ID
     * @param bone optional bone ID inside that instance
     */
    public record FormulaTarget(@Nullable String part, @Nullable String bone) {

        /** Normalizes blank fields and requires at least one target component. */
        public FormulaTarget {
            part = blankToNull(part);
            bone = blankToNull(bone);
            if (part == null && bone == null) {
                throw new IllegalArgumentException(
                        "Formula target must declare part, bone, or both"
                );
            }
        }

        /** Whether this target addresses an entire rigid part. */
        public boolean isPartRoot() {
            return part != null && bone == null;
        }

        /** Whether this target addresses a bone. */
        public boolean isBone() {
            return bone != null;
        }

        private String key() {
            return (part == null ? "" : part) + '\u0000' + (bone == null ? "" : bone);
        }

        /** JSON codec for a formula transform target. */
        public static final Codec<FormulaTarget> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.optionalFieldOf("part", "").forGetter(target ->
                        target.part() == null ? "" : target.part()),
                Codec.STRING.optionalFieldOf("bone", "").forGetter(target ->
                        target.bone() == null ? "" : target.bone())
        ).apply(instance, FormulaTarget::new));
    }

    /**
     * Complete formula transform.
     *
     * <p>Values are composed as translation, intrinsic Z-Y-X rotation in degrees, then scale.
     * Blank axes use identity values: zero for translation and rotation, one for scale.
     *
     * @param translate optional translation expressions
     * @param rotate    optional rotation expressions in degrees
     * @param scale     optional scale expressions
     */
    public record FormulaTransform(
            FormulaVector translate,
            FormulaVector rotate,
            FormulaVector scale
    ) {

        /** An identity formula transform. */
        public static final FormulaTransform EMPTY = new FormulaTransform(
                FormulaVector.EMPTY,
                FormulaVector.EMPTY,
                FormulaVector.EMPTY
        );

        /** Normalizes omitted transform vectors. */
        public FormulaTransform {
            translate = translate == null ? FormulaVector.EMPTY : translate;
            rotate = rotate == null ? FormulaVector.EMPTY : rotate;
            scale = scale == null ? FormulaVector.EMPTY : scale;
        }

        /** JSON codec for one complete formula transform. */
        public static final Codec<FormulaTransform> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                FormulaVector.CODEC.optionalFieldOf("translate", FormulaVector.EMPTY)
                        .forGetter(FormulaTransform::translate),
                FormulaVector.CODEC.optionalFieldOf("rotate", FormulaVector.EMPTY)
                        .forGetter(FormulaTransform::rotate),
                FormulaVector.CODEC.optionalFieldOf("scale", FormulaVector.EMPTY)
                        .forGetter(FormulaTransform::scale)
        ).apply(instance, FormulaTransform::new));
    }

    /**
     * One formula-driven transform for a rigid part or bone target.
     *
     * @param target    rigid part or bone target
     * @param transform complete local transform expressions
     */
    public record FormulaTransformTrack(FormulaTarget target, FormulaTransform transform) {

        /** Validates and normalizes one unified formula track. */
        public FormulaTransformTrack {
            target = Objects.requireNonNull(target, "target");
            transform = transform == null ? FormulaTransform.EMPTY : transform;
        }

        /** Creates a whole-part transform track. */
        public static FormulaTransformTrack part(String part, FormulaTransform transform) {
            return new FormulaTransformTrack(new FormulaTarget(part, null), transform);
        }

        /** Creates a bone transform track for a single model instance. */
        public static FormulaTransformTrack bone(String bone, FormulaTransform transform) {
            return new FormulaTransformTrack(new FormulaTarget(null, bone), transform);
        }

        /** Creates a bone transform track scoped to one model part instance. */
        public static FormulaTransformTrack bone(
                String part,
                String bone,
                FormulaTransform transform
        ) {
            return new FormulaTransformTrack(new FormulaTarget(part, bone), transform);
        }

        /** JSON codec for one unified formula transform track. */
        public static final Codec<FormulaTransformTrack> CODEC =
                RecordCodecBuilder.create(instance -> instance.group(
                        FormulaTarget.CODEC.fieldOf("target").forGetter(FormulaTransformTrack::target),
                        FormulaTransform.CODEC.optionalFieldOf("transform", FormulaTransform.EMPTY)
                                .forGetter(FormulaTransformTrack::transform)
                ).apply(instance, FormulaTransformTrack::new));
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
