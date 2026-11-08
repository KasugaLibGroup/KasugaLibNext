package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstanceFixture;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PartMotionSamplerTest {

    private static PartMotionSampler sampler(float scale) {
        return new PartMotionSampler(ModelPart.Spin.tick(() -> 0f, scale));
    }

    @Test
    void nothingIsDrawnBeforeTheFirstPublish() {
        assertNull(sampler(1f).snapshot().sample(0.5f));
    }

    @Test
    void theHalfwayFrameSitsBetweenTheTwoTicks() {
        PartMotionSampler sampler = sampler(1f);
        AtomicReference<Float> angle = new AtomicReference<>(10f);
        sampler = new PartMotionSampler(ModelPart.Spin.tick(angle::get));
        sampler.initialSnapshot(null);
        angle.set(20f);
        sampler.publishTick(null);
        assertTransform(new Transform().rotate(15f, 0f, 0f, true), sampler.snapshot().sample(0.5f));
        assertTransform(new Transform().rotate(10f, 0f, 0f, true), sampler.snapshot().sample(0f));
        assertTransform(new Transform().rotate(20f, 0f, 0f, true), sampler.snapshot().sample(1f));
    }

    @Test
    void wrapAroundKeepsSpinningForward() {
        AtomicReference<Float> angle = new AtomicReference<>(350f);
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.Spin.tick(angle::get));
        sampler.initialSnapshot(null);
        angle.set(2f);
        sampler.publishTick(null);
        assertTransform(new Transform().rotate(356f, 0f, 0f, true), sampler.snapshot().sample(0.5f));
    }

    @Test
    void wrapAroundAlsoWorksBackwards() {
        AtomicReference<Float> angle = new AtomicReference<>(2f);
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.Spin.tick(angle::get));
        sampler.initialSnapshot(null);
        angle.set(350f);
        sampler.publishTick(null);
        assertTransform(new Transform().rotate(-4f, 0f, 0f, true), sampler.snapshot().sample(0.5f));
    }

    @Test
    void scaleAppliesToTheStepNotOnlyToTheAbsolute() {
        AtomicReference<Float> angle = new AtomicReference<>(350f);
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.Spin.tick(angle::get, 2f));
        sampler.initialSnapshot(null);
        angle.set(2f);
        sampler.publishTick(null);
        assertTransform(new Transform().rotate(712f, 0f, 0f, true), sampler.snapshot().sample(0.5f));
    }

    @Test
    void frameSpinUsesTheCurrentCreateAngleWithoutUnwrappingOrInterpolation() {
        AtomicReference<Float> angle = new AtomicReference<>(350f);
        AtomicInteger sourceReads = new AtomicInteger();
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.Spin.frame(() -> {
            sourceReads.incrementAndGet();
            return angle.get();
        }, 2f));
        assertTransform(new Transform().rotate(700f, 0f, 0f, true), sampler.initialSnapshot(null).sample(1f));
        assertEquals(1, sourceReads.get());

        angle.set(2f);
        sampler.publishTick(null);
        sampler.publishFrame();
        assertEquals(2, sourceReads.get());
        assertTransform(new Transform().rotate(4f, 0f, 0f, true), sampler.snapshot().sample(0f));
        assertTransform(new Transform().rotate(4f, 0f, 0f, true), sampler.snapshot().sample(0.25f));
        assertTransform(new Transform().rotate(4f, 0f, 0f, true), sampler.snapshot().sample(0.5f));
        assertTransform(new Transform().rotate(4f, 0f, 0f, true), sampler.snapshot().sample(0.75f));
        assertTransform(new Transform().rotate(4f, 0f, 0f, true), sampler.snapshot().sample(1f));
        assertEquals(2, sourceReads.get());

        angle.set(350f);
        assertTransform(new Transform().rotate(4f, 0f, 0f, true), sampler.snapshot().sample(0.5f));
        assertEquals(2, sourceReads.get(), "render sampling must not read a frame source");
    }

    @Test
    void aHostFollowingPartTakesNoAngleAtAll() {
        PartMotionSampler sampler = new PartMotionSampler(null);
        sampler.publishTick(null);
        assertNull(sampler.initialSnapshot(null).sample(1f));
        assertNull(sampler.snapshot().sample(0.5f));
    }

    @Test
    void tickTransformInterpolatesTranslationRotationAndScaleExactlyOnce() {
        AtomicReference<Transform> current = new AtomicReference<>(new Transform()
                .setPosition(new org.joml.Vector3f(1f, 2f, 3f))
                .scale(1f, 1f, 1f));
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.tickTransform(
                machine -> current.get()));
        assertTransform(current.get(), sampler.initialSnapshot(null).sample(1f));

        current.set(new Transform()
                .rotate(0f, 90f, 0f, true)
                .scale(3f, 3f, 3f)
                .setPosition(new org.joml.Vector3f(3f, 4f, 5f)));
        sampler.publishTick(null);

        Transform halfway = sampler.snapshot().sample(0.5f);
        assertTransform(new Transform()
                .setPosition(new Vector3f(2f, 3f, 4f))
                .rotate(0f, 45f, 0f, true)
                .scale(2f, 2f, 2f), halfway);
    }

    @Test
    void frameTransformIsReadAtRenderTimeAndNeverInterpolated() {
        AtomicReference<Transform> current = new AtomicReference<>(new Transform().translate(1f, 0f, 0f));
        AtomicInteger sourceReads = new AtomicInteger();
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.frameTransform(() -> {
            sourceReads.incrementAndGet();
            return current.get();
        }));
        assertTransform(current.get(), sampler.initialSnapshot(null).sample(1f));
        assertEquals(1, sourceReads.get());

        current.set(new Transform().translate(2f, 0f, 0f));
        sampler.publishTick(null);
        sampler.publishFrame();
        assertEquals(2, sourceReads.get());
        current.set(new Transform().translate(8f, 0f, 0f));

        assertTransform(new Transform().translate(2f, 0f, 0f), sampler.snapshot().sample(0.25f));
        assertTransform(new Transform().translate(2f, 0f, 0f), sampler.snapshot().sample(0.75f));
        assertEquals(2, sourceReads.get(), "render sampling must return the published frame copy");
    }

    @Test
    void repeatedSamplingIsPureAndDoesNotMutateAModelInstance() {
        PartMotionSampler sampler = new PartMotionSampler(ModelPart.tickTransform(
                machine -> new Transform()
                        .rotate(0f, 90f, 0f, true)
                        .setPosition(new org.joml.Vector3f(2f, 3f, 4f))));
        var unrelated = ModelInstanceFixture.minimal();
        sampler.initialSnapshot(null);
        sampler.publishTick(null);

        Transform first = sampler.snapshot().sample(0.25f);
        Transform second = sampler.snapshot().sample(0.25f);

        assertEquals(first, second);
        assertNull(unrelated.getPoseDriver());
        assertNull(unrelated.getRootTransformDriver());
    }

    private static void assertTransform(Transform expected, Transform actual) {
        assertTrue(actual != null);
        for (int row = 0; row < 4; row++) {
            for (int column = 0; column < 4; column++) {
                assertEquals(expected.transform().get(row, column),
                        actual.transform().get(row, column), 1e-4f);
            }
        }
    }
}
