package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Numeric invariant reproduced with the macOS CGL probe in src/test/native. */
class PeelDepthPrecisionTest {
    @Test void roundedDownStoredDepthMakesOneSurfaceRepeatForever() {
        float fragment = .975f;
        float implicitStored = Math.nextDown(fragment);
        for (int layer = 0; layer < 32; layer++) {
            assertTrue(fragment > implicitStored);
        }
        assertTrue(1 - Math.pow(1 - .5, 32) > .999999);
    }

    @Test void explicitDepthRejectsSameSurfaceButPreservesAdjacentDepths() {
        float fragment = .975f;
        float explicitStored = fragment;
        assertFalse(fragment > explicitStored);
        assertTrue(Math.nextUp(fragment) > explicitStored);
        assertFalse(Math.nextDown(fragment) > explicitStored);
    }
}
