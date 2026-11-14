package lib.kasuga.registration.data_driven.validation;

import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.LocatedDefinition;
import lib.kasuga.registration.data_driven.pipeline.RegistryClaim;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only access to the prepared session for side-effect-free handler validation.
 */
public interface ValidationContext {
    String hostModId();

    SourceDescriptor source();

    int sourceOrder();

    LocatedDefinition<?> currentDefinition();

    Set<String> directDependencies();

    Optional<LocatedDefinition<?>> find(DefinitionKey key);

    List<LocatedDefinition<?>> findAll(DefinitionKey key);

    boolean canReference(DefinitionKey key);

    boolean requireDefinition(DefinitionKey key, String relativeJsonPointer);

    boolean resourceExists(ResourceDomain domain, ResourceLocation location);

    boolean factoryAvailable(FactoryType factoryType, String factoryId);

    boolean factorySupports(FactoryType factoryType, String factoryId, Class<?> capability);

    boolean isReserved(String symbol);

    Set<String> reservedSymbols();

    RegistryClaim claim(String registry, ResourceLocation id);

    void error(Code code, String relativeJsonPointer, String message, String suggestion);

    void warning(Code code, String relativeJsonPointer, String message, String suggestion);

    enum ResourceDomain {
        ASSETS,
        DATA
    }

    enum FactoryType {
        BLOCK,
        ITEM,
        BLOCK_ENTITY,
        GENERIC
    }
}
