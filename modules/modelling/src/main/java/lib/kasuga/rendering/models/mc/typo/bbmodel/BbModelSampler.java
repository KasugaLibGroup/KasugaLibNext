package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link AnimationSampler} for {@link BbModelAnimation}: Blockbench native channel interpolation into a
 * bone {@link Pose}. Ported from the 1.0 Forge animation system ({@code block_bench_model/anim/}) —
 * each segment interpolates {@code pre.post → next.pre}, the segment's interpolation is the
 * lowest-priority of its two endpoints (catmullrom &lt; bezier &lt; linear &lt; step), and near a
 * keyframe the exact keyframe value wins (the "step on key frame" boundary).
 *
 * <p>Output shape: one {@link Pose} bone entry per animated bone, {@code REPLACE}, transform composed as
 * {@code T(position/16) · R(rotation°) · S(scale)} — the same local-transform convention as
 * {@code ClipSampler}. Blockbench position is in texture pixels (Blockbench {@code 1/16} block units), so
 * it is scaled like the {@code VmdSampler} translation scale. Bones without any keyframed channel are not
 * emitted (a bone with only e.g. a rotation track resolves the other channels to their identity defaults).
 */
public final class BbModelSampler implements AnimationSampler<BbModelAnimation> {

    public static final BbModelSampler INSTANCE = new BbModelSampler();

    /** Blockbench texture pixels per block unit (positions divide by 16). */
    private static final float PIXELS_PER_BLOCK = 16f;

    /** Catmull-Rom spline tension (tau); matches the 1.0 reference and Minecraft's {@code Mth.catmullrom}. */
    private static final float CRS_TAU = 0.5f;

    private BbModelSampler() {
    }

    @Override
    public float duration(BbModelAnimation data) {
        return data.length();
    }

    @Override
    public Pose sample(BbModelAnimation data, float time) {
        Pose.Builder builder = new Pose.Builder();
        for (BbModelAnimation.BoneAnim bone : data.bones()) {
            if (!hasAnyChannel(bone)) {
                continue;
            }
            Vector3f position = sampleChannel(bone, BbModelAnimation.Channel.POSITION, time, new Vector3f());
            Vector3f rotation = sampleChannel(bone, BbModelAnimation.Channel.ROTATION, time, new Vector3f());
            Vector3f scale = sampleChannel(bone, BbModelAnimation.Channel.SCALE, time, new Vector3f(1f));
            Transform transform = new Transform();
            transform.translate(position.x() / PIXELS_PER_BLOCK, position.y() / PIXELS_PER_BLOCK, position.z() / PIXELS_PER_BLOCK);
            // Blockbench keyframes use the same Z→Y→X euler composition as the model's own rotations.
            transform.mul(QuaternionHelper.fromZYXDegrees(rotation));
            transform.scale(scale.x(), scale.y(), scale.z());
            builder.bone(bone.bone(), transform, ApplyMode.REPLACE);
        }
        return builder.build();
    }

    private static boolean hasAnyChannel(BbModelAnimation.BoneAnim bone) {
        return !bone.channels().isEmpty();
    }

    private static Vector3f sampleChannel(BbModelAnimation.BoneAnim bone, BbModelAnimation.Channel channel,
                                          float time, Vector3f fallback) {
        List<BbModelAnimation.Keyframe> keyframes = bone.channels().get(channel);
        if (keyframes == null || keyframes.isEmpty()) {
            return fallback;
        }
        return sampleTrack(keyframes, time);
    }

    private static Vector3f sampleTrack(List<BbModelAnimation.Keyframe> keyframes, float time) {
        BbModelAnimation.Keyframe first = keyframes.get(0);
        if (time <= first.time()) {
            return first.pre().value();
        }
        BbModelAnimation.Keyframe last = keyframes.get(keyframes.size() - 1);
        if (time >= last.time()) {
            return last.post().value();
        }
        for (int i = 0; i < keyframes.size() - 1; i++) {
            BbModelAnimation.Keyframe pre = keyframes.get(i);
            BbModelAnimation.Keyframe next = keyframes.get(i + 1);
            if (time >= pre.time() && time < next.time()) {
                return interpolate(keyframes, i, pre, next, time);
            }
        }
        return last.post().value();
    }

