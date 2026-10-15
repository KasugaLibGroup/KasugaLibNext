package lib.kasuga.rendering.models.uml.dynamic.animation;

/** Pure, format-specific evaluation. R is the sampled output, such as a model Pose or a CameraPose. */
public interface AnimationSource<T, R> {
    float duration(T data);
    R sample(T data, float time);
}
