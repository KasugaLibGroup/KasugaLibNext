package lib.kasuga.test.scripting.fsm;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmRegistries;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.State;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVarRegistry;
import lib.kasuga.scripting.fsm.AnimatorApi;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-JVM unit tests for {@link AnimatorApi} over a real {@link StateMachine}, using an injected
 * {@link FsmRegistries} — no V8 engine required. Covers every exported method,
 * including the invalid/released-handle and unknown-var no-op contract.
 */
class AnimatorApiTest {

    static final class Owner {
        boolean moving;
    }

    private static final Id MACHINE_ID =
            Id.fromNamespaceAndPath("test", "api_machine");

    private static StateVar<Integer> intVar(StateVarRegistry reg, String path) {
        return reg.register(StateVar.of(rl(path), Integer.class, Codec.INT, 0));
    }

    private static StateVar<Float> floatVar(StateVarRegistry reg, String path) {
        return reg.register(StateVar.of(rl(path), Float.class, Codec.FLOAT, 0f));
    }

    private static StateVar<Boolean> boolVar(StateVarRegistry reg, String path) {
        return reg.register(StateVar.of(rl(path), Boolean.class, Codec.BOOL, false));
    }

    private static Id rl(String path) {
        return Id.fromNamespaceAndPath("test", path);
    }

    /** idle --(when: owner.moving)--> walk(2 ticks) --(whenComplete)--> idle */
    private static StateMachine<Owner> buildMachine() {
        return StateMachine.<Owner>builder(new Owner())
                .layer("main", layer -> {
                    State<Owner> idle = layer.state("idle");
                    State<Owner> walk = layer.state("walk", s -> s.durationTicks(2));
                    layer.initial(idle);
                    layer.transition("idle_to_walk", idle, walk)
                            .when(ctx -> ((Owner) ctx.owner()).moving);
                    layer.transition("walk_to_idle", walk, idle).whenComplete();
                })
                .build();
    }

    @Test
    void tickDrivesConditionTransitions() {
        FsmRegistries registries = FsmRegistries.create();
        StateMachine<Owner> machine = buildMachine();
        int handle = (int) registries.machines().register(MACHINE_ID, machine);
        AnimatorApi api = new AnimatorApi(registries);

        assertEquals("idle", api.getState(handle, "main"));
        api.tick(handle);
        assertEquals("idle", api.getState(handle, "main"));

        machine.owner().moving = true;
        api.tick(handle);
        assertEquals("walk", api.getState(handle, "main"));

        // duration_ticks(2) + when_complete: two more ticks (entry tick counts as elapsed 1)
        api.tick(handle);
        assertEquals("walk", api.getState(handle, "main"));
        api.tick(handle);
        assertEquals("idle", api.getState(handle, "main"));
    }

    @Test
    void tickWithDeltaSecondsAdvancesMachine() {
        FsmRegistries registries = FsmRegistries.create();
        StateMachine<Owner> machine = buildMachine();
        int handle = (int) registries.machines().register(MACHINE_ID, machine);
        AnimatorApi api = new AnimatorApi(registries);

        machine.owner().moving = true;
        api.tick(handle, 1f / 20f);
        assertEquals("walk", api.getState(handle, "main"));

        api.tick(handle, 0.05f);
        assertEquals("walk", api.getState(handle, "main"));
        api.tick(handle, 0.05f);
        assertEquals("idle", api.getState(handle, "main"));
    }

    @Test
    void goToSwitchesState() {
        FsmRegistries registries = FsmRegistries.create();
        StateMachine<Owner> machine = buildMachine();
        int handle = (int) registries.machines().register(MACHINE_ID, machine);
        AnimatorApi api = new AnimatorApi(registries);

        api.goTo(handle, "main", "walk");
        api.tick(handle);
        assertEquals("walk", api.getState(handle, "main"));

        api.goTo(handle, "main", "idle");
        api.tick(handle);
        assertEquals("idle", api.getState(handle, "main"));
    }

