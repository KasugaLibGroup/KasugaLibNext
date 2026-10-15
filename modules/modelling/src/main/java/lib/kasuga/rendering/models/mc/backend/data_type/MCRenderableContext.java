package lib.kasuga.rendering.models.mc.backend.data_type;

import com.mojang.blaze3d.vertex.PoseStack;
import lib.kasuga.rendering.models.mc.backend.*;
import lib.kasuga.rendering.models.uml.backend.BackendContext;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.framework.render.RenderableFactory;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;

public class MCRenderableContext extends BackendContext<MCBridge, BackendInstance, MCBackendContext, MCBackend.BackendTransform> {

    private static final MCBackend.BackendTransform DEFAULT_TRANSFORM = new MCBackend.BackendTransform(
            new Vector3f(), null, null,
            false, false, true, true,
            1f, 1f, -1, -1
    );

    /** Degenerate form for identity root transforms: all components null, so the pose transform is a no-op (bit-for-bit equivalent to the legacy DEFAULT_TRANSFORM). */
    private static final MCBackend.BackendTransform IDENTITY_TRANSFORM = new MCBackend.BackendTransform(
            null, null, null,
            false, false, true, true,
            1f, 1f, -1, -1
    );

    /** Scale below this threshold is degenerate: rotation is not extractable (treated as no rotation). */
    private static final float MIN_SCALE = 1e-4f;

    public MCRenderableContext(MCBridge bridge, ModelInstance modelInstance) {
        super(bridge, modelInstance);
    }

    public MCRenderableContext(MCBridge bridge, ModelInstance modelInstance,
                              RenderableFactory<BackendInstance> factory) {
        super(bridge, modelInstance, factory);
    }

    /**
     * Extract the TRS of the skeleton root transform into a {@link MCBackend.BackendTransform} for
     * world-space lighting/culling in {@link MCBackend#render}.
     *
     * <p>The origin-local root is already baked into every bone by
     * {@code SkeletonInstance.updateTransform}. {@link MCBackend} applies the separate double
     * world origin camera-relatively, so this result carries {@code appliesTransform=false}
     * and supplies lighting/culling metadata without double-applying the root.
     */
    @Override
    public MCBackend.BackendTransform beforeRender(MCBackendContext context) {
        var skeleton = getModelInstance().getSkeletonInstance();
        return toBackendTransform(skeleton.getTransform(), skeleton.getWorldOrigin());
    }

    /** Push the full root matrix onto the pose stack; kept as a public utility (beforeRender uses TRS extraction instead, to avoid double application). */
    public void applyRootTransform(PoseStack pose, Transform transform) {
        PoseStack.Pose p = pose.last();
        p.pose().mul(transform.transform());
        p.normal().mul(transform.normal());
    }

    //region TRS extraction

    /**
     * TRS approximation of a skeleton root transform (pure function, for plain-JVM unit tests).
     *
     * <p>Approximate for shear / non-uniform negative scale. Rotation uses the XYZ convention of
     * {@code QuaternionHelper.fromXYZDegrees} and JOML column-major indexing (mXY = M[row=Y][col=X]):
     * x = atan2(−r12, r22), y = asin(r02), z = atan2(−r01, r00).
     *
     * <p>The origin-local root is already baked into skinning matrices, so the result carries
     * {@code appliesTransform=false}; identity → all components null; pure translation → rotation/scale null.
     */
    static MCBackend.BackendTransform toBackendTransform(@Nullable Transform transform) {
        return toBackendTransform(transform, new org.joml.Vector3d());
    }

    static MCBackend.BackendTransform toBackendTransform(@Nullable Transform transform,
                                                          org.joml.Vector3dc worldOrigin) {
        if (transform == null) {
            return DEFAULT_TRANSFORM;
        }
        boolean zeroOrigin = worldOrigin.x() == 0.0 && worldOrigin.y() == 0.0 && worldOrigin.z() == 0.0;
        if (transform.isIdentity() && zeroOrigin) {
            return IDENTITY_TRANSFORM;
        }
        Vector3f position = transform.isIdentity() ? null : transform.getPosition();
        Vector3f scale = extractScale(transform);
        Vector3f rotation = extractRotationDegrees(transform, scale);
        if (scale != null && close(scale.x(), 1f) && close(scale.y(), 1f) && close(scale.z(), 1f)) {
            scale = null;
        }
        org.joml.Vector3d preciseWorldPosition = new org.joml.Vector3d(worldOrigin);
        if (position != null) preciseWorldPosition.add(position.x, position.y, position.z);
        return new MCBackend.BackendTransform(position, rotation, scale,
                false, false, true, true,
                1f, 1f, -1, -1, false /* root already baked into skinning matrices */,
                preciseWorldPosition);
    }

    /** Scale = lengths of the rotation-part columns (valid for M = R·S layout; negative scale approximated via abs). */
    static Vector3f extractScale(Transform transform) {
        Matrix4f m = transform.transform();
        return new Vector3f(
                length(m.m00(), m.m01(), m.m02()),
                length(m.m10(), m.m11(), m.m12()),
                length(m.m20(), m.m21(), m.m22())
        );
    }

    /** XYZ Euler angles (degrees), per {@code QuaternionHelper.fromXYZDegrees}; degenerate scale → null. */
    @Nullable
    static Vector3f extractRotationDegrees(Transform transform, Vector3f scale) {
        float sx = scale.x(), sy = scale.y(), sz = scale.z();
        if (Math.abs(sx) < MIN_SCALE || Math.abs(sy) < MIN_SCALE || Math.abs(sz) < MIN_SCALE) {
            return null; // degenerate scale: rotation not extractable
        }
        Matrix4f m = transform.transform();
        // Normalize columns → pure rotation matrix (approximate; sign info of negative scale/shear is lost).
        float r00 = m.m00() / sx, r10 = m.m01() / sx, r20 = m.m02() / sx;
        float r01 = m.m10() / sy, r11 = m.m11() / sy, r21 = m.m12() / sy;
        float r02 = m.m20() / sz, r12 = m.m21() / sz, r22 = m.m22() / sz;
        float x = (float) Math.toDegrees(Math.atan2(-r12, r22));
        float y = (float) Math.toDegrees(Math.asin(clamp(r02)));
        float z = (float) Math.toDegrees(Math.atan2(-r01, r00));
        Vector3f rotation = new Vector3f(x, y, z);
        return isZero(rotation) ? null : rotation;
    }

    private static float length(float x, float y, float z) {
        return (float) Math.sqrt(x * x + y * y + z * z);
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 1e-3f;
    }

    private static boolean isZero(Vector3f v) {
        return close(v.x(), 0f) && close(v.y(), 0f) && close(v.z(), 0f);
    }

    //endregion
}
