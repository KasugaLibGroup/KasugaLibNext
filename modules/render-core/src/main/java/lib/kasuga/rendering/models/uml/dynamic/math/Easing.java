package lib.kasuga.rendering.models.uml.dynamic.math;

import java.util.Map;

/**
 * Easing function: maps normalized time {@code t ∈ [0,1]} to an eased progress value.
 *
 * <p>Business meaning: decides how an animated value unfolds over time between two keyframes —
 * constant speed, ease-in/out, or overshooting bounce. The sampler calls
 * {@code value = lerp(a, b, easing.apply(t))} each frame; the easing shapes the time axis, and
 * {@code lerp} only performs the final linear interpolation.
 *
 * <p>Contract: input {@code t} is expected to lie in {@code [0,1]} (caller's responsibility);
 * the output stays within {@code [0,1]} for linear/quad/cubic/sine, while {@code back},
 * {@code elastic} and {@code bounce} may overshoot (outside {@code [0,1]}) before returning to
 * the endpoints.
 *
 * <p>Design: a uniform {@link #apply(float)} contract plus parameterized static factories.
 * No-arg easings return shared singletons (zero allocation); parameterized easings
 * (overshoot / amplitude / period / bounce count) bind their parameters at construction, so the
 * sampler never has to know per-function differences.
 */
@FunctionalInterface
public interface Easing {

    float apply(float t);

    //region standard easings (no parameters, shared singletons)

    static Easing linear() {
        return LINEAR;
    }

    static Easing easeInQuad() {
        return EASE_IN_QUAD;
    }

    static Easing easeOutQuad() {
        return EASE_OUT_QUAD;
    }

    static Easing easeInOutQuad() {
        return EASE_IN_OUT_QUAD;
    }

    static Easing easeInCubic() {
        return EASE_IN_CUBIC;
    }

    static Easing easeOutCubic() {
        return EASE_OUT_CUBIC;
    }

    static Easing easeInOutCubic() {
        return EASE_IN_OUT_CUBIC;
    }

    static Easing easeInSine() {
        return EASE_IN_SINE;
    }

    static Easing easeOutSine() {
        return EASE_OUT_SINE;
    }

    static Easing easeInOutSine() {
        return EASE_IN_OUT_SINE;
    }

    //endregion

    //region parameterized easings

    /** easeInOutBack with the default overshoot of 1.70158. */
    static Easing back() {
        return easeInOutBack(DEFAULT_BACK_OVERSHOOT);
    }

    /** easeInOutBack; {@code overshoot} controls how far past the endpoint the curve travels. */
    static Easing back(float overshoot) {
        return easeInOutBack(overshoot);
    }

    static Easing easeInOutBack(float overshoot) {
        return t -> backFn(t, overshoot);
    }

    /** easeInOutElastic with the default amplitude of 1 and period of 0.3. */
    static Easing elastic() {
        return easeInOutElastic(DEFAULT_ELASTIC_AMPLITUDE, DEFAULT_ELASTIC_PERIOD);
    }

    /**
     * easeInOutElastic; {@code amplitude} controls the oscillation amplitude (values below 1 are
     * treated as 1), and {@code period} is the oscillation period — smaller values oscillate denser.
     */
    static Easing elastic(float amplitude, float period) {
        return easeInOutElastic(amplitude, period);
    }

    static Easing easeInOutElastic(float amplitude, float period) {
        return t -> elasticFn(t, amplitude, period);
    }

    /** easeOutBounce with the default of 4 bounces. */
    static Easing bounce() {
        return easeOutBounce(DEFAULT_BOUNCE_COUNT);
    }

    /** easeOutBounce; {@code bounces} is the bounce count (&ge;1) — more bounces oscillate denser. */
    static Easing bounce(float bounces) {
        return easeOutBounce(bounces);
    }

    static Easing easeOutBounce(float bounces) {
        return t -> bounceFn(t, Math.max(1, Math.round(bounces)));
    }

    //endregion

    //region constants and implementations

    float DEFAULT_BACK_OVERSHOOT = 1.70158f;
    float DEFAULT_ELASTIC_AMPLITUDE = 1f;
    float DEFAULT_ELASTIC_PERIOD = 0.3f;
    int DEFAULT_BOUNCE_COUNT = 4;

