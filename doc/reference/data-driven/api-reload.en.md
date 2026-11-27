# Data-Driven Reload API (Including the Assertable Pure-Function Surface and Call Examples)

## 13. Reload API

The reload domain is orchestrated by data-driven's `ReloadOrchestrator`, using `ReloadHandler` as its extension point (symmetric with `TypeHandler` at registration time). The orchestrator hardcodes no type names — it iterates `ReloadHandlerRegistry` and accepts each handler's `typeName()` as a valid top-level key.

### 13.1 ReloadHandler (Extension Point)

`reload/ReloadHandler.java:41`. Each handler owns decode, register, and post-reload validation for one reload type.

```java
public interface ReloadHandler<T> {
    String typeName();                                              // :50 top-level key and dedup collision space
    default String describeFile() { return "reload-domain file"; }  // :60 noun in decode diagnostics
    default Set<String> globDirectories() { return Set.of(); }      // :72 empty = index-only
    void clearBucket();                                             // :85 once per cycle before discovery
    Decoded<T> decode(JsonObject root);                             // :97 pure decode; no file I/O
    void register(T payload);                                       // :107 registers last-wins winners
    default void afterReload(List<Reloaded<T>> registered) {}       // :117 cross-type validation
}
```

| Method | Responsibility and constraints | Failure behavior |
|------|-----------|---------|
| `typeName()` | Claimed top-level JSON key (e.g. `"state_machines"`); unique key and dedup space | Re-registering the same name replaces in place |
| `describeFile()` | Noun inserted into decode error messages (e.g. `"state machine file"`) | Defaults to `"reload-domain file"` |
| `globDirectories()` | Directories under `data/<ns>/` scanned via glob; empty default = index-only | Return an ordered set to keep discovery order |
| `clearBucket()` | Runs once per cycle before discovery; must clear only reload entries (keep SCRIPT) | Exceptions are isolated by `runClear` (`:250-258`) under `Domain.RELOAD_DATA` |
| `decode(root)` | `root` is guaranteed to be a JSON object; do no file I/O and return errors in `Decoded.errors()` | Exceptions are isolated per file and recorded |
| `register(payload)` | Called only for last-wins winners after discovery completes | Throw on failure so orchestrator attributes to namespace |
| `afterReload(registered)` | Called after every handler finishes `register`, only for entries registered this cycle | Exceptions attribute to `KasugaLib.MODID` without blocking later handlers |

### 13.2 ReloadHandlerRegistry

`reload/ReloadHandlerRegistry.java:27`. Static `LinkedHashMap` keyed by `typeName()`, preserving registration order.

```java
public static void register(ReloadHandler<?> handler);       // :37
public static ReloadHandler<?> get(String typeName);         // :45
public static Collection<ReloadHandler<?>> all();            // :50 immutable view in registration order
public static void clearForTest();                           // :58 tests only
```

- **Order matters**: dispatch and registration order follow `all()`.
- **Idempotent replacement**: re-registering an existing `typeName` replaces in place at the original position.

### 13.3 Decoded / Entry

`reload/Decoded.java:19`:

```java
public record Decoded<T>(List<Entry<T>> entries, List<String> errors) {   // :19
    public record Entry<T>(String id, T payload) {}                        // :28
    public static <T> Decoded<T> empty();                                  // :36
}
```

- `Entry.id` is the deduplication key within the handler type; rejected files produce empty `entries` and non-empty `errors`.

### 13.4 Reloaded

`reload/Reloaded.java:14`:

```java
public record Reloaded<T>(String modId, T payload) {}
```

Pairs a surviving registered `payload` with its source namespace `modId` for `afterReload` attribution.

### 13.5 ReloadOrchestrator

`reload/ReloadOrchestrator.java:121`. Implements `ScopedResourceManagerConsumer` and `ScopedResourcePackListener`.

```java
public ReloadOrchestrator()                                      // :133 DI entry point, registered via init():155
public ReloadOrchestrator(ReloadHandler<?>... handlers)          // :145 registers handlers, then runs
public void reload(ResourceManager resourceManager)              // :186
public static String indexResourcePath(String modId)             // :290 -> "kasuga_lib/data_driven"
```

