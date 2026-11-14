package lib.kasuga.registration.data_driven.pipeline;

import lib.kasuga.registration.data_driven.source.SourceDescriptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable output of discovery, parsing, selection, validation, and registry-claim checks.
 */
public record PreparedContent(
        String hostModId,
        BuildOptions options,
        List<SourceDescriptor> sources,
        List<LocatedDefinition<?>> definitions,
        List<LocatedDefinition<?>> allDefinitions,
        List<LocatedContentPath> registerFiles,
        List<LocatedContentPath> reloadFiles,
        List<RegistryClaim> claims,
        List<BuildDiagnostic> diagnostics,
        Map<String, Set<String>> dependencies,
        Set<String> reservedSymbols
) {
    public PreparedContent {
        Objects.requireNonNull(hostModId, "hostModId");
        Objects.requireNonNull(options, "options");
        sources = List.copyOf(sources);
        definitions = List.copyOf(definitions);
        allDefinitions = List.copyOf(allDefinitions);
        registerFiles = List.copyOf(registerFiles);
        reloadFiles = List.copyOf(reloadFiles);
        claims = List.copyOf(claims);
        diagnostics = List.copyOf(diagnostics);
        dependencies = immutableCopy(dependencies);
        reservedSymbols = Set.copyOf(reservedSymbols);
    }

    public boolean hasBlockingDiagnostics() {
        return diagnostics.stream().anyMatch(BuildDiagnostic::blocking);
    }

    private static Map<String, Set<String>> immutableCopy(Map<String, Set<String>> input) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        input.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        return Map.copyOf(copy);
    }
}
