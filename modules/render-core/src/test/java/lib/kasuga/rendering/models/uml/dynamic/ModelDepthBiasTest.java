package lib.kasuga.rendering.models.uml.dynamic;

import lib.kasuga.rendering.models.uml.backend.DepthBias;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelDepthBiasTest {
    @Test
    void rejectsNonFiniteOffsetsBeforeTheyReachTheDepthBuffer() {
        for (float invalid : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            assertThrows(IllegalArgumentException.class, () -> new DepthBias(invalid, 0));
            assertThrows(IllegalArgumentException.class, () -> new DepthBias(0, invalid));
        }
    }

    @Test
    void zeroPoliciesCompareEquallyForBatching() {
        assertEquals(DepthBias.NONE, new DepthBias(-0.0f, -0.0f));
        assertEquals(DepthBias.NONE.hashCode(), new DepthBias(-0.0f, 0).hashCode());
        assertFalse(new DepthBias(-0.0f, 0).enabled());
        assertTrue(DepthBias.DECAL.enabled());
    }

    @Test
    void optingAnInstanceIntoLayeringDoesNotMoveItOrAffectAnotherInstance() {
        try (var overlay = new DummyModelInstance(); var other = new DummyModelInstance()) {
            var transform = overlay.getSkeletonInstance().getTransform().copy();
            assertEquals(DepthBias.NONE, overlay.getDepthBias());
            overlay.setDepthBias(DepthBias.DECAL);
            assertEquals(DepthBias.DECAL, overlay.getDepthBias());
            assertEquals(DepthBias.NONE, other.getDepthBias());
            assertEquals(transform.transform(), overlay.getSkeletonInstance().getTransform().transform());
            assertThrows(NullPointerException.class, () -> overlay.setDepthBias(null));
            assertEquals(DepthBias.DECAL, overlay.getDepthBias());
            overlay.setDepthBias(DepthBias.NONE);
            assertEquals(DepthBias.NONE, overlay.getDepthBias());
        }
    }
}
