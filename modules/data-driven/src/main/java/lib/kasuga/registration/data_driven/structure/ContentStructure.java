package lib.kasuga.registration.data_driven.structure;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The shared <em>structural</em> layer of the content-file parsing pipelines: the file-body check, the
 * unknown top-level key check, and the per-field element split (missing key / non-array value /
 * non-object element). Both parsing domains call it — the registration domain's
 * {@code JsonTreeBuilder} dispatcher and the reload domain's {@code ReloadOrchestrator} — and the
 * reload-domain decoders ({@code StateMachineDefinitionLoader}, {@code AnimationClipLoader}) use
 * {@link #field(JsonElement, String)} and {@link #describe(JsonElement)} for the same shape rules.
 *
 * <p><strong>Judgement only, rendering per side.</strong> This class decides structure and never
 * formats a user-facing message: an {@link Issue} carries the judgement result and the offending
 * value, and each caller renders it with its own template. That keeps the two domains' wording intact
 * — notably {@code ReloadHandler.describeFile()} stays the reload domain's own text contract, and the
 * two sides' cross-domain hints point in opposite directions — while the shape rules themselves live
 * in exactly one place.
 *
 * <p>It is a pure function of its input (only Gson and {@code java.util}), with no Minecraft or
 * loader types, so every rule here is locked down by a plain JVM unit test. It deliberately stops at
 * "structure": it never maps elements to a definition type, which is why each handler's own
 * {@code parse} / {@code decode} still runs on the elements this class returns.
 */
public final class ContentStructure {

    /** The kind of structural problem found in a content file. */
    public enum Kind {
        /** The file body is not a JSON object. */
        BODY_NOT_OBJECT,
        /** The object carries a top-level key the domain does not serve. */
        UNKNOWN_KEY,
        /** A known top-level key is absent from the object. */
        MISSING_KEY,
        /** A known top-level key holds a value that is not a JSON array. */
        VALUE_NOT_ARRAY,
        /** An array element is not a JSON object. */
        ELEMENT_NOT_OBJECT
    }

    /**
     * One structural problem. It is a judgement, not a message: the caller renders the text.
     *
     * @param kind   what is wrong
     * @param key    the top-level key involved, or {@code null} when no single key applies
     *               ({@link Kind#BODY_NOT_OBJECT})
     * @param index  the array index involved, or {@code -1} when no array position applies
     * @param actual the offending value ({@code null} when the body was absent)
     */
    public record Issue(Kind kind, String key, int index, JsonElement actual) {}

    /**
     * The structural split of one top-level field: the elements that are structurally valid, in file
     * order, plus the structural problems found (a missing key, a non-array value, or one issue per
     * non-object element).
     *
     * @param elements structurally valid elements (preserved order); never {@code null}
     * @param issues   the structural problems; never {@code null}
     */
    public record Field(List<Element> elements, List<Issue> issues) {}

    /**
     * One structurally valid element of a top-level field, paired with its true position in the field's
     * array. The position counts every array slot — including the non-object elements that were skipped
     * and reported as issues — so a caller's diagnostic points at the real file position without having
     * to recover it by walking the issue list.
     *
     * @param fileIndex the element's zero-based index in the top-level array
     * @param body      the element object
     */
    public record Element(int fileIndex, JsonObject body) {}

    /**
     * The file-body check: a content file must be a JSON object.
     *
     * @param raw the parsed file body; {@code null} is treated as a non-object
     * @return {@link Kind#BODY_NOT_OBJECT} when {@code raw} is not a JSON object, otherwise {@code null}
     */
    public static Issue body(JsonElement raw) {
        if (raw == null || !raw.isJsonObject()) {
            return new Issue(Kind.BODY_NOT_OBJECT, null, -1, raw);
        }
        return null;
    }

    /**
     * The unknown-top-level-key check: every key not named by {@code knownKeys} is one issue.
     *
     * @param body      the file body; a non-object yields no issues (the body check owns that case)
     * @param knownKeys the top-level keys this domain serves
     * @return one {@link Kind#UNKNOWN_KEY} issue per unrecognised key, in the body's key order
     */
    public static List<Issue> unknownKeys(JsonElement body, Set<String> knownKeys) {
        List<Issue> issues = new ArrayList<>();
        if (body == null || !body.isJsonObject()) {
            return issues;
        }
        JsonObject root = body.getAsJsonObject();
        for (String key : root.keySet()) {
            if (!knownKeys.contains(key)) {
                issues.add(new Issue(Kind.UNKNOWN_KEY, key, -1, root.get(key)));
            }
        }
        return issues;
    }

    /**
     * The structural split of one top-level field. The key must be present and hold an array; each
     * element of that array must be an object. A missing key or a non-array value yields exactly one
     * issue and no elements, so the caller can reject the whole field with a single message; a
     * non-object element yields an issue while its siblings are kept, preserving per-element isolation.
     *
     * @param body    the file body; a non-object yields a {@link Kind#MISSING_KEY} issue (the body check
     *                owns the non-object case and is expected to run first)
     * @param typeKey the top-level key to split
     * @return the valid elements and the structural issues, in file order
     */
    public static Field field(JsonElement body, String typeKey) {
        List<Element> elements = new ArrayList<>();
        List<Issue> issues = new ArrayList<>();
        if (body == null || !body.isJsonObject()) {
            issues.add(new Issue(Kind.MISSING_KEY, typeKey, -1, null));
            return new Field(elements, issues);
        }
        JsonObject root = body.getAsJsonObject();
        if (!root.has(typeKey)) {
            issues.add(new Issue(Kind.MISSING_KEY, typeKey, -1, null));
            return new Field(elements, issues);
        }
        JsonElement value = root.get(typeKey);
        if (!value.isJsonArray()) {
            issues.add(new Issue(Kind.VALUE_NOT_ARRAY, typeKey, -1, value));
            return new Field(elements, issues);
        }
        int index = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            if (element.isJsonObject()) {
                elements.add(new Element(index, element.getAsJsonObject()));
            } else {
                issues.add(new Issue(Kind.ELEMENT_NOT_OBJECT, typeKey, index, element));
            }
            index++;
        }
        return new Field(elements, issues);
    }

    /**
     * Renders an already-formatted list of items as an English disjunction: one item unchanged, and two
     * or more joined with commas and a trailing {@code " or "} ({@code "a"}, {@code "a or b"},
     * {@code "a, b or c"}). Shared by the two cross-domain hints so the "A, B or C" wording has a single
     * implementation instead of one copy per caller. Items are used verbatim — quoting them is the
     * caller's concern.
     *
     * @param items the items to join; must not be empty
     * @return the joined form
     */
    public static String joinWithOr(List<String> items) {
        if (items.size() == 1) {
            return items.get(0);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " or " + items.get(items.size() - 1);
    }

    /**
     * Human-readable JSON kind for diagnostics. This is the single implementation shared by every
     * pipeline (the registration dispatcher, the reload orchestrator and both reload decoders), which
     * previously each carried a byte-identical private copy.
     *
     * @param json the value to describe; {@code null} is described as {@code "null"}
     * @return a short description such as {@code "an array"} or {@code "a primitive (5)"}
     */
    public static String describe(JsonElement json) {
        if (json == null || json.isJsonNull()) {
            return "null";
        }
        if (json.isJsonArray()) {
            return "an array";
        }
        if (json.isJsonObject()) {
            return "an object";
        }
        if (json.isJsonPrimitive()) {
            return "a primitive (" + json + ")";
        }
        return json.toString();
    }

    private ContentStructure() {}
}
