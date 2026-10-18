package test.kasuga.slp.javet.fsm;

import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.scripting.ScriptConsole;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.fsm.AnimatorApi;
import lib.kasuga.scripting.fsm.FsmApiRegistration;
import lib.kasuga.scripting.security.SecurityEngineFeatureType;
import lib.kasuga.scripting.value.ScriptValue;
import lib.kasuga.slp.javet.JavetScriptEngine;
import lib.kasuga.slp.javet.KasugaLibJavet;
import lib.kasuga.slp.javet.module.JsModuleResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Assembly test for {@link FsmApiRegistration} on the Javet engine: installs the FSM globals on an
 * engine type (same context-initialization pattern as {@code ModuleResolutionTest} / {@code
 * ApiSecurityTest}), then verifies from JS that {@code Animator} / {@code AnimatorBuilder} exist and
 * that the register → instantiate → tick → read loop works end to end, including the Javet-wrapped
 * exception thrown by {@code instantiate} for an unknown id (caught in JS, engine stays alive).
 *
 * <p>Note on the end-to-end shape: the scripting converter bridges JS numbers as {@code Integer} /
 * {@code Double}, so {@code long}-parameter methods cannot be invoked from JS by the current bridge.
 * {@code registerDefinition} / {@code instantiate} are therefore driven from JS (string parameters,
 * long return values), while tick/read are asserted from Java against the same {@code GLOBAL} machine
 * registry — the full definition → machine → handle → read chain is still covered.
 *
 * <p>Requires the V8 native library on the test classpath (javet ships platform natives separately);
 * skipped via {@code assumeTrue} when unavailable.
 */
public class FsmApiAssemblyTest {

    private JavetScriptEngine engine;
    private ScriptEngineType<JavetScriptEngine> savedEngineType;

    @BeforeEach
    void setUp() throws ScriptException {
        Assumptions.assumeTrue(isV8NativeAvailable(),
                "V8 native library not available; skipping FSM javet assembly tests");

        savedEngineType = KasugaLibJavet.ENGINE_TYPE;
        ScriptEngineType<JavetScriptEngine> engineType = FsmApiRegistration.install(
                ScriptEngineType.<JavetScriptEngine>builder(JavetScriptEngine::new)
                        .scriptType("javascript")
                        .resolver(new JsModuleResolver())
                        .addFeature(SecurityEngineFeatureType.INSTANCE))
                .build();
        KasugaLibJavet.ENGINE_TYPE = engineType;

        engine = engineType.create(ScriptConsole.errorsToStderr());
    }

    @AfterEach
    void tearDown() {
        KasugaLibJavet.ENGINE_TYPE = savedEngineType;
        if (engine != null) {
            try {
                engine.getRuntime().lowMemoryNotification();
                engine.close();
            } catch (Exception e) {
                System.err.println("Teardown warning: " + e.getMessage());
            }
        }
    }

