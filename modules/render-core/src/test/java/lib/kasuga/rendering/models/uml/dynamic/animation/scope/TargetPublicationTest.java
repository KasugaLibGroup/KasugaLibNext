package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TargetPublicationTest {

    private static final TargetChannel MODEL_POSE = new TargetChannel("kasuga:model_pose");

    @Test
    void builtInTargetRefsUseNamespacedChannels() {
        assertEquals(MODEL_POSE, AnimationTargetRef.modelPose().channel());
        assertEquals("kasuga:part_root", AnimationTargetRef.partRoot("body").channel().id());
        assertEquals("kasuga:property", AnimationTargetRef.property("visibility").channel().id());
        assertEquals(AnimationTargetRef.part(3), AnimationTargetRef.partRoot("part/3"));
    }

    @Test
    void targetChannelMustUseANamespacedIdentity() {
        assertThrows(IllegalArgumentException.class, () -> new TargetChannel("model_pose"));
        assertThrows(IllegalArgumentException.class, () -> new TargetChannel("kasuga:"));
    }

    @Test
    void activePublicationCarriesPreviousAndCurrentOutputs() {
        AnimationTargetRef ref = new AnimationTargetRef(MODEL_POSE, "model/root");
        TargetPublication<String> publication = new TargetPublication<>(
                ref,
                7,
                new OutputState.Active<>("old", "new", Continuity.INTERPOLATE));

        assertEquals(ref, publication.ref());
        assertEquals(7, publication.revision());
        assertTrue(publication.outputState() instanceof OutputState.Active);
        @SuppressWarnings("unchecked")
        OutputState.Active<String> active =
                (OutputState.Active<String>) publication.outputState();
        assertEquals("old", active.previousOutput());
        assertEquals("new", active.currentOutput());
        assertEquals(Continuity.INTERPOLATE, active.continuity());
        assertThrows(IllegalArgumentException.class,
                () -> new TargetPublication<>(ref, -1, active));
    }

    @Test
    void activeRequiresCurrentOutputAndClearedCarriesItsReason() {
        assertThrows(NullPointerException.class,
                () -> new OutputState.Active<>(null, null, Continuity.SNAP));

        OutputState.Cleared<String> cleared = new OutputState.Cleared<>("detached");

        assertEquals("detached", cleared.reason());
        assertThrows(IllegalArgumentException.class,
                () -> new OutputState.Cleared<String>(" "));
    }

    @Test
    void differentTargetsKeepTheirOwnOutputTypes() {
        AnimationTargetRef rootRef = AnimationTargetRef.partRoot("body");
        AnimationTargetRef poseRef = AnimationTargetRef.modelPose();
        RecordingTarget<Double> rootTarget = new RecordingTarget<>(rootRef);
        RecordingTarget<TestPose> poseTarget = new RecordingTarget<>(poseRef);

        TargetPublication<Double> rootPublication = new TargetPublication<>(
                rootRef, 1, new OutputState.Active<>(0.0, 1.0, Continuity.INTERPOLATE));
        TargetPublication<TestPose> posePublication = new TargetPublication<>(
                poseRef, 1, new OutputState.Active<>(new TestPose(0.0), new TestPose(1.0), Continuity.SNAP));

        rootTarget.publish(rootPublication);
        poseTarget.publish(posePublication);

        assertSame(rootPublication, rootTarget.publication);
        assertSame(posePublication, poseTarget.publication);
    }

    @Test
    void frameInputsAreReadThroughTypedKeys() {
        FrameInputKey<Double> scale = new FrameInputKey<>("render/model_scale", Double.class);
        FrameInputKey<Boolean> visible = new FrameInputKey<>("render/visible", Boolean.class);
        FrameInputKey<String> sameNameDifferentType =
                new FrameInputKey<>("render/model_scale", String.class);
        FrameInputSnapshot empty = FrameInputSnapshot.empty();
        FrameInputSnapshot snapshot = empty
                .with(scale, 0.75)
                .with(visible, true);

        assertEquals(Optional.of(0.75), snapshot.value(scale));
        assertEquals(Optional.of(true), snapshot.value(visible));
        assertEquals(Optional.empty(), snapshot.value(sameNameDifferentType));
        assertEquals(Optional.empty(), empty.value(scale));
    }

    @Test
    void renderSampleContextValidatesItsInterpolationAlpha() {
        RenderFrameToken token = new RenderFrameToken("frame-10");
        FrameInputSnapshot inputs = FrameInputSnapshot.empty();

        RenderSampleContext atStart = new RenderSampleContext(token, 0.0, inputs);
        RenderSampleContext atEnd = new RenderSampleContext(token, 1.0, inputs);

        assertEquals(0.0, atStart.interpolationAlpha());
        assertEquals(1.0, atEnd.interpolationAlpha());
        assertThrows(IllegalArgumentException.class,
                () -> new RenderSampleContext(token, -0.01, inputs));
        assertThrows(IllegalArgumentException.class,
                () -> new RenderSampleContext(token, 1.01, inputs));
        assertThrows(IllegalArgumentException.class,
                () -> new RenderSampleContext(token, Double.NaN, inputs));
        assertThrows(IllegalArgumentException.class,
                () -> new RenderSampleContext(token, Double.POSITIVE_INFINITY, inputs));
    }

    @Test
    void targetReceivesPublicationAndRenderContextWithIdempotentClose() {
        RecordingTarget<TestPose> target = new RecordingTarget<>(AnimationTargetRef.modelPose());
        TargetPublication<TestPose> publication = new TargetPublication<>(
                target.ref(), 2, new OutputState.Active<>(
                        new TestPose(0.25), new TestPose(0.75), Continuity.INTERPOLATE));
        RenderSampleContext context = new RenderSampleContext(
                new RenderFrameToken("frame-11"),
                0.5,
                FrameInputSnapshot.empty());

        target.publish(publication);
        target.sample(context);

        assertSame(publication, target.publication);
        assertSame(context, target.sampleContext);
        assertDoesNotThrow(() -> {
            target.close();
            target.close();
        });
    }

    private record TestPose(double value) {
    }

    private static final class RecordingTarget<O> implements AnimationTarget<O> {
        private final AnimationTargetRef ref;
        private TargetPublication<O> publication;
        private RenderSampleContext sampleContext;

        private RecordingTarget(AnimationTargetRef ref) {
            this.ref = ref;
        }

        @Override
        public AnimationTargetRef ref() {
            return ref;
        }

        @Override
        public void publish(TargetPublication<O> publication) {
            this.publication = publication;
        }

        @Override
        public void sample(RenderSampleContext context) {
            this.sampleContext = context;
        }

        @Override
        public void sample(TargetPublication<O> publication, RenderSampleContext context) {
            sample(context);
        }
    }
}
