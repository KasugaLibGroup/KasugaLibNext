package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.dynamic.ModelPoseBinding;
import lib.kasuga.rendering.models.uml.dynamic.PoseDriver;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnimationScopeTest {

    @Test
    void oneStepPublishesTheSameRevisionToEveryTargetInContentOrder() {
        RecordingTarget first = new RecordingTarget(AnimationTargetRef.part(0));
        RecordingTarget second = new RecordingTarget(AnimationTargetRef.part(1));
        ManagedAnimationScope scope = scope(first, second);

        scope.advance(gameTick(0.05));

        assertEquals(1, first.publishCount);
        assertEquals(1, second.publishCount);
        assertEquals(1, first.lastPublication.revision());
        assertEquals(1, second.lastPublication.revision());
        assertEquals(ScopeAdvanceCause.GAME_TICK, first.lastEvaluation.timeStep().cause());
        assertEquals("part/0", first.ref().address());
    }

    @Test
    void pauseAndResumeFreezeAndContinueTheScopeRevision() {
        RecordingTarget target = new RecordingTarget(AnimationTargetRef.modelPose());
        ManagedAnimationScope scope = scope(target);

        scope.advance(gameTick(0.05));
        scope.pause();
        assertTrue(scope.isPaused());
        scope.advance(gameTick(0.05));
        assertEquals(2, target.publishCount);

        scope.resume();
        assertFalse(scope.isPaused());
        scope.advance(gameTick(0.05));
        assertEquals(4, target.publishCount);
        assertEquals(4, target.lastPublication.revision());
    }

    @Test
    void closeIsIdempotentAndClosesEveryTargetExactlyOnce() {
        RecordingTarget first = new RecordingTarget(AnimationTargetRef.part(0));
        RecordingTarget second = new RecordingTarget(AnimationTargetRef.part(1));
        ManagedAnimationScope scope = scope(first, second);

        scope.close();
        scope.close();
        assertThrows(IllegalStateException.class, () -> scope.advance(gameTick(0.05)));

        assertTrue(scope.isClosed());
        assertEquals(1, first.closeCount);
        assertEquals(1, second.closeCount);
        assertEquals(0, first.publishCount);
        assertEquals(0, second.publishCount);
    }

    @Test
    void duplicateTargetRefsAreRejected() {
        RecordingTarget first = new RecordingTarget(AnimationTargetRef.part(0));
        RecordingTarget second = new RecordingTarget(AnimationTargetRef.part(0));

        assertThrows(IllegalArgumentException.class, () -> scope(first, second));
    }

    @Test
    void invalidTimeInputsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> gameTick(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> gameTick(-1.0));
    }

    @Test
    void merelyReadingModelPosingDoesNotReplaceAnExistingDriver() {
        ModelInstance instance = ModelInstanceFixture.minimal();
        PoseDriver existing = new PoseDriver() {};
        ModelPoseBinding binding = ModelPoseBinding.install(instance, existing);

        instance.getPosing();

        assertSame(binding, instance.getPoseDriver());
    }

    private static ManagedAnimationScope scope(RecordingTarget... targets) {
        ManagedAnimationScope.Builder builder = ManagedAnimationScope.builder(
                UUID.randomUUID(), LogicalStepPolicy.fixed(0.05, 5));
        for (RecordingTarget target : targets) {
            builder.target(new TargetBinding<>(
                    target,
                    context -> {
                        target.lastEvaluation = context;
                        return 1.0;
                    },
                    new TargetPublicationFactory<>()));
        }
        return builder.build();
    }

    private static ScopeAdvance gameTick(double seconds) {
        return new ScopeAdvance(seconds, ScopeAdvanceCause.GAME_TICK);
    }

    private static final class RecordingTarget implements AnimationTarget<Double> {
        private final AnimationTargetRef ref;
        private int publishCount;
        private int closeCount;
        private TargetEvaluationContext lastEvaluation;
        private TargetPublication<Double> lastPublication;

        private RecordingTarget(AnimationTargetRef ref) {
            this.ref = ref;
        }

        @Override
        public AnimationTargetRef ref() {
            return ref;
        }

        @Override
        public void publish(TargetPublication<Double> publication) {
            publishCount++;
            lastPublication = publication;
        }

        @Override
        public void sample(TargetPublication<Double> publication, RenderSampleContext context) {
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
