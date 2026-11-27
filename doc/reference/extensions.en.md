# Extension Point Catalog

This page indexes the public SPIs, registries, factories, handlers, and pipeline hooks across KasugaLibNext modules, along with their registration timing, ownership, and cleanup contracts.

---

## 1. DI, Configuration, and Core Registration (`modules/inject`, `modules/core`)

| Extension Point (FQCN) | Registration / Implementation Entry | Timing & Purpose | Ownership & Removal |
| --- | --- | --- | --- |
| `lib.kasuga.inject.auto_configure.Configurable` | Implement on a `@Context` / `@Singleton` bean and override `configureClient`, `configureServer`, `configureCommon`, or `configureStartup` | Collected after `context.start()` inside `ModApplicationContext.init`; registers non-empty `ModConfigSpec`s | Process lifetime; gated by `@BeanOnlyIn` / `@CreateModBeans` |
| `lib.kasuga.inject.class_loader.MinecraftDist.ModLoaded` | Subclass abstract `Condition` and attach via `@Requires(condition = ...)` | Evaluated during `ApplicationContext` bean resolution | Stateless condition |
| `lib.kasuga.inject.auto_configure.Saved<T>` / `SavedFactory` | Inject `Saved<MySavedData>` (target `SavedData` needs a no-arg constructor and a `(CompoundTag)` / `(CompoundTag, HolderLookup.Provider)` / `(HolderLookup.Provider, CompoundTag)` constructor or static loader) | Bound to `ServerStartedEvent` / `ServerStoppingEvent` for lazy per-dimension `SavedData` access | Server world lifetime |
| `lib.kasuga.inject.mixins.ServiceMixinPlugin` | Subclass for Mixin `"plugin"` entries | Mixin loading phase | Classloader lifetime |
| `lib.kasuga.registration.core.RegisterContextRegistry` | `register(Side, RegistryDispatchFunction)` and `registerBefore(Side, RegistrationStage<?>, RegistryStageDispatchFunction)` | When mod registries configure on `COMMON` / `CLIENT`, or right before a `RegistrationStage` dispatches | `ApplicationContext` lifetime |
| `lib.kasuga.registration.stages.RegistrationStage<T>` | Declare `new RegistrationStage<>()`, dispatch via `RegisterContextRegistry`, and consume with `context.onStage(stage, consumer)` inside `Reg.register` | Custom mod or stage dispatch | Matched by reference identity (`equals`) |
| `lib.kasuga.registration.Registry` (attachments) | `getOrCreateAttachment(Class<T>, Supplier)`, `setAttachment`, `getAttachment`, `removeAttachment` | Share per-mod registration state (such as `CreateRegistrationState`, `CreateClientBridge`) | Owned by the `Registry` instance |
| `lib.kasuga.registration.beans.rendering.RenderingRegistry` | Implement and annotate with `@Replaces(DefaultRenderingRegistry.class)` (e.g., `@BeanOnlyIn.Client`) | Invoked at `RegistrationStage.BAKING_COMPLETE` | Micronaut singleton |
| `lib.kasuga.core.rendering.SpecialRenderingRegistry` | `SpecialRenderingRegistry.register(Item, ISpecialItemRenderer)` | Client in-hand special rendering hook (`MixinItemInHandRenderer`) | Global static map |
| `lib.kasuga.content.device.DeviceRegistries` | Use `DEVICE_ATTRIBUTE_REG` / `DEVICE_REG` | `NEW_REGISTRY` / `REGISTER_EVENT` | Custom synced MC registries |
| `lib.kasuga.content.document.DocumentComponentRegistries` | Use `DOCUMENT_COMPONENT_REGISTRY_REG` and `DocumentComponentReg` | `NEW_REGISTRY` / `REGISTER_EVENT` / `BAKING_COMPLETE` | Custom synced MC registry & data component |

---

## 2. Unified Resource Platform, VFS, and Client Hooks (`modules/core`)

