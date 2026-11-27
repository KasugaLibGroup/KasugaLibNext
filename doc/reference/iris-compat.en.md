# Iris Compatibility Layer Reference

`modules/iris-compat` isolates direct dependencies on Iris and Sodium internal classes, vertex formats, framebuffers, and mixins in a separate subproject so `modules/modelling` can load safely and fall back to native rendering whenever Iris is absent or its ABI changes.

## Module Isolation and Packaging

- `modules/iris-compat/build.gradle` declares only `compileOnly` dependencies on `:modules:modelling` (compiled classes only), `:modules:core`, `:modules:inject`, `:modules:utils`, `iris-neoforge-mc1.21.1-1.8.12.jar`, and `sodium-neoforge-mc1.21.1-0.6.13.jar`.
- `modules/modelling/build.gradle` bundles `:modules:iris-compat`'s `main` output into its `jar`, `shadowJar`, and dev `neoForge.mods`; `modules/library/build.gradle` pulls it in via `useModProject(project(':modules:iris-compat'))`. As a result, `modules/modelling`'s own compile classpath has no direct dependency on Iris types.

## Runtime Bridge and Capability Probing

`IrisCompat` (in `modules/modelling`) runs `IrisCompat.onStart()` during client reload-listener registration:

1. Checks `ModList.get().getModContainerById("iris")`.
2. When Iris is present, uses `CompatCapability<IrisIntegration>` (`lib.kasuga.core.compat.CompatCapability`) to probe and instantiate `lib.kasuga.compat.iris.IrisRenderingIntegration` by class name.
3. If the probe fails (`ReflectiveOperationException`, `InaccessibleObjectException`, `LinkageError`, or `SecurityException`), it logs a warning and leaves `adapter == null` without crashing client startup.

### `IrisRenderingIntegration`

`lib.kasuga.compat.iris.IrisRenderingIntegration` implements `IrisIntegration` as the unified adapter exposed by `modules/iris-compat`:

