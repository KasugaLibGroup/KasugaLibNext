package lib.kasuga.rendering.models.uml.dynamic.math;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lerp interpolation utility tests: scalars, vectors, quaternions, Transform decomposition/rebuild. */
class LerpTest {

    private static final float EPS = 1e-3f;

    //region scalar

    @Test
    void scalarEndpointsAndMidpoint() {
        assertEquals(0f, Lerp.lerp(0f, 10f, 0f), EPS);
        assertEquals(10f, Lerp.lerp(0f, 10f, 1f), EPS);
        assertEquals(5f, Lerp.lerp(0f, 10f, 0.5f), EPS);
    }

    @Test
    void lerpClamped() {
        assertEquals(0f, Lerp.lerpClamped(0f, 10f, -1f), EPS);
        assertEquals(10f, Lerp.lerpClamped(0f, 10f, 1.5f), EPS);
    }

    @Test
    void easedAppliesEasingToTimeAxis() {
        assertEquals(5f, Lerp.eased(0f, 10f, 0.5f, Easing.linear()), EPS);
        assertEquals(0.0625f, Lerp.eased(0f, 1f, 0.25f, Easing.easeInQuad()), EPS);
        assertEquals(0.4375f, Lerp.eased(0f, 1f, 0.25f, Easing.easeOutQuad()), EPS);
    }

    //endregion

    //region vectors and quaternions

    @Test
    void vectorLerp() {
        Vector3f a = new Vector3f(0f, 0f, 0f);
        Vector3f b = new Vector3f(2f, 4f, 6f);
        Vector3f out = Lerp.lerp(a, b, 0.5f, new Vector3f());
        assertEquals(1f, out.x, EPS);
        assertEquals(2f, out.y, EPS);
        assertEquals(3f, out.z, EPS);
    }

    @Test
    void quaternionSlerpHalfway() {
        Quaternionf identity = new Quaternionf();
        Quaternionf y90 = new Quaternionf().rotationY((float) Math.PI / 2f);
        Quaternionf out = Lerp.slerp(identity, y90, 0.5f, new Quaternionf());
        assertEquals((float) Math.PI / 4f, out.angle(), EPS);
    }

    //endregion

    //region Transform decomposition and interpolation

    @Test
    void getScaleDecomposesColumns() {
        Transform t = new Transform().translate(1f, 2f, 3f).scale(2f, 4f, 8f);
        Vector3f scale = Lerp.getScale(t);
        assertEquals(2f, scale.x, EPS);
        assertEquals(4f, scale.y, EPS);
        assertEquals(8f, scale.z, EPS);
        Vector3f pos = t.getPosition();
        assertEquals(1f, pos.x, EPS);
        assertEquals(2f, pos.y, EPS);
        assertEquals(3f, pos.z, EPS);
    }

    @Test
    void transformLerpPositionsAndScale() {
        Transform a = new Transform().translate(0f, 0f, 0f).scale(1f, 1f, 1f);
        Transform b = new Transform().translate(10f, 0f, 0f).scale(2f, 2f, 2f);
        Transform mid = Lerp.lerp(a, b, 0.5f);
        Vector3f pos = mid.getPosition();
        assertEquals(5f, pos.x, EPS);
        Vector3f scale = Lerp.getScale(mid);
        assertEquals(1.5f, scale.x, EPS);
    }

    @Test
    void transformLerpRotatesHalfway() {
        Transform a = new Transform();
        Transform b = new Transform().rotate(0f, 90f, 0f, true);
        Transform mid = Lerp.lerp(a, b, 0.5f);
        assertEquals((float) Math.PI / 4f, mid.getRotation().angle(), EPS);
    }

    @Test
    void lerpIntoReusesDestAndRoundTrips() {
        Transform a = new Transform().translate(1f, 2f, 3f).scale(1.5f, 2f, 3f);
        Transform dest = new Transform();
        Transform result = Lerp.lerpInto(a, a, 0.5f, dest);
        assertTrue(result == dest, "应复用传入的 dest");
        assertEquals(1f, result.getPosition().x, EPS);
        assertEquals(2f, result.getPosition().y, EPS);
        assertEquals(3f, result.getPosition().z, EPS);
        Vector3f scale = Lerp.getScale(result);
        assertEquals(1.5f, scale.x, EPS);
        assertEquals(2f, scale.y, EPS);
        assertEquals(3f, scale.z, EPS);
    }

    @Test
    void identityTransformRoundTrips() {
        Transform id = new Transform();
        Transform mid = Lerp.lerp(id, id, 0.4f);
        assertTrue(mid.isIdentity(), "identity lerp identity 应为 identity");
    }

    //endregion
}
