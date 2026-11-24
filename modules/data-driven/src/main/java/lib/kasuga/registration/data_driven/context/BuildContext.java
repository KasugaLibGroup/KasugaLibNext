package lib.kasuga.registration.data_driven.context;

import lib.kasuga.registration.data_driven.pipeline.LocatedDefinition;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

public class BuildContext {

    private final String modId;
    private final JsonRegistryGroup rootGroup;
    private final boolean strict;
    private final Map<String, Map<String, Object>> metaStore = new LinkedHashMap<>();
    private LocatedDefinition<?> currentDefinition;

    public BuildContext(String modId, JsonRegistryGroup rootGroup) {
        this(modId, rootGroup, false);
    }

    public BuildContext(String modId, JsonRegistryGroup rootGroup, boolean strict) {
        this.modId = modId;
        this.rootGroup = rootGroup;
        this.strict = strict;
    }

    /**
     * Historical name for the host mod id. Use {@link #getHostModId()} when the distinction from a
     * content source owner matters.
     */
    public String getModId() { return modId; }

    public String getHostModId() { return modId; }

    public JsonRegistryGroup getRootGroup() { return rootGroup; }

    public boolean isStrict() { return strict; }

    public Optional<LocatedDefinition<?>> getCurrentDefinition() {
        return Optional.ofNullable(currentDefinition);
    }

    @Nullable
    public String getSourceId() {
        return currentDefinition == null ? null : currentDefinition.source().sourceId();
    }

    @Nullable
    public String getOwnerId() {
        return currentDefinition == null ? null : currentDefinition.source().ownerId();
    }

    public Set<String> getNamespaces() {
        return currentDefinition == null ? Set.of() : currentDefinition.source().namespaces();
    }

    @Nullable
    public String getCurrentHandlerType() {
        return currentDefinition == null ? null : currentDefinition.typeName();
    }

    @Nullable
    public String getCurrentLogicalPath() {
        return currentDefinition == null ? null : currentDefinition.logicalPath().value();
    }

    @Nullable
    public String getCurrentJsonPointer() {
        return currentDefinition == null ? null : currentDefinition.jsonPointer();
    }

    /**
     * Runs apply while exposing immutable source and definition provenance to the handler.
     */
    public <R> R withCurrentDefinition(LocatedDefinition<?> definition, Supplier<R> action) {
        LocatedDefinition<?> previous = currentDefinition;
        currentDefinition = definition;
        try {
            return action.get();
        } finally {
            currentDefinition = previous;
        }
    }

    @SuppressWarnings("unchecked")
    public <T> void putMeta(String typeName, String id, T value) {
        metaStore.computeIfAbsent(typeName, k -> new LinkedHashMap<>()).put(id, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getMeta(String typeName, String id) {
        Map<String, Object> map = metaStore.get(typeName);
        return map != null ? (T) map.get(id) : null;
    }
}
