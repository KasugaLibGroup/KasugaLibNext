# Built-in Effects, Water Simulation, and Spatial Audio Reference

This document covers the built-in black-hole post-processing pipeline, shallow-water free-surface simulation and optical rendering, positional spatial audio, and the `/kasuga_effects` diagnostics and shader-parameter interface in `modules/modelling` and `modules/render-core`. For general managed effects and post-processing graphs, see [Managed Effects and Post-Processing](effects-and-postfx.md).

## 1. Black-Hole Gravitational Lensing Post-Processing (`BlackHoleEffect` / `BlackHoleEffects`)

Package: `lib.kasuga.rendering.effect.builtin.blackhole` (initialized on client startup by `BlackHoleEffects.initialize(RenderPipelineRegistrar)` inside `Constants.onRegisterRenderPipelines`).

- Pipeline registration: registers the Java Shader DSL-generated fullscreen shader `kasuga_lib:black_hole` (`BlackHoleShaderProvider`) and priority-`1000` `PostProcessGraph` `kasuga_lib:black_hole_graph` with a screen-scale, depth-enabled, `LINEAR`-filtered managed target (`kasuga_lib:black_hole_scene`). Graph `prepare` projects active black holes into the current camera view and skips both copy and composite passes when none are visible.
- Capacity and ordering: `BlackHoleEffects.MAX_VISIBLE_BLACK_HOLES = 8`. Visible black holes are sorted by projected screen radius descending (ties broken by ID) and truncated to at most 8 entries, packing `16` floats per hole into the `HoleData` uniform array.
- Exposed parameters (`BlackHoleShaderProvider`): `LensingScale` (default `1.0`, range `[0, 3]`), `DiskBrightness` (default `1.0`, range `[0, 4]`), and `ChromaticScale` (default `1.0`, range `[0, 3]`), editable live via `/kasuga_effects` (`Parameters`) or `/kasuga_effects parameter ...`.

### `BlackHoleEffect` Parameters and Defaults

Immutable record constructed with `BlackHoleEffect.builder(ResourceLocation id, Vec3 position)` or `toBuilder()`:

| Field | Default | Constraints / Notes |
| --- | --- | --- |
| `eventHorizonRadius` | `1.0f` | `> 0`, event horizon radius in world units |
| `influenceRadius` | `4.0f` | `>= 1.0f`, multiplier relative to `eventHorizonRadius` |
| `distortionStrength` | `0.7f` | `>= 0` |
| `accretionRadius` | `1.65f` | `>= 1.0f`, accretion-ring center relative to `eventHorizonRadius` |
| `accretionWidth` | `0.28f` | `> 0`, accretion-ring half-width relative to `eventHorizonRadius` |
| `glowStrength` | `1.3f` | `>= 0` |
| `chromaticAberration` | `0.025f` | `>= 0`, RGB separation relative to `eventHorizonRadius` |
| `rotationSpeed` | `1.0f` | Finite angular speed multiplier |
| `glowColor` | `(1.0f, 0.32f, 0.06f)` | `BlackHoleEffect.Color(red, green, blue)`, each component `>= 0` |
| `depthTest` | `true` | Whether scene depth occludes the effect |
| `accretionDiskTilt` | `1.0f` | `(0, 1]`, screen-space minor/major axis ratio (`1` face-on, near `0` edge-on) |

### `BlackHoleEffects` Lifecycle and Commands

- `BlackHoleEffects.put(effect)`: adds or replaces an entry by `effect.id()`.
- `BlackHoleEffects.register(effect)`: returns an owned `BlackHoleEffects.Registration` (`id()`, `effect()`, `update(BlackHoleEffect)`, `close()`), where closing an older token cannot remove a newer replacement with the same ID.
- `BlackHoleEffects.remove(id)`, `clear()`, `size()`, `snapshot()`, `shader()`. All entries are automatically cleared on level change in `ClientTickEvent.Post`.
- Development-client commands (`BlackHoleDemo` in `contentTesting`, `AUTO_SPAWN = false`):

| Command | Behavior |
| --- | --- |
| `/kasuga_black_hole demo` | Spawns a single black hole ahead of the player camera |
| `/kasuga_black_hole orbit` | Starts a three-body orbiting black-hole demo |
| `/kasuga_black_hole massive` | Spawns a large tilted accretion-disk black hole |
| `/kasuga_black_hole clear` | Removes the demo black holes |
| `/kasuga_black_hole status` | Prints `BlackHoleEffects.shader()` readiness, generation, active count, and demo mode |

