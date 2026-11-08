package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManagedAnimationScopeTest {

    private static final UUID SCOPE_ID =
            UUID.fromString("33c6b209-fab9-4ec3-9762-e21de0dc31eb");

    @Test
    void oneHostAdvancePublishesEveryFixedStepWithTheSameRevisionToEveryTarget() {
        RecordingTarget<Integer> numeric =
                new RecordingTarget<>(AnimationTargetRef.property("number"));
        RecordingTarget<String> text =
                new RecordingTarget<>(AnimationTargetRef.property("text"));
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .target(new TargetBinding<>(
                        numeric,
                        context -> (int) context.revision(),
                        new TargetPublicationFactory<>()))
                .target(new TargetBinding<>(
                        text,
                        context -> "revision-" + context.revision(),
                        new TargetPublicationFactory<>()))
                .build();

        ScopeAdvanceResult result =
                scope.advance(new ScopeAdvance(0.12, ScopeAdvanceCause.GAME_TICK));

        assertEquals(2, result.steps().size());
        assertEquals(1, result.firstRevision());
        assertEquals(2, result.lastRevision());
        assertEquals(2, numeric.publications.size());
        assertEquals(2, text.publications.size());
        assertEquals(numeric.publications.get(0).revision(), text.publications.get(0).revision());
        assertEquals(numeric.publications.get(1).revision(), text.publications.get(1).revision());
        assertEquals(0.10, scope.view().currentTime(), 1e-9);
        assertEquals(2, scope.view().revision());
    }

    @Test
    void noDeltaAndNoStateChangeProducesNoRevision() {
        RecordingTarget<Integer> target =
                new RecordingTarget<>(AnimationTargetRef.property("number"));
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .target(new TargetBinding<>(
                        target, context -> 1, new TargetPublicationFactory<>()))
                .build();

        ScopeAdvanceResult result =
                scope.advance(new ScopeAdvance(0.0, ScopeAdvanceCause.GAME_TICK));

        assertTrue(result.steps().isEmpty());
        assertEquals(0, result.lastRevision());
        assertTrue(target.publications.isEmpty());
    }

    @Test
    void playbackViewDistinguishesCompletionFromExplicitPause() {
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .rootPlayback("complete", new PlaybackBinding(
                        0.0, 0.05, 0.0, 1.0, 1.0,
                        PlaybackBoundaryPolicy.STOP))
                .build();

        scope.advance(new ScopeAdvance(0.10, ScopeAdvanceCause.GAME_TICK));

        PlaybackView view = scope.view().playbackViews().getFirst();
        assertFalse(view.paused());
        assertFalse(view.active());
        assertTrue(view.completed());
    }

    @Test
    void pauseResumeAndSeekPublishControlStepsWithoutAdvancingThroughPausedDelta() {
        RecordingTarget<Double> target =
                new RecordingTarget<>(AnimationTargetRef.property("time"));
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .target(new TargetBinding<>(
                        target,
                        context -> context.rootTimeline().currentTime(),
                        new TargetPublicationFactory<>()))
                .build();
        scope.advance(new ScopeAdvance(0.10, ScopeAdvanceCause.GAME_TICK));

        ScopeAdvanceResult paused = scope.pause();
        ScopeAdvanceResult frozen =
                scope.advance(new ScopeAdvance(1.0, ScopeAdvanceCause.GAME_TICK));
        assertEquals(1, paused.steps().size());
        assertEquals(ScopeAdvanceCause.PAUSE, paused.steps().getFirst().cause());
        assertTrue(frozen.steps().isEmpty());
        assertTrue(scope.view().paused());

        ScopeAdvanceResult seek = scope.seek(0.25);
        ScopeAdvanceResult resumed = scope.resume();

        assertEquals(1, seek.steps().size());
        assertEquals(TemporalDiscontinuity.SEEK, seek.steps().getFirst().discontinuity());
        assertEquals(0.25, scope.view().currentTime(), 1e-9);
        assertEquals(1, resumed.steps().size());
        assertFalse(scope.view().paused());
    }

    @Test
    void controllerStateCommitsWithTheStepAndSharedInputIsFrozen() {
        InputKey<Boolean> powered = new InputKey<>("test/powered", Boolean.class);
        ControllerBinding<Integer> controller = ControllerBinding.create(new RuntimeController<>() {
            @Override
            public ControlFrame<Integer> evaluate(ControlEvaluationRequest<Integer> request) {
                int next = request.state() == null ? 1 : request.state() + 1;
                return new ControlFrame<>(next, List.of(), List.of());
            }

            @Override
            public Integer initialState() {
                return 0;
            }
        });
        RecordingTarget<Integer> target =
                new RecordingTarget<>(AnimationTargetRef.property("ticks"));
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .controller(controller)
                .target(new TargetBinding<>(
                        target,
                        context -> context.inputs().value(powered).orElse(false) ? 1 : 0,
                        new TargetPublicationFactory<>()))
                .build();
        scope.inputPort().write(powered, true);

        scope.advance(new ScopeAdvance(0.10, ScopeAdvanceCause.GAME_TICK));

        assertEquals(2, controller.state());
        assertEquals(2, target.publications.size());
    }

    @Test
    void playbackAndControllerEventsReachTheirCommittedConsumers() {
        List<String> observed = new ArrayList<>();
        RecordingTarget<Integer> target =
                new RecordingTarget<>(AnimationTargetRef.property("events"));
        RuntimeController<Void> controller = new RuntimeController<>() {
            @Override
            public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                request.playbackEvents().forEach(event ->
                        observed.add("playback:" + event.eventId()));
                return new ControlFrame<>(
                        null,
                        List.of(),
                        List.of(new ControlEvent("controller-ready")));
            }
        };
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .controller(ControllerBinding.create(controller))
                .rootPlayback(
                        "timeline",
                        new PlaybackBinding(
                                0.0, 1.0, 0.0, 1.0, 1.0,
                                PlaybackBoundaryPolicy.HOLD_LAST),
                        List.of(new TimelineEventDefinition("click", 0.05)))
                .target(new TargetBinding<>(
                        target,
                        context -> 1,
                        new TargetPublicationFactory<>()))
                .controlEventSink(event -> {
                    assertFalse(target.publications.isEmpty(),
                            "control events must be delivered after publication routing");
                    observed.add("control:" + event.typeId());
                })
                .build();

        scope.advance(new ScopeAdvance(0.10, ScopeAdvanceCause.GAME_TICK));

        assertEquals(List.of(
                "control:controller-ready",
                "playback:click",
                "control:controller-ready"), observed);
    }

    @Test
    void playbackPauseFreezesLocalTimeAndResumeDoesNotConsumeThePausedDuration() {
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .rootPlayback("wheel", new PlaybackBinding(
                        0.0, 10.0, 0.0, 10.0, 2.0,
                        PlaybackBoundaryPolicy.REPEAT))
                .build();
        PlaybackInstanceId id = scope.view().activePlaybacks().getFirst().id();

        scope.advance(new ScopeAdvance(0.05, ScopeAdvanceCause.GAME_TICK));
        assertEquals(0.10, scope.view().activePlaybacks().getFirst().localTime(), 1e-9);
        assertEquals(2.0, scope.view().activePlaybacks().getFirst().speed(), 1e-9);

        scope.playbackPort().pauseSubtree(id);
        scope.advance(new ScopeAdvance(0.10, ScopeAdvanceCause.GAME_TICK));
        PlaybackView paused = scope.view().activePlaybacks().getFirst();
        assertTrue(paused.paused());
        assertEquals(0.10, paused.localTime(), 1e-9);

        scope.playbackPort().resumeSubtree(id);
        scope.advance(new ScopeAdvance(0.05, ScopeAdvanceCause.GAME_TICK));
        PlaybackView resumed = scope.view().activePlaybacks().getFirst();
        assertFalse(resumed.paused());
        assertEquals(0.20, resumed.localTime(), 1e-9);
    }

    @Test
    void playbackControlCannotMutateStateDuringAnAdvance() throws Exception {
        CountDownLatch controllerEntered = new CountDownLatch(1);
        CountDownLatch releaseController = new CountDownLatch(1);
        RuntimeController<Void> blockingController = new RuntimeController<>() {
            @Override
            public ControlFrame<Void> evaluate(ControlEvaluationRequest<Void> request) {
                controllerEntered.countDown();
                try {
                    if (!releaseController.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting for release");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
                return new ControlFrame<>(null, List.of(), List.of());
            }
        };
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .controller(ControllerBinding.create(blockingController))
                .rootPlayback("wheel", new PlaybackBinding(
                        0.0, 10.0, 0.0, 10.0, 1.0,
                        PlaybackBoundaryPolicy.REPEAT))
                .build();
        PlaybackInstanceId id = scope.view().activePlaybacks().getFirst().id();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        AtomicBoolean controlCompleted = new AtomicBoolean();
        try {
            Future<ScopeAdvanceResult> advancing = executor.submit(
                    () -> scope.advance(new ScopeAdvance(
                            0.05, ScopeAdvanceCause.GAME_TICK)));
            assertTrue(controllerEntered.await(5, TimeUnit.SECONDS));

            Future<?> controlling = executor.submit(() -> {
                scope.playbackPort().setSpeed(id, 0.5);
                controlCompleted.set(true);
            });
            assertFalse(controlCompleted.get(),
                    "playback control must wait for the in-progress logical step");

            releaseController.countDown();
            advancing.get(5, TimeUnit.SECONDS);
            controlling.get(5, TimeUnit.SECONDS);
            assertTrue(controlCompleted.get());
            assertEquals(0.5, scope.view().activePlaybacks().getFirst().speed(), 1e-9);
        } finally {
            releaseController.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void closeIsIdempotentAndRejectsFurtherAdvance() {
        RecordingTarget<Integer> target =
                new RecordingTarget<>(AnimationTargetRef.property("number"));
        ManagedAnimationScope scope = ManagedAnimationScope.builder(
                        SCOPE_ID, LogicalStepPolicy.fixed(0.05, 4))
                .target(new TargetBinding<>(
                        target, context -> 1, new TargetPublicationFactory<>()))
                .build();

        scope.close();
        scope.close();

        assertTrue(scope.isClosed());
        assertEquals(1, target.closeCalls);
        assertThrows(IllegalStateException.class,
                () -> scope.advance(new ScopeAdvance(0.05, ScopeAdvanceCause.GAME_TICK)));
    }

    private static final class RecordingTarget<O> implements AnimationTarget<O> {
        private final AnimationTargetRef ref;
        private final List<TargetPublication<O>> publications = new ArrayList<>();
        private int closeCalls;

        private RecordingTarget(AnimationTargetRef ref) {
            this.ref = ref;
        }

        @Override
        public AnimationTargetRef ref() {
            return ref;
        }

        @Override
        public void publish(TargetPublication<O> publication) {
            publications.add(publication);
        }

        @Override
        public void sample(TargetPublication<O> publication, RenderSampleContext context) {
        }

        @Override
        public void close() {
            closeCalls++;
        }
    }
}
