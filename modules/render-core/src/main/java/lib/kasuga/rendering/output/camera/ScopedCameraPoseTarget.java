package lib.kasuga.rendering.output.camera;

import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTarget;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.AnimationTargetRef;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.FrameInputKey;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.OutputState;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.RenderSampleContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetPublication;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TimelineValueHistory;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

final class ScopedCameraPoseTarget implements AnimationTarget<ScopedCameraPoseTarget.Output> {

    static final FrameInputKey<Double> RENDER_TIME =
            new FrameInputKey<>("camera-animation/render-time", Double.class);

    private static final AnimationTargetRef REF = AnimationTargetRef.cameraPose();
    private final TimelineValueHistory<CameraPose> history = new TimelineValueHistory<>();
    private volatile CameraPose sampledPose;

    @Override
    public AnimationTargetRef ref() {
        return REF;
    }

    @Override
    public synchronized void publish(TargetPublication<Output> publication) {
        if (!(publication.outputState() instanceof OutputState.Active<Output> active)) {
            return;
        }
        history.append(active.currentOutput().samples().stream()
                .map(sample -> new TimelineValueHistory.TimedValue<>(
                        sample.time(), sample.pose()))
                .toList());
    }

    synchronized void beginHostAdvance(double previousTime) {
        history.retainFrom(previousTime);
    }

    synchronized void resetHistory() {
        history.clearWith(() -> sampledPose = null);
    }

    @Override
    public synchronized void sample(RenderSampleContext context) {
        history.resolveFrame(
                context,
                RENDER_TIME,
                "camera render time is missing",
                ScopedCameraPoseTarget::blend).ifPresent(pose -> sampledPose = pose);
    }

    @Override
    public synchronized void sample(
            TargetPublication<Output> publication,
            RenderSampleContext context
    ) {
        sample(context);
    }

    CameraPose sampledPose() {
        return sampledPose;
    }

    @Override
    public synchronized void close() {
        history.clear();
        sampledPose = null;
    }

    private static CameraPose blend(CameraPose left, CameraPose right, float alpha) {
        EnumMap<CameraAnimationClip.Channel, Double> values =
                new EnumMap<>(CameraAnimationClip.Channel.class);
        for (CameraAnimationClip.Channel channel : CameraAnimationClip.Channel.values()) {
            Double from = left.channels().get(channel);
            Double to = right.channels().get(channel);
            if (from != null) {
                values.put(channel, to == null ? from : from + (to - from) * alpha);
            } else if (to != null) {
                values.put(channel, to);
            }
        }
        return new CameraPose(values);
    }

    record Output(List<TimedPose> samples) {
        Output {
            samples = List.copyOf(Objects.requireNonNull(samples, "samples"));
            if (samples.isEmpty()) {
                throw new IllegalArgumentException("samples must not be empty");
            }
        }
    }

    record TimedPose(double time, double sourceTime, CameraPose pose) {
        TimedPose {
            if (!Double.isFinite(time) || time < 0.0) {
                throw new IllegalArgumentException("time must be finite and non-negative");
            }
            if (!Double.isFinite(sourceTime) || sourceTime < 0.0) {
                throw new IllegalArgumentException("sourceTime must be finite and non-negative");
            }
            Objects.requireNonNull(pose, "pose");
        }
    }
}
