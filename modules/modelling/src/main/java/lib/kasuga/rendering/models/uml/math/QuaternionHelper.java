package lib.kasuga.rendering.models.uml.math;

import org.joml.Quaternionf;
import org.joml.Vector3f;

public class QuaternionHelper {

    public static final Vector3f ZERO = new Vector3f(0, 0, 0);

    public static Quaternionf fromXYZAngle(float x, float y, float z, boolean degrees) {
        if (degrees) {
            x = (float) Math.toRadians(x);
            y = (float) Math.toRadians(y);
            z = (float) Math.toRadians(z);
        }
        float i, j, k, r;
        float f = sin(0.5F * x);
        float f1 = cos(0.5F * x);
        float f2 = sin(0.5F * y);
        float f3 = cos(0.5F * y);
        float f4 = sin(0.5F * z);
        float f5 = cos(0.5F * z);

        i = f * f3 * f5 + f1 * f2 * f4;
        j = f1 * f2 * f5 - f * f3 * f4;
        k = f * f2 * f5 + f1 * f3 * f4;
        r = f1 * f3 * f5 - f * f2 * f4;

        return new Quaternionf(i, j, k, r);
    }

    public static Quaternionf fromXYZRadians(float x, float y, float z) {
        return fromXYZAngle(x, y, z, false);
    }

    public static Quaternionf fromXYZDegrees(float x, float y, float z) {
        return fromXYZAngle(x, y, z, true);
    }

    public static Quaternionf fromXYZDegrees(Vector3f angles) {
        return fromXYZDegrees(angles.x(), angles.y(), angles.z());
    }

    /**
     * Blockbench/Minecraft euler order: intrinsic Z, then Y, then X — the matrix {@code Rz·Ry·Rx}.
     *
     * <p>{@code .bbmodel} element, group and animation rotations are authored in this order. It is the
     * composition Blockbench's own preview applies (three.js {@code Euler.order = "ZYX"}), the order
     * Minecraft's {@code ModelPart} multiplies its {@code ZP}/{@code YP}/{@code XP} rotations in, and the
     * order {@code RotHelper.rotation} composes Bedrock cube rotations in. {@link #fromXYZDegrees} is
     * JOML's {@code rotationXYZ} — the reverse composition — and silently mis-places every rotation that
     * spans more than one axis. Single-axis rotations are unaffected by the order.
     */
    public static Quaternionf fromZYXAngle(float x, float y, float z, boolean degrees) {
        return new Quaternionf()
                .mul(fromXYZAngle(0.0f, 0.0f, z, degrees))
                .mul(fromXYZAngle(0.0f, y, 0.0f, degrees))
                .mul(fromXYZAngle(x, 0.0f, 0.0f, degrees));
    }

    public static Quaternionf fromZYXRadians(float x, float y, float z) {
        return fromZYXAngle(x, y, z, false);
    }

    public static Quaternionf fromZYXDegrees(float x, float y, float z) {
        return fromZYXAngle(x, y, z, true);
    }

    public static Quaternionf fromZYXDegrees(Vector3f angles) {
        return fromZYXDegrees(angles.x(), angles.y(), angles.z());
    }

    public static float sin(float angle) {
        return (float) Math.sin(angle);
    }

    public static float cos(float angle) {
        return (float) Math.cos(angle);
    }

    public static float tan(float angle) {
        return (float) Math.tan(angle);
    }
}
