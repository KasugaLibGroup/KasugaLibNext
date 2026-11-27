# Scripting and Javet Runtime Reference

`:modules:scripting` (package prefix `lib.kasuga.scripting`) provides the engine-agnostic scripting runtime, package discovery, `package.toml` parsing, module registries, `@Api` security enforcement, timers, and the animation state-machine (FSM) scripting bridge. `:external:slp:javet` (package prefix `lib.kasuga.slp.javet`, standalone NeoForge mod ID `kasuga_lib_javet`) provides the Javet / V8 5.0.1 JavaScript engine backend and automatic native library downloader.

> For `ScriptBlockRegistry`, `ScriptSubmissionRing`, `ManagedScriptEntryPoint`, and the asynchronous `ScriptTicket` contract in the unified resource platform, see [Unified Resource Platform and Script Tickets](resource-platform.md#script-blocks-and-tickets).

## Modules and Coordinates

| Module | Gradle Project | Mod ID / Packaging | Responsibility |
| --- | --- | --- | --- |
| Core scripting runtime | `:modules:scripting` | Merged into `kasuga_lib` (`useModProject`) | Engine abstraction, package scanning, threading model, module registries, timer, `@Api` security feature, FSM API |
| Javet V8 backend | `:external:slp:javet` | `kasuga_lib_javet` (requires `kasuga_lib`, `AFTER`) | `JavetScriptEngine`, `FastJavetClassConverter`, `JsModuleResolver`, `JavetDownloader` |

`modules/library/build.gradle` bundles `:modules:scripting` into the primary distribution via `useModProject(project(':modules:scripting'))`; `:external:slp:javet` ships as an optional companion mod (see [Library Packaging and Multiversion Structure](library-packaging.md)).

## Engine Abstraction and Registry

### ScriptEngine and ScriptEngineType

`lib.kasuga.scripting.ScriptEngine` defines the per-instance script engine lifecycle:

| Method | Description |
| --- | --- |
| `usePackageRegistry(PackageRegistry packages)` | Injects an isolated `PackageRegistry` before `init`; defaults to `UnsupportedOperationException` |
| `safetyCapabilities()` | Returns `ScriptSafetyCapabilities`, defaulting to `ScriptSafetyCapabilities.UNVERIFIED` |
| `init(ScriptConsole console)` | Initializes the runtime instance, console interceptor, and built-in modules |
| `setFeatures(Map<EngineFeatureType<?>, EngineFeature>)` / `getFeature(type)` | Binds and retrieves engine features (such as `SecurityEngineFeature`) |
| `createValue(Object object)` | Wraps a Java object as a `ScriptValue` |
| `loadModule(ResolvedScript script)` / `getLoadedModule(String sourcePath)` | Loads and caches a script module by source path, returning `ScriptModuleHandle` |
| `executeEntry(String entryName, InputStream source[, ResolvedPackage owner])` | Executes an entry script; supplying `owner` scopes relative `require` calls on the entry's first frame to that package |
| `tick()` / `close()` | Advances the engine and active `ScriptModule` instances each tick, or tears down the runtime on close |
| `registerGlobal(String name, Object api)` | Registers an engine-global object (such as `Animator`, `AnimatorBuilder`, `ScriptBlocks`) |

`lib.kasuga.scripting.ScriptEngineType<T>` encapsulates engine instantiation and metadata via `ScriptEngineType.builder(Supplier<T>)`:

| Builder Method | Field / Default | Description |
| --- | --- | --- |
| `scriptType(String)` | `scriptType` | Primary language identifier (e.g., `"javascript"`) |
| `engineSupplier(Supplier<T>)` | `engineSupplier` | Engine instance factory |
| `resolver(ModuleResolver)` | `resolver` | Script file locator (`locateScript` / `getExtension`) |
| `multiThreadSupporting(boolean)` | `false` | Multithreading capability flag |
| `priority(int)` | `0` | Resolution priority per language name (higher wins) |
| `addFeature(EngineFeatureType<?>)` | `featureTypes` | Instantiated independently per engine via `featureType.build()` |
| `addGlobalApi(String, Function<ScriptEngine, Object>)` | `globalApis` | Creates a global API object receiving the initialized `ScriptEngine` |
| `addGlobalApi(String, Supplier<Object>)` | `globalApis` | Convenience overload when no engine back-reference is needed |

`ScriptEngineType.create(ScriptConsole console[, PackageRegistry packages])` executes in strict order: instantiate engine -> `usePackageRegistry` (if provided) -> build and `setFeatures` -> `init(console)` -> `registerGlobal` in registration order. If `ScriptException` or `RuntimeException` occurs, `engine.close()` is invoked and any cleanup exception is attached as suppressed. `isAvailable()` returns `true` only when `loadingIssues` is empty.

### ScriptEngineRegistry

`lib.kasuga.scripting.ScriptEngineRegistry` (`@Singleton`) indexes engines by `ResourceLocation` and language type strings:

- `register(ResourceLocation location, List<String> types, ScriptEngineType<?> engineType, int priority)`: records the exact `location` entry and pushes `(location, priority)` into a max-priority queue for every declared language string in `types`.
- `resolve(String requiredEngine, boolean allowLoadingIssueWhenNoEnginePresent)`:
  1. If `requiredEngine` parses as a registered `ResourceLocation`, returns it directly (or `null` if `allowLoadingIssueWhenNoEnginePresent == false` and `loadingIssues` is non-empty).
  2. Otherwise looks up the language queue by descending `priority`, returning the first engine with empty `loadingIssues`.
  3. If all candidates have `loadingIssues` and `allowLoadingIssueWhenNoEnginePresent == true`, returns the first engine carrying issues for diagnostics; otherwise returns `null`.

### Legacy Pack Runtime vs. Managed Resource Entry

Two package execution paths exist:

- **Legacy pack runtime (`ScriptingSystem` + `ScriptRuntime`)**: `@Context` singleton `ScriptingApplication` registers `ScriptingSystem` as a `ScopedResourceManagerConsumer` on `ResourceSystem`. Each `ScopedResourceManager` (client `server == null`, or server `MinecraftServer`) owns a `ScriptRuntime`. `onReloaded(...)` serializes shutdown of the prior `ScriptThreadGroup` (observing a 5-second timeout warning without blocking game threads), scans `ScopedPackResources`, builds the `ScriptPackage` tree, and starts threads. `ServerTickEvent.Pre` and `ClientTickEvent.Pre` call `ScriptRuntime.tick()`.
- **Managed resource entry (`ManagedScriptEntryPoint`)**: registered under ID `"kasuga:script_blocks"`, accepting `RESOURCE_RELOAD`, `CLIENT_STARTUP`, `SERVER_STARTUP` and `CLIENT`, `SERVER`, `NONE`. Prepares an isolated `ScriptBlockRegistry`, `PackageRegistry`, and `ScriptThreadGroup` per generation, starting packages via `ScriptPackage.startManaged(...)` and exposing `ScriptBlockHandle` / `ScriptTicket` (see [Unified Resource Platform and Script Tickets](resource-platform.md#script-blocks-and-tickets)).

### Client and Dedicated-Server Error Handling

- **Client (`ScriptingClientApplication`, `@BeanOnlyIn.Client`)**: listens to `ClientResourceReloadFinishEvent`. When `PackageSystem.getMissingEngines()` is non-empty, opens `ScriptEngineMissingScreen` (offers "Open Mods Folder", "Download" to `https://ecosystem.kycraft.cn/search?keyword=...&platform=...`, "Ignore and Continue(Dangerous!)" with `ConfirmScreen`, and "Quit"). When `PackageSystem.getEngineErrors()` is non-empty, opens `ScriptEngineErrorScreen` displaying engine exceptions and stack traces.
- **Dedicated server (`ScriptingServerApplication`, `@BeanOnlyIn.DedicatedServer`)**: implements `Configurable` and registers server config boolean `ignore_scripting_errors` (default `false`). On `ServerResourceReloadFinishEvent`, missing engines log a warning when `ignore_scripting_errors` is `true` or throw `RuntimeException` when `false`; non-empty `engineErrors` always log and throw `RuntimeException`.

## Package Discovery, `package.toml`, and Threading

### `package.toml` Schema

`PackageSystem.readPackageInfo(ScopedPackResources spr, String packRelativeRoot)` parses `<packRelativeRoot>/package.toml` (UTF-8) via NightConfig `TomlParser` into `PackageInfo`:

| Key | Java Type | Required | Description |
| --- | --- | --- | --- |
| `name` | `@Nullable String` | Recommended | Unique package name (e.g., `"@test/scripting-test"`); duplicate names throw `DuplicatePackageException` into `loadingErrors` |
| `engine` | `String` | Required for runnable packages | Engine language type (e.g., `"javascript"`) or `ResourceLocation` string (e.g., `"kasuga_lib_javet:javet"`) |
| `description` | `String` | No | Human-readable description |
| `version` | `String` | No | Package version string |
| `main` | `String` | No | Main entry resolved by `require("<packageName>")` (extensionless logical path, e.g., `"index"`) |
| `workspaces` | `String` or `List<String>` | No | Sub-package workspace patterns (e.g., `["packages/*"]` or `["./packages/*"]`); scanned on `HierarchicalScopedPackResources` by stripping trailing `/*` and checking subdirectories for `package.toml` |
| `entry.common` | `String` or `List<String>` | No | Entry scripts executed on both client and server |
| `entry.server` | `String` or `List<String>` | No | Entry scripts executed only on the server side |
| `entry.client` | `String` or `List<String>` | No | Entry scripts executed only on the client side |

Example (from `external/slp/javet/src/main/resources/scripts/package.toml` and `packages/scripting-test/package.toml`):

```toml
# scripts/package.toml (workspace root)
engine = "javascript"
workspaces = ["packages/*"]
```

```toml
# scripts/packages/scripting-test/package.toml
name = "@test/scripting-test"
engine = "javascript"
version = "1.0.0"
main = "index"

[entry]
client = ["index"]
```

### Discovery and Side Filtering

`PackageSystem.scan(ScopedPackResources)` probes `scripts/package.toml`, resolves its engine, registers it in `PackageRegistry`, and scans declared `workspaces`. Packs without `package.toml` are skipped. Diagnostics accumulate in `loadingErrors`, `engineErrors`, and `engineMissing`.

`ScriptPackage.startInternal(...)` filters `[entry]` differently depending on mode:

- **Legacy (`ScriptPackage.start(ScriptThreadGroup)`)**: filters by physical `FMLEnvironment.dist` (`Dist.DEDICATED_SERVER` runs `common + server`; `Dist.CLIENT` runs `common + client`). Integrated-server scripts that must also run in a physical client belong in `entry.common` under the legacy path, or should use managed mode below.
- **Managed (`ScriptPackage.startManaged(..., LogicalSide side, ...)`)**: filters by concrete `LogicalSide` (rejects `LogicalSide.BOTH`): `SERVER` runs `common + server`, `CLIENT` runs `common + client`. Attaches `ScriptBlockRegistry.PackageSession` (ring capacity `1024`) and installs global `ScriptBlocks` plus optional `Resources` (`ScriptVfsFacade`).

### ScriptThreadGroup and ScriptThread

Each runnable package creates a child `ScriptThreadGroup(groupName, parent)` and an owning `ScriptThread` named `Script-<packageName>-<entryName>`.

Threading and queue invariants:

- Engine creation, module loading, callbacks, `tick()`, and `close()` execute exclusively on the owning `ScriptThread` (`ScriptThread.owns(ScriptEngine)`).
- `recordCall(Runnable)` enforces a bounded task queue of **4096** tasks, throwing `RejectedExecutionException` when full or closed.
- Each wakeup iteration executes one `pendingTasks` item, drains all `ScriptSubmissionRing` instances via `ring.drain(64, 2_000_000)` (up to 64 requests / 2 ms budget), and runs `engine.tick()` when a tick signal is pending; otherwise parks in `wait()`.
- `shutdown()` closes all submission rings, interrupts the thread, runs registered `onTermination(Runnable)` hooks (such as unpinning `ScriptFunction` handles), calls `engine.close()`, and completes `shutdownFuture`.

## Module Resolution, Built-ins, and Timers

### ModuleResolver, BuiltinModuleRegistry, and PackageRegistry

- `lib.kasuga.scripting.module.ModuleResolver`: declares `locateScript(ResolvedPackage pkg, List<String> relativeSegments)` and `getExtension()`, resolving segments into `ResolvedScript(filePath, pack, ModuleId, owner)`.
- `lib.kasuga.scripting.module.BuiltinModuleRegistry` (`@Context`):
  - `register(String moduleName, Supplier<Object> supplier)`: registers shared global built-in modules.
  - `registerFactory(ScriptModuleFactory<M> factory)`: registers per-engine `ScriptModule` factories (`init()`, `tick()`, `close()`); created automatically during `JavetScriptEngine.init(...)`.
- `lib.kasuga.scripting.module.PackageRegistry` (`@Context`): thread-safe map of `name -> ResolvedPackage`; rejects duplicates with `DuplicatePackageException`. Managed generations keep an isolated `PackageRegistry` in `ScriptBlockRegistry.modulePackages()`.

### TimerModule and KasugaTimer

`lib.kasuga.scripting.timer.TimerModule` (`TimerModule.FACTORY`, name `"kasuga:timer"`, registered by `KasugaLibJavet`) delegates to `KasugaTimer` (a `PriorityQueue<CallbackEntry>` tick scheduler):

| `@Api` Method | Signature | Semantics and Units |
| --- | --- | --- |
| `setInterval` | `int setInterval(ScriptValue callback, int ms)` | Requires `callback instanceof ScriptFunction` (returns `-1` otherwise); clones & pins via `ScriptReference`, repeating every `Math.max(1, ms / 50)` ticks (50 ms/tick) |
| `setTimeout` | `int setTimeout(ScriptValue callback, int ms)` | Fires once after `Math.max(1, ms / 50)` ticks |
| `clearInterval` | `void clearInterval(@Nullable Integer id)` | Unregisters timer `id`, unpins and closes its cloned `ScriptFunction` |
| `clearTimeout` | `void clearTimeout(@Nullable Integer id)` | Delegates to `clearInterval(id)` |

`TimerModule.close()` unpins and closes all active callbacks and clears `KasugaTimer`.

## Script FSM API (`lib.kasuga.scripting.fsm`)

`FsmApiRegistration.install(ScriptEngineType.Builder)` registers `"Animator"` (`AnimatorApi`) and `"AnimatorBuilder"` (`AnimatorBuilderApi`) with back-references to the owning `ScriptEngine`. See [State Machine and Animation Clip JSON Reference](fsm.md) for the FSM JSON schema.

### Two-Tier Error Philosophy and Auto-Tick

- **`AnimatorBuilderApi` (authoring / registration, fail-fast)**: throws `IllegalArgumentException` on invalid JSON/definitions, unknown definition IDs, or missing engine binding.
- **`AnimatorApi` (runtime control, never throws across the boundary)**: unknown handles, unknown var IDs, or wrong types log a warning once per key (256-entry LRU) and return inert defaults (`null`, `false`, `0`, `0.0`, `""`, `-1`, or empty arrays).
- **`FsmAutoTickModule` (`FACTORY.name() == "kasuga:fsm_autotick"`)**: per-engine `ScriptModule` that ticks registered scripting handles on the owning `ScriptThread`, honoring the FSM single-ticker invariant.

### AnimatorBuilderApi Methods

| Method | Description |
| --- | --- |
| `String[] varTypes()` | Lists registered `StateVarType` tokens (`"bool"`, `"int"`, `"float"`, `"string"`, `"vec3"`, plus any host-registered types) |
| `String registerStateVar(String namespace, String path, String type[, Object defaultValue[, boolean ephemeral]])` | Registers a typed `StateVar` (`ephemeral = true` creates a tick-scoped trigger); returns `""` on unknown `type`, or idempotent `"namespace:path"` |
| `void registerCondition(String namespace, String path, ScriptFunction guard)` | Registers a JS guard (clones & pins callback; passes `ScriptFsmContext`; evaluates truthiness via `ScriptValues.isTrue`; degrades exceptions to `false`). Java callers use the `Predicate<StateContext<?>>` overload |
| `void registerAction(String namespace, String path, ScriptFunction action)` | Registers a JS action (Java callers use `Consumer<StateContext<?>>`) |
| `String registerDefinition(ScriptValue definition)` / `(String json)` | Decodes a JS object literal or JSON string via `StateMachineDefinition.CODEC` into `FsmDefinitions` and returns the definition ID |
| `int instantiate(String id, Object owner[, Object model])` | Creates a runtime `StateMachine` and returns an `int` handle (avoiding JS `BigInt`). Attaches `ModelInstancePoseSink` when `model instanceof ModelInstance`; logs a warning and runs sink-less otherwise |

### AnimatorApi Methods

| Group | Methods | Inert Return | Notes |
| --- | --- | --- | --- |
| Typed vars | `get(int handle, String varId)` / `has(...)` / `set(..., Object value)` | `null` / `false` / no-op | Resolves full `"namespace:path"` first, then unambiguous short name (no `:` or `/`, matching `/<shortName>`) |
| Var discovery | `listVars(int handle)` / `declaredVars(int handle)` / `varType(String varId)` | `[]` / `[]` / `""` | `listVars` returns explicitly set vars; `declaredVars` returns all vars declared by the definition |
| State reads | `getState(int handle, String layerId)` / `getStateElapsed(...)` / `getStateDuration(...)` | `""` / `0` / `-1` | Active state ID, elapsed ticks, and configured duration ticks |
| Layer properties | `getLayerMode(int handle, String layerId)` / `getLayerWeight(...)` / `isLayerLocked(...)` | `""` / `0.0` / `false` | `BlendMode#serialName()` (`"base"`, `"additive"`, `"override"`), weight, lock flag |
| Clock & version | `getTick(int handle)` / `getVersion(int handle)` | `0.0` / `0` | `getTick` returns `double` so JS receives a plain `Number` |
| Triggers & jumps | `trigger(int handle, String triggerVarId)` / `isTriggered(...)` / `goTo(int handle, String layerId, String stateId)` | no-op / `false` / no-op | `trigger` requires a boolean var; `goTo` takes effect on the next tick |
| Lifecycle & tick | `tick(int handle)` / `tick(int handle, double dt)` / `dispose(int handle)` | no-op | `dispose` removes the handle from `FsmAutoTickModule` and releases it |
| Auto-tick | `autoTick(int handle, boolean on)` / `onTick(int handle, ScriptFunction)` / `onStateChanged(int handle, ScriptFunction)` | no-op | `onTick` fires after each advance; `onStateChanged` fires only when `machine.version()` changes |

### ScriptFsmContext and StateValueCoercer

JS guard/action callbacks receive `ScriptFsmContext` with `@Api` methods:

- `owner()`: returns the owner object passed to `instantiate` (JS objects round-trip with live V8 object identity).
- `isClientSide()`, `stateElapsedTicks()`, `stateDurationTicks()`, `isLayerLocked()`.
- `get(String varId)`, `has(String varId)`, `set(String varId, Object value)`, `trigger(String triggerId)`, `isTriggered(String triggerId)` (resolved by full ID via `StateVarRegistry.resolve`).

`StateValueCoercer` marshals script values: `bool` requires `Boolean`; `int` accepts `Integer` or finite integral `Double`; `float` widens `Integer`/`Double`/`Float`; `string` requires `String`; `vec3` accepts `Vector3f`, 3-element `List`, or `{x, y, z}` `Map` (and boxes back to 3-element `List<Float>`); `ResourceLocation` converts from/to string.

## `@Api` Export and Security Model

`lib.kasuga.scripting.security` enforces member-level access checks on exported Java objects:

- **`@Api`** (`RetentionPolicy.RUNTIME`):
  - `boolean export() default true`: excludes a public member when `false`.
  - `Parameters[] parameters() default {}`: key/value metadata (`@Api.Parameters(name = "...", value = "...")`) passed to conditions and callbacks.
  - `Class<? extends SecurityCheckCondition>[] conditions() default {}`: required condition classes checked before invocation or field access.
- **`SecurityEngineFeature` & `SecurityEngineFeatureType.INSTANCE`**:
  - Each engine builds its own `SecurityEngineFeature`.
  - Conditions are registered by class on `getConditionRegistry()` (`SecurityConditionRegistry`; built-in `AlwaysAllow`). Missing condition classes **fail closed** (`false`).
  - After all declared conditions pass, an optional `SecurityCheckCallback` (`setSecurityCheckCallback`) runs. Failure throws `RuntimeException("Security check denied: " + name)`.

> Note: `@Api` governs individual members on exposed Java objects, whereas package-level untrusted isolation is governed by `ScriptSafetyCapabilities`. Because `JavetScriptEngine` currently reports `ScriptSafetyCapabilities.UNVERIFIED`, `ManagedScriptEntryPoint` and `ScriptBlockRegistry` reject untrusted packs by default (see [Unified Resource Platform and Script Tickets](resource-platform.md#script-blocks-and-tickets)).

## Javet Engine Backend (`:external:slp:javet`)

### KasugaLibJavet and JavetDownloader

`KasugaLibJavet` (`@Mod("kasuga_lib_javet")`):

1. Registers `JavetDownloader` via `JavetLibLoader.setLibLoadingListener`.
2. Loads the native V8 library via `V8Host.getV8Instance().loadLibrary()` and evaluates `"1 + 1"`, recording any failure into `ENGINE_RUNTIME_ISSUES`.
3. Upon `KasugaLibStartupEvent`, registers `TimerModule.FACTORY` and `FsmAutoTickModule.FACTORY` in `BuiltinModuleRegistry`, builds `KasugaLibJavet.ENGINE_TYPE` (`"javascript"`, `JsModuleResolver`, `SecurityEngineFeatureType.INSTANCE`, `FsmApiRegistration.install`, `ScriptingTestApi`), and registers it in `ScriptEngineRegistry` under `kasuga_lib_javet:javet` with priority `0`.

`JavetDownloader` extracts missing V8 natives into `<gamePath>/native-libraries/javet/` (up to 3 attempts, reporting `ModLoadingManager` / `ModLoadingProgress`):

1. `Alibaba Cloud Mirror`: `https://maven.aliyun.com/repository/public/com/caoccao/javet/javet-{0}-{1}-{2}{3}/{4}/javet-{0}-{1}-{2}{3}-{4}.jar`
2. `Maven Repository`: `https://repo1.maven.org/maven2/com/caoccao/javet/com/caoccao/javet/javet-{0}-{1}-{2}{3}/{4}/javet-{0}-{1}-{2}{3}-{4}.jar`

Android and unsupported OS/architectures fail fast with a message recommending GraalJS mode. `external/slp/javet/build.gradle` explicitly places `org.apache.httpcomponents:httpclient:4.5.13` on `additionalRuntimeClasspath` because `KasugaLibJavet` eagerly instantiates `JavetDownloader` at mod construction.

### JavetScriptEngine and FastJavetClassConverter

Each `JavetScriptEngine` owns an isolated `V8Runtime`:

- Attaches `KasugaJavetConsoleInterceptor` forwarding `console.*` to `ScriptConsole`.
- Logs unhandled promise rejections (`event.getCode() == 0`) to `console.error`.
- Calls `runtime.lowMemoryNotification()` every 21 ticks (`gcTicks++ > 20`).
- Wraps modules and entries in CommonJS `function(require, exports, module)` signatures; inserts a `null` placeholder in `loadedModules` before execution to break circular `require`s.

`FastJavetClassConverter` (with `ClassAccessor`, `NativeProxyAccessor`, `MethodOverrideMap`, `ScriptConverter`, `V8ScriptFunctionProxy`):

- **Java -> V8 (`toV8Value`)**: unwraps/clones `JavetValue`, `ScriptValue` (via `ScriptConverter`), and `V8Value`; delegates primitives/primitive arrays/`BigInteger` to `JavetObjectConverter`; proxies arbitrary Java objects by caching prototypes of `@Api(export = true)` public methods/fields (plus `@FunctionalInterface` abstract methods), tagging V8 proxies with `"KasugaLib#Address"` (`WeakReference` map + `__nGCTracker__` + `setWeak()`).
- **V8 -> Java (`toObject`)**: resolves `"KasugaLib#Address"` back to live Java instances; wraps plain JS objects via `JavetValueBridge.wrap(object.toClone())` to preserve object identity across callbacks. `JavetValueBridge` checks `V8ValueArray` before `V8ValueObject` so JS arrays arrive as `JavetValueArray` rather than string-keyed objects.
- **Per-arity overload dispatch (`ClassAccessor` + `MethodOverrideMap`)**: parameter slots typed as `ScriptValue` (or `ScriptFunction`, `ScriptObject`, `ScriptArray`, `ScriptPrimitive`) clear their `converterMask` bit so arguments use `JavetValueBridge.wrap(...)` instead of `provider.toObject(...)`, allowing JS arrow functions to match `ScriptFunction` overloads directly.

### JsModuleResolver and PackageAwareRequireResolver

`JavetScriptEngine` resolves `require(moduleName)` in three stages:

1. Per-engine `ScriptModule` instances (`scriptModules`, e.g., `require("kasuga:timer").default`).
2. Global built-in suppliers in `BuiltinModuleRegistry.resolve(moduleName)` wrapped with `"default"` export in `JavetModuleHandle`.
3. `PackageAwareRequireResolver`:
   - **By package name**: `PackageRegistry.lookup(moduleName)` (scoped registry when configured, otherwise global bean) -> package `main`.
   - **Relative (`./...`, `../...`)**: normalizes segments from `fromSourcePath`, rejecting any `..` climbing past package root (`null`). Searches `engine.getOwningPackage(fromSourcePath)` first, then falls back to scanning `packageRegistry.all()` (iteration order is unspecified because `PackageRegistry` is backed by a `HashMap` and returns `Map.copyOf(packages)`).

`JsModuleResolver.locateScript(...)` independently splits and normalizes segments (rejecting path traversal) and probes `<basePath>.js` followed by `<basePath>/index.js`.

## Verification Commands

```sh
# Pure-JVM FSM scripting API tests (no NeoForge server launcher)
./gradlew :modules:scripting:scriptUnitTest

# Full scripting suite (package discovery & runtime via EphemeralTestServerProvider)
./gradlew :modules:scripting:test

# Javet V8 engine tests (security, converter, FSM assembly, Script Ticket integration)
./gradlew :external:slp:javet:test
./gradlew :external:slp:javet:test --tests '*ResourceScriptTicketIntegrationTest'
```
