package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.formula.compute.data.Namespace;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** Pure conversion of a state-machine layer snapshot into a render-time pose. */
public final class FsmPoseComposer {

    /** One game tick in seconds. */
    public static final float TICK_SECONDS = 1f / 20f;

    private FsmPoseComposer() {
    }

    /** Decodes clip formulas once so later render assignments do not silently disappear. */
    public static void warmUp(StateMachine<?> machine, Namespace namespace) {
        for (Layer<?> layer : machine.layers()) {
            for (State<?> state : layer.states) {
                if (state.hasClip()) {
                    PoseTarget.ClipTarget.sample(
                            state.clipSampler(), state.clipData(), 0f, namespace);
                }
            }
        }
    }

    /** Captures the mutable machine's layer state into an immutable render target. */
    public static PoseTarget snapshot(StateMachine<?> machine) {
        List<? extends Layer<?>> layers = machine.layers();
        List<PoseTarget.LayerTarget> layerTargets = new ArrayList<>(layers.size());
        List<BlendMode> modes = new ArrayList<>(layers.size());
        List<Float> weights = new ArrayList<>(layers.size());
        List<BoneMask> masks = new ArrayList<>(layers.size());
        for (Layer<?> layer : layers) {
            layerTargets.add(new PoseTarget.LayerTarget(
                    layer.active(),
                    layer.activeTransition(),
                    layer.transitionElapsed(),
                    layer.clipTarget()));
            modes.add(layer.mode());
            weights.add(layer.weight());
            masks.add(layer.boneMask());
        }
        return new PoseTarget(layerTargets, modes, weights, masks);
    }

    public static void compose(
            Blender blender,
            PoseTarget snapshot,
            float interpolationAlpha,
            @Nullable Namespace namespace
    ) {
        blender.reset();
        List<PoseTarget.LayerTarget> layerTargets = snapshot.layers();
        for (int i = 0; i < layerTargets.size(); i++) {
            PoseTarget.LayerTarget layerTarget = layerTargets.get(i);
            State<?> active = layerTarget.activeState();
            Transition<?> transition = layerTarget.activeTransition();
            PoseTarget.ClipTarget clip = layerTarget.clip();
            Pose pose;
            if (active == null) {
                pose = Pose.empty();
            } else if (transition != null) {
                float crossFade = transition.crossFadeSeconds();
                float alpha = crossFade <= 0f
                        ? 1f
                        : Math.min(1f, (layerTarget.transitionElapsed()
                        + interpolationAlpha * TICK_SECONDS) / crossFade);
                Pose fromPose = clip != null
                        ? poseOf(active, clipTimeAt(clip, interpolationAlpha), namespace)
                        : active.buildPose();
                Pose toPose = poseOf(transition.to(), 0f, namespace);
                pose = PoseBlend.blend(fromPose, toPose, alpha);
            } else {
                pose = clip != null
                        ? poseOf(active, clipTimeAt(clip, interpolationAlpha), namespace)
                        : active.buildPose();
            }
            blender.applyLayer(
                    snapshot.modes().get(i),
                    pose,
                    snapshot.weights().get(i),
                    snapshot.masks().get(i));
        }
    }

    private static Pose poseOf(State<?> state, float time, @Nullable Namespace namespace) {
        if (state == null) {
            return Pose.empty();
        }
        if (state.hasClip()) {
            return new Pose.Builder()
                    .merge(state.buildPose())
                    .merge(PoseTarget.ClipTarget.sample(
                            state.clipSampler(), state.clipData(), time, namespace))
                    .build();
        }
        return state.buildPose();
    }

    private static float clipTimeAt(PoseTarget.ClipTarget clip, float interpolationAlpha) {
        float fraction = Math.clamp(interpolationAlpha, 0f, 1f);
        float time = Math.fma(
                clip.time() - clip.prevTime(), fraction, clip.prevTime());
        float duration = clip.duration();
        return duration > 0f && clip.loop() ? time % duration : Math.min(time, duration);
    }
}
