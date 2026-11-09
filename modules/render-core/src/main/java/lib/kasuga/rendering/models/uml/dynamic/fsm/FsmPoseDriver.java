package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PoseDriver} backed by a {@link StateMachine}, driven at two cadences:
 *
 * <ul>
 *   <li><b>{@link #tick(float)} — main thread, game tick.</b> Advances the machine (a client {@code puppet} is
 *       built with a {@code null} sink so this advances cross-fade timers without flushing) and publishes a fresh
 *       {@link PoseTarget} snapshot (a {@code volatile} immutable record). The machine's mutable state stays
 *       main-thread-owned.</li>
 *   <li><b>{@link #sample(float)} — render thread, per frame.</b> Reads the latest {@link PoseTarget} and composes
 *       the pose at frame rate: cross-fades interpolate by {@code partialTick} (the fraction between game ticks),
 *       so animation is smooth at render rate instead of stepping at 20&nbsp;Hz. The pose is flushed through the
 *       driver's own {@link ModelInstancePoseSink}; the backend uploads to the GPU afterwards.</li>
 * </ul>
 *
 * <p>The driver owns the {@link ModelInstancePoseSink} (the machine's sink stays {@code null}); on resource-reload
 * rebind, {@link #rebind(ModelInstance)} swaps only the sink target — the machine and in-flight state survive.
 *
 * @deprecated New hosts use {@link FsmPoseEvaluator} with a private animation Scope. This class
 * remains as the compatibility path for callers that explicitly rely on its render-thread formula
 * interpolation.
 */
@Deprecated
public final class FsmPoseDriver implements lib.kasuga.rendering.models.uml.dynamic.RebindablePoseDriver {

    private final StateMachine<?> machine;
    private ModelInstance model;
    private volatile ModelInstancePoseSink sink;
    private final Blender blender = new Blender();
    private volatile PoseTarget target;

    private final VarProvider varProvider;
    private volatile Map<String, Float> varSnapshot = Map.of();

    /**
     * The render projection published by the PREVIOUS tick, kept for {@link #sample(float)}'s partialTick
     * interpolation. Without it, the formula namespace would receive the raw per-tick values — the fan would
     * step at the 20 Hz game-tick rate regardless of display refresh (visibly choppy animation).
     */
    private volatile Map<String, Float> prevVarSnapshot = Map.of();
    private final Namespace ns = new Namespace(Code.ROOT_NAMESPACE);

    public FsmPoseDriver(StateMachine<?> machine, ModelInstance model) {
        this(machine, model, null);
    }

    public FsmPoseDriver(StateMachine<?> machine, ModelInstance model, @Nullable VarProvider varProvider) {
        this.machine = machine;
        this.model = model;
        this.sink = new ModelInstancePoseSink(model);
        this.varProvider = varProvider != null ? varProvider : NoopVarProvider.INSTANCE;
        warmUpNamespace();
    }

    /**
     * Pre-decode every state's clip formulas into the driver's {@link Namespace}, registering their
     * {@code query.*} variables as INSTANT_VARS. {@code Namespace.assign} is a silent no-op for
     * unregistered codecs, so without this warm-up the render thread's per-frame assigns would be lost
     * and every formula track would evaluate to its fallback. No-op for the default (noop) provider —
     * no variables are produced anyway.
     */
    private void warmUpNamespace() {
        FsmPoseComposer.warmUp(machine, ns);
    }

    private static final class NoopVarProvider implements VarProvider {
        static final NoopVarProvider INSTANCE = new NoopVarProvider();

        @Override
        public void provide(StateMachine<?> machine, float dt, Map<String, Float> out) {
            // empty — without a provider, formula tracks fall back to their identity values
        }
    }

    public StateMachine<?> machine() {
        return machine;
    }

    public ModelInstance model() {
        return model;
    }

    /** The latest published {@link PoseTarget} (volatile read; {@code null} until the first {@link #tick}). */
    PoseTarget currentTarget() {
        return target;
    }

    /**
     * Main-thread game-tick advance: advance the machine (puppet {@code sink=null} → timers advance, no flush),
     * then let the {@link VarProvider} derive parameters on the machine (single source of truth) and project the
     * render values into a fresh {@code volatile} snapshot, then publish a {@link PoseTarget} snapshot copied
     * from {@link StateMachine#layers()}.
     */
    @Override
    public void tick(float dt) {
        machine.tick(dt);
        Map<String, Float> next = new HashMap<>();
        varProvider.provide(machine, dt, next);
        prevVarSnapshot = varSnapshot;
        varSnapshot = Map.copyOf(next);
        publishTarget();
    }

    private void publishTarget() {
        target = FsmPoseComposer.snapshot(machine);
    }

    /**
     * Render-thread per-frame sample: compose the pose from the latest {@link PoseTarget} — cross-fades blend at
     * {@code alpha = (elapsed + partialTick·TICK_SECONDS) / crossFadeSeconds} — and flush via the sink. No-op until
     * the first {@link #tick(float)} publishes a target.
     *
     * <p>The formula variables injected into the namespace are {@code lerp(prevTick, currentTick, partialTick)}:
     * the provider integrates on the main thread at 20 Hz (authoritative), but the render thread re-samples the
     * pose every frame, so per-tick discontinuities (e.g. {@code angle += speed·dt}) must be interpolated or the
     * animation visibly steps at the game-tick rate. For a provider whose output grows linearly within a tick
     * (constant speed), the lerp is exact; for the fan's ease curve it is a first-order approximation.
     */
    @Override
    public void sample(float partialTick) {
        PoseTarget snapshot = target;
        if (snapshot == null) {
            return;
        }
        Map<String, Float> previous = prevVarSnapshot;
        Map<String, Float> current = varSnapshot;
        for (Map.Entry<String, Float> entry : current.entrySet()) {
            float prev = previous.getOrDefault(entry.getKey(), entry.getValue());
            float value = prev + (entry.getValue() - prev) * partialTick;
            ns.assign("query." + entry.getKey(), value);
        }
        compose(blender, snapshot, partialTick, ns);
        sink.apply(blender);
    }

    /**
     * Compose the {@link PoseTarget} into {@code blender} at frame rate — cross-fades blend by {@code partialTick},
     * and clip states interpolate their clip clock by {@code partialTick} before sampling. Package-private so the
     * same path {@link #sample} uses is unit-testable without a real {@link ModelInstance} sink (assert on the
     * blender's accumulators). Resets the blender first. Formula tracks are skipped (no namespace).
     */
    void compose(Blender blender, PoseTarget snapshot, float partialTick) {
        compose(blender, snapshot, partialTick, null);
    }

    /**
     * Compose the {@link PoseTarget} into {@code blender} at frame rate, injecting a formula {@link Namespace}
     * so clip formula tracks read the per-entity {@code query.*} variables. Package-private so the same path
     * {@link #sample} uses is unit-testable without a real {@link ModelInstance} sink (assert on the blender's
     * accumulators). Resets the blender first.
     */
    void compose(Blender blender, PoseTarget snapshot, float partialTick, @Nullable Namespace ns) {
        FsmPoseComposer.compose(blender, snapshot, partialTick, ns);
    }

    /**
     * Re-target a fresh {@link ModelInstance} (resource reload / model rebind): install a new
     * {@link ModelInstancePoseSink} for {@code fresh}. The machine, its layers, in-flight cross-fades and the
     * published {@link PoseTarget} are unchanged — only the destination of the pose flush changes.
     */
    public void rebind(ModelInstance fresh) {
        this.model = fresh;
        this.sink = new ModelInstancePoseSink(fresh);
    }
}