    private static boolean isV8NativeAvailable() {
        try (V8Runtime runtime = V8Host.getV8Instance().createV8Runtime()) {
            runtime.getExecutor("1 + 1").executeVoid();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void shouldInstallAnimatorGlobals() throws ScriptException {
        assertEquals("object", engine.execute("typeof Animator").asString());
        assertEquals("object", engine.execute("typeof AnimatorBuilder").asString());
    }

    @Test
    public void shouldRegisterInstantiateFromJsAndDriveMachineFromJava() throws ScriptException {
        String script = """
                const def = {
                  id: "test:js_machine",
                  layers: [{
                    id: "main",
                    initial_state: "idle",
                    states: [
                      {id: "idle"},
                      {id: "walk", duration_ticks: 2}
                    ],
                    transitions: [
                      {id: "to_walk", from: "idle", to: "walk", trigger_on: "go"},
                      {id: "back_to_idle", from: "walk", to: "idle", when_complete: true}
                    ]
                  }]
                };
                const defId = AnimatorBuilder.registerDefinition(JSON.stringify(def));
                const handle = AnimatorBuilder.instantiate(defId, null);
                const info = {defId: defId, handle: handle, handleType: typeof handle};
                JSON.stringify(info);
                """;
        ScriptValue result = engine.execute(script);
        JsonObject info = JsonParser.parseString(result.asString()).getAsJsonObject();

        assertEquals("test:js_machine", info.get("defId").getAsString());
        assertEquals("number", info.get("handleType").getAsString());
        int handle = info.get("handle").getAsInt();
        assertTrue(handle > 0);

        // The machine lives in the GLOBAL registry the script globals are bound to; drive it from Java.
        AnimatorApi api = new AnimatorApi();
        assertEquals("idle", api.getState(handle, "main"));

        api.trigger(handle, "go");
        api.tick(handle);
        assertEquals("walk", api.getState(handle, "main"));

        // duration_ticks(2) + when_complete: entry tick counts as elapsed 1, so two more ticks return.
        api.tick(handle);
        api.tick(handle);
        assertEquals("idle", api.getState(handle, "main"));
    }

    @Test
    public void shouldCatchInstantiateExceptionInJsAndKeepEngineAlive() throws ScriptException {
        String script = """
                let caught = "";
                try {
                  AnimatorBuilder.instantiate("test:definitely_missing", null);
                } catch (e) {
                  caught = String(e && (e.message || e));
                }
                const stillWorks = caught.length > 0 && (1 + 1) === 2
                        && typeof Animator === "object" && typeof AnimatorBuilder === "object";
                stillWorks ? "PASS" : "FAIL caught='" + caught + "'";
                """;
        ScriptValue result = engine.execute(script);
        assertEquals("PASS", result.asString());
    }

    /**
     * Drive a machine AND its typed vars entirely from JS — the real script boundary that the {@code long}
     * handle used to block (Javet mapped {@code long}→BigInt, which JSON.stringify can't serialize and which
     * couldn't be passed back to {@code long}-param methods). With the {@code int} handle, JS receives a
     * Number, round-trips it through {@code trigger}/{@code tick}/{@code getState}/{@code set}/{@code get},
     * and a float var comes back as a JS number (not BigInt).
     */
    @Test
    public void shouldDriveMachineAndVarsEntirelyFromJs() throws ScriptException {
        String script = """
                const def = {
                  id: "test:js_ctrl",
                  state_vars: [
                    { name: "go", type: "bool", default: false, ephemeral: true },
                    { name: "speed", type: "float", default: 0.0 }
                  ],
                  layers: [{
                    id: "main", initial_state: "idle",
                    states: [ {id:"idle"}, {id:"run", duration_ticks: 2} ],
                    transitions: [
                      { id:"i2r", from:"idle", to:"run", trigger_on:"go" },
                      { id:"r2i", from:"run", to:"idle", when_complete: true }
                    ]
                  }]
                };
                AnimatorBuilder.registerDefinition(JSON.stringify(def));
                const h = AnimatorBuilder.instantiate("test:js_ctrl", null);
                const s0 = Animator.getState(h, "main");
                Animator.trigger(h, "test:js_ctrl/go");
                Animator.tick(h);
                const s1 = Animator.getState(h, "main");
                Animator.tick(h);
                Animator.tick(h);
                const s2 = Animator.getState(h, "main");
                Animator.set(h, "test:js_ctrl/speed", 1.25);
                const speed = Animator.get(h, "test:js_ctrl/speed");
                const handleType = typeof h;
                const speedType = typeof speed;
                JSON.stringify({handleType, s0, s1, s2, speed, speedType});
                """;
        ScriptValue result = engine.execute(script);
        JsonObject r = JsonParser.parseString(result.asString()).getAsJsonObject();

        assertEquals("number", r.get("handleType").getAsString(),
                "the int handle must arrive in JS as a Number (not BigInt) so it round-trips");
        assertEquals("idle", r.get("s0").getAsString());
        assertEquals("run", r.get("s1").getAsString(), "JS trigger + tick drove idle→run");
        assertEquals("idle", r.get("s2").getAsString(), "whenComplete returned run→idle");
        assertEquals(1.25, r.get("speed").getAsDouble(), 1e-6, "JS set/get round-tripped the float var");
        assertEquals("number", r.get("speedType").getAsString(),
                "a float var must come back as a JS number (not BigInt)");
    }

    /**
     * Register condition + action from JS (the lambdas receive {@code ctx} and call {@code ctx.owner()}),
     * pass a JS owner object into {@code instantiate}, then verify the condition evaluates against that owner
     * and the action mutates it — all through the real V8↔Java callback boundary.
     */
    @Test
    public void shouldRegisterConditionAndActionFromJsAndEvaluateWithOwner() throws ScriptException {
        String script = """
                AnimatorBuilder.registerCondition("test", "js_owner_check", ctx => {
                    const owner = ctx.owner();
                    return owner != null && owner.moving === true;
                });
                AnimatorBuilder.registerAction("test", "js_record_action", ctx => {
                    const owner = ctx.owner();
                    if (owner) owner.actionRan = true;
                });
                AnimatorBuilder.registerDefinition(JSON.stringify({
                    id: "test:js_cond",
                    layers: [{
                        id: "main", initial_state: "idle",
                        states: [ {id:"idle"}, {id:"go"} ],
                        transitions: [
                            { id:"i2g", from:"idle", to:"go",
                              when: ["test:js_owner_check"], on_fire: ["test:js_record_action"] }
                        ]
                    }]
                }));

                const owner = { moving: false, actionRan: false };
                const h = AnimatorBuilder.instantiate("test:js_cond", owner);

                const s0 = Animator.getState(h, "main");
                Animator.tick(h);
                const s1 = Animator.getState(h, "main");

                owner.moving = true;
                Animator.tick(h);
                const s2 = Animator.getState(h, "main");

                JSON.stringify({s0, s1, s2, actionRan: owner.actionRan});
                """;
        ScriptValue result = engine.execute(script);
        JsonObject r = JsonParser.parseString(result.asString()).getAsJsonObject();

        assertEquals("idle", r.get("s0").getAsString());
        assertEquals("idle", r.get("s1").getAsString(), "condition is false (owner.moving=false) → stays idle");
        assertEquals("go", r.get("s2").getAsString(),
                "condition evaluated ctx.owner().moving===true from a JS lambda → transition fired");
        assertTrue(r.get("actionRan").getAsBoolean(),
                "JS action mutated ctx.owner().actionRan=true via the V8 callback");
    }

    /**
     * Round-3 P1b: {@code registerDefinition} accepts a JS object literal directly — no
     * {@code JSON.stringify} muscle-memory tax. The definition is decoded from the walked object tree.
     */
    @Test
    public void shouldRegisterDefinitionFromObjectLiteral() throws ScriptException {
        String script = """
                const defId = AnimatorBuilder.registerDefinition({
                    id: "test:obj_literal",
                    layers: [{
                        id: "main", initial_state: "a",
                        states: [ {id:"a", duration_ticks: 1}, {id:"b", duration_ticks: 1} ],
                        transitions: [ { id:"a2b", from:"a", to:"b", when_complete: true } ]
                    }]
                });
                const h = AnimatorBuilder.instantiate(defId, null);
                JSON.stringify({defId: defId, h: h});
                """;
        JsonObject r = JsonParser.parseString(engine.execute(script).asString()).getAsJsonObject();
        assertEquals("test:obj_literal", r.get("defId").getAsString(),
                "registerDefinition returned the id from a JS object literal (no JSON.stringify)");
        int h = r.get("h").getAsInt();

        AnimatorApi api = new AnimatorApi();
        assertEquals("a", api.getState(h, "main"));
        // The initial state doesn't get the in-tick elapsed increment a fire()-entered state does, so a
        // duration-1 state needs two ticks to whenComplete (check runs at tick start, before the increment).
        api.tick(h);
        api.tick(h);
        assertEquals("b", api.getState(h, "main"), "object-literal definition decoded and ticked correctly");
    }

    /**
     * Round-3 P1a: {@code Animator.autoTick}/{@code onTick} drive the machine each server tick without the
     * author hand-rolling {@code timer.setInterval}. Here the engine's tick (which drives the
     * {@code FsmAutoTickModule} on the script thread) advances the machine and fires the JS callback.
     */
    @Test
    public void shouldAutoTickAndRunOnTickCallback() throws ScriptException {
        // The onTick callback writes the post-tick state into a holder object the test reads back.
        engine.execute("""
                AnimatorBuilder.registerDefinition({
                    id: "test:auto_tick",
                    state_vars: [ { name: "ticks", type: "int", default: 0 } ],
                    layers: [{
                        id: "main", initial_state: "a",
                        states: [ {id:"a", duration_ticks: 1}, {id:"b", duration_ticks: 1} ],
                        transitions: [ {id:"a2b", from:"a", to:"b", when_complete: true},
                                       {id:"b2a", from:"b", to:"a", when_complete: true} ]
                    }]
                });
                globalThis.__h = AnimatorBuilder.instantiate("test:auto_tick", null);
                globalThis.__seen = [];
                Animator.onTick(globalThis.__h, () => {
                    globalThis.__seen.push(Animator.getState(globalThis.__h, "main"));
                });
                """);
        int h = Integer.parseInt(engine.execute("String(globalThis.__h)").asString());

        AnimatorApi api = new AnimatorApi();
        assertEquals("a", api.getState(h, "main"));
        // The initial state needs two ticks to whenComplete at duration 1 (see object-literal test note).
        engine.tick();  // a: elapsed 0 → 1 (check 0>=1 false)
        engine.tick();  // a: 1>=1 → fire → b (fire()-entered b lands at elapsed 1), then onTick observes "b"
        assertEquals("b", api.getState(h, "main"));
        engine.tick();  // b entered at elapsed 1 → 1>=1 → fire → a
        assertEquals("a", api.getState(h, "main"));

        String seen = engine.execute("JSON.stringify(globalThis.__seen)").asString();
        var arr = JsonParser.parseString(seen).getAsJsonArray();
        assertTrue(arr.size() >= 2, "onTick callback fired after each engine tick: " + seen);
        assertTrue(contains(arr, "b"),
                "onTick observed state 'b' after the advancing tick (callback runs post-tick): " + seen);
    }

    /**
     * Round-3 P2: {@code declaredVars} lists the declared set, and {@code get}/{@code set} resolve short
     * names (auto-prefix from the machine's definition id) so authors don't hand-write the long id.
     */
    @Test
    public void shouldResolveShortVarNamesAndListDeclaredVars() throws ScriptException {
        String script = """
                AnimatorBuilder.registerDefinition({
                    id: "test:short_name",
                    state_vars: [
                        { name: "speed", type: "float", default: 0.0 },
                        { name: "armed", type: "bool", default: false }
                    ],
                    layers: [{ id: "main", initial_state: "idle", states: [ {id:"idle"} ] }]
                });
                const h = AnimatorBuilder.instantiate("test:short_name", null);
                Animator.set(h, "speed", 4.5);           // short name
                const speedShort = Animator.get(h, "speed");
                const speedLong = Animator.get(h, "test:short_name/speed");
                const declared = Animator.declaredVars(h);
                JSON.stringify({h, speedShort, speedLong, declared});
                """;
        JsonObject r = JsonParser.parseString(engine.execute(script).asString()).getAsJsonObject();
        assertEquals(4.5, r.get("speedShort").getAsDouble(), 1e-6, "short-name set/get round-tripped");
        assertEquals(4.5, r.get("speedLong").getAsDouble(), 1e-6, "long id still works");
        var declared = r.get("declared").getAsJsonArray();
        assertEquals(2, declared.size(), "declaredVars lists both declared vars");
        assertTrue(contains(declared, "test:short_name/speed") && contains(declared, "test:short_name/armed"),
                "declaredVars contains both full ids: " + declared);
    }

    /**
     * Round-3 P2: {@code Animator.dispose} releases the handle and stops auto-tick — after dispose, the
     * engine tick no longer advances it (it's gone from the auto-tick set) and the handle resolves to nothing.
     */
    @Test
    public void shouldDisposeStopsAutoTickAndReleasesHandle() throws ScriptException {
        engine.execute("""
                AnimatorBuilder.registerDefinition({
                    id: "test:dispose",
                    layers: [{
                        id: "main", initial_state: "a",
                        states: [ {id:"a", duration_ticks: 1}, {id:"b", duration_ticks: 1} ],
                        transitions: [ {id:"a2b", from:"a", to:"b", when_complete: true},
                                       {id:"b2a", from:"b", to:"a", when_complete: true} ]
                    }]
                });
                globalThis.__h = AnimatorBuilder.instantiate("test:dispose", null);
                Animator.autoTick(globalThis.__h, true);
                """);
        int h = Integer.parseInt(engine.execute("String(globalThis.__h)").asString());
        AnimatorApi api = new AnimatorApi();

        engine.tick();
        engine.tick();  // initial state needs two ticks to whenComplete at duration 1
        assertEquals("b", api.getState(h, "main"), "auto-tick advanced the machine before dispose");

        engine.execute("Animator.dispose(globalThis.__h);");
        engine.tick();  // must not throw, and the handle is gone
        assertEquals("", api.getState(h, "main"),
                "after dispose the handle is released — getState returns empty (machine gone)");
    }

    /**
     * Round-3 P3: {@code Animator.onStateChanged} fires only when the active state changes (version bump),
     * not on every tick — the efficient alternative to polling {@code getState}.
     */
    @Test
    public void shouldFireOnStateChangedOnlyOnStateChange() throws ScriptException {
        engine.execute("""
                AnimatorBuilder.registerDefinition({
                    id: "test:on_state_changed",
                    layers: [{
                        id: "main", initial_state: "a",
                        states: [ {id:"a", duration_ticks: 1}, {id:"b", duration_ticks: 1} ],
                        transitions: [ {id:"a2b", from:"a", to:"b", when_complete: true},
                                       {id:"b2a", from:"b", to:"a", when_complete: true} ]
                    }]
                });
                globalThis.__h = AnimatorBuilder.instantiate("test:on_state_changed", null);
                globalThis.__changes = [];
                Animator.onStateChanged(globalThis.__h, () => {
                    globalThis.__changes.push(Animator.getState(globalThis.__h, "main"));
                });
                """);
        int h = Integer.parseInt(engine.execute("String(globalThis.__h)").asString());
        AnimatorApi api = new AnimatorApi();

        // Tick 1: a elapsed 0→1, no state change yet → callback must NOT fire.
        engine.tick();
        var changesAfterTick1 = JsonParser.parseString(
                engine.execute("JSON.stringify(globalThis.__changes)").asString()).getAsJsonArray();
        assertEquals(0, changesAfterTick1.size(),
                "onStateChanged did not fire while still in 'a': " + changesAfterTick1);

        // Tick 2: a→b fires → callback observes "b".
        engine.tick();
        assertEquals("b", api.getState(h, "main"));
        var changes = JsonParser.parseString(
                engine.execute("JSON.stringify(globalThis.__changes)").asString()).getAsJsonArray();
        assertTrue(contains(changes, "b"),
                "onStateChanged fired on the a→b change and observed 'b': " + changes);
    }

    /**
     * Round-3 deep dive: a JS-built definition with TWO independent layers (BASE + ADDITIVE, weighted), each
     * with its own state graph and trigger. Proves per-layer {@code getState}/{@code getLayerMode}/{@code
     * getLayerWeight} from JS, the two layers advance independently under one {@code tick}, and a whenComplete
     * auto-advances per layer. Distinct from the single-layer tests above.
     */
    @Test
    public void shouldDriveMultiLayerMachineFromJs() throws ScriptException {
        String script = """
                AnimatorBuilder.registerDefinition({
                    id: "test:multi_layer",
                    layers: [
                        { id: "base", mode: "base", weight: 1.0, initial_state: "idle",
                          states: [ {id:"idle"}, {id:"run", duration_ticks: 1} ],
                          transitions: [ {id:"i2r", from:"idle", to:"run", trigger_on:"go"},
                                         {id:"r2i", from:"run", to:"idle", when_complete: true} ] },
                        { id: "overlay", mode: "additive", weight: 0.5, initial_state: "hide",
                          states: [ {id:"hide"}, {id:"wave", duration_ticks: 1} ],
                          transitions: [ {id:"h2w", from:"hide", to:"wave", trigger_on:"wave"},
                                         {id:"w2h", from:"wave", to:"hide", when_complete: true} ] }
                    ]
                });
                const h = AnimatorBuilder.instantiate("test:multi_layer", null);
                Animator.trigger(h, "test:multi_layer/go");
                Animator.trigger(h, "test:multi_layer/wave");
                Animator.tick(h);
                const s1 = Animator.getState(h, "base");
                const s2 = Animator.getState(h, "overlay");
                const mBase = Animator.getLayerMode(h, "base");
                const mOver = Animator.getLayerMode(h, "overlay");
                const wBase = Animator.getLayerWeight(h, "base");
                const wOver = Animator.getLayerWeight(h, "overlay");
                Animator.tick(h);
                const s3 = Animator.getState(h, "base");
                const s4 = Animator.getState(h, "overlay");
                JSON.stringify({s1, s2, s3, s4, mBase, mOver, wBase, wOver});
                """;
        JsonObject r = JsonParser.parseString(engine.execute(script).asString()).getAsJsonObject();
        assertEquals("run", r.get("s1").getAsString(), "base: trigger fired idle→run same-tick");
        assertEquals("wave", r.get("s2").getAsString(),
                "overlay: trigger fired hide→wave on the INDEPENDENT layer in the same tick");
        assertEquals("idle", r.get("s3").getAsString(), "base: run(1) whenComplete→idle");
        assertEquals("hide", r.get("s4").getAsString(), "overlay: wave(1) whenComplete→hide");
        assertEquals("base", r.get("mBase").getAsString());
        assertEquals("additive", r.get("mOver").getAsString());
        assertEquals(1.0, r.get("wBase").getAsDouble(), 1e-6);
        assertEquals(0.5, r.get("wOver").getAsDouble(), 1e-6);
    }

    /**
     * The data-component channel inside JS callbacks: a JS guard reads a sibling var via {@code ctx.get(id)}
     * and a JS action writes one via {@code ctx.set(id, value)} — distinct from owner-mutation (test 5). The
     * write is visible to the next tick's guard, and accumulates across re-fires.
     */
    @Test
    public void shouldLetJsGuardReadSiblingVarAndActionWriteIt() throws ScriptException {
        String script = """
                AnimatorBuilder.registerCondition("test", "armed_and_counting", ctx => {
                    return ctx.get("test:var_fsm/armed") === true;
                });
                AnimatorBuilder.registerAction("test", "record_swing", ctx => {
                    const prev = ctx.get("test:var_fsm/swings");
                    ctx.set("test:var_fsm/swings", (prev || 0) + 1);
                });
                AnimatorBuilder.registerDefinition({
                    id: "test:var_fsm",
                    state_vars: [
                        { name: "armed", type: "bool", default: false },
                        { name: "swings", type: "int", default: 0 }
                    ],
                    layers: [{
                        id: "main", initial_state: "idle",
                        states: [ {id:"idle"}, {id:"swing", duration_ticks: 1} ],
                        transitions: [
                            { id:"i2s", from:"idle", to:"swing",
                              when: ["test:armed_and_counting"], on_fire: ["test:record_swing"] },
                            { id:"s2i", from:"swing", to:"idle", when_complete: true }
                        ]
                    }]
                });
                const h = AnimatorBuilder.instantiate("test:var_fsm", null);
                Animator.tick(h);
                const s0 = Animator.getState(h, "main");
                const c0 = Animator.get(h, "test:var_fsm/swings");
                Animator.set(h, "test:var_fsm/armed", true);
                Animator.tick(h);
                const s1 = Animator.getState(h, "main");
                const c1 = Animator.get(h, "test:var_fsm/swings");
                Animator.tick(h);
                const s2 = Animator.getState(h, "main");
                Animator.tick(h);
                const c2 = Animator.get(h, "test:var_fsm/swings");
                JSON.stringify({s0, c0, s1, c1, s2, c2});
                """;
        JsonObject r = JsonParser.parseString(engine.execute(script).asString()).getAsJsonObject();
        assertEquals("idle", r.get("s0").getAsString(), "guard reads ctx.get(armed)===false → stays idle");
        assertEquals(0, r.get("c0").getAsInt(), "swings default 0");
        assertEquals("swing", r.get("s1").getAsString(),
                "after Animator.set(armed,true) the guard reads ctx.get(armed)===true → idle→swing");
        assertEquals(1, r.get("c1").getAsInt(), "JS action wrote swings=1 via ctx.set");
        assertEquals("idle", r.get("s2").getAsString(), "swing(1) whenComplete→idle");
        assertEquals(2, r.get("c2").getAsInt(),
                "armed stayed true → idle re-fires i2s and the action accumulated via ctx.set");
    }

    /**
     * {@code autoTick} + {@code onTick} + {@code onStateChanged} registered together on a 3-state a→b→c→a
     * whenComplete chain, driven by {@code engine.tick()} (the FsmAutoTickModule on the script thread).
     * Asserts {@code onTick} fires once per tick (observing the post-advance state), {@code onStateChanged}
     * fires ONLY on version bumps (skips the dwell tick), and the observed states are in order.
     */
    @Test
    public void shouldAutoTickThreeStateChainWithOnTickAndOnStateChanged() throws ScriptException {
        engine.execute("""
                AnimatorBuilder.registerDefinition({
                    id: "test:chain3",
                    layers: [{
                        id: "main", initial_state: "a",
                        states: [ {id:"a", duration_ticks: 1},
                                  {id:"b", duration_ticks: 1},
                                  {id:"c", duration_ticks: 1} ],
                        transitions: [ {id:"a2b", from:"a", to:"b", when_complete: true},
                                       {id:"b2c", from:"b", to:"c", when_complete: true},
                                       {id:"c2a", from:"c", to:"a", when_complete: true} ]
                    }]
                });
                globalThis.__h = AnimatorBuilder.instantiate("test:chain3", null);
                globalThis.__ticks = [];
                globalThis.__changes = [];
                Animator.onTick(globalThis.__h, () => {
                    globalThis.__ticks.push(Animator.getState(globalThis.__h, "main"));
                });
                Animator.onStateChanged(globalThis.__h, () => {
                    globalThis.__changes.push(Animator.getState(globalThis.__h, "main"));
                });
                """);
        // engine.tick() drives the FsmAutoTickModule which advances the machine then fires the callbacks.
        engine.tick();   // a: elapsed 0→1, no change. onTick observes "a"; onStateChanged does NOT fire.
        engine.tick();   // a: 1>=1 → a2b → b. onTick "b"; onStateChanged "b".
        engine.tick();   // b: 1>=1 → b2c → c. onTick "c"; onStateChanged "c".
        engine.tick();   // c: 1>=1 → c2a → a. onTick "a"; onStateChanged "a".

        int h = Integer.parseInt(engine.execute("String(globalThis.__h)").asString());
        AnimatorApi api = new AnimatorApi();
        assertEquals("a", api.getState(h, "main"), "after 4 ticks the 3-state cycle returns to a");

        JsonObject r = JsonParser.parseString(
                engine.execute("JSON.stringify({ticks: globalThis.__ticks, changes: globalThis.__changes})").asString())
                .getAsJsonObject();
        var ticks = r.getAsJsonArray("ticks");
        var changes = r.getAsJsonArray("changes");
        assertEquals(4, ticks.size(), "onTick fired once per engine tick: " + ticks);
        assertEquals("a", ticks.get(0).getAsString());
        assertEquals("b", ticks.get(1).getAsString());
        assertEquals("c", ticks.get(2).getAsString());
        assertEquals("a", ticks.get(3).getAsString());
        assertEquals(3, changes.size(), "onStateChanged fired only on state changes, not the dwell tick: " + changes);
        assertEquals("b", changes.get(0).getAsString());
        assertEquals("c", changes.get(1).getAsString());
        assertEquals("a", changes.get(2).getAsString());
    }

    /**
     * Typed var coercion matrix from JS: declare {@code int}/{@code bool}/{@code float} in one machine, read
     * defaults, {@code set} each with a JS Number/boolean, round-trip via {@code get}, and assert {@code varType}
     * reports the right token. Verifies JS integers don't arrive as floats that fail validation, and bools
     * round-trip — all values cross the V8↔Java boundary as Integer/Double/Boolean.
     */
    @Test
    public void shouldRoundTripIntBoolFloatVarsFromJs() throws ScriptException {
        String script = """
                AnimatorBuilder.registerDefinition({
                    id: "test:types",
                    state_vars: [
                        { name: "count", type: "int",   default: 0 },
                        { name: "on",    type: "bool",  default: false },
                        { name: "spd",   type: "float", default: 0.0 }
                    ],
                    layers: [{ id: "main", initial_state: "x", states: [ {id:"x"} ] }]
                });
                const h = AnimatorBuilder.instantiate("test:types", null);
                const dCount = Animator.get(h, "test:types/count");
                const dOn    = Animator.get(h, "test:types/on");
                const dSpd   = Animator.get(h, "test:types/spd");
                const tCount = Animator.varType("test:types/count");
                const tOn    = Animator.varType("test:types/on");
                const tSpd   = Animator.varType("test:types/spd");
                Animator.set(h, "test:types/count", 7);
                Animator.set(h, "test:types/on", true);
                Animator.set(h, "test:types/spd", 2.5);
                const count = Animator.get(h, "test:types/count");
                const on    = Animator.get(h, "test:types/on");
                const spd   = Animator.get(h, "test:types/spd");
                JSON.stringify({dCount, dOn, dSpd, tCount, tOn, tSpd, count, on, spd});
                """;
        JsonObject r = JsonParser.parseString(engine.execute(script).asString()).getAsJsonObject();
        assertEquals(0, r.get("dCount").getAsInt());
        assertEquals(false, r.get("dOn").getAsBoolean());
        assertEquals(0.0, r.get("dSpd").getAsDouble(), 1e-6);
        assertEquals("int", r.get("tCount").getAsString());
        assertEquals("bool", r.get("tOn").getAsString());
        assertEquals("float", r.get("tSpd").getAsString());
        assertEquals(7, r.get("count").getAsInt(), "int var round-trips a JS integer Number");
        assertEquals(true, r.get("on").getAsBoolean(), "bool var round-trips a JS boolean");
        assertEquals(2.5, r.get("spd").getAsDouble(), 1e-6, "float var round-trips a JS double Number");
    }

    /**
     * Lifecycle: {@code dispose} releases one handle (it goes inert — {@code getState} returns ""), re-{@code
     * instantiate} of the same definition returns a FRESH handle starting from the initial state, and the old
     * handle stays inert while the new one ticks independently (the multi-instance contract).
     */
    @Test
    public void shouldDisposeAndReInstantiateSameDefinition() throws ScriptException {
        String script = """
                AnimatorBuilder.registerDefinition({
                    id: "test:relifecycle",
                    layers: [{
                        id: "main", initial_state: "a",
                        states: [ {id:"a", duration_ticks: 1}, {id:"b", duration_ticks: 1} ],
                        transitions: [ {id:"a2b", from:"a", to:"b", when_complete: true},
                                       {id:"b2a", from:"b", to:"a", when_complete: true} ]
                    }]
                });
                const h1 = AnimatorBuilder.instantiate("test:relifecycle", null);
                Animator.tick(h1); Animator.tick(h1);
                const s1 = Animator.getState(h1, "main");
                Animator.dispose(h1);
                const sAfter = Animator.getState(h1, "main");
                const h2 = AnimatorBuilder.instantiate("test:relifecycle", null);
                const h2Type = typeof h2;
                const sFresh = Animator.getState(h2, "main");
                Animator.tick(h2); Animator.tick(h2);
                const s2 = Animator.getState(h2, "main");
                const s1Again = Animator.getState(h1, "main");
                JSON.stringify({s1, sAfter, h2Type, sFresh, s2, s1Again});
                """;
        JsonObject r = JsonParser.parseString(engine.execute(script).asString()).getAsJsonObject();
        assertEquals("b", r.get("s1").getAsString(), "h1 advanced a→b after two ticks");
        assertEquals("", r.get("sAfter").getAsString(), "h1 inert immediately after dispose");
        assertEquals("number", r.get("h2Type").getAsString(), "re-instantiate returns a fresh int handle");
        assertEquals("a", r.get("sFresh").getAsString(), "the fresh instance starts at the initial state");
        assertEquals("b", r.get("s2").getAsString(), "h2 advanced a→b independently");
        assertEquals("", r.get("s1Again").getAsString(), "the old handle h1 stays inert");
    }

    /**
     * Imperative {@code Animator.goTo(h, layer, state)} from JS: forces an instant jump bypassing guards (no
     * transition declared). Proves goTo DEFERS to the next tick (sets pendingGoTo — state is unchanged before
     * the tick), the jump runs the source state's {@code on_exit} BEFORE the target's {@code on_enter} (both JS
     * actions pushing into the live owner's array), and a dwell tick fires no duplicate actions.
     */
    @Test
    public void shouldGoToFromJsAndRunEntryExitActions() throws ScriptException {
        String script = """
                AnimatorBuilder.registerAction("test", "enter_b", ctx => { ctx.owner().log.push("enter_b"); });
                AnimatorBuilder.registerAction("test", "exit_a",  ctx => { ctx.owner().log.push("exit_a"); });
                AnimatorBuilder.registerDefinition({
                    id: "test:goto",
                    layers: [{
                        id: "main", initial_state: "a",
                        states: [ {id:"a", on_exit:["test:exit_a"]},
                                  {id:"b", on_enter:["test:enter_b"]} ],
                        transitions: []
                    }]
                });
                const owner = { log: [] };
                const h = AnimatorBuilder.instantiate("test:goto", owner);
                const s0 = Animator.getState(h, "main");
                Animator.goTo(h, "main", "b");
                const log1 = owner.log.slice();
                const s1 = Animator.getState(h, "main");
                Animator.tick(h);
                const log2 = owner.log.slice();
                const s2 = Animator.getState(h, "main");
                Animator.tick(h);
                const log3 = owner.log.slice();
                const s3 = Animator.getState(h, "main");
                JSON.stringify({s0, s1, s2, s3, log1, log2, log3});
                """;
        JsonObject r = JsonParser.parseString(engine.execute(script).asString()).getAsJsonObject();
        assertEquals("a", r.get("s0").getAsString());
        assertEquals("a", r.get("s1").getAsString(), "goTo defers — state unchanged before the applying tick");
        assertEquals(0, r.get("log1").getAsJsonArray().size(), "no actions before the tick that applies goTo");
        assertEquals("b", r.get("s2").getAsString(), "goTo applied on tick → a→b");
        var log2 = r.get("log2").getAsJsonArray();
        assertEquals(2, log2.size(), "exit_a + enter_b both fired on the jump");
        assertEquals("exit_a", log2.get(0).getAsString(),
                "the source state's onExit runs before the target's onEnter");
        assertEquals("enter_b", log2.get(1).getAsString());
        assertEquals("b", r.get("s3").getAsString(), "b has no outgoing transition → dwell stays");
        assertEquals(2, r.get("log3").getAsJsonArray().size(),
                "no duplicate enter/exit actions on a dwell tick");
    }

    /**
     * The rich, per-tick scenario — NOT a single end-state blob. A JS {@code on_update} action runs every tick
     * and mutates BOTH the bound owner (the data source, via {@code ctx.owner().ticks++}) AND an internal typed
     * var (via {@code ctx.get/ctx.set} of {@code heat}). JS guards read {@code ctx.owner().speed} to drive
     * idle→walk→run→idle. The script records a per-tick snapshot array — each entry carries the active state,
     * the owner's speed + ticks, and the heat var — so the assertion observes, tick by tick: the complex state
     * switches, the tick count, the internal var mutation, and the two-way owner↔FSM interaction.
     */
    @Test
    public void shouldObservePerTickStateVarAndOwnerAcrossComplexScenario() throws ScriptException {
        String script = """
                AnimatorBuilder.registerCondition("test", "is_idle", ctx => ctx.owner().speed === 0);
                AnimatorBuilder.registerCondition("test", "is_walk", ctx => ctx.owner().speed > 0 && ctx.owner().speed <= 5);
                AnimatorBuilder.registerCondition("test", "is_run",  ctx => ctx.owner().speed > 5);
                AnimatorBuilder.registerAction("test", "tick_advance", ctx => {
                    ctx.owner().ticks = (ctx.owner().ticks || 0) + 1;                 // mutate the bound data source
                    const heat = ctx.get("test:rich/heat");                          // read the internal var
                    ctx.set("test:rich/heat", (heat || 0) + 1);                      // write it back
                });
                AnimatorBuilder.registerDefinition({
                    id: "test:rich",
                    state_vars: [ { name: "heat", type: "int", default: 0 } ],
                    layers: [{
                        id: "main", initial_state: "idle",
                        states: [
                            { id: "idle", on_update: ["test:tick_advance"] },
                            { id: "walk", on_update: ["test:tick_advance"] },
                            { id: "run",  on_update: ["test:tick_advance"] }
                        ],
                        transitions: [
                            { id: "i2w", from: "idle", to: "walk", when: ["test:is_walk"] },
                            { id: "w2r", from: "walk", to: "run",  when: ["test:is_run"] },
                            { id: "r2w", from: "run",  to: "walk", when: ["test:is_walk"] },
                            { id: "w2i", from: "walk", to: "idle", when: ["test:is_idle"] },
                            { id: "r2i", from: "run",  to: "idle", when: ["test:is_idle"] }
                        ]
                    }]
                });
                const owner = { speed: 0, ticks: 0 };
                const h = AnimatorBuilder.instantiate("test:rich", owner);
                const log = [];
                const snap = (label) => log.push({
                    s: Animator.getState(h, "main"),
                    sp: owner.speed,
                    ticks: owner.ticks,
                    heat: Animator.get(h, "test:rich/heat"),
                    label: label
                });
                Animator.tick(h);        snap("t1");          // idle (speed 0); on_update: ticks=1, heat=1
                owner.speed = 3;                               // JS writes the data source -> walk range
                Animator.tick(h);        snap("t2");          // idle.on_update ticks=2,heat=2; is_walk -> walk
                Animator.tick(h);        snap("t3");          // walk.on_update ticks=3,heat=3; stays walk
                owner.speed = 8;                               // accelerate -> run range
                Animator.tick(h);        snap("t4");          // walk.on_update ticks=4,heat=4; is_run -> run
                owner.speed = 0;                               // stop -> idle range
                Animator.tick(h);        snap("t5");          // run.on_update ticks=5,heat=5; is_idle -> idle
                JSON.stringify(log);
                """;
        JsonArray log = JsonParser.parseString(engine.execute(script).asString()).getAsJsonArray();
        assertEquals(5, log.size(), "five per-tick snapshots: " + log);
        // t1: initial idle, first on_update fired (ticks=1), heat var incremented to 1
        assertSnap(log.get(0).getAsJsonObject(), "idle", 0, 1, 1, "t1");
        // t2: speed flipped to 3 -> idle's guard is_walk fired -> walk; on_update ran on idle before the switch
        assertSnap(log.get(1).getAsJsonObject(), "walk", 3, 2, 2, "t2");
        // t3: dwelling in walk (speed still 3, no guard matches) -> ticks/heat keep advancing
        assertSnap(log.get(2).getAsJsonObject(), "walk", 3, 3, 3, "t3");
        // t4: speed 8 -> w2r -> run
        assertSnap(log.get(3).getAsJsonObject(), "run", 8, 4, 4, "t4");
        // t5: speed 0 -> r2i -> idle; owner.ticks + heat keep accumulating (data source + var are sticky)
        assertSnap(log.get(4).getAsJsonObject(), "idle", 0, 5, 5, "t5");
    }

    private static void assertSnap(com.google.gson.JsonObject snap, String state, int speed, int ticks, int heat, String label) {
        assertEquals(state, snap.get("s").getAsString(),
                label + ": state mismatch (expected " + state + ", got " + snap.get("s") + ")");
        assertEquals(speed, snap.get("sp").getAsInt(),
                label + ": owner.speed (data source) mismatch");
        assertEquals(ticks, snap.get("ticks").getAsInt(),
                label + ": owner.ticks (JS on_update action mutating ctx.owner()) mismatch");
        assertEquals(heat, snap.get("heat").getAsInt(),
                label + ": heat var (ctx.get/ctx.set internal property) mismatch");
    }

    private static boolean contains(com.google.gson.JsonArray arr, String value) {
        for (int i = 0; i < arr.size(); i++) {
            if (value.equals(arr.get(i).getAsString())) {
                return true;
            }
        }
        return false;
    }
}
