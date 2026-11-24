package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A parsed definition together with its immutable source provenance.
 *
 * @param key              normalized logical identity, or {@code null} for a legacy anonymous definition
 * @param handler          handler that parsed the value
 * @param definition       parsed value
 * @param source           source descriptor
 * @param sourceOrder      caller-supplied priority order, where larger values win overrides
 * @param logicalPath      content file inside the source
 * @param jsonPointer      location inside the content file
 * @param declarationOrder stable discovery order across the complete session
 */
public record LocatedDefinition<T>(
        @Nullable DefinitionKey key,
        TypeHandler<T> handler,
        T definition,
        SourceDescriptor source,
        int sourceOrder,
        LogicalPath logicalPath,
        String jsonPointer,
        int declarationOrder
) {
    public LocatedDefinition {
        Objects.requireNonNull(handler, "handler");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(logicalPath, "logicalPath");
        Objects.requireNonNull(jsonPointer, "jsonPointer");
        if (sourceOrder < 0) throw new IllegalArgumentException("sourceOrder must not be negative");
        if (declarationOrder < 0) throw new IllegalArgumentException("declarationOrder must not be negative");
    }

    public String typeName() {
        return handler.getTypeName();
    }
}
