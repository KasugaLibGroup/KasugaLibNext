# Data-driven registration-phase API (JsonTreeBuilder, index, and content dispatch)

> This page is a reference (for looking things up). Its subject is the KasugaLibNext data-driven Java API: classes, methods, signatures, call timing, and failure behavior.
> Applies to: Minecraft 1.21.1 / NeoForge 21.1.203 / Parchment 2024.11.17 / Java 21. Signatures and line numbers refer to the current working tree (`modules/data-driven`, `modules/core`, and `modules/modelling`), covering both the v2 multi-source build pipeline (`DataDrivenRegistrationProviders`, `ContentSourceFactory.snapshot`, `DataDrivenBuildPipeline`) and the legacy single-mod entry point (`JsonTreeBuilder`).
> Boundary: which key a JSON field is written under, what type it has, and whether it is required all belong to [schema.md](schema.md); the getting-started tutorial belongs to [intro.md](../../guides/data-driven/intro.md), and the steps for content authors and extension authors belong to [guide-content.md](../../guides/data-driven/guide-content.md) / [guide-extension.md](../../guides/data-driven/guide-extension.md).
> Terminology: a **domain** is one of the two arrays in the index manifest, `on_register` (registration domain) and `on_reload` (reload domain); a **bucket** holds legacy registration diagnostics (mod dimension, keyed by mod id) or reload diagnostics (`RELOAD_DATA[<namespace>]`), whereas the v2 multi-source pipeline produces structured `BuildDiagnostic` values instead of writing to `Diagnostics`.

## 1. Module and package structure

Code root: `modules/data-driven/src/main/java/lib/kasuga/registration/data_driven/`.

```text
data_driven/
├── DataDrivenRegistrationProvider.java   # v2 host-owned replacement for mod-JAR scanning
├── DataDrivenRegistrationProviders.java  # process-wide provider install/find/close registry
├── JsonTreeIntegration.java              # RegisterContextRegistry hook (checks provider first, falls back to JsonTreeBuilder)
├── TypeHandler.java                      # handler interface: top-level/embedded, phase, identity, capabilities/validate/claims
├── TypeHandlerRegistry.java              # global static handler table (LinkedHashMap, order-preserving)
├── builder/
│   └── JsonTreeBuilder.java              # legacy single-mod entry point & pure-JVM index/content helpers
├── context/
│   ├── BuildContext.java                 # build context base class (hostModId / rootGroup / currentDefinition / strict / meta)
│   ├── RegBuildContext.java              # registration-scenario context (group / creativeTab / reg access)
│   └── JsonRegistryGroup.java            # root/property group created from JSON (RegistryGroup subclass)
├── dedup/
│   ├── DuplicateIdResolver.java          # pure-function last-wins deduplication
│   └── EffectiveId.java                  # id normalization (of / ofLegacy)
├── diagnostics/
│   ├── Diagnostics.java                  # diagnostic facade (mod dimension + domain/source-key dimension)
│   └── DataDiagnosticsCommands.java      # /kasuga_data errors [mod]
├── handler/
│   ├── MetaTypeHandler.java              # marker base class
│   ├── RegistryGroupDef.java / RegistryGroupHandler.java    # registry_groups, PHASE_GROUPS
│   ├── RegTypeHandler.java               # abstract base class for registration handlers (blocks/items)
│   ├── BlockDef.java / BlockTypeHandler.java                # blocks, PHASE_CONTENT
│   ├── ItemDef.java / ItemTypeHandler.java                  # items, PHASE_CONTENT
│   └── BlockEntityDef.java / BlockEntityTypeHandler.java    # block_entities (embedded), PHASE_EMBEDDED
├── json/
│   └── DataJson.java                     # Gson bindings for StatePropertySet and creativeTab
├── pipeline/                             # v2 multi-source build pipeline
│   ├── BuildDiagnostic.java              # structured diagnostic (Severity / Stage / Code)
│   ├── BuildFinalizer.java               # session-scoped hook run after all applies complete
│   ├── BuildOptions.java                 # LEGACY / STRICT policy
│   ├── BuildResult.java                  # build outcome (Status / rootGroup / diagnostics / finalizerResults)
│   ├── DataDrivenBuildHooks.java         # process-wide finalizer hooks bucketed by hostModId
│   ├── DataDrivenBuildPipeline.java      # two-phase prepare / build multi-source pipeline
│   ├── DefinitionKey.java                # (typeDomain, id) logical identity
│   ├── HandlerCapability.java            # STRICT_PREFLIGHT / NON_REGISTRATION / REGISTRY_CLAIMS / OVERLAY
│   ├── LocatedContentPath.java / LocatedDefinition.java / LocatedIndexPath.java
│   ├── PipelineValidationContext.java    # pipeline implementation of ValidationContext
│   ├── PreparedContent.java              # immutable snapshot produced by prepare
│   ├── RegistrationTarget.java           # factory hook creating RegBuildContext
│   └── RegistryClaim.java / RegistryClaimSnapshot.java  # registry occupation claim & host tree snapshot
├── property/
│   ├── JsonPropertyParser.java / JsonItemParser.java        # block/item property parsing & validateStrict
│   ├── StrictPropertyIssue.java / StrictPropertyValues.java
│   └── compiler/                         # PropertyCompiler / RLCompiler
├── reload/                               # reload domain: extension points, orchestrator, allowlist
│   ├── ReloadHandler.java / ReloadHandlerRegistry.java
│   ├── ReloadOrchestrator.java / ManagedReloadAllowlist.java
│   └── Decoded.java / Reloaded.java
├── resource/
│   └── ScriptBlockReferenceDecoder.java
├── source/                               # v2 content source abstractions and adapters
│   ├── ContentSource.java / ContentSourceFactory.java / SnapshotContentSource.java
│   ├── SourceDescriptor.java / LogicalPath.java / LocatedFile.java
│   ├── DirectoryContentSource.java / ZipContentSource.java / ModJarContentSource.java / NioRootContentSource.java
│   └── SourceAccessException.java / SourceLimits.java / LimitedInputStream.java / ZipSecurity.java
├── state/                                # block-state properties support
│   ├── DataDrivenBlock.java / DataDrivenBlockFactory.java
│   └── StateProperty.java / StatePropertyContext.java / StatePropertySet.java / StatefulBlockFactory.java / StringStateProperty.java
├── structure/
│   └── ContentStructure.java             # shared pure-function structural check layer
└── validation/
    └── ValidationContext.java            # read-only preflight validation context interface
```

