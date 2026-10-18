package lib.kasuga.shader;

import java.util.List;
import java.util.Objects;

public record ShaderGlobal(
        ShaderStorage storage,
        ShaderValueType type,
        String name,
        int arrayLength,
        List<Number> defaultValues
) {
    public ShaderGlobal {
        Objects.requireNonNull(storage, "storage");
        Objects.requireNonNull(type, "type");
        ShaderNames.requireIdentifier(name);
        if (arrayLength < 0 || arrayLength == 1) {
            throw new IllegalArgumentException("arrayLength must be 0 for scalar/vector or greater than 1");
        }
        defaultValues = List.copyOf(defaultValues);
        if ((storage == ShaderStorage.INPUT || storage == ShaderStorage.OUTPUT) && arrayLength != 0) {
            throw new IllegalArgumentException("Stage inputs and outputs cannot be arrays in this profile");
        }
        if (storage == ShaderStorage.SAMPLER && type != ShaderType.SAMPLER_2D) {
            throw new IllegalArgumentException("Sampler storage requires a sampler type");
        }
    }

    public boolean isArray() {
        return arrayLength > 0;
    }
}
