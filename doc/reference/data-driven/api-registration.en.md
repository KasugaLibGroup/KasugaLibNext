# Data-driven registration-phase API (JsonTreeBuilder, index, and content dispatch)

> This page is a reference (for looking things up). Its subject is the KasugaLibNext data-driven Java API: classes, methods, signatures, call timing, and failure behavior.
> Applies to: Minecraft 1.21.1 / NeoForge 21.1.203 / Parchment 2024.11.17 / Java 21. Line numbers on this page refer to the current working tree (the uncommitted data-driven reimplementation), baseline commit `ec864a1`; signatures evolve with the code, so references are written as `class:line` and can be checked back against the code.
> Boundary: which key a JSON field is written under, what type it has, and whether it is required all belong to `schema.md`; the getting-started tutorial belongs to `intro.md`, and the steps for content authors and extension authors belong to `guide-content.md` / `guide-extension.md`. This page mentions field names only at the level of "which method reads it, and what happens when the read fails".
> Terminology: a **domain** is one of the two arrays in the index manifest, `on_register` (registration domain) and `on_reload` (reload domain); a **bucket** is a list holding diagnostics, split along two dimensions: the **mod dimension**, split by mod id (registration-phase errors), and the **domain/source-key dimension**, split by `(Domain, key)` (reload-phase errors use `RELOAD_DATA[<namespace>]`).

## 1. Module and package structure

Code root: `modules/data-driven/src/main/java/lib/kasuga/registration/data_driven/`.

```text
data_driven/
├── TypeHandler.java                 # 处理器接口：顶层/内嵌类型、phase、identity
├── TypeHandlerRegistry.java         # 全局静态处理器表（LinkedHashMap 保序）
├── JsonTreeIntegration.java         # 与 RegisterContextRegistry 的接线点
├── builder/
│   └── JsonTreeBuilder.java         # 注册期入口：索引解析、内容分派、去重、apply、错误桶
├── context/
│   ├── BuildContext.java            # 构建上下文基类（modId / rootGroup / meta 存取）
│   ├── RegBuildContext.java         # 注册场景上下文（group / reg 存取）
│   └── JsonRegistryGroup.java       # JSON 创建的分组（RegistryGroup 子类）
├── handler/
│   ├── MetaTypeHandler.java         # 标记基类，无附加行为
│   ├── RegistryGroupHandler.java    # registry_groups，PHASE_GROUPS
│   ├── RegTypeHandler.java          # 注册类 handler 抽象基类（blocks/items 共用）
│   ├── BlockTypeHandler.java        # blocks，PHASE_CONTENT
│   ├── ItemTypeHandler.java         # items，PHASE_CONTENT
│   └── BlockEntityTypeHandler.java  # block_entities（内嵌于 blocks），PHASE_EMBEDDED
├── dedup/
│   ├── DuplicateIdResolver.java     # 纯函数 last-wins 去重
│   └── EffectiveId.java             # id 归一化
├── reload/                          # 重载域：扩展点 + 编排器
│   ├── ReloadHandler.java           # 扩展接口：一种 reload 类型的 decode/register
│   ├── ReloadHandlerRegistry.java   # 全局静态 handler 表（LinkedHashMap 保序）
│   ├── ReloadOrchestrator.java      # 重载周期编排器：清桶/发现/去重/注册/收尾
│   ├── Decoded.java                 # 一次 decode 的结果（entries + errors）
│   └── Reloaded.java                # 注册落地条目 + 来源命名空间
├── diagnostics/
│   ├── Diagnostics.java             # 诊断门面（mod 维 + 域/源键维）
│   └── DataDiagnosticsCommands.java # /kasuga_data errors [mod]
└── property/
    ├── JsonPropertyParser.java      # 方块属性解析
    ├── JsonItemParser.java          # 物品属性解析
    └── compiler/                    # 自定义属性匹配器
```

The orchestration and decoding of the reload domain live in two places:

