package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * A final Minecraft registry entry a definition intends to occupy.
 *
 * @param registry       target registry/domain, for example {@code minecraft:block}
 * @param id             final registry id
 * @param source         source that supplied the definition
 * @param sourceOrder    source priority
 * @param definitionKey  handler-local definition identity
 * @param logicalPath    definition file
 * @param jsonPointer    definition location
 */
public record RegistryClaim(
        String registry,
        ResourceLocation id,
        SourceDescriptor source,
        int sourceOrder,
        DefinitionKey definitionKey,
        LogicalPath logicalPath,
        String jsonPointer
) {
    public RegistryClaim {
        Objects.requireNonNull(registry, "registry");
        if (registry.isBlank()) throw new IllegalArgumentException("registry must not be blank");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(definitionKey, "definitionKey");
        Objects.requireNonNull(logicalPath, "logicalPath");
        Objects.requireNonNull(jsonPointer, "jsonPointer");
        if (sourceOrder < 0) throw new IllegalArgumentException("sourceOrder must not be negative");
    }

    public RegistryKey registryKey() {
        return new RegistryKey(registry, id);
    }

    public record RegistryKey(String registry, ResourceLocation id) implements Comparable<RegistryKey> {
        public RegistryKey {
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(id, "id");
        }

        @Override
        public int compareTo(RegistryKey other) {
            int registryOrder = registry.compareTo(other.registry);
            return registryOrder != 0 ? registryOrder : id.toString().compareTo(other.id.toString());
        }
    }
}
