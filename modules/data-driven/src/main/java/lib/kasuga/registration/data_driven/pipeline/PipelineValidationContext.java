package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Stage;
import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import lib.kasuga.registration.data_driven.validation.ValidationContext;
import lib.kasuga.registration.factory.FactoryRegistry;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class PipelineValidationContext implements ValidationContext {
    private final String hostModId;
    private final LocatedDefinition<?> current;
    private final List<PipelineSource> sources;
    private final Map<DefinitionKey, List<LocatedDefinition<?>>> candidates;
    private final Map<DefinitionKey, LocatedDefinition<?>> effective;
    private final Map<String, Set<String>> dependencies;
    private final Set<String> reservedSymbols;
    private final DiagnosticCollector diagnostics;

    PipelineValidationContext(
            String hostModId,
            LocatedDefinition<?> current,
            List<PipelineSource> sources,
            Map<DefinitionKey, List<LocatedDefinition<?>>> candidates,
            Map<DefinitionKey, LocatedDefinition<?>> effective,
            Map<String, Set<String>> dependencies,
            Set<String> reservedSymbols,
            DiagnosticCollector diagnostics
    ) {
        this.hostModId = hostModId;
        this.current = current;
        this.sources = sources;
        this.candidates = candidates;
        this.effective = effective;
        this.dependencies = dependencies;
        this.reservedSymbols = reservedSymbols;
        this.diagnostics = diagnostics;
    }

    @Override
    public String hostModId() {
        return hostModId;
    }

    @Override
    public SourceDescriptor source() {
        return current.source();
    }

    @Override
    public int sourceOrder() {
        return current.sourceOrder();
    }

    @Override
    public LocatedDefinition<?> currentDefinition() {
        return current;
    }

    @Override
    public Set<String> directDependencies() {
        return dependencies.getOrDefault(current.source().ownerId(), Set.of());
    }

    @Override
    public Optional<LocatedDefinition<?>> find(DefinitionKey key) {
        return Optional.ofNullable(effective.get(key));
    }

    @Override
    public List<LocatedDefinition<?>> findAll(DefinitionKey key) {
        return candidates.getOrDefault(key, List.of());
    }

    @Override
    public boolean canReference(DefinitionKey key) {
        Set<String> visibleOwners = new LinkedHashSet<>();
        visibleOwners.add(current.source().ownerId());
        visibleOwners.addAll(directDependencies());
        for (LocatedDefinition<?> candidate : findAll(key)) {
            if (visibleOwners.contains(candidate.source().ownerId())) return true;
        }
        return false;
    }

    @Override
    public boolean requireDefinition(DefinitionKey key, String relativeJsonPointer) {
        List<LocatedDefinition<?>> available = findAll(key);
        if (available.isEmpty()) {
            error(
                    BuildDiagnostic.Code.MISSING_REFERENCE,
                    relativeJsonPointer,
                    "Referenced definition " + key + " does not exist",
                    "Add the definition or remove the reference."
            );
            return false;
        }
        if (!canReference(key)) {
            error(
                    BuildDiagnostic.Code.REFERENCE_NOT_DECLARED,
                    relativeJsonPointer,
                    "Referenced definition " + key + " is owned by "
                            + available.stream()
                            .map(definition -> definition.source().ownerId())
                            .distinct()
                            .toList()
                            + " but is not visible to owner '" + current.source().ownerId() + "'",
                    "Declare the referenced owner as a direct dependency."
            );
            return false;
        }
        return true;
    }

    @Override
    public boolean resourceExists(ResourceDomain domain, ResourceLocation location) {
        Set<String> visibleOwners = new LinkedHashSet<>();
        visibleOwners.add(current.source().ownerId());
        visibleOwners.addAll(directDependencies());
        String prefix = domain == ResourceDomain.ASSETS ? "assets/" : "data/";
        LogicalPath path;
        try {
            path = LogicalPath.of(prefix + location.getNamespace() + "/" + location.getPath());
        } catch (IllegalArgumentException ignored) {
            return false;
        }

        List<PipelineSource> descending = new ArrayList<>(sources);
        descending.sort(Comparator.comparingInt(PipelineSource::order).reversed());
        for (PipelineSource source : descending) {
            SourceDescriptor descriptor = source.source().descriptor();
            if (visibleOwners.contains(descriptor.ownerId())
                    && descriptor.namespaces().contains(location.getNamespace())
                    && source.source().exists(path)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean factoryAvailable(FactoryType factoryType, String factoryId) {
        return switch (factoryType) {
            case BLOCK -> FactoryRegistry.contains(factoryId);
            case ITEM -> FactoryRegistry.containsItem(factoryId);
            case BLOCK_ENTITY -> FactoryRegistry.containsBlockEntity(factoryId);
            case GENERIC -> FactoryRegistry.containsGeneric(factoryId);
        };
    }

    @Override
    public boolean factorySupports(FactoryType factoryType, String factoryId, Class<?> capability) {
        Object factory = switch (factoryType) {
            case BLOCK -> FactoryRegistry.get(factoryId);
            case ITEM -> FactoryRegistry.getItemFactory(factoryId);
            case BLOCK_ENTITY -> FactoryRegistry.getBlockEntityFactory(factoryId);
            case GENERIC -> FactoryRegistry.getGeneric(factoryId);
        };
        return capability.isInstance(factory);
    }

    @Override
    public boolean isReserved(String symbol) {
        return reservedSymbols.contains(symbol);
    }

    @Override
    public Set<String> reservedSymbols() {
        return reservedSymbols;
    }

    @Override
    public RegistryClaim claim(String registry, ResourceLocation id) {
        if (current.key() == null) {
            throw new IllegalStateException("a registry claim requires a definition identity");
        }
        return new RegistryClaim(
                registry,
                id,
                current.source(),
                current.sourceOrder(),
                current.key(),
                current.logicalPath(),
                current.jsonPointer()
        );
    }

    @Override
    public void error(Code code, String relativeJsonPointer, String message, String suggestion) {
        diagnostics.error(
                current.source(),
                Stage.VALIDATION,
                code,
                current.logicalPath().value(),
                appendPointer(current.jsonPointer(), relativeJsonPointer),
                current.typeName(),
                message,
                suggestion
        );
    }

    @Override
    public void warning(Code code, String relativeJsonPointer, String message, String suggestion) {
        diagnostics.warning(
                current.source(),
                Stage.VALIDATION,
                code,
                current.logicalPath().value(),
                appendPointer(current.jsonPointer(), relativeJsonPointer),
                current.typeName(),
                message,
                suggestion
        );
    }

    static String appendPointer(String base, String relative) {
        Objects.requireNonNull(base, "base");
        if (relative == null || relative.isEmpty()) return base;
        if (relative.startsWith("/")) return base + relative;
        return base + "/" + relative;
    }
}
