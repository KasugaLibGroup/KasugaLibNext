package lib.kasuga.rendering.lighting;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DiffuseIrradianceTest {
    @Test void lobesRemainDirectionalContinuousAndEnergyBounded() {
        var red = new Vector3f(1, 0, 0);
        var zero = new Vector3f();
        var probe = new DiffuseIrradiance(red, zero, new Vector3f(0, 1, 0), zero, zero, zero);
        red.zero();
        assertEquals(new Vector3f(1, 0, 0), probe.sample(new Vector3f(1, 0, 0)));
        assertEquals(zero, probe.sample(new Vector3f(-1, 0, 0)));
        var diagonal = probe.sample(new Vector3f(1, 1, 0));
        assertEquals(.5f, diagonal.x, 1e-6); assertEquals(.5f, diagonal.y, 1e-6);
        assertEquals(0, diagonal.z);
        assertEquals(zero, DiffuseIrradiance.ZERO.sample(new Vector3f(0, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> probe.sample(zero));
        assertThrows(IllegalArgumentException.class, () -> probe.sample(new Vector3f(Float.NaN, 0, 0)));
    }
}
