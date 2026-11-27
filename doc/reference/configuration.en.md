# Configuration Options

This page catalogs all configuration mechanisms and options verified in the KasugaLibNext codebase, covering NeoForge `ModConfigSpec` (registered via Micronaut `Configurable` beans), JSON/TOML runtime configuration files, and JVM system properties / environment variables.

> Note: There is no `CoreConfig` class in `modules/core`. Core rendering configuration lives in `ClientRenderingRegistry`, while other subsystems expose their settings through module-owned `Configurable` beans, JSON persistence files, or JVM properties.

---

## 1. NeoForge `ModConfigSpec` and the `Configurable` SPI

During the DI startup phase (see [Lifecycle and Initialization Order](lifecycle.md)), `ModApplicationContext.init(ApplicationContext, IEventBus, ModContainer)` invokes `lib.kasuga.inject.auto_configure.Configurable.configure(context, modEventBus, modContainer)` right after `context.start()`:

1. Collects all active beans implementing `Configurable` via `context.getBeansOfType(Configurable.class)` (respecting `@BeanOnlyIn`, `@CreateModBeans`, and other Micronaut conditions).
2. Allocates one `ModConfigSpec.Builder` for each `ModConfig.Type` (`CLIENT`, `SERVER`, `COMMON`, `STARTUP`).
3. Calls `onConfigure(ModConfig.Type, ModConfigSpec.Builder)` on every bean, which dispatches to `configureClient`, `configureServer`, `configureCommon`, or `configureStartup`.
4. Calls `modContainer.registerConfig(type, spec)` for every built `IConfigSpec` that is non-empty (`!spec.isEmpty()`).

### 1.1 `CLIENT` Options (Physical Client Only)

Registered by `@BeanOnlyIn.Client` beans and active only on the physical client:

| Config Class (FQCN) | TOML Key | Type | Default / Constraint | Description |
| --- | --- | --- | --- | --- |
| `lib.kasuga.registration.beans.rendering.ClientRenderingRegistry` | `ignore_block_entity_renderer` | `boolean` | `false` | When `true`, skips `BlockEntityRenderer` registration through `ClientRenderingRegistry.registerBlockEntityRenderer(...)` and logs a `WARN` |
| `lib.kasuga.rendering.effect.builtin.weather.WeatherFilterConfig` | `enable_weather_filter` | `boolean` | `true` | Enables 3D world-space weather occlusion for indoor entity/scene spaces (rain, snow, splashes, and muffled rain audio) |
| `lib.kasuga.rendering.effect.builtin.weather.WeatherFilterConfig` | `included_entity_types` | `List<String>` | `[]` (entries validated via `ResourceLocation.tryParse`) | Optional entity type IDs that also occlude rain and snow (e.g., `"minecraft:boat"`) |
| `lib.kasuga.rendering.effect.builtin.weather.WeatherFilterConfig` | `excluded_entity_types` | `List<String>` | `[]` (entries validated via `ResourceLocation.tryParse`) | Entity type IDs excluded from automatic weather occlusion (e.g., `"create:carriage_contraption"`) |

> Outside a bootstrapped Micronaut / FML config environment (such as plain JVM unit tests), `WeatherFilterConfig` safely falls back to defaults (`isWeatherFilterEnabled()` returns `true`; included/excluded ID sets are empty).

### 1.2 `SERVER` Options (Dedicated Server Only)

| Config Class (FQCN) | TOML Key | Type | Default | Description |
| --- | --- | --- | --- | --- |
| `lib.kasuga.scripting.ScriptingServerApplication` | `ignore_scripting_errors` | `boolean` | `false` | Annotated with `@BeanOnlyIn.DedicatedServer`. When `ServerResourceReloadFinishEvent` detects missing script engines: logs `WARN` if `true`, or throws `RuntimeException` if `false`. Note: non-empty `packageSystem.getEngineErrors()` still throws `RuntimeException` |

### 1.3 `COMMON` Options (`CreateConfig`)

