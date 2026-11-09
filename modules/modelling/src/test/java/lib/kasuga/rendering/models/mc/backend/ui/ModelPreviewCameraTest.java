package lib.kasuga.rendering.models.mc.backend.ui;

import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModelPreviewCameraTest {
    @Test void screenPanIsIndependentOfModelRotationAspectAndZoom() {
        var min = new Vector3f(-4, -2, -1); var max = new Vector3f(6, 3, 2);
        var center = new Vector3f(min).add(max).mul(.5f);
        for (float aspect : new float[]{.5f, 1, 2}) for (float zoom : new float[]{.5f, 1, 3})
            for (float yaw : new float[]{0, .7f, 2}) {
                var camera = ModelPreviewCamera.frame(min, max, aspect, yaw, .4f, zoom, .15f, -.2f);
                var point = new Vector4f(center, 1);
                camera.modelView().transform(point); camera.projection().transform(point);
                assertEquals(.3f, point.x / point.w, 1e-5);
                assertEquals(.4f, point.y / point.w, 1e-5);
            }
    }

    @Test void zeroPanKeepsLegacyFramingAndInvalidPanIsRejected() {
        var min = new Vector3f(-2); var max = new Vector3f(2);
        var legacy = ModelPreviewCamera.frame(min, max, 2, .4f, .3f, 1);
        var panned = ModelPreviewCamera.frame(min, max, 2, .4f, .3f, 1, 0, 0);
        assertEquals(legacy.modelView(), panned.modelView()); assertEquals(legacy.projection(), panned.projection());
        assertThrows(IllegalArgumentException.class, () -> ModelPreviewCamera.frame(min, max, 1, 0, 0, 1, Float.NaN, 0));
        assertThrows(IllegalArgumentException.class, () -> ModelPreviewCamera.frame(min, max, 1, 0, 0, 1, 0, Float.POSITIVE_INFINITY));
    }
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
