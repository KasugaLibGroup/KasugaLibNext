package lib.kasuga.rendering.models.mc.backend.ui;

import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScreenPointerProjectionTest {
    @Test void centerAndCornersFollowPerspectiveAspectAndTopLeftCoordinates() {
        var projection = new ScreenPointerProjection(new Matrix4f().perspective((float) Math.PI / 2, 2, .05f, 100), 800, 400);
        var origin = new Vector3f();
        var direction = new Vector3f();
        assertTrue(projection.ray(400, 200, origin, direction));
        assertVector(new Vector3f(0, 0, -1), direction);
        assertTrue(projection.ray(800, 0, origin, direction));
        assertVector(new Vector3f(2, 1, -1).normalize(), direction);
        assertTrue(projection.ray(0, 400, origin, direction));
        assertVector(new Vector3f(-2, -1, -1).normalize(), direction);
    }

    @Test void projectionRoundTripIncludesZoomViewRotationAndGuiScale() {
        var projection = new Matrix4f().translate(.15f, -.08f, 0).scale(1.8f, 1.8f, 1)
                .perspective(.9f, 16f / 9, .05f, 100);
        var view = new Matrix4f().rotateX(.12f).rotateY(.2f);
        var point = new Vector3f(.4f, .15f, -3);
        var viewPoint = view.transformPosition(point, new Vector3f());
        var clip = projection.transformProject(viewPoint, new Vector3f());
        for (int scale : new int[]{1, 2, 4}) {
            double width = 1920d / scale, height = 1080d / scale;
            var ray = new ScreenPointerProjection(projection, width, height);
            var origin = new Vector3f();
            var direction = new Vector3f();
            assertTrue(ray.ray((clip.x + 1) * width / 2, (1 - clip.y) * height / 2, origin, direction));
            var error = new Vector3f(viewPoint).sub(origin).cross(direction);
            assertEquals(0, error.length(), 2e-5);
        }
    }

    @Test void orthographicAndInfiniteFarPlanesRemainUsableAndSnapshotIsDefensive() {
        var matrix = new Matrix4f().ortho(-2, 2, -1, 1, .1f, 100);
        var ray = new ScreenPointerProjection(matrix, 800, 400);
        matrix.zero();
        var origin = new Vector3f();
        var direction = new Vector3f();
        assertTrue(ray.ray(600, 100, origin, direction));
        assertVector(new Vector3f(1, .5f, -.1f), origin);
        assertVector(new Vector3f(0, 0, -1), direction);
        ray = new ScreenPointerProjection(new Matrix4f().perspective(1, 1, .05f, Float.POSITIVE_INFINITY), 100, 100);
        assertTrue(ray.ray(50, 50, origin, direction));
        assertVector(new Vector3f(0, 0, -1), direction);
    }

    @Test void rejectsInvalidViewportMatricesAndPointerValues() {
        assertThrows(IllegalArgumentException.class, () -> new ScreenPointerProjection(new Matrix4f(), 0, 10));
        assertThrows(IllegalArgumentException.class, () -> new ScreenPointerProjection(new Matrix4f().zero(), 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new ScreenPointerProjection(new Matrix4f(), Double.NaN, 10));
        var ray = new ScreenPointerProjection(new Matrix4f(), 10, 10);
        assertFalse(ray.ray(Double.NaN, 0, new Vector3f(), new Vector3f()));
    }

    private static void assertVector(Vector3f expected, Vector3f actual) {
        assertEquals(expected.x, actual.x, 1e-5);
        assertEquals(expected.y, actual.y, 1e-5);
        assertEquals(expected.z, actual.z, 1e-5);
    }
}
