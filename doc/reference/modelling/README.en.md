# Modelling Module

`modules/modelling` provides KasugaLib's client-side model loading, model-instance lifecycle, Minecraft renderer backend, texture atlas integration, and related animation/physics features.

For block entities that drive models through Proxy Sessions, start with
[Proxy principles](../../explanation/proxy/README.md) and the
[Proxy integration guide](../../guides/proxy-integration.md).

This document describes the standard integration path for a mod that ships models with its resource pack, plus the validation and debugging methods to use while developing the module itself. The implementation is client-only: do not access its rendering APIs from a dedicated server.

## Prerequisites

- Java 21. The module's Gradle toolchain is pinned to Java 21.
- A client resource reload must complete before a scanned model can be instantiated. In a development client, restart the game or press `F3 + T` after changing model assets or `model_proxy.json`.
- The module is initialized by `lib.kasuga.rendering.models.mc.Constants`; it registers the built-in pipelines and the resource reload listener.

## Loading A Model From Resources

### 1. Add the model asset

Place the model in your own mod's resource directory. For example:

```text
src/main/resources/assets/examplemod/models/vehicles/engine.bbmodel
```

Its model key is therefore:

```text
examplemod:models/vehicles/engine.bbmodel
```

The path must include the `models/` prefix and the file extension. Do not use the vanilla model identifier convention (`examplemod:vehicles/engine`) for this API.

### 2. Opt in through `model_proxy.json`

The scanner only loads resources matched by:

```text
src/main/resources/assets/<namespace>/models/model_proxy.json
```

The JSON object maps a namespace to an array of resource-path patterns. A minimal configuration for the file above is:

```json
{
  "examplemod": [
    "models/vehicles/**/*.bbmodel"
  ]
}
```

Patterns are relative to the resource namespace:

| Pattern | Meaning |
| --- | --- |
| `*` | Any characters except `/` |
| `**` | Any characters across directories |
| `?` | One character |
| `!pattern` | Exclude resources matching `pattern` |

For example, `"models/**/*.obj"` includes OBJ files anywhere below `models`, while `"!models/legacy/**"` excludes the legacy subtree. The loader combines the namespaces declared by the proxy configurations it reads into one scanner configuration.

### 3. Use a supported extension

The built-in router (`PipelineRegistry.BUILTIN_ROUTES`) chooses a pipeline by file extension:

| Extension | Pipeline ID | Loader / Notes | Source form |
| --- | --- | --- | --- |
| `.geo.json` | `be` | `BEModelLoader` (Bedrock) | JSON |
| `.json` | `je` | `JEModelLoader` (Java Edition) | JSON |
| `.obj` | `obj` | `KsgObjLoader` (Wavefront OBJ) | Text |
| `.mmd.zip` | `pmx` | `KsgPmxLoader` (PMX/MMD archive) | ZIP |
| `.glb`, `.gltf` | `gltf` | `KsgGltfLoader` (glTF) | Binary |
| `.bbmodel` | `bbmodel` | `KsgBbModelLoader` (Blockbench native model) | Text |
| `.mtb`, `.fmtb` | `fmt_archive` | `FmtArchiveLoader` (ZIP archive containing `Model.txt` or `model.jtmt` and textures) | ZIP |
| `.bob`, `.beo`, `.fmf` | `fmt_binary` | `FmtBinaryLoader` (FMT big-endian binary stream) | Binary |

`.geo.json` is routed before generic `.json`. For `.bob`/`.beo`, `FmtBinaryLoader` resolves a sibling external texture by stripping the `models/` prefix (`textures/<path>.png`), falling back to the missing sprite when absent; the codebase also contains a standalone text-stream MTB loader class `KsgMtbLoader`, which is not wired into `PipelineRegistry.BUILTIN_ROUTES`. In addition, `PipelineRegistry.ASSEMBLY` (`"assembly"`) is a derived assembly pipeline accessed through `PipelineRegistry.assemblies()` (`McModelAssemblies`) and `McModelHandle.ofAssembly(...)` rather than an extension-routed file loader. For an unsupported format, register a pipeline and a route through `PipelineRegistry` during client initialization.

