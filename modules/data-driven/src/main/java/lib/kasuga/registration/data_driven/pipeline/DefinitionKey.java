package lib.kasuga.registration.data_driven.pipeline;

import java.util.Objects;

/**
 * Logical identity within one handler type domain.
 *
 * <p>This is intentionally separate from a Minecraft registry key: several handler types may claim
 * the same final registry entry.
 */
public record DefinitionKey(String typeDomain, String id) implements Comparable<DefinitionKey> {
    public DefinitionKey {
        typeDomain = requireNonBlank(typeDomain, "typeDomain");
        id = requireNonBlank(id, "id");
    }

    @Override
    public int compareTo(DefinitionKey other) {
        int type = typeDomain.compareTo(other.typeDomain);
        return type != 0 ? type : id.compareTo(other.id);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