Related external modules:

- `modules/core/src/main/java/lib/kasuga/core/resource/`: `ResourceSource`, `ResourceSnapshot`, `FixedResourceSource`, `ResourceSources`, `ResourceSourceDescriptor`, `InputPolicy`, `StrictResourceJson`, `ApprovedResourcePacks`, `MappedPackResources`; plus `lib.kasuga.registration.RegistryGroup` (`dispatchWhen`) and `lib.kasuga.registration.factory.FactoryRegistry`.
- `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/dynamic/fsm/`: `FsmReloadHandler`, `FsmClipsReloadHandler`, `FsmReloadHandlerRegistrar`, `FsmBlockEntityFactories`, `StateMachineDefinitionLoader`, `AnimationClipLoader`.
- `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/proxies/`: `ProxyModelBlockFactories` (`proxy_model` / `proxy_model_be`), `ProxyModelBlock`, `ProxyModelBlockEntity`, `ProxyModelSelection`, `CompiledModelGroup`, `CompiledModelPart`, `ProxyCommands` (`/kasuga_proxy list`).

Dependency direction: `modelling → data-driven → core`. `data-driven` does not depend on `modelling`.

## 2. Legacy registration-phase entry point: JsonTreeBuilder

`builder/JsonTreeBuilder.java` is the **legacy single-mod JAR entry point** (and also hosts pure-JVM index/content parsing helpers). All methods are `static`, and the class cannot be instantiated (`:882`). Multi-source builds, external packs, owner dependency isolation, strict preflight, and claim checking should use the v2 `DataDrivenRegistrationProvider` + `DataDrivenBuildPipeline` APIs in sections 16–18.

### 2.1 buildForMod

```java
public static JsonRegistryGroup buildForMod(String modId)   // JsonTreeBuilder.java:67
```

Responsibility: load one mod JAR's `on_register` content, and build and return its root registry group `<modId>:json_root`.

Parameter constraint: `modId` must have a corresponding mod file in FML's `ModList` (internally via `findModFile`, `:744`). When not found, `null` is returned and no error is reported.

Execution order (one call):

1. `ensureHandlersRegistered()` (`:57`), idempotent, registers `DataDrivenBlockFactory` and the four built-in `TypeHandler`s once.
2. `clearLoadingErrors(modId)` (`:69`), clears only that mod's bucket.
3. Locate the index directory `data/<modId>/kasuga_lib/data_driven/` (`findIndexDir`, `:797`).
4. Read the index manifests one by one (`listIndexFiles` scans the directory, `readIndexManifest` parses), then aggregate them (`resolveIndexManifests`).
5. Parse only the content files `on_register` points at; `on_reload` is recognized and validated here, but not parsed (`:106-117`).
6. Resolve duplicate ids before any apply side effect (`:125`).
7. Apply each handler in ascending `getPhase()` order; `registry_groups` gets an extra topological sort before apply (`:128-152`).
8. Return `rootGroup` (`:154`).

Cases that return `null`:

| Case | Location | Error recorded? |
|------|------|-----------|
| The index directory does not exist, and the old-layout directory `data/<mod>/kasugalib/` does not exist either | `:82-85` | No, only a DEBUG record |
| The index directory does not exist, but the old-layout directory does | `:73-81` | Yes, WARN + bucket entry, hinting at migration to `on_register` |
| The index directory exists, but no manifest declares either domain (an empty directory included) | `:103-105` | Yes; aggregation records "declares neither ... in any index file" |

Note: a mod that declares only `on_reload` does not count as `nothingDeclared()`, so it returns a non-null empty root group (`:106-109` records only INFO), not `null`.

### 2.2 buildAll

```java
public static Map<String, JsonRegistryGroup> buildAll()   // JsonTreeBuilder.java:157
```

