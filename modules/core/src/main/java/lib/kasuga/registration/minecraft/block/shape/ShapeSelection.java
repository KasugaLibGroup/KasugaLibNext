package lib.kasuga.registration.minecraft.block.shape;

import lib.kasuga.geometry.Shape;

import java.util.Objects;

/**
 * One resolved or delegated value in a compiled block-state shape plan.
 *
 * <p>{@link Missing} continues the derivation policy, {@link Native} explicitly requests the
 * host implementation, and {@link Empty} is an explicit empty result.</p>
 */
public sealed interface ShapeSelection
        permits ShapeSelection.Missing,
                ShapeSelection.Native,
                ShapeSelection.Empty,
                ShapeSelection.Geometry,
                ShapeSelection.Alias {

    ShapeSelection MISSING = new Missing();
    ShapeSelection NATIVE = new Native();
    ShapeSelection EMPTY = new Empty();

    static ShapeSelection geometry(Shape shape) {
        return new Geometry(shape);
    }

    static ShapeSelection alias(ShapeSlot target) {
        return new Alias(target);
    }

    static ShapeSelection nativeBinding() {
        return NATIVE;
    }

    static ShapeSelection empty() {
        return EMPTY;
    }

    record Missing() implements ShapeSelection {}

    record Native() implements ShapeSelection {}

    record Empty() implements ShapeSelection {}

    record Geometry(Shape shape) implements ShapeSelection {
        public Geometry {
            Objects.requireNonNull(shape, "shape");
        }
    }

    record Alias(ShapeSlot target) implements ShapeSelection {
        public Alias {
            Objects.requireNonNull(target, "target");
        }
    }
}
