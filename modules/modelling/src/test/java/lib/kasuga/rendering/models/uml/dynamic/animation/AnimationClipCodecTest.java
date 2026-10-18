package lib.kasuga.rendering.models.uml.dynamic.animation;

import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** AnimationClip CODEC round-trip and JSON decoding tests (moved off the deleted AnimationClipPoseDriverTest). */
class AnimationClipCodecTest {

    private static final float EPS = 1e-3f;

    private static AnimationClip wheelClip() {
        return new AnimationClip(Id.parse("kasuga_lib:wheel"), 1f,
                List.of(new AnimationClip.BoneTrack("root",
                        List.of(
                                new AnimationClip.Keyframe(0f,
                                        new TransformDefinition(new Vector3f(), new Vector3f(0f, 0f, 0f), new Vector3f(1f, 1f, 1f)),
                                        null),
                                new AnimationClip.Keyframe(1f,
                                        new TransformDefinition(new Vector3f(), new Vector3f(0f, 180f, 0f), new Vector3f(1f, 1f, 1f)),
                                        null)))),
                List.of(), List.of(), List.of());
    }

    @Test
    void codecRoundTrip() {
        AnimationClip original = wheelClip();
        AnimationClip decoded = AnimationClip.CODEC.decode(JsonOps.INSTANCE,
                AnimationClip.CODEC.encodeStart(JsonOps.INSTANCE, original).result().orElseThrow())
                .resultOrPartial(error -> {
                    throw new AssertionError(error);
                }).orElseThrow().getFirst();
        assertEquals(original, decoded);
    }

    @Test
    void codecDecodesFromJson() {
        com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString("""
                {
                  "id": "kasuga_lib:wheel",
                  "duration_seconds": 2.0,
                  "bones": [
                    { "bone": "root", "keyframes": [
                      { "time": 0.0, "transform": { "rotate": [0, 0, 0] }, "easing": "linear" },
                      { "time": 1.0, "transform": { "rotate": [0, 180, 0] }, "easing": "ease_in_out_cubic" }
                    ]}
                  ]
                }
                """);
        AnimationClip clip = AnimationClip.CODEC.decode(JsonOps.INSTANCE, json).resultOrPartial(error -> {
            throw new AssertionError(error);
        }).orElseThrow().getFirst();
        assertEquals(Id.parse("kasuga_lib:wheel"), clip.id());
        assertEquals(2f, clip.durationSeconds(), EPS);
        assertEquals(1, clip.bones().size());
        assertEquals(2, clip.bones().get(0).keyframes().size());
        assertEquals("ease_in_out_cubic", AnimationClip.EASING_CODEC.encodeStart(JsonOps.INSTANCE,
                clip.bones().get(0).keyframes().get(1).easing()).result().orElseThrow().getAsString());
    }
}