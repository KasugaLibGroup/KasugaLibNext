# Data-Driven Reload API (Including the Assertable Pure-Function Surface and Call Examples)

## 13. Reload API

The reload domain is orchestrated by data-driven's `ReloadOrchestrator`, and the extension point is `ReloadHandler` (symmetric with `TypeHandler` in the registration phase). The orchestrator does not know any type name — it walks `ReloadHandlerRegistry` and treats each handler's `typeName()` as a legal top-level key.

### 13.1 ReloadHandler (the Extension Point)

`reload/ReloadHandler.java:41`. One handler owns the decode/register of one reload type; functionally it corresponds to that type's "old decoder + registration step + wrap-up validation".

```java
public interface ReloadHandler<T> {
    String typeName();                                    // :50 顶层键，兼作去重碰撞空间
    default String describeFile() { return "reload-domain file"; }  // :60 decode 诊断里的名词
    default Set<String> globDirectories() { return Set.of(); }      // :72 默认空 = 仅索引
    void clearBucket();                                   // :85 每周期一次，register 前
    Decoded<T> decode(JsonObject root);                   // :97 纯解码，转调现有 decodeFile
    void register(T payload);                             // :107 last-wins 胜者注册
    default void afterReload(List<Reloaded<T>> registered) {}  // :117 跨类型校验
}
```

| Method | Responsibility and constraints | Failure behavior |
|------|-----------|---------|
| `typeName()` | The top-level JSON key it claims, e.g. `"state_machines"`; unique in the registry, and also the collision space for deduplication | A later registration under the same name replaces the earlier one in place |
| `describeFile()` | The noun used in decode diagnostics, e.g. `"state machine file"` → `"Failed to decode state machine file '<path>'"` | Defaults to `"reload-domain file"`; a handler whose diagnostic text is pinned by a test should override it |
| `globDirectories()` | Extra directories under `data/<ns>/` discovered by directory glob; default empty = index-only | Return an ordered collection so that discovery order is preserved |
| `clearBucket()` | Once per cycle, before discovery. **Drops only the reload source** ("script wins"), so it delegates to the bucket's `clearResource()` | An exception is isolated in place by the orchestrator (`runClear`, `:250-261`) and recorded under `Domain.RELOAD_DATA`; the other handlers carry on |
| `decode(root)` | `root` is guaranteed to be a JSON object. **Must not read files** (the orchestrator owns I/O) and **must not throw for malformed content** (return it as `Decoded.errors()`) | A thrown exception is isolated per file and recorded by the orchestrator |
| `register(payload)` | Called only for the last-wins winner, and only after the whole discovery round has finished | You should throw (the orchestrator isolates it and attributes it by namespace); do not swallow |
| `afterReload(registered)` | Called once every handler's registration stage is done, and only for the entries registered this round; cross-type validation belongs here | A thrown exception is attributed to the library mod (`KasugaLib.MODID`) and does not block later handlers |

### 13.2 ReloadHandlerRegistry

`reload/ReloadHandlerRegistry.java:27`. `ReloadHandlerRegistry` is the reload-side counterpart of `TypeHandlerRegistry`: static, indexed by `typeName()`, order-preserving via `LinkedHashMap`.

```java
public static void register(ReloadHandler<?> handler);       // :37
public static ReloadHandler<?> get(String typeName);         // :45
public static Collection<ReloadHandler<?>> all();            // :50 不可变视图，保注册顺序
public static void clearForTest();                           // :58 仅测试，生产不调
```

- **Order carries weight**: the orchestrator walks `all()` to decide the dispatch order and the register order, and together they decide cross-type last-wins. If you need a specific order (for example state machines before clips), register in that order.
- **Registration is idempotent by type name**: registering the same `typeName` again replaces it in place and keeps the original position; a `@Context` bean re-running in tests therefore does not produce duplicate keys.
- `clearForTest()` is a hook for tests (`TypeHandlerRegistry` has no counterpart, so handlers registered by a test leak into later tests, a documented design pitfall); production code does not call it.

### 13.3 Decoded / Entry

`reload/Decoded.java:19`. The result of one `decode`: the decoded entries plus human-readable diagnostics.

```java
public record Decoded<T>(List<Entry<T>> entries, List<String> errors) {   // :19
    public record Entry<T>(String id, T payload) {}                        // :28
    public static <T> Decoded<T> empty();                                  // :36
}
```

- `Entry.id` is the entry's effective id within the handler type (the key for the deduplication decision); payload is the value handed back to `register`. The handler picks the identity itself; the orchestrator never knows how a handler assembles the id.
- Whole file rejected → only `errors`; `entries` is empty.

