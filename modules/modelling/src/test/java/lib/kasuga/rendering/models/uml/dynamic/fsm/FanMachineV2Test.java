package lib.kasuga.rendering.models.uml.dynamic.fsm;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import lib.kasuga.formula.Code;
import lib.kasuga.formula.compute.data.Namespace;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.animation.ClipSampler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.codec.StateMachineDefinition;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.StateVar;
import lib.kasuga.rendering.models.uml.math.QuaternionHelper;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v2.0 fan machine JVM test: the {@code kasuga_lib:fan_machine}
 * definition (identical JSON to {@code ModellingContentTest}) built through
 * {@link DefinitionStateMachineFactory} with isolated registries.
 *
 * <p>U9 FSM flow: the {@code gear} layer cycles {@code off→g1→g2→g3→off} on the {@code cycle} trigger
 * (each cross-fade is 0.25s). Derived-parameter visibility: the machine's declared vars include
 * {@code kasuga_lib:fan/current_speed} / {@code kasuga_lib:fan/angle} as {@code externalWritable=false}
 * {@link ParameterSpec}s, so external {@code set} throws while the provider path ({@code setInternal}) works.
 *
 * <p>U1–U8 formula anchors (locked formula table): the driver-side ease formula is replicated verbatim
 * ({@code FanVarProvider} lives in the contentTesting source set and is not on the test classpath), τ=0.5s,
 * dt=1/20. Discrete 20 steps = 1s → ~86.4% (continuous limit {@code 1−e^(−2)}=86.5%); 40 steps = 2s → ~98.2%.
 * Angle integrates {@code speed·dt}; cover sway {@code sin(rad(2·angle))·30} has a 12s cycle at gear 1
 * (phase 2θ = 30°/s): +30° at θ=45°, −30° at θ=135°.
 */
class FanMachineV2Test {

    private static final float DT = 1f / 20f;
    private static final float TAU = 0.5f;
    private static final float[] SPEEDS = {0f, 15f, 30f, 45f};

    /** Same definition JSON string as {@code ModellingContentTest.FAN_MACHINE_JSON} (copied). */
    private static final String FAN_MACHINE_JSON = """
            {
              "id": "kasuga_lib:fan_machine",
              "state_vars": [
                { "name": "cycle", "type": "bool", "default": false, "ephemeral": true, "reference": "kasuga_lib:fan/cycle" },
                { "name": "kasuga_lib:fan/current_speed", "reference": "kasuga_lib:fan/current_speed" },
                { "name": "kasuga_lib:fan/angle", "reference": "kasuga_lib:fan/angle" }
              ],
              "layers": [
                {
                  "id": "gear",
                  "mode": "base",
                  "initial_state": "off",
                  "states": [
                    { "id": "off", "clip": { "id": "kasuga_lib:fan_fsm", "loop": true } },
                    { "id": "g1", "clip": { "id": "kasuga_lib:fan_fsm", "loop": true } },
                    { "id": "g2", "clip": { "id": "kasuga_lib:fan_fsm", "loop": true } },
                    { "id": "g3", "clip": { "id": "kasuga_lib:fan_fsm", "loop": true } }
                  ],
                  "transitions": [
                    { "id": "off_to_g1", "from": "off", "to": "g1", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
                    { "id": "g1_to_g2", "from": "g1", "to": "g2", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
                    { "id": "g2_to_g3", "from": "g2", "to": "g3", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
                    { "id": "g3_to_off", "from": "g3", "to": "off", "trigger_on": "cycle", "cross_fade_seconds": 0.25 }
                  ]
                }
              ]
            }
            """;

    private static final StateVar<Boolean> CYCLE = StateVar.trigger(Id.fromNamespaceAndPath("kasuga_lib", "fan/cycle"));

