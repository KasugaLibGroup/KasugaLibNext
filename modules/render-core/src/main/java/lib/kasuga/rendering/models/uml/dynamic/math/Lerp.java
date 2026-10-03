package lib.kasuga.rendering.models.uml.dynamic.math;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Interpolation utilities (part of the Math Toolbox). Answers "how to interpolate between two
 * values"; combined with {@link Easing} it also answers "how fast / how naturally to get there".
 *
 * <p>{@code lerpInto(Transform, Transform, float, Transform)} decomposes translation / rotation /
 * scale, interpolates (lerp/slerp) each, and rebuilds. Transforms built as {@code T·R·S} (or pure
 * translation / pure scale) round-trip exactly; when rotation and non-uniform scale coexist, the
 * column-length / normalized-rotation decomposition is approximate (consistent with the existing
 * FSM {@code TransformLerp}, sufficient for visual blending).
 */
public final class Lerp {

    private Lerp() {
    }

    public static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    /** Clamps {@code t} to [0,1] before interpolating. */
    public static float lerpClamped(float a, float b, float t) {
        return lerp(a, b, clamp01(t));
    }

    /** Shapes the time axis with an easing, then interpolates: {@code lerp(a, b, easing.apply(t))}. */
    public static float eased(float a, float b, float t, Easing easing) {
        return lerp(a, b, easing.apply(t));
    }

    public static Vector3f lerp(Vector3f a, Vector3f b, float t, Vector3f dest) {
        return dest.set(a).lerp(b, t);
    }

    public static Quaternionf slerp(Quaternionf a, Quaternionf b, float t, Quaternionf dest) {
        return a.slerp(b, t, dest);
    }

    /** Convenience entry: allocates the dest internally. */
    public static Transform lerp(Transform a, Transform b, float t) {
        return lerpInto(a, b, t, new Transform());
    }

    /**
     * Interpolates translation via lerp, rotation via slerp and scale via lerp, writing into
     * {@code dest} (including normal-matrix derivation). Scale is decomposed by
     * {@link #getScale(Transform)} (column lengths); assumes no shear.
     */
    public static Transform lerpInto(Transform a, Transform b, float t, Transform dest) {
        Vector3f pos = a.getPosition().lerp(b.getPosition(), t, new Vector3f());
        Quaternionf rot = a.getRotation().slerp(b.getRotation(), t, new Quaternionf());
        Vector3f scale = getScale(a).lerp(getScale(b), t, new Vector3f());
        Matrix4f matrix = new Matrix4f().translationRotateScale(pos, rot, scale);
        dest.set(matrix);
        return dest;
    }

    /** Decomposes the scale components from the matrix column lengths (assumes {@code T·R·S}, no shear). */
    public static Vector3f getScale(Transform transform) {
        Matrix4f m = transform.transform();
        float sx = new Vector3f(m.m00(), m.m10(), m.m20()).length();
        float sy = new Vector3f(m.m01(), m.m11(), m.m21()).length();
        float sz = new Vector3f(m.m02(), m.m12(), m.m22()).length();
        return new Vector3f(sx, sy, sz);
    }

    private static float clamp01(float t) {
        return t < 0f ? 0f : Math.min(t, 1f);
    }
}