| Extension Point (FQCN) | Registration / Entry Point | Timing & Purpose | Ownership & Removal |
| --- | --- | --- | --- |
| `lib.kasuga.core.resource.ResourceSystem` | `registerConsumer(ScopedResourceManagerConsumer)`; `createServerResourceScope` / `createClientResourceScope` / `createProcessResourceScope`; `mountScoped` / `mountClientScoped` | Client setup, server start, resource reload, and shutdown notifications | Managed `AutoCloseable`s and `ResourceCoordinator` scopes close with their owning server/client/process |
| `lib.kasuga.core.resource.lifecycle.ResourceEntryPoint` | `coordinator.register(ResourceEntryPoint)` with `EntryPointDescriptor` (`events`, `sides`, `inputTypes`, `dependencies`, `reloadable`) and `prepare(...)` | Executed asynchronously in dependency order during `ResourceCoordinator.reload(...)`, then published via `PreparedConsumerBatch.publish()` | Prior generation is retired (`retire()` / `close()`) once the replacement `ResourceGeneration` publishes |
| `lib.kasuga.core.resource.source.ResourceSource` | Implement `capture()` returning an immutable `ResourceSnapshot` | Preparation phase of `ResourceCoordinator.reload(...)` | Snapshots closed with their `ResourceGeneration` |
| `lib.kasuga.core.vfs.VirtualFileSystem` | Implement read-only/writable VFS backend and mount via `MountTableVfs.mount` or `ResourceSystem.mountScoped` | Anytime (supports external polling via `VfsWatchService`) | Returns a `VfsSubscription` (`AutoCloseable`) |
| `lib.kasuga.client.ClientBlockUpdateHooks` | `ClientBlockUpdateHooks.addListener(Consumer<Update>)` | Synchronous callback on client main thread from `ClientLevel#sendBlockUpdated` | Returns an `AutoCloseable` removal handle |
| `lib.kasuga.core.compat.CompatCapability<T>` | Construct `new CompatCapability<>(name, probe)` using `requireClass` / `requireMethod` | Lazily evaluated and cached on first `isAvailable()` / `result()` call | Immutable cached probe |

---

## 3. Data-Driven Build, Property Parsing, and Reload (`modules/data-driven`, `modules/core`)

| Extension Point (FQCN) | Registration / Entry Point | Phase & Scope | Ownership & Removal |
| --- | --- | --- | --- |
| `lib.kasuga.registration.data_driven.DataDrivenRegistrationProviders` | `install(hostModId, DataDrivenRegistrationProvider)` | Replaces default mod-JAR scan at registration (`JsonTreeIntegration` checks this before falling back to `JsonTreeBuilder.buildForMod`) | Returns an `AutoCloseable` `Attachment`; duplicate installs for the same `hostModId` throw |
| `lib.kasuga.registration.data_driven.pipeline.DataDrivenBuildHooks` | `registerFinalizer(hostModId, BuildFinalizer)` | Runs at `Stage.FINALIZE` after all `TypeHandler.apply` calls in `DataDrivenBuildPipeline.build(...)` | Process-wide list keyed by `hostModId` |
| `lib.kasuga.registration.data_driven.pipeline.DataDrivenBuildPipeline` | Compose custom `TypeHandler<?>`s, `BuildFinalizer`s, `RegistrationTarget`, `ContentSource`s (from `ContentSourceFactory`), and `BuildOptions` (`LEGACY` / `STRICT`) | Host-controlled multi-source `prepare()` and `build()` | Session object; `HandlerCapability` includes `STRICT_PREFLIGHT`, `NON_REGISTRATION`, `REGISTRY_CLAIMS`, `OVERLAY` |
| `lib.kasuga.registration.data_driven.TypeHandler<T>` / `TypeHandlerRegistry` | `TypeHandlerRegistry.register(TypeHandler<T>)` | Registration phase ordered by `getPhase()` (`PHASE_GROUPS = 0`, `PHASE_CONTENT = 1`, `PHASE_EMBEDDED = 2`); supports embedded types (`getParentTypeName()`, `extractEmbedded`) | Process-wide table (idempotent overwrite by `typeName`) |
| `lib.kasuga.registration.data_driven.reload.ReloadHandler<T>` / `ReloadHandlerRegistry` | `ReloadHandlerRegistry.register(ReloadHandler<?>)` | Reload phase orchestrated in insertion order: `clearBucket() → decode() → DuplicateIdResolver → register() → afterReload()`; optionally declares `globDirectories()` | Process-wide ordered map (re-registering replaces in place; tests may call `clearForTest()`) |
| `lib.kasuga.registration.factory.FactoryRegistry` & `StatefulBlockFactory` | `register` / `registerItem` / `registerBlockEntity` / `registerGeneric` | Invoked by `BlockTypeHandler`, `ItemTypeHandler`, `BlockEntityTypeHandler` for JSON `type` keys; `StatefulBlockFactory` receives `StatePropertySet` | Process-wide concurrent maps (built-ins include `proxy_model` / `proxy_model_be`) |
| `lib.kasuga.registration.data_driven.property.JsonPropertyParser` | `JsonPropertyParser.getInstance().registerCompiler(...)` / `registerStrictCompiler(...)` | Matches keys in block JSON `properties` during parse or strict preflight | Process-wide singleton list / map |
| `lib.kasuga.registration.data_driven.property.JsonItemParser` | `JsonItemParser.INSTANCE.registerParser(...)` / `registerStrictParser(...)` | Matches keys in item JSON `properties` during parse or strict preflight | Process-wide singleton map |

