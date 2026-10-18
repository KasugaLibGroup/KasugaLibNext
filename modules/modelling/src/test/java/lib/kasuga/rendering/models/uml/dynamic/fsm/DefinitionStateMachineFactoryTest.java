package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.function.FsmFunctionLibrary;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Data-driven {@link DefinitionStateMachineFactory}: layer registration, pose flushing, apply modes, tick behavior. */
class DefinitionStateMachineFactoryTest {

    /** Two layers: base "locomotion" (idle↔walk, crossfade, when-complete) and override "upper_body" (trigger). */
    private static final String MAIN_JSON = """
            {
              "id": "test:factory",
              "state_vars": [
                { "name": "attack", "type": "bool", "default": false, "ephemeral": true }
              ],
              "layers": [
                {
                  "id": "locomotion",
                  "mode": "base",
                  "weight": 0.5,
                  "bone_mask": "arm_l",
                  "initial_state": "idle",
                  "states": [
                    {
                      "id": "idle",
                      "duration_ticks": 2,
                      "pose": { "bones": [
                        { "name": "arm_l", "transform": { "translate": [0, 1, 0] }, "mode": "add" },
                        { "name": "head", "transform": { "translate": [0, 1, 0] } }
                      ] }
                    },
                    { "id": "walk", "duration_ticks": 3, "pose": { "bones": [
                        { "name": "arm_l", "transform": { "scale": [2, 2, 2] }, "mode": "multiply" }
                    ] } }
                  ],
                  "transitions": [
                    { "id": "idle_to_walk", "from": "idle", "to": "walk", "when_complete": true, "cross_fade_seconds": 0.25 },
                    { "id": "walk_to_idle", "from": "walk", "to": "idle", "when_complete": true }
                  ]
                },
                {
                  "id": "upper_body",
                  "mode": "override",
                  "initial_state": "none",
                  "states": [
                    { "id": "none", "pose": { "bones": [
                        { "name": "arm_r", "transform": { "translate": [0, 2, 0] }, "mode": "replace" }
                    ] } },
                    { "id": "attack", "duration_ticks": 1 }
                  ],
                  "transitions": [
                    { "id": "none_to_attack", "from": "none", "to": "attack", "trigger_on": "attack" }
                  ]
                }
              ]
            }
            """;

    static final class RecordingSink implements PoseSink {
        Blender last;
        @Override
        public void apply(Blender blender) {
            last = blender;
        }
    }