### 13.4 Reloaded

`reload/Reloaded.java:14`. A payload that passed deduplication and has been handed to `register`, plus the namespace it came from. The orchestrator carries it through the wrap-up stage so that the handler's `afterReload` can attribute diagnostics to the mod that produced the entry — a generic handler cannot get that namespace from the payload itself.

```java
public record Reloaded<T>(String modId, T payload) {}
```

### 13.5 ReloadOrchestrator

`reload/ReloadOrchestrator.java:121`. It implements `ScopedResourceManagerConsumer` and `ScopedResourcePackListener` and is the only orchestrator in the reload domain. The DI entry point `ReloadOrchestrator()` (`:133`) registers into `ResourceSystem` through `init()` (`:155`); the host entry point `ReloadOrchestrator(ReloadHandler<?>...)` (`:145`) first registers the handlers passed in, then runs cycles — tests, or a host that brings its own buckets, use it to bypass the modelling-side registrar bean.

```java
public ReloadOrchestrator()                                      // :133 DI 入口
public ReloadOrchestrator(ReloadHandler<?>... handlers)          // :145 登记后运行
public void reload(ResourceManager resourceManager)              // :186
public static String indexResourcePath(String modId)             // :290
```

- The set of top-level keys is **no longer a hard-coded constant**: it is derived by `knownTypeNames()` (`:473`) from `ReloadHandlerRegistry.all()`. Adding a type = registering a handler, not editing the orchestrator.
- The registration-type examples in the D9 reverse hint are derived by `registrationFieldsExample()` (`:488`) from `TypeHandlerRegistry.all()` (a top-level registration type = a handler with no parent); nothing is invented when the registry is empty.
- `reload(ResourceManager)` is the per-cycle entry point, wrapped in a final fallback catch (`:188-190`): every `RuntimeException` that escapes the guards of the individual stages is attributed to the library's own mod id (`KasugaLib.MODID`) and recorded in a bucket. Because the method sits on the reload listener path and the buckets were already cleared before that stage, exceptions must not escape.
- `indexResourcePath(modId)` drops the first two segments from `JsonTreeBuilder.indexDirectorySegments(modId)` and returns the pack-stack path `kasuga_lib/data_driven`. It shares the same source as the registration side's directory spelling.

The stage order of one cycle (`runCycle`, `:194`):

1. **Build the cycle**: create one `HandlerCycle` per handler in the registry (`:199-201`).
2. **Clear the buckets**: call `runClear(entry)` → `clearBucket()` for each handler, exactly once per round (`:213-215`; `runClear`, `:250-261`). Only the reload source is cleared, so entries registered by a script or by code are untouched; an exception thrown by one `clearBucket()` is isolated in place and recorded, while the other handlers clear as usual.
3. **Aggregate the glob directories**: collect each handler's `globDirectories()` into a deduplicated ordered set (`:222-225`); a directory shared by two handlers is listed once, and files are routed by their top-level key.
4. **Per-namespace discovery** (`collectNamespace`, `:266`): first `readReloadPaths` reads that namespace's index manifest `on_reload`, then `collectGlob` (`:348-367`) scans the aggregated directories (skipping paths already listed by an index), and finally `collectIndex` (`:369`) reads the files that `on_reload` points to.
5. **Registration stage**: call `register(entry)` for each handler (`:507-526`), in registry order; glob candidates come first and index candidates after (`HandlerCycle.candidates()`, `:595`). Within the same type the later entry is last-wins.
6. **Wrap-up stage**: call `runAfterReload(entry)` for each handler (`:530-538`), only for the entries actually registered this round, and after all the registers have completed — only then can a cross-type validation see another handler's bucket.

**Dispatch** (`dispatch`, `:429`): routing by top-level key. An unknown top-level key is reported as an error first, with the D9 reverse hint attached (`:439-446`); for every non-unknown key, each handler is checked in turn for `root.has(typeName())`, and on a hit the content is `decode`d, wrapping the `Decoded.Entry` into `DuplicateIdResolver.Candidate(typeName, id, label, new Reloaded<>(namespace, payload))`.

**Deduplication** (`resolveAndReport`, `:545`): uses the shared `DuplicateIdResolver`. The loser **never enters the handler's `register`** ("loser never has a side effect"), and each conflict logs a WARN plus one bucket entry, attributed to the mod the loser lives in.

Neither `indexResourcePath` nor `runCycle` has any special dependency beyond Minecraft; `reload` needs a `ResourceManager`.

### 13.6 Exception Guards

Four layers of participant-level guards plus one fallback layer:

| Layer | Scope | Source |
|----|------|------|
| Per handler, bucket clearing | `clearBucket()` throws | `runClear:250-261` |
| Per file | Read and decode exceptions, including the decoder itself throwing | `readAndDispatch:405-415` |
| Per namespace | The discovery mechanism itself (directory glob, manifest read) throws | `collectNamespace:266-288` |
| Per entry | Registering a single entry throws | `register:507-526` |
| Per handler, wrap-up | `afterReload` throws | `runAfterReload:530-538` |
| Fallback | Exceptions none of the above could attribute | `reload:186-193` |

When every entry in a cycle fails, the bucket stays in its cleared state, and that is intentional: empty is the truthful outcome, and the errors are in the diagnostics bucket.

### 13.7 StateMachineDefinitionLoader

It stays in modelling, demoted to a pure file-level decoder: it no longer holds a listener, does not clear buckets, and does not list files (`StateMachineDefinitionLoader.java:14-33`).

```java
public static final String PATH = "state_machines";                     // :39
public static final String FIELD_STATE_MACHINES = "state_machines";     // :42
public record DecodedFile(List<StateMachineDefinition> definitions, List<String> errors) {}  // :55
public static DecodedFile decodeFile(JsonElement json);                  // :67
```

`StateMachineDefinitionLoader`'s directory-listing method **has been removed** (0 callers). The directory glob is now done by `ReloadOrchestrator`'s own `collectGlob` (`:348-367`) using `ResourceManager.listResources`, and `FsmReloadHandler.globDirectories()` (`:69-71`) declares the directories to scan; `StateMachineDefinitionLoader.PATH` (`:39`) is kept so that `globDirectories()` can reference it.

`decodeFile`: the file shape is `{"state_machines": [ <definition>, ... ]}`. A non-object root, a missing top-level key, or a non-array value all reject the whole file and return one error; **extra top-level keys no longer reject the file** (a genuinely unknown key is reported by the orchestrator, and this decoder only ignores it), while a non-object array element or a failed decode skips only that element and the siblings carry on as usual. An empty returned `errors` means the file is clean. It is now called through by `FsmReloadHandler.decode`; the orchestrator no longer knows it directly.

### 13.8 AnimationClipLoader

Likewise a pure file-level component (`AnimationClipLoader.java:14-39`).

```java
public static final String FIELD_ANIMATION_CLIPS = "animation_clips";   // :45
public record DecodedFile(List<AnimationClip> clips, List<String> errors) {}  // :57
public static DecodedFile decodeFile(JsonElement json);                  // :69
public static void register(FsmAnimationClips clips, AnimationClip clip);// :113
```

`decodeFile`: the shape is `{"animation_clips": [ <clip>, ... ]}`, and the failure-isolation rules are the same as in `StateMachineDefinitionLoader.decodeFile`. A clip's id is carried by the element's own `id` field (`AnimationClip.CODEC` reads it), and there is no separate id key; keys the record does not name are ignored by the codec, so comment-like fields can be written. Called through by `FsmClipsReloadHandler.decode`.

`register`: writes the decoded clip into the RESOURCE half of the bucket by calling `clips.registerResource`. When `clips` or `clip` is `null` it is a no-op. It is now called through by `FsmClipsReloadHandler.register`. Animation clip files can arrive only through `on_reload`; there is no `animation_clips/` directory glob.

### 13.9 The Two Modelling-Side Handlers and the Registrar Bean

These three classes live in modelling's `lib.kasuga.rendering.models.mc.dynamic.fsm` package. They are consumers and did not move into data-driven along with the orchestrator:

| Class | typeName | Discovery | Role |
|----|----------|------|------|
| `FsmReloadHandler` (`:42`) | `state_machines` | glob `state_machines/` | `decode` → `StateMachineDefinitionLoader.decodeFile`; `register` writes the RESOURCE half of `FsmDefinitions`; `afterReload` runs the dangling clip check |
| `FsmClipsReloadHandler` (`:24`) | `animation_clips` | index-only (keeps the default empty set) | `decode` → `AnimationClipLoader.decodeFile`; `register` → `AnimationClipLoader.register` |
| `FsmReloadHandlerRegistrar` (`:23`) | — | — | `@Context` + `@PostConstruct` (`:25-31`), registering the two handlers into `ReloadHandlerRegistry` in **fsm → clips** order, bound to the process-level `FsmRegistries.GLOBAL` bucket |

### 13.10 FsmDefinitions and FsmAnimationClips

Both bucketed stores live in `render-core`. Each bucket splits by source into RESOURCE (the reload path, which `clearResource` can drop) and SCRIPT (the runtime/script path, which survives reloads).