Responsibility: walk every mod file in `ModList`, call `buildForMod` for each mod, and collect non-null results keyed by mod id in `LinkedHashMap` order. The same mod file path is processed only once (`visitedRoots`, `:168`). When a single mod throws, the `catch` logs an ERROR and wraps the failure as an `IllegalStateException` in that mod's bucket (`:177-181`) before continuing. Production wiring currently routes through `JsonTreeIntegration` per mod rather than `buildAll()`.

### 2.3 When JsonTreeIntegration is wired up

`JsonTreeIntegration` (`JsonTreeIntegration.java:13`) is a Micronaut `@Context` bean. `@PostConstruct` (`:21`) registers a **COMMON-side callback** with `RegisterContextRegistry` (`:24-42`):

1. First checks `DataDrivenRegistrationProviders.find(modRegistry.getModId()).orElse(null)` (`:25-26`):
   - If a provider is installed: calls `provider.build(modRegistry.getModId())`; if non-null, attaches it as a child of `modRegistry`; **if `null`, returns immediately without falling back to `JsonTreeBuilder`**; exceptions thrown by a provider are not swallowed here (hosts manage their own diagnostics).
2. If no provider is installed: falls back to `JsonTreeBuilder.buildForMod(modRegistry.getModId())` inside a `try/catch`, attaching any non-null root (`:34-41`).

The callback fires lazily on the first registration-event dispatch for that mod, guaranteeing that `@Context` factory registrations and early provider installations run before JSON parsing.

### 2.4 Phase semantics

| Constant | Value | Handler that uses it | Reason |
|------|-----|-----------------|------|
| `TypeHandler.PHASE_GROUPS` | 0 | `registry_groups` | Groups must exist before content |
| `TypeHandler.PHASE_CONTENT` | 1 | `blocks`, `items` | Regular content |
| `TypeHandler.PHASE_EMBEDDED` | 2 | `block_entities` | A BE has to attach to an already existing block Reg |

Within the same phase in `JsonTreeBuilder`, order follows `TypeHandlerRegistry` registration order (`LinkedHashMap`); in v2 `DataDrivenBuildPipeline`, sorting additionally considers handler list index, `sourceOrder`, `logicalPath`, `jsonPointer`, `declarationOrder`, and `parentDefinition` topological edges (see section 18). `JsonTreeBuilder` topologically sorts `registry_groups` via `topoSortGroupDefs` (`:890`), logging a WARN on cycles and falling back to the root group for missing parents (whereas v2 `STRICT` reports `GROUP_CYCLE` as a blocking error).

## 3. Index API

Index directory: `data/<modId>/kasuga_lib/data_driven/`. Every `.json` under it is a manifest, and a manifest declares only content-file paths, never types.

### 3.1 IndexManifest

```java
public record IndexManifest(boolean declaredOnRegister, boolean declaredOnReload,
                            List<String> onRegisterPaths, List<String> onReloadPaths)
// JsonTreeBuilder.java:368
```

The parsed result of one manifest, not yet consumed. `empty()` (`:371`, package-visible) returns a "nothing declared" instance when a manifest cannot be read.

| Member | Meaning |
|------|------|
| `declaredOnRegister` | `on_register` appears as an array (an empty array counts too, D2) |
| `declaredOnReload` | `on_reload` appears as an array |
| `onRegisterPaths` | The entries of `on_register`, in file order |
| `onReloadPaths` | The entries of `on_reload`, in file order |

### 3.2 ResolvedIndex

```java
public record ResolvedIndex(boolean declaredOnRegister, boolean declaredOnReload,
                            List<String> onRegisterPaths, List<String> onReloadPaths)
// JsonTreeBuilder.java:386
// public boolean nothingDeclared()   // :389
```

The result of aggregating all of one mod's manifests and removing cross-domain conflicts. `nothingDeclared()` is the D4 test: when true, no manifest declared either domain.

### 3.3 parseIndexManifest

```java
public static IndexManifest parseIndexManifest(String modId, String label, JsonObject root)
// JsonTreeBuilder.java:228
```

Responsibility: parse one in-memory manifest with no NeoForge dependency, callable from plain JVM tests. A `null` `root` is treated as an empty file. Only `on_register` and `on_reload` are recognized; unknown fields are recorded in the `modId` bucket without throwing and without aborting sibling fields (D3).

### 3.4 resolveIndexManifests

```java
public static ResolvedIndex resolveIndexManifests(String modId, List<IndexManifest> manifests)
// JsonTreeBuilder.java:318
```

Responsibility: aggregate all manifests of one mod, apply cross-domain fail-closed filtering (D6: a path appearing in both domains is reported and removed from both lists), and emit the aggregate D4 warning/error when neither domain was declared.

### 3.5 listIndexFiles

```java
public static List<Path> listIndexFiles(Path indexDir, String modId)   // JsonTreeBuilder.java:783
```

Responsibility: list `.json` files in `indexDir`, sorted by path string; records an ERROR in the mod bucket and returns an empty list if listing fails.

### 3.6 How D1 to D6 show up at the API layer

