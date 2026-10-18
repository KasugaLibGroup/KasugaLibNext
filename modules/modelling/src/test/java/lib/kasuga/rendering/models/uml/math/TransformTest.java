package lib.kasuga.rendering.models.uml.math;

import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransformTest {
    @Test
    void zeroScaleKeepsPositionsCollapsedAndNormalsFinite() {
        for (Vector3f scale : new Vector3f[]{new Vector3f(0, 1.5f, 0), new Vector3f(0, 2, 3),
                new Vector3f(0), new Vector3f(-2), new Vector3f(-1, 2, 3)}) {
            Transform value = new Transform().rotate(17, 23, 41, true).scale(scale.x, scale.y, scale.z);
            assertTrue(value.normal().isFinite(), "scale " + scale);
            assertTrue(value.transform().isFinite());
            Transform matrix = new Transform().set(new Matrix4f().scaling(scale));
            assertTrue(matrix.normal().isFinite(), "matrix scale " + scale);
        }
        assertEquals(new Vector3f(0, 3, 0), new Transform().scale(0, 1.5f, 0).apply(new Vector3f(2)));
        Vector3f normal = new Transform().scale(0, 2, 3).normal().transform(new Vector3f(1, 0, 0));
        assertEquals(new Vector3f(1, 0, 0), normal);
    }

    @Test
    void reflectedScaleHasTheInverseTransposeNormalDirection() {
        Vector3f expected = new Matrix4f().scaling(-1, 2, 3).normal(new org.joml.Matrix3f())
                .transform(new Vector3f(1, 1, 1)).normalize();
        Vector3f actual = new Transform().scale(-1, 2, 3).normal()
                .transform(new Vector3f(1, 1, 1)).normalize();
        assertEquals(expected.x, actual.x, 1e-6);
        assertEquals(expected.y, actual.y, 1e-6);
        assertEquals(expected.z, actual.z, 1e-6);
    }

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
