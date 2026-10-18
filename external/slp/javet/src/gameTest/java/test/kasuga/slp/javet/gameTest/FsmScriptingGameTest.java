package test.kasuga.slp.javet.gameTest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.scripting.ScriptConsole;
import lib.kasuga.scripting.value.ScriptValue;
import lib.kasuga.slp.javet.JavetScriptEngine;
import lib.kasuga.slp.javet.KasugaLibJavet;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;

/**
 * In-game SCRIPTING integration on the javet module's dedicated-server runtime: the production
 * {@link KasugaLibJavet#ENGINE_TYPE} (installed by the javet @Mod at boot — V8 native + FSM globals via
 * {@code FsmApiRegistration}) is used to run a JavaScript program that drives a state machine per-tick. The
 * scripting/modelling/core classes reach this game-test server as flat class directories on
 * additionalRuntimeClasspath (NOT project deps — those break the JPMS layer).
 *
 * <p>The JS records a per-tick snapshot — active state, owner.speed (the data source, JS-written + guard-read),
 * owner.ticks (a JS {@code on_update} action mutating {@code ctx.owner()}), and a {@code heat} typed var
 * ({@code ctx.get/ctx.set} internal property) — so the assertion observes the complex state switches
 * (idle→walk→run→idle), the tick-by-tick progression, the internal var mutation, and the two-way owner↔FSM
 * interaction, not just an end-state blob.
 */
@GameTestHolder
public final class FsmScriptingGameTest {

    private FsmScriptingGameTest() {
    }

    @GameTest(template = "empty", templateNamespace = "kasuga_lib_javet", timeoutTicks = 200)
    public static void jsDrivesStateMachinePerTickInDedicatedServer(GameTestHelper helper) {
        if (KasugaLibJavet.ENGINE_TYPE == null) {
            helper.fail("KasugaLibJavet.ENGINE_TYPE is null — the javet @Mod did not install V8 + the FSM "
                    + "globals in the dedicated-server runtime");
            return;
        }
        JavetScriptEngine engine;
        try {
            engine = KasugaLibJavet.ENGINE_TYPE.create(ScriptConsole.errorsToStderr());
        } catch (Exception e) {
            helper.fail("V8 engine creation failed in the server runtime (native unavailable?): " + e);
            return;
        }

        String js = """
                AnimatorBuilder.registerCondition("test", "is_idle", ctx => ctx.owner().speed === 0);
                AnimatorBuilder.registerCondition("test", "is_walk", ctx => ctx.owner().speed > 0 && ctx.owner().speed <= 5);
                AnimatorBuilder.registerCondition("test", "is_run",  ctx => ctx.owner().speed > 5);
                AnimatorBuilder.registerAction("test", "tick_advance", ctx => {
                    ctx.owner().ticks = (ctx.owner().ticks || 0) + 1;
                    const heat = ctx.get("test:rich/heat");
                    ctx.set("test:rich/heat", (heat || 0) + 1);
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
                    s: Animator.getState(h, "main"), sp: owner.speed,
                    ticks: owner.ticks, heat: Animator.get(h, "test:rich/heat"), label: label
                });
                Animator.tick(h);   snap("t1");        // idle (speed 0); on_update ticks=1, heat=1
                owner.speed = 3;                       // JS writes the data source -> walk range
                Animator.tick(h);   snap("t2");        // ticks=2,heat=2; is_walk -> walk
                Animator.tick(h);   snap("t3");        // ticks=3,heat=3; stays walk
                owner.speed = 8;                       // -> run range
                Animator.tick(h);   snap("t4");        // ticks=4,heat=4; is_run -> run
                owner.speed = 0;                       // -> idle range
                Animator.tick(h);   snap("t5");        // ticks=5,heat=5; is_idle -> idle
                JSON.stringify(log);
                """;

        try {
            ScriptValue result = engine.execute(js);
            JsonArray log = JsonParser.parseString(result.asString()).getAsJsonArray();
            if (log.size() != 5) {
                helper.fail("expected 5 per-tick snapshots, got " + log.size() + ": " + log);
                return;
            }
            assertSnap(helper, log.get(0).getAsJsonObject(), "idle", 0, 1, 1, "t1");
            assertSnap(helper, log.get(1).getAsJsonObject(), "walk", 3, 2, 2, "t2");
            assertSnap(helper, log.get(2).getAsJsonObject(), "walk", 3, 3, 3, "t3");
            assertSnap(helper, log.get(3).getAsJsonObject(), "run", 8, 4, 4, "t4");
            assertSnap(helper, log.get(4).getAsJsonObject(), "idle", 0, 5, 5, "t5");
            helper.succeed();
        } catch (Exception e) {
            helper.fail("in-game scripting test failed: " + e);
        } finally {
            try {
                engine.close();
            } catch (Exception ignored) {
                // best-effort V8 cleanup
            }
        }
    }

    private static void assertSnap(GameTestHelper helper, JsonObject snap, String state, int speed, int ticks, int heat, String label) {
        if (!state.equals(snap.get("s").getAsString())) {
            helper.fail(label + ": state mismatch (expected " + state + ", got " + snap.get("s") + ")");
            return;
        }
        if (snap.get("sp").getAsInt() != speed) {
            helper.fail(label + ": owner.speed (data source) mismatch");
            return;
        }
        if (snap.get("ticks").getAsInt() != ticks) {
            helper.fail(label + ": owner.ticks (JS on_update via ctx.owner()) mismatch");
            return;
        }
        if (snap.get("heat").getAsInt() != heat) {
            helper.fail(label + ": heat var (ctx.get/ctx.set) mismatch");
        }
    }
}
