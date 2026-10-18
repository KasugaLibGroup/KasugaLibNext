package lib.kasuga.shader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Immutable declaration of one uniform that a shader deliberately exposes to users. */
public record ShaderParameter(
        String name,
        String description,
        ShaderParameterType type,
        ShaderParameterRange range,
        List<Number> defaultValues
) {
    public ShaderParameter {
        ShaderNames.requireIdentifier(name);
        Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("Shader parameter description cannot be blank");
        }
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(range, "range");
        validateRange(name, type, range);
        defaultValues = List.copyOf(defaultValues);
        if (defaultValues.size() != type.componentCount()) {
            throw new IllegalArgumentException("Shader parameter " + name + " requires "
                    + type.componentCount() + " default values");
        }
        validateValues(name, type, range, defaultValues);
        if (type == ShaderParameterType.BOOLEAN
                && (range.minimum() != 0.0 || range.maximum() != 1.0)) {
            throw new IllegalArgumentException("Boolean shader parameters require range [0, 1]");
        }
    }

    private static void validateRange(
            String name,
            ShaderParameterType type,
            ShaderParameterRange range
    ) {
        if (type.integral()) {
            if (range.minimum() != Math.rint(range.minimum())
                    || range.maximum() != Math.rint(range.maximum())) {
                throw new IllegalArgumentException("Shader parameter " + name
                        + " requires integer range endpoints");
            }
            if (range.minimum() < Integer.MIN_VALUE || range.maximum() > Integer.MAX_VALUE) {
                throw new IllegalArgumentException("Shader parameter " + name
                        + " range exceeds the signed 32-bit integer range");
            }
        } else if (!Float.isFinite((float) range.minimum())
                || !Float.isFinite((float) range.maximum())) {
            throw new IllegalArgumentException("Shader parameter " + name
                    + " range cannot be represented by 32-bit shader floats");
        }
    }

    public static Builder builder(String name, String description, ShaderParameterType type) {
        return new Builder(name, description, type);
    }

    public static ShaderParameter floatParameter(
            String name,
            String description,
            float defaultValue,
            float minimum,
            float maximum
    ) {
        return builder(name, description, ShaderParameterType.FLOAT)
                .range(minimum, maximum)
                .defaultValues(defaultValue)
                .build();
    }

    public static ShaderParameter intParameter(
            String name,
            String description,
            int defaultValue,
            int minimum,
            int maximum
    ) {
        return builder(name, description, ShaderParameterType.INTEGER)
                .range(minimum, maximum)
                .defaultValues(defaultValue)
                .build();
    }

    public static ShaderParameter booleanParameter(
            String name,
            String description,
            boolean defaultValue
    ) {
        return builder(name, description, ShaderParameterType.BOOLEAN)
                .range(0, 1)
                .defaultValues(defaultValue ? 1 : 0)
                .build();
    }

    public void validate(double... values) {
        Objects.requireNonNull(values, "values");
        if (values.length != type.componentCount()) {
            throw new IllegalArgumentException("Shader parameter " + name + " requires "
                    + type.componentCount() + " values");
        }
        List<Number> boxed = new ArrayList<>(values.length);
        for (double value : values) boxed.add(value);
        validateValues(name, type, range, boxed);
    }

    private static void validateValues(
            String name,
            ShaderParameterType type,
            ShaderParameterRange range,
            List<Number> values
    ) {
        for (Number boxed : values) {
            Objects.requireNonNull(boxed, "defaultValues contains null");
            double value = boxed.doubleValue();
            if (!Double.isFinite(value)) {
                throw new IllegalArgumentException("Shader parameter " + name + " must be finite");
            }
            if (type.integral() && value != Math.rint(value)) {
                throw new IllegalArgumentException("Shader parameter " + name + " requires integer values");
            }
            if (type.integral() && (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)) {
                throw new IllegalArgumentException("Shader parameter " + name
                        + " exceeds the signed 32-bit integer range");
            }
            if (!type.integral() && !Float.isFinite((float) value)) {
                throw new IllegalArgumentException("Shader parameter " + name
                        + " cannot be represented by a 32-bit shader float");
            }
            if (!range.contains(value)) {
                throw new IllegalArgumentException("Shader parameter " + name + " value " + value
                        + " is outside [" + range.minimum() + ", " + range.maximum() + "]");
            }
        }
    }

    public static final class Builder {
        private final String name;
        private final String description;
        private final ShaderParameterType type;
        private ShaderParameterRange range;
        private List<Number> defaultValues;

        private Builder(String name, String description, ShaderParameterType type) {
            this.name = name;
            this.description = description;
            this.type = Objects.requireNonNull(type, "type");
        }

        public Builder range(double minimum, double maximum) {
            range = ShaderParameterRange.of(minimum, maximum);
            return this;
        }

        public Builder defaultValues(Number... values) {
            Objects.requireNonNull(values, "values");
            defaultValues = List.of(values.clone());
            return this;
        }

        public ShaderParameter build() {
            if (range == null) throw new IllegalStateException("Shader parameter range is required");
            if (defaultValues == null) throw new IllegalStateException("Shader parameter default values are required");
            return new ShaderParameter(name, description, type, range, defaultValues);
        }
    }
}
