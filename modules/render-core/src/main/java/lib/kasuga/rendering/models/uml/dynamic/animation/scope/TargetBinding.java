package lib.kasuga.rendering.models.uml.dynamic.animation.scope;

import java.util.Objects;

/**
 * Type-safe binding between one target endpoint, its output evaluator and its publication factory.
 *
 * @param <O> immutable, target-specific output type
 */
public record TargetBinding<O>(
        AnimationTarget<O> target,
        TargetOutputEvaluator<O> evaluator,
        TargetPublicationFactory<O> factory
) {

    public TargetBinding {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(factory, "factory");
    }

    public AnimationTargetRef ref() {
        return target.ref();
    }
}
