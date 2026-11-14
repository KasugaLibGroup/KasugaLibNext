package lib.kasuga.registration.data_driven.pipeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.core.resource.routing.StrictResourceJson;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Code;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Severity;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic.Stage;
import lib.kasuga.registration.data_driven.reload.Decoded;
import lib.kasuga.registration.data_driven.reload.ReloadHandler;
import lib.kasuga.registration.data_driven.reload.ReloadHandlerRegistry;
import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.LocatedFile;
import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import net.minecraft.resources.ResourceLocation;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.CharacterCodingException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Multi-source data-driven prepare/build session.
 *
 * <p>Sources are supplied in ascending priority order: later sources override earlier sources for
 * non-registration definitions. Registration definitions keep all candidates in strict mode so
 * registry claims can be checked before any handler runs.
 */
public final class DataDrivenBuildPipeline {
    private static final Set<String> MANIFEST_FIELDS = Set.of("on_register", "on_reload");

    private final String hostModId;
    private final List<TypeHandler<?>> handlers;
    private final List<BuildFinalizer> finalizers;
    private final RegistrationTarget registrationTarget;
    private final Set<String> reservedSymbols;
    private final Set<RegistryClaim.RegistryKey> reservedRegistryClaims;

    public DataDrivenBuildPipeline(
            String hostModId,
            Collection<? extends TypeHandler<?>> handlers,
            Collection<? extends BuildFinalizer> finalizers
    ) {
        this(hostModId, handlers, finalizers, RegistrationTarget.standard(), Set.of(), Set.of());
    }

    public DataDrivenBuildPipeline(
            String hostModId,
            Collection<? extends TypeHandler<?>> handlers,
            Collection<? extends BuildFinalizer> finalizers,
            RegistrationTarget registrationTarget,
            Collection<String> reservedSymbols
    ) {
        this(
                hostModId,
                handlers,
                finalizers,
                registrationTarget,
                reservedSymbols,
                Set.of()
        );
    }

    public DataDrivenBuildPipeline(
            String hostModId,
            Collection<? extends TypeHandler<?>> handlers,
            Collection<? extends BuildFinalizer> finalizers,
            RegistrationTarget registrationTarget,
            Collection<String> reservedSymbols,
            Collection<RegistryClaim.RegistryKey> reservedRegistryClaims
    ) {
        this.hostModId = requireNonBlank(hostModId, "hostModId");
        this.handlers = List.copyOf(handlers);
        this.finalizers = List.copyOf(finalizers);
        this.registrationTarget = Objects.requireNonNull(registrationTarget, "registrationTarget");
        this.reservedSymbols = Set.copyOf(reservedSymbols);
        this.reservedRegistryClaims = Set.copyOf(reservedRegistryClaims);
        for (TypeHandler<?> handler : this.handlers) {
            Objects.requireNonNull(handler, "handlers must not contain null");
            if (handler.getTypeName() == null || handler.getTypeName().isBlank()) {
                throw new IllegalArgumentException("handler type names must not be blank");
            }
        }
    }

    public PreparedContent prepare(Collection<? extends ContentSource> sources, BuildOptions options) {
        return prepare(sources, options, Map.of());
    }

    /**
     * Discovers and validates all sources without calling any handler.
     *
     * @param sources      sources in ascending priority order
     * @param options      legacy or strict policy
     * @param dependencies direct owner dependencies, keyed by owner id
     */
    public PreparedContent prepare(
            Collection<? extends ContentSource> sources,
            BuildOptions options,
            Map<String, ? extends Collection<String>> dependencies
    ) {
        Objects.requireNonNull(sources, "sources");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(dependencies, "dependencies");

        List<PipelineSource> sourceViews = new ArrayList<>();
        int inputOrder = 0;
        for (ContentSource source : sources) {
            ContentSource checked = Objects.requireNonNull(source, "source");
            var registration = checked.descriptor();
            int sourceOrder = registration.resource() == null
                    ? inputOrder
                    : registration.rank();
            sourceViews.add(new PipelineSource(checked, sourceOrder, inputOrder++));
        }
        sourceViews.sort(Comparator.comparingInt(PipelineSource::order)
                .thenComparingInt(PipelineSource::inputOrder));

        DiagnosticCollector diagnostics = new DiagnosticCollector();
        validateSources(sourceViews, options, diagnostics);
        Map<String, Set<String>> dependencyView =
                validateDependencies(sourceViews, dependencies, options, diagnostics);

        PreparationData data = new PreparationData(diagnostics);
        for (PipelineSource source : sourceViews) {
            try {
                prepareSource(source, options, data);
            } catch (RuntimeException failure) {
                SourceDescriptor descriptor = source.source().descriptor();
                diagnostics.error(
                        descriptor,
                        Stage.SOURCE,
                        Code.SOURCE_READ_FAILED,
                        null,
                        null,
                        null,
                        "Failed to prepare content source: " + failure,
                        "Verify the source file list can be enumerated repeatedly."
                );
            }
        }
        for (LocatedContentPath reloadFile : data.reloadFiles) {
            validateReloadContentFile(sourceViews, reloadFile, options, data.diagnostics);
        }

        checkStrictCapabilities(options, data);
        List<LocatedDefinition<?>> selected = selectDefinitions(options, data.allDefinitions);
        DefinitionDirectory directory = DefinitionDirectory.from(data.allDefinitions);
        collectValidationDiagnostics(options, selected, sourceViews, directory, dependencyView, data.diagnostics);
        collectGroupCycleDiagnostics(options, selected, sourceViews, directory, dependencyView, data.diagnostics);
        List<RegistryClaim> claims =
                collectClaims(options, selected, sourceViews, directory, dependencyView, data.diagnostics);

        return new PreparedContent(
                hostModId,
                options,
                sourceViews.stream().map(source -> source.source().descriptor()).toList(),
                selected,
                data.allDefinitions,
                data.registerFiles,
                data.reloadFiles,
                claims,
                data.diagnostics.snapshot(),
                dependencyView,
                reservedSymbols
        );
    }

    public BuildResult build(PreparedContent prepared) {
        return build(prepared, registrationTarget);
    }

