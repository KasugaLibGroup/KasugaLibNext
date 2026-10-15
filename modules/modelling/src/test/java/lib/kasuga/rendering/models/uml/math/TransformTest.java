package lib.kasuga.rendering.models.uml.math;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransformTest {
    @Test
    void extractsAUnitRotationFromAScaledAffineMatrix() {
        Quaternionf expected = new Quaternionf().rotationXYZ(0.47f, -0.82f, 1.13f);
        Transform transform = new Transform().set(new Matrix4f()
                .translationRotateScale(new Vector3f(2f, -3f, 4f), expected,
                        new Vector3f(1.3f)));

        Quaternionf actual = transform.getRotation();

        assertEquals(1f, actual.lengthSquared(), 1.0e-6f);
        assertEquals(1f, Math.abs(expected.dot(actual)), 1.0e-6f);
    }
}
