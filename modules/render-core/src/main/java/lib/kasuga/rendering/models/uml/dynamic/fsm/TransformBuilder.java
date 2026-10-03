package lib.kasuga.rendering.models.uml.dynamic.fsm;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;

/** Fluent builder for {@link Transform}s used as bone targets. Angles are degrees; {@link #build()} returns a copy. */
public final class TransformBuilder {

    private final Transform transform = new Transform();

    private TransformBuilder() {}

    public static TransformBuilder create() {
        return new TransformBuilder();
    }

    public TransformBuilder translate(float x, float y, float z) {
        transform.translate(x, y, z);
        return this;
    }

    public TransformBuilder translate(Vector3f offset) {
        transform.translate(offset);
        return this;
    }

    public TransformBuilder rotateX(float degrees) {
        transform.rotate(degrees, 0f, 0f, true);
        return this;
    }

    public TransformBuilder rotateY(float degrees) {
        transform.rotate(0f, degrees, 0f, true);
        return this;
    }

    public TransformBuilder rotateZ(float degrees) {
        transform.rotate(0f, 0f, degrees, true);
        return this;
    }

    public TransformBuilder scale(float x, float y, float z) {
        transform.scale(x, y, z);
        return this;
    }

    public Transform build() {
        return transform.copy();
    }
}