- Legal top-level keys come dynamically from `knownTypeNames()` (`:528`) via `ReloadHandlerRegistry.all()`, while D9 registration-type examples come from `registrationFieldsExample()` (`:543`) via `TypeHandlerRegistry.all()`.
- `reload(ResourceManager)` includes a final safety catch (`:188-193`) attributing escaped exceptions to `KasugaLib.MODID`.
- Cycle stages (`runCycle`, `:198`):
  1. Clear `Diagnostics.clear(Domain.RELOAD_DATA)` (`:208`) and create `HandlerCycle` (`:633`) per handler;
  2. Call `runClear` → `clearBucket()` per handler (`:250-258`);
  3. Collect unique `globDirectories()` across handlers;
  4. Per-namespace discovery (`collectNamespace`, `:266`): `readReloadPaths` (`:307`) reads `on_reload` manifests → `collectGlob` (`:365`) scans globs skipping indexed paths (enforcing `ManagedReloadAllowlist` when managed) → `collectIndex` (`:392`) reads explicit indexed files with allowlist validation;
  5. Dispatch and decode (`dispatch`, `:484`; `readAndDispatch`, `:460`): unknown top-level keys report with D9 hint (`:494-501`), matching handlers run `decode`;
  6. Deduplicate (`resolveAndReport`, `:602`) and call `register` (`:562`) with glob candidates first, index candidates second (last-wins; losers never reach `handler.register`);
  7. Run `runAfterReload` (`:585`).

### 13.6 Exception Guards

| Layer | Scope | Source |
|----|------|------|
| Per handler clear | `clearBucket()` throws | `runClear:250-258` |
| Per file | I/O or decode failure | `readAndDispatch:460-476` |
| Per namespace | Glob listing or manifest read failure | `collectNamespace:266-288` |
| Allowlist violation | Managed pack attempts unauthorized index/content | `collectIndex:430-434` |
| Per entry | Single `register` throws | `register:562-580` |
| Per handler wrap-up | `afterReload` throws | `runAfterReload:585-600` |
| Fallback | Uncaught exception attributed to `KasugaLib.MODID` | `reload:186-193` |

### 13.7 StateMachineDefinitionLoader

Pure file decoder (`modules/modelling/.../dynamic/fsm/StateMachineDefinitionLoader.java:36-103`):

```java
public static final String PATH = "state_machines";                                        // :39
public static final String FIELD_STATE_MACHINES = "state_machines";                        // :42
public record DecodedFile(List<StateMachineDefinition> definitions, List<String> errors) {}// :55
public static DecodedFile decodeFile(JsonElement json);                                    // :67
```

Expects `{"state_machines": [...]}`. Non-object roots, missing keys, or non-array values reject the whole file; extra top-level keys are ignored by the decoder (unknown keys are reported by the orchestrator); malformed array entries are skipped individually.

### 13.8 AnimationClipLoader

Pure file decoder (`modules/modelling/.../dynamic/fsm/AnimationClipLoader.java:44-135`):

```java
public static final String FIELD_ANIMATION_CLIPS = "animation_clips";                      // :47
public record DecodedFile(List<AnimationClip> clips, List<String> errors) {}               // :59
public static DecodedFile decodeFile(JsonElement json);                                    // :71
public static void register(FsmAnimationClips clips, AnimationClip clip);                  // :127
```

- **Removed-key rejection (`:100-108`)**: elements containing removed keys `"functions"` or `"formula_parts"` are explicitly rejected with `"animation_clips[i] uses removed key '<legacyKey>'; replace it with 'formula_transforms'; element skipped"`, skipping only that element.
- **Registration & discovery**: `AnimationClip.CODEC` reads `id` from each clip object; `register` writes to `clips.registerResource` (preserving script-wins). Clips are index-only (`on_reload`), with no glob directory.

### 13.9 Modelling-Side Handlers and Registrar Bean

Located in `modules/modelling` package `lib.kasuga.rendering.models.mc.dynamic.fsm`:

| Class | `typeName` | Discovery | Role |
|----|----------|------|------|
| `FsmReloadHandler` (`:42`) | `state_machines` | glob `state_machines/` + index | `decode` → `StateMachineDefinitionLoader.decodeFile`; `register` writes `FsmDefinitions` RESOURCE half; `afterReload` reports dangling clip refs to `Domain.RELOAD_DATA` |
| `FsmClipsReloadHandler` (`:24`) | `animation_clips` | index-only (empty `globDirectories`) | `decode` → `AnimationClipLoader.decodeFile`; `register` → `AnimationClipLoader.register` |
| `FsmReloadHandlerRegistrar` (`:23`) | — | — | `@Context` + `@PostConstruct` (`:25-31`), registers **fsm → clips** into `ReloadHandlerRegistry`, bound to `FsmRegistries.GLOBAL` |

### 13.10 FsmDefinitions and FsmAnimationClips

