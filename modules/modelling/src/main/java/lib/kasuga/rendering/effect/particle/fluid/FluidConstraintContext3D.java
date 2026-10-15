package lib.kasuga.rendering.effect.particle.fluid;

import org.joml.Vector3f;

/**
 * Mutable cell view exposed while applying one fluid environment.
 *
 * <p>Cell indices are interior indices in {@code [0, resolution)}. Coordinates returned by
 * {@link #coordinate(int)} identify cell centers in normalized simulation space.</p>
 */
public interface FluidConstraintContext3D {
    int resolution();

    float deltaTime();

    float coordinate(int cell);

    float density(int x, int y, int z);

    void density(int x, int y, int z, float value);

    Vector3f velocity(int x, int y, int z, Vector3f destination);

    void velocity(int x, int y, int z, float xVelocity, float yVelocity, float zVelocity);

    void addVelocity(int x, int y, int z, float xVelocity, float yVelocity, float zVelocity);

    void solid(int x, int y, int z, boolean value);

    boolean solid(int x, int y, int z);
}