    Easing LINEAR = Easing::linearFn;
    Easing EASE_IN_QUAD = Easing::easeInQuadFn;
    Easing EASE_OUT_QUAD = Easing::easeOutQuadFn;
    Easing EASE_IN_OUT_QUAD = Easing::easeInOutQuadFn;
    Easing EASE_IN_CUBIC = Easing::easeInCubicFn;
    Easing EASE_OUT_CUBIC = Easing::easeOutCubicFn;
    Easing EASE_IN_OUT_CUBIC = Easing::easeInOutCubicFn;
    Easing EASE_IN_SINE = Easing::easeInSineFn;
    Easing EASE_OUT_SINE = Easing::easeOutSineFn;
    Easing EASE_IN_OUT_SINE = Easing::easeInOutSineFn;

    private static float linearFn(float t) {
        return t;
    }

    private static float easeInQuadFn(float t) {
        return t * t;
    }

    private static float easeOutQuadFn(float t) {
        return t * (2f - t);
    }

    private static float easeInOutQuadFn(float t) {
        return t < 0.5f ? 2f * t * t : -1f + (4f - 2f * t) * t;
    }

    private static float easeInCubicFn(float t) {
        return t * t * t;
    }

    private static float easeOutCubicFn(float t) {
        return 1f - (1f - t) * (1f - t) * (1f - t);
    }

    private static float easeInOutCubicFn(float t) {
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;
    }

    private static float easeInSineFn(float t) {
        return 1f - (float) Math.cos(t * (Math.PI / 2f));
    }

    private static float easeOutSineFn(float t) {
        return (float) Math.sin(t * (Math.PI / 2f));
    }

    private static float easeInOutSineFn(float t) {
        return -(float) (Math.cos(Math.PI * t) - 1f) / 2f;
    }

    private static float backFn(float t, float overshoot) {
        float c2 = overshoot * 1.525f;
        if (t < 0.5f) {
            return (float) (Math.pow(2f * t, 2f) * ((c2 + 1f) * 2f * t - c2) / 2f);
        }
        float x = 2f * t - 2f;
        return (float) ((Math.pow(x, 2f) * ((c2 + 1f) * x + c2) + 2f) / 2f);
    }

    private static float elasticFn(float t, float amplitude, float period) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        float a = amplitude < 1f ? 1f : amplitude;
        float p = period <= 0f ? DEFAULT_ELASTIC_PERIOD : period;
        float s = a < 1f ? p / 4f : (float) (p * Math.asin(1f / a) / (2f * Math.PI));
        float x = 2f * t - 1f;
        if (t < 0.5f) {
            return -0.5f * (float) (a * Math.pow(2f, 10f * x) * Math.sin((x - s) * (2f * Math.PI / p)));
        }
        return 0.5f * (float) (a * Math.pow(2f, -10f * x) * Math.sin((x - s) * (2f * Math.PI / p))) + 1f;
    }

    private static float bounceFn(float t, int bounces) {
        if (t <= 0f) {
            return 0f;
        }
        if (t >= 1f) {
            return 1f;
        }
        if (bounces <= 1) {
            return t * t;
        }
        float seg = t * bounces;
        int k = (int) Math.min(Math.floor(seg), bounces - 1f);
        float u = seg - k;
        if (k == 0) {
            return u * u;
        }
        // Bounce k: drops from 1 to the dip, then rises back to 1 (parabola vertex at segment middle).
        float dip = 1f - (float) Math.pow(0.25, k);
        float twoU1 = 2f * u - 1f;
        return dip + (1f - dip) * twoU1 * twoU1;
    }

    //endregion

    //region named registry (AnimationClip codec support)

    /** Canonical snake_case names for the no-arg easings, used by {@code AnimationClip.EASING_CODEC}. */
    Map<String, Easing> NAMED = Map.ofEntries(
            Map.entry("linear", linear()),
            Map.entry("ease_in_quad", easeInQuad()),
            Map.entry("ease_out_quad", easeOutQuad()),
            Map.entry("ease_in_out_quad", easeInOutQuad()),
            Map.entry("ease_in_cubic", easeInCubic()),
            Map.entry("ease_out_cubic", easeOutCubic()),
            Map.entry("ease_in_out_cubic", easeInOutCubic()),
            Map.entry("ease_in_sine", easeInSine()),
            Map.entry("ease_out_sine", easeOutSine()),
            Map.entry("ease_in_out_sine", easeInOutSine()),
            Map.entry("back", back()),
            Map.entry("elastic", elastic()),
            Map.entry("bounce", bounce())
    );

    /** Looks up an easing by its canonical name; {@code null} for unknown names. */
    static Easing byName(String name) {
        return NAMED.get(name);
    }

    /** Canonical name of a registered easing; parameterized easings fall back to {@code "linear"}. */
    static String nameOf(Easing easing) {
        for (Map.Entry<String, Easing> entry : NAMED.entrySet()) {
            if (entry.getValue() == easing) {
                return entry.getKey();
            }
        }
        return "linear";
    }

    //endregion
}