    /**
     * Applies one prepared session to one mutable registration target.
     *
     * <p>Strict preparations with blocking diagnostics are rejected before the target is created.
     * Apply and finalizer failures are fatal for the result and do not attempt partial rollback.
     */
    public BuildResult build(PreparedContent prepared, RegistrationTarget target) {
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(target, "target");
        if (!hostModId.equals(prepared.hostModId())) {
            throw new IllegalArgumentException("prepared content belongs to '" + prepared.hostModId()
                    + "', not '" + hostModId + "'");
        }

        List<BuildDiagnostic> diagnostics = new ArrayList<>(prepared.diagnostics());
        if (prepared.options().isStrict() && prepared.hasBlockingDiagnostics()) {
            return new BuildResult(
                    hostModId,
                    BuildResult.Status.REJECTED,
                    null,
                    diagnostics,
                    List.of()
            );
        }

        JsonRegistryGroup root = new JsonRegistryGroup(hostModId + ":json_root");
        root.withProperty(ResourceLocation.class,
                location -> ResourceLocation.fromNamespaceAndPath(hostModId, location.getPath()));

        RegBuildContext context;
        try {
            context = target.createContext(hostModId, root, prepared.options());
        } catch (RuntimeException failure) {
            diagnostics.add(sessionDiagnostic(
                    Stage.APPLY,
                    Code.APPLY_FAILED,
                    null,
                    "Failed to create registration context: " + failure,
                    "Inspect the registration target lifecycle."
            ));
            return new BuildResult(hostModId, BuildResult.Status.FAILED, root, diagnostics, List.of());
        }

        Map<String, Integer> handlerOrder = handlerOrder();
        List<LocatedDefinition<?>> applyOrder;
        try {
            applyOrder = stableApplyOrder(prepared.definitions(), handlerOrder);
        } catch (RuntimeException failure) {
            diagnostics.add(sessionDiagnostic(
                    Stage.APPLY,
                    Code.APPLY_FAILED,
                    null,
                    "Failed to order definitions for apply: " + failure,
                    "Fix parentDefinition so it returns stable DefinitionKeys."
            ));
            return new BuildResult(hostModId, BuildResult.Status.FAILED, root, diagnostics, List.of());
        }
        for (LocatedDefinition<?> definition : applyOrder) {
            try {
                apply(definition, context);
            } catch (RuntimeException failure) {
                diagnostics.add(definitionDiagnostic(
                        definition,
                        Stage.APPLY,
                        Code.APPLY_FAILED,
                        "Failed to apply '" + definition.typeName() + "': " + failure,
                        "Fix the definition or handler before retrying startup."
                ));
                return new BuildResult(hostModId, BuildResult.Status.FAILED, root, diagnostics, List.of());
            }
        }

        List<Object> finalizerResults = new ArrayList<>();
        for (BuildFinalizer finalizer : finalizers) {
            try {
                finalizerResults.add(finalizer.afterBuild(context, prepared));
            } catch (RuntimeException failure) {
                diagnostics.add(sessionDiagnostic(
                        Stage.FINALIZE,
                        Code.FINALIZER_FAILED,
                        null,
                        "Build finalizer failed: " + failure,
                        "Fix the finalizer before retrying startup."
                ));
                return new BuildResult(
                        hostModId,
                        BuildResult.Status.FAILED,
                        root,
                        diagnostics,
                        finalizerResults
                );
            }
        }

        return new BuildResult(
                hostModId,
                BuildResult.Status.APPLIED,
                root,
                diagnostics,
                finalizerResults
        );
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void apply(LocatedDefinition<?> definition, RegBuildContext context) {
        context.withCurrentDefinition(definition, () -> {
            ((TypeHandler) definition.handler()).apply(definition.definition(), context);
            return null;
        });
    }

    private void validateSources(
            List<PipelineSource> sources,
            BuildOptions options,
            DiagnosticCollector diagnostics
    ) {
        Set<String> sourceIds = new HashSet<>();
        Set<Integer> sourceOrders = new HashSet<>();
        for (PipelineSource view : sources) {
            SourceDescriptor descriptor = view.source().descriptor();
            String identity = descriptor.sourceId() + "/" + descriptor.rootId();
            if (!sourceIds.add(identity)) {
                addIssue(
                        options,
                        diagnostics,
                        Severity.ERROR,
                        descriptor,
                        Stage.SOURCE,
                        Code.INVALID_SOURCE,
                        null,
                        null,
                        null,
                        "Duplicate source root '" + identity + "'",
                        "Assign every content source a stable unique source/root identity."
                );
            }
            if (!sourceOrders.add(view.order())) {
                addIssue(
                        options,
                        diagnostics,
                        Severity.ERROR,
                        descriptor,
                        Stage.SOURCE,
                        Code.INVALID_SOURCE,
                        null,
                        null,
                        null,
                        "Duplicate source rank " + view.order(),
                        "Assign every live content root a unique rank."
                );
            }
        }
    }

    private Map<String, Set<String>> validateDependencies(
            List<PipelineSource> sources,
            Map<String, ? extends Collection<String>> input,
            BuildOptions options,
            DiagnosticCollector diagnostics
    ) {
        Map<String, Set<String>> normalized = new LinkedHashMap<>();
        input.forEach((owner, dependencies) -> {
            String normalizedOwner = requireNonBlank(owner, "dependency owner");
            LinkedHashSet<String> values = new LinkedHashSet<>();
            for (String dependency : dependencies) {
                values.add(requireNonBlank(dependency, "dependency"));
            }
            normalized.put(normalizedOwner, Set.copyOf(values));
        });

        Set<String> owners = sources.stream()
                .map(source -> source.source().descriptor().ownerId())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String owner : owners) {
            normalized.putIfAbsent(owner, Set.of());
        }
        for (Map.Entry<String, Set<String>> entry : normalized.entrySet()) {
            for (String dependency : entry.getValue()) {
                if (!owners.contains(dependency)) {
                    SourceDescriptor source = sources.stream()
                            .map(PipelineSource::source)
                            .map(ContentSource::descriptor)
                            .filter(descriptor -> descriptor.ownerId().equals(entry.getKey()))
                            .findFirst()
                            .orElse(null);
                    addIssue(
                            options,
                            diagnostics,
                            options.isStrict() ? Severity.ERROR : Severity.WARNING,
                            source,
                            Stage.SOURCE,
                            Code.UNKNOWN_DEPENDENCY,
                            null,
                            null,
                            null,
                            "Owner '" + entry.getKey() + "' declares missing dependency '" + dependency + "'",
                            "Supply the dependency source or remove the dependency declaration."
                    );
                }
            }
        }
        Map<String, Set<String>> immutable = new LinkedHashMap<>();
        normalized.forEach((key, value) -> immutable.put(key, Set.copyOf(value)));
        return Collections.unmodifiableMap(immutable);
    }

