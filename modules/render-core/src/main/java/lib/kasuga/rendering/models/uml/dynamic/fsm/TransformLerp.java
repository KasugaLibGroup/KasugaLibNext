package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Interpolates two {@link Transform}s by TRS decomposition (translation + scale linear, rotation via
 * quaternion slerp). {@link Transform} has no native slerp, so this is the one utility providing it.
 *
 * <p>Two entry points: {@link #lerpInto} reuses a caller-supplied {@link Scratch} (zero per-call allocation —
 * the hot path, called per bone per tick during cross-fade), while {@link #lerp} allocates a scratch per call
 * (convenience for one-off callers).
 */
public final class TransformLerp {

    private TransformLerp() {}

    /**
     * Reusable scratch buffers for {@link #lerpInto}. Allocate once per caller (e.g. one per {@link Layer}) and
     * reuse across bones and ticks to avoid the ~12 JOML allocations {@link #lerp} does per call.
     */
    public static final class Scratch {
        public final Vector3f translationFrom = new Vector3f();
        public final Vector3f translationTo = new Vector3f();
        public final Vector3f translation = new Vector3f();
        public final Quaternionf rotationFrom = new Quaternionf();
        public final Quaternionf rotationTo = new Quaternionf();
        public final Quaternionf rotation = new Quaternionf();
        public final Vector3f scaleFrom = new Vector3f();
        public final Vector3f scaleTo = new Vector3f();
        public final Vector3f scale = new Vector3f();
    }

    /**
     * Interpolate {@code from}→{@code to} into {@code dest}, reusing {@code scratch}. Hot path: allocate the
     * {@link Scratch} once on the caller (e.g. a {@link Layer} field) and reuse across bones/ticks.
     */
    public static void lerpInto(Transform from, Transform to, float alpha, Transform dest, Scratch scratch) {
        if (alpha <= 0f) {
            dest.set(from);
            return;
        }
        if (alpha >= 1f) {
            dest.set(to);
            return;
        }
        Matrix4f mf = from.transform();
        Matrix4f mt = to.transform();

        mf.getTranslation(scratch.translationFrom);
        mt.getTranslation(scratch.translationTo);
        scratch.translationFrom.lerp(scratch.translationTo, alpha, scratch.translation);

        mf.getNormalizedRotation(scratch.rotationFrom);
        mt.getNormalizedRotation(scratch.rotationTo);
        scratch.rotationFrom.slerp(scratch.rotationTo, alpha, scratch.rotation);

        scaleOf(mf, scratch.scaleFrom);
        scaleOf(mt, scratch.scaleTo);
        scratch.scaleFrom.lerp(scratch.scaleTo, alpha, scratch.scale);

        dest.setIdentity();
        dest.translate(scratch.translation);
        dest.mul(scratch.rotation);
        dest.scale(scratch.scale.x(), scratch.scale.y(), scratch.scale.z());
    }

    /** Convenience: allocates a {@link Scratch} per call — avoid on the per-bone hot path (use {@link #lerpInto}). */
    public static void lerp(Transform from, Transform to, float alpha, Transform dest) {
        lerpInto(from, to, alpha, dest, new Scratch());
    }

    private static void scaleOf(Matrix4f m, Vector3f dest) {
        dest.set(len(m.m00(), m.m10(), m.m20()), len(m.m01(), m.m11(), m.m21()), len(m.m02(), m.m12(), m.m22()));
    }

    private static float len(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }
}