    /** Replicates {@code FanVarProvider.CURRENT_SPEED} (same id, externalWritable=false). */
    private static final ParameterSpec<Float> CURRENT_SPEED = ParameterSpec.<Float>parameter(
            Id.fromNamespaceAndPath("kasuga_lib", "fan/current_speed"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .externalWritable(false)
            .build();

    /** Replicates {@code FanVarProvider.ANGLE} (same id, externalWritable=false). */
    private static final ParameterSpec<Float> ANGLE = ParameterSpec.<Float>parameter(
            Id.fromNamespaceAndPath("kasuga_lib", "fan/angle"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .externalWritable(false)
            .build();

    private static AnimationClip fanClip() {
        return new AnimationClip(
                Id.fromNamespaceAndPath("kasuga_lib", "fan_fsm"),
                12f,
                List.of(), List.of(), List.of(),
                List.of(
                        new AnimationClip.FunctionTrack("group", AnimationClip.FunctionChannel.ROTATE,
                                "", "query.angle", ""),
                        new AnimationClip.FunctionTrack("fan", AnimationClip.FunctionChannel.ROTATE,
                                "", "11 * query.angle", ""),
                        new AnimationClip.FunctionTrack("cover", AnimationClip.FunctionChannel.ROTATE,
                                "", "", "sin(rad(2 * query.angle)) * 30")
                )
        );
    }

    private static StateMachineDefinition decode(String json) {
        JsonElement element = JsonParser.parseString(json);
        DataResult<StateMachineDefinition> result = StateMachineDefinition.CODEC.parse(JsonOps.INSTANCE, element);
        return result.getOrThrow();
    }

    /** Isolated registries; server-style build (sink=null → logicEnabled). */
    private static StateMachine<Object> buildFanMachine() {
        FsmRegistries registries = FsmRegistries.create();
        registries.vars().register(CYCLE);
        registries.vars().register(CURRENT_SPEED);
        registries.vars().register(ANGLE);
        registries.clips().register(Id.fromNamespaceAndPath("kasuga_lib", "fan_fsm"), ClipSampler.INSTANCE, fanClip());
        return new DefinitionStateMachineFactory<Object>(registries.functions(), registries.vars(), registries.clips())
                .build(new Object(), decode(FAN_MACHINE_JSON), null);
    }

    private static void assertRotationEquals(Quaternionf expected, Quaternionf actual, String label) {
        float dot = expected.normalize().dot(actual.normalize());
        assertTrue(Math.abs(dot) > 1f - 1e-2f,
                "rotation mismatch for " + label + ": expected " + expected + ", actual " + actual + " (dot " + dot + ")");
    }

    //region U9 — FSM state flow (gear = state)

    @Test
    void fsmCyclesThroughGears() {
        StateMachine<Object> machine = buildFanMachine();
        assertEquals("off", machine.activeStateId("gear"), "initial state is off (gear 0)");

        // 4 triggers verify the full off→g1→g2→g3→off ring. Each transition cross-fades over 0.25s, so
        // keep ticking (bounded) until the target state becomes active before firing the next trigger.
        String[] expected = {"g1", "g2", "g3", "off"};
        for (String target : expected) {
            machine.trigger(CYCLE);
            machine.tick(DT);
            for (int i = 0; i < 20 && !target.equals(machine.activeStateId("gear")); i++) {
                machine.tick(DT);
            }
            assertEquals(target, machine.activeStateId("gear"), "gear should reach " + target);
        }
    }

    @Test
    void activeStateIndexMapsToGear() {
        StateMachine<Object> machine = buildFanMachine();
        assertEquals(0, machine.layer("gear").activeStateIndex(), "off → gear 0");
        machine.trigger(CYCLE);
        machine.tick(DT);
        for (int i = 0; i < 20 && machine.layer("gear").activeStateIndex() != 1; i++) {
            machine.tick(DT);
        }
        assertEquals(1, machine.layer("gear").activeStateIndex(), "g1 → gear 1");
    }

    //endregion

    //region derived-parameter visibility

    @Test
    void declaredVarsContainDerivedParams() {
        StateMachine<Object> machine = buildFanMachine();
        assertTrue(machine.declaredVars().contains(CURRENT_SPEED), "current_speed must be declared");
        assertTrue(machine.declaredVars().contains(ANGLE), "angle must be declared");
        assertTrue(machine.declaredVars().contains(CYCLE), "cycle trigger must be declared");

        // the derived params are ParameterSpecs with externalWritable=false
        for (StateVar<?> var : machine.declaredVars()) {
            if (var.equals(CURRENT_SPEED) || var.equals(ANGLE)) {
                ParameterSpec<?> spec = (ParameterSpec<?>) var;
                assertFalse(spec.externalWritable(), spec.id() + " must be machine-internal (derived)");
            }
        }
    }

    @Test
    void externalSetOfDerivedParamThrows() {
        StateMachine<Object> machine = buildFanMachine();
        assertThrows(IllegalStateException.class, () -> machine.set(CURRENT_SPEED, 100f),
                "external set of a derived parameter must throw");
        assertThrows(IllegalStateException.class, () -> machine.set(ANGLE, 100f),
                "external set of a derived parameter must throw");

        // the provider path (machine-internal) bypasses the guard
        machine.setInternal(CURRENT_SPEED, 42f);
        assertEquals(42f, machine.get(CURRENT_SPEED));
    }

    //endregion

    //region U1–U8 — locked formula-table anchors (replicating FanVarProvider verbatim)

    /** One discrete ease step with dt=1/20, τ=0.5 — identical to {@code FanVarProvider.provide}. */
    private static float easeStep(float current, float target) {
        return current + (target - current) * (1f - (float) Math.exp(-DT / TAU));
    }

    @Test
    void easeConvergesDiscrete() {
        float target = SPEEDS[1]; // 15 °/s (gear 1 group rate)
        float current = 0f;
        for (int i = 0; i < 20; i++) {
            current = easeStep(current, target);
        }
        // 20 discrete steps at dt=1/20 sum to 1.0s → 1 − (1−e^(−0.1))^20 ≈ 86.4% of target.
        // The 86.5% figure is the continuous limit 1 − e^(−2); assert with the discrete anchor 86.4 ± 0.5%.
        assertEquals(0.864f * target, current, 0.005f * target, "1s discrete ease");

        current = 0f;
        for (int i = 0; i < 40; i++) {
            current = easeStep(current, target);
        }
        // 2s → 1 − (1−e^(−0.1))^40 ≈ 98.2% (±0.3%)
        assertEquals(0.982f * target, current, 0.003f * target, "2s discrete ease");
    }

    @Test
    void speedIsContinuousAndMonotone() {
        // accelerate 0→1 (U3): target 15, monotone non-decreasing, per-step delta bounded
        float target = SPEEDS[1];
        float current = 0f;
        float maxStep = target * (1f - (float) Math.exp(-DT / TAU)); // first step is the largest delta
        for (int i = 0; i < 40; i++) {
            float next = easeStep(current, target);
            assertTrue(next >= current, "acceleration must be monotone non-decreasing");
            assertTrue(next - current <= maxStep + 1e-3f, "no jump: step delta bounded");
            current = next;
        }
        // 40 discrete steps = 2s → ~98.2% of target (the 2s anchor), not full convergence
        assertEquals(0.982f * target, current, 0.003f * target, "accelerated toward target");

        // decelerate 3→2 (U4): from 45 toward 30, monotone non-increasing
        float decelTarget = SPEEDS[2];
        current = SPEEDS[3];
        for (int i = 0; i < 40; i++) {
            float next = easeStep(current, decelTarget);
            assertTrue(next <= current, "deceleration must be monotone non-increasing");
            current = next;
        }
        // same 2s anchor: remaining gap = (initial − target)·(1 − 0.9817) ≈ 15·0.0183 ≈ 0.27 °/s
        assertEquals(decelTarget + (SPEEDS[3] - decelTarget) * (1f - 0.9817f),
                current, 0.003f * decelTarget, "decelerated toward target");
    }

    @Test
    void offDeceleratesToStopAndAngleFreezes() {
        // →OFF (U5): from 15 toward 0, speed decays and the angle stops accumulating
        float current = SPEEDS[1];
        float angle = 0f;
        for (int i = 0; i < 80; i++) {
            current = easeStep(current, 0f);
            angle += current * DT;
        }
        assertEquals(0f, current, 1f, "speed decays to ~0");
        float frozen = angle;
        for (int i = 0; i < 40; i++) {
            current = easeStep(current, 0f);
            angle += current * DT;
        }
        assertEquals(frozen, angle, 0.5f, "angle freezes once speed ≈ 0");
    }

    @Test
    void angleIntegratesSpeed() {
        // U6: discrete θ = Σ speed·dt over 20 steps at gear 1, compared against the closed form of the
        // geometric sum Σ(1−r^k)·dt: r = e^(−dt/τ). The discrete integral (~212°) exceeds the continuous
        // integral ∫360·(1−e^(−2t))dt = 204.4° because the Euler step samples the speed at each step start.
        float target = SPEEDS[1];
        double r = Math.exp(-DT / TAU);
        double expectedDiscrete = target * DT * (20 - r * (1 - Math.pow(r, 20)) / (1 - r));

        float current = 0f;
        float angle = 0f;
        for (int i = 0; i < 20; i++) {
            current = easeStep(current, target);
            angle += current * DT;
        }
        assertEquals(expectedDiscrete, angle, 1e-3f, "angle = Σ speed·dt (discrete closed form)");
        assertTrue(angle > 0f && angle > 5f, "angle accumulated over the acceleration ramp (~8.8° at gear 1)");
    }

    @Test
    void coverFormulaEndpoints() {
        // U7: sin(rad(2·angle))·30 endpoints (rad = degrees→radians; the locked values)
        assertEquals(30f, (float) Math.sin(Math.toRadians(90f)) * 30f, 1e-4f, "2θ=90 (θ=45) → +30°");
        assertEquals(-30f, (float) Math.sin(Math.toRadians(270f)) * 30f, 1e-4f, "2θ=270 (θ=135) → −30°");
        // zero phase: no sway at θ=0 / full cycle at 2θ=360 (θ=180)
        assertEquals(0f, (float) Math.sin(Math.toRadians(0f)) * 30f, 1e-4f, "θ=0 → 0°");
        assertEquals(0f, (float) Math.sin(Math.toRadians(360f)) * 30f, 1e-4f, "θ=180 → 0° (full cycle)");
    }

    @Test
    void clipFormulaTracksReadAngleThroughBuiltMachine() {
        // U8: the built machine's gear states resolve the registered fan clip; group y = θ, fan local y = 2θ,
        // cover at the +30 endpoint — tying definition → clip registry → formula sampling together.
        StateMachine<Object> machine = buildFanMachine();
        State<?> active = machine.layer("gear").active();
        assertNotNull(active);
        assertTrue(active.hasClip(), "every gear state carries the fan clip");
        assertEquals(Id.fromNamespaceAndPath("kasuga_lib", "fan_fsm"), ((AnimationClip) active.clipData()).id());

        Namespace ns = new Namespace(Code.ROOT_NAMESPACE);
        ClipSampler.INSTANCE.sample((AnimationClip) active.clipData(), 0f, ns); // warm-up registers query.angle
        float angle = 45f; // gear-1 rate: 3s → cover +30° endpoint (2θ=90), group 45°, fan local 11·45
        ns.assign("query.angle", angle);
        Pose pose = ClipSampler.INSTANCE.sample((AnimationClip) active.clipData(), angle / 15f, ns);

        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 11f * angle, 0f), pose.bones().get("fan").transform().getRotation(), "fan (local 11θ → absolute 12θ)");
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, 0f, 30f), pose.bones().get("cover").transform().getRotation(), "cover");
        assertRotationEquals(QuaternionHelper.fromXYZDegrees(0f, angle, 0f), pose.bones().get("group").transform().getRotation(), "group");
    }

    //endregion
}