See [Data-Driven Extension API](data-driven/api-extension.md) and [Data-Driven Reload API](data-driven/api-reload.md) for full contracts.

---

## 4. Models, Skeleton Animation, Physics, and Proxies (`modules/render-core`, `modules/render-opengl`, `modules/modelling`)

| Extension Point (FQCN) | Registration / Entry Point | Purpose & Contract | Ownership & Removal |
| --- | --- | --- | --- |
| `lib.kasuga.rendering.models.mc.registry.PipelineRegistry` | `register(String id, ModelPipeLine)`, `registerRoute(KasugaPipeLineRouter, String ext, String id)` | Registers model pipelines (built-in routes cover `.geo.json`, `.obj`, `.mmd.zip`, `.glb`/`.gltf`, `.json`, `.bbmodel`, `.mtb`/`.fmtb`, `.bob`/`.beo`/`.fmf`, and `assembly`) | Throws if `id` is already bound to a different pipeline |
| `lib.kasuga.rendering.models.mc.source.model.KasugaPipeLineRouter` | `register(Predicate, Supplier)`, `registerByExtension`, `registerByPathPattern` | Routes scanned `ResourceLocation` keys to target `ModelPipeLine`s | Owned by client `KasugaModelManager` |
| `lib.kasuga.rendering.models.uml.framework.render.*` | Implement `RenderBackend`, `ModelGeometryAdapter`, `RenderableFactory`, `RenderContext` | Plugs in graphics backends, geometry adapters, renderable factories, and frame render contexts | See [Rendering backend integration reference](rendering-backends.md) and [UML rendering framework](uml-render-framework.md) |
| `lib.kasuga.rendering.models.uml.bridge.Bridge`, `lib.kasuga.rendering.models.uml.loaders.ModelLoader`, `lib.kasuga.rendering.models.uml.loaders.sources.Source` / `SourceManager` | Wire via `ModelPipeLine.Builder` (`withBridge`, `withLoader`, `withModelSource` / `withSidedSource`) and `SourceManager.registerSource` | Plugs in custom model format loaders, asset sources, and `Bridge` backend adapters | Held by the owning `ModelPipeLine` instance |
| `lib.kasuga.rendering.models.uml.framework.buffer.UploadDevice` | Supply custom implementation to `UploadRing` / `GpuUploadRing` | Custom buffer allocation, fencing, or orphan policy (e.g., `MacTboUploadDevice` for macOS TBOs) | Buffers and fences released by owning `UploadRing.close()` |
| `lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop` | `addPreIk(id, module)`, `addPostIk(id, module)`, `addPostPhysics(id, module)`, or `getPipeline()` (`ModelTickLoopModule`) | Inserts procedural pose/physics controllers around canonical slots (`kasuga:apply`, `kasuga:ik`, `kasuga:physics`, `kasuga:anchor`) | Owned per `ModelInstance`; destroyed via `tickLoop.destroy()` |
| `lib.kasuga.rendering.models.uml.dynamic.physics.ModelPhysicsClusterManager.EnvironmentFactory` | `clusterManager.setEnvironmentFactory(EnvironmentFactory)` | Builds a local `CollisionEnvironment` whenever a dynamic physics cluster allocates a `ModelPhysicsScene` | Owned by cluster lifetime |
| `lib.kasuga.rendering.models.mc.api.pbr.PbrConversionRegistry` | `PbrConversionRegistry.register(ResourceLocation id, int priority, PbrConversionRule)` | Customizes PMX-to-PBR material conversion rules (ascending `priority`; `PbrUserConfig` runs at `1000`) | Returns `PbrConversionRegistry.Registration` (`AutoCloseable`) |
| `lib.kasuga.rendering.models.mc.api.ModelLightingDefaults` | `ModelLightingDefaults.register(ResourceLocation model, ModelLightingMode)` | Sets default `ModelLightingMode` (`PBR` or `BLOCK`; defaults to `PBR`) | Survives resource reloads |
| `lib.kasuga.rendering.models.mc.api.item.ItemModelRenderers` | `register(Item, ResourceLocation model, modelName, guiScale)` + register `ItemModelRenderers.clientExtensions()` at `REGISTER_CLIENT_EXTENSIONS` | Connects `minecraft:builtin/entity` items to the shared UML `ItemModelRenderer` | Process-wide map; see [ItemModelRenderers](item-model-renderers.md) |
| `lib.kasuga.rendering.models.mc.multiplexer.MultiplexerRegistry` | `register(ResourceLocation blockId, Multiplexer)` / `resolve(...)` | Caches stateless variant selectors per block type | See [Multiplexer Reference](multiplexer.md) |
| `lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime` & `BlockEntityProxyDefinitions` | `ProxyRuntime.GLOBAL.register(ProxyDefinition)` or `BlockEntityProxyDefinitions.register(BlockEntityType, ResourceLocation, ProxyTemplate)` | Registers proxy templates mapping elements to animation/render ports and attaches sessions via `ProxyAttachToken` | Explicit registration/unregistration; one live session per element |
| `lib.kasuga.rendering.models.mc.api.McModelAssemblies` | `register(ResourceLocation id, ModelAssemblyDefinition)` / `unregister` | Registers programmatic model assembly recipes (take precedence over resource-pack definitions) | See [Minecraft outfit resources and instance switching API](minecraft-model-assembly.md) |