Both bucket stores live in `render-core`, separating RESOURCE (cleared by `clearResource`) from SCRIPT (survives reloads):

`FsmAnimationClips` (`FsmAnimationClips.java:30`):

```java
public enum ClipSource { RESOURCE, SCRIPT }                                              // :35
public record Entry(AnimationSampler<?> sampler, Object data, ClipSource source) {}      // :43
public void register(Id id, AnimationSampler<?> sampler, Object data)                    // :53 (SCRIPT)
public void registerResource(Id id, AnimationSampler<?> sampler, Object data)            // :66 (RESOURCE)
public Entry get(Id id)                                                                  // :87
public boolean remove(Id id)                                                             // :92
public void clearResource()                                                              // :100
public void clearAll()                                                                   // :108
public void clear()                                                                      // :117 (alias of clearResource)
```

`FsmDefinitions` (`FsmDefinitions.java:27`) is isomorphic: `register` (`:59`), `registerResource` (`:71`), `clearResource` (`:84`), `clearAll` (`:95`), `remove` (`:107`), `get` (`:116`), `hash` (`:126`), `addListener` (`:142`).

### 13.11 ManagedReloadAllowlist

`reload/ManagedReloadAllowlist.java:22-163` enforces a process-wide allowlist for resources projected from `ApprovedResourcePacks`, leaving legacy mod and data packs unchanged:

```java
public static synchronized void install(
        String hostModId,
        List<LocatedIndexPath> indexes,
        List<LocatedContentPath> reloadFiles)                                                // :28
public static synchronized void install(
        String hostModId,
        List<SourceDescriptor> sources,
        List<LocatedIndexPath> indexes,
        List<LocatedContentPath> reloadFiles)                                                // :36
public static synchronized void install(
        String hostModId,
        List<SourceDescriptor> sources,
        Map<String, String> projectedSourcePackIds,
        List<LocatedIndexPath> indexes,
        List<LocatedContentPath> reloadFiles)                                                // :45
public static synchronized boolean isManaged(String sourcePackId)                            // :96
public static synchronized boolean allowsIndex(String sourcePackId, ResourceLocation loc)    // :101
public static synchronized boolean allowsContent(String sourcePackId, ResourceLocation loc)  // :109
public static synchronized int priority(String sourcePackId)                                 // :117
public static synchronized void clearForTests()                                              // :126
```

`ReloadOrchestrator` applies the allowlist at three points:
- `readReloadPaths` (`:317-332`): when a stack contains managed entries matching `ManagedReloadAllowlist.isManaged(sourcePackId(resource))`, filters to `allowsIndex(sourcePackId, loc)` and orders them by `priority(sourcePackId)` (skipping if none are allowed);
- `collectGlob` (`:375-380`): filters `!ManagedReloadAllowlist.isManaged(sourcePackId(resource))` so managed packs never leak into directory globs;
- `collectIndex` (`:419-437`): filters managed stack entries via `allowsContent(sourcePackId, loc)` ordered by `priority(sourcePackId)`, blocking unapproved managed paths with a `Domain.RELOAD_DATA` diagnostic.

### 13.12 v2 Startup `on_reload` Preflight

`DataDrivenBuildPipeline` (`DataDrivenBuildPipeline.java:919-1025`, `validateReloadContentFile`) runs read-only preflight on all files listed under `on_reload` during registration-phase `prepare`:

- Checks each top-level key against `ReloadHandlerRegistry.get(field)`, emitting `UNKNOWN_TYPE` (`ERROR` under `STRICT`, `WARNING` under `LEGACY`) for unregistered fields.
- Calls `handler.decode(root)` and converts `Decoded.errors()` or thrown exceptions into `BuildDiagnostic` entries (`Stage.PARSE`, `Code.CONTENT_INVALID`).
- Performs **zero bucket mutations**: `clearBucket()`, `register(...)`, and `afterReload(...)` are never called during startup preflight.

---

## 14. The Pure-Function Surface Assertable from Tests

These APIs require no running NeoForge game instance and can be asserted directly in plain JVM unit tests:

| API | What it asserts |
|-----|---------|
| `JsonTreeBuilder.indexDirectorySegments` / `validateSourcePath` | Index directory layout segments and content path syntax rules |
| `JsonTreeBuilder.parseIndexManifest` / `resolveIndexManifests` | Index schema rules D1–D6 (including dual-listing fail-closed) |
| `JsonTreeBuilder.parseContentBody` / `listIndexFiles` / `applyUnchecked` | v1 dispatch, embedded extraction, D9 hints, and bucketed apply errors |
| `DuplicateIdResolver` / `EffectiveId.of` / `EffectiveId.ofLegacy` | Last-wins conflict detection and `STRICT` vs. `LEGACY` identity rules |
| `Diagnostics` / `DataDiagnosticsCommands.kasugaDataCommand` | Two-dimension bucket isolation, `summarize` format, and command tree shape |
| `SnapshotContentSource.open` / `ContentSourceFactory.snapshot` / `LogicalPath` / `SourceDescriptor` | v2 snapshot wrapping, literal path validation, `declaredIndexes` scoping, and post-close guards |
| `DataDrivenBuildPipeline.prepare` / `build` (with `SnapshotContentSource`) | Multi-source discovery, strict JSON decoding, capability checks, references, claim conflicts, and `BuildResult` states |
| `HandlerCapability` / `TypeHandler.strictValidationImplemented` / `ValidationContext` (`find` / `findAllVisible` / `findAll(String)` / `claimAt`) | `OVERLAY` candidate retention, single-visible `find`, full `findAll(typeDomain)` enumeration, and strict readiness |
| `RegistryClaimSnapshot.collect` | BFS claim extraction from a `Reg` tree (skipping `dispatchEnabled == false` groups) |
| `JsonPropertyParser.validateStrict` / `JsonItemParser.validateStrict` | Exact property validation (types/bounds/reserved `tab` checks) and strict predicate extensions |
| `ManagedReloadAllowlist` / `ReloadOrchestrator` / `ReloadHandlerRegistry` | Reload cycle ordering, allowlist enforcement, and test isolation |
| `StateMachineDefinitionLoader.decodeFile` / `AnimationClipLoader.decodeFile` / `register` | State-machine/clip file decoding (including `functions`/`formula_parts` rejection) and script-wins |
| `ProxyModelBlockFactories.parseModelSettings` / `validateBlockParams` / `ProxyModelSelection` | Proxy `params` parsing, uniform group part ID checks, and `model_by_state` compilation |
| `ContentStructure.body` / `unknownKeys` / `field` / `joinWithOr` / `describe` | Shared structural validation helpers |

---

## 15. Call Examples

All snippets below match current source signatures.

### 15.1 v2 Multi-Source Pipeline `prepare` + `build` (Pure JVM `SnapshotContentSource`)

```java
import java.util.List;
import lib.kasuga.core.resource.source.InputPolicy;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;
import lib.kasuga.core.resource.source.ResourceSources;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.registration.data_driven.TypeHandlerRegistry;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.pipeline.BuildOptions;
import lib.kasuga.registration.data_driven.pipeline.BuildResult;
import lib.kasuga.registration.data_driven.pipeline.DataDrivenBuildPipeline;
import lib.kasuga.registration.data_driven.pipeline.PreparedContent;
import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.ContentSourceFactory;

JsonTreeBuilder.ensureHandlersRegistered();

ResourceSourceDescriptor resDesc = new ResourceSourceDescriptor(
        "kuayue", "kuayue_main", "root_0", 0, ResourceSourceDescriptor.Origin.EXTERNAL);

try (var vfs = new InMemoryVfs("kuayue_main")) {
    vfs.writeString(VfsPath.of("data/kuayue/kasuga_lib/data_driven/index.json"),
            "{\"on_register\": [\"blocks/demo.json\"]}");
    vfs.writeString(VfsPath.of("data/kuayue/blocks/demo.json"),
            "{\"blocks\": [{\"id\": \"kuayue:demo_block\", \"type\": \"data_driven_block\"}]}");

    try (var snapshot = ResourceSources.fixed(
                    resDesc,
                    ResourceSources.vfs(resDesc, vfs, false, "", "", InputPolicy.DEFAULT),
                    InputPolicy.DEFAULT).capture();
         ContentSource source = ContentSourceFactory.snapshot(snapshot, resDesc, List.of("kuayue"), "main")) {

        DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                "kuayue",
                TypeHandlerRegistry.all(),
                List.of());
        PreparedContent prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);
        if (!prepared.hasBlockingDiagnostics()) {
            BuildResult result = pipeline.build(prepared);
            boolean ok = result.applied(); // true only for Status.APPLIED
        }
    }
}
```

### 15.2 v2 Host Provider Installation and `ValidationContext.findAll`