    @Test
    void typedGetSetQuerySurface() {
        FsmRegistries registries = FsmRegistries.create();
        StateVarRegistry stateVars = registries.vars();
        StateVar<Integer> combo = intVar(stateVars, "combo");
        StateVar<Float> f = floatVar(stateVars, "f");
        StateVar<Boolean> flag = boolVar(stateVars, "flag");
        int handle = (int) registries.machines().register(MACHINE_ID, buildMachine());
        AnimatorApi api = new AnimatorApi(registries);

        // defaults read through before any set
        assertEquals(0, api.get(handle, "test:combo"));
        assertEquals(0f, (float) api.get(handle, "test:f"), 1e-6f);
        assertFalse((boolean) api.get(handle, "test:flag"));
        assertFalse(api.has(handle, "test:combo"));
        assertEquals("int", api.varType("test:combo"));
        assertEquals("float", api.varType("test:f"));

        api.set(handle, "test:combo", 42);
        api.set(handle, "test:f", 1.5f);
        api.set(handle, "test:flag", true);

        assertEquals(42, api.get(handle, "test:combo"));
        assertEquals(1.5f, (float) api.get(handle, "test:f"), 1e-6f);
        assertTrue((boolean) api.get(handle, "test:flag"));
        assertTrue(api.has(handle, "test:combo"));

        // JS-style integral Double coerces to the int var
        api.set(handle, "test:combo", 7.0);
        assertEquals(7, api.get(handle, "test:combo"));

        // wrong-kind set is rejected (no-op) and the previous value survives
        api.set(handle, "test:combo", "not-a-number");
        assertEquals(7, api.get(handle, "test:combo"));

        // structural read still works
        assertEquals("idle", api.getState(handle, "main"));
    }

    @Test
    void unknownVarIsNoOp() {
        FsmRegistries registries = FsmRegistries.create();
        int handle = (int) registries.machines().register(MACHINE_ID, buildMachine());
        AnimatorApi api = new AnimatorApi(registries);

        assertNull(api.get(handle, "test:never_registered"));
        assertFalse(api.has(handle, "test:never_registered"));
        assertEquals("", api.varType("test:never_registered"));
        // set on an unknown var must not throw and must not crash the machine
        api.set(handle, "test:never_registered", 1);
    }

    @Test
    void triggerByVarId() {
        FsmRegistries registries = FsmRegistries.create();
        // triggers must be ephemeral so the engine clears them at the end of the tick
        StateVar<Boolean> go = registries.vars().register(StateVar.builder(
                rl("go"), Boolean.class, Codec.BOOL
        ).defaultValue(Boolean.FALSE).ephemeral().build());
        int handle = (int) registries.machines().register(MACHINE_ID, buildMachine());
        AnimatorApi api = new AnimatorApi(registries);

        assertFalse(api.isTriggered(handle, "test:go"));
        api.trigger(handle, "test:go");
        assertTrue(api.isTriggered(handle, "test:go"));
        api.tick(handle);
        assertFalse(api.isTriggered(handle, "test:go"), "ephemeral trigger cleared after tick");
    }

    @Test
    void invalidHandleIsNoOp() {
        AnimatorApi api = new AnimatorApi(FsmRegistries.create());
        int handle = 424242; // never registered

        api.trigger(handle, "test:go");
        api.goTo(handle, "main", "walk");
        api.set(handle, "test:combo", 1);
        api.tick(handle);
        api.tick(handle, 1f / 20f);

        assertEquals("", api.getState(handle, "main"));
        assertNull(api.get(handle, "test:combo"));
        assertFalse(api.has(handle, "test:combo"));
        assertFalse(api.isTriggered(handle, "test:go"));
        assertEquals(0, api.getVersion(handle));
        assertEquals(0L, api.getTick(handle));
    }

    @Test
    void releasedHandleBecomesInert() {
        FsmRegistries registries = FsmRegistries.create();
        int handle = (int) registries.machines().register(MACHINE_ID, buildMachine());
        registries.machines().release(handle);
        AnimatorApi api = new AnimatorApi(registries);

        api.trigger(handle, "test:go");
        api.tick(handle);

        assertEquals("", api.getState(handle, "main"));
        assertEquals(0, api.getVersion(handle));
    }
}
