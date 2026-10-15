package lib.kasuga.rendering.effect.particle;

import lib.kasuga.rendering.models.uml.math.Transform;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.function.UnaryOperator;

/** Reusable physical and scripted building blocks for {@link ParticleOperator}. */
public final class ParticleOperators {
    private static final ParticleOperator IDENTITY = state -> state;
    private static final ParticleOperator INTEGRATE = state -> state.withTransform(
            state.transform().translateWorld(state.velocity())
    );

    private ParticleOperators() {
    }

    public static ParticleOperator identity() {
        return IDENTITY;
    }

    /** Integrates the current velocity for one client tick in world space. */
    public static ParticleOperator integrate() {
        return INTEGRATE;
    }

    /**
     * Applies acceleration and drag, then integrates velocity for one client tick.
     */
    public static ParticleOperator physics(Vector3f acceleration, float drag) {
        Vector3f accelerationCopy = new Vector3f(
                Objects.requireNonNull(acceleration, "acceleration")
        );
        requireFinite(accelerationCopy, "acceleration");
        if (!Float.isFinite(drag) || drag < 0) {
            throw new IllegalArgumentException("drag must be finite and non-negative");
        }
        return state -> {
            Vector3f velocity = state.velocity().add(accelerationCopy).mul(drag);
            return state.withVelocity(velocity)
                    .withTransform(state.transform().translateWorld(velocity));
        };
    }

    /** Multiplies the current size every tick. */
    public static ParticleOperator scale(float factor) {
        if (!Float.isFinite(factor) || factor <= 0) {
            throw new IllegalArgumentException("scale factor must be finite and positive");
        }
        return state -> state.withTransform(state.transform().scale(factor, factor, factor));
    }

    /** Adds an Euler rotation, in radians, every tick. */
    public static ParticleOperator rotate(Vector3f radiansPerTick) {
        Vector3f rotation = new Vector3f(
                Objects.requireNonNull(radiansPerTick, "radiansPerTick")
        );
        requireFinite(rotation, "radiansPerTick");
        return state -> state.withTransform(state.transform().rotate(rotation, false));
    }

    /** Multiplies alpha every tick while preserving RGB. */
    public static ParticleOperator fade(float alphaRetention) {
        if (!Float.isFinite(alphaRetention) || alphaRetention < 0 || alphaRetention > 1) {
            throw new IllegalArgumentException("alphaRetention must be between 0 and 1");
        }
        return state -> {
            Vector4f color = state.color();
            color.w *= alphaRetention;
            return state.withColor(color);
        };
    }

    /**
     * Adapts a plain {@code UnaryOperator<Transform>} supplied by Java or a script. The input and
     * output are defensively copied by {@link ParticleState}.
     */
    public static ParticleOperator transform(UnaryOperator<Transform> operator) {
        Objects.requireNonNull(operator, "operator");
        return state -> state.withTransform(Objects.requireNonNull(
                operator.apply(state.transform()), "transform operator result"
        ));
    }

    private static void requireFinite(Vector3f value, String name) {
        if (!value.isFinite()) {
            throw new IllegalArgumentException(name + " must contain only finite values");
        }
    }
}
