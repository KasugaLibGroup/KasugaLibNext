package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D9's registration-side conditional hint: a file listed under {@code on_register} whose top-level
 * field is reload-domain content (state machine definitions) is reported through the ordinary
 * unknown-field path — the registration domain does not recognise reload shapes and must not widen
 * its known set for them — and the message tells the author to list the file under {@code on_reload}.
 *
 * <p>{@link JsonTreeBuilder#parseContentBody} is the in-memory half of the content pipeline, so this
 * is a plain JVM test, mirroring {@link JsonTreeBuilderEmbeddedTypeDispatchTest}.
 */
class JsonTreeBuilderReloadHintTest {

    private static final String MOD = "reload_hint_mod";

    /** Reload-domain content, i.e. what an on_register listing of a state machine file looks like. */
    private static final String CONTENT = """
            {
              "state_machines": [ { "id": "reload_hint_mod:panel", "layers": [] } ]
            }
            """;

    @AfterEach
    void clearBuckets() {
        JsonTreeBuilder.clearLoadingErrors();
    }

    @Test
    void reloadDomainContentUnderOnRegisterIsReportedWithOnReloadHint() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        JsonTreeBuilder.parseContentBody(MOD, "test:state_machines.json",
                JsonParser.parseString(CONTENT).getAsJsonObject());

        List<Throwable> errors = JsonTreeBuilder.getLoadingErrors(MOD);
        assertEquals(1, errors.size(), "the one offending field must produce exactly one diagnostic: " + errors);
        Throwable error = errors.get(0);
        assertTrue(error instanceof IllegalStateException,
                "the diagnostic must come from the unknown-field path, not a parse crash: " + error);
        String message = error.getMessage();
        assertTrue(message.contains("unsupported top-level field 'state_machines'"),
                "the offending field must be named: " + message);
        assertTrue(message.contains("'on_reload'"),
                "the hint must point the author at the index's other array: " + message);
    }

    @Test
    void halfApplyStillDispatchesSiblingRegistrationFields() {
        JsonTreeBuilder.clearLoadingErrors(MOD);

        JsonObject body = JsonParser.parseString("""
                {
                  "state_machines": [ { "id": "reload_hint_mod:panel", "layers": [] } ],
                  "blocks": [ { "id": "reload_hint_block", "type": "simple_block" } ]
                }
                """).getAsJsonObject();

        var collected = JsonTreeBuilder.parseContentBody(MOD, "test:mixed.json", body);

        assertEquals(1, JsonTreeBuilder.getLoadingErrors(MOD).size(),
                "only the reload-domain field is unsupported");
        assertEquals(1, collected.getOrDefault("blocks", List.of()).size(),
                "the sibling registration field must still dispatch (half-apply): " + collected);
    }
}
