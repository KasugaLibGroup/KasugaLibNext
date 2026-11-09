package lib.kasuga.rendering.models.mc.source.model;

import com.google.gson.JsonParseException;
import lib.kasuga.utils.json.GsonBindings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class JsonBindingContractTest {
    private record Input(String name, boolean enabled, int[] meshIndices) {}

    @Test void strictSchemasRejectImplicitPrimitiveCoercions() {
        for (String input : new String[]{
                "{\"name\":3}", "{\"enabled\":\"false\"}",
                "{\"mesh_indices\":[1.5]}", "{\"mesh_indices\":[\"2\"]}"}) {
            assertThrows(JsonParseException.class, () -> GsonBindings.STRICT.fromJson(input, Input.class), input);
        }
    }

    @Test void strictSchemasRetainTypedValuesAndSnakeCaseNames() {
        var decoded = GsonBindings.STRICT.fromJson(
                "{\"name\":\"body\",\"enabled\":true,\"mesh_indices\":[0,2]}", Input.class);
        assertEquals("body", decoded.name());
        assertTrue(decoded.enabled());
        assertArrayEquals(new int[]{0, 2}, decoded.meshIndices());
    }
}
