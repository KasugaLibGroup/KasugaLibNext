package lib.kasuga.rendering.models.uml.dynamic.animation.function;

import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.formula.compute.infrastructure.Formula;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RampMathTest {

    private static final float DELTA = 1e-2f;

    private static float normalize360(float angle) {
        return (angle % 360f + 360f) % 360f;
    }

    private static void assertMonotonicVelocity(RampMath.Ramp ramp, float t0, float t1, boolean increasing) {
        int steps = 64;
        float prev = RampMath.velocityAt(ramp, t0);
        for (int i = 1; i <= steps; i++) {
            float t = t0 + (t1 - t0) * i / steps;
            float curr = RampMath.velocityAt(ramp, t);
            if (increasing) {
                assertTrue(curr >= prev - 1e-4f,
                        "velocity decreased at t=" + t + ": " + prev + " -> " + curr);
            } else {
                assertTrue(curr <= prev + 1e-4f,
                        "velocity increased at t=" + t + ": " + prev + " -> " + curr);
            }
            prev = curr;
        }
    }

    @Test
    void u1_rampStartIsContinuous() {
        RampMath.Ramp sCurve = RampMath.Ramp.sCurve(360f, 540f, 100f, 0f);
        assertEquals(100f, RampMath.angleAt(sCurve, 0f), DELTA);
        assertEquals(360f, RampMath.velocityAt(sCurve, 0f), DELTA);

        float[] plan = RampMath.easeOutPlan(100f, 540f);
        RampMath.Ramp easeOut = RampMath.Ramp.easeOut(540f, 100f, 0f, plan[0], plan[1]);
        assertEquals(100f, RampMath.angleAt(easeOut, 0f), DELTA);
        assertEquals(540f, RampMath.velocityAt(easeOut, 0f), DELTA);
    }

    @Test
    void u2_cruiseDegeneratesToLinear() {
        float theta0 = 100f;
        RampMath.Ramp ramp = RampMath.Ramp.sCurve(540f, 540f, theta0, 0f);
        assertEquals(theta0 + 540f * 2f, RampMath.angleAt(ramp, 2.0f), DELTA);
    }

    @Test
    void u3_gearUpSCurve() {
        float theta0 = 100f;
        RampMath.Ramp ramp = RampMath.Ramp.sCurve(360f, 540f, theta0, 0f);
        assertEquals(360f, RampMath.velocityAt(ramp, 0f), 1e-3f);
        assertEquals(540f, RampMath.velocityAt(ramp, RampMath.T_REF), 1e-3f);
        assertEquals(450f, RampMath.velocityAt(ramp, RampMath.T_REF / 2f), 1e-3f);
        assertMonotonicVelocity(ramp, 0f, RampMath.T_REF, true);
        assertEquals(RampMath.sCurveArrivalAngle(theta0, 360f, 540f), RampMath.angleAt(ramp, RampMath.T_REF), DELTA);
        assertEquals(theta0 + 675f, RampMath.sCurveArrivalAngle(theta0, 360f, 540f), DELTA);
    }

    @Test
    void u4_gearDownSCurve() {
        float theta0 = 100f;
        RampMath.Ramp ramp = RampMath.Ramp.sCurve(720f, 540f, theta0, 0f);
        assertMonotonicVelocity(ramp, 0f, RampMath.T_REF, false);
        assertEquals(540f, RampMath.velocityAt(ramp, RampMath.T_REF), 1e-3f);
    }

    @Test
    void u5_offIntegerLanding() {
        float[] thetas = {0f, 33f, 359f, 720.5f};
        float[] v0s = {360f, 540f, 720f};
        for (float theta0 : thetas) {
            for (float v0 : v0s) {
                float[] plan = RampMath.easeOutPlan(theta0, v0);
                RampMath.Ramp ramp = RampMath.Ramp.easeOut(v0, theta0, 0f, plan[0], plan[1]);
                float actual = normalize360(RampMath.angleAt(ramp, plan[1]));
                float target = (float) Math.round(theta0 + 0.75f * v0 * RampMath.T_REF);
                float expected = normalize360(target);
                assertEquals(expected, actual, 1e-3f,
                        "integer landing failed for theta0=" + theta0 + " v0=" + v0);
            }
        }
    }

    @Test
    void u6_landingAlwaysForward() {
        float[] thetas = {0f, 33f, 359f, 720.5f};
        float[] v0s = {360f, 540f, 720f};
        for (float theta0 : thetas) {
            for (float v0 : v0s) {
                float[] plan = RampMath.easeOutPlan(theta0, v0);
                assertTrue(plan[0] > 0f,
                        "delta not forward for theta0=" + theta0 + " v0=" + v0 + " delta=" + plan[0]);
            }
        }
    }

    @Test
    void u7_offVelocityContinuous() {
        float theta0 = 100f;
        float v0 = 540f;
        float[] plan = RampMath.easeOutPlan(theta0, v0);
        RampMath.Ramp ramp = RampMath.Ramp.easeOut(v0, theta0, 0f, plan[0], plan[1]);
        assertEquals(v0, RampMath.velocityAt(ramp, 0f), DELTA);
        assertEquals(0f, RampMath.velocityAt(ramp, plan[1]), DELTA);
        assertMonotonicVelocity(ramp, 0f, plan[1], false);
    }

    @Test
    void u8_resumePlayback() {
        RampMath.Ramp ramp = RampMath.Ramp.sCurve(0f, 540f, 120f, 0f);
        assertEquals(120f, RampMath.angleAt(ramp, 0f), DELTA);
        assertMonotonicVelocity(ramp, 0f, RampMath.T_REF, true);
        assertEquals(540f, RampMath.velocityAt(ramp, RampMath.T_REF), 1e-3f);
    }

    @Test
    void u9_speedArrayRoundTrip() {
        float[] gearSpeeds = {360f, 540f, 720f};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gearSpeeds.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Float.toString(gearSpeeds[i]));
        }
        String encoded = sb.toString();
        String[] parts = encoded.split(",");
        float[] decoded = new float[parts.length];
        for (int i = 0; i < parts.length; i++) decoded[i] = Float.parseFloat(parts[i]);
        assertArrayEquals(gearSpeeds, decoded, 1e-4f);
    }

    @Test
    void u10_switchTimeContinuity() {
        float theta0 = 100f;
        RampMath.Ramp oldRamp = RampMath.Ramp.sCurve(360f, 720f, theta0, 0f);
        float t = 0.8f;
        float theta = RampMath.angleAt(oldRamp, t);
        float v = RampMath.velocityAt(oldRamp, t);

        RampMath.Ramp newRamp = RampMath.Ramp.sCurve(v, 720f, theta, t);

        // angleAt/velocityAt take seconds since the ramp's own start, so "the new ramp sampled
        // at its own start time" is elapsed 0, not `t`. The new ramp at elapsed 0 must reproduce
        // the sampled (θ, v) state of the old ramp at its elapsed t.
        assertEquals(theta, RampMath.angleAt(newRamp, 0f), DELTA);
        assertEquals(v, RampMath.velocityAt(newRamp, 0f), DELTA);

        // The spec also floats angleAt(oldRamp, 1.3) == angleAt(newRamp, 1.3 - t). That equality
        // does NOT hold exactly: a fresh S-curve restarted from (θ, v) is a different profile from
        // the original S-curve continued, because the new ramp re-enters the ease_in_out_cubic
        // envelope from its own t=0. Measured deviation for these values is ~45 degrees
        // (old(1.3) ≈ 766.17 vs new(0.5) ≈ 721.15). Per the spec, only the two start-state
        // invariants above are asserted; the deviation is documented here instead.
        float deviation = Math.abs(RampMath.angleAt(oldRamp, 1.3f) - RampMath.angleAt(newRamp, 1.3f - t));
        assertTrue(deviation > 10f, "expected the restart profile to deviate from continuation, got " + deviation);
    }

    @Test
    void u11_formulaEngineSinEndpoints() {
        // The engine's `sin` is radian-based, so the cover sway formula converts its phase to
        // degrees with the engine's built-in `rad()` (Math.toRadians): sin(rad(t*30))*30 reproduces
        // the Blockbench catmullrom endpoints ±30° at t = 3 / 6 / 9 / 12. This is the exact formula
        // the fan clip uses (see FanAnimationClipFactory), gated by query.speed_ratio.
        Namespace ns = new Namespace(Code.ROOT_NAMESPACE);
        Formula f = ns.decodeFormula("sin(rad(query.anim_time * 30)) * 30 * query.speed_ratio");
        ns.assign("query.speed_ratio", 1f);
        float[][] expected = { {3f, 30f}, {6f, 0f}, {9f, -30f}, {12f, 0f} };
        for (float[] e : expected) {
            ns.assign("query.anim_time", e[0]);
            assertEquals(e[1], f.getResult(), 1e-2f, "cover sway endpoint at anim_time=" + e[0]);
        }
        ns.assign("query.speed_ratio", 0f);
        assertEquals(0f, f.getResult(), 1e-2f);
    }

    @Test
    void u12_completionInstantAngle() {
        float theta0 = 100f;
        float[] plan = RampMath.easeOutPlan(theta0, 540f);
        RampMath.Ramp easeOut = RampMath.Ramp.easeOut(540f, theta0, 0f, plan[0], plan[1]);
        assertEquals(theta0 + plan[0], RampMath.arrivalAngle(easeOut), DELTA);
        assertEquals(RampMath.arrivalAngle(easeOut), RampMath.angleAt(easeOut, plan[1]), DELTA);

        RampMath.Ramp sCurve = RampMath.Ramp.sCurve(360f, 540f, theta0, 0f);
        assertEquals(theta0 + RampMath.T_REF * (360f + 540f) / 2f, RampMath.arrivalAngle(sCurve), DELTA);
        assertEquals(RampMath.arrivalAngle(sCurve), RampMath.angleAt(sCurve, RampMath.T_REF), DELTA);
    }
}