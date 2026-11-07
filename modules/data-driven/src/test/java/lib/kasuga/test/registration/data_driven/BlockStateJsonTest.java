package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.factory.FactoryRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BlockStateJsonTest {
    private static final String MOD = "state_property_test";

    @AfterEach
    void clearErrors() {
        JsonTreeBuilder.clearLoadingErrors(MOD);
    }

    @Test
    void rejectsAnUnknownPropertyTypeWithoutDroppingSiblings() {
        String json = """
                {"blocks": [
                  {"id": "example:good", "type": "data_driven_block"},
                  {"id": "example:bad", "type": "data_driven_block",
                   "state_properties": {"open": {"type": "unknown"}}}
                ]}
                """;
        Map<String, List<Object>> parsed = JsonTreeBuilder.parseContentBody(
                MOD, "states.json", JsonParser.parseString(json).getAsJsonObject());
        assertEquals(1, parsed.get("blocks").size(), "only the invalid block should be skipped");
        assertEquals(1, JsonTreeBuilder.getLoadingErrors(MOD).size());
        assertTrue(JsonTreeBuilder.getLoadingErrors(MOD).get(0).getMessage().contains("open"));
    }

    @Test
    void includesABuiltInStateAwareBlockFactory() {
        JsonTreeBuilder.parseContentBody(MOD, "empty.json", JsonParser.parseString("{}").getAsJsonObject());
        assertNotNull(FactoryRegistry.get("data_driven_block"));
    }
}