    private void prepareSource(
            PipelineSource sourceView,
            BuildOptions options,
            PreparationData data
    ) {
        Map<String, NamespaceAccumulator> namespaces = new LinkedHashMap<>();
        int registerStart = data.registerFiles.size();
        int reloadStart = data.reloadFiles.size();
        for (LocatedFile index : sourceView.source().indexes()) {
            String namespace = index.path().segments().get(1);
            ManifestResult manifest = readManifest(
                    sourceView,
                    index,
                    options,
                    data.diagnostics
            );
            NamespaceAccumulator accumulator =
                    namespaces.computeIfAbsent(namespace, ignored -> new NamespaceAccumulator());
            accumulator.declaredOnRegister |= manifest.declaredOnRegister();
            accumulator.declaredOnReload |= manifest.declaredOnReload();
            accumulator.register.addAll(manifest.register());
            accumulator.reload.addAll(manifest.reload());
        }

        for (Map.Entry<String, NamespaceAccumulator> entry : namespaces.entrySet()) {
            resolveNamespace(sourceView, entry.getKey(), entry.getValue(), data);
        }

        List<LocatedContentPath> registerFiles = new ArrayList<>(
                data.registerFiles.subList(registerStart, data.registerFiles.size()));
        registerFiles.sort(Comparator.comparing(file -> file.logicalPath().value()));
        data.registerFiles.subList(registerStart, data.registerFiles.size()).clear();
        data.registerFiles.addAll(registerFiles);

        List<LocatedContentPath> reloadFiles = new ArrayList<>(
                data.reloadFiles.subList(reloadStart, data.reloadFiles.size()));
        reloadFiles.sort(Comparator.comparing(file -> file.logicalPath().value()));
        data.reloadFiles.subList(reloadStart, data.reloadFiles.size()).clear();
        data.reloadFiles.addAll(reloadFiles);

        for (LocatedContentPath registerFile : registerFiles) {
            parseContentFile(sourceView, registerFile, options, data);
        }
    }

    private ManifestResult readManifest(
            PipelineSource sourceView,
            LocatedFile index,
            BuildOptions options,
            DiagnosticCollector diagnostics
    ) {
        SourceDescriptor source = sourceView.source().descriptor();
        JsonElement document;
        try (InputStream input = sourceView.source().open(index.path()).orElseThrow()) {
            document = parseJson(input, options);
        } catch (RuntimeException | IOException failure) {
            diagnostics.error(
                    source,
                    Stage.MANIFEST,
                    Code.MANIFEST_INVALID,
                    index.path().value(),
                    null,
                    null,
                    "Cannot read index manifest: " + failure,
                    "Provide a readable UTF-8 JSON object."
            );
            return ManifestResult.empty();
        }
        if (document == null || !document.isJsonObject()) {
            diagnostics.error(
                    source,
                    Stage.MANIFEST,
                    Code.MANIFEST_INVALID,
                    index.path().value(),
                    "",
                    null,
                    "Index manifest must be a JSON object",
                    "Use an object containing on_register and/or on_reload arrays."
            );
            return ManifestResult.empty();
        }

        JsonObject root = document.getAsJsonObject();
        for (String field : root.keySet()) {
            if (!MANIFEST_FIELDS.contains(field)) {
                diagnostics.error(
                        source,
                        Stage.MANIFEST,
                        Code.MANIFEST_INVALID,
                        index.path().value(),
                        "/" + escapePointer(field),
                        null,
                        "Unsupported index field '" + field + "'",
                        "An index may only contain on_register and on_reload."
                );
            }
        }

        PathField register = readPathField(root, "on_register", source, index, diagnostics);
        PathField reload = readPathField(root, "on_reload", source, index, diagnostics);
        if (!register.declared() && !reload.declared()) {
            diagnostics.error(
                    source,
                    Stage.MANIFEST,
                    Code.MANIFEST_EMPTY,
                    index.path().value(),
                    "",
                    null,
                    "Index manifest declares neither on_register nor on_reload",
                    "Declare at least one path array; an empty array is valid."
            );
        }
        return new ManifestResult(
                register.declared(),
                reload.declared(),
                register.paths(),
                reload.paths()
        );
    }

