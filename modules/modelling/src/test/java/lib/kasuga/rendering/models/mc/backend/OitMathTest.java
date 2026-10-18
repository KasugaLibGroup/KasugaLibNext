package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OitMathTest {

    @Test
    void aggregateAndResolveAreIndependentOfSubmissionOrder() {
        List<Sample> samples = List.of(
                new Sample(0.9f, 0.2f, 1.7f),
                new Sample(0.1f, 0.6f, 0.8f),
                new Sample(0.4f, 0.35f, 2.2f)
        );

        Result forward = aggregate(samples);
        Result reverse = aggregate(samples.reversed());

        assertEquals(forward.accumulatedColor(), reverse.accumulatedColor(), 1e-6f);
        assertEquals(forward.accumulatedWeight(), reverse.accumulatedWeight(), 1e-6f);
        assertEquals(forward.revealage(), reverse.revealage(), 1e-6f);
        assertEquals(forward.resolvedColor(), reverse.resolvedColor(), 1e-6f);
        assertEquals(forward.resolvedAlpha(), reverse.resolvedAlpha(), 1e-6f);
    }

    @Test
    void emptyPixelIsTheResolveIdentity() {
        assertEquals(0f, OitMath.resolveColor(0f, 0f));
        assertEquals(0f, OitMath.resolveAlpha(1f));
        assertEquals(0.37f, OitMath.sourceOver(0.37f, 0f, 0f), 1e-6f);
    }

    @Test
    void revealageHandlesExtremeAlphaWithoutInvalidValues() {
        assertEquals(1f, OitMath.multiplyRevealage(1f, 0f));
        assertEquals(0f, OitMath.multiplyRevealage(1f, 1f));
        assertEquals(0f, OitMath.resolveAlpha(1.5f));
        assertEquals(1f, OitMath.resolveAlpha(-0.25f));
        assertTrue(Float.isFinite(OitMath.resolveColor(1f, 0f)));
    }

    @Test
    void depthWeightIsPositiveAndBounded() {
        assertTrue(OitMath.depthWeight(0f) <= 8f);
        assertTrue(OitMath.depthWeight(1f) >= 0.01f);
        assertTrue(OitMath.depthWeight(0.5f) > 0f);
    }

    private static Result aggregate(List<Sample> samples) {
        float color = 0f;
        float weight = 0f;
        float revealage = 1f;
        for (Sample sample : samples) {
            color = OitMath.accumulateColor(color, sample.color(), sample.alpha(), sample.weight());
            weight = OitMath.accumulateWeight(weight, sample.alpha(), sample.weight());
            revealage = OitMath.multiplyRevealage(revealage, sample.alpha());
        }
        return new Result(color, weight, revealage,
                OitMath.resolveColor(color, weight), OitMath.resolveAlpha(revealage));
    }

    private record Sample(float color, float alpha, float weight) {}

    private record Result(float accumulatedColor, float accumulatedWeight,
                          float revealage, float resolvedColor, float resolvedAlpha) {}
}
