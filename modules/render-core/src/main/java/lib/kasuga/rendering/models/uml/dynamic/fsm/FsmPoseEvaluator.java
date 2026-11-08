package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.animation.output.PoseOutput;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlEvaluationRequest;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.Continuity;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControllerCheckpoint;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetEvaluationContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetOutputEvaluator;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Advances one FSM inside a Scope step and exposes its committed pose result.
 *
 * <p>The controller portion owns no scope clock. It only consumes the committed step duration and
 * updates the machine. It samples clips and formula tracks once for that logical step. The target
 * evaluator portion returns an immutable pose result; rendering later interpolates that result in
 * {@code ModelPoseTarget} without reading the machine or animation source again.
 *
 * <p>A loop boundary cannot interpolate from the final sampled pose back into the first pose. The
 * result therefore carries a {@code SNAP} continuity hint for that step.
 */
public final class FsmPoseEvaluator {

    private final StateMachine<?> machine;
    private final VarProvider varProvider;
    private final Namespace namespace = new Namespace(Code.ROOT_NAMESPACE);
    private final Blender blender = new Blender();

    private PoseOutput output = PoseOutput.empty();
    private Map<String, Float> variables = Map.of();

    public FsmPoseEvaluator(StateMachine<?> machine) {
        this(machine, null);
    }

    public FsmPoseEvaluator(StateMachine<?> machine, @Nullable VarProvider varProvider) {
        this.machine = Objects.requireNonNull(machine, "machine");
        this.varProvider = varProvider != null ? varProvider : NoopVarProvider.INSTANCE;
        FsmPoseComposer.warmUp(machine, namespace);
    }

    public StateMachine<?> machine() {
        return machine;
    }

    /** Controller that advances this evaluator exactly once for each real logical step. */
    public RuntimeController<Void> controller() {
        return new Controller();
    }

    /** Evaluator that publishes the pose produced by the preceding controller call. */
    public TargetOutputEvaluator<PoseOutput> targetEvaluator() {
        return this::evaluateTarget;
    }

    public PoseOutput currentOutput() {
        return output;
    }

    public Map<String, Float> variables() {
        return variables;
    }

    private PoseOutput evaluateTarget(TargetEvaluationContext context) {
        return output;
    }

    private void advance(float deltaSeconds) {
        if (!Float.isFinite(deltaSeconds) || deltaSeconds < 0f) {
            throw new IllegalArgumentException("deltaSeconds must be finite and non-negative");
        }
        machine.tick(deltaSeconds);

        Map<String, Float> nextVariables = new HashMap<>();
        varProvider.provide(machine, deltaSeconds, nextVariables);
        variables = Map.copyOf(nextVariables);
        for (Map.Entry<String, Float> entry : variables.entrySet()) {
            namespace.assign("query." + entry.getKey(), entry.getValue());
        }

        PoseTarget snapshot = FsmPoseComposer.snapshot(machine);
        FsmPoseComposer.compose(blender, snapshot, 1f, namespace);
        output = new PoseOutput(
                resolve(blender),
                crossesClipLoopBoundary(snapshot) ? Continuity.SNAP : null);
    }

    private final class Controller implements RuntimeController<Void> {

        @Override
        public ControllerCheckpoint beginStep() {
            StateMachineCheckpoint machineCheckpoint = machine.checkpoint();
            PoseOutput previousOutput = output;
            Map<String, Float> previousVariables = variables;
            Map<String, Float> previousNamespace = namespaceValues();
            return new ControllerCheckpoint() {
                @Override
                public void rollback() {
                    machine.restore(machineCheckpoint);
                    output = previousOutput;
                    variables = previousVariables;
                    namespace.INSTANT_VARS.forEach((codec, ignored) -> {
                        if (codec.startsWith("query.")) {
                            namespace.assign(codec, 0f);
                        }
                    });
                    previousNamespace.forEach(namespace::assign);
                }
            };
        }

        @Override
        public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
            if (request.timeStep().cause() == ScopeAdvanceCause.GAME_TICK) {
                advance((float) request.timeStep().deltaSeconds());
            }
            return new ControlFrame<>(null, List.of(), List.of());
        }
    }

    private Map<String, Float> namespaceValues() {
        Map<String, Float> values = new HashMap<>();
        namespace.INSTANT_VARS.forEach((codec, assignable) -> {
            if (codec.startsWith("query.")) {
                values.put(codec, assignable.getValue(codec));
            }
        });
        return Map.copyOf(values);
    }

    private static Pose resolve(Blender blender) {
        Pose.Builder result = new Pose.Builder();

        blender.morphs().forEach((id, value) -> {
            if (value.unbounded) {
                result.morphWeight(id, value.value());
            } else {
                result.morph(id, value.value(), value.factor());
            }
        });

        blender.bones().forEach((name, value) -> {
            if (value.hasOverride) {
                result.bone(name, value.override, value.mode);
                return;
            }
            if (value.base != null) {
                lib.kasuga.rendering.models.uml.math.Transform transform = value.base.copy();
                if (value.additive != null) {
                    transform.mul(value.additive);
                }
                result.bone(name, transform, value.mode);
            } else if (value.additive != null) {
                result.bone(name, value.additive, ApplyMode.MULTIPLY);
            }
        });

        blender.frames().forEach((id, value) -> result.frame(id, value.frame()));
        blender.ikEnabled().forEach((name, value) -> result.ikEnabled(name, value.enabled()));
        return result.build();
    }

    private static boolean crossesClipLoopBoundary(PoseTarget snapshot) {
        for (PoseTarget.LayerTarget layer : snapshot.layers()) {
            PoseTarget.ClipTarget clip = layer.clip();
            if (clip == null || !clip.loop()) {
                continue;
            }
            float duration = clip.duration();
            if (Float.isFinite(duration) && duration > 0f
                    && clip.time() > clip.prevTime()
                    && Math.floor(clip.prevTime() / duration)
                    < Math.floor(clip.time() / duration)) {
                return true;
            }
        }
        return false;
    }

    private enum NoopVarProvider implements VarProvider {
        INSTANCE;

        @Override
        public void provide(
                StateMachine<?> machine,
                float deltaSeconds,
                Map<String, Float> output
        ) {
        }
    }
}
