package lib.kasuga.rendering.models.mc.proxies;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, resolved model-group structure shared by generic block and domain-specific adapters.
 *
 * <p>This is a compiled geometry description. Runtime handles, instances, playback state and
 * host-specific motion binding belong to later stages.
 */
public record CompiledModelGroup(
        String id,
        float yOffset,
        List<CompiledModelPart> parts
) {

    public CompiledModelGroup {
        if (id == null || !id.matches("[a-z0-9_.:/-]+")) {
            throw new IllegalArgumentException("compiled model group id is invalid: " + id);
        }
        if (!Float.isFinite(yOffset)) {
            throw new IllegalArgumentException("compiled model group y_offset must be finite");
        }
        Objects.requireNonNull(parts, "parts");
        if (parts.isEmpty()) {
            throw new IllegalArgumentException("compiled model group must contain at least one part");
        }
        parts = List.copyOf(parts);
        Set<String> ids = new HashSet<>();
        for (CompiledModelPart part : parts) {
            if (!ids.add(part.id())) {
                throw new IllegalArgumentException(
                        "compiled model group " + id + " contains duplicate part id " + part.id());
            }
        }
    }

    public static CompiledModelGroup single(String id, ModelPart part) {
        return new CompiledModelGroup(
                id,
                0f,
                List.of(new CompiledModelPart(
                        "body", part.model(), part.modelName(), part.local()))
        );
    }

    public List<String> partIds() {
        return parts.stream().map(CompiledModelPart::id).toList();
    }

    public List<ModelPart> modelParts() {
        return parts.stream().map(CompiledModelPart::toModelPart).toList();
    }
}
