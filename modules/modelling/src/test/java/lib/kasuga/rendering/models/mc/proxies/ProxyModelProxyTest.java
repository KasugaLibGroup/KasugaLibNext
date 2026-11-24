package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProxyModelProxyTest {
    private static final Vector3f NORTH = new Vector3f(0f, 0f, -1f);

    @Test
    void minecraftYawUsesBlockstateHandedness() {
        assertDirection(new Vector3f(0f, 0f, -1f), 0f);
        assertDirection(new Vector3f(1f, 0f, 0f), 90f);
        assertDirection(new Vector3f(0f, 0f, 1f), 180f);
        assertDirection(new Vector3f(-1f, 0f, 0f), 270f);
    }

    private static void assertDirection(Vector3f expected, float yaw) {
        Vector3f actual = ProxyModelProxy.applyBlockstateYaw(new Transform(), yaw)
                .applyDirection(new Vector3f(NORTH));
        assertEquals(expected.x, actual.x, 1.0e-6f);
        assertEquals(expected.y, actual.y, 1.0e-6f);
        assertEquals(expected.z, actual.z, 1.0e-6f);
    }
}
