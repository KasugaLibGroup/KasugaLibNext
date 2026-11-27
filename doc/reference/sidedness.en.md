# Sidedness Model

This page documents physical side detection (`Dist` / `@BeanOnlyIn`), logical side contracts (`LogicalSide` / `Level.isClientSide`), Mixin sidedness and ABI gating, resource/script routing sides, and the subsystem sidedness matrix in KasugaLibNext.

---

## 1. Physical Side Detection and Micronaut Conditional Beans

### 1.1 `Envs` Utilities

`modules/inject` provides two environment helpers:

- `lib.kasuga.inject.class_loader.Envs` (safe when `FMLLoader.getDist() == null`, suitable for plain JVM tests):
  - `isDevEnvironment()`: `!FMLLoader.isProduction()`
  - `isClient()`: `dist != null && dist.isClient()`
  - `isDedicatedServer()`: `dist != null && dist.isDedicatedServer()`
- `lib.kasuga.inject.Envs`: delegates directly to `FMLLoader.getDist().isClient()` and `isDedicatedServer()` without a null check on `dist`.

### 1.2 `@BeanOnlyIn` and `MinecraftDist`

`lib.kasuga.inject.class_loader.BeanOnlyIn` uses Micronaut `@Requires(condition = ...)` to restrict bean activation:

| Annotation | `Condition` (`MinecraftDist`) | Active When | Representative Beans |
| --- | --- | --- | --- |
| `@BeanOnlyIn.Client` | `MinecraftDist.IsClient` | `Envs.isClient()` | `KasugaLibClientApplication`, `ClientRenderingRegistry`, `DefaultClientRegisterContextDispatchers`, `WeatherFilterConfig`, `ScriptingClientApplication`, `ClientCreateRegistrationBridge`, `ClientBogeyRendererRegistry` |
| `@BeanOnlyIn.DedicatedServer` | `MinecraftDist.IsDedicatedServer` | `Envs.isDedicatedServer()` | `ScriptingServerApplication` |
| `@BeanOnlyIn.Development` | `MinecraftDist.IsDevelopment` | `Envs.isDevEnvironment()` | Dev-only beans |
| `@BeanOnlyIn.Never` | `MinecraftDist.Never` | Always `false` | Disabled beans |
| `@CreateModBeans` (`lib.kasuga.create`) | `IfCreateModLoaded` | `ModList.get() != null && ModList.get().isLoaded("create")` | `CreateApplication`, `CreateConfig`, `CreateRegistrationDispatcher`, `ClientCreateRegistrationBridge`, `ClientBogeyRendererRegistry` |

`MinecraftDist.ModLoaded` is an abstract `Condition` checking `ModList.get().isLoaded(getModId())` for optional mod integrations.

#### Replacing Common Fallback Beans on the Client

`DefaultRenderingRegistry` (`@Singleton`) acts as the server-side no-op fallback (logs at `DEBUG` and skips renderer registration). `ClientRenderingRegistry` is annotated with `@Singleton`, `@Replaces(DefaultRenderingRegistry.class)`, and `@BeanOnlyIn.Client`, so on physical clients Micronaut replaces the default bean and performs actual `BlockEntityRenderer`, `EntityRenderer`, and `DocumentComponentRenderer` registration.

---

## 2. Registration Dispatch and Mixin Sidedness

### 2.1 `RegisterContextRegistry.Side`

`lib.kasuga.registration.core.RegisterContextRegistry.Side` defines two registration sides:

- `COMMON`: activated by `KasugaLibApplication` via `registry.register(modEventBus)`, dispatching `NEW_REGISTRY`, `CreateRegistrationStages.PREPARE`, `REGISTER_EVENT`, `REGISTER_CAPABILITIES`, `PAYLOAD_REGISTRATION`, and `CREATIVE_TAB_CONTENT_REGISTRATION`.
- `CLIENT`: activated by `KasugaLibClientApplication` (`@BeanOnlyIn.Client`) via `registry.registerClient(modEventBus)`, dispatching `MENU_SCREEN_BINDING`, `REGISTER_CLIENT_EXTENSIONS`, and `BAKING_COMPLETE` on the physical client only.

In the Create integration, `CreateRegistrationDispatcher` runs on `Side.COMMON` and looks up `CreateClientBridge` as a `Registry` attachment: `ClientCreateRegistrationBridge` installs the client implementation on `Side.CLIENT` (handling `PartialModel`, `TrackModel`, and `BogeyStyle.SizeRenderer`), whereas dedicated servers fall back to `CreateClientBridge.noop()`.

### 2.2 Mixin Sidedness and Bytecode ABI Gating

| Mixin Config | Plugin Class | Sidedness & Gating Behavior |
| --- | --- | --- |
| `kasuga_lib.mixins.json` | `lib.kasuga.mixins.KasugaLibMixinPlugin` (extends `ServiceMixinPlugin`) | Checks for `lib/kasuga/mixins/modelling/PeelShaderLoaderMixin.class`: when `Side != CLIENT`, loads only 3 common/server modelling mixins (`CameraChunkServerMixin`, `CameraEntityDataAccessor`, `ModelCollisionLevelMixin`); when `Side == CLIENT`, loads the full client world-view, headless, and weather mixin set |
| `kasuga_lib_create.mixins.json` | `lib.kasuga.create.compat.CreateMixinPlugin` | `"mixins"` applies on both sides; `"client"` applies on client only (and inspects `BezierConnection.getBakedSegments` bytecode to choose between `BezierConnectionMixin` and `ModernBezierSegmentsMixin` / `ModernBezierGirdersMixin`) |
| `kasuga_lib_iris.mixins.json` (`"required": false`) | `lib.kasuga.compat.iris.IrisCameraMixinPlugin` | Returns an empty list unless `MixinEnvironment.Side.CLIENT` and all Iris + Sodium bytecode ABI checks pass (with an independent gate for `WorldViewIrisCloudMixin`) |

