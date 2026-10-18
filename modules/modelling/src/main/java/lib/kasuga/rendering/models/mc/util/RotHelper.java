package lib.kasuga.rendering.models.mc.util;

import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3f;

public class RotHelper {

    public static final Vector3f ZERO = new Vector3f(0, 0, 0);

    public static void rotation(Transform transform, Vector3f rotation) {
        if (rotation.equals(ZERO)) return;
        transform.rotate(0, 0, rotation.z, true);
        transform.rotate(0, - rotation.y, 0, true);
        transform.rotate(- rotation.x, 0, 0, true);
    }

    public static Transform rotateX(float angleDegrees) {
        return rotateX(angleDegrees, 0, 0, 0);
    }

    public static Transform rotateX(float angleDegrees, Vector3f origin) {
        return rotateX(angleDegrees, origin.x(), origin.y(), origin.z());
    }

    public static Transform rotateX(float angleDegrees, float ox, float oy, float oz) {
        return createRotation(angleDegrees, 1, 0, 0, ox, oy, oz);
    }

    public static Transform rotateY(float angleDegrees) {
        return rotateY(angleDegrees, 0, 0, 0);
    }

    public static Transform rotateY(float angleDegrees, Vector3f origin) {
        return rotateY(angleDegrees, origin.x(), origin.y(), origin.z());
    }

    public static Transform rotateY(float angleDegrees, float ox, float oy, float oz) {
        return createRotation(angleDegrees, 0, 1, 0, ox, oy, oz);
    }

    public static Transform rotateZ(float angleDegrees) {
        return rotateZ(angleDegrees, 0, 0, 0);
    }

    public static Transform rotateZ(float angleDegrees, Vector3f origin) {
        return rotateZ(angleDegrees, origin.x(), origin.y(), origin.z());
    }

    public static Transform rotateZ(float angleDegrees, float ox, float oy, float oz) {
        return createRotation(angleDegrees, 0, 0, 1, ox, oy, oz);
    }

    public static Transform rotateAxis(float angleDegrees, Vector3f axis) {
        return rotateAxis(angleDegrees, axis, 0, 0, 0);
    }

    public static Transform rotateAxis(float angleDegrees, Vector3f axis, Vector3f origin) {
        return rotateAxis(angleDegrees, axis, origin.x(), origin.y(), origin.z());
    }

    public static Transform rotateAxis(float angleDegrees, Vector3f axis, float ox, float oy, float oz) {
        return createRotation(angleDegrees, axis.x(), axis.y(), axis.z(), ox, oy, oz);
    }

    public static Transform fromMinecraftRotation(Vector3f origin, String axisStr, float angleDegrees) {
        return fromMinecraftRotation(origin.x(), origin.y(), origin.z(), axisStr, angleDegrees);
    }

    public static Transform fromMinecraftRotation(float ox, float oy, float oz, String axisStr, float angleDegrees) {
        float ax, ay, az;
        switch (axisStr.toLowerCase()) {
            case "x" -> { ax = 1; ay = 0; az = 0; }
            case "y" -> { ax = 0; ay = 1; az = 0; }
            case "z" -> { ax = 0; ay = 0; az = 1; }
            default -> throw new IllegalArgumentException("Invalid axis: " + axisStr + ". Must be 'x', 'y', or 'z'");
        }
        return createRotation(angleDegrees, ax, ay, az, ox, oy, oz);
    }

    public static Transform fromEulerAngles(Vector3f origin, float xAngle, float yAngle, float zAngle) {
        return fromEulerAngles(origin.x(), origin.y(), origin.z(), xAngle, yAngle, zAngle);
    }

    public static Transform fromEulerAngles(float ox, float oy, float oz, float xAngle, float yAngle, float zAngle) {
        Transform transform = new Transform();
        transform.translate(ox, oy, oz);
        
        Quaternionf rotation = QuaternionHelper.fromXYZDegrees(xAngle, yAngle, zAngle);
        transform.mul(rotation);
        
        transform.translate(-ox, -oy, -oz);
        return transform;
    }

    public static Vector3f applyRotation(Vector3f vertex, Vector3f origin, Vector3f axis, float angleDegrees) {
        float angleRadians = (float) Math.toRadians(angleDegrees);
        Quaternionf rotation = new Quaternionf().rotationAxis(angleRadians, axis);
        Vector3f result = new Vector3f(vertex);
        result.sub(origin);
        rotation.transform(result);
        result.add(origin);
        return result;
    }

    public static Vector3f applyRotation(Vector3f vertex, Transform transform) {
        return transform.apply(new Vector3f(vertex));
    }

    private static Transform createRotation(float angleDegrees, float ax, float ay, float az, 
                                           float ox, float oy, float oz) {
        Transform transform = new Transform();
        
        transform.translate(ox, oy, oz);
        
        float angleRadians = (float) Math.toRadians(angleDegrees);
        Quaternionf rotation = new Quaternionf().rotationAxis(angleRadians, ax, ay, az);
        transform.mul(rotation);
        
        transform.translate(-ox, -oy, -oz);
        
        return transform;
    }
}
