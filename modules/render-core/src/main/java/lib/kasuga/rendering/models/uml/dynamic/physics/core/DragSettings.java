package lib.kasuga.rendering.models.uml.dynamic.physics.core;

/** Tunable soft mouse/anchor constraint with stable interactive defaults. */
public record DragSettings(float positionSlop, float positionStiffness,
                           float maxPositionCorrection, float biasRate,
                           float maxBiasSpeed, float maxTargetSpeed,
                           float maxVelocityImpulse, float relativeLinearDamping,
                           float relativeAngularDamping) {
    public static final DragSettings DEFAULT = new DragSettings(
            0.002f, 0.16f, 0.012f, 7f, 4f, 6f, 0.65f, 12.643f, 4.899f);

    public DragSettings {
        if (!finiteNonNegative(positionSlop) || !finiteNonNegative(positionStiffness)
                || !finiteNonNegative(maxPositionCorrection) || !finiteNonNegative(biasRate)
                || !finiteNonNegative(maxBiasSpeed) || !finiteNonNegative(maxTargetSpeed)
                || !finiteNonNegative(maxVelocityImpulse)
                || !finiteNonNegative(relativeLinearDamping)
                || !finiteNonNegative(relativeAngularDamping)) {
            throw new IllegalArgumentException("drag settings must be finite and non-negative");
        }
    }

    private static boolean finiteNonNegative(float value) {
        return Float.isFinite(value) && value >= 0f;
    }
}
