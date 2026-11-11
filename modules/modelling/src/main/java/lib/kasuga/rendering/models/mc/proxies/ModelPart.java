package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.animation.FormulaTransformSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * One independently placed (and optionally animated) rigid model under a single {@link ProxyInstance}.
 *
 * <p>Content that needs several geometries for one element — a bogey frame plus its wheelsets, a
 * machine's body plus its rotating parts — lists them here instead of baking them into one model.
 * Each part gets its own {@code ModelInstance} in the pipeline, so parts can be transformed and
 * released independently; the proxy root stays the single authority for where the whole model group
 * sits.
 *
 * <h2>Placement</h2>
 * <p>{@link #local()} is the part's fixed pose <b>relative to the proxy root</b>, composed as
 * {@code root × local}. Both transforms are in the pipeline's root space (for block hosts that is
 * the block centre, see {@code BogeyRoots}); local values are usually copied from the reference
 * renderer of the same model. The part keeps its own snapshot, and {@link #local()} returns another
 * snapshot so content cannot mutate an assembled model's layout through the same Transform.
 *
 * <h2>Motion</h2>
 * <p>A part with a {@link Motion} is composed as {@code root × local × motion}. Tick motion is
 * published once per client tick and interpolated at render time; frame motion is captured once per
 * host frame and reused by render samples. {@link Spin} is retained as the common single-axis
 * motion; a complete local {@link Transform} can be supplied for translation, three-axis rotation,
 * and scale. Parts without motion are still driven at frame rate so their base pose follows a
 * moving host.
 * A spin angle comes either from the proxy's state machine ({@link Spin.Param}), a tick-native host
 * function ({@link Spin.TickSource}), or a frame-native host function ({@link Spin.FrameSource}).
 * The sampler performs interpolation only for tick-domain motion.
 *
 * @param model     the model resource; routed by file extension like any other model
 * @param modelName the model name inside the file (MMD {@code .pmx} only), otherwise {@code null}
 * @param local     fixed pose relative to the proxy root; an identity transform centres the part on
 *                  the root
 * @param motion    optional local motion, or {@code null} for a part that only follows its host
 */
public record ModelPart(
        ResourceLocation model,
        @Nullable String modelName,
        Transform local,
        @Nullable Motion motion
) {

    public ModelPart {
        Objects.requireNonNull(model, "model");
        local = Objects.requireNonNull(local, "local").copy();
    }

    @Override
    public Transform local() {
        return local.copy();
    }

    /** A static part centred on the proxy root. */
    public static ModelPart of(ResourceLocation model) {
        return new ModelPart(model, null, new Transform(), null);
    }

    /** A static part at {@code local} relative to the proxy root. */
    public static ModelPart at(ResourceLocation model, Transform local) {
        return new ModelPart(model, null, local, null);
    }

    /** The same part, now spinning; keeps model, name and local placement. */
    public ModelPart spinning(Spin spin) {
        return moving(Objects.requireNonNull(spin, "spin"));
    }

    /**
     * The same part with a complete local motion; keeps model and local placement.
     *
     * <p>The motion is applied after {@link #local()}, so its translation/rotation/scale uses the
     * part's own origin. This is the general path for mechanisms that need more than single-axis
     * rotation.
     */
    public ModelPart moving(Motion motion) {
        return new ModelPart(model, modelName, this.local, Objects.requireNonNull(motion, "motion"));
    }

    /** The part's current motion in its pre-generalized {@link Spin} form, if it is one. */
    public @Nullable Spin spin() {
        return motion instanceof Spin spin ? spin : null;
    }

    /** The clock domain in which one part motion is authored. */
    public enum TimeDomain {
        TICK,
        FRAME
    }

    /** A local motion whose explicit time domain determines when and how it is sampled. */
    public sealed interface Motion permits TransformMotion, Spin {

        /**
         * Time domain of this motion.
         */
        TimeDomain timeDomain();
    }

    /** A complete local Transform authored in either a game tick or a host frame domain. */
    public sealed interface TransformMotion extends Motion
            permits TransformMotion.Tick, TransformMotion.Frame {

        /** Produces one game tick's complete local motion. */
        @FunctionalInterface
        interface TickSource {
            @Nullable Transform atTick(@Nullable StateMachine<?> machine);
        }

        /** Reads the host's already frame-interpolated complete local motion. */
        @FunctionalInterface
        interface FrameSource {
            @Nullable Transform current();
        }

        /** Tick-domain complete motion, interpolated once by the sampler. */
        record Tick(TickSource source) implements TransformMotion {

            public Tick {
                Objects.requireNonNull(source, "source");
            }

            @Override
            public TimeDomain timeDomain() {
                return TimeDomain.TICK;
            }
        }

        /** Frame-domain complete motion, consumed without history or interpolation. */
        record Frame(FrameSource source) implements TransformMotion {

            public Frame {
                Objects.requireNonNull(source, "source");
            }

            @Override
            public TimeDomain timeDomain() {
                return TimeDomain.FRAME;
            }
        }
    }

    /** Defines complete motion sampled once per game tick. */
    public static TransformMotion tickTransform(TransformMotion.TickSource source) {
        return new TransformMotion.Tick(source);
    }

    /**
     * Creates a tick-domain motion from one part in a compiled formula animation clip.
     *
     * <p>The input callback assigns current {@code query.*} values before sampling. The sampler
     * caches unchanged inputs, so several parts may share one sampler and input source within the
     * same logical step.
     *
     * @param sampler formula part sampler shared by the model group
     * @param part    target part ID declared by the clip
     * @param input   assigns current formula inputs
     * @return complete tick-domain local motion
     */
    public static TransformMotion tickFormula(
            FormulaTransformSampler sampler,
            String part,
            Consumer<FormulaTransformSampler> input
    ) {
        Objects.requireNonNull(sampler, "sampler");
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException("Formula part ID must not be blank");
        }
        Objects.requireNonNull(input, "input");
        if (!sampler.parts().contains(part)) {
            throw new IllegalArgumentException("Unknown formula part: " + part);
        }
        return tickTransform(ignored -> {
            input.accept(sampler);
            return sampler.samplePart(part);
        });
    }

    /** Defines complete motion sampled from the host's current render frame. */
    public static TransformMotion frameTransform(TransformMotion.FrameSource source) {
        return new TransformMotion.Frame(source);
    }

    /**
     * Creates a frame-domain motion from one part in a compiled formula animation clip.
     *
     * <p>The input callback assigns current {@code query.*} values before sampling. This is
     * appropriate when the host already supplies a frame-interpolated source value.
     *
     * @param sampler formula part sampler shared by the model group
     * @param part    target part ID declared by the clip
     * @param input   assigns current formula inputs
     * @return complete frame-domain local motion
     */
    public static TransformMotion frameFormula(
            FormulaTransformSampler sampler,
            String part,
            Consumer<FormulaTransformSampler> input
    ) {
        Objects.requireNonNull(sampler, "sampler");
        if (part == null || part.isBlank()) {
            throw new IllegalArgumentException("Formula part ID must not be blank");
        }
        Objects.requireNonNull(input, "input");
        if (!sampler.parts().contains(part)) {
            throw new IllegalArgumentException("Unknown formula part: " + part);
        }
        return frameTransform(() -> {
            input.accept(sampler);
            return sampler.samplePart(part);
        });
    }

    /**
     * Local axial rotation. Kept as a motion subtype so existing content and docs continue to
     * express wheels naturally while the driver can accept complete transforms.
     */
    public sealed interface Spin extends Motion
            permits Spin.Param, Spin.TickSource, Spin.FrameSource {

        /** Multiplier applied to the source angle; {@code 1} means "same speed as the source". */
        float scale();

        /** A tick spin following a machine parameter 1:1. */
        static Param param(ParameterSpec<Float> angle) {
            return new Param(angle, 1f);
        }

        /** A tick spin following a host-provided angle function 1:1. */
        static TickSource tick(AngleSource angle) {
            return tick(angle, 1f);
        }

        /** A tick spin following a host-provided angle function. */
        static TickSource tick(AngleSource angle, float scale) {
            return new TickSource(angle, scale);
        }

        /** A frame spin following Create's already interpolated angle 1:1. */
        static FrameSource frame(AngleSource angle) {
            return frame(angle, 1f);
        }

        /** A frame spin following Create's already interpolated angle. */
        static FrameSource frame(AngleSource angle, float scale) {
            return new FrameSource(angle, scale);
        }

        /** A tick spin driven by a machine parameter. */
        record Param(ParameterSpec<Float> angle, float scale) implements Spin {

            public Param {
                Objects.requireNonNull(angle, "angle");
                requireFiniteScale(scale);
            }

            @Override
            public TimeDomain timeDomain() {
                return TimeDomain.TICK;
            }

            /** Current game-tick angle in degrees. */
            public float degrees(@Nullable StateMachine<?> machine) {
                if (machine == null) {
                    return 0f;
                }
                Float value = machine.get(angle);
                return value == null ? 0f : value;
            }
        }

        /** A spin driven by a host-provided game-tick angle function. */
        record TickSource(AngleSource angle, float scale) implements Spin {

            public TickSource {
                Objects.requireNonNull(angle, "angle");
                requireFiniteScale(scale);
            }

            @Override
            public TimeDomain timeDomain() {
                return TimeDomain.TICK;
            }
        }

        /** A spin driven by Create's already interpolated render-frame angle. */
        record FrameSource(AngleSource angle, float scale) implements Spin {

            public FrameSource {
                Objects.requireNonNull(angle, "angle");
                requireFiniteScale(scale);
            }

            @Override
            public TimeDomain timeDomain() {
                return TimeDomain.FRAME;
            }
        }

        /**
         * Angle in degrees, interpreted in the explicit tick or frame time domain of the enclosing
         * source record.
         */
        @FunctionalInterface
        interface AngleSource {
            float degrees();
        }
    }

    private static void requireFiniteScale(float scale) {
        if (!Float.isFinite(scale)) {
            throw new IllegalArgumentException("spin scale must be finite, got " + scale);
        }
    }
}