`FsmAnimationClips` (`FsmAnimationClips.java:30`):

```java
public enum ClipSource { RESOURCE, SCRIPT }                              // :35
public record Entry(AnimationSampler<?> sampler, Object data, ClipSource source) {}  // :43
public void register(Id id, AnimationSampler<?> sampler, Object data)    // :53
public void registerResource(Id id, AnimationSampler<?> sampler, Object data)  // :66
public Entry get(Id id)                                                  // :87
public boolean remove(Id id)                                             // :92
public void clearResource()                                              // :100
public void clearAll()                                                   // :108
public void clear()                                                      // :117
```

- `register` writes a SCRIPT entry and replaces a same-id RESOURCE entry with a WARN (script wins).
- `registerResource` writes a RESOURCE entry and does not override a same-id SCRIPT entry; a later RESOURCE overrides an earlier one.
- `get` returns `null` when nothing is found; it also returns `null` when the id is `null`.
- `register` / `registerResource` throw `IllegalArgumentException` for a `null` id, sampler, or data (`validate:76`).
- `clearResource` clears RESOURCE only; `clearAll` clears everything; `clear()` is an alias for `clearResource()` and no longer clears code-registered entries.

`FsmDefinitions` (`FsmDefinitions.java:27`) is isomorphic: `register` (SCRIPT, `:59`), `registerResource` (RESOURCE, `:71`), `clearResource` (`:84`), `clearAll` (`:95`), `remove` (`:107`), `get` (`:116`), `hash` (`:126`), `addListener` (`:142`). An identity change (override, removal, clearing) notifies the `InvalidationListener`. `hash(id)` returns the content hash, 0 when absent, for the sync layer's identity check.

script-wins is a write-side invariant: a single map plus the guard in `registerResource`; `get` is an ordinary query, and the result does not depend on registration order.

## 14. The Pure-Function Surface Assertable from Tests

The APIs below have no NeoForge dependency or can be called on a plain JVM; they are exposed specifically so that tests can assert the contract:

| API | What it asserts |
|-----|---------|
| `JsonTreeBuilder.indexDirectorySegments` | The index directory segment array; pins the spelling and the levels |
| `JsonTreeBuilder.validateSourcePath` | Content path rules |
| `JsonTreeBuilder.parseIndexManifest` | Index schema D1 through D5 |
| `JsonTreeBuilder.resolveIndexManifests` | Aggregation D4 and the dual-listing fail-closed D6 |
| `JsonTreeBuilder.parseContentBody` | Content dispatch, embedded types, half-apply, the D9 hint |
| `JsonTreeBuilder.listIndexFiles` | The failure branches and the ordering of the directory scan |
| `JsonTreeBuilder.applyUnchecked` | A failed apply landing in the bucket |
| `DuplicateIdResolver.resolve` / `describe` / `toLoadingError` | last-wins and the conflict messages |
| `EffectiveId.of` | The id normalization table |
| `Diagnostics` methods spanning both dimensions | Two-dimension isolation, clearing, the summarize format |
| `DataDiagnosticsCommands.kasugaDataCommand` | The shape of the command tree |
| `ReloadOrchestrator.reload` / `indexResourcePath` | The reload cycle, the two entry points, cross-entry deduplication, diagnostics |
| A `ReloadHandler` implementation (fixture) | Wiring in a new reload type: `ReloadHandlerExtensionPointTest` uses a fake handler to assert dispatch/dedup/registration and the after-stage order |
| `ReloadHandlerRegistry.register` / `all` / `clearForTest` | Registry order preservation, same-name replacement, test isolation |
| `StateMachineDefinitionLoader.decodeFile` | The wrapper-layer decode contract (the directory glob is the job of `ReloadOrchestrator.collectGlob`; the decoder no longer lists files) |
| `AnimationClipLoader.decodeFile` / `register` | Animation clip file decoding and script-wins registration |
| `ContentStructure.body` / `unknownKeys` / `field` / `joinWithOr` / `describe` | Shared structural decisions: the body check, unknown keys, field splitting (`Field`/`Element`), hint joining, the type description |

## 15. Call Examples

Every snippet below compiles against the current signatures. Each one names the real test case it imitates, with the test class in parentheses.

Register a mod and check the error bucket afterwards (imitating `JsonTreeBuilderIndexPathTest.contentTestingIndexLoadsWithoutErrors`):

```java
import java.util.List;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;

JsonTreeBuilder.clearLoadingErrors("kasuga_lib");
JsonRegistryGroup root = JsonTreeBuilder.buildForMod("kasuga_lib");
// root 为 null 表示没有声明任何 on_register 内容；非 null 时是挂在 mod 注册树上的根组
List<Throwable> errors = JsonTreeBuilder.getLoadingErrors("kasuga_lib");
```

