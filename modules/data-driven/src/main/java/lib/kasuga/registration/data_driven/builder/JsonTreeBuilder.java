package lib.kasuga.registration.data_driven.builder;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.logging.LogUtils;
import lib.kasuga.content.graph.GraphCycleDetector;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.TypeHandlerRegistry;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;
import lib.kasuga.registration.data_driven.context.RegBuildContext;
import lib.kasuga.registration.data_driven.dedup.DuplicateIdResolver;
import lib.kasuga.registration.data_driven.handler.*;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModFileInfo;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforgespi.locating.IModFile;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

public class JsonTreeBuilder {

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Gson GSON = new Gson();
    private static boolean handlersRegistered = false;

    /**
     * Loading errors bucketed by mod id. A bucket per mod means that a later
     * {@link #buildForMod(String)} call for another mod can no longer erase the diagnostics raised
     * for an earlier one (the previous single global list was cleared on every call, so callers only
     * ever observed the last mod's errors).
     */
    private static final Map<String, List<Throwable>> loadingErrorsByMod = new ConcurrentHashMap<>();

    private static synchronized void ensureHandlersRegistered() {
        if (handlersRegistered) return;
        handlersRegistered = true;
        TypeHandlerRegistry.register(new RegistryGroupHandler());
        TypeHandlerRegistry.register(new BlockTypeHandler());
        TypeHandlerRegistry.register(new ItemTypeHandler());
        TypeHandlerRegistry.register(new BlockEntityTypeHandler());
    }

    public static JsonRegistryGroup buildForMod(String modId) {
        ensureHandlersRegistered();
        clearLoadingErrors(modId);

        Path indexDir = findIndexDir(modId);
        if (indexDir == null) {
            Path legacyDir = findLegacyDataDrivenDir(modId);
            if (legacyDir != null) {
                String msg = "Mod '" + modId + "' still ships the deprecated data-driven layout at "
                        + "'data/" + modId + "/kasugalib/' (" + legacyDir + "), which is no longer read. "
                        + "Migrate the content files and add an index manifest at "
                        + "'data/" + modId + "/kasuga_lib/data_driven/<name>.json' that lists them in a "
                        + "\"sources\" array (paths relative to 'data/" + modId + "/').";
                LOGGER.warn(msg);
                addLoadingError(modId, new IllegalStateException(msg));
            } else {
                LOGGER.debug("No data-driven index directory for mod '{}'", modId);
            }
            return null;
        }

        JsonRegistryGroup rootGroup = new JsonRegistryGroup(modId + ":json_root");
        rootGroup.withProperty(ResourceLocation.class,
            loc -> ResourceLocation.fromNamespaceAndPath(modId, loc.getPath()));
        RegBuildContext context = new RegBuildContext(modId, rootGroup);

        // Index files are pure manifests: each lists content files via its "sources" array.
        // Every content file is parsed at most once, even if referenced from several manifests.
        Map<TypeHandler<?>, List<ParsedEntry>> parsed = new LinkedHashMap<>();
        Set<String> parsedSources = new HashSet<>();
        try (Stream<Path> files = Files.list(indexDir)) {
            files.filter(p -> p.toString().endsWith(".json"))
                 .sorted()
                 .forEach(path -> parseIndexFile(path, modId, parsedSources, parsed));
        } catch (IOException e) {
            LOGGER.error("Error scanning directory: {}", indexDir, e);
        }

        if (parsed.isEmpty()) {
            String msg = "Data-driven index directory for mod '" + modId + "' exists (" + indexDir
                    + ") but no entries were loaded; expected at least one content file listed by a "
                    + "manifest's \"sources\" array";
            LOGGER.warn(msg);
            addLoadingError(modId, new IllegalStateException(msg));
            return null;
        }

        int totalEntries = parsed.values().stream().mapToInt(List::size).sum();
        LOGGER.info("Loaded {} JSON entries across {} types for mod '{}'",
                totalEntries, parsed.size(), modId);

        // Duplicate ids are resolved in a side-effect-free window before any handler is applied:
        // a losing entry never reaches apply, so MappedRegistry never sees a duplicated key.
        Map<TypeHandler<?>, List<Object>> surviving = resolveDuplicates(modId, parsed);

        // Apply handlers by lifecycle phase; groups are sorted so parents precede children.
        List<TypeHandler<?>> ordered = TypeHandlerRegistry.all().stream()
            .sorted(Comparator.comparingInt(TypeHandler::getPhase))
            .toList();

        for (TypeHandler<?> handler : ordered) {
            List<Object> defs = surviving.getOrDefault(handler, List.of());
            if (defs.isEmpty()) continue;
            LOGGER.debug("Applying {} entries for type '{}'", defs.size(), handler.getTypeName());

            // Sort registry_groups by parent dependency so parents are applied before children
            if (handler instanceof RegistryGroupHandler) {
                List<RegistryGroupDef> groupDefs = new ArrayList<>(defs.size());
                for (Object def : defs) {
                    groupDefs.add((RegistryGroupDef) def);
                }
                for (RegistryGroupDef def : topoSortGroupDefs(groupDefs)) {
                    applyUnchecked(handler, def, context);
                }
                continue;
            }

            for (Object def : defs) {
                applyUnchecked(handler, def, context);
            }
        }

        return rootGroup;
    }