    private static Vector3f interpolate(List<BbModelAnimation.Keyframe> keyframes, int index,
                                        BbModelAnimation.Keyframe pre, BbModelAnimation.Keyframe next, float time) {
        float span = next.time() - pre.time();
        float u = span <= 0f ? 1f : (time - pre.time()) / span;
        // "step on key frame": within a whisker of a keyframe, return that keyframe's own value.
        if (u < 0.001f) {
            return pre.pre().value();
        }
        if (u > 0.999f) {
            return next.post().value();
        }
        switch (segment(pre, next)) {
            case STEP -> {
                return pre.post().value();
            }
            case CATMULLROM -> {
                return catmullRom(keyframes, index, pre, next, u);
            }
            case BEZIER -> {
                return bezier(pre, next, u);
            }
            default -> {
                return lerp(pre.post().value(), next.pre().value(), u);
            }
        }
    }

    /** The segment's interpolation = the lowest-priority of its two endpoints (ported 1.0 semantics). */
    private static BbModelAnimation.Interpolation segment(BbModelAnimation.Keyframe pre, BbModelAnimation.Keyframe next) {
        return pre.interpolation().priority() <= next.interpolation().priority() ? pre.interpolation() : next.interpolation();
    }

    /**
     * Catmull-Rom between {@code pre.post} and {@code next.pre} (tau 0.5). Interior segments use the
     * previous keyframe's {@code post} and the next-next keyframe's {@code pre} as spline neighbours;
     * leading/trailing segments extrapolate a virtual neighbour. Falls back to linear when there is no
     * full neighbourhood (2-keyframe track, or both endpoints carry split pre/post points).
     */
    private static Vector3f catmullRom(List<BbModelAnimation.Keyframe> keyframes, int index,
                                       BbModelAnimation.Keyframe pre, BbModelAnimation.Keyframe next, float u) {
        int size = keyframes.size();
        boolean preUninterrupted = isUninterrupted(pre);
        boolean nextUninterrupted = isUninterrupted(next);
        if (size == 2 || (!preUninterrupted && !nextUninterrupted)) {
            return lerp(pre.post().value(), next.pre().value(), u);
        }
        boolean hasPrevious = index > 0;
        boolean hasNextNext = index + 2 <= size - 1;
        if (index == 0 || !preUninterrupted) {
            if (!hasNextNext) {
                return lerp(pre.post().value(), next.pre().value(), u);
            }
            BbModelAnimation.Keyframe nextNext = keyframes.get(index + 2);
            return applyCRS(last3PointsToCRS(pre.post().value(), next.pre().value(), nextNext.pre().value()), u);
        }
        if (index + 1 == size - 1 || !nextUninterrupted) {
            if (!hasPrevious) {
                return lerp(pre.post().value(), next.pre().value(), u);
            }
            BbModelAnimation.Keyframe last = keyframes.get(index - 1);
            return applyCRS(first3PointsToCRS(last.post().value(), pre.pre().value(), next.pre().value()), u);
        }
        BbModelAnimation.Keyframe last = keyframes.get(index - 1);
        BbModelAnimation.Keyframe nextNext = keyframes.get(index + 2);
        return applyCRS(genDefaultCRS(last.post().value(), pre.pre().value(), next.pre().value(), nextNext.pre().value()), u);
    }

    private static boolean isUninterrupted(BbModelAnimation.Keyframe keyframe) {
        return keyframe.pre().value().equals(keyframe.post().value());
    }

