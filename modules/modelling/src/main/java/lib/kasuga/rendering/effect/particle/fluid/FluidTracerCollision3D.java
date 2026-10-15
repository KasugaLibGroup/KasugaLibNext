package lib.kasuga.rendering.effect.particle.fluid;

import org.joml.Vector3f;

/**
 * Resolves a tracer's world-space movement against an environment outside the finite fluid grid.
 *
 * <p>Implementations mutate {@code position} and {@code velocity}. The supplied vectors are
 * temporary controller values and must not be retained.</p>
 */
@FunctionalInterface
public interface FluidTracerCollision3D {
    FluidTracerCollision3D NONE = (previousPosition, position, velocity, radius) -> {
    };

    void resolve(
            Vector3f previousPosition,
            Vector3f position,
            Vector3f velocity,
            float radius
    );
}
