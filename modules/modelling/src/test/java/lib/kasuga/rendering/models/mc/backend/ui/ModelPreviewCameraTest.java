package lib.kasuga.rendering.models.mc.backend.ui;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelPreviewCameraTest {
    @Test void framesAllCornersAcrossAspectAndRotationWithoutChangingBounds() {
        var min = new Vector3f(-4, -2, -1);
        var max = new Vector3f(6, 3, 2);
        for (float aspect : new float[]{.1f, .5f, 1, 2, 10}) for (float yaw : new float[]{0, .7f, 2}) {
            var frame = ModelPreviewCamera.frame(min, max, aspect, yaw, .4f, 1);
            for (float x : new float[]{min.x, max.x}) for (float y : new float[]{min.y, max.y})
                for (float z : new float[]{min.z, max.z}) {
                    var point = new Vector4f(x, y, z, 1);
                    frame.modelView().transform(point);
                    frame.projection().transform(point);
                    assertTrue(Math.abs(point.x / point.w) <= 1 && Math.abs(point.y / point.w) <= 1
                            && Math.abs(point.z / point.w) <= 1, "Model corner clipped: " + point);
                }
        }
        assertEquals(new Vector3f(-4, -2, -1), min);
        assertEquals(new Vector3f(6, 3, 2), max);
    }

    @Test void handlesPointAndFlatBoundsAndRejectsInvalidCameraInputs() {
        assertTrue(ModelPreviewCamera.frame(new Vector3f(), new Vector3f(), 1, 0, 0, 1).projection().isFinite());
        assertTrue(ModelPreviewCamera.frame(new Vector3f(-2, 0, 0), new Vector3f(2, 0, 0), .5f, 0, 0, 1).projection().isFinite());
        assertThrows(IllegalArgumentException.class, () -> ModelPreviewCamera.frame(new Vector3f(), new Vector3f(1), 0, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ModelPreviewCamera.frame(new Vector3f(), new Vector3f(1), 1, Float.NaN, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ModelPreviewCamera.frame(new Vector3f(), new Vector3f(1), 1, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> ModelPreviewCamera.frame(new Vector3f(2), new Vector3f(1), 1, 0, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> ModelPreviewCamera.frame(new Vector3f(-Float.MAX_VALUE), new Vector3f(Float.MAX_VALUE), 1, 0, 0, 1));
    }
}
