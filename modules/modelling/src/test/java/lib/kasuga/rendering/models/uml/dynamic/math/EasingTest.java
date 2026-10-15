package lib.kasuga.rendering.models.uml.dynamic.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Easing contract tests: endpoints, known values, overshoot, parameter effects, extrema. */
class EasingTest {

    private static final float EPS = 1e-4f;

    private float maxOver(Easing easing, float from, float to, int steps) {
        float max = Float.NEGATIVE_INFINITY;
        for (int i = 0; i <= steps; i++) {
            float t = from + (to - from) * i / steps;
            max = Math.max(max, easing.apply(t));
        }
        return max;
    }

    private int localExtremaCount(Easing easing, int steps) {
        int count = 0;
        float prev = easing.apply(0f);
        Boolean rising = null;
        for (int i = 1; i <= steps; i++) {
            float cur = easing.apply(i / (float) steps);
            float diff = cur - prev;
            if (diff == 0f) {
                continue;
            }
            boolean up = diff > 0f;
            if (rising != null && up != rising) {
                count++;
            }
            rising = up;
            prev = cur;
        }
        return count;
    }

    //region endpoints and known values

    @Test
    void fixedEasingsHitEndpoints() {
        Easing[] easings = {
                Easing.linear(), Easing.easeInQuad(), Easing.easeOutQuad(), Easing.easeInOutQuad(),
                Easing.easeInCubic(), Easing.easeOutCubic(), Easing.easeInOutCubic(),
                Easing.easeInSine(), Easing.easeOutSine(), Easing.easeInOutSine()
        };
        for (Easing easing : easings) {
            assertEquals(0f, easing.apply(0f), EPS, () -> "apply(0)=" + easing.getClass().getSimpleName());
            assertEquals(1f, easing.apply(1f), EPS);
        }
    }

    @Test
    void knownMidpointValues() {
        assertEquals(0.25f, Easing.linear().apply(0.25f), EPS);
        assertEquals(0.25f, Easing.easeInQuad().apply(0.5f), EPS);
        assertEquals(0.75f, Easing.easeOutQuad().apply(0.5f), EPS);
        assertEquals(0.125f, Easing.easeInOutQuad().apply(0.25f), EPS);
        assertEquals(0.125f, Easing.easeInCubic().apply(0.5f), EPS);
        assertEquals(0.875f, Easing.easeOutCubic().apply(0.5f), EPS);
        assertEquals(0.0625f, Easing.easeInOutCubic().apply(0.25f), EPS);
        assertEquals(0.292893f, Easing.easeInSine().apply(0.5f), EPS);
        assertEquals(0.707107f, Easing.easeOutSine().apply(0.5f), EPS);
        assertEquals(0.5f, Easing.easeInOutSine().apply(0.5f), EPS);
    }

    //endregion

    //region overshoot easings: settle at endpoints, may exceed [0,1] mid-way

    @Test
    void overshootEasingsHitEndpoints() {
        assertEquals(0f, Easing.back().apply(0f), EPS);
        assertEquals(1f, Easing.back().apply(1f), EPS);
        assertEquals(0f, Easing.elastic().apply(0f), EPS);
        assertEquals(1f, Easing.elastic().apply(1f), EPS);
        assertEquals(0f, Easing.bounce().apply(0f), EPS);
        assertEquals(1f, Easing.bounce().apply(1f), EPS);
    }

    @Test
    void backOvershoots() {
        assertTrue(maxOver(Easing.back(4f), 0f, 1f, 1000) > 1f, "back(4) should overshoot");
    }

    @Test
    void elasticOvershootsAndMidpoints() {
        assertTrue(maxOver(Easing.elastic(1f, 0.3f), 0f, 1f, 1000) > 1f, "elastic should overshoot");
        assertEquals(0.5f, Easing.elastic(1f, 0.3f).apply(0.5f), EPS, "elastic 中点=0.5");
    }

    @Test
    void bounceStaysInUnitRangeAndEndpoints() {
        Easing bounce = Easing.bounce(4f);
        assertEquals(0f, bounce.apply(0f), EPS);
        assertEquals(1f, bounce.apply(1f), EPS);
        for (int i = 0; i <= 1000; i++) {
            float v = bounce.apply(i / 1000f);
            assertTrue(v >= 0f && v <= 1f + EPS, "bounce value should be within [0,1]: " + v);
        }
    }

    @Test
    void bounceCreatesDips() {
        int dips = localExtremaCount(Easing.bounce(4f), 2000);
        assertTrue(dips >= 4, "4 bounces should produce several direction changes, got " + dips);
    }

    //endregion

    //region parameter effects

    @Test
    void largerElasticAmplitudeOvershootsMore() {
        float small = maxOver(Easing.elastic(1f, 0.3f), 0f, 1f, 1000);
        float large = maxOver(Easing.elastic(3f, 0.3f), 0f, 1f, 1000);
        assertTrue(large > small, "larger amplitude should overshoot more");
    }

    @Test
    void smallerElasticPeriodOscillatesMore() {
        int slow = localExtremaCount(Easing.elastic(1f, 0.6f), 2000);
        int fast = localExtremaCount(Easing.elastic(1f, 0.15f), 2000);
        assertTrue(fast > slow, "smaller period should oscillate denser (" + fast + " vs " + slow + ")");
    }

    @Test
    void moreBouncesOscillatesMore() {
        int few = localExtremaCount(Easing.bounce(2f), 1000);
        int many = localExtremaCount(Easing.bounce(6f), 1000);
        assertTrue(many > few, "more bounces should oscillate denser (" + many + " vs " + few + ")");
    }

    @Test
    void backOvershootParamChangesShape() {
        float low = maxOver(Easing.back(0.5f), 0f, 1f, 1000);
        float high = maxOver(Easing.back(5f), 0f, 1f, 1000);
        assertTrue(high > low, "larger overshoot should travel farther past the endpoint");
    }

    //endregion
}
