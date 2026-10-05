package lib.kasuga.rendering.models.mc.dynamic.fsm;

import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The file-shape contract of {@link AnimationClipLoader}: {@code {"animation_clips": [ <clip>, ... ]}},
 * decoded with the existing {@link AnimationClip#CODEC}. Locks the two failure levels apart — a shape
 * violation rejects the file, a malformed element drops only itself — plus the two properties the
 * orchestrator relies on: the clip's own {@code id} field is the id (nothing is extracted), and keys the
 * record does not name are ignored by the codec.
 */
class AnimationClipLoaderTest {

    private static final String WHEEL_ELEMENT = """
            { "id": "kasuga_lib:wheel_spin",
              "duration_seconds": 2.5,
              "bones": [ { "bone": "wheel_r", "keyframes": [
                  { "time": 0.0, "transform": { "rotate": [0, 0, 0] } },
                  { "time": 1.0, "transform": { "rotate": [0, 360, 0] }, "easing": "ease_in_out_cubic" } ] } ] }
            """;

    private static AnimationClipLoader.DecodedFile decode(String json) {
        return AnimationClipLoader.decodeFile(JsonParser.parseString(json));
    }

    private static String clipsFile(String... elements) {
        return "{ \"animation_clips\": [ " + String.join(", ", elements) + " ] }";
    }

    @Test
    void decodesTheWrappedClipArray() {
        AnimationClipLoader.DecodedFile decoded = decode(clipsFile(WHEEL_ELEMENT));

        assertEquals(List.of(), decoded.errors(), "a well-formed file reports nothing");
        assertEquals(1, decoded.clips().size());
        AnimationClip clip = decoded.clips().get(0);
        assertEquals(Id.parse("kasuga_lib:wheel_spin"), clip.id(), "the element's own id field is the clip id");
        assertEquals(2.5f, clip.durationSeconds(), 1e-4f);
        assertEquals(1, clip.bones().size());
        assertEquals("wheel_r", clip.bones().get(0).bone());
        assertEquals(2, clip.bones().get(0).keyframes().size());
    }

    /** An empty array is a valid file: it declares the key and loads no clip. */
    @Test
    void emptyClipArrayIsValid() {
        AnimationClipLoader.DecodedFile decoded = decode(clipsFile());

        assertEquals(List.of(), decoded.errors());
        assertEquals(List.of(), decoded.clips());
    }

    @Test
    void rejectsANonObjectBody() {
        AnimationClipLoader.DecodedFile decoded = decode("[]");

        assertEquals(List.of(), decoded.clips());
        assertEquals(1, decoded.errors().size());
        assertTrue(decoded.errors().get(0).contains(AnimationClipLoader.FIELD_ANIMATION_CLIPS),
                "the diagnostic must quote the expected shape: " + decoded.errors());
    }

    @Test
    void rejectsAMissingTopLevelKey() {
        AnimationClipLoader.DecodedFile decoded = decode("{ \"state_machines\": [] }");

        assertEquals(List.of(), decoded.clips());
        assertEquals(1, decoded.errors().size());
        assertTrue(decoded.errors().get(0).contains("missing top-level key"),
                "a file without the key must be rejected as a whole: " + decoded.errors());
    }

    /** A file may carry another type's key alongside this one: it is simply not this decoder's concern. */
    @Test
    void ignoresAnExtraTopLevelKey() {
        AnimationClipLoader.DecodedFile decoded = decode(
                "{ \"animation_clips\": [], \"state_machines\": [] }");

        assertEquals(List.of(), decoded.clips());
        assertTrue(decoded.errors().isEmpty(),
                "an unrelated top-level key must not be reported by this decoder: " + decoded.errors());
    }

    @Test
    void rejectsANonArrayValue() {
        AnimationClipLoader.DecodedFile decoded = decode("{ \"animation_clips\": {} }");

        assertEquals(List.of(), decoded.clips());
        assertEquals(1, decoded.errors().size());
        assertTrue(decoded.errors().get(0).contains("must be an array"),
                "the value must be an array: " + decoded.errors());
    }

    /** A malformed element drops only itself; its siblings still load (entry-level isolation). */
    @Test
    void skipsAMalformedElementAndKeepsItsSiblings() {
        AnimationClipLoader.DecodedFile decoded = decode(clipsFile(
                "{ \"id\": \"kasuga_lib:first\" }", "42", "{ \"id\": \"kasuga_lib:third\" }"));

        assertEquals(List.of(Id.parse("kasuga_lib:first"), Id.parse("kasuga_lib:third")),
                decoded.clips().stream().map(AnimationClip::id).toList());
        assertEquals(1, decoded.errors().size());
        assertTrue(decoded.errors().get(0).contains("animation_clips[1]"),
                "the diagnostic must point at the offending index: " + decoded.errors());
    }

    /** The clip id is required — it is what {@code states[].clip} resolves against. */
    @Test
    void skipsAnElementWithoutAnId() {
        AnimationClipLoader.DecodedFile decoded = decode(clipsFile("{ \"duration_seconds\": 1.0 }"));

        assertEquals(List.of(), decoded.clips());
        assertEquals(1, decoded.errors().size());
        assertTrue(decoded.errors().get(0).contains("animation_clips[0]")
                        && decoded.errors().get(0).contains("element skipped"),
                "a missing id must skip that element with a diagnostic: " + decoded.errors());
    }

    /**
     * Keys the {@link AnimationClip} record does not name are ignored by the codec, so an author may
     * annotate an element (comment, note, editor metadata) without breaking the load.
     */
    @Test
    void ignoresKeysTheClipRecordDoesNotName() {
        AnimationClipLoader.DecodedFile decoded = decode(clipsFile("""
                { "id": "kasuga_lib:wheel_spin",
                  "_comment": "kept for the editor",
                  "note": "spins the wheel",
                  "duration_seconds": 1.5 }
                """));

        assertEquals(List.of(), decoded.errors(), "an unknown element key is not an error: " + decoded.errors());
        assertEquals(1, decoded.clips().size());
        assertEquals(1.5f, decoded.clips().get(0).durationSeconds(), 1e-4f);
    }
}
