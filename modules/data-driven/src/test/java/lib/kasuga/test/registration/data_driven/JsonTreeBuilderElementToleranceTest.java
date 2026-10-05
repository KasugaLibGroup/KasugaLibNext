package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.TypeHandlerRegistry;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.BuildContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * B1: element-level parse tolerance of the registration dispatcher. A handler's {@code parse} that
 * throws on one element must only drop that element — its siblings still parse — and the failure must
 * be reported as a paired log + loading error naming the element's position. This also locks
 * {@link JsonTreeBuilder#parseContentBody}'s documented "reported …, never thrown" contract, which
 * previously did not hold: a throwing {@code parse} escaped the dispatcher.
 *
 * <p>Pure JVM: the handler is registered directly into {@link TypeHandlerRegistry} under a unique type
 * name, and no {@code ModList} or mod jar is involved.
 */
class JsonTreeBuilderElementToleranceTest {

    private static final String MOD = "element_tolerance_mod";

    /** A unique type name, so registering the probe cannot disturb another test's handler. */
    private static final String TYPE = "tolerance_probe_type";

    @AfterEach
    void clearBuckets() {
        JsonTreeBuilder.clearLoadingErrors();
    }

    @Test
    void aThrowingParseSkipsOnlyItsElementAndIsReported() {
        TypeHandlerRegistry.register(new ProbeHandler());

        String body = "{ \"" + TYPE + "\": [ "
                + "{ \"id\": \"a\" }, "
                + "{ \"id\": \"b\", \"explode\": true }, "
                + "{ \"id\": \"c\" } ] }";

        Map<String, List<Object>> parsed = assertDoesNotThrow(
                () -> JsonTreeBuilder.parseContentBody(MOD, "probe.json", JsonParser.parseString(body).getAsJsonObject()),
                "a throwing parse must be reported, never propagated (B1)");

        assertEquals(2, parsed.get(TYPE).size(), "the two well-behaved elements must still be collected");
        assertEquals("a", ((ProbeDef) parsed.get(TYPE).get(0)).id());
        assertEquals("c", ((ProbeDef) parsed.get(TYPE).get(1)).id());

        List<Throwable> errors = JsonTreeBuilder.getLoadingErrors(MOD);
        assertEquals(1, errors.size(), "the failed element must produce exactly one diagnostic: " + errors);
        String message = errors.get(0).getMessage();
        assertTrue(message.contains("entry 1"), "the diagnostic must point at the offending index: " + message);
        assertTrue(message.contains("'" + TYPE + "'"), "the diagnostic must name the field: " + message);
        assertTrue(message.contains("failed to parse"), "the diagnostic must say the element failed: " + message);
        assertTrue(message.contains("entry skipped"), "the diagnostic must say the element was skipped: " + message);
        assertTrue(message.contains("boom-parse"), "the diagnostic must carry the failure summary: " + message);
    }

    /** A one-element file whose element throws still parses to an empty result instead of escaping. */
    @Test
    void aWholeFileOfFailingElementsParsesToNothing() {
        TypeHandlerRegistry.register(new ProbeHandler());

        String body = "{ \"" + TYPE + "\": [ { \"id\": \"a\", \"explode\": true } ] }";

        Map<String, List<Object>> parsed = assertDoesNotThrow(
                () -> JsonTreeBuilder.parseContentBody(MOD, "probe.json", JsonParser.parseString(body).getAsJsonObject()));

        assertEquals(List.of(), parsed.getOrDefault(TYPE, List.of()), "the failed element contributes nothing");
        assertEquals(1, JsonTreeBuilder.getLoadingErrors(MOD).size());
    }

    /** The probe's parsed shape: only an id, so the collected element is identifiable. */
    record ProbeDef(String id) {}

    /** A handler whose {@code parse} throws when the element carries {@code "explode": true}. */
    private static final class ProbeHandler implements TypeHandler<ProbeDef> {
        @Override
        public String getTypeName() {
            return TYPE;
        }

        @Override
        public int getPhase() {
            return PHASE_CONTENT;
        }

        @Override
        public ProbeDef parse(JsonObject json) {
            if (json.has("explode")) {
                throw new IllegalStateException("boom-parse");
            }
            return new ProbeDef(json.get("id").getAsString());
        }

        @Override
        public void apply(ProbeDef definition, BuildContext context) {
            // not exercised: parseContentBody only parses and collects
        }
    }
}
