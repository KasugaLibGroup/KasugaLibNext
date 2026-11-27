package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.validation.ValidationContext;

/**
 * Read-only preparation environment for handlers that build domain artifacts.
 */
@FunctionalInterface
public interface ArtifactPreparationContext {
    /**
     * Returns a validation context whose current definition is {@code definition}.
     */
    ValidationContext validationFor(LocatedDefinition<?> definition);
}