---

## 5. Custom Rendering, Shaders, Managed Effects, and Frame Outputs (`modules/modelling`, `modules/render-core`)

> Note: There is no standalone `EffectPipeline` class in the codebase. Rendering extension points are organized around `RegisterRenderPipelinesEvent`, `RenderPipelineRegistrar` (`RenderPipelineScope`), `EffectRenderPipeline`, post-processing registries, and shader registries (`ClientEffectRuntime.EffectPipelineSnapshot` provides diagnostic snapshots).

| Extension Point (FQCN) | Registration Entry | Pipeline Type / Responsibility | Ownership & Removal |
| --- | --- | --- | --- |
| `lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent` | Listen on the mod event bus and call `event.registrar(owner)` to obtain an owner-scoped `RenderPipelineRegistrar` (`RenderPipelineScope`) | Registers client-lifetime render resources attributed to `owner` | Owned by `CLIENT_RENDER_PIPELINES`, closed in reverse order |
| `lib.kasuga.rendering.effect.RenderPipelineScope` | `RenderPipelineScope.create(owner)` / `scope.child(childOwner)`; exposes `shader`, `world`, `postProcess`, `graph`, `effects`, `particles` | Scoped registrar for dynamic content (levels, scripts, previews) | `scope.close()` closes all owned registrations in reverse order |
| `lib.kasuga.rendering.effect.WorldRenderPipelineRegistry` | `register(owner, RenderPipelineDescriptor, DuplicatePolicy, WorldRenderPipeline)` | Hooks world render passes at a `RenderPhase` (including `POST_PROCESS` and `AFTER_LEVEL`) ordered by priority | Returns `PipelineRegistration` (`AutoCloseable`) |
| `lib.kasuga.rendering.effect.EffectRenderPipeline<T>` | `EffectRenderPipeline.register(owner, descriptor, duplicatePolicy, sortBackToFront, EffectRenderer<T>)` | Manages tickable, cullable, optionally back-to-front sorted `RenderEffect` instances; `spawn(T)` is thread-safe and returns `EffectHandle<T>` | Returns `EffectRenderPipeline<T>` (implements `PipelineRegistration`) |
| `lib.kasuga.rendering.effect.particle.ParticleRenderPipeline` | `ParticleRenderPipeline.register(owner, descriptor, duplicatePolicy, ParticleBatchRenderer)` | Manages custom particle batch renderers | Returns `ParticleRenderPipeline` |
| `lib.kasuga.rendering.effect.post.PostProcessPipelineRegistry` | `register(owner, descriptor, duplicatePolicy, PostProcessPass)` | Registers fullscreen passes at `RenderPhase.POST_PROCESS` (manages `PostProcessContext` and restores main target) | Returns `PipelineRegistration` |
| `lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistry` | `register(owner, descriptor, duplicatePolicy, PostProcessGraph)` | Registers validated multi-pass post-processing graphs with ref-counted `PostProcessTargetPool` targets | Returns `PostProcessGraphRegistration` |
| `lib.kasuga.rendering.effect.shader.RenderShaderRegistry` | `register(owner, RenderShaderDescriptor / ShaderProgram, DuplicatePolicy, ShaderLoadListener)` | Registers resource, custom-factory (`RenderShaderFactory`), or Java Shader DSL (`ShaderProgram`) shaders; exposes reload-stable `RenderShaderHandle` | Returns `ShaderRegistration` (`close()` invalidates handle and closes owned `ShaderInstance` on render thread) |
| `lib.kasuga.rendering.output.mc.MinecraftWorldViews` | `register(viewId, Supplier<WorldCameraView>, ...)` / `registerWhenAvailable(...)` | Renders sequential offscreen camera views before the main view | Returns `MinecraftWorldViews.Registration` (must register/close on render thread) |
| `lib.kasuga.rendering.output.mc.MinecraftFrameOutputs` & `FrameOutputRouter` | `MinecraftFrameOutputs.open(viewId, FrameOutputMode, Consumer<OutputFrame<FrameTexture>>)` | Subscribes to read-only composited frames of any view or `minecraft:main` (`MIRROR` / `OFFSCREEN_ONLY`) | Returns `FrameOutputRouter.Registration` (must register/close on render thread) |

