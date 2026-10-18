package lib.kasuga.rendering.models.uml.math;

import org.joml.*;

import java.lang.Math;
import java.util.Objects;

public class Transform {


    private final Matrix4f transform;
    private final Matrix3f normal;

    public Transform(Matrix4f transform, Matrix3f normal) {
        this.transform = transform;
        this.normal = normal;
    }

    public Transform() {
        this.transform = new Matrix4f().identity();
        this.normal = new Matrix3f().identity();
    }

    public Vector3f apply(Vector3f vector) {
        return transform.transformPosition(vector);
    }

    public Vector3f applyDirection(Vector3f vector) {
        return transform.transformDirection(vector);
    }

    public Vector3f applyInvert(Vector3f vector) {
        return invertTransform().transformPosition(vector);
    }

    public Transform copy() {
        return new Transform(new Matrix4f(transform), new Matrix3f(normal));
    }

    public Matrix4f transform() {
        return transform;
    }

    public Matrix4f invertTransform() {
        return new Matrix4f(transform).invert();
    }

    public Transform invert() {
        return new Transform(invertTransform(), new Matrix3f(normal).invert());
    }

    public Matrix3f normal() {
        return normal;
    }

    public Transform mul(Transform other) {
        transform.mul(other.transform);
        normal.mul(other.normal);
        return this;
    }

    public Transform set(Transform other) {
        transform.set(other.transform);
        normal.set(other.normal);
        return this;
    }

    /** Replaces this transform; singular bases use cofactors to keep collapsed geometry's normals finite. */
    public Transform set(Matrix4f value) {
        transform.set(Objects.requireNonNull(value, "value"));
        normal.set(value).invert().transpose();
        if (!normal.isFinite()) {
            normal.set(value).cofactor();
            if (!normal.isFinite()) normal.identity();
        }
        return this;
    }

    public Transform translate(float x, float y, float z) {
        transform.translate(x, y, z);
        return this;
    }

    public Transform translate(Vector3f vec) {
        transform.translate(vec);
        return this;
    }

    /**
     * Moves this transform in world space without changing its rotation or scale.
     *
     * <p>This differs from {@link #translate(Vector3f)}, which composes a local-space
     * translation and is therefore affected by the transform's current rotation and scale.</p>
     */
    public Transform translateWorld(Vector3f delta) {
        Objects.requireNonNull(delta, "delta");
        transform.setTranslation(
                transform.m30() + delta.x,
                transform.m31() + delta.y,
                transform.m32() + delta.z
        );
        return this;
    }

    /** Replaces only the world-space translation component. */
    public Transform setPosition(Vector3f position) {
        transform.setTranslation(Objects.requireNonNull(position, "position"));
        return this;
    }

    public Transform mul(Quaternionf quaternion) {
        transform.rotate(quaternion);
        normal.rotate(quaternion);
        return this;
    }

    public Transform rotate(float x, float y, float z, boolean degrees) {
        Quaternionf quaternion = QuaternionHelper.fromXYZAngle(x, y, z, degrees);
        return mul(quaternion);
    }

    public Transform rotate(Vector3f rotation, boolean degrees) {
        Quaternionf quaternionf = QuaternionHelper.fromXYZAngle(rotation.x(), rotation.y(), rotation.z(), degrees);
        return mul(quaternionf);
    }

    public Transform scale(float x, float y, float z) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)) {
            throw new IllegalArgumentException("Scale components must be finite");
        }
        transform.mul(createScalingMatrix(x, y, z));

        if (x == 0f || y == 0f || z == 0f) {
            // An inverse does not exist. Cofactors retain the surviving plane's normal;
            // normalize their magnitude in double precision before converting to floats.
            double nx = (double) y * z, ny = (double) x * z, nz = (double) x * y;
            double largest = Math.max(Math.abs(nx), Math.max(Math.abs(ny), Math.abs(nz)));
            if (largest > 0) normal.scale((float) (nx / largest), (float) (ny / largest), (float) (nz / largest));
            return this;
        }

        if (x == y && y == z) {
            if (x < 0) normal.scale(-1f);
            return this;
        }

        double magnitude = Math.cbrt(Math.abs((double) x * y * z));
        normal.scale((float) (magnitude / x), (float) (magnitude / y), (float) (magnitude / z));
        return this;
    }

    public Transform setIdentity() {
        transform.identity();
        normal.identity();
        return this;
    }

    public boolean isIdentity() {
        return close(transform.m00(), 1.0f) &&
                close(transform.m01(), 0.0f) &&
                close(transform.m02(), 0.0f) &&
                close(transform.m03(), 0.0f) &&
                close(transform.m10(), 0.0f) &&
                close(transform.m11(), 1.0f) &&
                close(transform.m12(), 0.0f) &&
                close(transform.m13(), 0.0f) &&
                close(transform.m20(), 0.0f) &&
                close(transform.m21(), 0.0f) &&
                close(transform.m22(), 1.0f) &&
                close(transform.m23(), 0.0f) &&
                close(transform.m30(), 0.0f) &&
                close(transform.m31(), 0.0f) &&
                close(transform.m32(), 0.0f) &&
                close(transform.m33(), 1.0f);
    }

    private static boolean close(float a, float b) {
        return Math.abs(a - b) < 1.0e-6f;
    }

    public static Matrix4f createScalingMatrix(float x, float y, float z) {
        Matrix4f scalingMatrix = new Matrix4f();
        scalingMatrix.m00(x);
        scalingMatrix.m11(y);
        scalingMatrix.m22(z);
        scalingMatrix.m33(1F);
        return scalingMatrix;
    }

    public static Matrix3f createScalingMatrix3f(float x, float y, float z) {
        Matrix3f scalingMatrix = new Matrix3f();
        scalingMatrix.m00(x);
        scalingMatrix.m11(y);
        scalingMatrix.m22(z);
        return scalingMatrix;
    }

    public static float fastInvCubeRoot(float pNumber) {
        int i = Float.floatToIntBits(pNumber);
        i = 1419967116 - i / 3;
        float f = Float.intBitsToFloat(i);
        f = 0.6666667F * f + 1.0F / (3.0F * f * f * pNumber);
        return 0.6666667F * f + 1.0F / (3.0F * f * f * pNumber);
    }

    public Vector3f getPosition() {
        return transform.getTranslation(new Vector3f());
    }

    public Quaternionf getRotation() {
        // Matrix4f#getNormalizedRotation assumes that the 3x3 basis already
        // has unit length. Model manifests and glTF roots commonly carry a
        // scale, so using it here leaks that scale into the quaternion and a
        // later translationRotate call applies an additional, pose-dependent
        // scale. getUnnormalizedRotation removes basis scale first.
        return transform.getUnnormalizedRotation(new Quaternionf()).normalize();
    }

    public DualQuaternion toDualQuaternion() {
        return new DualQuaternion(this.transform);
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof Transform trans)) return false;
        return Objects.equals(trans.transform, this.transform) &&
                Objects.equals(trans.normal, this.normal);
    }

    @Override
    public int hashCode() {
        return Objects.hash(transform, normal);
    }
}
