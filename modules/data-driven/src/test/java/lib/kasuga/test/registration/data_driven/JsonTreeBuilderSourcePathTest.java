package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Table test for {@link JsonTreeBuilder#validateSourcePath(String)}, the pure validation applied to
 * every content-file reference listed in an index manifest's {@code sources} array.
 *
 * <p>Each malformed shape maps to a distinct, deterministic reason so that a caller (and the
 * per-mod loading errors) can point at exactly what is wrong.
 */
class JsonTreeBuilderSourcePathTest {

    @Test
    void rejectsMalformedSourcePaths() {
        assertInvalid(null, "path is empty");
        assertInvalid("", "path is empty");
        assertInvalid("   ", "path is empty");
        assertInvalid("/blocks.json", "path must be relative (remove the leading '/')");
        assertInvalid("blocks", "path must include the '.json' suffix");
        assertInvalid("a//b.json", "path contains an empty segment");
        assertInvalid("./blocks.json", "path must not contain '.' or '..'");
        assertInvalid("a/../b.json", "path must not contain '.' or '..'");
    }

    @Test
    void acceptsWellFormedRelativeJsonPaths() {
        assertNull(JsonTreeBuilder.validateSourcePath("blocks.json"));
        assertNull(JsonTreeBuilder.validateSourcePath("carriages/m1/blocks.json"));
        // A '.' inside a segment is fine; only whole '.'/'..' segments are rejected.
        assertNull(JsonTreeBuilder.validateSourcePath("a.b/blocks.v2.json"));
    }

    private static void assertInvalid(String path, String expectedReason) {
        assertEquals(expectedReason, JsonTreeBuilder.validateSourcePath(path),
                "validateSourcePath(" + path + ")");
    }
}