## Creating And Rendering An Instance

After the resource reload has published the model, resolve its pipeline, create an instance, then add that instance to the Minecraft bridge/backend. The built-in names are `mc_bridge` and `mc_backend`.

```java
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;

ResourceLocation modelKey = ResourceLocation.fromNamespaceAndPath(
        "examplemod", "models/vehicles/engine.bbmodel"
);
ResourceLocation instanceKey = ResourceLocation.fromNamespaceAndPath(
        "examplemod", "engine_at_spawn"
);

ModelPipeLine<ResourceLocation, ResourceLocation> pipeline =
        PipelineRegistry.resolve(modelKey);
if (pipeline == null || !pipeline.hasModel(modelKey)) {
    // The proxy rule did not match, or resource reload has not completed yet.
    return;
}

if (!pipeline.hasInstance(modelKey, instanceKey)) {
    Transform transform = new Transform().translate(x, y, z);
    ModelInstance instance = pipeline.createInstance(modelKey, instanceKey, transform, null, null);
    if (instance != null) {
        pipeline.addToRenderer(modelKey, instanceKey, "mc_bridge", "mc_backend");
    }
}
```

Call this only on the client-side game/render flow. `createInstance` returns `null` when the model key is not published. Reuse a stable `instanceKey`; creating another instance with the same key replaces the pipeline's lookup entry without automatically removing the old renderable.

When an instance is no longer needed, remove it through the pipeline rather than retaining and closing it yourself:

```java
pipeline.removeInstance(modelKey, instanceKey);
```

This removes it from every registered backend and closes its runtime animation/physics state.

## Blockbench (`.bbmodel`)

The `bbmodel` pipeline supports native Blockbench JSON with cube and mesh elements, nested outliner groups, element/group visibility, rotations, per-face texture selection, face UV rotation, external textures, and embedded `data:image/...` textures.

Use `face.texture` as the texture-array index written by Blockbench. For external texture sources, resource locations may be written with or without `textures/` and `.png`; the loader normalizes them to atlas identifiers. For example, both `examplemod:textures/vehicles/engine.png` and `examplemod:vehicles/engine` resolve to the sprite `examplemod:vehicles/engine`.

Blockbench records UV coordinates in texture pixels. `KsgBbModelLoader` converts them to normalized UVs using the texture's **UV canvas**: `uv_width`/`uv_height` when the file declares them, otherwise the declared texture size, otherwise the project `resolution`. The canvas is deliberately independent of the decoded image, because Blockbench stretches an image over its canvas — `qj_bogey_main.bbmodel` keeps 64×64 pngs on a 128×128 canvas whose face UVs reach 128, so dividing by the image size would sample the wrong region. This conversion is intentionally local to the bbmodel loader. Do not add pixel-UV normalization in `FlatModelData` or another shared backend path, because those paths are also used by OBJ, PMX, glTF, Bedrock, and Java Edition models.

Cube faces follow Blockbench's `CubeFace.UVToLocal` convention: the `uv` rectangle is mapped so that `u` points to the viewer's right and `v` downward as seen from outside the cube (top face `u=+X, v=+Z`; bottom face `u=+X, v=-Z`), and `rotation` turns the texture clockwise. Each face is emitted top-left → bottom-left → bottom-right → top-right, which is also the winding the backend needs for outward normals.

Element, group and animation rotations are used exactly as stored (no sign flips) and composed intrinsic Z → Y → X, i.e. the matrix `Rz·Ry·Rx` that three.js `Euler.order = "ZYX"`, Minecraft's `ModelPart` (`ZP`/`YP`/`XP` order) and `RotHelper.rotation` all use. Each element rotates around its own `origin`, inside its parent group's already-rotated frame, so nesting is relative. Only rotations that span more than one axis can tell the orders apart, which is why a wrong order shows up as "some parts are rotated wrong". Blockbench's Bedrock export writes negated X/Y angles (`test_fan_be.bbmodel` `[0,45,0]` ↔ `test_fan_be.geo.json` `[0,-45,0]`), so the sign flips in `RotHelper` belong to the Bedrock loaders — do not copy them here.

