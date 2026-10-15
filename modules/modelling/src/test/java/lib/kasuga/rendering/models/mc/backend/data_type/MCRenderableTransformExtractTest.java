package lib.kasuga.rendering.models.mc.backend.data_type;

import lib.kasuga.rendering.models.mc.backend.MCBackend;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B3 纯函数：{@link MCRenderableContext#toBackendTransform} 的 TRS 提取。
 * 恒等 → position null；纯平移 → 无旋转/缩放；旋转单位/顺序与
 * {@code Transform.rotate(x, y, z, degrees)} / {@code QuaternionHelper.fromXYZDegrees} 一致。
 */
class MCRenderableTransformExtractTest {

    private static final float DEG = 1e-2f;
    private static final float RAD = 1e-4f;

    @Test
    void identityYieldsNullPosition() {
        MCBackend.BackendTransform transform = MCRenderableContext.toBackendTransform(new Transform());
        assertNull(transform.getPosition(), "identity root must pass null position (old-behavior equivalence)");
        assertNull(transform.getRotation());
        assertNull(transform.getScale());
        assertTrue(transform.isAppliesTransform(), "identity root keeps appliesTransform=true (no-op)");
    }

    @Test
    void nullTransformFallsBackToDefault() {
        MCBackend.BackendTransform transform = MCRenderableContext.toBackendTransform(null);
        assertNotNull(transform.getPosition());
        assertEquals(0f, transform.getPosition().x(), 1e-6f);
        assertEquals(0f, transform.getPosition().y(), 1e-6f);
        assertEquals(0f, transform.getPosition().z(), 1e-6f);
    }

    @Test
    void pureTranslationHasNoRotationOrScale() {
        MCBackend.BackendTransform transform =
                MCRenderableContext.toBackendTransform(new Transform().translate(1f, 2f, 3f));
        assertEquals(1f, transform.getPosition().x(), RAD);
        assertEquals(2f, transform.getPosition().y(), RAD);
        assertEquals(3f, transform.getPosition().z(), RAD);
        assertNull(transform.getRotation(), "pure translation must yield null rotation");
        assertNull(transform.getScale(), "pure translation must yield null scale");
        assertFalse(transform.isAppliesTransform(), "extracted (baked-root) transforms must not re-apply into the pose stack");
    }

    @Test
    void fullTrsRoundTrips() {
        Transform transform = new Transform()
                .translate(1.5f, -2.5f, 3.25f)
                .rotate(30f, 45f, 60f, true)
                .scale(2f, 2f, 2f);
        MCBackend.BackendTransform extracted = MCRenderableContext.toBackendTransform(transform);

        assertEquals(1.5f, extracted.getPosition().x(), RAD);
        assertEquals(-2.5f, extracted.getPosition().y(), RAD);
        assertEquals(3.25f, extracted.getPosition().z(), RAD);

        assertNotNull(extracted.getRotation());
        assertEquals(30f, extracted.getRotation().x(), DEG, "rotation must be in degrees");
        assertEquals(45f, extracted.getRotation().y(), DEG);
        assertEquals(60f, extracted.getRotation().z(), DEG);

        assertNotNull(extracted.getScale());
        assertEquals(2f, extracted.getScale().x(), RAD);
        assertEquals(2f, extracted.getScale().y(), RAD);
        assertEquals(2f, extracted.getScale().z(), RAD);
    }

    @Test
    void nonUniformScaleKeepsRotation() {
        Transform transform = new Transform().rotate(90f, 0f, 0f, true).scale(1f, 2f, 3f);
        MCBackend.BackendTransform extracted = MCRenderableContext.toBackendTransform(transform);

        assertEquals(90f, extracted.getRotation().x(), DEG);
        assertEquals(0f, extracted.getRotation().y(), DEG);
        assertEquals(0f, extracted.getRotation().z(), DEG);
        assertEquals(1f, extracted.getScale().x(), RAD);
        assertEquals(2f, extracted.getScale().y(), RAD);
        assertEquals(3f, extracted.getScale().z(), RAD);
    }

    @Test
    void rotationConventionMatchesQuaternionHelper() {
        Transform transform = new Transform().rotate(10f, 20f, 30f, true);
        MCBackend.BackendTransform extracted = MCRenderableContext.toBackendTransform(transform);

        Quaternionf extractedQ = QuaternionHelper.fromXYZDegrees(extracted.getRotation());
        Quaternionf originalQ = transform.getRotation();
        // 同一旋转的四元数，符号可差
        float dot = extractedQ.x * originalQ.x + extractedQ.y * originalQ.y
                + extractedQ.z * originalQ.z + extractedQ.w * originalQ.w;
        assertTrue(Math.abs(dot) > 0.999f, "extracted XYZ euler must reproduce the original rotation");
    }

    @Test
    void flagsFollowDefaultTransformValues() {
        MCBackend.BackendTransform extracted =
                MCRenderableContext.toBackendTransform(new Transform().translate(5f, 0f, 0f));
        assertTrue(extracted.isEnableWorldLightAndBrightness());
        assertTrue(extracted.isEnableAutoOverlay());
        assertEquals(1f, extracted.getEmissiveStrength(), 1e-6f);
        assertEquals(1f, extracted.getBrightness(), 1e-6f);
    }

    @Test
    void extractedMatrixMatchesOriginalForTrs() {
        Transform transform = new Transform()
                .translate(1f, 2f, 3f)
                .rotate(15f, 25f, 35f, true)
                .scale(1.5f, 1f, 0.5f);
        MCBackend.BackendTransform extracted = MCRenderableContext.toBackendTransform(transform);

        Matrix4f rebuilt = new Matrix4f().identity()
                .translate(extracted.getPosition())
                .rotate(QuaternionHelper.fromXYZDegrees(extracted.getRotation()))
                .scale(extracted.getScale());
        Matrix4f original = transform.transform();
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                assertEquals(original.get(col, row), rebuilt.get(col, row), 1e-2f,
                        "rebuilt matrix must approximate the original (m[" + col + "][" + row + "])");
            }
        }
    }
}
