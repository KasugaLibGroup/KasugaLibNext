package lib.kasuga.test.registration.data_driven;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.DefinitionStateMachineFactory;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * End-to-end FSM definition: a realistic multi-layer definition (BASE + OVERRIDE) carrying an inline
 * ephemeral trigger var and a {@code trigger_on} transition decodes via the production codec, builds via
 * {@link DefinitionStateMachineFactory}, and the two layers run in parallel — the trigger fires the OVERRIDE
 * layer independently of the BASE layer's {@code when_complete} progression. Mirrors how a shipper authors a
 * multi-layer data-driven FSM, asserted here against a shipped-shape multi-layer payload.
 *
 * <p>This lives in modelling's own test source set (the UML FSM lives in render-core, a modelling
 * dependency) because data-driven no longer depends on modelling.
 */
class DataDrivenFsmDefinitionTest {

    private static final String MULTI_JSON = """
            { "id": "kasuga_lib:dd_multi",
              "state_vars": [ { "name": "poke", "type": "bool", "default": false, "ephemeral": true } ],
              "layers": [
                { "id": "base", "mode": "base", "initial_state": "idle",
                  "states": [ { "id": "idle", "duration_ticks": 2 },
                               { "id": "run",  "duration_ticks": 5 } ],
                  "transitions": [ { "id": "i2r", "from": "idle", "to": "run", "when_complete": true } ] },
                { "id": "fx", "mode": "override", "initial_state": "off",
                  "states": [ { "id": "off" }, { "id": "flash", "duration_ticks": 1 } ],
                  "transitions": [ { "id": "o2f", "from": "off", "to": "flash", "trigger_on": "poke" } ] }
              ] }
            """;

    private static StateMachine<Object> build() {
        StateMachineDefinition def = StateMachineDefinition.CODEC
                .decode(JsonOps.INSTANCE, JsonParser.parseString(MULTI_JSON))
                .resultOrPartial(error -> { throw new AssertionError("decode failed: " + error); })
                .orElseThrow().getFirst();
        return new DefinitionStateMachineFactory<Object>(FsmRegistries.GLOBAL.functions())
                .build(new Object(), def, null);
    }

    @Test
    void twoLayersRunInParallelTriggerFiresOverrideOnly() {
        StateMachine<Object> m = build();
        assertEquals("idle", m.activeStateId("base"));
        assertEquals("off", m.activeStateId("fx"));

        // base progresses on when_complete; fx stays put without the trigger
        m.tick(); m.tick(); m.tick();   // idle(2) -> run (entry tick counts as elapsed 1, so 3 ticks to fire)
        assertEquals("run", m.activeStateId("base"));
        assertEquals("off", m.activeStateId("fx"), "fx must not move without the trigger");

        StateVar<?> poke = FsmRegistries.GLOBAL.vars().resolve("kasuga_lib:dd_multi/poke");
        assertNotNull(poke, "the inline ephemeral trigger var must be registered by the factory");
        @SuppressWarnings("unchecked") StateVar<Boolean> trigger = (StateVar<Boolean>) poke;
        m.trigger(trigger);
        m.tick();
        assertEquals("flash", m.activeStateId("fx"), "trigger_on fires the override transition independently");
        assertEquals("run", m.activeStateId("base"), "base must be unaffected by the fx trigger");
    }
}