| Rule | Meaning | Where it lands |
|------|------|------|
| D1 | Both fields are individually optional; when neither appears an error is reported and nothing is consumed | `JsonTreeBuilder.parseIndexManifest:247-253` |
| D2 | An empty array is legal and counts as "declared" | `parseIndexManifest:244`, `resolveIndexManifests:347` branch |
| D3 | An unknown top-level field or non-array field does not abort sibling fields | `parseIndexManifest:231-238`, `readPathArray:263-279` |
| D4 | The aggregate warning branches on "which field was declared" | `resolveIndexManifests:347-352` |
| D5 | `sources` is not an alias; reported as an unknown field | `parseIndexManifest:231-238` |
| D6 | A path listed in both arrays fails closed and neither domain consumes it | `resolveIndexManifests:330-345` |

v2 `DataDrivenBuildPipeline` (`readManifest:513-588`, `resolveNamespace:635-710`) enforces the same D1–D6 rules with `MANIFEST_INVALID` and `MANIFEST_EMPTY` diagnostics.

### 3.7 The deprecated field

`sources` is no longer a legal index field (D5); writing it produces an unsupported-field error / `MANIFEST_INVALID` and declares no domain. Only `on_register` and `on_reload` are valid.

### 3.8 Path contract

```java
public static String validateSourcePath(String path)              // JsonTreeBuilder.java:407
public static String[] indexDirectorySegments(String modId)       // JsonTreeBuilder.java:766
```

`validateSourcePath`: returns `null` when legal (non-empty, relative without leading `/`, ends in `.json`, no empty segment, no `.` or `..`), otherwise a reason string. Pure JVM function.

`indexDirectorySegments`: returns a fresh array `{"data", modId, "kasuga_lib", "data_driven"}`. `ReloadOrchestrator.indexResourcePath` drops the first two segments to yield `kasuga_lib/data_driven`.

## 4. Content dispatch and apply

### 4.1 parseSource (private)

`parseSource` (`JsonTreeBuilder.java:419`) validates via `validateSourcePath`, deduplicates via `parsedSources`, reads `data/<modId>/<sourcePath>` from `findModFile`, and hands the object to `dispatchContent`.

### 4.2 dispatchContent (private)

`dispatchContent` (`JsonTreeBuilder.java:485`) dispatches top-level fields (`getParentTypeName() == null`), reports unknown top-level fields (including embedded types written at the top level and D9 reload-domain hints via `reloadDomainHint:580-591`), checks arrays/objects via `ContentStructure.field`, and extracts embedded definitions via `extractEmbedded` (`:544-569`).

### 4.3 parseContentBody

```java
public static Map<String, List<Object>> parseContentBody(String modId, String contentLabel, JsonObject root)
// JsonTreeBuilder.java:641
```

In-memory half of the legacy content pipeline with no NeoForge dependency: parses and groups definitions by handler `typeName` without registering or applying; malformed input goes into the mod's error bucket rather than throwing.

### 4.4 applyUnchecked

```java
public static void applyUnchecked(TypeHandler handler, Object definition, BuildContext context, String modId)
// JsonTreeBuilder.java:733
```

Runs `handler.apply` and records any thrown exception as a `Failed to apply '<type>' handler for mod '<mod>'` ERROR (`IllegalStateException`) in the mod bucket.

## 5. Conflict resolution

Duplicate ids are resolved before any apply side effect so `MappedRegistry` never sees a duplicate key.

### 5.1 DuplicateIdResolver

`dedup/DuplicateIdResolver.java`, pure function with no Minecraft types.

```java
public record Candidate(String typeName, String identity, String sourcePath, Object payload)  // :39
public record Conflict(Candidate loser, Candidate winner)                                     // :47
public record Result(List<Candidate> winners, List<Conflict> conflicts)                       // :65
public static Result resolve(List<Candidate> candidates)                                      // :73
public static String describe(Conflict conflict)                                              // :109
public static Throwable toLoadingError(Conflict conflict)                                     // :124
```

- `null` `Candidate.identity` opts out of deduplication and never conflicts.
- Collision space is `(typeName, identity)`.
- `resolve` implements last-wins by position in `candidates`.

### 5.2 EffectiveId

```java
public static String of(String modId, String rawId)         // EffectiveId.java:39
public static String ofLegacy(String modId, String rawId)   // EffectiveId.java:54
```

Normalizes a raw id to its effective `ResourceLocation` string:

| Raw id | `EffectiveId.of` (v2 `STRICT`) | `EffectiveId.ofLegacy` (legacy & `LEGACY`) |
|---------|---------|---------|
| `foo` | `<modId>:foo` (`STRICT` validate rejects bare ids) | `<modId>:foo` |
| `minecraft:foo` | **`minecraft:foo`** (kept verbatim) | `<modId>:foo` (legacy rewrite) |
| `othermod:foo` | `othermod:foo` | `othermod:foo` |

### 5.3 Per-type identity

| Type | identity | Source |
|------|----------|------|
| `blocks` / `items` | `EffectiveId.of` (strict) or `EffectiveId.ofLegacy` (legacy) | `RegTypeHandler.resolveIdentity` (`RegTypeHandler.java:77-86`) |
| `block_entities` | host block's effective id (same mode-aware rule) | `BlockEntityTypeHandler.resolveIdentity` (`:87-96`) |
| `registry_groups` | raw `id` string, not normalized | `RegistryGroupHandler.resolveIdentity` (`:66-68`) |
| Custom handler | `null` by default (opts out of deduplication) | `TypeHandler.java:112-120` |