    public static Map<String, JsonRegistryGroup> buildAll() {
        Set<Path> visitedRoots = new HashSet<>();
        Map<String, JsonRegistryGroup> result = new LinkedHashMap<>();

        for (IModFileInfo modFile : ModList.get().getModFiles()) {
            Path rootPath;
            try {
                rootPath = modFile.getFile().getFilePath();
            } catch (Exception e) {
                continue;
            }
            if (!visitedRoots.add(rootPath)) continue;

            for (IModInfo mod : modFile.getMods()) {
                String modId = mod.getModId();
                try {
                    JsonRegistryGroup root = buildForMod(modId);
                    if (root != null) {
                        result.put(modId, root);
                    }
                } catch (Exception e) {
                    LOGGER.error("Failed to load JSON registrations for mod '{}'", modId, e);
                }
            }
        }

        return result;
    }

    /**
     * Parses one index file. An index file is a pure manifest: its only supported top-level field is
     * {@code sources}, an array of content-file paths (each relative to {@code data/<mod>/} and
     * including the {@code .json} suffix). The index does not name any registration type.
     */
    private static void parseIndexFile(Path path, String modId, Set<String> parsedSources,
                                       Map<TypeHandler<?>, List<ParsedEntry>> parsed) {
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            if (root == null) return;

            for (String field : root.keySet()) {
                if (!"sources".equals(field)) {
                    String msg = "Index file '" + path.getFileName() + "' for mod '" + modId
                            + "' contains unsupported field '" + field
                            + "'; an index file may only contain 'sources'";
                    LOGGER.error(msg);
                    addLoadingError(modId, new IllegalStateException(msg));
                }
            }

            JsonElement sourcesElement = root.get("sources");
            if (sourcesElement == null || !sourcesElement.isJsonArray()) {
                String msg = "Index file '" + path.getFileName() + "' for mod '" + modId
                        + "' must contain a 'sources' array";
                LOGGER.error(msg);
                addLoadingError(modId, new IllegalStateException(msg));
                return;
            }

            for (JsonElement element : sourcesElement.getAsJsonArray()) {
                if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                    String msg = "Index file '" + path.getFileName() + "' for mod '" + modId
                            + "': every 'sources' entry must be a string path";
                    LOGGER.error(msg);
                    addLoadingError(modId, new IllegalStateException(msg));
                    continue;
                }
                parseSource(modId, element.getAsString(), parsedSources, parsed);
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing JSON file: {}", path, e);
            addLoadingError(modId, new IOException("Error parsing JSON file: " + path, e));
        }
    }

    /**
     * Validates and resolves one content-file reference, then parses its definitions.
     * <p>
     * The path is relative to {@code data/<mod>/} and includes the {@code .json} suffix, e.g.
     * {@code "carriages/m1/blocks.json"} → {@code data/<mod>/carriages/m1/blocks.json}.
     * A path already parsed for this mod is skipped (references are deduplicated); content files
     * never reference other files, so no recursion or cycle detection is needed.
     * <p>
     * Pure function with no NeoForge dependency, exposed so the path contract can be asserted by a
     * plain JVM test (in the same spirit as {@link #indexDirectorySegments(String)}).
     *
     * @return {@code null} when the path is valid, otherwise a human-readable reason
     */
    public static String validateSourcePath(String path) {
        if (path == null || path.isBlank()) return "path is empty";
        if (path.startsWith("/")) return "path must be relative (remove the leading '/')";
        if (!path.endsWith(".json")) return "path must include the '.json' suffix";
        for (String segment : path.split("/", -1)) {
            if (segment.isEmpty()) return "path contains an empty segment";
            if (segment.equals(".") || segment.equals("..")) return "path must not contain '.' or '..'";
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void parseSource(String modId, String sourcePath, Set<String> parsedSources, Map parsed) {
        String invalid = validateSourcePath(sourcePath);
        if (invalid != null) {
            String msg = "Invalid source path '" + sourcePath + "' for mod '" + modId + "': " + invalid;
            LOGGER.error(msg);
            addLoadingError(modId, new IOException(msg));
            return;
        }
        if (!parsedSources.add(sourcePath)) {
            return; // already parsed for this mod
        }

        try {
            IModFile modFile = findModFile(modId);
            if (modFile == null) {
                String msg = "No mod file found for mod '" + modId + "' while resolving source '" + sourcePath + "'";
                LOGGER.error(msg);
                addLoadingError(modId, new IOException(msg));
                return;
            }
            // sourcePath is relative to data/<mod>/ and already includes the ".json" suffix;
            // IModFile.findResource resolves segments literally (no extension appended).
            String[] sourceParts = sourcePath.split("/");
            String[] pathSegments = new String[sourceParts.length + 2];
            pathSegments[0] = "data";
            pathSegments[1] = modId;
            System.arraycopy(sourceParts, 0, pathSegments, 2, sourceParts.length);
            Path contentPath = modFile.findResource(pathSegments);
            if (!Files.isRegularFile(contentPath)) {
                String msg = "Source file not found for mod '" + modId + "': data/" + modId + "/" + sourcePath;
                LOGGER.error(msg);
                addLoadingError(modId, new IOException(msg));
                return;
            }
            try (Reader reader = Files.newBufferedReader(contentPath)) {
                JsonObject content = GSON.fromJson(reader, JsonObject.class);
                if (content == null) return;
                dispatchContent(content, modId, contentPath, sourcePath, parsed);
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing source '{}' for mod '{}': {}", sourcePath, modId, e.getMessage());
            addLoadingError(modId, e);
        }
    }

    /**
     * Dispatches a content file to the type handlers. Each top-level field names a registration type
     * (e.g. {@code blocks}, {@code items}, {@code registry_groups}); the matching handler parses every
     * definition in that array. Embedded types (e.g. a {@code block_entity} inside a block definition)
     * are extracted from their parent object.
     * <p>
     * Malformed content is reported instead of skipped silently: an unknown top-level field, a
     * type field that is not an array, and a non-object array entry each produce an error log and a
     * per-mod loading error, matching the strictness already applied to index files.
     *
     * @param contentPath the content file being dispatched, used in diagnostics
     * @param sourcePath  the content file path relative to {@code data/<mod>/}, stored as the
     *                    entry's provenance for duplicate diagnostics
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dispatchContent(JsonObject root, String modId, Path contentPath,
                                        String sourcePath, Map parsed) {
        Set<String> knownFields = new LinkedHashSet<>();
        for (TypeHandler<?> handler : TypeHandlerRegistry.all()) {
            knownFields.add(handler.getTypeName());
        }
        for (String field : root.keySet()) {
            if (knownFields.contains(field)) continue;
            String msg = "Content file '" + contentPath + "' for mod '" + modId
                    + "' contains unsupported top-level field '" + field
                    + "'; supported fields are " + knownFields;
            LOGGER.error(msg);
            addLoadingError(modId, new IllegalStateException(msg));
        }

        for (TypeHandler<?> handler : TypeHandlerRegistry.all()) {
            if (!root.has(handler.getTypeName())) continue;
            JsonElement field = root.get(handler.getTypeName());
            if (!field.isJsonArray()) {
                String msg = "Content file '" + contentPath + "' for mod '" + modId
                        + "': field '" + handler.getTypeName() + "' must be an array";
                LOGGER.error(msg);
                addLoadingError(modId, new IllegalStateException(msg));
                continue;
            }
            for (JsonElement el : field.getAsJsonArray()) {
                if (!el.isJsonObject()) {
                    String msg = "Content file '" + contentPath + "' for mod '" + modId
                            + "': every entry in '" + handler.getTypeName() + "' must be a JSON object";
                    LOGGER.error(msg);
                    addLoadingError(modId, new IllegalStateException(msg));
                    continue;
                }
                parseAndCollect(parsed, handler, el.getAsJsonObject(), sourcePath);
            }
        }

        // Embedded types live inside their parent's object, so they are read from the parent field.
        for (TypeHandler<?> handler : TypeHandlerRegistry.all()) {
            if (handler.getParentTypeName() == null) continue;
            TypeHandler<?> parent = TypeHandlerRegistry.get(handler.getParentTypeName());
            if (parent == null || !root.has(parent.getTypeName())) continue;
            JsonElement parentField = root.get(parent.getTypeName());
            if (!parentField.isJsonArray()) continue;

            for (JsonElement el : parentField.getAsJsonArray()) {
                if (!el.isJsonObject()) continue;
                List<JsonObject> embedded = handler.extractEmbedded(el.getAsJsonObject());
                if (embedded == null) continue;
                for (JsonObject emb : embedded) {
                    parseAndCollect(parsed, handler, emb, sourcePath);
                }
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void parseAndCollect(Map parsed, TypeHandler handler, JsonObject json, String sourcePath) {
        Object def = handler.parse(json);
        List list = (List) parsed.computeIfAbsent(handler, k -> new ArrayList());
        list.add(new ParsedEntry(handler, def, sourcePath));
    }

    /**
     * A parsed definition together with the content file it came from. The provenance is carried
     * through the parse pipeline so a duplicate can be reported with both the winning and the losing
     * source file.
     */
    private record ParsedEntry(TypeHandler<?> handler, Object definition, String sourcePath) {}

    /**
     * Resolves duplicate ids across all parsed entries, reporting each conflict as a WARN log and a
     * loading error, and returns only the winning definitions grouped by their handler.
     *
     * <p>The candidate order is the loader's load order (index files sorted by path, then each
     * manifest's {@code sources} array, then each type field's array); last-wins therefore means "the
     * last source entry parsed". Failures while resolving an identity downgrade the entry to "no
     * identity" instead of escaping, keeping the no-crash guarantee.
     */
    private static Map<TypeHandler<?>, List<Object>> resolveDuplicates(
            String modId, Map<TypeHandler<?>, List<ParsedEntry>> parsed) {
        List<DuplicateIdResolver.Candidate> candidates = new ArrayList<>();
        for (Map.Entry<TypeHandler<?>, List<ParsedEntry>> entry : parsed.entrySet()) {
            TypeHandler<?> handler = entry.getKey();
            for (ParsedEntry parsedEntry : entry.getValue()) {
                candidates.add(new DuplicateIdResolver.Candidate(
                        handler.getTypeName(),
                        identityOf(handler, modId, parsedEntry.definition()),
                        parsedEntry.sourcePath(),
                        parsedEntry));
            }
        }

        DuplicateIdResolver.Result result = DuplicateIdResolver.resolve(candidates);
        for (DuplicateIdResolver.Conflict conflict : result.conflicts()) {
            String message = DuplicateIdResolver.describe(conflict);
            LOGGER.warn(message);
            addLoadingError(modId, DuplicateIdResolver.toLoadingError(conflict));
        }
        if (!result.conflicts().isEmpty()) {
            LOGGER.warn("Resolved {} duplicate id(s) for mod '{}' before applying registrations",
                    result.conflicts().size(), modId);
        }

        Map<TypeHandler<?>, List<Object>> surviving = new LinkedHashMap<>();
        for (DuplicateIdResolver.Candidate candidate : result.winners()) {
            ParsedEntry parsedEntry = (ParsedEntry) candidate.payload();
            surviving.computeIfAbsent(parsedEntry.handler(), k -> new ArrayList<>())
                    .add(parsedEntry.definition());
        }
        return surviving;
    }

    /**
     * Reads a handler's duplicate identity, converting any failure into {@code null} so a misbehaving
     * custom handler cannot abort the load.
     */
    @SuppressWarnings("unchecked")
    private static String identityOf(TypeHandler<?> handler, String modId, Object definition) {
        try {
            return ((TypeHandler<Object>) handler).resolveIdentity(modId, definition);
        } catch (Exception e) {
            LOGGER.warn("Failed to resolve identity for type '{}': {} (entry skipped by duplicate detection)",
                    handler.getTypeName(), e.getMessage());
            return null;
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void applyUnchecked(TypeHandler handler, Object def, BuildContext context) {
        try {
            handler.apply(def, context);
        } catch (Exception e) {
            LOGGER.error("Failed to apply {} handler: {}", handler.getTypeName(), e.getMessage());
        }
    }

    private static IModFile findModFile(String modId) {
        for (IModFileInfo modFile : ModList.get().getModFiles()) {
            for (IModInfo mod : modFile.getMods()) {
                if (mod.getModId().equals(modId)) {
                    return modFile.getFile();
                }
            }
        }
        return null;
    }

    /**
     * Path segments, relative to a mod file's root, of the data-driven index directory.
     * <p>
     * The canonical layout is {@code data/<mod_id>/kasuga_lib/data_driven/} — the same
     * {@code kasuga_lib} spelling used by the mod id and {@code data_driven} as used by
     * this package. Kept as a pure function so the path contract can be asserted by a
     * plain JVM test without a NeoForge runtime.
     *
     * @param modId the owning mod's id, used as the second segment
     * @return a fresh array of segments, e.g. {@code {"data", modId, "kasuga_lib", "data_driven"}}
     */
    public static String[] indexDirectorySegments(String modId) {
        return new String[]{"data", modId, "kasuga_lib", "data_driven"};
    }

    private static Path findIndexDir(String modId) {
        IModFile modFile = findModFile(modId);
        if (modFile == null) return null;
        Path dir = modFile.findResource(indexDirectorySegments(modId));
        if (Files.isDirectory(dir)) {
            return dir;
        }
        return null;
    }

    /**
     * Locates the pre-migration data-driven directory {@code data/<mod_id>/kasugalib/}, if present.
     * The directory's contents are never read; it only backs the migration warning raised by
     * {@link #buildForMod(String)} so an ignored old layout is no longer silent.
     *
     * @return the legacy directory, or {@code null} when the mod never shipped one (or has no mod file)
     */
    private static Path findLegacyDataDrivenDir(String modId) {
        IModFile modFile = findModFile(modId);
        if (modFile == null) return null;
        Path legacy = modFile.findResource("data", modId, "kasugalib");
        return Files.isDirectory(legacy) ? legacy : null;
    }

    // --- loading errors (one bucket per mod; see loadingErrorsByMod) ---

    /**
     * Snapshot of the loading errors recorded for {@code modId}. Returns an empty list when the mod
     * has none.
     */
    public static List<Throwable> getLoadingErrors(String modId) {
        List<Throwable> bucket = loadingErrorsByMod.get(modId);
        if (bucket == null) return List.of();
        synchronized (bucket) {
            return List.copyOf(bucket);
        }
    }

    /**
     * Snapshot of every mod's loading errors, keyed by mod id. Mods are ordered by id so the result
     * is deterministic.
     */
    public static Map<String, List<Throwable>> getLoadingErrorsByMod() {
        List<String> modIds = new ArrayList<>(loadingErrorsByMod.keySet());
        Collections.sort(modIds);
        Map<String, List<Throwable>> snapshot = new LinkedHashMap<>();
        for (String modId : modIds) {
            snapshot.put(modId, getLoadingErrors(modId));
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * Aggregate snapshot of the loading errors of every mod, mods ordered by id. Prefer
     * {@link #getLoadingErrors(String)} when the mod is known — this view mixes mods together.
     */
    public static List<Throwable> getLoadingErrors() {
        List<Throwable> all = new ArrayList<>();
        for (List<Throwable> bucket : getLoadingErrorsByMod().values()) {
            all.addAll(bucket);
        }
        return List.copyOf(all);
    }

    /**
     * Records a loading error against {@code modId}. Called by the parse pipeline for every
     * malformed input, and exposed so diagnostics and tests can attribute errors to a mod.
     */
    public static void addLoadingError(String modId, Throwable error) {
        List<Throwable> bucket = loadingErrorsByMod.computeIfAbsent(modId, k -> new ArrayList<>());
        synchronized (bucket) {
            bucket.add(error);
        }
    }

    /** Clears only {@code modId}'s recorded loading errors; other mods' buckets are untouched. */
    public static void clearLoadingErrors(String modId) {
        loadingErrorsByMod.remove(modId);
    }

    /** Clears the recorded loading errors of every mod. */
    public static void clearLoadingErrors() {
        loadingErrorsByMod.clear();
    }

    private JsonTreeBuilder() {}

    /**
     * Sort group definitions so parents are applied before children.
     * Uses {@link GraphCycleDetector#topologicalSort} — groups with cycles or missing
     * parents stay in their original relative order (the existing fallback in
     * {@link RegistryGroupHandler#store} attaches them to root).
     */
    private static List<RegistryGroupDef> topoSortGroupDefs(List<RegistryGroupDef> defs) {
        Set<String> ids = new LinkedHashSet<>();
        for (RegistryGroupDef def : defs) {
            ids.add(def.id());
        }

        Set<String> allIds = new HashSet<>(ids);
        GraphCycleDetector.GraphAdapter<String> adapter = id -> {
            RegistryGroupDef def = defs.stream()
                .filter(d -> d.id().equals(id)).findFirst().orElse(null);
            if (def == null || def.parent() == null) return List.of();
            return allIds.contains(def.parent()) ? List.of(def.parent()) : List.of();
        };

        GraphCycleDetector.TopoResult<String> result =
            GraphCycleDetector.topologicalSort(new HashSet<>(ids), adapter);

        if (result.hasCycle()) {
            LOGGER.warn("Cycle detected among registry groups: {}", result.getNodesNotSorted());
        }

        // Build sorted list: sorted nodes first, then unsorted (cycles) in original order
        Map<String, RegistryGroupDef> byId = new LinkedHashMap<>();
        for (RegistryGroupDef def : defs) {
            byId.put(def.id(), def);
        }

        List<RegistryGroupDef> sorted = new ArrayList<>();
        for (String id : result.getSorted()) {
            RegistryGroupDef def = byId.get(id);
            if (def != null) sorted.add(def);
        }
        for (RegistryGroupDef def : defs) {
            if (!result.getSorted().contains(def.id())) {
                sorted.add(def); // cycles or unreachable — fall back to original order
            }
        }
        return sorted;
    }
}
