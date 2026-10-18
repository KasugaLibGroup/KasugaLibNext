package lib.kasuga.rendering.effect.particle.fluid;

/**
 * Backend-neutral environment operation applied to a fluid grid before each simulation step.
 *
 * <p>Implementations may mark solid cells, inject density/velocity, apply forces, or remove
 * density. Constraints are applied in environment order, so later constraints may refine earlier
 * ones.</p>
 */
@FunctionalInterface
public interface FluidConstraint3D {
    void apply(FluidConstraintContext3D context);
}
