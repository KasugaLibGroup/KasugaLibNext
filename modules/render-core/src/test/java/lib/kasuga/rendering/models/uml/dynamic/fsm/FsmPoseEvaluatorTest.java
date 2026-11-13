package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.dynamic.animation.output.PoseOutput;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControllerBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlEvaluationRequest;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ControlFrame;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.Continuity;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.LogicalStepPolicy;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ManagedAnimationScope;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.OutputState;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RuntimeController;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvance;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.ScopeAdvanceCause;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetBinding;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublicationFactory;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FsmPoseEvaluatorTest {

    private static final float DT = 1f / 20f;

    @Test
    void scopeAdvancesTheMachineOnceAndPublishesItsResultPose() {
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("loco", layer -> {
                    State<Object> idle = layer.state("idle").morph("m", 0f);
                    State<Object> active = layer.state("active").morph("m", 1f);
                    layer.initial(idle);
                    layer.transition("activate", idle, active)
                            .when(context -> true)
                            .crossFade(DT);
                })
                .build();
        FsmPoseEvaluator evaluator = new FsmPoseEvaluator(machine);
        RecordingPoseTarget target =
                new RecordingPoseTarget(AnimationTargetRef.modelPose());
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        UUID.randomUUID(), LogicalStepPolicy.fixed(DT, 5))
                .controller(ControllerBinding.create(evaluator.controller()))
                .target(new TargetBinding<>(
                        target,
                        evaluator.targetEvaluator(),
                        new TargetPublicationFactory<>()))
                .build();

        scope.advance(new ScopeAdvance(DT, ScopeAdvanceCause.GAME_TICK));

        assertEquals(1, machine.tickCount());
        assertEquals(1, target.publications.size());
        OutputState.Active<PoseOutput> active =
                (OutputState.Active<PoseOutput>) target.publications.getFirst().outputState();
        assertEquals(1f, active.currentOutput().pose().morphs().get("m").value(), 1e-6f);
    }

    @Test
    void failedLaterControllerRollsBackTheEvaluatorPoseAndMachine() {
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("loco", layer -> {
                    State<Object> idle = layer.state("idle").morph("m", 0f);
                    State<Object> active = layer.state("active").morph("m", 1f);
                    layer.initial(idle);
                    layer.transition("activate", idle, active).when(context -> true);
                })
                .build();
        FsmPoseEvaluator evaluator = new FsmPoseEvaluator(machine);
        RuntimeController<Void> failing = new RuntimeController<>() {
            @Override
            public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                if (request.timeStep().cause() == ScopeAdvanceCause.GAME_TICK) {
                    throw new IllegalStateException("later controller failed");
                }
                return new ControlFrame<>(null, List.of(), List.of());
            }
        };
        RecordingPoseTarget target =
                new RecordingPoseTarget(AnimationTargetRef.modelPose());
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        UUID.randomUUID(), LogicalStepPolicy.fixed(DT, 5))
                .controller(ControllerBinding.create(evaluator.controller()))
                .controller(ControllerBinding.create(failing))
                .target(new TargetBinding<>(
                        target,
                        evaluator.targetEvaluator(),
                        new TargetPublicationFactory<>()))
                .build();

        var aborted = scope.advance(new ScopeAdvance(DT, ScopeAdvanceCause.GAME_TICK));
        assertTrue(aborted.aborted());
        assertEquals("later controller failed", aborted.failure().getMessage());
        assertEquals(0, machine.tickCount());
        assertTrue(evaluator.currentOutput().pose().isEmpty());

        scope.pause();
        OutputState.Active<PoseOutput> published =
                (OutputState.Active<PoseOutput>) target.publications.getLast().outputState();
        assertTrue(published.currentOutput().pose().isEmpty(),
                "a later pause must not publish the failed evaluator sidecar");
    }

    @Test
    void crossingALoopBoundarySnapsInsteadOfInterpolatingEndIntoTickStart() {
        AnimationSampler<Object> sampler = new AnimationSampler<>() {
            @Override
            public float duration(Object data) {
                return 1f;
            }

            @Override
            public Pose sample(Object data, float time) {
                return Pose.bone(
                        "loop",
                        new Transform().translate(time * 10f, 0f, 0f));
            }
        };
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("loco", layer -> {
                    State<Object> spin = layer.state("spin").clip(sampler, new Object(), true);
                    layer.initial(spin);
                })
                .build();
        FsmPoseEvaluator evaluator = new FsmPoseEvaluator(machine);
        RecordingPoseTarget target =
                new RecordingPoseTarget(AnimationTargetRef.modelPose());
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        UUID.randomUUID(), LogicalStepPolicy.fixed(DT, 5))
                .controller(ControllerBinding.create(evaluator.controller()))
                .target(new TargetBinding<>(
                        target,
                        evaluator.targetEvaluator(),
                        new TargetPublicationFactory<>()))
                .build();

        for (int tick = 0; tick < 20; tick++) {
            scope.advance(new ScopeAdvance(DT, ScopeAdvanceCause.GAME_TICK));
        }

        OutputState.Active<PoseOutput> seam =
                (OutputState.Active<PoseOutput>) target.publications.getLast().outputState();
        assertEquals(Continuity.SNAP, seam.continuity());
        assertEquals(0f, seam.currentOutput().pose().bones().get("loop")
                .transform().getPosition().x, 1e-5f);
    }

    @Test
    void formulaTracksUseTheLogicalTickVariableSnapshot() {
        AnimationClip clip = new AnimationClip(
                Id.fromNamespaceAndPath("kasuga_lib", "logical_formula"),
                1f,
                List.of(), List.of(), List.of(),
                List.of(),
                List.of(),
                List.of(AnimationClip.FormulaTransformTrack.bone(
                        "formula",
                        new AnimationClip.FormulaTransform(
                                new AnimationClip.FormulaVector("query.angle", "", ""),
                                AnimationClip.FormulaVector.EMPTY,
                                AnimationClip.FormulaVector.EMPTY
                        )
                )));
        StateMachine<Object> machine = StateMachine.<Object>builder(new Object())
                .layer("loco", layer -> {
                    State<Object> state = layer.state("state")
                            .clip(ClipSampler.INSTANCE, clip, true);
                    layer.initial(state);
                })
                .build();
        AtomicInteger providerCalls = new AtomicInteger();
        FsmPoseEvaluator evaluator = new FsmPoseEvaluator(machine, (ignored, delta, output) -> {
            providerCalls.incrementAndGet();
            output.put("angle", 45f);
        });
        RecordingPoseTarget target =
                new RecordingPoseTarget(AnimationTargetRef.modelPose());
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        UUID.randomUUID(), LogicalStepPolicy.fixed(DT, 5))
                .controller(ControllerBinding.create(evaluator.controller()))
                .target(new TargetBinding<>(
                        target,
                        evaluator.targetEvaluator(),
                        new TargetPublicationFactory<>()))
                .build();

        scope.advance(new ScopeAdvance(DT, ScopeAdvanceCause.GAME_TICK));

        assertEquals(1, providerCalls.get());
        OutputState.Active<PoseOutput> active =
                (OutputState.Active<PoseOutput>) target.publications.getLast().outputState();
        assertEquals(45f, active.currentOutput().pose().bones().get("formula")
                .transform().getPosition().x, 1e-5f);
    }

    private static final class RecordingPoseTarget implements AnimationTarget<PoseOutput> {
        private final AnimationTargetRef ref;
        private final List<TargetPublication<PoseOutput>> publications = new ArrayList<>();

        private RecordingPoseTarget(AnimationTargetRef ref) {
            this.ref = ref;
        }

        @Override
        public AnimationTargetRef ref() {
            return ref;
        }

        @Override
        public void publish(TargetPublication<PoseOutput> publication) {
            publications.add(publication);
        }

        @Override
        public void sample(
                TargetPublication<PoseOutput> publication,
                lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext context
        ) {
        }
    }
}