### 5.4 last-wins order (legacy loader)

`resolveDuplicates` (`JsonTreeBuilder.java:673`) builds candidates in load order: ascending index filename → `on_register` array order → content array order (with embedded BEs following their host blocks). Losers never enter `apply`, and each conflict emits a WARN log plus a mod-bucket `IllegalStateException`. For v2 multi-source selection, `OVERLAY`, and `REGISTRY_CLAIMS` checks, see section 18 and [api-extension.md](api-extension.md) §8.8.

---

## 16. v2 Host Takeover: `DataDrivenRegistrationProvider` & `DataDrivenRegistrationProviders`

### 16.1 DataDrivenRegistrationProvider

`DataDrivenRegistrationProvider.java:8-11`:

```java
@FunctionalInterface
public interface DataDrivenRegistrationProvider {
    JsonRegistryGroup build(String hostModId);
}
```

Replaces legacy mod-JAR scanning (`JsonTreeBuilder.buildForMod`) for a host mod. Returning `null` indicates that the host took over and no JSON root group should be attached for this run.

### 16.2 DataDrivenRegistrationProviders

`DataDrivenRegistrationProviders.java:11-55`:

```java
public static Attachment install(String hostModId, DataDrivenRegistrationProvider provider)  // :14
public static Optional<DataDrivenRegistrationProvider> find(String hostModId)                // :25

public static final class Attachment implements AutoCloseable {
    @Override public void close()                                                            // :51
}
```

- **Process-wide map**: backed by `ConcurrentHashMap<String, Attachment>`.
- **Uniqueness**: installing a second provider for the same `hostModId` throws `IllegalStateException` (`:18-21`); null or blank `hostModId` throws `IllegalArgumentException` (`:31-36`).
- **Exact removal**: `Attachment.close()` calls `PROVIDERS.remove(hostModId, this)` so tests or explicit teardowns only remove their own attachment (`:51-53`).
- **Timing**: must be installed before `JsonTreeIntegration`'s registration callback runs for that mod.

## 17. v2 Content Sources & Snapshots: `ContentSource`, `ContentSourceFactory`, and `SnapshotContentSource`

### 17.1 Two-layer capture vs registration view

v2 production wiring separates physical capture from registration-time reading across `modules/core` and `modules/data-driven`:

1. **Platform capture layer (`modules/core`, `lib.kasuga.core.resource.source`)**:
   - `ResourceSourceDescriptor(String ownerId, String sourceId, String rootId, int rank, Origin origin)` (`ResourceSourceDescriptor.java:6-16`): unique platform identity; IDs forbid colons (`ResourcePaths.id`), `rank >= 0`, `origin` is `EXTERNAL`, `MC_MANAGED`, or `DERIVED`.
   - `ResourceSource` (`ResourceSource.java:5-9`): constructed via `ResourceSources.mod`, `directory`, `readOnlyDirectory`, `archive`, or `vfs`. `ResourceSources.fixed(descriptor, source, policy)` (`ResourceSources.java:94-118`) immediately captures an immutable `baseline` snapshot and closes the underlying live source, returning a `FixedResourceSource` whose subsequent `capture()` calls emit independent `ResourceSnapshot`s.
   - `ResourceSnapshot` (`ResourceSnapshot.java:17-187`): immutable captured bytes (stored in memory up to `InputPolicy.memoryThresholdBytes`, or spooled to private temp files above that); reference-counted `open()` / `lease()` lets in-flight streams finish after `snapshot.close()` while rejecting new leases (`STALE_REFERENCE`).
   - `InputPolicy.DEFAULT` (`InputPolicy.java:6`): 8 MiB max JSON file, 256 MiB max regular file, 1 GiB max total per snapshot, 50,000 max files, max JSON depth 64, 1 MiB memory threshold.
2. **Registration view layer (`modules/data-driven`, `lib.kasuga.registration.data_driven.source`)**: `ContentSource` exposes a single captured root to `DataDrivenBuildPipeline`.

### 17.2 ContentSource and supporting value types

`ContentSource.java:16-66`:

```java
public interface ContentSource extends AutoCloseable {
    SourceDescriptor descriptor();                                // :17
    Optional<InputStream> open(LogicalPath path) throws IOException;  // :24
    boolean exists(LogicalPath path);                             // :26
    List<LocatedFile> files();                                    // :31 immutable, path-sorted
    default List<LocatedFile> indexes()                           // :37 data/<ns>/kasuga_lib/data_driven/*.json
    default InputStream requireOpen(LogicalPath path) throws IOException; // :44 throws NOT_FOUND if absent
    @Override default void close() throws IOException
}
```

Supporting types:

- `SourceDescriptor` (`SourceDescriptor.java:20-112`):
  - Platform factory: `SourceDescriptor.of(ResourceSourceDescriptor resource, Collection<String> namespaces, String diagnosticLabel)` (`:78-84`), preserving `rootId()`, `rank()`, `origin()`, and `resource()`.
  - Legacy adapter factory: `SourceDescriptor.of(String sourceId, String ownerId, Collection<String> namespaces)` (`:74-76`), with `resource() == null` and `rank() == 0`.
  - `namespaces` must be non-empty, sorted, and match `[a-z0-9_.-]+`.