- `modules/data-driven/src/main/java/lib/kasuga/registration/data_driven/reload/` (the orchestrator `ReloadOrchestrator`, the extension points `ReloadHandler` / `ReloadHandlerRegistry` / `Decoded` / `Reloaded`)
- `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/dynamic/fsm/` (the consumers: the two handlers `FsmReloadHandler` / `FsmClipsReloadHandler`, the registrar bean `FsmReloadHandlerRegistrar`, and the pure decoders `StateMachineDefinitionLoader.java` and `AnimationClipLoader.java`)
- `modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/fsm/FsmAnimationClips.java` and `FsmDefinitions.java` (bucketed storage)

Dependency direction: `modelling → data-driven`. data-driven does not depend on modelling.

## 2. Registration-phase entry point: JsonTreeBuilder

`builder/JsonTreeBuilder.java` is the only entry point of the registration domain. All methods are `static`, and the class cannot be instantiated (`:798`).

### 2.1 buildForMod

```java
public static JsonRegistryGroup buildForMod(String modId)   // JsonTreeBuilder.java:61
```

Responsibility: load one mod's `on_register` content, and build and return its root registry group `<modId>:json_root`.

Parameter constraint: `modId` must have a corresponding mod file in FML's `ModList` (internally via `findModFile`, `:723`). When it is not found, `null` is returned and no error is reported.

Execution order (one call):

1. `ensureHandlersRegistered()` (`:52`), idempotent, registers the four built-in handlers once.
2. `clearLoadingErrors(modId)` (`:63`), clears only that mod's bucket.
3. Locate the index directory `data/<modId>/kasuga_lib/data_driven/` (`findIndexDir`, `:776`).
4. Read the index manifests one by one (`listIndexFiles` scans the directory, `readIndexManifest` parses), then aggregate them (`resolveIndexManifests`).
5. Parse only the content files `on_register` points at; the `on_reload` section is recognized and validated here, but not parsed (`:105-110`).
6. Resolve duplicate ids before any apply side effect (`:119`).
7. Apply each handler in ascending `getPhase()` order; `registry_groups` gets an extra topological sort before apply (`:135-146`).
8. Return `rootGroup` (`:148`).

Cases that return `null`:

| Case | Location | Error recorded? |
|------|------|-----------|
| The index directory does not exist, and the old-layout directory `data/<mod>/kasugalib/` does not exist either | `:75-79` | No, only a DEBUG record |
| The index directory does not exist, but the old-layout directory does | `:66-74` | Yes, WARN + bucket entry, hinting at migration to `on_register` |
| The index directory exists, but no manifest declares either domain (an empty directory included) | `:95-99` | Yes; the aggregation stage records "declares neither ... in any index file" |

Note: a mod that declares only `on_reload` does not count as `nothingDeclared()`, so it returns a non-null empty root group (`:100-104` records only INFO), not `null`.

### 2.2 buildAll

```java
public static Map<String, JsonRegistryGroup> buildAll()   // JsonTreeBuilder.java:151
```

Responsibility: walk every mod file in `ModList`, call `buildForMod` for each mod, and collect the non-null results with the mod id as key, preserving `LinkedHashMap` order.

Parameters: none. Constraint: the same mod file path is processed only once (`visitedRoots`, `:159`).

Failure behavior: when a single mod throws, the `catch` records an ERROR and puts the exception wrapped in an `IllegalStateException` into that mod's bucket (`:168-172`), then carries on with the next mod.

Note: there is no call site for `buildAll()` anywhere in the repository (only its declaration); the actual wiring currently goes through `buildForMod`. Whether it is a reserved API is to be confirmed.

### 2.3 When JsonTreeIntegration is wired up

`JsonTreeIntegration` (`JsonTreeIntegration.java:13`) is a Micronaut `@Context` bean. `@PostConstruct` (`:21`) registers a **COMMON-side callback** with `RegisterContextRegistry`; the callback body calls `JsonTreeBuilder.buildForMod(modRegistry.getModId())` and, when the result is non-null, attaches it as a child node of `modRegistry` (`:24-33`).

The callback fires lazily: it runs only at the first registration-event dispatch for that mod. The guarantee this gives is that every `@Context` static factory registration precedes JSON parsing, without relying on explicit ordering.

