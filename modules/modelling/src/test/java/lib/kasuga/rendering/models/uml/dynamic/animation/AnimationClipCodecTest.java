package lib.kasuga.rendering.models.uml.dynamic.animation;

import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void codecDecodesFormulaVariablesAndUnifiedTransforms() {
        com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString("""
                {
                  "id": "kasuga_lib:formula_transforms",
                  "variables": [
                    { "name": "derived.angle", "expression": "rad(query.wheel_angle)" }
                  ],
                  "formula_transforms": [
                    {
                      "target": { "part": "crank" },
                      "transform": {
                        "translate": { "z": "sin(derived.angle)" },
                        "rotate": { "x": "-query.wheel_angle", "y": "180" },
                        "scale": { "y": "1.5" }
                      }
                    },
                    {
                      "target": { "part": "crank", "bone": "pin" },
                      "transform": { "rotate": { "z": "query.pin_angle" } }
                    }
                  ]
                }
                """);
        AnimationClip clip = AnimationClip.CODEC.decode(JsonOps.INSTANCE, json).resultOrPartial(error -> {
            throw new AssertionError(error);
        }).orElseThrow().getFirst();

        assertEquals(1, clip.variables().size());
        assertEquals("derived.angle", clip.variables().getFirst().name());
        assertEquals(2, clip.formulaTransforms().size());
        AnimationClip.FormulaTransformTrack partTrack = clip.formulaTransforms().getFirst();
        assertEquals("crank", partTrack.target().part());
        assertNull(partTrack.target().bone());
        assertEquals("sin(derived.angle)", partTrack.transform().translate().z());
        assertEquals("180", partTrack.transform().rotate().y());
        assertEquals("1.5", partTrack.transform().scale().y());
        AnimationClip.FormulaTransformTrack boneTrack = clip.formulaTransforms().get(1);
        assertEquals("crank", boneTrack.target().part());
        assertEquals("pin", boneTrack.target().bone());
        assertEquals("query.pin_angle", boneTrack.transform().rotate().z());
    }

    @Test
    void formulaTransformClipRoundTrips() {
        AnimationClip original = new AnimationClip(
                Id.parse("kasuga_lib:formula_transform_round_trip"),
                1f,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(new AnimationClip.FormulaVariable(
                        "mechanism.angle",
                        "rad(query.wheel_angle)"
                )),
                List.of(AnimationClip.FormulaTransformTrack.part(
                        "crank",
                        new AnimationClip.FormulaTransform(
                                new AnimationClip.FormulaVector("", "", "sin(mechanism.angle)"),
                                new AnimationClip.FormulaVector("-query.wheel_angle", "180", ""),
                                AnimationClip.FormulaVector.EMPTY
                        )
                ))
        );

        AnimationClip decoded = AnimationClip.CODEC.decode(
                        JsonOps.INSTANCE,
                        AnimationClip.CODEC.encodeStart(JsonOps.INSTANCE, original)
                                .result()
                                .orElseThrow()
                )
                .resultOrPartial(error -> {
                    throw new AssertionError(error);
                })
                .orElseThrow()
                .getFirst();

        assertEquals(original, decoded);
    }

    @Test
    void codecRejectsDuplicateFormulaTargets() {
        com.google.gson.JsonElement json = com.google.gson.JsonParser.parseString("""
                {
                  "id": "kasuga_lib:duplicate_targets",
                  "formula_transforms": [
                    { "target": { "part": "crank" } },
                    { "target": { "part": "crank" } }
                  ]
                }
                """);
        var result = AnimationClip.CODEC.decode(JsonOps.INSTANCE, json);

        assertTrue(result.error().isPresent());
    }
}
