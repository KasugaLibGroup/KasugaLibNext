package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RootSnapshotTest {

    @Test
    void valuesAreCopiedOnInputAndOutput() {
        Transform previous = new Transform().translate(1f, 2f, 3f);
        Transform current = new Transform().translate(5f, 6f, 7f);
        RootSnapshot snapshot = new RootSnapshot(previous, current);

        previous.translate(100f, 100f, 100f);
        current.translate(100f, 100f, 100f);
        Transform exposedPrevious = snapshot.previous();
        Transform exposedCurrent = snapshot.current();
        exposedPrevious.translate(-100f, -100f, -100f);
        exposedCurrent.translate(-100f, -100f, -100f);

        assertPosition(1f, 2f, 3f, snapshot.previous());
        assertPosition(5f, 6f, 7f, snapshot.current());
        assertNotSame(snapshot.previous(), snapshot.previous());
    }

    @Test
    void samplesIntoACallerTargetOrReturnsACopy() {
        RootSnapshot snapshot = new RootSnapshot(
                new Transform().translate(0f, 0f, 0f),
                new Transform().translate(10f, 20f, 30f));
        Transform target = new Transform().translate(99f, 99f, 99f);

        Transform sampled = snapshot.at(0.5f, target);

        assertSame(target, sampled);
        assertPosition(5f, 10f, 15f, target);
        assertPosition(10f, 20f, 30f, snapshot.at(1f));
        assertPosition(0f, 0f, 0f, snapshot.at(0f));
    }

    @Test
    void nonFiniteAlphaIsRejected() {
        RootSnapshot snapshot = new RootSnapshot(new Transform(), new Transform());

        assertThrows(IllegalArgumentException.class, () -> snapshot.at(Float.NaN));
        assertThrows(IllegalArgumentException.class, () -> snapshot.at(Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class,
                () -> snapshot.at(Float.NEGATIVE_INFINITY, new Transform()));
    }

    @Test
    void firstAdvanceDoesNotInterpolateFromIdentityAndFramePublishDoesNotInterpolate() {
        ModelGroupFrameState state = new ModelGroupFrameState();
        RigidPartBinding target = new RigidPartBinding(
                0, new PartMotionSampler(null), () -> null, state, new Transform());
        ModelGroupScope scope = new ModelGroupScope(state, new Transform(), java.util.List.of(target));

        scope.tick(0.05f, new Transform().translate(7f, 8f, 9f));
        assertPosition(7f, 8f, 9f, state.current().root().at(0f));
        assertPosition(7f, 8f, 9f, state.current().root().at(1f));

        scope.tick(0.05f, new Transform().translate(11f, 12f, 13f));
        assertPosition(9f, 10f, 11f, state.current().root().at(0.5f));

        scope.publishFrame(new Transform().translate(20f, 21f, 22f));
        assertPosition(20f, 21f, 22f, state.current().root().at(0f));
        assertPosition(20f, 21f, 22f, state.current().root().at(0.5f));
        assertPosition(20f, 21f, 22f, state.current().root().at(1f));
    }

    private static void assertPosition(float x, float y, float z, Transform transform) {
        assertEquals(x, transform.getPosition().x, 1e-5f);
        assertEquals(y, transform.getPosition().y, 1e-5f);
        assertEquals(z, transform.getPosition().z, 1e-5f);
    }
}
