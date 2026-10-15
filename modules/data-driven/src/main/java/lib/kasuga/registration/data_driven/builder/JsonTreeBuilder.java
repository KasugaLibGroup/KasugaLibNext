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
import lib.kasuga.registration.data_driven.reload.ReloadHandler;
import lib.kasuga.registration.data_driven.reload.ReloadHandlerRegistry;
import lib.kasuga.registration.data_driven.structure.ContentStructure;
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

    /** Index-manifest field naming the content files consumed during registration. */
    private static final String ON_REGISTER = "on_register";
    /** Index-manifest field naming the content files consumed during a resource reload. */
    private static final String ON_RELOAD = "on_reload";

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
                        + "'data/" + modId + "/kasuga_lib/data_driven/<name>.json' that lists them in an "
                        + "\"on_register\" array (paths relative to 'data/" + modId + "/').";
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

        // Index files are pure manifests: each lists content files per domain through its
        // "on_register" and "on_reload" arrays. Every manifest is read before anything is consumed
        // so a path listed in both domains can be rejected fail-closed (D6); each content file is
        // then parsed at most once per mod, even if referenced from several manifests.
        List<IndexManifest> manifests = new ArrayList<>();
        for (Path path : listIndexFiles(indexDir, modId)) {
            manifests.add(readIndexManifest(path, modId));
        }
        ResolvedIndex index = resolveIndexManifests(modId, manifests);

        if (index.nothingDeclared()) {
            return null;
        }
        if (!index.declaredOnRegister()) {
            LOGGER.info("Data-driven index for mod '{}' declares no 'on_register' entries; nothing to register",
                    modId);
        }

        // Registration consumes only 'on_register'; 'on_reload' is recognised but read by the reload
        // side, so it was already validated and cross-checked above without being parsed here.
        Map<TypeHandler<?>, List<ParsedEntry>> parsed = new LinkedHashMap<>();
        Set<String> parsedSources = new HashSet<>();
        for (String sourcePath : index.onRegisterPaths()) {
            parseSource(modId, sourcePath, parsedSources, parsed);
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
                    applyUnchecked(handler, def, context, modId);
                }
                continue;
            }

            for (Object def : defs) {
                applyUnchecked(handler, def, context, modId);
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
                    String msg = "Failed to load JSON registrations for mod '" + modId + "': " + e;
                    LOGGER.error(msg, e);
                    addLoadingError(modId, new IllegalStateException(msg, e));
                }
            }
        }

        return result;
    }

    /**
     * Reads one index manifest from disk and parses it with
     * {@link #parseIndexManifest(String, String, JsonObject)}. A file that cannot be read or is not
     * valid JSON is reported and treated as declaring nothing, so it does not abort the other
     * manifests of the same mod.
     */
    private static IndexManifest readIndexManifest(Path path, String modId) {
        String label = path.getFileName() != null ? path.getFileName().toString() : path.toString();
        try (Reader reader = Files.newBufferedReader(path)) {
            JsonObject root = GSON.fromJson(reader, JsonObject.class);
            return parseIndexManifest(modId, label, root);
        } catch (Exception e) {
            LOGGER.error("Error parsing JSON file: {}", path, e);
            addLoadingError(modId, new IOException("Error parsing JSON file: " + path, e));
            return IndexManifest.empty();
        }
    }

    /**
     * Parses one in-memory index manifest. A manifest is a pure declaration: it lists content files
     * per domain through the top-level arrays {@code on_register} and {@code on_reload}, each entry a
     * content-file path relative to {@code data/<mod>/} and including the {@code .json} suffix. The
     * manifest does not name any registration type.
     *
     * <p>Both fields are independently optional but at least one must be present: a manifest that
     * declares neither (including an empty file or one made only of unknown fields) is reported and
     * contributes no paths (D1). An empty array is valid and still counts as declared (D2). Malformed
     * input never aborts the file: an unknown top-level field and a field that is not an array are
     * each reported, and the sibling field is still read (D3). {@code sources} is not an alias — it
     * goes through the ordinary unknown-field path (D5).
     *
     * <p>This is the in-memory half of the index pipeline with no NeoForge dependency, exposed so the
     * schema rules can be asserted by a plain JVM test (in the same spirit as
     * {@link #indexDirectorySegments(String)}).
     *
     * @param modId the owning mod's id, used to bucket diagnostics
     * @param label human-readable name of the manifest, used in diagnostics
     * @param root  the manifest document, or {@code null} for an empty file
     * @return what the manifest declared, never {@code null}
     */
    public static IndexManifest parseIndexManifest(String modId, String label, JsonObject root) {
        JsonObject document = root == null ? new JsonObject() : root;

        for (String field : document.keySet()) {
            if (ON_REGISTER.equals(field) || ON_RELOAD.equals(field)) continue;
            String msg = "Index file '" + label + "' for mod '" + modId
                    + "' contains unsupported field '" + field
                    + "'; an index file may only contain 'on_register' and 'on_reload'";
            LOGGER.error(msg);
            addLoadingError(modId, new IllegalStateException(msg));
        }

        List<String> onRegisterPaths = readPathArray(modId, label, document, ON_REGISTER);
        List<String> onReloadPaths = readPathArray(modId, label, document, ON_RELOAD);

        boolean declaredOnRegister = document.has(ON_REGISTER) && document.get(ON_REGISTER).isJsonArray();
        boolean declaredOnReload = document.has(ON_RELOAD) && document.get(ON_RELOAD).isJsonArray();

        if (!declaredOnRegister && !declaredOnReload) {
            String msg = "Index file '" + label + "' for mod '" + modId
                    + "' declares neither 'on_register' nor 'on_reload'; an index file must declare "
                    + "at least one of them";
            LOGGER.error(msg);
            addLoadingError(modId, new IllegalStateException(msg));
        }

        return new IndexManifest(declaredOnRegister, declaredOnReload,
                List.copyOf(onRegisterPaths), List.copyOf(onReloadPaths));
    }

    /**
     * Reads one of the two path arrays of a manifest. A field that is present but not an array, or an
     * entry that is not a string, is reported without aborting the sibling field (D3).
     */
    private static List<String> readPathArray(String modId, String label, JsonObject document, String field) {
        if (!document.has(field)) return List.of();
        JsonElement element = document.get(field);
        if (!element.isJsonArray()) {
            String msg = "Index file '" + label + "' for mod '" + modId
                    + "': field '" + field + "' must be an array of content-file paths";
            LOGGER.error(msg);
            addLoadingError(modId, new IllegalStateException(msg));
            return List.of();
        }
        List<String> paths = new ArrayList<>();
        for (JsonElement entry : element.getAsJsonArray()) {
            if (!entry.isJsonPrimitive() || !entry.getAsJsonPrimitive().isString()) {
                String msg = "Index file '" + label + "' for mod '" + modId
                        + "': every '" + field + "' entry must be a string path";
                LOGGER.error(msg);
                addLoadingError(modId, new IllegalStateException(msg));
                continue;
            }
            paths.add(entry.getAsString());
        }
        return paths;
    }

    /**
     * Aggregates every index manifest read for one mod before anything is consumed (SDD D4/D6), with
     * no NeoForge dependency so the rules can be asserted by a plain JVM test.
     *
     * <p>A path listed in both domains — in the same manifest or across manifests — is rejected
     * fail-closed: one diagnostic per path is recorded and the path is dropped from both lists, so
     * neither domain consumes it. When no manifest declared either field, the aggregate
     * "declares neither" diagnostic is recorded and the caller must register nothing; a mod that only
     * declared {@code on_reload} is a valid pure reload mod and gets no aggregate error.
     *
     * @param modId     the owning mod's id, used to bucket diagnostics
     * @param manifests every manifest read for the mod, in load order
     * @return the paths each domain may consume, plus what the mod declared
     */
    public static ResolvedIndex resolveIndexManifests(String modId, List<IndexManifest> manifests) {
        boolean declaredOnRegister = false;
        boolean declaredOnReload = false;
        List<String> onRegisterPaths = new ArrayList<>();
        List<String> onReloadPaths = new ArrayList<>();
        for (IndexManifest manifest : manifests) {
            declaredOnRegister |= manifest.declaredOnRegister();
            declaredOnReload |= manifest.declaredOnReload();
            onRegisterPaths.addAll(manifest.onRegisterPaths());
            onReloadPaths.addAll(manifest.onReloadPaths());
        }

        Set<String> reloadPaths = new HashSet<>(onReloadPaths);
        Set<String> crossListed = new LinkedHashSet<>();
        for (String path : onRegisterPaths) {
            if (reloadPaths.contains(path)) crossListed.add(path);
        }
        if (!crossListed.isEmpty()) {
            for (String path : crossListed) {
                String msg = "Index path '" + path + "' for mod '" + modId
                        + "' is listed in both 'on_register' and 'on_reload'; a content file belongs to "
                        + "exactly one domain and is therefore consumed by neither";
                LOGGER.error(msg);
                addLoadingError(modId, new IllegalStateException(msg));
            }
            onRegisterPaths.removeIf(crossListed::contains);
            onReloadPaths.removeIf(crossListed::contains);
        }

        if (!declaredOnRegister && !declaredOnReload) {
            String msg = "Data-driven index for mod '" + modId + "' declares neither 'on_register' nor "
                    + "'on_reload' in any index file; no entries can be loaded";
            LOGGER.warn(msg);
            addLoadingError(modId, new IllegalStateException(msg));
        }

        return new ResolvedIndex(declaredOnRegister, declaredOnReload,
                List.copyOf(onRegisterPaths), List.copyOf(onReloadPaths));
    }

    /**
     * What one index manifest declared, in the order the file wrote it. Produced by
     * {@link #parseIndexManifest(String, String, JsonObject)} without consuming anything.
     *
     * @param declaredOnRegister whether {@code on_register} was present as an array (an empty array
     *                           still counts — D2)
     * @param declaredOnReload   whether {@code on_reload} was present as an array
     * @param onRegisterPaths    the {@code on_register} entries, in file order
     * @param onReloadPaths      the {@code on_reload} entries, in file order
     */
    public record IndexManifest(boolean declaredOnRegister, boolean declaredOnReload,
                                List<String> onRegisterPaths, List<String> onReloadPaths) {
        /** A manifest that declared nothing; used when an index file cannot be read at all. */
        static IndexManifest empty() {
            return new IndexManifest(false, false, List.of(), List.of());
        }
    }

    /**
     * The aggregate of every index manifest of one mod, after cross-domain conflicts were removed
     * (D6). {@link #nothingDeclared()} is the D4 predicate: when true no index file declared either
     * domain, no entries can be loaded, and the caller must return without registering anything.
     *
     * @param declaredOnRegister whether any manifest declared {@code on_register}
     * @param declaredOnReload   whether any manifest declared {@code on_reload}
     * @param onRegisterPaths    the {@code on_register} entries to consume, conflicts removed
     * @param onReloadPaths      the {@code on_reload} entries, conflicts removed
     */
    public record ResolvedIndex(boolean declaredOnRegister, boolean declaredOnReload,
                                List<String> onRegisterPaths, List<String> onReloadPaths) {
        /** True when no index file declared either domain (D4). */
        public boolean nothingDeclared() {
            return !declaredOnRegister && !declaredOnReload;
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
                dispatchContent(content, modId, contentPath.toString(), sourcePath, parsed);
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
     * Embedded types only ever arrive through {@link TypeHandler#extractEmbedded(JsonObject)}, so a
     * top-level field named after one (e.g. {@code block_entities}) is not a dispatch slot: it takes
     * the same "unsupported top-level field" path as a typo, is reported, and — because an embedded
     * type has no meaning without its parent — the rest of the file still dispatches normally instead
     * of the whole file aborting. The message then names the key the author meant to write.
     * <p>
     * Malformed content is reported instead of skipped silently: an unknown top-level field, a
     * type field that is not an array, and a non-object array entry each produce an error log and a
     * per-mod loading error, matching the strictness already applied to index files.
     *
     * @param contentLabel the content file being dispatched, used in diagnostics
     * @param sourcePath   the content file path relative to {@code data/<mod>/}, stored as the
     *                     entry's provenance for duplicate diagnostics
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void dispatchContent(JsonObject root, String modId, String contentLabel,
                                        String sourcePath, Map parsed) {
        Set<String> knownFields = new LinkedHashSet<>();
        Map<String, TypeHandler<?>> embeddedByField = new LinkedHashMap<>();
        for (TypeHandler<?> handler : TypeHandlerRegistry.all()) {
            if (handler.getParentTypeName() == null) {
                knownFields.add(handler.getTypeName());
            } else {
                embeddedByField.put(handler.getTypeName(), handler);
            }
        }
        for (ContentStructure.Issue issue : ContentStructure.unknownKeys(root, knownFields)) {
            String field = issue.key();
            String msg = "Content file '" + contentLabel + "' for mod '" + modId
                    + "' contains unsupported top-level field '" + field
                    + "'; supported fields are " + knownFields;
            TypeHandler<?> embedded = embeddedByField.get(field);
            if (embedded != null) {
                msg += ". '" + field + "' is an embedded type: write it inside a '"
                        + embedded.getParentTypeName() + "' entry's '" + embedded.getEmbeddedKeyName()
                        + "' key instead of as a top-level field";
            }
            // Conditional hint (D9): the same "unsupported field" message is what an author sees when
            // they list a reload-domain content file (e.g. state machine definitions) in on_register.
            // The registration domain does not recognise reload-domain shapes, so instead of widening
            // the known set the message points at the index's other array. The reload-domain key names
            // are enumerated from the reload handler registry rather than spelled out literally, so the
            // hint stays correct as reload types are added; data-driven still owns the registry, so no
            // modelling dependency is introduced. With no handlers registered (pure-JVM unit tests) the
            // example is omitted rather than invented.
            msg += reloadDomainHint();
            LOGGER.error(msg);
            addLoadingError(modId, new IllegalStateException(msg));
        }

        for (TypeHandler<?> handler : TypeHandlerRegistry.all()) {
            if (handler.getParentTypeName() != null) continue; // embedded types are not top-level fields
            if (!root.has(handler.getTypeName())) continue;
            ContentStructure.Field field = ContentStructure.field(root, handler.getTypeName());
            for (ContentStructure.Issue issue : field.issues()) {
                String msg;
                if (issue.kind() == ContentStructure.Kind.VALUE_NOT_ARRAY) {
                    msg = "Content file '" + contentLabel + "' for mod '" + modId
                            + "': field '" + handler.getTypeName() + "' must be an array";
                } else {
                    msg = "Content file '" + contentLabel + "' for mod '" + modId
                            + "': every entry in '" + handler.getTypeName() + "' must be a JSON object";
                }
                LOGGER.error(msg);
                addLoadingError(modId, new IllegalStateException(msg));
            }
            // The structure layer already paired every valid element with its true file index.
            for (ContentStructure.Element element : field.elements()) {
                parseAndCollect(parsed, handler, element.body(), sourcePath, modId, contentLabel,
                        element.fileIndex());
            }
        }

        // Embedded types live inside their parent's object, so they are read from the parent field.
        for (TypeHandler<?> handler : TypeHandlerRegistry.all()) {
            if (handler.getParentTypeName() == null) continue;
            TypeHandler<?> parent = TypeHandlerRegistry.get(handler.getParentTypeName());
            if (parent == null || !root.has(parent.getTypeName())) continue;
            ContentStructure.Field parentField = ContentStructure.field(root, parent.getTypeName());
            for (ContentStructure.Element parentElement : parentField.elements()) {
                List<JsonObject> embedded;
                try {
                    embedded = handler.extractEmbedded(parentElement.body());
                } catch (RuntimeException e) {
                    String msg = "Content file '" + contentLabel + "' for mod '" + modId
                            + "': entry " + parentElement.fileIndex() + " in '" + parent.getTypeName()
                            + "' failed to parse: " + e.getMessage() + "; entry skipped";
                    LOGGER.error(msg, e);
                    addLoadingError(modId, new IllegalStateException(msg, e));
                    continue;
                }
                if (embedded == null) {
                    continue;
                }
                for (JsonObject emb : embedded) {
                    parseAndCollect(parsed, handler, emb, sourcePath, modId, contentLabel,
                            parentElement.fileIndex());
                }
            }
        }
    }

    /**
     * D9's registration-side hint, appended when an {@code on_register} file carries a top-level field
     * the registration domain does not know. The reload-domain key names are enumerated at runtime from
     * {@link ReloadHandlerRegistry}, so the example stays correct as reload types are added without
     * data-driven depending on the modelling module that owns them. With no handler registered — a
     * pure-JVM unit test, or a host that never loaded the reload types — the example is omitted rather
     * than invented, and the hint still points the author at {@code on_reload} unconditionally.
     */
    private static String reloadDomainHint() {
        List<String> typeNames = new ArrayList<>();
        for (ReloadHandler<?> handler : ReloadHandlerRegistry.all()) {
            typeNames.add("'" + handler.typeName() + "'");
        }
        if (typeNames.isEmpty()) {
            return ". If these fields belong to reload-domain content, list this file under "
                    + "'on_reload' instead";
        }
        return ". If these fields belong to reload-domain content (for example written as a "
                + ContentStructure.joinWithOr(typeNames) + " array), list this file under 'on_reload' instead";
    }

    /**
     * Parses one element and collects it. A parse failure is isolated to the element: it is reported as
     * a paired log + loading error naming the element's position and skipped, so one malformed
     * definition can neither abort the file nor its siblings (B1). This is what makes
     * {@link #parseContentBody}'s "reported …, never thrown" contract true.
     *
     * @param parsed       the collected entries, keyed by handler
     * @param handler      the handler whose {@code parse} runs
     * @param json         the element to parse
     * @param sourcePath   the content file path, kept as provenance
     * @param modId        the owning mod's id, used to bucket the diagnostic
     * @param contentLabel human-readable name of the document, used in the diagnostic
     * @param index        the element's position in its top-level array, used in the diagnostic
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void parseAndCollect(Map parsed, TypeHandler handler, JsonObject json, String sourcePath,
                                        String modId, String contentLabel, int index) {
        Object def;
        try {
            def = handler.parse(json);
        } catch (RuntimeException e) {
            String msg = "Content file '" + contentLabel + "' for mod '" + modId
                    + "': entry " + index + " in '" + handler.getTypeName()
                    + "' failed to parse: " + e.getMessage() + "; entry skipped";
            LOGGER.error(msg, e);
            addLoadingError(modId, new IllegalStateException(msg, e));
            return;
        }
        List list = (List) parsed.computeIfAbsent(handler, k -> new ArrayList());
        list.add(new ParsedEntry(handler, def, sourcePath));
    }

    /**
     * Dispatches one content document that is already in memory and returns the definitions that were
     * collected, grouped by handler type name in dispatch order. This is the half of the content
     * pipeline that has no NeoForge dependency — {@link #parseSource} resolves and reads the file
     * first, then hands the body here — which makes the dispatch rules (unknown top-level fields,
     * embedded types, half-apply behaviour) assertable by a plain JVM test.
     *
     * <p>Malformed input is reported through the mod's loading-error bucket, never thrown: callers
     * get back whatever was still parseable. Nothing is registered — this only parses and collects.
     *
     * @param modId        the owning mod's id, used to bucket diagnostics
     * @param contentLabel human-readable name of the document, used in diagnostics
     * @param root         the content document
     * @return the collected definitions per handler type name; empty when nothing parsed
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static Map<String, List<Object>> parseContentBody(String modId, String contentLabel, JsonObject root) {
        ensureHandlersRegistered();
        Map<TypeHandler<?>, List<ParsedEntry>> parsed = new LinkedHashMap<>();
        dispatchContent(root, modId, contentLabel, contentLabel, parsed);

        Map<String, List<Object>> byType = new LinkedHashMap<>();
        for (Map.Entry<TypeHandler<?>, List<ParsedEntry>> entry : parsed.entrySet()) {
            List<Object> definitions = new ArrayList<>(entry.getValue().size());
            for (ParsedEntry parsedEntry : entry.getValue()) {
                definitions.add(parsedEntry.definition());
            }
            byType.put(entry.getKey().getTypeName(), List.copyOf(definitions));
        }
        return Collections.unmodifiableMap(byType);
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
     * manifest's {@code on_register} array, then each type field's array); last-wins therefore means "the
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

    /**
     * Applies one handler and turns any failure into a recorded diagnostic. Registration failures
     * must leave a trace: a handler that throws has done none of its side effects, so swallowing the
     * exception would make a failed registration look like a successful one.
     *
     * <p>Called by {@link #buildForMod(String)} for every surviving definition; exposed because the
     * "failed apply is recorded" contract is otherwise only reachable through a full mod-jar load.
     *
     * @param modId the owning mod's id, used to bucket the diagnostic
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void applyUnchecked(TypeHandler handler, Object definition, BuildContext context, String modId) {
        try {
            handler.apply(definition, context);
        } catch (Exception e) {
            String msg = "Failed to apply '" + handler.getTypeName() + "' handler for mod '" + modId
                    + "': " + e;
            LOGGER.error(msg, e);
            addLoadingError(modId, new IllegalStateException(msg, e));
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

    /**
     * Lists the JSON index manifests of {@code indexDir}, sorted, recording a diagnostic instead of
     * throwing when the directory cannot be listed. An empty result is a valid outcome — it means
     * "no manifest here", and {@link #buildForMod(String)} reports that separately when the directory
     * exists but yielded no entries.
     *
     * <p>Exposed like {@link #indexDirectorySegments(String)} so the failure branch is assertable by a
     * plain JVM test.
     *
     * @param indexDir the index directory to scan
     * @param modId    the owning mod's id, used to bucket the diagnostic
     * @return the manifest paths, or an empty list when the directory cannot be listed
     */
    public static List<Path> listIndexFiles(Path indexDir, String modId) {
        try (Stream<Path> files = Files.list(indexDir)) {
            return files.filter(p -> p.toString().endsWith(".json"))
                        .sorted()
                        .toList();
        } catch (IOException e) {
            String msg = "Error scanning data-driven index directory for mod '" + modId + "': "
                    + indexDir + " (" + e + ")";
            LOGGER.error(msg, e);
            addLoadingError(modId, new IOException(msg, e));
            return List.of();
        }
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
