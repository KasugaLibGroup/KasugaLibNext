package lib.kasuga.test.scripting.fsm;

import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import lib.kasuga.scripting.fsm.AnimatorApi;
import lib.kasuga.scripting.fsm.AnimatorBuilderApi;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM unit tests for {@link AnimatorBuilderApi} with an injected {@link FsmRegistries} — no V8
 * engine required. Verifies that definitions/behaviors land in the injected instances (never the
 * GLOBAL registries), the unknown-id contract of {@code instantiate}, and the full
 * register → instantiate → tick → read loop.
 */
class AnimatorBuilderApiTest {

    static final class Owner {
        boolean walking;
    }

    private static final Id MACHINE_ID =
            Id.fromNamespaceAndPath("test", "script_machine");

    /** idle --(when: test:is_walking)--> walk(3 ticks) --(whenComplete)--> idle */
    private static final String MACHINE_JSON = """
            {
              "id": "test:script_machine",
              "layers": [
                {
                  "id": "main",
                  "initial_state": "idle",
                  "states": [
                    {"id": "idle"},
                    {"id": "walk", "duration_ticks": 3,
                     "on_enter": ["test:entered"], "on_update": ["test:updating"]}
                  ],
                  "transitions": [
                    {"id": "to_walk", "from": "idle", "to": "walk",
                     "when": ["test:is_walking"], "on_fire": ["test:fired"]},
                    {"id": "back_to_idle", "from": "walk", "to": "idle", "when_complete": true}
                  ]
                }
              ]
            }
            """;

    @Test
    void registerDefinitionWritesToInjectedRegistryOnly() {
        FsmRegistries registries = FsmRegistries.create();
        AnimatorBuilderApi api = new AnimatorBuilderApi(registries);

        String id = api.registerDefinition(MACHINE_JSON);

        assertEquals(MACHINE_ID.toString(), id);
        assertNotNull(registries.definitions().get(MACHINE_ID));
        assertNull(FsmRegistries.GLOBAL.definitions().get(MACHINE_ID),
                "definition must not leak into the GLOBAL registry");
    }

    @Test
    void registerConditionAndActionLandInInjectedLibrary() {
        FsmRegistries registries = FsmRegistries.create();
        AnimatorBuilderApi api = new AnimatorBuilderApi(registries);

        api.registerCondition("test", "is_walking", ctx -> ((Owner) ctx.owner()).walking);
        api.registerAction("test", "fired", ctx -> {});

        assertNotNull(registries.functions().condition(Id.fromNamespaceAndPath("test", "is_walking")));
        assertNotNull(registries.functions().action(Id.fromNamespaceAndPath("test", "fired")));
        assertNull(FsmRegistries.GLOBAL.functions().condition(Id.fromNamespaceAndPath("test", "is_walking")),
                "condition must not leak into the GLOBAL library");
        assertNull(FsmRegistries.GLOBAL.functions().action(Id.fromNamespaceAndPath("test", "fired")),
                "action must not leak into the GLOBAL library");
    }

    @Test
    void instantiateUnknownIdThrows() {
        AnimatorBuilderApi api = new AnimatorBuilderApi(FsmRegistries.create());

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> api.instantiate("test:missing", new Owner()));
        assertTrue(unknown.getMessage().contains("test:missing"));

