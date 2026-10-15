package lib.kasuga.rendering.effect.shader;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShaderParameterPersistenceTest {
    @Test
    void roundTripsVersionedParameterValuesDeterministically() {
        Map<String, Map<String, double[]>> values = new LinkedHashMap<>();
        values.put("test:second", Map.of("Enabled", new double[]{1}));
        values.put("test:first", Map.of(
                "Tint", new double[]{1.0, 0.5, 0.25},
                "Iterations", new double[]{1_234_567_891}
        ));

        String encoded = ShaderParameterPersistence.encode(values);
        Map<String, Map<String, double[]>> decoded = ShaderParameterPersistence.decode(encoded);

        assertTrue(encoded.indexOf("test:first") < encoded.indexOf("test:second"));
        assertArrayEquals(new double[]{1.0, 0.5, 0.25}, decoded.get("test:first").get("Tint"));
        assertArrayEquals(new double[]{1_234_567_891}, decoded.get("test:first").get("Iterations"));
        assertEquals(encoded, ShaderParameterPersistence.encode(decoded));
    }

    @Test
    void rejectsUnknownPersistenceVersions() {
        assertThrows(IllegalArgumentException.class, () -> ShaderParameterPersistence.decode(
                "{\"version\":2,\"shaders\":{}}"
        ));
    }
}