### 2.4 Phase semantics

| Constant | Value | Handler that uses it | Reason |
|------|-----|-----------------|------|
| `TypeHandler.PHASE_GROUPS` | 0 | `registry_groups` | Groups must exist before content |
| `TypeHandler.PHASE_CONTENT` | 1 | `blocks`, `items` | Regular content |
| `TypeHandler.PHASE_EMBEDDED` | 2 | `block_entities` | A BE has to attach to an already existing block Reg |

Within the same phase the order is registration order (`TypeHandlerRegistry` uses a `LinkedHashMap`). The application order of `registry_groups` is also topologically sorted by `parent` reference via `topoSortGroupDefs` (`:869`); when there is a cycle, the `Cycle detected among registry groups` WARN is logged, the nodes on the cycle apply in their original order, and `RegistryGroupHandler.store` falls back to the root group for a parent it cannot find. A reference cycle therefore loses only hierarchy, never a group.

## 3. Index API

Index directory: `data/<modId>/kasuga_lib/data_driven/`. Every `.json` under it is a manifest, and a manifest declares only content-file paths, never types.

### 3.1 IndexManifest

```java
public record IndexManifest(boolean declaredOnRegister, boolean declaredOnReload,
                            List<String> onRegisterPaths, List<String> onReloadPaths)
// JsonTreeBuilder.java:344
```