The repository has runnable content-testing examples in:

```text
modules/modelling/src/contentTesting/resources/assets/kasuga_lib/models/block/test/blockbench/
```

## Lighting Modes, Global Batching, Assemblies, and Live Sync

### Lighting Modes (`ModelLightingMode` / `ModelLighting` / `ModelLightingDefaults`)

- `ModelLightingMode` (`lib.kasuga.rendering.models.uml.backend.ModelLightingMode`) defines the per-instance lighting intent: `PBR` (default) and `BLOCK` (vanilla block/item shading).
- `ModelLightingDefaults.register(ResourceLocation model, ModelLightingMode mode)` registers content-owned default lighting modes per model resource ID; defaults survive resource reloads, and `KasugaModelPipelines.createAndBind` applies `ModelLightingDefaults.resolve(modelLoc)` automatically when creating instances (defaulting to `ModelLightingMode.PBR` when unregistered).
- Hosts can override an instance or handle via `ModelInstance.setLightingMode(mode)` or `McModelHandle.setLightingMode(mode)` (buffered until mount and reapplied after resource replacement); data-driven `proxy_model_be` block entities expose `"pbr"` or `"vanilla_block"` under `params.lighting_mode`.
- `ModelLighting` (`lib.kasuga.rendering.models.mc.backend.ModelLighting`) packages the mode, item flag, and directional shade factors (`downShade`, `upShade`, `zShade`, `xShade`) as an immutable record constructed via `ModelLighting.world(mode, level)` (sampling `level.getShade(...)`) or `ModelLighting.item(mode)`. It forms part of `GlobalModelBatcher`'s batch key and drives `ksg_LightingMode` (`0` = PBR, `1` = world block shading, `2` = vanilla item diffuse lighting) and `ksg_BlockShade` on `KasugaShaderInstance` / `KasugaGlobalBatchShaderInstance`.

### Global Batching and Automatic Instancing (`GlobalModelBatcher` / `FlatModelData` / `KasugaGlobalBatchShaderInstance`)

- On the non-Iris `OPAQUE` and `MASK` passes, `MCBackend.renderObjectsInternal` collects eligible instances into `GlobalModelBatcher`; `TRANSLUCENT` is excluded from global batching so WBOIT and sorted translucent ordering remain intact.
- `GlobalModelBatcher` groups draws by `BatchKey` (pass, `VertexFormat.Mode`, emissive strength, ambient light enhancement, `DepthBias`, and `ModelLighting`), packs per-object transforms, normal matrices, brightness, bone offset, GPU-skinning flag, lightmap, and overlay into the `ksg_InstanceData` TBO (`9` texels / `36` floats per object, texture unit `10`), and packs bone matrices into the `ksg_BoneTransforms` TBO (`36` floats per bone, texture unit `11`), uploaded via `TextureUploadRing` and rendered with `KasugaGlobalBatchShaderInstance` (`kasuga_lib:ksglib_global_batch`).
- **Automatic instancing**: When `KasugaGlobalBatchShaderInstance` exposes `ksg_Instanced`, contiguous GPU-skinned (`!usesCpuSkinning()`) runs in a batch share the same `Model`, and `FlatModelData.shareBatchGeometry(...)` confirms identical vertex payloads (ignoring per-instance packed `UV1`/`UV2` lightmap and overlay shorts that are fetched from `ksg_InstanceData`), `GlobalModelBatcher` issues a shared-VBO instanced draw (`GL31.glDrawElementsInstanced` with `ksg_Instanced = 1` reading `gl_InstanceID`, instead of merging vertices and patching `UV1.x`) whenever `shouldInstance(...)` holds (at least `2` objects and either the whole batch is one run or `(count - 1) * meshBytes >= 16 KiB`). Chunks split at `MAX_OBJECTS_PER_BATCH` (`Short.MAX_VALUE`) and the hardware `GL_MAX_TEXTURE_BUFFER_SIZE` limit.