```java
import java.util.List;
import lib.kasuga.registration.data_driven.DataDrivenRegistrationProviders;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.LocatedDefinition;
import lib.kasuga.registration.data_driven.validation.ValidationContext;

// Install host takeover (close Attachment on teardown in tests)
try (var attachment = DataDrivenRegistrationProviders.install("kuayue", hostModId -> {
    // Run DataDrivenBuildPipeline.prepare + build and return JsonRegistryGroup
    return null;
})) {
    // ...
}

// Inside a custom TypeHandler.validate(..., ValidationContext context):
void validateCustomRef(ValidationContext context, String targetBlockId) {
    // 1. Checks missing, undeclared-dependency, and >1 visible ambiguity automatically
    context.requireDefinition(new DefinitionKey("blocks", targetBlockId), "/target_block");

    // 2. Enumerate every parsed block definition across all sources in declaration order
    List<LocatedDefinition<?>> allBlocks = context.findAll("blocks");
}
```

### 15.3 v1 Mod Registration and Error Bucket Inspection

```java
import java.util.List;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;

JsonTreeBuilder.clearLoadingErrors("kasuga_lib");
JsonRegistryGroup root = JsonTreeBuilder.buildForMod("kasuga_lib");
// null root means no on_register content was declared; non-null is the root group attached to the mod tree
List<Throwable> errors = JsonTreeBuilder.getLoadingErrors("kasuga_lib");
```

### 15.4 Index Manifest Parsing and Aggregation (v1 Pure Functions)

```java
import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder.IndexManifest;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder.ResolvedIndex;

JsonObject doc = JsonParser.parseString(
        "{\"on_register\": [\"carriages/m1/blocks.json\"]}").getAsJsonObject();
IndexManifest manifest = JsonTreeBuilder.parseIndexManifest("kuayue", "index.json", doc);
ResolvedIndex index = JsonTreeBuilder.resolveIndexManifests("kuayue", List.of(manifest));
if (!index.nothingDeclared()) {
    for (String path : index.onRegisterPaths()) {
        // path is relative to data/kuayue/ and cleared D6 dual-listing checks
    }
}
```

### 15.5 Content-Body Parse Without Registration (v1 Pure Function)

```java
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;

JsonObject body = /* parsed content JSON */ new JsonObject();
Map<String, List<Object>> collected =
        JsonTreeBuilder.parseContentBody("kuayue", "carriages/m1/blocks.json", body);
// keys are handler typeName; parse errors land in JsonTreeBuilder.getLoadingErrors("kuayue")
```

### 15.6 `Diagnostics` Buckets and Command Execution

```java
import java.util.List;
import com.mojang.brigadier.CommandDispatcher;
import lib.kasuga.registration.data_driven.diagnostics.DataDiagnosticsCommands;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

Diagnostics.report("kuayue", new IllegalStateException("boom"));
List<Throwable> perMod = Diagnostics.errors("kuayue");
List<String> lines = Diagnostics.summarize();
Diagnostics.clear("kuayue");

CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
DataDiagnosticsCommands.onRegisterCommands(
        new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null));
dispatcher.execute("kasuga_data errors kuayue", source);   // requires permission >= 2
```

### 15.7 Reload Cycle and Animation Clip Decoding

```java
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationClipLoader;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmClipsReloadHandler;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmReloadHandler;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import net.minecraft.server.packs.resources.ResourceManager;

FsmDefinitions definitions = new FsmDefinitions();
FsmAnimationClips clips = new FsmAnimationClips();
ReloadOrchestrator orchestrator = new ReloadOrchestrator(
        new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips));
orchestrator.reload(resourceManager);

AnimationClipLoader.DecodedFile decoded =
        AnimationClipLoader.decodeFile(JsonParser.parseString(json));
for (AnimationClip clip : decoded.clips()) {
    AnimationClipLoader.register(clips, clip);   // writes RESOURCE half; SCRIPT entries remain intact
}
```

### Reference Usage Notes

- v2 `DataDrivenBuildPipeline` with in-memory `SnapshotContentSource` runs `prepare` preflight directly in pure JVM JUnit tests; running `build`/`apply` for Minecraft `Block`/`Item` registrations or `ReloadOrchestrator.reload` requires NeoForge registries / `ResourceManager`.
- v1 pure helpers (`parseContentBody`, `parseIndexManifest`, `resolveIndexManifests`, `DuplicateIdResolver`, `EffectiveId`, `Diagnostics`) can likewise be asserted without NeoForge runtime bootstrap.

> Other pages in the data-driven section: [Registration API](api-registration.md) · [Extension API](api-extension.md) · [Diagnostics API](api-diagnostics.md) · [Reload API](api-reload.md) · [Schema Reference](schema.md) · [Block State Properties](block-state-properties.md) · [Section index](README.md)
