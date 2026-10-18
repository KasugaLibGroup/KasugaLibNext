package lib.kasuga.rendering.effect.particle.fluid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable ordered collection of constraints applied to one stable-fluid step. */
public final class FluidEnvironment3D {
    public static final FluidEnvironment3D EMPTY = new FluidEnvironment3D(List.of());

    private final List<FluidConstraint3D> constraints;

    private FluidEnvironment3D(List<FluidConstraint3D> constraints) {
        this.constraints = constraints;
    }

    public static Builder builder() {
        return new Builder();
    }

    public boolean isEmpty() {
        return constraints.isEmpty();
    }

    public int size() {
        return constraints.size();
    }

    void apply(FluidConstraintContext3D context) {
        for (FluidConstraint3D constraint : constraints) constraint.apply(context);
    }

    public static final class Builder {
        private final List<FluidConstraint3D> constraints = new ArrayList<>();

        public Builder add(FluidConstraint3D constraint) {
            constraints.add(Objects.requireNonNull(constraint, "constraint"));
            return this;
        }

        public FluidEnvironment3D build() {
            return constraints.isEmpty()
                    ? EMPTY
                    : new FluidEnvironment3D(List.copyOf(constraints));
        }
    }
}
