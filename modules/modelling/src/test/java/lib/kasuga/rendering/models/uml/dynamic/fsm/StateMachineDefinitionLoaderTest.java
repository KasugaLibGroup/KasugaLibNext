package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.dynamic.fsm.StateMachineDefinitionLoader;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the file-level wrapper contract of {@link StateMachineDefinitionLoader#decodeFile}:
 * a file may carry several definitions, a shape violation rejects the file, and a malformed
 * array element only drops that element.
 *
 * <p>The reload <em>cycle</em> (clear once, both entries, cross-entry last-wins, registration side
 * effects) lives in {@code ReloadOrchestratorTest} — {@link StateMachineDefinitionLoader} is no longer a
 * reload participant, it only decodes a file. {@code decodeFile} is a pure function, so this is a
 * plain JVM test with no {@code ResourceManager}.
 */
class StateMachineDefinitionLoaderTest {

    /** A single wrapper file carrying two distinct definitions. */
    private static final String MULTI_JSON = """
            {
              "state_machines": [
                { "id": "test:good", "layers": [ { "id": "l", "initial_state": "idle",
                  "states": [ { "id": "idle", "duration_ticks": 10 } ] } ] },
                { "id": "test:second", "layers": [ { "id": "m", "initial_state": "idle",
                  "states": [ { "id": "idle", "duration_ticks": 20 } ] } ] }
              ]
            }
            """;

    /** Pre-wrapper shape: the top-level object is a definition, not a {@code state_machines} array. */
    private static final String LEGACY_JSON = """
            { "id": "test:good", "layers": [ { "id": "l", "initial_state": "idle" } ] }
            """;

    /** A number and a definition missing {@code layers} sit between two good elements. */
    private static final String MIXED_ELEMENTS_JSON = """
            {
              "state_machines": [
                { "id": "test:first", "layers": [ { "id": "l", "initial_state": "idle" } ] },
                5,
                { "id": "test:missing_layers" },
                { "id": "test:last", "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ]
            }
            """;

    private static final String EXTRA_KEY_JSON = """
            {
              "state_machines": [
                { "id": "test:good", "layers": [ { "id": "l", "initial_state": "idle" } ] }
              ],
              "extra": 1
            }
            """;

    private static final String NON_ARRAY_JSON = "{ \"state_machines\": {} }";

    private static final String MISSING_KEY_JSON = "{ \"other\": [] }";

    @Test
    void wrapperFileDecodesEveryDefinition() {
        StateMachineDefinitionLoader.DecodedFile decoded = decode(MULTI_JSON);

        assertTrue(decoded.errors().isEmpty(), "a clean file must not report anything: " + decoded.errors());
        assertEquals(2, decoded.definitions().size(), "every wrapper element must decode");
        assertEquals("test:good", decoded.definitions().get(0).id().toString(),
                "definitions keep file order");
        assertEquals("test:second", decoded.definitions().get(1).id().toString());
    }

    @Test
    void legacyShapeIsRejectedAndReportsExpectedShape() {
        StateMachineDefinitionLoader.DecodedFile decoded = decode(LEGACY_JSON);

        assertTrue(decoded.definitions().isEmpty(), "a pre-wrapper file decodes to no definitions");
        assertTrue(decoded.errors().stream().anyMatch(error -> error.contains("state_machines")),
                "the rejection must name the expected wrapper shape, got " + decoded.errors());
    }

    @Test
    void badElementIsSkippedWhileSiblingsDecode() {
        StateMachineDefinitionLoader.DecodedFile decoded = decode(MIXED_ELEMENTS_JSON);

        assertEquals(2, decoded.definitions().size(), "only the two good elements decode");
        assertEquals("test:first", decoded.definitions().get(0).id().toString());
        assertEquals("test:last", decoded.definitions().get(1).id().toString());
        assertTrue(decoded.errors().size() >= 2,
                "each bad element must produce a diagnostic, got " + decoded.errors());
    }

    /** A file carrying another type's key alongside this one is legal: the extra key is not our concern. */
    @Test
    void extraTopLevelKeyDoesNotAffectThisType() {
        StateMachineDefinitionLoader.DecodedFile decoded = decode(EXTRA_KEY_JSON);

        assertEquals(1, decoded.definitions().size(),
                "the state_machines array must still decode when the file carries another key");
        assertTrue(decoded.errors().isEmpty(),
                "an unrelated top-level key is not this type's error: " + decoded.errors());
    }

    @Test
    void nonArrayValueRejectsWholeFile() {
        StateMachineDefinitionLoader.DecodedFile decoded = decode(NON_ARRAY_JSON);

        assertTrue(decoded.definitions().isEmpty());
        assertTrue(decoded.errors().stream().anyMatch(error -> error.contains("array")),
                "the diagnostic must mention the array requirement, got " + decoded.errors());
    }

    @Test
    void missingKeyRejectsWholeFile() {
        StateMachineDefinitionLoader.DecodedFile decoded = decode(MISSING_KEY_JSON);

        assertTrue(decoded.definitions().isEmpty());
        assertTrue(decoded.errors().stream().anyMatch(error -> error.contains("state_machines")),
                "the diagnostic must name the missing key, got " + decoded.errors());
    }

    /** A null body is what an empty resource yields; it must be reported, not thrown on. */
    @Test
    void nullBodyIsRejectedWithADiagnostic() {
        StateMachineDefinitionLoader.DecodedFile decoded = StateMachineDefinitionLoader.decodeFile(null);

        assertTrue(decoded.definitions().isEmpty());
        assertFalse(decoded.errors().isEmpty(), "a null body must produce a diagnostic");
    }

    private static StateMachineDefinitionLoader.DecodedFile decode(String json) {
        return StateMachineDefinitionLoader.decodeFile(JsonParser.parseString(json));
    }
}
