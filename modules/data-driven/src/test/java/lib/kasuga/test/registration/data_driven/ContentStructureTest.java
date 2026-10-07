package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.structure.ContentStructure;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The shared structural layer of the content pipelines, exercised as a pure function. Covers the four
 * primitives every pipeline now routes through: {@code body} (the file-body check), {@code unknownKeys}
 * (the top-level key check), {@code field} (the per-field split: missing key / non-array value /
 * non-object element) and {@code describe} (the shared JSON-kind wording). No Minecraft types, so this
 * is a plain JVM test.
 */
class ContentStructureTest {

    private static JsonElement json(String text) {
        return JsonParser.parseString(text);
    }

    // --- body ---

    @Test
    void bodyAcceptsAJsonObject() {
        assertNull(ContentStructure.body(json("{}")), "an object body is structurally valid");
    }

    @Test
    void bodyRejectsANonObject() {
        assertIssue(ContentStructure.body(json("[]")), ContentStructure.Kind.BODY_NOT_OBJECT, null, -1);
        assertIssue(ContentStructure.body(json("5")), ContentStructure.Kind.BODY_NOT_OBJECT, null, -1);
    }

    @Test
    void bodyTreatsNullAndJsonNullAsNonObject() {
        assertIssue(ContentStructure.body(null), ContentStructure.Kind.BODY_NOT_OBJECT, null, -1);
        assertIssue(ContentStructure.body(json("null")), ContentStructure.Kind.BODY_NOT_OBJECT, null, -1);
    }

    // --- unknownKeys ---

    @Test
    void unknownKeysReturnsOnlyKeysOutsideTheKnownSet() {
        List<ContentStructure.Issue> issues = ContentStructure.unknownKeys(
                json("{ \"blocks\": [], \"typo\": [], \"other\": 1 }"), Set.of("blocks", "items"));

        assertEquals(2, issues.size(), "both unknown keys must be reported");
        assertEquals("typo", issues.get(0).key(), "body key order is preserved");
        assertEquals("other", issues.get(1).key());
        for (ContentStructure.Issue issue : issues) {
            assertEquals(ContentStructure.Kind.UNKNOWN_KEY, issue.kind());
            assertEquals(-1, issue.index());
        }
    }

    @Test
    void unknownKeysIsEmptyWhenEveryKeyIsKnown() {
        assertTrue(ContentStructure.unknownKeys(json("{ \"blocks\": [] }"), Set.of("blocks")).isEmpty());
    }

    @Test
    void unknownKeysYieldsNothingForANonObjectBody() {
        assertTrue(ContentStructure.unknownKeys(json("[]"), Set.of("blocks")).isEmpty(),
                "the body check owns the non-object case");
    }

    // --- field: three branches ---

    @Test
    void fieldSplitsAWellFormedArrayInOrder() {
        ContentStructure.Field field = ContentStructure.field(
                json("{ \"blocks\": [ {\"id\":\"a\"}, {\"id\":\"b\"} ] }"), "blocks");

        assertTrue(field.issues().isEmpty());
        assertEquals(2, field.elements().size());
        assertEquals("a", field.elements().get(0).body().get("id").getAsString());
        assertEquals("b", field.elements().get(1).body().get("id").getAsString());
        assertEquals(0, field.elements().get(0).fileIndex(), "a valid element keeps its array position");
        assertEquals(1, field.elements().get(1).fileIndex());
    }

    @Test
    void fieldReportsAMissingKey() {
        ContentStructure.Field field = ContentStructure.field(json("{ \"items\": [] }"), "blocks");

        assertEquals(1, field.issues().size());
        assertIssue(field.issues().get(0), ContentStructure.Kind.MISSING_KEY, "blocks", -1);
        assertTrue(field.elements().isEmpty());
    }

    @Test
    void fieldReportsANonArrayValue() {
        ContentStructure.Field field = ContentStructure.field(json("{ \"blocks\": {} }"), "blocks");

        assertEquals(1, field.issues().size());
        assertIssue(field.issues().get(0), ContentStructure.Kind.VALUE_NOT_ARRAY, "blocks", -1);
        assertTrue(field.elements().isEmpty(), "a non-array value contributes no elements");
    }

    @Test
    void fieldKeepsValidElementsAndReportsEachInvalidOne() {
        ContentStructure.Field field = ContentStructure.field(
                json("{ \"blocks\": [ {\"id\":\"a\"}, 5, {\"id\":\"c\"} ] }"), "blocks");

        assertEquals(2, field.elements().size(), "the two object elements survive");
        assertEquals("a", field.elements().get(0).body().get("id").getAsString());
        assertEquals("c", field.elements().get(1).body().get("id").getAsString());
        assertEquals(0, field.elements().get(0).fileIndex());
        assertEquals(2, field.elements().get(1).fileIndex(),
                "the file index counts the skipped non-object element, so it stays the real array position");
        assertEquals(1, field.issues().size());
        assertIssue(field.issues().get(0), ContentStructure.Kind.ELEMENT_NOT_OBJECT, "blocks", 1);
    }

    @Test
    void fieldReportsMissingKeyForANonObjectBody() {
        ContentStructure.Field field = ContentStructure.field(json("[]"), "blocks");

        assertIssue(field.issues().get(0), ContentStructure.Kind.MISSING_KEY, "blocks", -1);
    }

    // --- joinWithOr ---

    @Test
    void joinWithOrRendersAnEnglishDisjunction() {
        assertEquals("a", ContentStructure.joinWithOr(List.of("a")));
        assertEquals("a or b", ContentStructure.joinWithOr(List.of("a", "b")));
        assertEquals("a, b or c", ContentStructure.joinWithOr(List.of("a", "b", "c")),
                "two or more items get commas and a trailing ' or '");
    }

    // --- describe ---

    @Test
    void describeNamesEveryJsonKind() {
        assertEquals("null", ContentStructure.describe(null));
        assertEquals("null", ContentStructure.describe(json("null")));
        assertEquals("an array", ContentStructure.describe(json("[]")));
        assertEquals("an object", ContentStructure.describe(json("{}")));
        assertEquals("a primitive (5)", ContentStructure.describe(json("5")));
        assertEquals("a primitive (\"x\")", ContentStructure.describe(json("\"x\"")));
    }

    private static void assertIssue(ContentStructure.Issue issue, ContentStructure.Kind kind,
                                    String key, int index) {
        assertEquals(kind, issue.kind());
        assertEquals(key, issue.key());
        assertEquals(index, issue.index());
    }
}