## 2. Shallow-Water Simulation and Optical Surface (`WaterEffects` / `WaterSurface` / `ShallowWaterGrid` / `WaterOptics`)

### CPU Shallow-Water Solver and Optics (`modules/render-core`)

Package: `lib.kasuga.rendering.water`, single-owner-thread with zero graphics or host dependencies:

- `ShallowWaterGrid(int size, float cellSize, float restDepth)`: solves conserved depth and horizontal momenta `(h, hu, hv)` on a flat bed with a 2D Rusanov finite-volume update (`size` in `8..512`, positive finite `cellSize` and `restDepth`, `GRAVITY = 9.81f`, periodic boundaries, adaptive CFL substeps via `step(float seconds)` for `[0, 1]`, damping `exp(-0.16 * dt)`).
  - Core methods: `displace(x, z, radius, amount)` (volume-conserving depression with surrounding positive ring), `impulse(x, z, radius, vx, vz)`, `shift(cellsX, cellsZ)` (shifts the moving simulation window while preserving existing world-space waves and filling new edges at `restDepth`), `height(x, z)`, `velocityX(x, z)`, `velocityZ(x, z)`, `cellDepth(x, z)`, `extent()`, `volume()`, `minimumDepth()`, `maximumDeviation()`, `isFinite()`, and `reset()`. This depth-averaged model does not represent overturning waves or spray.
- `WaterOptics(absorptionRed, absorptionGreen, absorptionBlue, scatterRed, scatterGreen, scatterBlue, shorelineFoam)`: absorption coefficients in `[0, 20]`, scattering and foam in `[0, 1]`; built-in presets `WaterOptics.DEFAULT` and `WaterOptics.CLEAR_COAST`.

### Client Water Post-Processing Pipeline (`modules/modelling`)

Package: `lib.kasuga.rendering.effect.builtin.water` (`WaterEffects.initialize` registers resource shader `kasuga_lib:water/clear_surface` and priority-`800` `PostProcessGraph` `kasuga_lib:water/graph`):

- `WaterEffects.open(ClientLevel level, double centerX, float bedY, double centerZ, int resolution, float cellSize, float depth)`: opens the active `WaterSurface` for `level` (at most one active surface per `ClientLevel`; requires `WaterEffects.ready()`). `ClientTickEvent.Post` steps active surfaces at `0.05f` seconds while unpaused and closes surfaces when switching levels; removing the last surface releases managed targets via `PostProcessTargetPool`.
- `WaterSurface` (`AutoCloseable`) chainable configuration:
  - `followPlayer(boolean)` / `playerInteraction(boolean)` (default `true`; generates foot ripples, imparts mild water velocity coupling, and shifts the `ShallowWaterGrid` window when the player moves more than `8` cells from center).
  - `renderEnabled(boolean)` (default `true`; hides the optical surface while preserving simulation state).
  - `optics(WaterOptics)` and `terrainHeight(DoubleBinaryOperator)` (supplies walkable bed elevation in world coordinates; defaults to flat `bedY`).
  - `interact(Vec3 position, Vec3 velocity, float radius, float displacement)`: injects local displacement and horizontal momentum within `±0.45 * extent()`.
  - Encodes `(cellDepth - restDepth + 0.25) * 2` as 16-bit RG bytes in a `DynamicTexture` (`HeightSampler`) for depth-tested refraction, Beer-Lambert absorption, and Fresnel sky reflection.
- `contentTesting` provides `/kasuga_cobalt_blue [stop]` and `/ksglib demo cobalt_blue [stop]` (requires a private singleplayer world, ready water shaders, Box3D native physics, and generated `assets/kasuga_demo/cobalt_blue/track.json` audio assets).

## 3. Scene Tracks and Spatial Audio (`SceneTrack` / `SpatialAudio`)

Package: `lib.kasuga.audio` (`Dist.CLIENT` only):