### Model Assemblies and Dummy Transform Groups (`McModelAssemblies` / `McDummyModels`)

- `McModelAssemblies` adapts `ModelAssemblyRegistry<McModelAssemblies.Reference, ResourceLocation>` and `ModelAssemblyCache` onto `PipelineRegistry.ASSEMBLY` (returned by `PipelineRegistry.assemblies()`). It supports referencing published models via `McModelAssemblies.Reference(model, modelName)`, programmatic `register`/`unregister`, atomic resource updates (`replaceResourceDefinitions` / `publishResourceGeneration`), dependent-model invalidation on source changes, and `McModelHandle.ofAssembly(...)` / `switchAssembly(...)`. See [Model assembly and outfit cache API](../model-assembly.md) and [Minecraft outfit resources and instance switching API](../minecraft-model-assembly.md).
- `McDummyModels.create()` / `McDummyModels.create(@Nullable Transform rootTransform)` creates a non-renderable transform-only `DummyModelInstance` (`Model.isRenderable() == false`, no vertices, meshes, or bone TBOs) and registers it with `MinecraftModelRuntime` for automatic per-frame evaluation; callers can add runtime anchors (`defineAnchor`, `setAnchorTransform`) and attach children (`attach(anchorName, child[, localOffset])`), and `close()` detaches children while unregistering the dummy. See [Anchor and Minecraft equipment attachment](../anchors.md).

### Live Local Model Sync (`LiveModelSync`)

- `LiveModelSync` (`lib.kasuga.rendering.models.mc.component.LiveModelSync`) provides an opt-in local development bridge: it opens `resourcepacks/kasuga_live` as a `DirectoryVfs`, mounts it via `ResourceSystem.mountClientScoped(...)`, ensures `pack.mcmeta` (`pack_format: 34`) and the `file/kasuga_live` pack selection, and polls `.kasuga-sync.json` (`version: 1`, `generation`, up to 8 KiB) every `100 ms` on `ClientTickEvent.Post` while no loading overlay is active.
- `LiveModelSyncState` applies a `350 ms` debounce and single-flight gate before calling `Minecraft#reloadResourcePacks()`, logging `KASUGA_LIVE_MODEL_RELOAD_COMPLETE` on completion.
- Client commands: `/kasuga_models live start`, `/kasuga_models live stop`, `/kasuga_models live status`.

## Development And Debugging

Run commands from the repository root. On Windows use `gradlew.bat`; on macOS/Linux use `./gradlew`.

### Compile And Unit Tests

```powershell
.\gradlew.bat :modules:modelling:compileJava
.\gradlew.bat :modules:modelling:test
.\gradlew.bat :modules:modelling:modelUnitTest
```

To run only the Blockbench loader tests:

```powershell
.\gradlew.bat :modules:modelling:test --tests lib.kasuga.rendering.models.mc.typo.bbmodel.BbModelDefinitionTest
```

`modelUnitTest` is the module's pure-Java test task. The regular `test` task uses the configured NeoForge test runtime.

### Start The Client With A Selected Smoke Model

The `contentTesting` development client displays model smoke tests by default; production code does not create these instances. The default selection is `bbmodel`; pass a filename or a complete resource path to show exactly one Blockbench model in front of the player:

```powershell
.\gradlew.bat :modules:modelling:runClient `
  '-PkasugaTestModel=bbmodel' `
  '-PkasugaTestBbmodel=qj_bogey_main.bbmodel'
```

The short `kasugaTestBbmodel` value is resolved under:

```text
models/block/test/blockbench/
```

