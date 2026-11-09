package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetRegistryTest {

    private final PlaybackView playback = new PlaybackView(
            new PlaybackInstanceId(UUID.fromString("00000000-0000-0000-0000-000000000001"), 1),
            "walk",
            null,
            0.25,
            1.0,
            false);

    @Test
    void publishesHeterogeneousTargetsInRegistrationOrderWithTheirOwnPreviousOutputs() {
        AnimationTargetRef partRef = AnimationTargetRef.partRoot("wheel");
        AnimationTargetRef poseRef = AnimationTargetRef.modelPose();
        RecordingTarget<Double> partTarget = new RecordingTarget<>(partRef);
        RecordingTarget<TestPose> poseTarget = new RecordingTarget<>(poseRef);
        List<String> evaluationOrder = new ArrayList<>();
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                partTarget,
                context -> {
                    evaluationOrder.add("part");
                    assertEquals(List.of(playback), context.activePlaybacks());
                    return context.revision() == 1 ? 0.1 : 0.3;
                },
                new TargetPublicationFactory<>()));
        registry.register(new TargetBinding<>(
                poseTarget,
                context -> {
                    evaluationOrder.add("pose");
                    return new TestPose(context.revision() == 1 ? 0.25 : 0.75);
                },
                new TargetPublicationFactory<>()));

        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));

        assertEquals(List.of("part", "pose"), evaluationOrder);
        assertEquals(List.of(partTarget, poseTarget), registry.renderTargets());
        assertEquals(1, partTarget.publications.size());
        assertEquals(1, poseTarget.publications.size());
        assertActive(partTarget.publications.getFirst(), partRef, 1, null, 0.1, Continuity.SNAP);
        assertActive(
                poseTarget.publications.getFirst(),
                poseRef,
                1,
                null,
                new TestPose(0.25),
                Continuity.SNAP);

        registry.publish(context(2, 0.05, 0.10, TemporalDiscontinuity.NONE));

        assertActive(
                partTarget.publications.getLast(),
                partRef,
                2,
                0.1,
                0.3,
                Continuity.INTERPOLATE);
        assertActive(
                poseTarget.publications.getLast(),
                poseRef,
                2,
                new TestPose(0.25),
                new TestPose(0.75),
                Continuity.INTERPOLATE);
    }

    @Test
    void inactiveOutputClearsAndTheNextActiveOutputRestartsWithSnap() {
        RecordingTarget<Double> target = new RecordingTarget<>(AnimationTargetRef.property("lift"));
        int[] evaluation = {0};
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                target,
                context -> switch (evaluation[0]++) {
                    case 0 -> 1.0;
                    case 1 -> null;
                    default -> 3.0;
                },
                new TargetPublicationFactory<>()));

        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));
        registry.publish(context(2, 0.05, 0.10, TemporalDiscontinuity.NONE));

        @SuppressWarnings("unchecked")
        OutputState.Cleared<Double> cleared = (OutputState.Cleared<Double>) outputState(
                target.publications.getLast(), OutputState.Cleared.class);
        assertEquals("inactive", cleared.reason());

        registry.publish(context(3, 0.10, 0.15, TemporalDiscontinuity.NONE));

        assertActive(target.publications.getLast(), target.ref(), 3, null, 3.0, Continuity.SNAP);
    }

    @Test
    void zeroDeltaHoldsCurrentAndSeekSnaps() {
        RecordingTarget<Double> target = new RecordingTarget<>(AnimationTargetRef.property("lift"));
        int[] evaluation = {0};
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                target,
                context -> 1.0 + evaluation[0]++,
                new TargetPublicationFactory<>()));

        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));
        registry.publish(context(2, 0.05, 0.05, TemporalDiscontinuity.CONTROL));
        registry.publish(context(3, 0.05, 0.02, TemporalDiscontinuity.SEEK));

        assertActive(
                target.publications.get(1),
                target.ref(),
                2,
                1.0,
                2.0,
                Continuity.HOLD_CURRENT);
        assertActive(
                target.publications.getLast(),
                target.ref(),
                3,
                2.0,
                3.0,
                Continuity.SNAP);
    }

    @Test
    void rejectsDuplicateTargetRefsWithoutReplacingTheFirstBinding() {
        RecordingTarget<Double> first = new RecordingTarget<>(AnimationTargetRef.property("lift"));
        RecordingTarget<Double> duplicate = new RecordingTarget<>(AnimationTargetRef.property("lift"));
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                first, context -> 1.0, new TargetPublicationFactory<>()));

        assertThrows(IllegalArgumentException.class, () -> registry.register(new TargetBinding<>(
                duplicate, context -> 2.0, new TargetPublicationFactory<>())));

        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));

        assertEquals(1, first.publications.size());
        assertTrue(duplicate.publications.isEmpty());
        assertEquals(List.of(first), registry.renderTargets());
    }

    @Test
    void targetFailureIsIsolatedAndLaterTargetsContinue() {
        RecordingTarget<Double> failing =
                new RecordingTarget<>(AnimationTargetRef.property("first"), true);
        RecordingTarget<Double> succeeding =
                new RecordingTarget<>(AnimationTargetRef.property("second"));
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                failing, context -> 1.0, new TargetPublicationFactory<>()));
        registry.register(new TargetBinding<>(
                succeeding, context -> 2.0, new TargetPublicationFactory<>()));

        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));

        assertEquals(1, failing.publishAttempts);
        assertEquals(1, succeeding.publications.size());
        assertActive(
                succeeding.publications.getFirst(),
                succeeding.ref(),
                1,
                null,
                2.0,
                Continuity.SNAP);
    }

    @Test
    void aFailedPublicationDoesNotBecomeTheNextVisiblePreviousOutput() {
        List<TargetPublication<Double>> accepted = new ArrayList<>();
        int[] attempts = {0};
        AnimationTarget<Double> recovering = new AnimationTarget<>() {
            private final AnimationTargetRef ref = AnimationTargetRef.property("recovering");

            @Override
            public AnimationTargetRef ref() {
                return ref;
            }

            @Override
            public void publish(TargetPublication<Double> publication) {
                if (attempts[0]++ == 0) {
                    throw new IllegalStateException("first publication failed");
                }
                accepted.add(publication);
            }

            @Override
            public void sample(TargetPublication<Double> publication, RenderSampleContext context) {
            }
        };
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                recovering, context -> (double) context.revision(),
                new TargetPublicationFactory<>()));

        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));
        registry.publish(context(2, 0.05, 0.10, TemporalDiscontinuity.NONE));

        assertEquals(2, attempts[0]);
        assertEquals(1, accepted.size());
        assertActive(
                accepted.getFirst(),
                recovering.ref(),
                2,
                null,
                2.0,
                Continuity.SNAP);
    }

    @Test
    void aFailedPublicationKeepsTheLastAcceptedPublicationVisible() {
        List<TargetPublication<Double>> accepted = new ArrayList<>();
        List<Long> sampledRevisions = new ArrayList<>();
        int[] attempts = {0};
        AnimationTarget<Double> flaky = new AnimationTarget<>() {
            private final AnimationTargetRef ref = AnimationTargetRef.property("flaky");

            @Override
            public AnimationTargetRef ref() {
                return ref;
            }

            @Override
            public void publish(TargetPublication<Double> publication) {
                if (attempts[0]++ == 1) {
                    throw new IllegalStateException("reload publication failed");
                }
                accepted.add(publication);
            }

            @Override
            public void sample(TargetPublication<Double> publication,
                               RenderSampleContext context) {
                sampledRevisions.add(publication.revision());
            }
        };
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                flaky,
                context -> (double) context.revision(),
                new TargetPublicationFactory<>()));

        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));
        registry.sample(renderContext("before-failure"));
        registry.publish(context(2, 0.05, 0.10, TemporalDiscontinuity.NONE));
        registry.sample(renderContext("after-failure"));

        assertEquals(2, attempts[0]);
        assertEquals(1, accepted.size());
        assertEquals(List.of(1L, 1L), sampledRevisions);
    }

    @Test
    void closeAggregatesFailuresAndIsIdempotent() {
        RecordingTarget<Double> first =
                new RecordingTarget<>(AnimationTargetRef.property("first"), false, true);
        RecordingTarget<Double> second =
                new RecordingTarget<>(AnimationTargetRef.property("second"), false, true);
        RecordingTarget<Double> third = new RecordingTarget<>(AnimationTargetRef.property("third"));
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                first, context -> 1.0, new TargetPublicationFactory<>()));
        registry.register(new TargetBinding<>(
                second, context -> 2.0, new TargetPublicationFactory<>()));
        registry.register(new TargetBinding<>(
                third, context -> 3.0, new TargetPublicationFactory<>()));

        IllegalStateException failure = assertThrows(IllegalStateException.class, registry::close);

        assertEquals(1, failure.getSuppressed().length);
        assertEquals(1, first.closeAttempts);
        assertEquals(1, second.closeAttempts);
        assertEquals(1, third.closeAttempts);
        assertTrue(registry.renderTargets().isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.register(new TargetBinding<>(
                new RecordingTarget<>(AnimationTargetRef.property("late")),
                context -> 4.0,
                new TargetPublicationFactory<>())));
        assertThrows(IllegalStateException.class,
                () -> registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE)));
        registry.close();
        assertEquals(1, first.closeAttempts);
        assertEquals(1, second.closeAttempts);
        assertEquals(1, third.closeAttempts);
    }

    @Test
    void evaluationContextDefensivelyCopiesPlaybackViews() {
        List<PlaybackView> mutable = new ArrayList<>(List.of(playback));
        TargetEvaluationContext context = new TargetEvaluationContext(
                4,
                step(0.10, 0.15, TemporalDiscontinuity.NONE),
                new RootTimelineSnapshot(0.10, 0.15, 0.0, false, false),
                new ScopeInputSnapshot(Map.of(), Set.of()),
                mutable);

        mutable.clear();

        assertEquals(List.of(playback), context.activePlaybacks());
        assertThrows(UnsupportedOperationException.class,
                () -> context.activePlaybacks().add(playback));
    }

    @Test
    void renderUsesOneCompletedBatchWhileTheNextPublicationIsInProgress() throws Exception {
        AnimationTargetRef firstRef = AnimationTargetRef.property("first");
        AnimationTargetRef secondRef = AnimationTargetRef.property("second");
        BlockingTarget first = new BlockingTarget(firstRef);
        RecordingTarget<Double> second = new RecordingTarget<>(secondRef);
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                first, context -> (double) context.revision(), new TargetPublicationFactory<>()));
        registry.register(new TargetBinding<>(
                second, context -> (double) context.revision(), new TargetPublicationFactory<>()));

        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));
        registry.sample(renderContext("before"));
        assertEquals(List.of(1L), first.sampledRevisions);
        assertEquals(List.of(1L), second.sampledRevisions);

        first.blockRevision = 2;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> publishing = executor.submit(
                    () -> registry.publish(context(2, 0.05, 0.10, TemporalDiscontinuity.NONE)));
            assertTrue(first.revisionTwoPublishing.await(5, TimeUnit.SECONDS));

            registry.sample(renderContext("during"));
            assertEquals(List.of(1L, 1L), first.sampledRevisions);
            assertEquals(List.of(1L, 1L), second.sampledRevisions);

            first.releaseRevisionTwo.countDown();
            publishing.get(5, TimeUnit.SECONDS);
        } finally {
            first.releaseRevisionTwo.countDown();
            executor.shutdownNow();
        }

        registry.sample(renderContext("after"));
        assertEquals(List.of(1L, 1L, 2L), first.sampledRevisions);
        assertEquals(List.of(1L, 1L, 2L), second.sampledRevisions);
    }

    @Test
    void repeatedSamplingOfTheSameRenderFrameTokenIsIdempotent() {
        RecordingTarget<Double> target =
                new RecordingTarget<>(AnimationTargetRef.property("lift"));
        TargetRegistry registry = new TargetRegistry();
        registry.register(new TargetBinding<>(
                target, context -> 1.0, new TargetPublicationFactory<>()));
        registry.publish(context(1, 0.0, 0.05, TemporalDiscontinuity.NONE));

        RenderSampleContext context = renderContext("same-frame");
        registry.sample(context);
        registry.sample(context);

        assertEquals(List.of(1L), target.sampledRevisions);
        assertEquals(1, target.publishAttempts,
                "render sampling must not publish the same batch again");
    }

    private TargetEvaluationContext context(
            long revision,
            double previousTime,
            double currentTime,
            TemporalDiscontinuity discontinuity) {
        return new TargetEvaluationContext(
                revision,
                step(previousTime, currentTime, discontinuity),
                new RootTimelineSnapshot(previousTime, currentTime, 0.0, false, false),
                new ScopeInputSnapshot(Map.of(), Set.of()),
                List.of(playback));
    }

    private static TemporalStep step(
            double previousTime,
            double currentTime,
            TemporalDiscontinuity discontinuity) {
        return new TemporalStep(
                previousTime,
                currentTime,
                currentTime - previousTime,
                ScopeAdvanceCause.GAME_TICK,
                discontinuity);
    }

    private static RenderSampleContext renderContext(String frame) {
        return new RenderSampleContext(
                new RenderFrameToken(frame),
                0.5,
                FrameInputSnapshot.empty());
    }

    private static <O> void assertActive(
            TargetPublication<O> publication,
            AnimationTargetRef ref,
            long revision,
            O previous,
            O current,
            Continuity continuity) {
        assertSame(ref, publication.ref());
        assertEquals(revision, publication.revision());
        @SuppressWarnings("unchecked")
        OutputState.Active<O> active = (OutputState.Active<O>) outputState(
                publication, OutputState.Active.class);
        assertEquals(previous, active.previousOutput());
        assertEquals(current, active.currentOutput());
        assertEquals(continuity, active.continuity());
        if (previous == null) {
            assertNull(active.previousOutput());
        }
    }

    private static <O> OutputState<O> outputState(
            TargetPublication<O> publication,
            Class<? extends OutputState> expectedType) {
        assertTrue(expectedType.isInstance(publication.outputState()));
        return publication.outputState();
    }

    private record TestPose(double value) {
    }

    private static final class RecordingTarget<O> implements AnimationTarget<O> {
        private final AnimationTargetRef ref;
        private final boolean failOnPublish;
        private final boolean failOnClose;
        private final List<TargetPublication<O>> publications = new ArrayList<>();
        private final List<Long> sampledRevisions = new ArrayList<>();
        private int publishAttempts;
        private int closeAttempts;

        private RecordingTarget(AnimationTargetRef ref) {
            this(ref, false, false);
        }

        private RecordingTarget(AnimationTargetRef ref, boolean failOnPublish) {
            this(ref, failOnPublish, false);
        }

        private RecordingTarget(AnimationTargetRef ref, boolean failOnPublish, boolean failOnClose) {
            this.ref = ref;
            this.failOnPublish = failOnPublish;
            this.failOnClose = failOnClose;
        }

        @Override
        public AnimationTargetRef ref() {
            return ref;
        }

        @Override
        public void publish(TargetPublication<O> publication) {
            publishAttempts++;
            if (failOnPublish) {
                throw new IllegalStateException("target publish failed");
            }
            publications.add(publication);
        }

        @Override
        public void sample(TargetPublication<O> publication, RenderSampleContext context) {
            sampledRevisions.add(publication.revision());
        }

        @Override
        public void close() {
            closeAttempts++;
            if (failOnClose) {
                throw new IllegalStateException("target close failed");
            }
        }
    }

    private static final class BlockingTarget implements AnimationTarget<Double> {
        private final AnimationTargetRef ref;
        private final List<Long> sampledRevisions = new ArrayList<>();
        private final CountDownLatch revisionTwoPublishing = new CountDownLatch(1);
        private final CountDownLatch releaseRevisionTwo = new CountDownLatch(1);
        private volatile long blockRevision = Long.MIN_VALUE;

        private BlockingTarget(AnimationTargetRef ref) {
            this.ref = ref;
        }

        @Override
        public AnimationTargetRef ref() {
            return ref;
        }

        @Override
        public void publish(TargetPublication<Double> publication) {
            if (publication.revision() == blockRevision) {
                revisionTwoPublishing.countDown();
                try {
                    if (!releaseRevisionTwo.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("timed out waiting to finish publication");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
        }

        @Override
        public void sample(TargetPublication<Double> publication, RenderSampleContext context) {
            sampledRevisions.add(publication.revision());
        }
    }
}