        // not even a valid ResourceLocation → same contract
        assertThrows(IllegalArgumentException.class,
                () -> api.instantiate("not a valid id", new Owner()));
    }

    @Test
    void instantiateWithoutModelRunsLogicOnly() {
        FsmRegistries registries = FsmRegistries.create();
        AnimatorBuilderApi api = new AnimatorBuilderApi(registries);
        api.registerDefinition(MACHINE_JSON);

        int handle = api.instantiate(MACHINE_ID.toString(), new Owner());

        assertTrue(handle > 0);
        assertNotNull(registries.machines().resolve(handle));
        assertNull(FsmRegistries.GLOBAL.machines().latest(MACHINE_ID),
                "machine must not be registered into the GLOBAL registry");
    }

    @Test
    void instantiateWithNonModelInstanceRunsWithoutSink() {
        FsmRegistries registries = FsmRegistries.create();
        AnimatorBuilderApi api = new AnimatorBuilderApi(registries);
        api.registerDefinition(MACHINE_JSON);

        int handle = api.instantiate(MACHINE_ID.toString(), new Owner(), "not-a-model");

        assertTrue(handle > 0);
        assertFalse(registries.machines().resolve(handle).isClientSide());
    }

    @Test
    void instantiateFullLoopRegisterTickRead() {
        FsmRegistries registries = FsmRegistries.create();
        AnimatorBuilderApi api = new AnimatorBuilderApi(registries);
        Owner owner = new Owner();
        List<String> events = new ArrayList<>();

        api.registerCondition("test", "is_walking", ctx -> ((Owner) ctx.owner()).walking);
        api.registerAction("test", "fired", ctx -> events.add("fired"));
        api.registerAction("test", "entered", ctx -> events.add("entered"));
        api.registerAction("test", "updating", ctx -> events.add("updating"));
        api.registerDefinition(MACHINE_JSON);

        int handle = api.instantiate(MACHINE_ID.toString(), owner);
        assertTrue(handle > 0);

        AnimatorApi animator = new AnimatorApi(registries);
        assertEquals("idle", animator.getState(handle, "main"));

        owner.walking = true;
        animator.tick(handle);
        assertEquals("walk", animator.getState(handle, "main"));
        assertTrue(events.contains("fired"), "on_fire action should have run: " + events);
        assertTrue(events.contains("entered"), "on_enter action should have run: " + events);

        // walk's on_update runs from the next tick onward
        animator.tick(handle);
        assertTrue(events.contains("updating"), "on_update action should have run: " + events);

        // duration_ticks(3) + when_complete: entry tick counts as elapsed 1, so three more ticks
        // reach elapsed 3 and fire back to idle
        animator.tick(handle);
        animator.tick(handle);
        assertEquals("idle", animator.getState(handle, "main"));
    }

    @Test
    void registerStateVarDefinesTypedVarUsableViaAnimatorApi() {
        FsmRegistries registries = FsmRegistries.create();
        StateVarRegistry stateVars = registries.vars();
        AnimatorBuilderApi api = new AnimatorBuilderApi(registries);

        // the script-facing type catalog advertises the built-in tokens
        assertTrue(Arrays.asList(api.varTypes()).contains("float"));

        // register a float var with a default; lands in the injected registry only
        String speedId = api.registerStateVar("test", "speed", "float", 0.5);
        assertEquals("test:speed", speedId);
        StateVar<?> speed = stateVars.resolve("test:speed");
        assertNotNull(speed);
        assertEquals(0.5f, speed.defaultValue());
        assertNull(FsmRegistries.GLOBAL.vars().resolve("test:speed"),
                "registered var must not leak into the GLOBAL registry");

        // re-registering the same id is idempotent (keeps the original default)
        assertEquals(speedId, api.registerStateVar("test", "speed", "float", 9f));
        assertEquals(0.5f, stateVars.resolve("test:speed").defaultValue());

        // ephemeral trigger var
        StateVar<?> attack = stateVars.resolve(api.registerStateVar("test", "attack", "bool", false, true));
        assertNotNull(attack);
        assertTrue(attack.ephemeral());

        // unknown type is rejected (returns "") and registers nothing
        assertEquals("", api.registerStateVar("test", "bogus", "no_such_type", null, false));
        assertNull(stateVars.resolve("test:bogus"));

        // the script-defined var is usable through AnimatorApi on a machine
        int handle = (int) registries.machines().register(MACHINE_ID, StateMachine.builder(new Object()).build());
        AnimatorApi animator = new AnimatorApi(registries);

        assertEquals(0.5f, (float) animator.get(handle, "test:speed"), 1e-6f);
        assertFalse(animator.has(handle, "test:speed"));
        animator.set(handle, "test:speed", 1.25);
        assertTrue(animator.has(handle, "test:speed"));
        assertEquals(1.25f, (float) animator.get(handle, "test:speed"), 1e-6f);

        // ephemeral trigger fires this tick and is cleared after tick
        assertFalse(animator.isTriggered(handle, "test:attack"));
        animator.trigger(handle, "test:attack");
        assertTrue(animator.isTriggered(handle, "test:attack"));
        animator.tick(handle);
        assertFalse(animator.isTriggered(handle, "test:attack"));
    }
}
