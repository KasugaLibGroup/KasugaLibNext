package lib.kasuga.registration.data_driven.reload;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.resource.ResourceSystem;
import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.ScopedResourceManagerConsumer;
import lib.kasuga.core.resource.ScopedResourcePackListener;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.TypeHandlerRegistry;
import lib.kasuga.registration.data_driven.dedup.DuplicateIdResolver;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.registration.data_driven.structure.ContentStructure;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * The single reload orchestrator of the RELOAD-DATA domain. On every resource reload it owns the
 * whole cycle: it clears the reload-sourced buckets <em>once</em>, discovers reload content through
 * both entries — the glob directories declared by the registered {@link ReloadHandler}s and the
 * {@code on_reload} arrays of the {@code data/<ns>/kasuga_lib/data_driven/} index manifests — decodes
 * each file, resolves duplicate ids across both entries, registers the winners, and finally lets each
 * handler run its after-phase (e.g. the post-load clip reference check).
 *
 * <p><strong>Content types are extensions, not hard-coded.</strong> The reload domain is symmetric to
 * registration: where a registration type is a {@link TypeHandler} in
 * {@link TypeHandlerRegistry}, a reload type is a {@link ReloadHandler} in
 * {@link ReloadHandlerRegistry}. The orchestrator routes files, reports unknown keys, discovers glob
 * directories and clears buckets entirely through the registry, so adding a reload type is a
 * registration and no longer an edit to this class. The registry's order drives both dispatch and
 * registration order, and therefore last-wins.
 *
 * <p><strong>Handler binding.</strong> The orchestrator is type-agnostic: it knows no reload type by
 * name. The standard modelling handlers ({@code FsmReloadHandler}, {@code FsmClipsReloadHandler}) are
 * installed by the modelling-side registrar at context startup, bound to the process-wide buckets, so
 * the registry is populated before any orchestrator exists. The varargs constructor additionally
 * registers the handlers it is handed, so a host that owns its own buckets (a test, or an embedding
 * application) can bind them without the modelling registrar. Registration is idempotent by type name.
 *
 * <p>Owning the cycle in one place is deliberate: a handler's {@code clearBucket()} may be called
 * exactly once per reload. A second listener clearing independently would erase whatever the first one
 * had just written, so the file decoders no longer carry a listener or a clear of their own — they are
 * only the file decoders and the glob listers.
 *
 * <p><strong>Content types.</strong> A reload content file is routed by its top-level key, and the
 * resource type follows the shape — the domain is decided by the index array, never by the content
 * file. Each registered handler's {@link ReloadHandler#typeName()} is a known key; the two modelling
 * handlers are {@code state_machines} and {@code animation_clips}. Each present key is decoded
 * independently by its own handler, so a file declaring several known keys contributes all of them
 * with no diagnostic — see {@link #dispatch} for the exact routing, mixed-file and unknown-key rules.
 *
 * <p><strong>Two entries</strong> are supported. The directory glob covers every directory any handler
 * declares through {@link ReloadHandler#globDirectories()} (today only {@code state_machines/});
 * animation clips are index-only — {@code animation_clips/} is not a glob directory, so a clip file
 * that no manifest lists is never read.
 *
 * <p><strong>Application order</strong> (glob first, index second; a later entry with the same id
 * wins, "last-wins"):
 * <ol>
 *   <li>every file discovered by the aggregated directory glob, sorted by resource location;</li>
 *   <li>every file listed in an {@code on_reload} array — index manifests sorted by path, then the
 *       array order the manifest wrote, then the arrays inside a file in the order the decoders
 *       preserve.</li>
 * </ol>
 * A file that the index lists is skipped by the glob entry, so it is read (and its definitions
 * registered) exactly once instead of colliding with itself. Identity carries the namespace, so files
 * of different namespaces can never collide and the relative order of the two entries only matters
 * inside one namespace — where the glob still precedes the index.
 *
 * <p>A losing duplicate never reaches a handler's {@link ReloadHandler#register(Object)} — the
 * registration domain's "the loser never has a side effect" rule (its duplicate gate runs before
 * {@code apply}) is reproduced here with the same pure {@link DuplicateIdResolver}. Diagnostics are the
 * pair "log + {@link Diagnostics} bucket", written to the {@link Diagnostics.Domain#RELOAD_DATA} source
 * dimension and addressed by the namespace the offending file lives in, so a reload-triggered error
 * never lands on another mod's bucket. The domain is cleared at the start of every cycle, so each
 * reload's diagnostics replace the previous one's rather than accumulating.
 *
 * <p><strong>Post-load checks.</strong> After every handler has finished its register phase, each
 * handler's {@link ReloadHandler#afterReload(List)} runs over exactly the entries this cycle registered
 * (each carrying its namespace). For state machines that is the dangling {@code states[].clip} check:
 * it must run after the clips are loaded, or every reference would look dangling. It never blocks a
 * registration or changes the build-time degradation.
 *
 * <p><strong>No failure escapes a cycle.</strong> This method sits on the reload listener path, so a
 * throw would abort the pack reload; and because the cycle clears the buckets before it repopulates
 * them, a throw would leave the half-cleared (empty) buckets behind. Every stage therefore guards its
 * own participants and turns a failure into the paired "log + {@link Diagnostics} bucket" diagnostic:
 * per handler (clear), per file (read / decode), per namespace (discovery), per entry (registration)
 * and per handler's after-phase. Only a failure that no participant could be charged for reaches the
 * last-resort guard in
 * {@link #reload(ResourceManager)}. A cycle whose every entry failed leaves the buckets empty on
 * purpose: the empty state is the truthful outcome and the bucket holds the errors, whereas restoring
 * the previous entries would hide that this cycle loaded nothing.
 */
@Context
public final class ReloadOrchestrator implements ScopedResourceManagerConsumer, ScopedResourcePackListener {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject
    ResourceSystem resourceSystem;

    /**
     * The DI entry: discovers nothing itself, it routes through whatever handlers the registry holds.
     * The standard modelling handlers are installed at context startup by the modelling-side registrar,
     * so by the time this bean runs the registry is populated.
     */
    public ReloadOrchestrator() {
    }

    /**
     * Host-injectable entry: registers the handlers it is handed before running any cycle. A host that
     * owns dedicated buckets installs its own handlers (bound to those buckets) and passes them here,
     * so it exercises the real registry path without touching the process-wide table. Registration is
     * idempotent by type name and preserves insertion order, so the caller controls last-wins by the
     * order of the arguments.
     *
     * @param handlers the reload handlers to install; {@code null} entries are ignored
     */
    public ReloadOrchestrator(ReloadHandler<?>... handlers) {
        if (handlers == null) {
            return;
        }
        for (ReloadHandler<?> handler : handlers) {
            ReloadHandlerRegistry.register(handler);
        }
    }

    @PostConstruct
    public void init() {
        resourceSystem.registerConsumer(this);
    }

    @Override
    public void onResourceManagerAdded(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        resourceManager.addListener(this);
    }

    @Override
    public void onResourceManagerRemoved(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        // Machine instances are host-owned (never cleared here); the next reload re-populates definitions.
    }

    @Override
    public void onReloaded(ScopedResourceManager resourceManager) {
        reload(resourceManager.getResourceManager());
    }

    /**
     * Runs one complete reload cycle against a resource manager.
     *
     * <p>The whole cycle is bounded by a last-resort guard: this method is a reload listener, and the
     * buckets were already cleared by the time a stage runs, so a failure must never escape it. Every
     * stage it calls guards its own participants (see the class documentation); whatever still reaches
     * this guard has no participant to be charged for, so it is attributed to the library mod that
     * owns the orchestrator — the same fallback attribution the rest of the module uses for a
     * diagnostic it cannot tie to a source file.
     *
     * @param resourceManager the pack stack to read from; must not be {@code null}
     */
    public void reload(ResourceManager resourceManager) {
        try {
            runCycle(resourceManager);
        } catch (RuntimeException e) {
            reportError(KasugaLib.MODID, "Reload cycle failed before it completed", e);
        }
    }

    /**
     * One clear / discover / register / after-check cycle. Kept apart from {@link #reload(ResourceManager)}
     * so the public entry stays a single guard around it.
     */
    private void runCycle(ResourceManager resourceManager) {
        List<HandlerCycle> cycle = new ArrayList<>();
        for (ReloadHandler<?> handler : ReloadHandlerRegistry.all()) {
            cycle.add(new HandlerCycle(handler));
        }

        // The reload domain's diagnostics are re-derived from scratch every cycle: without this the
        // N-th reload would report the same bad file N times and the summary's "N error(s)" would lose
        // its meaning. The clear is scoped to this one domain, so registration diagnostics in the mod
        // buckets are untouched.
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);

        // Exactly one clear per cycle, per handler (the orchestrator owns it): both entries then
        // populate the same buckets, so neither can erase the other's writes. A handler's clear drops
        // only reload-sourced entries -- entries registered by scripts / code keep theirs ("script wins").
        for (HandlerCycle entry : cycle) {
            runClear(entry);
        }

        List<String> namespaces = new ArrayList<>(resourceManager.getNamespaces());
        Collections.sort(namespaces);

        // The aggregated glob directories, in registry order and de-duplicated: a directory two handlers
        // share is listed once, and files are routed to a handler by their top-level key.
        Set<String> globDirectories = new LinkedHashSet<>();
        for (HandlerCycle entry : cycle) {
            globDirectories.addAll(entry.handler.globDirectories());
        }

        for (String namespace : namespaces) {
            collectNamespace(resourceManager, namespace, globDirectories, cycle);
        }

        // The register phase: every handler resolves and registers its own candidates, in registry order
        // (definitions first, clips second), after the whole cycle's discovery is complete.
        for (HandlerCycle entry : cycle) {
            register(entry);
        }

        // The after-phase: only now, once every handler has registered, may a cross-type check run --
        // the state machine clip check must see the clips registered above, not judge them too early.
        for (HandlerCycle entry : cycle) {
            runAfterReload(entry);
        }
    }

    /**
     * Runs one handler's clear phase. A clear that throws is isolated to that handler — the same
     * per-participant guard the other phases carry — so the remaining handlers are still cleared and
     * then populated, and the failure becomes a paired diagnostic instead of reaching the whole-cycle
     * guard and leaving the rest of the cycle unrun.
     */
    private void runClear(HandlerCycle entry) {
        ReloadHandler<?> handler = entry.handler;
        try {
            handler.clearBucket();
        } catch (RuntimeException e) {
            reportError(KasugaLib.MODID, "Clear phase of reload handler '"
                    + handler.typeName() + "' failed", e);
        }
    }

    /**
     * Runs the two discoveries of one namespace. The per-file guard inside {@code readAndDispatch}
     * already isolates a single content file, so what this guard catches is the discovery machinery
     * itself — a directory glob or a manifest reader that throws. It is charged to the namespace it
     * happened in, and the next namespace still runs.
     */
    private void collectNamespace(ResourceManager resourceManager, String namespace, Set<String> globDirectories,
                                  List<HandlerCycle> cycle) {
        try {
            // The index is read first: its on_reload arrays tell the glob entry which files it must
            // not read a second time.
            List<String> reloadPaths = readReloadPaths(resourceManager, namespace);
            collectGlob(resourceManager, namespace, new LinkedHashSet<>(reloadPaths), globDirectories, cycle);
            collectIndex(resourceManager, namespace, reloadPaths, cycle);
        } catch (RuntimeException e) {
            reportError(namespace, "Failed to discover reload content for mod '" + namespace + "'", e);
        }
    }

    /**
     * The index directory as a {@link ResourceManager} path, derived from
     * {@link JsonTreeBuilder#indexDirectorySegments(String)} so the jar reader (registration) and the
     * pack-stack reader (reload) share one spelling. A pack-stack path is relative to
     * {@code data/<ns>/} — the pack already provides that prefix — while the canonical segments start
     * with {@code {"data", <mod>, ...}}, so the leading pair is dropped:
     * {@code {"data", "kasuga_lib", "kasuga_lib", "data_driven"}} → {@code "kasuga_lib/data_driven"}.
     *
     * @param modId the owning mod's id, passed through to {@link JsonTreeBuilder#indexDirectorySegments}
     * @return the pack-stack path of the index directory
     */
    public static String indexResourcePath(String modId) {
        String[] segments = JsonTreeBuilder.indexDirectorySegments(modId);
        if (segments.length < 3) {
            throw new IllegalStateException(
                    "Unexpected data-driven index layout: " + Arrays.toString(segments));
        }
        return String.join("/", Arrays.copyOfRange(segments, 2, segments.length));
    }

    /**
     * Reads the {@code on_reload} arrays of one namespace's index manifests, reusing the registration
     * domain's reader: the manifests are parsed with {@link JsonTreeBuilder#parseIndexManifest} and
     * aggregated with {@link JsonTreeBuilder#resolveIndexManifests}, so D6's fail-closed cross-listing
     * (a path in both arrays is consumed by neither domain) applies identically on this side.
     *
     * @return the {@code on_reload} content paths, or an empty list when the namespace ships no index
     */
    private List<String> readReloadPaths(ResourceManager resourceManager, String namespace) {
        Map<ResourceLocation, Resource> found = resourceManager.listResources(indexResourcePath(namespace),
                loc -> loc.getNamespace().equals(namespace) && loc.getPath().endsWith(".json"));
        if (found.isEmpty()) {
            return List.of();
        }

        List<JsonTreeBuilder.IndexManifest> manifests = new ArrayList<>();
        for (Map.Entry<ResourceLocation, Resource> entry : new TreeMap<>(found).entrySet()) {
            ResourceLocation loc = entry.getKey();
            JsonObject root;
            try (BufferedReader reader = entry.getValue().openAsReader()) {
                JsonElement json = JsonParser.parseReader(reader);
                if (json == null || json.isJsonNull()) {
                    root = null; // empty file: reported as "declares neither" by the manifest parser
                } else if (json.isJsonObject()) {
                    root = json.getAsJsonObject();
                } else {
                    reportError(namespace, "Index file '" + loc + "' must be a JSON object, got "
                            + ContentStructure.describe(json));
                    continue;
                }
            } catch (IOException | JsonParseException e) {
                reportError(namespace, "Failed to read data-driven index " + loc, e);
                continue;
            }
            manifests.add(JsonTreeBuilder.parseIndexManifest(namespace, loc.getPath(), root));
        }
        if (manifests.isEmpty()) {
            return List.of();
        }
        return JsonTreeBuilder.resolveIndexManifests(namespace, manifests).onReloadPaths();
    }

    /**
     * Discovers one namespace's glob content files — every {@code *.json} under a directory any handler
     * declared through {@link ReloadHandler#globDirectories()} — skipping any file the namespace's
     * {@code on_reload} arrays already list (it is read once, through the index). A file is routed by its
     * top-level key, so a {@code state_machines/} file that declares {@code animation_clips} still routes
     * to that handler (and is reported by its decoder, which only knows that shape's own key rule).
     */
    private void collectGlob(ResourceManager resourceManager, String namespace, Set<String> indexedPaths,
                             Set<String> globDirectories, List<HandlerCycle> cycle) {
        for (String directory : globDirectories) {
            Map<ResourceLocation, Resource> found = new TreeMap<>(resourceManager.listResources(directory,
                    loc -> loc.getNamespace().equals(namespace) && loc.getPath().endsWith(".json")));
            for (Map.Entry<ResourceLocation, Resource> entry : found.entrySet()) {
                ResourceLocation loc = entry.getKey();
                if (indexedPaths.contains(loc.getPath())) {
                    continue;
                }
                readAndDispatch(namespace, "data/" + namespace + "/" + loc.getPath(), loc.getPath(),
                        entry.getValue(), cycle, true);
            }
        }
    }

    /**
     * Reads the content files one namespace's {@code on_reload} arrays point at. Paths are resolved
     * through the pack stack ({@link ResourceManager#getResource}), not the jar, so a data pack can
     * override reload-domain content.
     */
    private void collectIndex(ResourceManager resourceManager, String namespace, List<String> reloadPaths,
                              List<HandlerCycle> cycle) {
        for (String path : reloadPaths) {
            String invalid = JsonTreeBuilder.validateSourcePath(path);
            if (invalid != null) {
                reportError(namespace, "Invalid 'on_reload' path '" + path + "' for mod '" + namespace + "': "
                        + invalid);
                continue;
            }
            ResourceLocation loc = ResourceLocation.tryBuild(namespace, path);
            if (loc == null) {
                // The shared contract checks relativity, the '.json' suffix and the segments, but not
                // the character set of a resource location. Building the location directly would
                // therefore throw on it (an uppercase path is still "valid" to the contract), so the
                // location is built through the non-throwing factory and a refusal becomes one more
                // isolated entry.
                reportError(namespace, "Invalid 'on_reload' path '" + path + "' for mod '" + namespace
                        + "': not a valid resource location (a path allows only [a-z0-9/._-] and a "
                        + "namespace only [a-z0-9_.-])");
                continue;
            }
            Optional<Resource> resource = resourceManager.getResource(loc);
            if (resource.isEmpty()) {
                reportError(namespace, "Source file not found for mod '" + namespace + "': data/"
                        + namespace + "/" + path);
                continue;
            }
            readAndDispatch(namespace, "data/" + namespace + "/" + path, path, resource.get(), cycle, false);
        }
    }

    /**
     * Reads and dispatches one content file. Read failures and decode failures both become a paired
     * diagnostic, never an exception; the decode guard also covers a decoder that throws instead of
     * returning its diagnostics, so one bad file can neither abort the batch nor the reload.
     */
    private void readAndDispatch(String namespace, String label, String sourcePath, Resource resource,
                                 List<HandlerCycle> cycle, boolean viaGlob) {
        try (BufferedReader reader = resource.openAsReader()) {
            dispatch(namespace, label, sourcePath, JsonParser.parseReader(reader), cycle, viaGlob);
        } catch (IOException | JsonParseException e) {
            reportError(namespace, "Failed to read content file '" + label + "'", e);
        } catch (RuntimeException e) {
            reportError(namespace, "Failed to process content file '" + label + "'", e);
        }
    }

    /**
     * Routes one content document by its top-level keys, through the registered handlers. An unknown
     * top-level key is reported with the hint that points an author whose file is actually registration
     * content back at {@code on_register}, mirroring the registration side's D9 hint toward
     * {@code on_reload}.
     *
     * <p>Each present key is decoded independently by its handler, so a file mixing two known types
     * contributes both, with no diagnostic; a file carrying any other top-level key is reported once
     * per unknown key, and its known keys are still decoded and contributed (a half-applied file,
     * mirroring the registration side's D3 handling). A file discovered through the directory glob
     * appends to the handler's glob list; an index-listed file appends to its index list (the unified
     * order, glob before index, is applied per handler at registration time).
     */
    private void dispatch(String namespace, String label, String sourcePath, @Nullable JsonElement json,
                          List<HandlerCycle> cycle, boolean viaGlob) {
        if (ContentStructure.body(json) != null) {
            reportError(namespace, "Content file '" + label + "' must be a JSON object, got "
                    + ContentStructure.describe(json));
            return;
        }
        JsonObject root = json.getAsJsonObject();

        Set<String> knownFields = knownTypeNames();
        for (ContentStructure.Issue issue : ContentStructure.unknownKeys(root, knownFields)) {
            String field = issue.key();
            reportError(namespace, "Content file '" + label + "' contains unsupported top-level field '"
                    + field + "'; reload-domain files may only contain " + knownFields
                    + ". If these fields are registration content"
                    + registrationFieldsExample()
                    + ", list the file under 'on_register' instead");
        }

        for (HandlerCycle entry : cycle) {
            String typeName = entry.handler.typeName();
            if (!root.has(typeName)) {
                continue;
            }
            Decoded<?> decoded;
            try {
                decoded = entry.handler.decode(root);
            } catch (RuntimeException e) {
                reportError(namespace, "Failed to decode " + entry.handler.describeFile() + " '" + label
                        + "': " + e.getMessage(), e);
                continue;
            }
            for (String error : decoded.errors()) {
                reportError(namespace, "Failed to decode " + entry.handler.describeFile() + " '" + label
                        + "': " + error);
            }
            for (Decoded.Entry<?> decodedEntry : decoded.entries()) {
                entry.add(new DuplicateIdResolver.Candidate(typeName, decodedEntry.id(), label,
                        new Reloaded<>(namespace, decodedEntry.payload())), viaGlob);
            }
        }
    }

    /** The top-level keys currently served by a registered handler, in registry order. */
    private static Set<String> knownTypeNames() {
        Set<String> names = new LinkedHashSet<>();
        for (ReloadHandler<?> handler : ReloadHandlerRegistry.all()) {
            names.add(handler.typeName());
        }
        return names;
    }

    /**
     * D9's reload-side example of registration fields, derived from {@link TypeHandlerRegistry} rather
     * than spelled out literally: the top-level registration types (those with no parent) are what an
     * author who listed a registration file under {@code on_reload} should move back. When the
     * registration registry is empty — a pure-JVM context with no handlers loaded — the example is
     * omitted rather than invented.
     */
    private static String registrationFieldsExample() {
        List<String> topLevel = new ArrayList<>();
        for (TypeHandler<?> handler : TypeHandlerRegistry.all()) {
            if (handler.getParentTypeName() == null) {
                topLevel.add("'" + handler.getTypeName() + "'");
            }
        }
        if (topLevel.isEmpty()) {
            return "";
        }
        return " (e.g. a " + ContentStructure.joinWithOr(topLevel) + " array)";
    }

    /**
     * Resolves duplicate ids among one handler's candidates and registers the winners. The candidates
     * are the glob-discovered ones first, then the index-listed ones (last-wins). A registration that
     * throws is charged to the entry's own namespace and skipped, so the remaining winners still land.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void register(HandlerCycle entry) {
        ReloadHandler handler = entry.handler;
        for (DuplicateIdResolver.Candidate winner : resolveAndReport(entry.candidates())) {
            Reloaded<?> reloaded = (Reloaded<?>) winner.payload();
            try {
                handler.register(reloaded.payload());
            } catch (RuntimeException e) {
                reportError(reloaded.modId(), "Failed to register '" + winner.identity() + "' from '"
                        + winner.sourcePath() + "'", e);
                continue;
            }
            entry.registered.add(reloaded);
            LOGGER.info("Loaded '{}' from '{}' (reload domain, mod '{}')",
                    winner.identity(), winner.sourcePath(), reloaded.modId());
        }
    }

    /**
     * Runs one handler's after-phase over exactly the entries this cycle registered. A failure is
     * charged to the library mod only as a fallback: individual checks are expected to guard their own
     * participants, but a handler's after-phase must not abort the cycle for the handlers after it.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private void runAfterReload(HandlerCycle entry) {
        ReloadHandler handler = entry.handler;
        try {
            handler.afterReload(entry.registered);
        } catch (RuntimeException e) {
            reportError(KasugaLib.MODID, "After-reload phase of reload handler '"
                    + handler.typeName() + "' failed", e);
        }
    }

    /**
     * Last-wins resolution of one type's candidates plus the paired diagnostic for every loser. A
     * losing duplicate is reported (WARN + bucket) but never registered, so the id keeps exactly one
     * reload-sourced entry.
     *
     * @return the surviving candidates, in their original relative order
     */
    private List<DuplicateIdResolver.Candidate> resolveAndReport(List<DuplicateIdResolver.Candidate> candidates) {
        DuplicateIdResolver.Result result = DuplicateIdResolver.resolve(candidates);
        for (DuplicateIdResolver.Conflict conflict : result.conflicts()) {
            LOGGER.warn(DuplicateIdResolver.describe(conflict));
            Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, modIdOf(conflict.loser()),
                    DuplicateIdResolver.toLoadingError(conflict));
        }
        return result.winners();
    }

    private static String modIdOf(DuplicateIdResolver.Candidate candidate) {
        return ((Reloaded<?>) candidate.payload()).modId();
    }

    /** Logs and records one reload-domain diagnostic under the namespace that owns the file. */
    private static void reportError(String modId, String message) {
        reportError(modId, message, null);
    }

    private static void reportError(String modId, String message, @Nullable Throwable cause) {
        LOGGER.error(message, cause);
        // The reload domain owns its own source dimension: the key is the namespace the offending file
        // lives in, so the error never mixes into the registration side's per-mod bucket.
        Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, modId,
                cause == null ? new IllegalStateException(message) : new IOException(message, cause));
    }

    /**
     * One handler's working state for a single cycle: its glob-discovered and index-listed candidates,
     * plus the entries it registered (handed to its after-phase).
     */
    private static final class HandlerCycle {

        private final ReloadHandler<?> handler;
        private final List<DuplicateIdResolver.Candidate> glob = new ArrayList<>();
        private final List<DuplicateIdResolver.Candidate> index = new ArrayList<>();
        private final List<Reloaded<?>> registered = new ArrayList<>();

        private HandlerCycle(ReloadHandler<?> handler) {
            this.handler = handler;
        }

        /** Records one candidate against the entry it came from (glob before index, last-wins). */
        private void add(DuplicateIdResolver.Candidate candidate, boolean viaGlob) {
            (viaGlob ? glob : index).add(candidate);
        }

        /** The unified application order: every glob-discovered entry first, then every index-listed one. */
        private List<DuplicateIdResolver.Candidate> candidates() {
            List<DuplicateIdResolver.Candidate> combined = new ArrayList<>(glob);
            combined.addAll(index);
            return combined;
        }
    }
}
