package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

/** Cubic MMD interpolation curve from (0,0) to (1,1). */
public record VmdBezier(float x1, float y1, float x2, float y2) {
    public VmdBezier {
        x1 = Math.clamp(x1, 0f, 1f);
        y1 = Math.clamp(y1, 0f, 1f);
        x2 = Math.clamp(x2, 0f, 1f);
        y2 = Math.clamp(y2, 0f, 1f);
    }

    public static VmdBezier bytes(byte x1, byte y1, byte x2, byte y2) {
        return new VmdBezier(unsigned127(x1), unsigned127(y1), unsigned127(x2), unsigned127(y2));
    }

    private static float unsigned127(byte value) {
        return Math.clamp(Byte.toUnsignedInt(value) / 127f, 0f, 1f);
    }

    /** Maps linear frame progress to MMD's cubic Bezier value. */
    public float evaluate(float progress) {
        float x = Math.clamp(progress, 0f, 1f);
        if (x == 0f || x == 1f) return x;
        float low = 0f;
        float high = 1f;
        float t = x;
        for (int i = 0; i < 15; i++) {
            float estimate = cubic(t, x1, x2);
            if (Math.abs(estimate - x) < 1e-5f) break;
            if (estimate < x) low = t;
            else high = t;
            t = (low + high) * 0.5f;
        }
        return cubic(t, y1, y2);
    }

    private static float cubic(float t, float p1, float p2) {
        float inv = 1f - t;
        return 3f * inv * inv * t * p1 + 3f * inv * t * t * p2 + t * t * t;
    }
}