The parsed result of one manifest, not yet consumed. `empty()` (`:347`, package-visible) returns a "nothing declared" instance, used when the manifest file as a whole cannot be read.

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
// JsonTreeBuilder.java:362
// public boolean nothingDeclared()   // :365
```

The result of aggregating all of one mod's manifests and removing cross-domain conflicts. `nothingDeclared()` is the D4 test: when it is true, no manifest ever declared either domain, so the caller must return immediately and register nothing.

### 3.3 parseIndexManifest

```java
public static IndexManifest parseIndexManifest(String modId, String label, JsonObject root)
// JsonTreeBuilder.java:222
```

Responsibility: parse one in-memory manifest, consuming no file. It has no NeoForge dependency and can be called from pure JVM tests.

Constraints: a `null` `root` is treated as an empty file. Only `on_register` and `on_reload` are recognized; any other top-level field goes down the unknown-field path and is reported (see 3.6). The return value is never `null`.

Failure behavior: every violation is recorded as an ERROR and put into the `modId` bucket, with no exception thrown; within one call, one broken field does not implicate its sibling fields (D3).

### 3.4 resolveIndexManifests

```java
public static ResolvedIndex resolveIndexManifests(String modId, List<IndexManifest> manifests)
// JsonTreeBuilder.java:294
```

Responsibility: aggregate all of one mod's manifests, run the cross-domain fail-closed check, and return the paths each domain can consume. A pure function, testable.

Constraint: `manifests` is passed in load order, and the aggregation keeps that order.

Failure behavior:

- The same path appearing in both `on_register` and `on_reload` (within one manifest or across manifests, both count) is reported as an ERROR and put into the bucket (D6), and is removed from **both domains**, so neither consumes it.
- When no manifest declares either domain, one aggregate WARN is recorded plus a bucket entry (D4), and the returned `nothingDeclared()` is true.

### 3.5 listIndexFiles

```java
public static List<Path> listIndexFiles(Path indexDir, String modId)   // JsonTreeBuilder.java:762
```

Responsibility: list the files under the index directory that end in `.json`, sorted by path string.

Failure behavior: when the directory cannot be listed, an ERROR is recorded and put into the bucket, and an empty list is returned. An empty result is itself a legal result (it means there are no manifests) and is judged separately by `buildForMod`.

### 3.6 How D1 to D6 show up at the API layer

The rule numbers follow the design document. Each corresponds to one testable behavior:

| Rule | Meaning | Where it lands |
|------|------|------|
| D1 | Both fields are individually optional; when neither appears (an empty file, or a file with unknown fields only) an error is reported and nothing is consumed | `parseIndexManifest:240-244` |
| D2 | An empty array is legal and counts as "declared" | `parseIndexManifest:237`, the `resolveIndexManifests:323` branch |
| D3 | An unknown top-level field is reported and processing continues; a field that is not an array is reported and its sibling fields are still processed | `parseIndexManifest:226-230`, `readPathArray:256-277` |
| D4 | The aggregate warning branches on "which field was declared", not on the entries that were parsed out | `resolveIndexManifests:323-326` |
| D5 | `sources` is not an alias; it is reported as an unknown field | goes through `parseIndexManifest:226-230` |
| D6 | A path listed in both arrays fails closed, and neither domain consumes it | `resolveIndexManifests:308-316` |

`readPathArray` (`:256`) is private: a field that exists but is not an array, or an array element that is not a string, each records one ERROR and a bucket entry, without implicating sibling fields.

### 3.7 The deprecated field

The old index field was named `sources`. After the hard migration (D5) it is no longer a legal field: writing `sources` goes down the unknown-field path, reports `contains unsupported field 'sources'` and puts a bucket entry in, and that manifest declares no domain. The only legal domain fields today are `on_register` and `on_reload`.

### 3.8 Path contract

```java
public static String validateSourcePath(String path)              // JsonTreeBuilder.java:383
public static String[] indexDirectorySegments(String modId)       // JsonTreeBuilder.java:745
```

`validateSourcePath`: content-file path validation. Returning `null` means legal; otherwise it returns a human-readable reason. Rules: non-empty, no leading `/`, ends in `.json`, no empty segment, no `.`/`..` segment. A path is resolved relative to `data/<mod>/`. A pure function with no NeoForge dependency.

`indexDirectorySegments`: returns the segment array of the index directory relative to the mod file root, fixed at `{"data", modId, "kasuga_lib", "data_driven"}`. Every call returns a **new array**, so a caller's modification cannot pollute other callers. `ReloadOrchestrator.indexResourcePath` drops the first two segments to get the pack-stack path `kasuga_lib/data_driven`.

## 4. Content dispatch and apply

### 4.1 parseSource (private)

`parseSource` (`:395`) reads and parses one content file by path:

1. Validate with `validateSourcePath`; if illegal, record an ERROR and a bucket entry, then return.
2. Deduplicate by path string using the `parsedSources` set; the same content file is parsed only once per `buildForMod`, even when several manifests reference it.
3. Locate the mod file with `findModFile`, concatenate `data/<modId>/<sourcePath>`, and read it.
4. A missing file records a `Source file not found` ERROR and a bucket entry.
5. A read/parse exception records an ERROR and a bucket entry (`:431-434`).
6. Hand it to `dispatchContent`.

### 4.2 dispatchContent (private)

`dispatchContent` (`:461`) dispatches a content file to handlers by top-level field:

- First collect the names of all handlers with `getParentTypeName() == null` into `knownFields`, and put every embedded handler into `embeddedByField` keyed by typeName.
- Every top-level field not in `knownFields` is reported as an error and put into the bucket. If that field name happens to be an embedded type, the message adds a hint naming which key of which parent entry it should be written into (`:475-490`).
- Each unknown-field message also appends a conditional hint (D9): if these fields belong to reload-domain content (written as a `state_machines` array, for instance), list the file under `on_reload` instead (`:483-490`).
- For every known top-level handler: the field must be an array and its elements must be objects; otherwise each is reported as an error, put into the bucket, and skipped.
- Embedded types enter only through `extractEmbedded(parentJson)` and do not take a top-level dispatch slot (`:528-535`).

### 4.3 parseContentBody

```java
public static Map<String, List<Object>> parseContentBody(String modId, String contentLabel, JsonObject root)
// JsonTreeBuilder.java:620
```

Responsibility: the in-memory half of the content pipeline. `parseSource` handles location and reading, and hands the body to it once read. Returns an immutable map grouped by "handler typeName to definition list", preserving dispatch order.

Constraints and failure behavior: parse and collect only; no registration, no apply. Every malformed input is reported through the bucket rather than thrown; the caller gets back the part that could still be parsed (partial-apply semantics). No NeoForge dependency, so it can be called directly from pure JVM tests.

### 4.4 applyUnchecked

```java
public static void applyUnchecked(TypeHandler handler, Object definition, BuildContext context, String modId)
// JsonTreeBuilder.java:712
```

Responsibility: run one handler.apply and turn an exception into a bucket diagnostic.

Constraint: `buildForMod` calls it for every surviving definition. On an exception it records the `Failed to apply '<type>' handler for mod '<mod>'` ERROR and puts the wrapped `IllegalStateException` into the bucket (the original exception is kept as the cause). It is public because the contract "a failed apply must leave a trace" could otherwise only be exercised by a full mod load.

## 5. Conflict resolution

Duplicate ids are resolved before any apply side effect, so that `MappedRegistry` never sees a duplicate key.

### 5.1 DuplicateIdResolver

`dedup/DuplicateIdResolver.java`, a pure function with no Minecraft types.

```java
public record Candidate(String typeName, String identity, String sourcePath, Object payload)  // :39
public record Conflict(Candidate loser, Candidate winner)                                     // :47
public record Result(List<Candidate> winners, List<Conflict> conflicts)                       // :65
public static Result resolve(List<Candidate> candidates)                                      // :73
public static String describe(Conflict conflict)                                              // :109
public static Throwable toLoadingError(Conflict conflict)                                     // :124
```

- A `null` `Candidate.identity` opts out of deduplication: it is always a winner and conflicts with no entry.
- The conflict key is `(typeName, identity)`. Different `typeName` values never conflict.
- `resolve` is last-wins: for the same key it takes the one appearing last in the candidate sequence. The sequence order is the load order, and the method does not sort internally.
- `describe` / `toLoadingError` produce human-readable messages, containing the type field, the identity, and the source file of the loser and of the winner.

### 5.2 EffectiveId

```java
public static String of(String modId, String rawId)   // EffectiveId.java:39
```

Responsibility: normalize a raw id to the `ResourceLocation` string it finally registers under, for deduplication comparison.

| Raw id | Normalized result |
|---------|---------|
| `foo` | `<modId>:foo` |
| `minecraft:foo` | `<modId>:foo` |
| `othermod:foo` | `othermod:foo` (verbatim) |

When `rawId` is `null`, returns `null`. It splits namespace and path on the first `:` only.

### 5.3 Per-type identity

| Type | identity | Source |
|------|----------|------|
| `blocks` / `items` | the normalized effective id | `RegTypeHandler.resolveIdentity` (`RegTypeHandler.java:58`) |
| `block_entities` | the host block's effective id | `BlockEntityTypeHandler.resolveIdentity` (`:66`) |
| `registry_groups` | the raw `id` string, not normalized | `RegistryGroupHandler.resolveIdentity` (`:50`) |
| A custom handler | `TypeHandler.resolveIdentity` returns `null` by default, i.e. it takes no part in deduplication | `TypeHandler.java:64` |

Why `registry_groups` is not normalized: groups do not go into an MC registry, and the loader references them by raw string everywhere (a block's `registry_group` lookup, `store`'s `putRegistryGroup`), so normalizing only the decision key would produce a mismatch where a duplicate is decided but the reference cannot be found.

### 5.4 last-wins order

`resolveDuplicates` (`:652`) builds candidates in the load order of `parsed`:

1. Index files by ascending filename;
2. within one manifest, by the writing order of the `on_register` array;
3. within one content file, by the writing order of the type-field arrays;
4. embedded `block_entities` follow the position of the host `blocks` entry.

Result: a loser never enters apply at all, and the registry sees the winner exactly once; each conflict records one WARN and goes into that mod's bucket as an `IllegalStateException`; there is also a summary `Resolved N duplicate id(s)` WARN. When identity resolution fails it degrades to `null` and does not escape (`identityOf`, `:691`).


> Other pages in the data-driven section: [Registration-phase API](api-registration.md) · [Extension API](api-extension.md) · [Diagnostics API](api-diagnostics.md) · [Reload API](api-reload.md) · [Section index](README.md)
