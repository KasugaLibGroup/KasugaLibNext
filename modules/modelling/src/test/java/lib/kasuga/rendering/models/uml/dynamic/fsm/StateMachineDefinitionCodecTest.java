package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.LayerDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.PoseDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransformDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.TransitionDefinition;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JSON decode/encode round-trips of {@link StateMachineDefinition} and its nested codecs. */
class StateMachineDefinitionCodecTest {

    private static final String FULL_JSON = """
            {
              "id": "test:panel",
              "layers": [
                {
                  "id": "locomotion",
                  "mode": "base",
                  "weight": 0.8,
                  "bone_mask": "arm_l, arm_r",
                  "initial_state": "idle",
                  "states": [
                    {
                      "id": "idle",
                      "duration_ticks": 60,
                      "on_enter": ["test:enter"],
                      "pose": {
                        "morphs": { "blink": 0.5 },
                        "bones": [
                          {
                            "name": "arm_r",
                            "transform": { "translate": [0, 1, 0], "rotate": [0, 90, 0], "scale": [2, 1, 1] },
                            "mode": "multiply"
                          }
                        ],
                        "frames": [ { "material": "test:mat", "frame": 3 } ]
                      }
                    }
                  ],
                  "transitions": [
                    {
                      "id": "t",
                      "from": "idle",
                      "to": "idle",
                      "when": ["test:cond"],
                      "trigger_on": "go",
                      "when_complete": true,
                      "cross_fade_seconds": 0.25,
                      "on_fire": ["test:fire"]
                    }
                  ]
                }
              ]
            }
            """;

    private static StateMachineDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        DataResult<StateMachineDefinition> result = StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element);
        return result.resultOrPartial(error -> {
            throw new AssertionError("decode failed: " + error);
        }).orElseThrow();
    }

    @Test
    void decodesFullJson() {
        StateMachineDefinition def = decode(FULL_JSON);
        assertEquals("test:panel", def.id().toString());

        LayerDefinition layer = def.layers().get(0);
        assertEquals("locomotion", layer.id());
        assertEquals(BlendMode.BASE, layer.mode());
        assertEquals(0.8f, layer.weight(), 1e-4f);
        assertTrue(layer.resolvedMask().matches("arm_l"));
        assertTrue(layer.resolvedMask().matches("arm_r"));
        assertFalse(layer.resolvedMask().matches("head"));

        StateDefinition state = layer.states().get(0);
        assertEquals("idle", state.id());
        assertEquals(60, state.durationTicks().orElse(-1));
        assertEquals("test:enter", state.onEnter().get(0).toString());

        PoseDefinition pose = state.pose();
        assertEquals(0.5f, pose.morphs().get("blink"), 1e-4f);
        PoseDefinition.BoneDefinition bone = pose.bones().get(0);
        assertEquals("arm_r", bone.name());
        assertEquals("multiply", bone.mode());
        TransformDefinition transform = bone.transform();
        assertEquals(1f, transform.translate().y, 1e-4f);
        assertEquals(90f, transform.rotate().y, 1e-4f);
        assertEquals(2f, transform.scale().x, 1e-4f);
        assertEquals(3, pose.frames().get(0).frame());

        TransitionDefinition transition = layer.transitions().get(0);
        assertEquals("go", transition.triggerOn().orElse(null));
        assertTrue(transition.whenComplete());
        assertEquals(0.25f, transition.crossFadeSeconds(), 1e-4f);
        assertEquals("test:cond", transition.when().get(0).toString());
        assertEquals("test:fire", transition.onFire().get(0).toString());
    }

    @Test
    void appliesDefaultsWhenAbsent() {
        StateMachineDefinition def = decode("""
                { "id": "test:min", "layers": [ { "id": "l", "initial_state": "s", "states": [ { "id": "s" } ] } ] }
                """);
        LayerDefinition layer = def.layers().get(0);
        assertEquals(BlendMode.BASE, layer.mode());
        assertEquals(1f, layer.weight(), 1e-4f);
        assertTrue(layer.resolvedMask().matches("anything"));
        StateDefinition state = layer.states().get(0);
        assertTrue(state.durationTicks().isEmpty());
        assertTrue(state.onEnter().isEmpty());
        assertTrue(state.pose().morphs().isEmpty());
        assertTrue(state.pose().bones().isEmpty());
        assertTrue(state.pose().frames().isEmpty());
        assertTrue(layer.transitions().isEmpty());
    }

    @Test
    void invalidLayerModeIsAnError() {
        JsonElement element = JsonParser.parseString("""
                { "id": "test:bad", "layers": [ { "id": "l", "mode": "garbage", "initial_state": "s", "states": [ { "id": "s" } ] } ] }
                """);
        DataResult<StateMachineDefinition> result = StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element);
        assertTrue(result.error().isPresent());
    }

    @Test
    void invalidBoneModeSilentlyDegradesToReplace() {
        // bone.mode is a plain String (ApplyMode.byName semantics): decode succeeds, lookup degrades
        StateMachineDefinition def = decode("""
                { "id": "test:badmode", "layers": [ { "id": "l", "initial_state": "s", "states": [
                    { "id": "s", "pose": { "bones": [ { "name": "b", "transform": { "scale": [1, 1, 1] }, "mode": "garbage" } ] } }
                ] } ] }
                """);
        PoseDefinition.BoneDefinition bone = def.layers().get(0).states().get(0).pose().bones().get(0);
        assertEquals("garbage", bone.mode());
        assertEquals(ApplyMode.REPLACE, ApplyMode.byName(bone.mode()));
    }

    @Test
    void encodeDecodeRoundTrip() {
        StateMachineDefinition first = decode(FULL_JSON);
        DataResult<JsonElement> encoded = StateMachineDefinition.CODEC.encodeStart(JsonOps.INSTANCE, first);
        JsonElement json = encoded.resultOrPartial(error -> {
            throw new AssertionError("encode failed: " + error);
        }).orElseThrow();
        StateMachineDefinition second = StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, json)
                .resultOrPartial(error -> {
                    throw new AssertionError("round-trip decode failed: " + error);
                }).orElseThrow();
        assertEquals(first, second);
        assertNotNull(second);
    }
}