---

## 6. Scripting Runtime and Security Sandbox (`modules/scripting`)

| Extension Point (FQCN) | Registration / Entry Point | Purpose & Contract | Ownership & Removal |
| --- | --- | --- | --- |
| `lib.kasuga.scripting.ScriptEngineRegistry` | `register(ResourceLocation, List<String> types, ScriptEngineType<?>, int priority)` | Registers language engine implementations (e.g., `kasuga_lib_javet:javet` for `"javascript"` at priority `0`) | Micronaut `@Singleton` |
| `lib.kasuga.scripting.ScriptEngineType.Builder` | `scriptType(...)`, `resolver(ModuleResolver)`, `addFeature(EngineFeatureType<?>)`, `addGlobalApi(name, ...)` | Configures module resolution, engine features (such as `SecurityEngineFeatureType`), and per-engine global APIs (e.g., `FsmApiRegistration.install` injects `"Animator"` and `"AnimatorBuilder"`) | Immutable engine type descriptor |
| `lib.kasuga.scripting.module.BuiltinModuleRegistry` | `register(String moduleName, Supplier<Object>)` or `registerFactory(ScriptModuleFactory<M>)` | Exposes global or per-engine built-in modules to `require(...)` (e.g., `TimerModule.FACTORY`, `FsmAutoTickModule.FACTORY`) | `@Context` singleton |
| `lib.kasuga.scripting.security.SecurityConditionRegistry` & `SecurityCheckCondition` | Register condition instances on `SecurityEngineFeature.getConditionRegistry()`, paired with `@Api(conditions = ...)` | Enforces access predicates on Java APIs exposed to scripts | Owned by per-engine `SecurityEngineFeature` |
| `lib.kasuga.scripting.resource.ManagedScriptEntryPoint` | Register `new ManagedScriptEntryPoint(engines, trusted, authorized, accessFactory)` with a `ResourceCoordinator` | Hosts script packages per `ResourceGeneration` with isolated `PackageRegistry`, trust checks, `ScriptBlockRegistry`, and `ScriptTicket`s | Retires its `ScriptThreadGroup` when `PreparedConsumerBatch` closes |