Parse one index manifest and aggregate it (imitating `JsonTreeBuilderIndexSchemaTest.declaringNeitherDomainIsReportedAndConsumesNothing` and `crossListedPathIsConsumedByNeitherDomain`):

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
        // path 相对 data/kuayue/，已通过双列 fail-closed 检查
    }
}
```

Parse a content file only, without registering (imitating `JsonTreeBuilderEmbeddedTypeDispatchTest.topLevelEmbeddedTypeIsReportedAndTheRestOfTheFileStillDispatches`):

```java
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;

JsonObject body = /* 已解析的内容文件 */ new JsonObject();
Map<String, List<Object>> collected =
        JsonTreeBuilder.parseContentBody("kuayue", "carriages/m1/blocks.json", body);
// collected 的 key 是 handler 的 typeName；解析错误进 JsonTreeBuilder.getLoadingErrors("kuayue")
```

Write to and read the diagnostics bucket (imitating `DiagnosticsTest.modDimensionIsTheExistingBucket` and `summarizePrintsOneLinePerNonEmptyBucketDeterministically`):

```java
import java.util.List;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;

Diagnostics.report("kuayue", new IllegalStateException("boom"));
List<Throwable> perMod = Diagnostics.errors("kuayue");
// perMod 与 JsonTreeBuilder.getLoadingErrors("kuayue") 是同一个桶
List<String> lines = Diagnostics.summarize();   // 每行 "<mod>: N error(s)"（mod 维）或 "<DOMAIN>[<key>]: N error(s)"（源维）
Diagnostics.clear("kuayue");
```

Register and run the diagnostics command (imitating `DataDiagnosticsCommandsTest.onRegisterCommandsRegistersKasugaDataErrorsIntoTheDispatcher`):

```java
import com.mojang.brigadier.CommandDispatcher;
import lib.kasuga.registration.data_driven.diagnostics.DataDiagnosticsCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
DataDiagnosticsCommands.onRegisterCommands(
        new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null));
dispatcher.execute("kasuga_data errors kuayue", source);   // source 权限需 >= 2
```

Run one reload cycle (imitating `ReloadOrchestratorTest.loadsValidDefinitionsIntoInjectedBucket`):

```java
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmClipsReloadHandler;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmReloadHandler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import net.minecraft.server.packs.resources.ResourceManager;

FsmDefinitions definitions = new FsmDefinitions();
FsmAnimationClips clips = new FsmAnimationClips();
// varargs 构造器把这两个 handler 登记进 ReloadHandlerRegistry，顺序即 dispatch/注册顺序
ReloadOrchestrator orchestrator = new ReloadOrchestrator(
        new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips));
orchestrator.reload(resourceManager);   // 一个周期：清桶、双入口发现、去重注册、收尾校验
```

> The orchestrator has moved to `lib.kasuga.registration.data_driven.reload` (data-driven), while the two handlers stay in modelling's `lib.kasuga.rendering.models.mc.dynamic.fsm`, so this example imports both packages. In tests the varargs constructor brings its own buckets; in production the handlers are registered by `FsmReloadHandlerRegistrar` and the DI constructor runs with no arguments.

Decode and register animation clips (imitating `AnimationClipLoaderTest.decodesTheWrappedClipArray`; the registration call is made by the orchestrator):

```java
import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationClipLoader;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;

FsmAnimationClips clips = new FsmAnimationClips();
AnimationClipLoader.DecodedFile decoded =
        AnimationClipLoader.decodeFile(JsonParser.parseString(json));
for (AnimationClip clip : decoded.clips()) {
    AnimationClipLoader.register(clips, clip);   // 写 RESOURCE 半区，SCRIPT 同 id 条目不被覆盖
}
```

### Reference Usage Notes

`parseContentBody`, `parseIndexManifest`, `resolveIndexManifests`, `DuplicateIdResolver`, and `EffectiveId` are all pure functions and can be asserted directly in an ordinary JUnit test with no NeoForge runtime. `buildForMod`, `buildAll`, `ReloadOrchestrator.reload`, and the registration side of `FsmAnimationClips` need a mod or game runtime; `buildForMod`-style tests usually guard with `Assumptions.assumeTrue` (see `JsonTreeBuilderIndexPathTest`, `JsonTreeBuilderLoadingErrorTest`).

> Other pages in the data-driven series: [Registration API](api-registration.md) · [Extension API](api-extension.md) · [Diagnostics API](api-diagnostics.md) · [Reload API](api-reload.md) · [Series index](README.md)