Alternatively pass `models/vehicles/engine.bbmodel`. The selected resource must be included by the active `model_proxy.json` before the client starts; embedded textures are registered while the resource reload builds the atlas, so loading a new bbmodel after the atlas has been published is not supported.

Other built-in smoke selections are `obj`, `be`, and `je`; any other value takes the MMD test path:

```powershell
.\gradlew.bat :modules:modelling:runClient '-PkasugaTestModel=obj'
```

Set `-Dkasuga.renderTestModels=false` on the JVM to suppress all hardcoded smoke models when investigating unrelated rendering behavior.

### Logs And Failure Triage

The client run directory is:

```text
modules/modelling/run/client/
```

Inspect `logs/latest.log` after a resource reload or client run.

| Symptom | Checks |
| --- | --- |
| Model is absent | Confirm the exact `ResourceLocation`, make sure `model_proxy.json` matches it, then restart/reload resources. Look for the warning `Test bbmodel ... is unavailable after resource reload`. |
| Wrong model appears | Ensure the `kasugaTestModel` and `kasugaTestBbmodel` Gradle properties are both set. A short bbmodel name always resolves under the test Blockbench directory. |
| Green/missing texture | Verify external texture namespace/path, check that the texture exists below `assets/<namespace>/textures`, and search the log for atlas/sprite errors. |
| Texture appears tiled or granular | Check the texture's UV canvas, not just the image: the loader normalizes pixel UVs by `uv_width`/`uv_height` (falling back to the declared size and the project `resolution`), so a texture whose image is smaller than its canvas must still have all face UVs inside the canvas. Do not compensate by changing shared backend UV logic. |
| Faces are mirrored, rotated, or lit from the wrong side | Face `uv` rectangles are mapped Blockbench-style (`u` = viewer's right, `v` = down, viewed from outside; `rotation` is clockwise) and emitted in outward-wound corner order. Compare against `KsgBbModelLoaderFaceUvTest`, and do not reorder a single face's corners without moving its UVs with them. |
| Single-axis rotations look right but some rotated parts are wrong | Those parts carry a rotation on two or three axes, so the euler order is what differs: it must be intrinsic Z → Y → X (`Rz·Ry·Rx`, `KsgBbModelLoaderRotationTest`). The angles themselves are used unflipped; negating X/Y is the Bedrock loaders' job (`RotHelper`), because Blockbench's Bedrock export already writes negated X/Y. |
| Model exists but is not visible | Check `pipeline.hasModel`, `pipeline.hasInstance`, then `pipeline.isRendering(modelKey, instanceKey, "mc_backend")`. Confirm the transform is in front of the camera and use `removeInstance` before recreating a changed instance. |
| Loader error | Search `latest.log` for `Invalid Blockbench model`, `Unable to decode embedded texture`, and the model's resource path. |

## Relevant Source Locations

| Area | Source |
| --- | --- |
| Client initialization and smoke renderer | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/Constants.java` |
| Built-in pipelines and extension routes | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/registry/PipelineRegistry.java` |
| Model lifecycle API | `modules/modelling/src/main/java/lib/kasuga/rendering/models/uml/dynamic/ModelPipeLine.java` |
| Blockbench loader | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/typo/bbmodel/KsgBbModelLoader.java` |
| FMT archive / binary / MTB loaders | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/typo/fmt/FmtArchiveLoader.java`, `FmtBinaryLoader.java`, `mtb/KsgMtbLoader.java` |
| Lighting, batching, assemblies, dummies, and live sync | `ModelLightingDefaults.java`, `ModelLighting.java`, `GlobalModelBatcher.java`, `McModelAssemblies.java`, `McDummyModels.java`, `LiveModelSync.java` |
| Resource-proxy matcher | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/source/model/ModelProxyConfig.java` |
| Iris compatibility and built-in effects/audio | [Iris Compatibility Layer](../iris-compat.md), [Built-in Effects and Spatial Audio](../builtin-effects-and-audio.md) |
