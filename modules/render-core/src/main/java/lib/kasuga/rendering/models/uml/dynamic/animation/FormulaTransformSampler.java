package lib.kasuga.rendering.models.uml.dynamic.animation;

import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.data.Variable;
import lib.kasuga.formula.compute.infrastructure.Assignable;
import lib.kasuga.formula.compute.infrastructure.Formula;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Compiles and evaluates formula variables and unified formula transform tracks from an
 * {@link AnimationClip}.
 *
 * <p>The clip clock does not affect these tracks. A host assigns current {@code query.*} values,
 * calls {@link #sample()}, and routes part-root transforms to rigid-part motion bindings and bone
 * transforms to skeletal pose targets. Repeated samples with unchanged inputs reuse the last
 * snapshot, so one sampler may serve several targets in one logical step.
 *
 * <p>This class is synchronized and intended to be owned by one host instance. Callers that pass a
 * namespace must not mutate it concurrently.
 */
public final class FormulaTransformSampler {

    private final Namespace namespace;
    private final List<CompiledVariable> variables;
    private final List<CompiledTrack> tracks;
    private final Set<String> parts;
    private final Set<FormulaTransformPose.BoneTarget> bones;
    private boolean dirty = true;
    @Nullable
    private FormulaTransformPose cached;

    /**
     * Creates a sampler with a fresh namespace inheriting all built-in formula functions.
     *
     * @param clip animation clip containing variables and formula transforms
     */
    public FormulaTransformSampler(AnimationClip clip) {
        this(clip, new Namespace(Code.ROOT_NAMESPACE));
    }

    /**
     * Creates a sampler using a caller-owned namespace.
     *
     * <p>The namespace may contain custom functions required by the clip. It should be dedicated
     * to this sampler because formula variables and query values are written into it.
     *
     * @param clip      animation clip containing variables and formula transforms
     * @param namespace formula namespace used to compile and evaluate expressions
     */
    public FormulaTransformSampler(AnimationClip clip, Namespace namespace) {
        Objects.requireNonNull(clip, "clip");
        this.namespace = Objects.requireNonNull(namespace, "namespace");

        this.variables = clip.variables().stream()
                .map(variable -> {
                    if (namespace.getStaticVar(variable.name()) != null
                            || namespace.getFunction(variable.name()) != null) {
                        throw new IllegalArgumentException(
                                "Formula variable conflicts with a registered symbol: "
                                        + variable.name()
                        );
                    }
                    if (namespace.getInstance(variable.name()) == null) {
                        namespace.registerInstance(
                                variable.name(),
                                new Variable(variable.name(), namespace)
                        );
                    }
                    return new CompiledVariable(
                            variable.name(),
                            compile(variable.expression(), namespace, "variable " + variable.name())
                    );
                })
                .toList();

        this.tracks = clip.formulaTransforms().stream()
                .map(track -> new CompiledTrack(
                        track.target(),
                        CompiledTransform.compile(track.transform(), namespace, track.target())
                ))
                .toList();

        Set<String> partTargets = new LinkedHashSet<>();
        Set<FormulaTransformPose.BoneTarget> boneTargets = new LinkedHashSet<>();
        for (CompiledTrack track : tracks) {
            AnimationClip.FormulaTarget target = track.target();
            if (target.isPartRoot()) {
                partTargets.add(target.part());
            } else {
                boneTargets.add(new FormulaTransformPose.BoneTarget(
                        target.part(),
                        target.bone()
                ));
            }
        }
        this.parts = Collections.unmodifiableSet(partTargets);
        this.bones = Collections.unmodifiableSet(boneTargets);
    }

    /**
     * Assigns a formula variable by its full codec.
     *
     * <p>Unknown codecs are ignored, matching {@link Namespace#assign(String, float)}. Non-finite
     * values are rejected because they cannot produce a valid transform.
     *
     * @param codec full formula variable name, for example {@code query.wheel_angle}
     * @param value finite value
     */
    public synchronized void assign(String codec, float value) {
        if (codec == null || codec.isBlank()) {
            throw new IllegalArgumentException("Formula variable codec must not be blank");
        }
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Formula value must be finite: " + codec + "=" + value);
        }
        Assignable variable = namespace.getInstance(codec);
        if (variable == null) {
            return;
        }
        if (Float.compare(variable.getValue(codec), value) != 0) {
            variable.assign(codec, value);
            dirty = true;
        }
    }

    /**
     * Assigns a {@code query.*} input without repeating the prefix.
     *
     * @param name  query name, for example {@code wheel_angle}
     * @param value finite input value
     */
    public synchronized void assignQuery(String name, float value) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Query name must not be blank");
        }
        assign("query." + name, value);
    }

    /**
     * Marks the cached snapshot stale after direct namespace mutation.
     */
    public synchronized void invalidate() {
        dirty = true;
    }

    /**
     * Evaluates the current variables and every formula transform.
     *
     * @return an immutable snapshot of part and bone transforms
     */
    public synchronized FormulaTransformPose sample() {
        if (!dirty && cached != null) {
            return cached;
        }
        for (CompiledVariable variable : variables) {
            float value = evaluate(variable.expression(), 0f);
            namespace.assign(variable.name(), value);
        }

        Map<String, Transform> partTransforms = new LinkedHashMap<>();
        Map<FormulaTransformPose.BoneTarget, Transform> boneTransforms = new LinkedHashMap<>();
        for (CompiledTrack track : tracks) {
            Transform transform = track.transform().evaluate(this);
            AnimationClip.FormulaTarget target = track.target();
            if (target.isPartRoot()) {
                partTransforms.put(target.part(), transform);
            } else {
                boneTransforms.put(
                        new FormulaTransformPose.BoneTarget(target.part(), target.bone()),
                        transform
                );
            }
        }
        cached = new FormulaTransformPose(partTransforms, boneTransforms);
        dirty = false;
        return cached;
    }

    /**
     * Evaluates and returns one rigid-part transform.
     *
     * @param part model part ID
     * @return a defensive transform copy, or {@code null}
     */
    public synchronized @Nullable Transform samplePart(String part) {
        return sample().part(part);
    }

    /**
     * Evaluates and returns one bone transform.
     *
     * @param part part ID, or {@code null} for a single model instance
     * @param bone bone ID
     * @return a defensive transform copy, or {@code null}
     */
    public synchronized @Nullable Transform sampleBone(@Nullable String part, String bone) {
        return sample().bone(part, bone);
    }

    /**
     * Evaluates and returns one bone transform in the clip's single model instance.
     *
     * @param bone bone ID
     * @return a defensive transform copy, or {@code null}
     */
    public synchronized @Nullable Transform sampleBone(String bone) {
        return sampleBone(null, bone);
    }

    /**
     * Returns whole-part target IDs.
     *
     * @return immutable part IDs in declaration order
     */
    public Set<String> parts() {
        return parts;
    }

    /**
     * Returns bone targets.
     *
     * @return immutable bone targets in declaration order
     */
    public Set<FormulaTransformPose.BoneTarget> bones() {
        return bones;
    }

    private static Formula compile(String expression, Namespace namespace, String context) {
        try {
            return Code.decodeFormula(expression, namespace);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid formula in " + context + ": " + expression, failure);
        }
    }

    private float evaluate(@Nullable Formula formula, float fallback) {
        if (formula == null) {
            return fallback;
        }
        try {
            float value = formula.getResult();
            return Float.isFinite(value) ? value : fallback;
        } catch (RuntimeException failure) {
            return fallback;
        }
    }

    private record CompiledVariable(String name, Formula expression) {
    }

    private record CompiledTrack(
            AnimationClip.FormulaTarget target,
            CompiledTransform transform
    ) {
    }

    private record CompiledTransform(
            CompiledVector translate,
            CompiledVector rotate,
            CompiledVector scale
    ) {
        private static CompiledTransform compile(
                AnimationClip.FormulaTransform transform,
                Namespace namespace,
                AnimationClip.FormulaTarget target
        ) {
            String context = target.toString();
            return new CompiledTransform(
                    CompiledVector.compile(transform.translate(), namespace, context + " translate"),
                    CompiledVector.compile(transform.rotate(), namespace, context + " rotate"),
                    CompiledVector.compile(transform.scale(), namespace, context + " scale")
            );
        }

        private Transform evaluate(FormulaTransformSampler sampler) {
            float tx = sampler.evaluate(translate.x(), 0f);
            float ty = sampler.evaluate(translate.y(), 0f);
            float tz = sampler.evaluate(translate.z(), 0f);
            float rx = sampler.evaluate(rotate.x(), 0f);
            float ry = sampler.evaluate(rotate.y(), 0f);
            float rz = sampler.evaluate(rotate.z(), 0f);
            float sx = sampler.evaluate(scale.x(), 1f);
            float sy = sampler.evaluate(scale.y(), 1f);
            float sz = sampler.evaluate(scale.z(), 1f);
            return FormulaTransforms.build(tx, ty, tz, rx, ry, rz, sx, sy, sz);
        }
    }

    private record CompiledVector(@Nullable Formula x, @Nullable Formula y, @Nullable Formula z) {
        private static CompiledVector compile(
                AnimationClip.FormulaVector vector,
                Namespace namespace,
                String context
        ) {
            return new CompiledVector(
                    compileAxis(vector.x(), namespace, context + " x"),
                    compileAxis(vector.y(), namespace, context + " y"),
                    compileAxis(vector.z(), namespace, context + " z")
            );
        }

        private static @Nullable Formula compileAxis(
                String expression,
                Namespace namespace,
                String context
        ) {
            return expression == null || expression.isBlank()
                    ? null
                    : FormulaTransformSampler.compile(expression, namespace, context);
        }
    }
}
