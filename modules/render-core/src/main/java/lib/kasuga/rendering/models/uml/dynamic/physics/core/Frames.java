package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import lib.kasuga.rendering.models.uml.math.Transform;

/** Coordinate-frame conversion used by the Java-to-Box3D adapters. */
public final class Frames {
    public static final float EPSILON = 1e-7f;

    private Frames() {}

    /** Position + orientation pair backed by mutable JOML storage. */
    public static final class Pose {
        public final Vector3f position;
        public final Quaternionf rotation;

        public Pose() {
            this(new Vector3f(), new Quaternionf());
        }

        public Pose(Vector3f position, Quaternionf rotation) {
            this.position = new Vector3f(position);
            this.rotation = new Quaternionf(rotation).normalize();
        }

        public Pose set(Pose other) {
            position.set(other.position);
            rotation.set(other.rotation);
            return this;
        }
    }

    public static Pose compose(Pose a, Pose b) {
        Vector3f position = a.rotation.transform(new Vector3f(b.position)).add(a.position);
        Quaternionf rotation = new Quaternionf(a.rotation).mul(b.rotation).normalize();
        return new Pose(position, rotation);
    }

    public static Pose interpolate(Pose previous, Pose current, float alpha) {
        return new Pose(new Vector3f(previous.position).lerp(current.position, alpha),
                new Quaternionf(previous.rotation).slerp(current.rotation, alpha).normalize());
    }

    public static Pose inverse(Pose value) {
        Quaternionf rotation = new Quaternionf(value.rotation).invert().normalize();
        Vector3f position = rotation.transform(new Vector3f(value.position).negate());
        return new Pose(position, rotation);
    }

    public static Pose relative(Pose a, Pose b) {
        return compose(inverse(a), b);
    }

    public static Pose poseOf(Transform transform) {
        return new Pose(transform.getPosition(), transform.getRotation());
    }

    public static Transform transformOf(Pose pose) {
        return new Transform().set(new Matrix4f().translationRotate(pose.position, pose.rotation));
    }

    public static Quaternionf quaternionFromEuler(Vector3f euler) {
        return new Quaternionf().rotationXYZ(euler.x, euler.y, euler.z);
    }

    public static Vector3f euler(Quaternionf rotation) {
        Vector3f result = rotation.getEulerAnglesXYZ(new Vector3f());
        result.set(wrapPi(result.x), wrapPi(result.y), wrapPi(result.z));
        return result;
    }

    public static float wrapPi(float angle) {
        float twoPi = (float) (Math.PI * 2.0);
        float wrapped = (angle + (float) Math.PI) % twoPi;
        if (wrapped < 0f) wrapped += twoPi;
        return wrapped - (float) Math.PI;
    }

    /** Normalized quaternion with a non-negative scalar part. */
    public static Quaternionf canonical(Quaternionf rotation) {
        Quaternionf value = new Quaternionf(rotation).normalize();
        if (value.w < 0f) value.set(-value.x, -value.y, -value.z, -value.w);
        return value;
    }

    /** Twist component of {@code rotation} around {@code axis}. */
    public static Quaternionf decomposeTwist(Quaternionf rotation, Vector3f axis) {
        float projection = rotation.x * axis.x + rotation.y * axis.y + rotation.z * axis.z;
        Quaternionf twist = new Quaternionf(axis.x * projection, axis.y * projection,
                axis.z * projection, rotation.w);
        return twist.lengthSquared() > EPSILON ? canonical(twist) : new Quaternionf();
    }

    public static float signedTwistAngle(Quaternionf twist, Vector3f axis) {
        float sinHalf = twist.x * axis.x + twist.y * axis.y + twist.z * axis.z;
        return wrapPi(2f * (float) Math.atan2(sinHalf, twist.w));
    }

    public static float quaternionAngle(Quaternionf rotation) {
        return 2f * (float) Math.acos(Math.clamp(canonical(rotation).w, -1f, 1f));
    }

    public static Vector3f quaternionAxis(Quaternionf rotation) {
        Quaternionf value = canonical(rotation);
        Vector3f axis = new Vector3f(value.x, value.y, value.z);
        return axis.lengthSquared() > EPSILON ? axis.normalize() : new Vector3f(1f, 0f, 0f);
    }
}