    /**
     * Bezier between {@code pre.post} and {@code next.pre}. The optional handles are Blockbench
     * per-axis time/value offsets anchored to {@code pre.pre} (left) and {@code next.post} (right);
     * the per-axis control polyline is evaluated with de Casteljau and its value coordinate returned.
     */
    private static Vector3f bezier(BbModelAnimation.Keyframe pre, BbModelAnimation.Keyframe next, float u) {
        return new Vector3f(
                bezierAxis(pre, next, u, 0),
                bezierAxis(pre, next, u, 1),
                bezierAxis(pre, next, u, 2));
    }

    private static float bezierAxis(BbModelAnimation.Keyframe pre, BbModelAnimation.Keyframe next, float u, int axis) {
        float t0 = pre.time();
        float v0 = pre.post().value().get(axis);
        float t3 = next.time();
        float v3 = next.pre().value().get(axis);
        List<float[]> points = new ArrayList<>(4);
        points.add(new float[]{t0, v0});
        if (pre.bezierRight() != null) {
            points.add(new float[]{t0 + pre.bezierRight().time().get(axis),
                    pre.pre().value().get(axis) + pre.bezierRight().value().get(axis)});
        }
        if (next.bezierLeft() != null) {
            points.add(new float[]{t3 + next.bezierLeft().time().get(axis),
                    next.post().value().get(axis) + next.bezierLeft().value().get(axis)});
        }
        points.add(new float[]{t3, v3});
        return deCasteljau(points, u)[1];
    }

    private static float[] deCasteljau(List<float[]> points, float u) {
        int n = points.size();
        float[][] work = new float[n][2];
        for (int i = 0; i < n; i++) {
            work[i] = new float[]{points.get(i)[0], points.get(i)[1]};
        }
        for (int level = 1; level < n; level++) {
            for (int i = 0; i < n - level; i++) {
                work[i][0] = lerp(work[i][0], work[i + 1][0], u);
                work[i][1] = lerp(work[i][1], work[i + 1][1], u);
            }
        }
        return work[0];
    }

    //region catmull-rom spline (ported 1.0 CatmullRomUtils, tau 0.5)

    private static Vector3f[] last3PointsToCRS(Vector3f p1, Vector3f p2, Vector3f p3) {
        Vector3f p0 = new Vector3f(p1).mul(2f).sub(p2);
        return genCRS(p0, p1, p2, p3);
    }

    private static Vector3f[] first3PointsToCRS(Vector3f p0, Vector3f p1, Vector3f p2) {
        Vector3f p3 = new Vector3f(p2).mul(2f).sub(p1);
        return genCRS(p0, p1, p2, p3);
    }

    private static Vector3f[] genDefaultCRS(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3) {
        return genCRS(p0, p1, p2, p3);
    }

    private static Vector3f[] genCRS(Vector3f p0, Vector3f p1, Vector3f p2, Vector3f p3) {
        Vector3f c0 = new Vector3f(p1);
        Vector3f c1 = new Vector3f(p0).mul(-CRS_TAU).add(new Vector3f(p2).mul(CRS_TAU));
        Vector3f c2 = new Vector3f(p0).mul(2f * CRS_TAU)
                .add(new Vector3f(p1).mul(CRS_TAU - 3f))
                .add(new Vector3f(p2).mul(3f - 2f * CRS_TAU))
                .add(new Vector3f(p3).mul(-CRS_TAU));
        Vector3f c3 = new Vector3f(p0).mul(-CRS_TAU)
                .add(new Vector3f(p1).mul(2f - CRS_TAU))
                .add(new Vector3f(p2).mul(CRS_TAU - 2f))
                .add(new Vector3f(p3).mul(CRS_TAU));
        return new Vector3f[]{c0, c1, c2, c3};
    }

    private static Vector3f applyCRS(Vector3f[] points, float u) {
        return new Vector3f(points[0])
                .fma(u, points[1])
                .fma(u * u, points[2])
                .fma(u * u * u, points[3]);
    }

    //endregion

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static Vector3f lerp(Vector3f a, Vector3f b, float t) {
        return new Vector3f(a).fma(t, new Vector3f(b).sub(a));
    }
}