`lib.kasuga.create.CreateConfig` is annotated with `@Context` and `@CreateModBeans` (active only when `ModList.get().isLoaded("create")`). Several defaults delegate to `KasugaLibCreateApi`:

| TOML Key | Type | Default / Range | Static Accessor (`CreateConfig`) | Description |
| --- | --- | --- | --- | --- |
| `enable_multi_bogey_carriage` | `boolean` | `KasugaLibCreateApi.getMultiBogeyCarriageDefault()` (initially `false`) | `isMultiBogeyCarriageEnabled()` | Allows train carriages to be assembled with more than two bogeys; when `false`, assembly, persistence, sync, and rendering use the vanilla two-bogey path |
| `enable_carriage_y_offset` | `boolean` | `KasugaLibCreateApi.getCarriageYOffsetDefault()` (initially `false`) | `isCarriageYOffestEnabled()` | Allows carriages to have vertical (Y-axis) offsets |
| `max_bogey_offset` | `double` | `1.0D` (`[0.0D, Double.MAX_VALUE]`) | `maxBogeyOffset()` | Maximum lateral offset (in blocks) an interior bogey may shift from its carriage-local position on curves |
| `max_carriage_y_offset` | `double` | `0.5D` (`[0.0D, Double.MAX_VALUE]`) | `maxCarriageYOffset()` | Maximum vertical distance (in blocks) a carriage body may offset from its bogeys in either direction |
| `derailed_on_bogey_offset_overflow` | `boolean` | `KasugaLibCreateApi.getDerailedOnBogeyOffsetDefault()` (initially `false`) | `derailedOnBogeyOffsetEnabled()` | Whether a train derails when bogeys reach their maximum offsets |
| `enable_contraption_lod` | `boolean` | `true` | `isContraptionLodEnabled()` | Sends distant LOD render meshes for active Create contraptions |
| `enable_contraption_weather_filter` | `boolean` | `KasugaLibCreateApi.getContraptionWeatherFilterDefault()` (initially `true`) | `isContraptionWeatherFilterEnabled()` | Automatically occludes rain and snow inside Create contraptions (e.g., trains) |
| `enable_sable_weather_filter` | `boolean` | `true` | `isSableWeatherFilterEnabled()` | Automatically occludes rain and snow inside Sable / Aeronautics physics sublevels |

#### Downstream Default Overrides (`KasugaLibCreateApi`)

Dependent mods can override the generated defaults for four `COMMON` keys during mod construction (before configs load) via `lib.kasuga.create.KasugaLibCreateApi`. Explicit entries in the user's TOML config file still take priority:

- `KasugaLibCreateApi.setMultiBogeyCarriageDefault(boolean)`
- `KasugaLibCreateApi.setCarriageYOffsetDefault(boolean)`
- `KasugaLibCreateApi.setDerailedOnBogeyOffsetDefault(boolean)`
- `KasugaLibCreateApi.setContraptionWeatherFilterDefault(boolean)`

No bean currently implements `configureStartup(ModConfigSpec.Builder)`, so the `STARTUP` spec remains empty and is not registered.

---

## 2. JSON and TOML File Configurations