- `LogicalPath` (`LogicalPath.java:15-83`): relative slash-separated literal path; rejects blank, leading `/`, `\`, Windows drive prefixes, empty segments, `.` / `..`, NUL, and ISO control characters.
- `LocatedFile` (`LocatedFile.java:11-47`): carries `source`, `path`, `size`, plus snapshot `uri` (`ResourceUri`), SHA-256 `revision`, and `tags`.
- `SourceLimits` (`SourceLimits.java:6-36`): byte/file limits for legacy adapters (`DEFAULT` matches `InputPolicy.DEFAULT`).
- `SourceAccessException` (`SourceAccessException.java:9-90`): typed `IOException` carrying `Reason` (`NOT_FOUND`, `ACCESS_DENIED`, `OUT_OF_BOUNDS`, `SYMLINK_REJECTED`, `DUPLICATE_ENTRY`, `INVALID_ENTRY`, `LIMIT_EXCEEDED`, `CORRUPT_ARCHIVE`, `SOURCE_CLOSED`, `IO_ERROR`).

### 17.3 ContentSourceFactory

`ContentSourceFactory.java:21-140`:

| Method | Behavior | Intended use |
|------|------|---------|
| `snapshot(ResourceSnapshot, ResourceSourceDescriptor, Collection<String> namespaces, String diagnosticLabel)` (`:52`) | Wraps a captured snapshot as `SnapshotContentSource` (default index scan) | **v2 production path** |
| `snapshot(snapshot, resource, namespaces, diagnosticLabel, Collection<String> declaredIndexes)` (`:61`) | Same, restricting `indexes()` to host-declared logical paths (Scene indexing) | **v2 production path** (e.g. parcel roots) |
| `directory(Path, SourceDescriptor[, SourceLimits])` (`:25,29`) | Opens a filesystem directory (rejects symlinks / special files) | Legacy / plain-JVM tests |
| `zip(Path, SourceDescriptor[, SourceLimits])` (`:34,38`) | Opens a ZIP/JAR archive | Legacy / tooling |
| `modJar(IModFile, SourceDescriptor[, SourceLimits])` (`:43,47`) | Opens a NeoForge `IModFile` without closing shared secure-JAR FS | Legacy |
| `open(Path, SourceDescriptor[, SourceLimits])` (`:80,84`) | Auto-selects `directory` or `zip` from physical path | Legacy / CLI |

### 17.4 SnapshotContentSource

`SnapshotContentSource.java:23-145`:

- Factory `SnapshotContentSource.open(...)` (delegated to by `ContentSourceFactory.snapshot(...)`).
- Construction checks:
  - every file in `snapshot` must match the supplied `ownerId`, `sourceId`, and `rootId`, otherwise `IllegalArgumentException` (`:52-57`);
  - duplicate `logicalPath` values are rejected (`:43-47`);
  - files are sorted by `path` and retain `uri`, `revision`, `tags`, and `size`.
- **`declaredIndexes` semantics** (`:40, 121-129`):
  - `null`: falls back to `ContentSource.super.indexes()`, discovering `data/<declared-ns>/kasuga_lib/data_driven/*.json`;
  - non-null collection: filters `indexes()` to files whose path is in `declaredIndexes` (an empty collection explicitly yields zero manifests for pure asset/data roots). Does **not** prune `files()` or `open()`, and does not validate that declared paths exist.
- **Ownership & close semantics**: `SnapshotContentSource` owns the supplied `ResourceSnapshot` and calls `snapshot.close()` on `close()` (`:132-137`). Calling `open`, `exists`, or `files` after close throws `IllegalStateException` (`:139-144`). Never pass the same `ResourceSnapshot` to two owners; capture separate snapshots from a `FixedResourceSource` when sharing bytes across consumers.

---

## 18. v2 Multi-Source Build Pipeline: `DataDrivenBuildPipeline` and Hooks

`pipeline/DataDrivenBuildPipeline.java:54-1644` orchestrates discovery, strict JSON decoding, definition selection, cross-source validation, registry claim checks, `apply`, and finalizers across a `prepare` / `build` boundary.

### 18.1 Construction and options

```java
public DataDrivenBuildPipeline(
        String hostModId,
        Collection<? extends TypeHandler<?>> handlers,
        Collection<? extends BuildFinalizer> finalizers)   // :64

public DataDrivenBuildPipeline(
        String hostModId,
        Collection<? extends TypeHandler<?>> handlers,
        Collection<? extends BuildFinalizer> finalizers,
        RegistrationTarget registrationTarget,
        Collection<String> reservedSymbols)                // :72

public DataDrivenBuildPipeline(
        String hostModId,
        Collection<? extends TypeHandler<?>> handlers,
        Collection<? extends BuildFinalizer> finalizers,
        RegistrationTarget registrationTarget,
        Collection<String> reservedSymbols,
        Collection<RegistryClaim.RegistryKey> reservedRegistryClaims)  // :89
```

- The pipeline does **not** automatically read `TypeHandlerRegistry` or `DataDrivenBuildHooks`: hosts must pass explicit `handlers` and `finalizers` (for example, calling `JsonTreeBuilder.ensureHandlersRegistered()` then passing `TypeHandlerRegistry.all()`, plus `DataDrivenBuildHooks.finalizersFor(hostModId)`).
- Handler list order breaks ties within the same `getPhase()`; in `STRICT` mode, duplicate `getTypeName()` entries raise `HANDLER_NOT_STRICT_READY`.
- `BuildOptions` (`BuildOptions.java:6-20`):
  - `BuildOptions.LEGACY`: Gson tolerant parsing, `EffectiveId.ofLegacy`, skips strict capabilities and `validate`, does not reject `build` on `hasBlockingDiagnostics()`;
  - `BuildOptions.STRICT` (`isStrict() == true`): `StrictResourceJson`, `EffectiveId.of`, strict capability checks, full `validate` and claim conflict detection, and `REJECTED` before apply when any `Severity.ERROR` diagnostic exists.

### 18.2 `prepare` stage and `PreparedContent`

```java
public PreparedContent prepare(Collection<? extends ContentSource> sources, BuildOptions options)
public PreparedContent prepare(Collection<? extends ContentSource> sources, BuildOptions options,
                               Map<String, ? extends Collection<String>> dependencies)
public PreparedContent prepare(Collection<? extends ContentSource> sources, BuildOptions options,
                               Map<String, ? extends Collection<String>> dependencies,
                               Collection<String> sharedAssetOwners)  // :111-143
```

Parameters:

- `sources`: content sources. Platform sources (`descriptor.resource() != null`) are ordered by their explicit non-negative `rank` as `sourceOrder`; only legacy 4-arg `SourceDescriptor` instances (`resource() == null`) derive order from collection iteration index.
- `dependencies`: direct owner-to-owners dependency map (not transitive). Unknown dependency owners emit `UNKNOWN_DEPENDENCY` (`ERROR` in `STRICT`, `WARNING` in `LEGACY`, `:411-461`).
- `sharedAssetOwners`: owners whose `ASSETS` domain files are visible to every source in `ValidationContext.resourceExists(ResourceDomain.ASSETS, ...)` without exposing their `DATA` files or definitions (`:158-164`).

Execution order of `prepare` (never calls `TypeHandler.apply`):

1. Check `(sourceId, rootId)` uniqueness and global `rank` uniqueness (`INVALID_SOURCE` on duplicates, `:368-409`).
2. Normalize and validate `dependencies` (`:411-461`).
3. Per source, read `indexes()`, aggregate manifests per namespace (D1–D6), parse `on_register` files (`parse` + `resolveIdentity(hostModId, parsed, options.isStrict())`), and collect `on_reload` paths (`:463-511, 764-917`). Unexpected per-source `RuntimeException`s are isolated as `SOURCE_READ_FAILED` (`:173-185`).
4. Preflight-decode `on_reload` files using `ReloadHandlerRegistry.get(field)` (`validateReloadContentFile`, `:919-1025`) without calling `clearBucket()` or `register()`.
5. In `STRICT` mode, verify every used handler declares `STRICT_PREFLIGHT`, returns `true` from `strictValidationImplemented()`, and declares XOR of `NON_REGISTRATION` / `REGISTRY_CLAIMS` (`:1101-1147`).
6. Select definitions (`selectDefinitions`, `:1149-1183`): `OVERLAY` or `STRICT && REGISTRY_CLAIMS` keep every candidate per `DefinitionKey`; all others keep the highest `(sourceOrder, declarationOrder)` winner.
7. In `STRICT` mode, run `validate(definition, PipelineValidationContext)` in deterministic order (`:1185-1226`).
8. Detect `parentDefinition` cycles (`GROUP_CYCLE`: `ERROR` in `STRICT`, `WARNING` in `LEGACY`, `:1233-1297`).
9. Collect `claims` from `REGISTRY_CLAIMS` handlers and check collisions against `reservedRegistryClaims` and other claims (`CLAIM_CONFLICT` / `CLAIM_FAILED`, `:1304-1401`).

Output `PreparedContent` (`PreparedContent.java:14-81`):

```java
public record PreparedContent(
        String hostModId,
        BuildOptions options,
        List<SourceDescriptor> sources,
        List<LocatedDefinition<?>> definitions,
        List<LocatedDefinition<?>> allDefinitions,
        List<LocatedContentPath> registerFiles,
        List<LocatedContentPath> reloadFiles,
        List<LocatedIndexPath> indexFiles,
        List<RegistryClaim> claims,
        List<BuildDiagnostic> diagnostics,
        Map<String, Set<String>> dependencies,
        Set<String> reservedSymbols
) {
    public boolean hasBlockingDiagnostics()  // true when any diagnostic has Severity.ERROR
}
```

Each definition is wrapped in `LocatedDefinition<T>` (`LocatedDefinition.java:22-45`), carrying `key` (`DefinitionKey`), `handler`, `definition`, `source` (`SourceDescriptor`), `sourceOrder`, `logicalPath`, `jsonPointer`, and `declarationOrder`.

### 18.3 `build` stage, `BuildResult`, `BuildFinalizer`, and `RegistrationTarget`

```java
public BuildResult build(PreparedContent prepared)                             // :231
public BuildResult build(PreparedContent prepared, RegistrationTarget target)  // :241
```

- Verifies `prepared.hostModId()` matches `hostModId`.
- If `prepared.options().isStrict() && prepared.hasBlockingDiagnostics()`, returns `Status.REJECTED` immediately (`rootGroup == null`, zero apply/finalizer side effects).
- Creates `JsonRegistryGroup(hostModId + ":json_root")` with default namespace rewrite to `hostModId`, then creates `RegBuildContext` via `target.createContext(hostModId, root, prepared.options())` (`:260-266`).
- Applies each selected definition inside `context.withCurrentDefinition(definition, ...)` in `stableApplyOrder` (`:1411-1477`):
  1. `TypeHandler.getPhase()` ascending;
  2. first index of `handler` in `handlers`;
  3. `sourceOrder` ascending;
  4. `logicalPath` lexicographically;
  5. `jsonPointer` lexicographically;
  6. `declarationOrder` ascending;
  7. topological parent-before-child ordering from `parentDefinition`.
- Runs `finalizers` in order (`BuildFinalizer.afterBuild(BuildContext, PreparedContent)`, `BuildFinalizer.java:9-11`), collecting their return values.
- Any `RuntimeException` thrown during context creation, ordering, `apply`, or a finalizer stops execution and returns `Status.FAILED` (carrying the possibly partially populated `rootGroup` and `cause`, with **no automatic rollback**).

`BuildResult` (`BuildResult.java:12-50`):

```java
public record BuildResult(
        String hostModId,
        Status status,
        @Nullable JsonRegistryGroup rootGroup,
        List<BuildDiagnostic> diagnostics,
        List<Object> finalizerResults,
        @Nullable Throwable cause
) {
    public enum Status { REJECTED, APPLIED, FAILED }
    public boolean successful() { return status == Status.APPLIED; }
    public boolean applied()    { return status == Status.APPLIED; }
}
```

> **Note**: both `BuildResult.successful()` and `BuildResult.applied()` return `true` **only** when `status == Status.APPLIED` (both return `false` on `FAILED`, even if earlier `apply` calls already mutated the context; pinned by `BuildResultTest.onlyAppliedStatusCountsAsApplied`).

`RegistrationTarget` (`RegistrationTarget.java:10-17`): `RegistrationTarget.standard()` constructs `new RegBuildContext(hostModId, rootGroup, options.isStrict())`; hosts can supply custom subclasses.

`RegistryClaimSnapshot` (`RegistryClaimSnapshot.java:15-39`):

```java
public static Set<RegistryClaim.RegistryKey> collect(Reg<?, ?> root)  // :19
```

Runs BFS from `root`, skipping any `RegistryGroup` (and its subtree) where `isDispatchEnabled()` is `false`, and collects `MinecraftDeferRegistryReg` entries as `RegistryKey`s for passing into `reservedRegistryClaims`.

### 18.4 Process-wide hooks: `DataDrivenBuildHooks`

`DataDrivenBuildHooks.java:12-36`:

```java
public static void registerFinalizer(String hostModId, BuildFinalizer finalizer)  // :16
public static List<BuildFinalizer> finalizersFor(String hostModId)                // :23
```

Registers session-end `BuildFinalizer`s keyed by `hostModId` (`ConcurrentHashMap` + `CopyOnWriteArrayList`). There is no unregister API, so callers must guard against duplicate registration, and host providers must explicitly pass `DataDrivenBuildHooks.finalizersFor(hostModId)` when constructing `DataDrivenBuildPipeline`.

### 18.5 v1-to-v2 migration summary

| Concern | v1 legacy entry (`JsonTreeBuilder`) | v2 multi-source pipeline |
|---|---|---|
| Registration hook | `JsonTreeIntegration` falls back to `buildForMod(modId)` | `DataDrivenRegistrationProviders.install(modId, provider)` |
| Source & snapshot | Scans mod JAR files directly | `ResourceSources.fixed` → `ResourceSnapshot` → `ContentSourceFactory.snapshot` |
| Index selection | Scans all `data/<mod>/kasuga_lib/data_driven/*.json` | Default scan, or exact `declaredIndexes` (Scene indexing) |
| JSON decoding | Tolerant Gson | `BuildOptions.STRICT` uses `StrictResourceJson` (rejects duplicate keys, comments, trailing commas, malformed UTF-8) |
| `minecraft:` ids | `EffectiveId.ofLegacy` rewrites to `<hostModId>:path` | `STRICT` uses `EffectiveId.of` preserving `minecraft:` and rejects bare ids |
| Diagnostics | `JsonTreeBuilder.getLoadingErrors` / `Diagnostics` / `/kasuga_data errors` | `PreparedContent.diagnostics()` / `BuildResult.diagnostics()` (`BuildDiagnostic`) |


> Other pages in the data-driven section: [Registration-phase API](api-registration.md) · [Extension API](api-extension.md) · [Diagnostics API](api-diagnostics.md) · [Reload API](api-reload.md) · [Section index](README.md)