    private static StateMachineDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        DataResult<StateMachineDefinition> result = StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element);
        return result.resultOrPartial(error -> {
            throw new AssertionError("decode failed: " + error);
        }).orElseThrow();
    }

    private static StateMachine<Object> build(String json, FsmFunctionLibrary library, PoseSink sink, StateVarRegistry stateVars) {
        return new DefinitionStateMachineFactory<Object>(library, stateVars).build(new Object(), decode(json), sink);
    }

    private static StateMachine<Object> build(String json, FsmFunctionLibrary library, PoseSink sink) {
        return build(json, library, sink, new StateVarRegistry());
    }

    @Test
    void builderIndexesLayersById() {
        StateMachine<Object> machine = build(MAIN_JSON, new FsmFunctionLibrary(), null);
        assertNotNull(machine.layerOrNull("locomotion"));
        assertNotNull(machine.layerOrNull("upper_body"));
        assertSame(machine.layer("locomotion"), machine.layerOrNull("locomotion"));
        assertNull(machine.layerOrNull("unknown"));
    }

    @Test
    void externalLayersAddFoundByFallbackScan() {
        StateMachine<Object> machine = build(MAIN_JSON, new FsmFunctionLibrary(), null);
        Layer<Object> external = new Layer<>("external");
        machine.layers().add(external);
        assertSame(external, machine.layerOrNull("external"));
        assertSame(external, machine.layer("external"));
    }

    @Test
    void initialStatesAndBlendProps() {
        StateMachine<Object> machine = build(MAIN_JSON, new FsmFunctionLibrary(), null);
        assertEquals("idle", machine.layer("locomotion").active().id());
        assertEquals("none", machine.layer("upper_body").active().id());
        assertEquals(BlendMode.BASE, machine.layer("locomotion").mode());
        assertEquals(BlendMode.OVERRIDE, machine.layer("upper_body").mode());
        assertEquals(0.5f, machine.layer("locomotion").weight(), 1e-4f);
        assertTrue(machine.layer("locomotion").boneMask().matches("arm_l"));
        assertFalse(machine.layer("locomotion").boneMask().matches("head"));
    }

    @Test
    void whenCompleteThenCrossfadeAdvances() {
        StateMachine<Object> machine = build(MAIN_JSON, new FsmFunctionLibrary(), null);
        machine.tick();
        machine.tick();
        assertEquals("idle", machine.layer("locomotion").active().id());
        machine.tick(); // idle (duration 2) completes -> crossfade starts
        assertNotNull(machine.layer("locomotion").activeTransition());
        for (int i = 0; i < 10 && !"walk".equals(machine.layer("locomotion").active().id()); i++) {
            machine.tick();
        }
        assertEquals("walk", machine.layer("locomotion").active().id());
        assertNull(machine.layer("locomotion").activeTransition());
    }

    @Test
    void triggerOnFiresTransition() {
        StateVarRegistry stateVars = new StateVarRegistry();
        StateMachine<Object> machine = build(MAIN_JSON, new FsmFunctionLibrary(), null, stateVars);
        machine.tick();
        assertEquals("none", machine.layer("upper_body").active().id());

        // the declared ephemeral "attack" var is registered under <machine.id>/<name>
        StateVar<?> resolved = stateVars.resolve("test:factory/attack");
        assertNotNull(resolved, "declared state var must be registered by the factory");
        assertEquals(Boolean.class, resolved.type());
        @SuppressWarnings("unchecked")
        StateVar<Boolean> attack = (StateVar<Boolean>) resolved;

        machine.trigger(attack);
        machine.tick();
        assertEquals("attack", machine.layer("upper_body").active().id());
    }

    @Test
    void poseAndBoneModesFlushedThroughSink() {
        RecordingSink sink = new RecordingSink();
        StateMachine<Object> machine = build(MAIN_JSON, new FsmFunctionLibrary(), sink);
        assertTrue(machine.isClientSide());
        machine.tick();
        assertNotNull(sink.last);
        // base layer writes arm_l with its own mode (ADD); mask excludes "head"
        Blender.BoneAccum armL = sink.last.bones().get("arm_l");
        assertNotNull(armL);
        assertEquals(ApplyMode.ADD, armL.mode);
        assertEquals(1f, armL.base.getPosition().y, 1e-4f);
        assertFalse(sink.last.bones().containsKey("head"));
        // override layer writes arm_r with REPLACE (override wins)
        Blender.BoneAccum armR = sink.last.bones().get("arm_r");
        assertNotNull(armR);
        assertTrue(armR.hasOverride);
        assertEquals(ApplyMode.REPLACE, armR.mode);
        assertEquals(2f, armR.override.getPosition().y, 1e-4f);
    }

    @Test
    void multiplyModeSurvivesIntoBoneAccum() {
        RecordingSink sink = new RecordingSink();
        StateMachine<Object> machine = build(MAIN_JSON, new FsmFunctionLibrary(), sink);
        // walk state's arm_l is multiply
        machine.tick();
        machine.tick();
        machine.tick();
        for (int i = 0; i < 10 && !"walk".equals(machine.layer("locomotion").active().id()); i++) {
            machine.tick();
        }
        assertEquals("walk", machine.layer("locomotion").active().id());
        assertNotNull(sink.last);
        Blender.BoneAccum armL = sink.last.bones().get("arm_l");
        assertNotNull(armL);
        assertEquals(ApplyMode.MULTIPLY, armL.mode);
    }

    @Test
    void whenGuardRegisteredInLibraryFires() {
        FsmFunctionLibrary library = new FsmFunctionLibrary();
        library.registerCondition(Id.fromNamespaceAndPath("test", "always"), ctx -> true);
        StateMachine<Object> machine = build("""
                { "id": "test:guard", "layers": [ {
                    "id": "g", "initial_state": "a",
                    "states": [ { "id": "a" }, { "id": "b" } ],
                    "transitions": [ { "id": "t", "from": "a", "to": "b", "when": ["test:always"] } ]
                } ] }
                """, library, null);
        machine.tick();
        assertEquals("b", machine.layer("g").active().id());
    }

    @Test
    void missingReferencesDegradeNotThrow() {
        // missing on_enter action + missing when condition: build and tick must not throw;
        // the missing guard degrades to false so the transition never fires
        StateMachine<Object> machine = build("""
                { "id": "test:missing", "layers": [ {
                    "id": "g", "initial_state": "a",
                    "states": [ { "id": "a", "on_enter": ["test:missing_action"] }, { "id": "b" } ],
                    "transitions": [ { "id": "t", "from": "a", "to": "b", "when": ["test:missing_cond"] } ]
                } ] }
                """, new FsmFunctionLibrary(), null);
        for (int i = 0; i < 5; i++) {
            machine.tick();
        }
        assertEquals("a", machine.layer("g").active().id());
    }
}