---

## 3. Logical Side (`LogicalSide`), State Callbacks, and Script Entry Points

Physical sides and logical sides (singleplayer integrated server vs. client world) differ across resource routing, block-state callbacks, and script execution:

### 3.1 `LogicalSide` and `ResourceRouting`

`lib.kasuga.core.resource.lifecycle.LogicalSide` defines `CLIENT`, `SERVER`, `BOTH`, and `NONE`:

- `ResourceRouting.route(catalog, event, side)` requires a concrete invocation side (`LogicalSide.BOTH` throws `IllegalArgumentException`).
- Output validation rejects impossible combinations: `CLIENT_STARTUP` cannot target `SERVER` or `BOTH`; `SERVER_STARTUP` cannot target `CLIENT` or `BOTH`.
- `ResourceCoordinator` passes `context.neutral()` to `LogicalSide.NONE` entry points and forbids mixing `NONE` and game-side outputs in one entry point invocation.
- `ManagedScriptEntryPoint` and `ScriptBlockRegistry` also reject `LogicalSide.BOTH`, and `ScriptBlockRegistry.bind(ref, context)` requires `context.side() == side && ref.side() == side` (client and server script blocks never cross-bind).

### 3.2 `ScriptPackage.start` (Legacy Physical Dist) vs. `ScriptPackage.startManaged` (Managed Logical Side)

`package.toml` declares `server`, `client`, and `common` entry arrays under `[entry]`, filtered differently by the two startup paths:

- **Legacy `ScriptPackage.start(ScriptThreadGroup)`** (used by `ScriptRuntime`): filters by **physical** `FMLEnvironment.dist` (`entry.server` runs only on `Dist.DEDICATED_SERVER`, `entry.client` only on `Dist.CLIENT`, and `entry.common` on both). Integrated-server logic in legacy packages must therefore be placed in `entry.common`.
- **Managed `ScriptPackage.startManaged(...)`** (used by `ManagedScriptEntryPoint`): filters by **logical** `LogicalSide` (`LogicalSide.SERVER` runs `common + server`; `LogicalSide.CLIENT` runs `common + client`).

### 3.3 `StateProperty.Side` and `Saved<T>`

- `lib.kasuga.registration.data_driven.state.StateProperty`:
  - `set(ServerLevel, BlockPos, T, int)` asserts `level.getServer().isSameThread()` and writes only on the server thread.
  - `onChange(Side, Consumer<Change<T>>)` supports `Side.SERVER` (default), `Side.CLIENT`, and `Side.BOTH`, matching `level.isClientSide`.
- `lib.kasuga.inject.auto_configure.Saved<T>`: provided by `SavedFactory`, bound on `ServerStartedEvent`, and cleared on `ServerStoppingEvent`; calling `get()` before server start or without a server context throws `IllegalStateException("The level is not loaded yet.")`.

---

## 4. Module and API Sidedness Matrix

| Category | Modules / APIs | Allowed Side | Notes |
| --- | --- | --- | --- |
| Pure JVM / Graphics-Independent | `modules/utils`, `modules/formula`, `modules/data-driven` pipeline (`DataDrivenBuildPipeline`), `modules/render-core` (`ModelInstance.forSimulation`, `ModelTickLoop`, `SkeletonRagdoll`, `ProxyRuntime`) | Both + offline tests | Physics requires native Box3D (`NativeBox3D.availableOrWarn()`); no Minecraft or OpenGL dependency |
| Common Registration, VFS & Networking | `KasugaLib`, `Registry` / `Reg`, `ResourceSystem`, `VirtualFileSystem`, `RpcSessionManager`, `DeviceRegistries`, `DocumentComponentRegistries` | Both (`COMMON`) | Client and server maintain separate `ScopedResourceManager` instances |
| Server / Dedicated-Server Only | `Saved` / `SavedFactory`, `ServerCollisionModels`, `ScriptingServerApplication` | Logical server / dedicated server | `ScriptingServerApplication` is annotated `@BeanOnlyIn.DedicatedServer` |
| Client-Only Rendering & Output | `modules/render-opengl`, `modules/iris-compat`, `Constants`, `PipelineRegistry`, `MCBackend`, `EffectRenderPipeline`, `RenderShaderRegistry`, `WorldRenderPipelineRegistry`, `PostProcessGraphRegistry`, `MinecraftWorldViews`, `MinecraftFrameOutputs`, `ItemModelRenderers`, `ModelParticleRuntime`, `ModelLightRuntime`, `ClientBlockUpdateHooks`, `HeadlessClient`, `ScriptingClientApplication`, `ClientBogeyRendererRegistry` | Physical client only (GPU calls on render thread) | Loading client rendering classes on a dedicated server fails due to client/OpenGL types; `HeadlessClient` still runs as a full physical client |

## Related Pages

- [Lifecycle and Initialization Order](lifecycle.md)
- [Configuration Options](configuration.md)
- [Extension Point Catalog](extensions.md)
- [Threading Model](../explanation/threads.md)
- [Unified Resource Platform and Script Tickets](resource-platform.md)
