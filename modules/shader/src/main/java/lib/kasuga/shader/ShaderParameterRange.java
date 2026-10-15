package lib.kasuga.shader;

/** Inclusive range applied to every component of an exposed parameter. */
public record ShaderParameterRange(double minimum, double maximum) {
    public ShaderParameterRange {
        if (!Double.isFinite(minimum) || !Double.isFinite(maximum)) {
            throw new IllegalArgumentException("Shader parameter range must be finite");
        }
        if (minimum > maximum) {
            throw new IllegalArgumentException("Shader parameter minimum exceeds maximum");
        }
    }

    public static ShaderParameterRange of(double minimum, double maximum) {
        return new ShaderParameterRange(minimum, maximum);
    }

    public boolean contains(double value) {
        return value >= minimum && value <= maximum;
    }
}
