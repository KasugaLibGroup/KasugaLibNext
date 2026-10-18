package test.kasuga.modelling;

import com.mojang.serialization.Codec;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;
import lib.kasuga.rendering.models.uml.dynamic.fsm.StateMachine;
import lib.kasuga.rendering.models.uml.dynamic.fsm.VarProvider;
import lib.kasuga.rendering.models.uml.dynamic.fsm.state.ParameterSpec;

import java.util.Map;

/**
 * Render projection for the fan test machine ({@code kasuga_lib:fan_machine}). Drives the
 * v2.0 ease formulas on the main thread every game tick — the fan's only stateful animation:
 *
 * <ul>
 *   <li><b>target speed</b> = {@code SPEEDS[gear]} where {@code gear} is the active state index of the
 *       {@code "gear"} layer (0..3 = off/g1/g2/g3), a compile-time constant;</li>
 *   <li><b>acceleration</b> = exponential approach {@code current += (target - current)·(1 - e^(-dt/τ))},
 *       τ = 0.5s (locked formula table);</li>
 *   <li><b>angle</b> = decorative integral {@code angle += current·dt} — local only, not persisted,
 *       not aligned across ends, no checkpoints.</li>
 * </ul>
 *
 * <p>Writes the two derived ({@code externalWritable=false}) parameters back through
 * {@link StateMachine#setInternal} (single source of truth) and projects the render values into
 * {@code out} keyed without the {@code "query."} prefix — {@code "speed"} and {@code "angle"} are injected
 * into the formula namespace by {@link lib.kasuga.rendering.models.uml.dynamic.fsm.FsmPoseDriver} so the
 * fan clip's formula tracks read {@code query.speed} / {@code query.angle}.
 *
 * <p>Per-instance state ({@code currentSpeed}/{@code angle}) crosses ticks; OFF freezes naturally because
 * the speed eases to 0 and the angle stops accumulating — no gating variable needed.
 */
public class FanVarProvider implements VarProvider {

    /** Derived current speed (°/s), id {@code kasuga_lib:fan/current_speed}. */
    public static final ParameterSpec<Float> CURRENT_SPEED = ParameterSpec.<Float>parameter(
            Id.fromNamespaceAndPath("kasuga_lib", "fan/current_speed"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .externalWritable(false)
            .build();

    /** Derived angle (°), id {@code kasuga_lib:fan/angle}. */
    public static final ParameterSpec<Float> ANGLE = ParameterSpec.<Float>parameter(
            Id.fromNamespaceAndPath("kasuga_lib", "fan/angle"), Float.class, Codec.FLOAT)
            .defaultValue(0f)
            .externalWritable(false)
            .build();

    /**
     * Target speeds per gear (layer active state index 0..3) in °/s — the phase rate {@code dθ/dt}, which
     * equals the GROUP's absolute rotation speed (re-anchored: 1/2/3 gear = 15/30/45 °/s). The fan
     * rotor's LOCAL rotation is 11·θ, so its absolute visual speed is 12× the group's (group θ + fan 11θ).
     */
    static final float[] SPEEDS = {0f, 15f, 30f, 45f};

    /** Ease time constant in seconds — locked with the formula table. */
    static final float TAU = 0.5f;

    private float currentSpeed;
    private float angle;

    @Override
    public void provide(StateMachine<?> machine, float dt, Map<String, Float> out) {
        int gear = machine.layer("gear").activeStateIndex();
        if (gear < 0) {
            gear = 0;
        }
        float target = SPEEDS[gear];
        currentSpeed += (target - currentSpeed) * (1f - (float) Math.exp(-dt / TAU));
        angle += currentSpeed * dt;
        machine.setInternal(CURRENT_SPEED, currentSpeed);
        machine.setInternal(ANGLE, angle);
        out.put("speed", currentSpeed);
        out.put("angle", angle);
    }
}