- `SceneTrack(String title, String artist, String album, float durationSeconds, ResourceLocation sound, ResourceLocation audioFile)`:
  - `SceneTrack.load(ResourceLocation metadata)` reads JSON metadata (`title`, `artist`, `album`, `duration_seconds`, `sound`, `audio_file`) from the client `ResourceManager`.
  - `available()` verifies both `BuiltInRegistries.SOUND_EVENT.containsKey(sound)` and the presence of `audioFile`.
  - `playAt(Vec3 position, float gain)` starts a positional `SoundSource.RECORDS` voice via `SpatialAudio.play(...)` (throws `IllegalStateException` if `available()` is `false`).
- `SpatialAudio`:
  - `SpatialAudio.play(SoundEvent sound, SoundSource category, Vec3 position, float gain)` validates finite position and `gain > 0`, returning an owned `SpatialAudio.Voice` (`AbstractTickableSoundInstance`, `AutoCloseable`, `relative = false`, `looping = false`, `Attenuation.LINEAR`). Streaming and mono spatialization are governed by the registered Ogg resource.
  - `SpatialAudio.activeCount()` returns active voices; `ClientTickEvent.Post` closes voices on level change or once stopped.
  - `SpatialAudio.Voice` exposes `seconds()`, `isPlaying()`, `failedToStart()` (`ticks > 60 && !observedPlaying`), `position()`, `spatial()` (`!relative`), and `close()`.

## 4. Effect Diagnostics, Parameter Editor, and `/kasuga_effects` CLI

Package: `lib.kasuga.rendering.effect.debug` (client commands registered in `Constants.onRegisterClientCommands`):

- **Client command tree `/kasuga_effects`**:
  - `/kasuga_effects`: opens the non-pausing `EffectDiagnosticsScreen` (`"Kasuga Effect Inspector"`, refreshes every `10` ticks).
  - `/kasuga_effects preload`: calls `RenderShaderRegistry.preloadPending()` to queue pending shaders for preloading.
  - `/kasuga_effects workers <count>`: calls `ShaderPreparationScheduler.configureWorkers(count)` (`0` selects automatic worker count).
  - `ShaderParameterCommands` subcommands:
    - `/kasuga_effects parameter list <shader>`
    - `/kasuga_effects parameter set <shader> <name> <values...>` (booleans accept `true`/`false`; vector/color/matrix components are space- or comma-separated)
    - `/kasuga_effects parameter reset <shader> <name>`
- **`EffectDiagnosticsScreen`**: tabs `Effects` (owner, pipeline ID, type, active/visible count, render time), `Shaders` (source kind, state, preload policy/priority, origin, generation, queue wait, prepare/compile times, exposed parameter count, errors, and worker/budget summary), and `Pipelines` (owner, stage/phase, priority, primitive mode, buffer size, cached variants), with `Parameters` (opens `ShaderParameterScreen`), `Preload pending`, and `Done` buttons.
- **`ShaderParameterScreen`**: non-pausing schema-driven editor listing every registered shader with exposed parameters (`< Prev` / `Next >` cycling, `Reset` / `Reset all`, boolean toggles, and per-component sliders labeled `Value`, `XYZW`, `RGBA`, or `Mij`). Edits mutate `RenderShaderHandle.parameters()` immediately for the next draw without recompiling and are persisted asynchronously by `ShaderParameterPersistence` to `config/kasuga_lib/shader-parameters.json`.

## Relevant Source Locations

| Area | Source |
| --- | --- |
| Black-hole post-processing | `modules/modelling/src/main/java/lib/kasuga/rendering/effect/builtin/blackhole/{BlackHoleEffect,BlackHoleEffects,BlackHoleShaderProvider}.java` |
| Shallow-water grid & optics | `modules/render-core/src/main/java/lib/kasuga/rendering/water/{ShallowWaterGrid,WaterOptics}.java` |
| Water post-processing & demo | `modules/modelling/src/main/java/lib/kasuga/rendering/effect/builtin/water/{WaterEffects,WaterSurface}.java`, `modules/modelling/src/contentTesting/java/test/kasuga/modelling/demo/cobalt/CobaltBlueDemo.java` |
| Scene track & spatial audio | `modules/modelling/src/main/java/lib/kasuga/audio/{SceneTrack,SpatialAudio}.java` |
| Diagnostics & parameter UI/CLI | `modules/modelling/src/main/java/lib/kasuga/rendering/effect/debug/{EffectDiagnosticsScreen,ShaderParameterScreen,ShaderParameterCommands}.java`, `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/Constants.java` |