| Method | Responsibility and fallback behavior |
| --- | --- |
| `registerPbrLoader()` | Probes and registers `KasugaPBRLoader` via `CompatCapability<KasugaPBRLoader>` with `PBRTextureLoaderRegistry` for `KasugaTextureAtlas` (bridging `CombinedTextureManager`'s normal and specular atlases); disables Iris PBR with a warning on failure. |
| `isUsingShaderPack()` | Queries `IrisApi.getInstance()::isShaderPackInUse`; latches `packQueryFailed` on `LinkageError` and returns `false` to fall back to native rendering. |
| `supportsModelRendering()` | Caches `IrisModelFormats.probe()` via `CompatCapability<Boolean>`; logs once and returns `false` on failure. |
| `compatibleFormat(format, force)` | Delegates to `IrisModelFormats.compatibleFormat(format, force)`. |
| `createVertexBuffer(...)` | Constructs `IrisVertexBuffer`. |
| `framebufferUnavailable()` / `bindWorldFramebuffer()` | Delegates to `IrisWorldFramebuffer`. |
| `openCamera(shader)` | Verifies `IrisCameraMixinPlugin.supportsCamera()` and constructs `CameraIrisSession`, or throws `IOException` if unavailable. |
| `closeCameraBuffers(source)` | Calls `CameraIrisNativeBuffers.close(source)` to free Iris `MemoryTrackingBuffer` instances; latches `cameraBuffersUnavailable` with a warning on `LinkageError`. |

## Vertex Formats, Vertex Buffers, and World Framebuffer

### `IrisModelFormats`

`lib.kasuga.compat.iris.IrisModelFormats` probes and maps extended Iris vertex formats:

- `probe()` uses `MethodHandles.lookup()` and `CompatCapability.requireMethod(...)` to verify `ImmediateState.skipExtension`, `IrisVertexFormats` (`TERRAIN`, `ENTITY`, `GLYPH`, `MID_TEXTURE_ELEMENT`, `TANGENT_ELEMENT`, `ENTITY_ID_ELEMENT`), `CapturedRenderingState.INSTANCE` (with `getCurrentRenderedEntity`, `getCurrentRenderedBlockEntity`, and `getCurrentRenderedItem`), and `Iris.isPackInUseQuick()`.
- `compatibleFormat(format, force)` translates vanilla formats when a shader pack is active and extension is not skipped (or when `force == true`):
  - `DefaultVertexFormat.NEW_ENTITY` → `IrisVertexFormats.ENTITY`
  - `DefaultVertexFormat.BLOCK` → `IrisVertexFormats.TERRAIN`
  - `DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP` → `IrisVertexFormats.GLYPH`

### `IrisVertexBuffer`

`lib.kasuga.compat.iris.vertex.IrisVertexBuffer` implements `IVertexBuffer`, packing `FlatModelData` into the extended Iris vertex format and managing GPU uploads through a `FencedVertexBuffer` upload ring:

- Temporarily sets `ImmediateState.skipExtension` to `true` while packing so `BufferBuilder` writes the already-extended format directly; when `vertexCount >= multiThreadedThreshold` (`BackendInstance` passes `10000`), chunks are built concurrently on the supplied `ExecutorService` before copying into `packedVertices`.
- Populates `MID_TEXTURE_ELEMENT` (`midpointUv` averaged per `meshMode.primitiveLength`), `TANGENT_ELEMENT` (signed-byte normalized xyzw tangent, defaulting to `(1, 0, 0, 1)` when `tangOffset < 0`), and `ENTITY_ID_ELEMENT` (from `CapturedRenderingState.INSTANCE`).
- `updateGpuBuffer(dirtyVertices, forceUploadAll)` merges dirty vertex runs up to `maxMergeGap` for partial updates when fewer than 75% of vertices are dirty, and falls back to a full repack/upload at or above 75%.

### `IrisWorldFramebuffer`

`lib.kasuga.compat.iris.IrisWorldFramebuffer` binds the active Iris world framebuffer for weighted-blended OIT:

- Uses `CompatCapability<BooleanSupplier>` to link `Iris::getPipelineManager`, `PipelineManager::getPipelineNullable`, `IrisRenderingPipeline::bindDefault`, and `IrisApi.getInstance()::isRenderingShadowPass`.
- Returns `false` during shadow passes (`isRenderingShadowPass() == true`) or when no `WorldRenderingPipeline` is active.
- If the probe fails, a `LinkageError` occurs while binding, or the active pipeline is not an `IrisRenderingPipeline`, latches `framebufferUnavailable = true` and invokes `IrisOitCompat.reportFallback(...)` so `OitRenderer` drops cleanly to sorted translucency. See [Model Material Alpha Rendering Reference](render-alpha-passes.md#irisoitcompat-and-compatcapability-fallback-path) for full OIT fallback rules.

## Multi-Camera Isolation and Mixin ABI Gating

### `CameraIrisSession`

`lib.kasuga.rendering.output.mc.iris.CameraIrisSession` implements `CameraShaderSession` to keep shader-pack state isolated per camera view (see [Multi-camera output contract](multi-camera-output.md) and [How offscreen rendering, multi-camera, and shared output work](../explanation/offline-rendering-and-multi-cam.md)):

- When `CameraRenderSettings.Shader.pack()` is `null`, creates a `PipelineManager` backed by `VanillaRenderingPipeline`; otherwise resolves `shaders/` from the directory or ZIP archive (opening a separate `FileSystem` per pack path via `FileSystems.newFileSystem`) and builds an isolated `ShaderPack` and `IrisRenderingPipeline`.
- Each session owns its own `WorldRenderingSettings`, `CapturedRenderingState`, `SystemTimeUniforms.Timer`, `SystemTimeUniforms.FrameCounter`, and `CameraIrisShadowState` (saving and restoring `ShadowRenderer` and `ImmediateState` statics across `enter()` / `close()`).
- On the render thread, `enter()` returns a `Scope` that installs thread-local state and resets `ProgramUniforms.clearActiveUniforms()`; `Scope.beginFrame()` advances the session's `FrameCounter` and `Timer`; `finish()` calls `finalizeGameRendering()`; `close()` enters the owning session to execute `pipelines.destroyPipeline()` and closes any opened archive `FileSystem`.
- `CameraIrisSession.useMaterials(WorldRenderingSettings)` scopes the material map on Sodium asynchronous chunk-meshing workers without mutating global singletons.

### `kasuga_lib_iris.mixins.json` and `IrisCameraMixinPlugin`

`modules/iris-compat/src/main/resources/kasuga_lib_iris.mixins.json` sets `"required": false`, `"package": "lib.kasuga.compat.iris.mixin"`, `"plugin": "lib.kasuga.compat.iris.IrisCameraMixinPlugin"`, and leaves `"mixins"` empty so mixins are selected dynamically after bytecode inspection:

- Runs only on `MixinEnvironment.Side.CLIENT`; `IrisCameraMixinPlugin.supportsCamera()` reads bytecode headers (`ClassReader.SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES`) to verify exact class, field, and method descriptors across Iris (`Iris`, `CapturedRenderingState`, `SystemTimeUniforms`, `SystemTimeUniforms$FrameCounter`, `ShadowRenderer`, `ImmediateState`, `WorldRenderingSettings`, `PipelineManager`, `WorldRenderingPipeline`, `IrisRenderingPipeline`, `VanillaRenderingPipeline`, `ShaderPack`, `StandardMacros`, `ProgramUniforms`) and Sodium (`ChunkJobTyped.execute(ChunkBuildContext)`).
- Only when `supportsCamera()` succeeds does `getMixins()` enable the six core mixins (`WorldViewIrisMixin`, `WorldViewIrisStateAccessor`, `WorldViewIrisTimeAccessor`, `WorldViewIrisCounterAccessor`, `WorldViewIrisMaterialsMixin`, `WorldViewIrisChunkJobMixin`).
- Separately checks `hasCloudCameraAbi()` (`MixinCloudRenderer.hadShadersOn` and Sodium `CloudRenderer.render`) before appending `WorldViewIrisCloudMixin`, keeping the cloud hook independent of the core camera ABI gate.
- If any ABI check fails, `getMixins()` returns `List.of()` so startup succeeds; opening an isolated camera session later fails cleanly with `IOException` from `IrisRenderingIntegration.openCamera`.

## Relevant Source Locations

| Component | Source |
| --- | --- |
| Compatibility facade & OIT bridge | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/compat/iris/{IrisCompat,IrisIntegration,IrisOitCompat}.java` |
| Generic capability prober | `modules/core/src/main/java/lib/kasuga/core/compat/CompatCapability.java` |
| Iris implementation & vertex buffer | `modules/iris-compat/src/main/java/lib/kasuga/compat/iris/{IrisRenderingIntegration,IrisModelFormats,IrisWorldFramebuffer,KasugaPBRLoader,CameraIrisNativeBuffers}.java`, `vertex/IrisVertexBuffer.java` |
| Camera shader session & mixins | `modules/iris-compat/src/main/java/lib/kasuga/rendering/output/mc/iris/{CameraIrisSession,CameraIrisShadowState}.java`, `modules/iris-compat/src/main/java/lib/kasuga/compat/iris/IrisCameraMixinPlugin.java`, `modules/iris-compat/src/main/resources/kasuga_lib_iris.mixins.json` |
