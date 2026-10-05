package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P2-6's index contract, for the embedded dispatch path: a diagnostic must name the element's true
 * position in its top-level array. The top-level loop already reports {@code fileIndex} (which counts
 * the non-object slots the structure layer skipped); the embedded loop used to re-derive its own
 * running counter, so {@code blocks: [obj, 5, obj]} reported positions 0 and 1 instead of 0 and 2.
 *
 * <p>{@link JsonTreeBuilder#parseContentBody} is the in-memory half of the content pipeline, so this
 * is a plain JVM test — no mod jar, no NeoForge runtime.
 */
class JsonTreeBuilderEmbeddedElementIndexTest {

    private static final String MOD = "embedded_element_index_mod";

    /**
     * Two structurally valid blocks (positions 0 and 2) whose embedded {@code block_entity} is a
     * primitive, so {@code BlockEntityTypeHandler.extractEmbedded} throws for both, around a
     * non-object element (position 1) that the structure layer reports separately.
     */
    private static final String CONTENT = """
            {
              "blocks": [
                { "id": "index_a", "type": "simple_block", "block_entity": 5 },
                7,
                { "id": "index_b", "type": "simple_block", "block_entity": 5 }
              ]
            }
            """;

    @AfterEach
    void clearBuckets() {
        JsonTreeBuilder.clearLoadingErrors();
    }

    @Test
    void embeddedParseFailureReportsTheTrueArrayIndexSkippingNonObjectElements() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        JsonTreeBuilder.parseContentBody(MOD, "test:embedded_index.json",
                JsonParser.parseString(CONTENT).getAsJsonObject());

        List<String> embeddedFailures = JsonTreeBuilder.getLoadingErrors(MOD).stream()
                .map(Throwable::getMessage)
                .filter(message -> message.contains("in 'blocks' failed to parse"))
                .toList();

        assertEquals(2, embeddedFailures.size(),
                "each embedded parse failure must be reported exactly once: " + embeddedFailures);
        assertTrue(embeddedFailures.get(0).contains("entry 0"),
                "the first valid element sits at array position 0: " + embeddedFailures.get(0));
        assertTrue(embeddedFailures.get(1).contains("entry 2"),
                "the second valid element sits at array position 2, not 1 — the non-object element at "
                        + "position 1 must still be counted: " + embeddedFailures.get(1));
    }
}
