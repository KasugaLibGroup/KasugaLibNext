package lib.kasuga.rendering.models.mc.proxies;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationLibrary;
import lib.kasuga.rendering.models.uml.dynamic.animation.output.PoseOutput;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.PlaybackTimeResolution;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetEvaluationContext;
import lib.kasuga.rendering.models.uml.dynamic.animation.scope.TargetOutputEvaluator;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Samples a native model clip selected by a committed playback definition.
 *
 * <p>The definition-to-clip map is presentation configuration. Controllers decide when a
 * definition starts, stops or changes; this evaluator only turns the committed source time into a
 * pose publication.
 */
public final class ModelClipPoseEvaluator implements TargetOutputEvaluator<PoseOutput> {

    private final Supplier<@Nullable ModelInstance> model;
    private final Map<String, String> clipByDefinition;

    public ModelClipPoseEvaluator(
            Supplier<@Nullable ModelInstance> model,
            Map<String, String> clipByDefinition
    ) {
        this.model = Objects.requireNonNull(model, "model");
        this.clipByDefinition = Map.copyOf(clipByDefinition);
    }

    @Override
    public @Nullable PoseOutput evaluate(TargetEvaluationContext context) {
        PlaybackTimeResolution selected = null;
        for (PlaybackTimeResolution time : context.playbackTimes()) {
            if (clipByDefinition.containsKey(time.definitionId())) {
                selected = time;
            }
        }
        if (selected == null) {
            return null;
        }
        ModelInstance instance = model.get();
        if (instance == null) {
            return null;
        }
        String clipName = clipByDefinition.get(selected.definitionId());
        AnimationLibrary.Clip<?> clip = instance.getModel().getAnimations().get(clipName);
        if (clip == null) {
            return null;
        }
        float sourceTime = (float) Math.clamp(
                selected.currentSourceLocalTime(), 0.0, clip.duration());
        return new PoseOutput(clip.sample(sourceTime));
    }
}