---

## 7. Create Integration Extensions (`modules/create`)

| Extension Point (FQCN) | Registration / Entry Point | Purpose & Stage | Ownership & Removal |
| --- | --- | --- | --- |
| `lib.kasuga.create.registration.CreateRegistrationStages.PREPARE` | Attach `TrackMaterialReg`, `BogeySizeReg`, `BogeyBlockReg`, `BogeyGroupReg`, `TrackReg` to a mod `Registry` | On `CreateRegistrationDispatcher` dispatch, the first four submit phase-ordered work via `CreateRegistrationEvent.submit(...)` (`TRACK_MATERIAL`, `BOGEY_SIZE`, `BOGEY_BLOCK`, `BOGEY_STYLE`); `TrackReg` hooks `PREPARE` only to capture `event.clientBridge()` (it never calls `submit`) | Owned by the mod `Registry` (see [Lifecycle](lifecycle.md)) |
| `lib.kasuga.create.registration.edge_data.EdgeDataReg` & `lib.kasuga.create.registration.edge_point.TrackEdgePointReg` | Attach to a mod `Registry`; hooks core `RegistrationStage.REGISTER_EVENT` directly | Registers custom track-edge payloads (`EdgeExtraPayloadRegistry.register(...)`) and edge-point types (`EdgePointType.register(...)`) without going through `PREPARE` / `CreateRegistrationEvent` | Owned by the mod `Registry` |
| `lib.kasuga.create.content.train.device.TrainDeviceRegistry` | `register(ResourceLocation, TrainDeviceSystemType<?>, boolean contextDevice)` | Registers train device system types (optionally auto-instantiated as per-train context devices) | Static global maps (duplicate ID/type forbidden) |
| `lib.kasuga.create.content.train.graph.EdgeExtraPayloadRegistry` | `register(ResourceLocation, EdgeExtraPayloadType<?>)` | Registers custom synced payloads on `TrackEdge`s | Static global maps |
| `lib.kasuga.create.content.train.signal.BoundarySegmentRegistry` | `register(featureLocation, EdgePointType, Function<UUID, CustomTrackSegment>)`, `registerUpdateListener(...)` | Registers custom signal boundary track segments and update listeners | Static global maps |
| `lib.kasuga.create.client.registration.ClientBogeyRendererRegistry` | `@BeanOnlyIn.Client` singleton; `register(BogeyRendererHandle, Supplier<BogeyRenderer>, BogeyVisualizer)` | Registers client bogey renderers and Flywheel visualizers | Physical client only |
| `lib.kasuga.create.client.rendering.edge_point.EdgePointOverlayRendererRegistry` | Unannotated plain instance helper class; `register(EdgePointType<?>, EdgePointOverlayRenderer)` / `getRendererFor(...)` | Thread-safe `WeakHashMap` registry for track edge-point overlay renderers; currently has no global/DI singleton (`@Context`/`@Singleton`/`@BeanOnlyIn`) or active callers in `modules/` (`EdgePointBlockEntityRenderer` does not invoke it) | Caller-held instance (no global DI singleton wired) |
| `lib.kasuga.create.interaction.ContraptionShapeProvider` | Implement on contraption-compatible blocks | Supplies custom interaction shapes inside Create contraptions | Per-block instance |

## Related Pages

- [Lifecycle and Initialization Order](lifecycle.md)
- [Configuration Options](configuration.md)
- [Sidedness Model](sidedness.md)
- [Kasuga Custom Rendering Specification](effect-rendering.md)
- [Data-Driven Extension API](data-driven/api-extension.md)
- [Rendering backend integration reference](rendering-backends.md)
