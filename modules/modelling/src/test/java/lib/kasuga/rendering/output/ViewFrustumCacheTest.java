package lib.kasuga.rendering.output;

import lib.kasuga.rendering.output.mc.ViewFrustumCache;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ViewFrustumCacheTest {
    @Test void equalValuesReuseVisibilityButMovingOrReplacingGridInvalidates() {
        var cache = new ViewFrustumCache();
        var grid = new Object();
        var view = new Matrix4f();
        var projection = new Matrix4f().perspective(1.2f, 16f / 9, .05f, 128);
        assertTrue(cache.update(Vec3.ZERO, view, projection, grid));
        assertFalse(cache.update(new Vec3(0, 0, 0), new Matrix4f(view), new Matrix4f(projection), grid));
        assertTrue(cache.update(new Vec3(.01, 0, 0), view, projection, grid));
        assertFalse(cache.update(new Vec3(.01, 0, 0), view, projection, grid));
        assertTrue(cache.update(new Vec3(.01, 0, 0), view, projection, new Object()));
    }

    @Test void inPlaceFovAspectAndOrientationChangesAreDetected() {
        var cache = new ViewFrustumCache();
        var grid = new Object();
        var view = new Matrix4f();
        var projection = new Matrix4f().perspective(1.2f, 16f / 9, .05f, 128);
        cache.update(Vec3.ZERO, view, projection, grid);
        projection.identity().perspective(.7f, 16f / 9, .05f, 128);
        assertTrue(cache.update(Vec3.ZERO, view, projection, grid));
        projection.identity().perspective(.7f, 1, .05f, 128);
        assertTrue(cache.update(Vec3.ZERO, view, projection, grid));
        view.rotateZ(.2f);
        assertTrue(cache.update(Vec3.ZERO, view, projection, grid));
        assertFalse(cache.update(Vec3.ZERO, view, projection, grid));
    }
}
