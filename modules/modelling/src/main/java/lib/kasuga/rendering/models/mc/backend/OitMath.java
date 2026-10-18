package lib.kasuga.rendering.models.mc.backend;

/** Pure reference equations shared by OIT tests and renderer documentation. */
final class OitMath {

    static final float EPSILON = 1e-5f;

    private OitMath() {}

    /** Mirrors the native shader's split, after texture/vertex/modulator alpha. */
    static boolean writesOpaqueCoverage(float effectiveAlpha) {
        return effectiveAlpha >= 1f;
    }

    static boolean contributesToOit(float effectiveAlpha) {
        return effectiveAlpha > 1f / 255f && effectiveAlpha < 1f;
    }

    static float accumulateColor(float accumulated, float color, float alpha, float weight) {
        return accumulated + color * alpha * weight;
    }

    static float accumulateWeight(float accumulated, float alpha, float weight) {
        return accumulated + alpha * weight;
    }

    static float multiplyRevealage(float revealage, float alpha) {
        return revealage * (1f - clamp01(alpha));
    }

    static float resolveColor(float accumulatedColor, float accumulatedWeight) {
        return accumulatedWeight > EPSILON ? accumulatedColor / accumulatedWeight : 0f;
    }

    static float resolveAlpha(float revealage) {
        return 1f - clamp01(revealage);
    }

    static float sourceOver(float sceneColor, float oitColor, float oitAlpha) {
        float alpha = clamp01(oitAlpha);
        return oitColor * alpha + sceneColor * (1f - alpha);
    }

    static float depthWeight(float depth) {
        float normalizedDepth = 1f - clamp01(depth);
        return Math.clamp((float) (Math.pow(Math.max(0f, normalizedDepth), 3.0) * 8.0 + 0.01),
                0.01f, 8f);
    }

    private static float clamp01(float value) {
        return Math.clamp(value, 0f, 1f);
    }
}
