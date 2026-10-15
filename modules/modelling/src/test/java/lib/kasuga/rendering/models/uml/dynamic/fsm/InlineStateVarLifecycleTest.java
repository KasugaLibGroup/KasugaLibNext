package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * doc/fsm-design-review.md block 3.3: inline {@code state_vars} must not leak in the registry when their
 * definition is cleared/reloaded. The var is registered (owned by the machine id) when a machine is built;
 * clearing the definition (resource reload / removal) must remove exactly its inline vars from the
 * {@link StateVarRegistry} — wired through the composition root ({@link FsmRegistries#create()}).
 */
class InlineStateVarLifecycleTest {

    private static final String INLINE_JSON = """
            {
              "id": "test:inline",
              "state_vars": [
                { "name": "speed", "type": "float", "default": 0.5 },
                { "name": "attack", "type": "bool", "default": false, "ephemeral": true }
              ],
              "layers": [ {
                "id": "base", "initial_state": "idle",
                "states": [ { "id": "idle" }, { "id": "run" } ],
                "transitions": [ { "id": "i2r", "from": "idle", "to": "run", "when_complete": true } ]
              } ]
            }
            """;

    private static StateMachineDefinition decode() {
        return StateMachineDefinition.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(INLINE_JSON))
                .resultOrPartial(e -> { throw new AssertionError("decode failed: " + e); })
                .orElseThrow().getFirst();
    }

    private static Id varId(String name) {
        return Id.fromNamespaceAndPath("test", "inline/" + name);
    }

    @Test
    void inlineVarsRegisterOnBuildAndCleanOnClear() {
        FsmRegistries registries = FsmRegistries.create();
        StateVarRegistry stateVars = registries.vars();
        StateMachineDefinition def = decode();
        registries.definitions().register(Id.parse("test:inline"), def);

        // before any build, no inline var is registered
        assertNull(stateVars.get(varId("speed")));

        // building registers the inline vars (idempotent across builds — same id re-puts)
        DefinitionStateMachineFactory<Object> factory =
                new DefinitionStateMachineFactory<>(registries.functions(), stateVars);
        factory.build(new Object(), def, null);
        factory.build(new Object(), def, null);
        assertNotNull(stateVars.get(varId("speed")));
        assertNotNull(stateVars.get(varId("attack")));
        assertEquals(2, stateVars.ids().stream().filter(id -> "test".equals(id.getNamespace())
                && id.getPath().startsWith("inline/")).count(), "exactly the two inline vars (re-build must not duplicate)");

        // clearing the definitions must remove the inline vars (no leak across reloads)
        registries.definitions().clearAll();
        assertNull(stateVars.get(varId("speed")));
        assertNull(stateVars.get(varId("attack")));
        assertTrue(stateVars.ids().stream().noneMatch(id -> id.getPath().startsWith("inline/")),
                "clearAll must clean the definition's inline vars");
    }

    @Test
    void removeDefinitionCleansItsInlineVars() {
        FsmRegistries registries = FsmRegistries.create();
        StateVarRegistry stateVars = registries.vars();
        StateMachineDefinition def = decode();
        registries.definitions().register(Id.parse("test:inline"), def);
        new DefinitionStateMachineFactory<Object>(registries.functions(), stateVars).build(new Object(), def, null);
        assertNotNull(stateVars.get(varId("speed")));

        assertTrue(registries.definitions().remove(Id.parse("test:inline")));
        assertFalse(stateVars.ids().stream().anyMatch(id -> id.getPath().startsWith("inline/")),
                "remove must clean the removed definition's inline vars");
    }

    /**
     * Regression (design-review N4): ownership is exact — clearing machine {@code test:a} must not
     * drop the inline vars of the sibling machine {@code test:a/b}, which the old
     * {@code machineId.path + "/"} prefix match used to over-match.
     */
    @Test
    void clearingMachineDoesNotTouchSiblingPathVars() {
        FsmRegistries registries = FsmRegistries.create();
        StateVarRegistry stateVars = registries.vars();
        Id parent = Id.parse("test:a");
        Id sibling = Id.parse("test:a/b");

        DefinitionStateMachineFactory<Object> factory =
                new DefinitionStateMachineFactory<>(registries.functions(), stateVars);
        StateMachineDefinition parentDef = definitionWithInlineVar(parent, "speed");
        StateMachineDefinition siblingDef = definitionWithInlineVar(sibling, "speed");
        registries.definitions().register(parent, parentDef);
        registries.definitions().register(sibling, siblingDef);
        factory.build(new Object(), parentDef, null);
        factory.build(new Object(), siblingDef, null);

        Id parentVar = Id.parse("test:a/speed");
        Id siblingVar = Id.parse("test:a/b/speed");
        assertNotNull(stateVars.get(parentVar));
        assertNotNull(stateVars.get(siblingVar));

        registries.definitions().remove(parent);
        assertNull(stateVars.get(parentVar), "the cleared machine's own inline var is dropped");
        assertNotNull(stateVars.get(siblingVar), "clearing test:a must NOT drop test:a/b's inline vars");

        registries.definitions().remove(sibling);
        assertNull(stateVars.get(siblingVar));
    }

    /** A definition with a single inline float var, decodable via the inline path of the factory. */
    private static StateMachineDefinition definitionWithInlineVar(Id machineId, String varName) {
        String json = """
                {
                  "id": "%s",
                  "state_vars": [ { "name": "%s", "type": "float", "default": 0.5 } ],
                  "layers": [ {
                    "id": "base", "initial_state": "idle",
                    "states": [ { "id": "idle" } ],
                    "transitions": []
                  } ]
                }
                """.formatted(machineId, varName);
        return StateMachineDefinition.CODEC.decode(JsonOps.INSTANCE, JsonParser.parseString(json))
                .resultOrPartial(e -> { throw new AssertionError("decode failed: " + e); })
                .orElseThrow().getFirst();
    }
}