| Class / Subsystem | File Path | Load & Reload Timing | Purpose and Reference |
| --- | --- | --- | --- |
| `lib.kasuga.rendering.models.mc.api.pbr.PbrUserConfig` | `<gameDir>/config/kasuga-pbr.json` | `KasugaModelManager.reload(...)` (client resource reload, `/kasuga_pbr reload_config`) | Player-editable PMX-to-PBR conversion overrides. Auto-generated with an example rule if missing; registers into `PbrConversionRegistry` as `kasuga_lib:user_config` at priority `1000`. See [KasugaLib PMX → PBR Conversion](pbr.md) |
| `lib.kasuga.rendering.effect.shader.ShaderParameterPersistence` | `<configDir>/kasuga_lib/shader-parameters.json` | `Constants.onClientSetup` calls `initialize()`; updates debounce asynchronously (`200ms`), or write synchronously via `flush()` | Persists non-default finite parameters on `RenderShaderHandle` (`version = 1`, `shaders.<shader_id>.<param> = [double...]`). Edited via `/kasuga_effects parameter` or `ShaderParameterScreen` |
| `lib.kasuga.rendering.models.mc.source.model.ModelProxyConfig` / `ModelProxyConfigLoader` | `assets/<namespace>/models/model_proxy.json` | Scanned and merged across namespaces in `KasugaModelManager.reload(...)` | Glob inclusion/exclusion rules (`*`, `**`, `?`, `!` prefix) selecting models handled by Kasuga model pipelines. See [Modelling Module](modelling/README.md) |
| `lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig` | Resource-pack JSON (`ResourceLocation` supplied by caller) | `MinecraftRagdollConfig.load(ResourceManager, ResourceLocation)` / `fromJson(JsonObject)` | Configures ragdoll bodies, `limits`, `include_secondary_bodies`, `simulation`, `collision`, `environment`, `initial_state`, `dragging`, and `sleeping`. See [PMX and glTF Ragdoll Configuration](mmd-ragdoll.md) |
| `lib.kasuga.scripting.discovery.PackageSystem` / `PackageInfo` | `package.toml` at a script package root (default `scripts/` in a resource pack) | `ScriptRuntime` (legacy scan) or `ManagedScriptEntryPoint` (managed resource generations) | Top-level keys `name`, `engine`, `description`, `version`, `main`, `workspaces`, and `[entry]` lists `server`, `client`, `common` |

---

## 3. JVM System Properties and Environment Variables

Low-level rendering, physics, and headless client controls read JVM `-D` properties and environment variables:

| System Property (`-D`) | Environment Variable | Default | Source Class | Description |
| --- | --- | --- | --- | --- |
| `kasuga.shaderPreparationWorkers` | — | `0` (automatic: `min(max(1, (cpus+1)/2), 4)`) | `ShaderPreparationScheduler` | Background CPU worker count for Java Shader DSL translation; positive values are capped at `availableProcessors`. Also tunable at runtime via `/kasuga_effects workers <count>` |
| `kasuga.modelGpuSkinning` | `KASUGA_MODEL_GPU_SKINNING` | `false` | `KsgVertexBuffer` | Env var takes precedence over property. Enables GPU skinning (`isGpuSkinningEnabled()` returns `false` when Iris is active; Iris paths query `isIrisGpuSkinningEnabled()`) |
| `kasuga.layeredTransparency` | — | `true` | `LayeredTransparency` | Enables layered translucent replay; automatically disabled when Iris, Fabulous graphics (`Minecraft.useShaderTransparency()`), or Voxy is active |
| `kasuga.headless` | — | `"disabled"` | `HeadlessClient` | Headless client backend: `"disabled"`, `"hidden"`, or `"egl"` (Linux only; requires `org.lwjgl.glfw.libname` set to the pbuffer GLFW library) |
| `kasuga.box3d.library` | — | Unset (loads `/native/<os>-<arch>/` or `System.loadLibrary`) | `NativeBox3D` | Explicit path to the native Box3D shared library |
| `kasuga.profileModel` | `KASUGA_PROFILE_MODEL` | `false` | `ModelProfiler` | Env var takes precedence. Enables periodic `[model-profile]` timing logs |
| `kasuga.profileModel.intervalMs` | `KASUGA_PROFILE_MODEL_INTERVAL_MS` | `2000` | `ModelProfiler` | Env var takes precedence. `[model-profile]` reporting interval in milliseconds (minimum `1`) |
| `kasuga.profileModel.recalculateDynamicTangents` | `KASUGA_PROFILE_MODEL_RECALCULATE_DYNAMIC_TANGENTS` | `false` | `CpuVertexSkinning` | Env var takes precedence. Recomputes dynamic tangents during CPU skinning |

## Related Pages

- [Lifecycle and Initialization Order](lifecycle.md)
- [Sidedness Model](sidedness.md)
- [KasugaLib PMX → PBR Conversion](pbr.md)
- [Managed Effects and Post-Processing](effects-and-postfx.md)
- [PMX and glTF Ragdoll Configuration](mmd-ragdoll.md)
