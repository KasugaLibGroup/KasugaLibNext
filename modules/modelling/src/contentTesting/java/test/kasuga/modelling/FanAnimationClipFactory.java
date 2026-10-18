package test.kasuga.modelling;

import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Id;

import java.util.List;

/**
 * v2.0 formula track for the fan FSM clip ({@code kasuga_lib:fan_fsm}): all visuals derive from
 * {@code query.angle} (the decorative speed integral) — no ramp parameters, no gating variables.
 *
 * <ul>
 *   <li>group:      y = {@code query.angle} — the group's absolute angle (θ); 1/2/3 gear turns 15/30/45 °/s</li>
 *   <li>fan rotor:  y = {@code 11 * query.angle} — LOCAL rotation = 11θ, so the rotor's absolute visual
 *       speed = θ (group) + 11θ (local) = 12θ, i.e. exactly 12× the group's</li>
 *   <li>cover sway:  z = {@code sin(rad(2 * query.angle)) * 30} (30° amplitude, phase = 2θ — one full sway
 *       per half group revolution, 12s at gear 1 (15°/s); +30° at θ=45°, −30° at θ=135°)</li>
 * </ul>
 */
public final class FanAnimationClipFactory {

    private FanAnimationClipFactory() {
    }

    public static AnimationClip fanClip() {
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
}