    private PathField readPathField(
            JsonObject root,
            String field,
            SourceDescriptor source,
            LocatedFile index,
            DiagnosticCollector diagnostics
    ) {
        if (!root.has(field)) return new PathField(false, List.of());
        JsonElement value = root.get(field);
        if (!value.isJsonArray()) {
            diagnostics.error(
                    source,
                    Stage.MANIFEST,
                    Code.MANIFEST_INVALID,
                    index.path().value(),
                    "/" + field,
                    null,
                    "Index field '" + field + "' must be an array of content paths",
                    "Use an array of JSON strings."
            );
            return new PathField(false, List.of());
        }

        List<PathDeclaration> paths = new ArrayList<>();
        int arrayIndex = 0;
        for (JsonElement element : value.getAsJsonArray()) {
            String pointer = "/" + field + "/" + arrayIndex++;
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                diagnostics.error(
                        source,
                        Stage.MANIFEST,
                        Code.MANIFEST_INVALID,
                        index.path().value(),
                        pointer,
                        null,
                        "Index path entries must be strings",
                        "Use a path relative to data/<namespace>/ including the .json suffix."
                );
                continue;
            }
            paths.add(new PathDeclaration(element.getAsString(), pointer, index.path()));
        }
        return new PathField(true, List.copyOf(paths));
    }

    private void resolveNamespace(
            PipelineSource sourceView,
            String namespace,
            NamespaceAccumulator accumulator,
            PreparationData data
    ) {
        SourceDescriptor source = sourceView.source().descriptor();
        if (!accumulator.declaredOnRegister && !accumulator.declaredOnReload) {
            data.diagnostics.error(
                    source,
                    Stage.MANIFEST,
                    Code.MANIFEST_EMPTY,
                    null,
                    null,
                    null,
                    "Namespace '" + namespace + "' has no valid manifest declarations",
                    "Declare on_register and/or on_reload in at least one index."
            );
        }

        Set<String> registerPaths = accumulator.register.stream()
                .map(PathDeclaration::path)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> reloadPaths = accumulator.reload.stream()
                .map(PathDeclaration::path)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> crossListed = new LinkedHashSet<>(registerPaths);
        crossListed.retainAll(reloadPaths);
        for (String path : crossListed) {
            PathDeclaration declaration = accumulator.register.stream()
                    .filter(candidate -> candidate.path().equals(path))
                    .findFirst()
                    .orElseThrow();
            data.diagnostics.error(
                    source,
                    Stage.MANIFEST,
                    Code.MANIFEST_INVALID,
                    declaration.manifestPath().value(),
                    declaration.jsonPointer(),
                    null,
                    "Content path '" + path + "' is listed in both on_register and on_reload",
                    "List each content file in exactly one domain."
            );
        }

        Set<String> seenRegister = new HashSet<>();
        for (PathDeclaration declaration : accumulator.register) {
            if (crossListed.contains(declaration.path()) || !seenRegister.add(declaration.path())) {
                continue;
            }
            LocatedContentPath located = locateContent(
                    sourceView,
                    namespace,
                    declaration,
                    LocatedContentPath.Domain.REGISTER,
                    data.diagnostics
            );
            if (located == null) continue;
            data.registerFiles.add(located);
        }

        Set<String> seenReload = new HashSet<>();
        for (PathDeclaration declaration : accumulator.reload) {
            if (crossListed.contains(declaration.path()) || !seenReload.add(declaration.path())) {
                continue;
            }
            LocatedContentPath located = locateContent(
                    sourceView,
                    namespace,
                    declaration,
                    LocatedContentPath.Domain.RELOAD,
                    data.diagnostics
            );
            if (located != null) data.reloadFiles.add(located);
        }
    }

    private LocatedContentPath locateContent(
            PipelineSource sourceView,
            String namespace,
            PathDeclaration declaration,
            LocatedContentPath.Domain domain,
            DiagnosticCollector diagnostics
    ) {
        SourceDescriptor source = sourceView.source().descriptor();
        String invalid = JsonTreeBuilder.validateSourcePath(declaration.path());
        LogicalPath logicalPath = null;
        if (invalid == null) {
            try {
                logicalPath = LogicalPath.of("data/" + namespace + "/" + declaration.path());
            } catch (IllegalArgumentException failure) {
                invalid = failure.getMessage();
            }
        }
        if (invalid != null) {
            diagnostics.error(
                    source,
                    Stage.MANIFEST,
                    Code.INVALID_PATH,
                    declaration.manifestPath().value(),
                    declaration.jsonPointer(),
                    null,
                    "Invalid manifest path '" + declaration.path() + "': " + invalid,
                    "Use a slash-separated path relative to data/<namespace>/."
            );
            return null;
        }
        if (!sourceView.source().exists(logicalPath)) {
            diagnostics.error(
                    source,
                    Stage.SOURCE,
                    Code.SOURCE_FILE_MISSING,
                    logicalPath.value(),
                    declaration.jsonPointer(),
                    null,
                    "Manifest declares missing content file '" + logicalPath.value() + "'",
                    "Add the file to the source or remove the manifest entry."
            );
            return null;
        }
        return new LocatedContentPath(
                source,
                sourceView.order(),
                logicalPath,
                declaration.jsonPointer(),
                domain
        );
    }

    private void parseContentFile(
            PipelineSource sourceView,
            LocatedContentPath contentFile,
            BuildOptions options,
            PreparationData data
    ) {
        JsonElement document;
        try (InputStream input = sourceView.source().open(contentFile.logicalPath()).orElseThrow()) {
            document = parseJson(input, options);
        } catch (RuntimeException | IOException failure) {
            data.diagnostics.error(
                    contentFile.source(),
                    Stage.PARSE,
                    Code.SOURCE_READ_FAILED,
                    contentFile.logicalPath().value(),
                    null,
                    null,
                    "Cannot read content file: " + failure,
                    "Provide a readable UTF-8 JSON object."
            );
            return;
        }
        if (document == null || !document.isJsonObject()) {
            data.diagnostics.error(
                    contentFile.source(),
                    Stage.PARSE,
                    Code.CONTENT_INVALID,
                    contentFile.logicalPath().value(),
                    "",
                    null,
                    "Content file must be a JSON object",
                    "Use top-level arrays named by handler types."
            );
            return;
        }

        JsonObject root = document.getAsJsonObject();
        Set<String> topLevelTypes = handlers.stream()
                .filter(handler -> handler.getParentTypeName() == null)
                .map(TypeHandler::getTypeName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (String field : root.keySet()) {
            if (!topLevelTypes.contains(field)) {
                addIssue(
                        options,
                        data.diagnostics,
                        options.isStrict() ? Severity.ERROR : Severity.WARNING,
                        contentFile.source(),
                        Stage.PARSE,
                        Code.UNKNOWN_TYPE,
                        contentFile.logicalPath().value(),
                        "/" + escapePointer(field),
                        null,
                        "Unsupported top-level field '" + field + "'",
                        "Use one of " + topLevelTypes + "."
                );
            }
        }

        for (TypeHandler<?> handler : handlers) {
            if (handler.getParentTypeName() != null || !root.has(handler.getTypeName())) continue;
            JsonElement field = root.get(handler.getTypeName());
            String fieldPointer = "/" + escapePointer(handler.getTypeName());
            if (!field.isJsonArray()) {
                data.diagnostics.error(
                        contentFile.source(),
                        Stage.PARSE,
                        Code.CONTENT_INVALID,
                        contentFile.logicalPath().value(),
                        fieldPointer,
                        handler.getTypeName(),
                        "Top-level field '" + handler.getTypeName() + "' must be an array",
                        "Wrap definitions in an array of JSON objects."
                );
                continue;
            }
            int index = 0;
            for (JsonElement element : field.getAsJsonArray()) {
                String pointer = fieldPointer + "/" + index++;
                if (!element.isJsonObject()) {
                    data.diagnostics.error(
                            contentFile.source(),
                            Stage.PARSE,
                            Code.CONTENT_INVALID,
                            contentFile.logicalPath().value(),
                            pointer,
                            handler.getTypeName(),
                            "Definition must be a JSON object",
                            "Replace the array entry with an object."
                    );
                    continue;
                }
                collectDefinition(
                        sourceView,
                        contentFile,
                        handler,
                        element.getAsJsonObject(),
                        pointer,
                        options,
                        data
                );
            }
        }

        for (TypeHandler<?> embedded : handlers) {
            if (embedded.getParentTypeName() == null) continue;
            TypeHandler<?> parent = findHandler(embedded.getParentTypeName());
            if (parent == null || !root.has(parent.getTypeName())) continue;
            JsonElement parentField = root.get(parent.getTypeName());
            if (!parentField.isJsonArray()) continue;
            int parentIndex = 0;
            for (JsonElement parentElement : parentField.getAsJsonArray()) {
                String parentPointer = "/" + escapePointer(parent.getTypeName()) + "/" + parentIndex++;
                if (!parentElement.isJsonObject()) continue;
                List<JsonObject> extracted;
                try {
                    extracted = embedded.extractEmbedded(parentElement.getAsJsonObject());
                } catch (RuntimeException failure) {
                    data.diagnostics.error(
                            contentFile.source(),
                            Stage.PARSE,
                            Code.PARSE_FAILED,
                            contentFile.logicalPath().value(),
                            parentPointer,
                            embedded.getTypeName(),
                            "Embedded definition extraction failed: " + failure,
                            "Fix the parent entry shape."
                    );
                    continue;
                }
                if (extracted == null) continue;
                int embeddedIndex = 0;
                for (JsonObject object : extracted) {
                    String key = embedded.getEmbeddedKeyName() == null
                            ? embedded.getTypeName()
                            : embedded.getEmbeddedKeyName();
                    String pointer = parentPointer + "/" + escapePointer(key);
                    if (extracted.size() > 1) pointer += "/" + embeddedIndex;
                    embeddedIndex++;
                    collectDefinition(
                            sourceView,
                            contentFile,
                            embedded,
                            object,
                            pointer,
                            options,
                            data
                    );
                }
            }
        }
    }

    private void validateReloadContentFile(
            List<PipelineSource> sources,
            LocatedContentPath contentFile,
            BuildOptions options,
            DiagnosticCollector diagnostics
    ) {
        PipelineSource sourceView = sources.stream()
                .filter(source -> {
                    SourceDescriptor descriptor = source.source().descriptor();
                    return descriptor.sourceId().equals(contentFile.source().sourceId())
                            && descriptor.rootId().equals(contentFile.source().rootId());
                })
                .findFirst()
                .orElse(null);
        if (sourceView == null) {
            diagnostics.error(
                    contentFile.source(),
                    Stage.SOURCE,
                    Code.SOURCE_READ_FAILED,
                    contentFile.logicalPath().value(),
                    null,
                    null,
                    "Reload content source is no longer available",
                    "Open the source again and repeat strict preparation."
            );
            return;
        }

        JsonElement document;
        try (InputStream input = sourceView.source().open(contentFile.logicalPath()).orElseThrow()) {
            document = parseJson(input, options);
        } catch (RuntimeException | IOException failure) {
            diagnostics.error(
                    contentFile.source(),
                    Stage.PARSE,
                    Code.SOURCE_READ_FAILED,
                    contentFile.logicalPath().value(),
                    null,
                    null,
                    "Cannot read reload content file: " + failure,
                    "Provide a readable UTF-8 JSON object."
            );
            return;
        }
        if (document == null || !document.isJsonObject()) {
            diagnostics.error(
                    contentFile.source(),
                    Stage.PARSE,
                    Code.CONTENT_INVALID,
                    contentFile.logicalPath().value(),
                    "",
                    null,
                    "Reload content file must be a JSON object",
                    "Use top-level arrays named by registered reload handler types."
            );
            return;
        }

        JsonObject root = document.getAsJsonObject();
        for (String field : root.keySet()) {
            ReloadHandler<?> handler = ReloadHandlerRegistry.get(field);
            if (handler == null) {
                addIssue(
                        options,
                        diagnostics,
                        options.isStrict() ? Severity.ERROR : Severity.WARNING,
                        contentFile.source(),
                        Stage.PARSE,
                        Code.UNKNOWN_TYPE,
                        contentFile.logicalPath().value(),
                        "/" + escapePointer(field),
                        field,
                        "Unknown reload type '" + field + "'",
                        "Register a ReloadHandler for this top-level field."
                );
                continue;
            }
            try {
                Decoded<?> decoded = handler.decode(root);
                for (String error : decoded.errors()) {
                    diagnostics.error(
                            contentFile.source(),
                            Stage.PARSE,
                            Code.CONTENT_INVALID,
                            contentFile.logicalPath().value(),
                            "/" + escapePointer(field),
                            field,
                            error,
                            "Fix the reload payload according to the handler contract."
                    );
                }
            } catch (RuntimeException failure) {
                diagnostics.error(
                        contentFile.source(),
                        Stage.PARSE,
                        Code.CONTENT_INVALID,
                        contentFile.logicalPath().value(),
                        "/" + escapePointer(field),
                        field,
                        "Reload handler decode failed: " + failure,
                        "Make decode side-effect-free and return payload errors instead of throwing."
                );
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void collectDefinition(
            PipelineSource sourceView,
            LocatedContentPath contentFile,
            TypeHandler<?> handler,
            JsonObject json,
            String pointer,
            BuildOptions options,
            PreparationData data
    ) {
        Object parsed;
        try {
            parsed = ((TypeHandler) handler).parse(json);
        } catch (RuntimeException failure) {
            data.diagnostics.error(
                    contentFile.source(),
                    Stage.PARSE,
                    Code.PARSE_FAILED,
                    contentFile.logicalPath().value(),
                    pointer,
                    handler.getTypeName(),
                    "Failed to parse '" + handler.getTypeName() + "': " + failure.getMessage(),
                    "Fix the fields at the reported JSON pointer."
            );
            return;
        }

        DefinitionKey key;
        try {
            String identity = ((TypeHandler<Object>) handler)
                    .resolveIdentity(hostModId, parsed, options.isStrict());
            key = identity == null || identity.isBlank()
                    ? null
                    : new DefinitionKey(handler.getTypeName(), identity);
        } catch (RuntimeException failure) {
            data.diagnostics.add(
                    options.isStrict() ? Severity.ERROR : Severity.WARNING,
                    contentFile.source(),
                    Stage.PARSE,
                    Code.IDENTITY_FAILED,
                    contentFile.logicalPath().value(),
                    pointer,
                    handler.getTypeName(),
                    "Failed to resolve definition identity: " + failure.getMessage(),
                    "Return a stable explicit namespace-qualified identity in strict mode."
            );
            key = null;
        }

        if (key == null && options.isStrict()) {
            data.diagnostics.error(
                    contentFile.source(),
                    Stage.PARSE,
                    Code.MISSING_IDENTITY,
                    contentFile.logicalPath().value(),
                    pointer,
                    handler.getTypeName(),
                    "Strict definitions require a non-empty identity",
                    "Return a stable explicit namespace-qualified identity from resolveIdentity."
            );
        }

        data.allDefinitions.add(new LocatedDefinition(
                key,
                handler,
                parsed,
                contentFile.source(),
                sourceView.order(),
                contentFile.logicalPath(),
                pointer,
                data.declarationOrder++
        ));
    }

    private void checkStrictCapabilities(BuildOptions options, PreparationData data) {
        if (!options.isStrict()) return;
        Map<String, Integer> occurrences = new HashMap<>();
        for (TypeHandler<?> handler : handlers) {
            occurrences.merge(handler.getTypeName(), 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : occurrences.entrySet()) {
            if (entry.getValue() > 1) {
                data.diagnostics.error(
                        null,
                        Stage.VALIDATION,
                        Code.HANDLER_NOT_STRICT_READY,
                        null,
                        null,
                        entry.getKey(),
                        "Handler type '" + entry.getKey() + "' is registered more than once",
                        "Register exactly one handler for each type domain."
                );
            }
        }

        Set<String> usedHandlers = data.allDefinitions.stream()
                .map(LocatedDefinition::typeName)
                .collect(Collectors.toSet());
        for (TypeHandler<?> handler : handlers) {
            if (!usedHandlers.contains(handler.getTypeName())) continue;
            Set<HandlerCapability> capabilities = handler.capabilities();
            boolean hasRole = capabilities.contains(HandlerCapability.NON_REGISTRATION)
                    ^ capabilities.contains(HandlerCapability.REGISTRY_CLAIMS);
            if (!capabilities.contains(HandlerCapability.STRICT_PREFLIGHT) || !hasRole) {
                data.diagnostics.error(
                        null,
                        Stage.VALIDATION,
                        Code.HANDLER_NOT_STRICT_READY,
                        null,
                        null,
                        handler.getTypeName(),
                        "Handler '" + handler.getTypeName()
                                + "' does not declare strict preflight and exactly one identity role",
                        "Declare STRICT_PREFLIGHT plus NON_REGISTRATION or REGISTRY_CLAIMS."
                );
            }
        }
    }

    private List<LocatedDefinition<?>> selectDefinitions(
            BuildOptions options,
            List<LocatedDefinition<?>> definitions
    ) {
        Map<DefinitionKey, List<LocatedDefinition<?>>> grouped = new LinkedHashMap<>();
        List<LocatedDefinition<?>> anonymous = new ArrayList<>();
        for (LocatedDefinition<?> definition : definitions) {
            if (definition.key() == null) {
                anonymous.add(definition);
            } else {
                grouped.computeIfAbsent(definition.key(), ignored -> new ArrayList<>()).add(definition);
            }
        }

        Set<LocatedDefinition<?>> selected =
                Collections.newSetFromMap(new IdentityHashMap<>());
        selected.addAll(anonymous);
        for (List<LocatedDefinition<?>> candidates : grouped.values()) {
            TypeHandler<?> handler = candidates.get(0).handler();
            Set<HandlerCapability> capabilities = handler.capabilities();
            boolean configuration = capabilities.contains(HandlerCapability.NON_REGISTRATION);
            boolean strictRegistration = options.isStrict()
                    && capabilities.contains(HandlerCapability.REGISTRY_CLAIMS)
                    && !configuration;
            if (strictRegistration) {
                selected.addAll(candidates);
            } else {
                selected.add(candidates.stream().max(priorityComparator()).orElseThrow());
            }
        }

        return definitions.stream()
                .filter(selected::contains)
                .toList();
    }

    private void collectValidationDiagnostics(
            BuildOptions options,
            List<LocatedDefinition<?>> selected,
            List<PipelineSource> sources,
            DefinitionDirectory directory,
            Map<String, Set<String>> dependencies,
            DiagnosticCollector diagnostics
    ) {
        if (!options.isStrict()) return;
        for (LocatedDefinition<?> definition : selected.stream()
                .sorted(stableDefinitionComparator(handlerOrder()))
                .toList()) {
            PipelineValidationContext context = new PipelineValidationContext(
                    hostModId,
                    definition,
                    sources,
                    directory.candidates(),
                    directory.effective(),
                    dependencies,
                    reservedSymbols,
                    diagnostics
            );
            try {
                invokeValidate(definition, context);
            } catch (RuntimeException failure) {
                diagnostics.error(
                        definition.source(),
                        Stage.VALIDATION,
                        Code.VALIDATION_FAILED,
                        definition.logicalPath().value(),
                        definition.jsonPointer(),
                        definition.typeName(),
                        "Handler validation failed unexpectedly: " + failure,
                        "Make validate side-effect-free and report expected failures through the context."
                );
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void invokeValidate(LocatedDefinition<?> definition, PipelineValidationContext context) {
        ((TypeHandler) definition.handler()).validate(definition.definition(), context);
    }

    private void collectGroupCycleDiagnostics(
            BuildOptions options,
            List<LocatedDefinition<?>> selected,
            List<PipelineSource> sources,
            DefinitionDirectory directory,
            Map<String, Set<String>> dependencies,
            DiagnosticCollector diagnostics
    ) {
        Map<DefinitionKey, LocatedDefinition<?>> byKey = new LinkedHashMap<>();
        for (LocatedDefinition<?> definition : selected) {
            if (definition.key() != null) {
                byKey.putIfAbsent(definition.key(), definition);
            }
        }

        Set<DefinitionKey> reported = new HashSet<>();
        for (LocatedDefinition<?> definition : byKey.values()) {
            LinkedHashSet<DefinitionKey> path = new LinkedHashSet<>();
            LocatedDefinition<?> cursor = definition;
            while (cursor != null && cursor.key() != null && path.add(cursor.key())) {
                DefinitionKey parentKey;
                try {
                    parentKey = invokeParent(cursor);
                } catch (RuntimeException failure) {
                    addIssue(
                            options,
                            diagnostics,
                            Severity.ERROR,
                            cursor.source(),
                            Stage.VALIDATION,
                            Code.VALIDATION_FAILED,
                            cursor.logicalPath().value(),
                            cursor.jsonPointer(),
                            cursor.typeName(),
                            "Failed to resolve parent definition: " + failure,
                            "Return a stable parent DefinitionKey."
                    );
                    cursor = null;
                    continue;
                }
                cursor = parentKey == null ? null : byKey.get(parentKey);
            }
            if (cursor == null || cursor.key() == null || !path.contains(cursor.key())) continue;

            boolean first = false;
            for (DefinitionKey key : path) {
                if (key.equals(cursor.key())) first = true;
                if (!first || !reported.add(key)) continue;
                LocatedDefinition<?> member = byKey.get(key);
                addIssue(
                        options,
                        diagnostics,
                        options.isStrict() ? Severity.ERROR : Severity.WARNING,
                        member.source(),
                        Stage.VALIDATION,
                        Code.GROUP_CYCLE,
                        member.logicalPath().value(),
                        member.jsonPointer(),
                        member.typeName(),
                        "Definition parent cycle includes " + path,
                        "Remove the cycle so parents can be applied before children."
                );
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static DefinitionKey invokeParent(LocatedDefinition<?> definition) {
        return ((TypeHandler) definition.handler()).parentDefinition(definition.definition());
    }

    private List<RegistryClaim> collectClaims(
            BuildOptions options,
            List<LocatedDefinition<?>> selected,
            List<PipelineSource> sources,
            DefinitionDirectory directory,
            Map<String, Set<String>> dependencies,
            DiagnosticCollector diagnostics
    ) {
        List<RegistryClaim> claims = new ArrayList<>();
        for (LocatedDefinition<?> definition : selected.stream()
                .sorted(stableDefinitionComparator(handlerOrder()))
                .toList()) {
            if (!definition.handler().capabilities().contains(HandlerCapability.REGISTRY_CLAIMS)) {
                continue;
            }
            PipelineValidationContext context = new PipelineValidationContext(
                    hostModId,
                    definition,
                    sources,
                    directory.candidates(),
                    directory.effective(),
                    dependencies,
                    reservedSymbols,
                    diagnostics
            );
            try {
                claims.addAll(invokeClaims(definition, context));
            } catch (RuntimeException failure) {
                addIssue(
                        options,
                        diagnostics,
                        options.isStrict() ? Severity.ERROR : Severity.WARNING,
                        definition.source(),
                        Stage.CLAIMS,
                        Code.CLAIM_FAILED,
                        definition.logicalPath().value(),
                        definition.jsonPointer(),
                        definition.typeName(),
                        "Failed to declare registry claims: " + failure,
                        "Derive every final registry id without creating registration side effects."
                );
            }
        }

        LinkedHashMap<RegistryClaim, RegistryClaim> unique = new LinkedHashMap<>();
        for (RegistryClaim claim : claims) unique.put(claim, claim);
        List<RegistryClaim> uniqueClaims = List.copyOf(unique.values());

        for (RegistryClaim claim : uniqueClaims) {
            if (reservedRegistryClaims.contains(claim.registryKey())) {
                addIssue(
                        options,
                        diagnostics,
                        Severity.ERROR,
                        claim.source(),
                        Stage.CLAIMS,
                        Code.CLAIM_CONFLICT,
                        claim.logicalPath().value(),
                        claim.jsonPointer(),
                        claim.definitionKey().typeDomain(),
                        "Registry claim conflicts with reserved host entry "
                                + claim.registry() + " / " + claim.id(),
                        "Choose a different registry id or remove the conflicting definition."
                );
            }
        }

        Map<RegistryClaim.RegistryKey, List<RegistryClaim>> byRegistry = new LinkedHashMap<>();
        for (RegistryClaim claim : uniqueClaims) {
            byRegistry.computeIfAbsent(claim.registryKey(), ignored -> new ArrayList<>()).add(claim);
        }
        for (Map.Entry<RegistryClaim.RegistryKey, List<RegistryClaim>> entry : byRegistry.entrySet()) {
            if (entry.getValue().size() < 2) continue;
            RegistryClaim first = entry.getValue().get(0);
            String participants = entry.getValue().stream()
                    .map(claim -> claim.source().sourceId() + ":"
                            + claim.logicalPath().value() + "#" + claim.jsonPointer())
                    .collect(Collectors.joining(", "));
            addIssue(
                    options,
                    diagnostics,
                    options.isStrict() ? Severity.ERROR : Severity.WARNING,
                    first.source(),
                    Stage.CLAIMS,
                    Code.CLAIM_CONFLICT,
                    first.logicalPath().value(),
                    first.jsonPointer(),
                    first.definitionKey().typeDomain(),
                    "Registry claim conflict for " + entry.getKey().registry() + " / "
                            + entry.getKey().id() + " from " + participants,
                    "The host must choose a registry conflict policy before apply."
            );
        }
        return uniqueClaims;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<RegistryClaim> invokeClaims(
            LocatedDefinition<?> definition,
            PipelineValidationContext context
    ) {
        return ((TypeHandler) definition.handler()).claims(definition.definition(), context);
    }

    private List<LocatedDefinition<?>> stableApplyOrder(
            List<LocatedDefinition<?>> definitions,
            Map<String, Integer> handlerOrder
    ) {
        Comparator<LocatedDefinition<?>> comparator = stableDefinitionComparator(handlerOrder);
        LinkedHashMap<DefinitionKey, LocatedDefinition<?>> byKey = new LinkedHashMap<>();
        for (LocatedDefinition<?> definition : definitions) {
            if (definition.key() != null) byKey.putIfAbsent(definition.key(), definition);
        }

        Map<DefinitionKey, List<DefinitionKey>> children = new HashMap<>();
        Map<DefinitionKey, Integer> indegree = new HashMap<>();
        Set<DefinitionKey> keys = byKey.keySet();
        for (LocatedDefinition<?> definition : definitions) {
            if (definition.key() != null) indegree.putIfAbsent(definition.key(), 0);
        }
        for (LocatedDefinition<?> definition : definitions) {
            DefinitionKey parent = invokeParent(definition);
            if (definition.key() == null || parent == null || !keys.contains(parent)) continue;
            children.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(definition.key());
            indegree.merge(definition.key(), 1, Integer::sum);
        }

        PriorityQueue<LocatedDefinition<?>> ready = new PriorityQueue<>(comparator);
        for (LocatedDefinition<?> definition : definitions) {
            if (definition.key() == null || indegree.get(definition.key()) == 0) ready.add(definition);
        }

        List<LocatedDefinition<?>> ordered = new ArrayList<>(definitions.size());
        while (!ready.isEmpty()) {
            LocatedDefinition<?> definition = ready.remove();
            ordered.add(definition);
            if (definition.key() == null) continue;
            for (DefinitionKey childKey : children.getOrDefault(definition.key(), List.of())) {
                int remaining = indegree.merge(childKey, -1, Integer::sum);
                if (remaining == 0) ready.add(byKey.get(childKey));
            }
        }

        if (ordered.size() != definitions.size()) {
            Set<LocatedDefinition<?>> included =
                    Collections.newSetFromMap(new IdentityHashMap<>());
            included.addAll(ordered);
            definitions.stream()
                    .filter(definition -> !included.contains(definition))
                    .sorted(comparator)
                    .forEach(ordered::add);
        }
        return List.copyOf(ordered);
    }

    private Comparator<LocatedDefinition<?>> stableDefinitionComparator(Map<String, Integer> handlerOrder) {
        return Comparator
                .comparingInt((LocatedDefinition<?> definition) -> definition.handler().getPhase())
                .thenComparingInt(definition ->
                        handlerOrder.getOrDefault(definition.typeName(), Integer.MAX_VALUE))
                .thenComparingInt(LocatedDefinition::sourceOrder)
                .thenComparing(definition -> definition.logicalPath().value())
                .thenComparing(LocatedDefinition::jsonPointer)
                .thenComparingInt(LocatedDefinition::declarationOrder);
    }

    private static Comparator<LocatedDefinition<?>> priorityComparator() {
        return Comparator
                .comparingInt(LocatedDefinition<?>::sourceOrder)
                .thenComparingInt(LocatedDefinition::declarationOrder);
    }

    private Map<String, Integer> handlerOrder() {
        Map<String, Integer> order = new LinkedHashMap<>();
        for (int index = 0; index < handlers.size(); index++) {
            order.putIfAbsent(handlers.get(index).getTypeName(), index);
        }
        return order;
    }

    private TypeHandler<?> findHandler(String typeName) {
        for (TypeHandler<?> handler : handlers) {
            if (handler.getTypeName().equals(typeName)) return handler;
        }
        return null;
    }

    private void addIssue(
            BuildOptions options,
            DiagnosticCollector diagnostics,
            Severity severity,
            SourceDescriptor source,
            Stage stage,
            Code code,
            String logicalPath,
            String jsonPointer,
            String handler,
            String message,
            String suggestion
    ) {
        if (options.isStrict()) {
            severity = Severity.ERROR;
        }
        diagnostics.add(severity, source, stage, code, logicalPath, jsonPointer, handler, message, suggestion);
    }

    private BuildDiagnostic definitionDiagnostic(
            LocatedDefinition<?> definition,
            Stage stage,
            Code code,
            String message,
            String suggestion
    ) {
        return new BuildDiagnostic(
                Severity.ERROR,
                stage,
                code,
                definition.source().sourceId(),
                definition.source().ownerId(),
                definition.logicalPath().value(),
                definition.jsonPointer(),
                definition.typeName(),
                message,
                suggestion
        );
    }

    private BuildDiagnostic sessionDiagnostic(
            Stage stage,
            Code code,
            String handler,
            String message,
            String suggestion
    ) {
        return new BuildDiagnostic(
                Severity.ERROR,
                stage,
                code,
                null,
                null,
                null,
                null,
                handler,
                message,
                suggestion
        );
    }

    private static String escapePointer(String segment) {
        return segment.replace("~", "~0").replace("/", "~1");
    }

    private static JsonElement parseJson(InputStream input, BuildOptions options) throws IOException {
        byte[] bytes = input.readAllBytes();
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException exception) {
            throw new IOException("INVALID_UTF8", exception);
        }
        return options.isStrict()
                ? StrictResourceJson.parse(text)
                : JsonParser.parseString(text);
    }

    private static String requireNonBlank(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    private static final class PreparationData {
        private final DiagnosticCollector diagnostics;
        private final List<LocatedDefinition<?>> allDefinitions = new ArrayList<>();
        private final List<LocatedContentPath> registerFiles = new ArrayList<>();
        private final List<LocatedContentPath> reloadFiles = new ArrayList<>();
        private int declarationOrder;

        private PreparationData(DiagnosticCollector diagnostics) {
            this.diagnostics = diagnostics;
        }
    }

    private static final class NamespaceAccumulator {
        private boolean declaredOnRegister;
        private boolean declaredOnReload;
        private final List<PathDeclaration> register = new ArrayList<>();
        private final List<PathDeclaration> reload = new ArrayList<>();
    }

    private record PathField(boolean declared, List<PathDeclaration> paths) {
    }

    private record PathDeclaration(String path, String jsonPointer, LogicalPath manifestPath) {
    }

    private record ManifestResult(
            boolean declaredOnRegister,
            boolean declaredOnReload,
            List<PathDeclaration> register,
            List<PathDeclaration> reload
    ) {
        private static ManifestResult empty() {
            return new ManifestResult(false, false, List.of(), List.of());
        }
    }

    private record DefinitionDirectory(
            Map<DefinitionKey, List<LocatedDefinition<?>>> candidates,
            Map<DefinitionKey, LocatedDefinition<?>> effective
    ) {
        private static DefinitionDirectory from(List<LocatedDefinition<?>> definitions) {
            Map<DefinitionKey, List<LocatedDefinition<?>>> candidates = new LinkedHashMap<>();
            for (LocatedDefinition<?> definition : definitions) {
                if (definition.key() == null) continue;
                candidates.computeIfAbsent(definition.key(), ignored -> new ArrayList<>()).add(definition);
            }

            Map<DefinitionKey, LocatedDefinition<?>> effective = new LinkedHashMap<>();
            candidates.forEach((key, values) -> effective.put(
                    key,
                    values.stream().max(priorityComparator()).orElseThrow()
            ));
            Map<DefinitionKey, List<LocatedDefinition<?>>> immutableCandidates = new LinkedHashMap<>();
            candidates.forEach((key, values) ->
                    immutableCandidates.put(key, List.copyOf(values)));
            return new DefinitionDirectory(
                    Collections.unmodifiableMap(immutableCandidates),
                    Collections.unmodifiableMap(effective)
            );
        }
    }
}
