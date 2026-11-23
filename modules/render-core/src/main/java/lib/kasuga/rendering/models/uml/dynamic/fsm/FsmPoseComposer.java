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
            Pose pose = sampleLayer(layerTarget.activeState(), layerTarget.activeTransition(),
                    layerTarget.transitionElapsed(), layerTarget.clip(), interpolationAlpha, namespace);
            blender.applyLayer(snapshot.modes().get(i), pose, snapshot.weights().get(i), snapshot.masks().get(i));
        }
    }

    /** Samples the current logical tick on the machine's owning thread. Returns true if a clip wrapped. */
    static boolean composeTick(Blender blender, StateMachine<?> machine, Namespace namespace) {
        blender.reset();
        boolean wrapped = false;
        for (Layer<?> layer : machine.layers()) {
            PoseTarget.ClipTarget clip = layer.clipTarget();
            Pose pose = sampleLayer(layer.active(), layer.activeTransition(), layer.transitionElapsed(),
                    clip, 1f, namespace);
            blender.applyLayer(layer.mode(), pose, layer.weight(), layer.boneMask());
            wrapped |= crossesClipLoopBoundary(clip);
        }
        return wrapped;
    }

    private static Pose sampleLayer(State<?> active, Transition<?> transition, float transitionElapsed,
                                    PoseTarget.ClipTarget clip, float interpolationAlpha,
                                    @Nullable Namespace namespace) {
        if (active == null) return Pose.empty();
        Pose from = clip != null ? poseOf(active, clipTimeAt(clip, interpolationAlpha), namespace) : active.buildPose();
        if (transition == null) return from;
        float crossFade = transition.crossFadeSeconds();
        float alpha = crossFade <= 0f ? 1f
                : Math.min(1f, (transitionElapsed + interpolationAlpha * TICK_SECONDS) / crossFade);
        return PoseBlend.blend(from, poseOf(transition.to(), 0f, namespace), alpha);
    }

    private static boolean crossesClipLoopBoundary(PoseTarget.ClipTarget clip) {
        if (clip == null || !clip.loop()) return false;
        float duration = clip.duration();
        return Float.isFinite(duration) && duration > 0f && clip.time() > clip.prevTime()
                && Math.floor(clip.prevTime() / duration) < Math.floor(clip.time() / duration);
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
