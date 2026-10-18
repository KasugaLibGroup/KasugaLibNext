package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the behaviour of a top-level embedded type field, e.g. {@code block_entities}.
 *
 * <p>It used to be dispatched like any other top-level field: {@code BlockEntityTypeHandler.parse}
 * read {@code _parent_block}, which only {@code extractEmbedded} ever injects, threw, and the catch in
 * {@code parseSource} then abandoned the rest of the content file. Now an embedded type is not a
 * top-level dispatch slot at all: it is reported as an unsupported top-level field with a hint naming
 * the key it belongs in, and the file's other fields keep dispatching (half-apply semantics).
 *
 * <p>{@link JsonTreeBuilder#parseContentBody} is the in-memory half of the content pipeline, so this
 * is a plain JVM test — no mod jar, no NeoForge runtime.
 */
class JsonTreeBuilderEmbeddedTypeDispatchTest {

    private static final String MOD = "embedded_type_dispatch_mod";

    /** One offending top-level field next to one legitimate field, in the same document. */
    private static final String CONTENT = """
            {
              "block_entities": [ { "type": "fsm_be" } ],
              "blocks": [ { "id": "embedded_dispatch_test_block", "type": "simple_block" } ]
            }
            """;

    @AfterEach
    void clearBuckets() {
        JsonTreeBuilder.clearLoadingErrors();
    }

    @Test
    void topLevelEmbeddedTypeIsReportedAndTheRestOfTheFileStillDispatches() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        Map<String, List<Object>> collected = JsonTreeBuilder.parseContentBody(MOD, "test:embedded.json", body());

        List<Throwable> errors = JsonTreeBuilder.getLoadingErrors(MOD);
        assertEquals(1, errors.size(), "the one offending field must produce exactly one diagnostic: " + errors);
        Throwable error = errors.get(0);
        assertTrue(error instanceof IllegalStateException,
                "the diagnostic must come from the unknown-field path, not from a parse crash: " + error);
        String message = error.getMessage();
        assertTrue(message.contains("unsupported top-level field 'block_entities'"),
                "the offending field must be named: " + message);
        assertTrue(message.contains("'blocks' entry's 'block_entity' key"),
                "the hint must name the parent entry and the key the author meant to write: " + message);

        // Half-apply: the embedded field is dropped, the sibling field is still collected.
        assertEquals(1, collected.getOrDefault("blocks", List.of()).size(),
                "the rest of the file must still dispatch after the offending field: " + collected);
        assertFalse(collected.containsKey("block_entities"),
                "an embedded type is never collected from a top-level field: " + collected);
    }

    private static JsonObject body() {
        return JsonParser.parseString(CONTENT).getAsJsonObject();
    }
}
