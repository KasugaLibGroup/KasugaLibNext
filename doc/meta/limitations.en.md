# Known Limitations

This page summarizes verified limitations, platform-specific constraints, and compatibility boundaries observed in the source code, build scripts, and verification docs.

## Graphics Backends and Legacy Modules

- **Only OpenGL and the Minecraft Blaze3D host adapter are implemented**: `modules/render-core` defines graphics-API-neutral contracts, while concrete rendering implementations currently exist only in `modules/render-opengl` and `modules/modelling`. Vulkan and Metal backends are not implemented; the leftover `modules/render-metal` directory on disk is neither included in `settings.gradle` nor tracked in Git.
- **Legacy modelling UI prototype does not compile**: `modules/old` preserves historical prototype sources behind `-PkasugaIncludeOld=true` using Gradle's `base` plugin for source browsing only; it references retired registration APIs and is excluded from default builds.

## macOS and Platform Caveats

- **macOS TBO upload path**: Because Apple OpenGL drivers incur heavy validation penalties after mapped/`subdata` updates on texture buffer objects, `modules/render-opengl` uses `MacTboUploadDevice` (`macos-tbo-orphan`, re-allocating storage via `glBufferData` on every upload) while keeping fenced mapping for VBOs.
- **macOS depth precision and main-thread flag**: `ksglib_main.fsh` documents 1-ULP fixed-function depth rounding and water-layer ordering sensitivity on macOS; standalone LWJGL tasks (`renderGlTest`, `renderPreview`, `renderBench`) require `-XstartOnFirstThread` on macOS.
- **Desktop display requirement for `hidden` mode**: `runClientHeadless` defaults to `hidden` (hidden GLFW window) on macOS and Windows and still requires a desktop display session; only Linux supports display-free `egl` (`EGL_PLATFORM=surfaceless`) via the patched GLFW 3.4 Null platform.

## Iris, Sodium, and Shader-Pack Compatibility

- **Bytecode ABI gating and graceful fallback**: `IrisCameraMixinPlugin` and `CompatCapability` in `modules/iris-compat` inspect Iris/Sodium signatures at startup. If an installed Iris/Sodium build fails ABI checks, Kasuga logs a warning and shows a fallback toast (`kasuga_lib.iris.models.unavailable.message` / `kasuga_lib.oit.fallback.iris`), degrading to non-shader rendering or sorted translucency rather than crashing.
- **Weighted-Blended OIT (WBOIT) scope**:
  - Runs at `AFTER_TRANSLUCENT_BLOCKS` and composites KasugaLib `BLEND` layers over the already rendered scene; it does **not** merge vanilla water or shader-pack translucent fragments into the same OIT equation.
  - With an active Iris shader pack, Kasuga cannot inject its depth weight into unmodified shader-pack fragment shaders, so it uses unit-weight WBOIT (subject to Iris's `entities_translucent` ~`0.1` alpha test), resolves only the main color buffer, and falls back to far-to-near sorted translucency when float targets or Iris world framebuffers are unavailable. Shader packs relying on extra custom gbuffer channels for translucent surfaces may require pack-specific integration.
- **Sequential multi-camera rendering**: Multi-camera world views render sequentially on a single OpenGL context (concurrent multi-threaded camera drawing is unsupported). Extra camera views omit HUD/GUI, first-person hand, and glowing outlines.
- **Weather filters and shader packs**: `WeatherFilters` automatically falls back from GPU top-down depth filtering to CPU column-height filtering when an Iris shader pack is active ([Managed Effects and Post-Processing](../reference/effects-and-postfx.md)). [Sky Volumetric Clouds](../reference/volumetric-clouds.md) and [World-Space Lights](../guides/world-lights.md) are not validated across arbitrary third-party shader-pack custom gbuffers.

## World-Space Lights, Volumetric Clouds, and Particle Fluids

- **Screen-depth `WorldLights` boundaries** ([World-Space Lights](../guides/world-lights.md)):
  - Runs at `POST_PROCESS` priority `700`/`701`, reconstructing normals from adjacent scene depth and approximating albedo from scene color. **No shadow maps, offscreen occlusion queries, indirect bounce, or separate lighting on transparent geometry without depth/normals**; walls do not cast dynamic shadows behind them.
  - Does not alter Minecraft block light levels or mob spawning.
  - Volumetric scattering integrates 48 steps per spherical volume and sums overlapping optical depth/scattering without full inter-volume multiple scattering.
- **Volumetric clouds** ([Sky Volumetric Clouds](../reference/volumetric-clouds.md)): No long-range inter-cloud shadowing, ground cloud shadows, or full weather simulation; dimensions without vanilla cloud layers render no sky clouds. `HIGH` quality is substantially heavier inside cloud layers than the default `BALANCED` preset.
- **Particle fluid grids** ([Managed Effects and Post-Processing](../reference/effects-and-postfx.md)): `StableFluidGrid3D` (`GasSmokePreset` / `LiquidFlowPreset`) is a real-time visual approximation without free surfaces, surface tension, droplet merging, conservative FLIP/PIC, or two-way forces back onto entities, pistons, or vanilla fluid blocks.

## Physics, Ragdolls, Model Formats, and Collisions

- **Box3D native build and feature boundaries** ([PMX and glTF Ragdoll Configuration](../reference/mmd-ragdoll.md)):
  - When `cmake` is absent from `PATH`, Gradle skips building `kasuga_box3d` and excludes `box3d`-tagged tests.
  - Box3D provides no fluid-volume/buoyancy API; legacy JSON fields (`simulation.solver_iterations`, `max_angular_speed`, `limit_stiffness`, `sleeping.delay_seconds`) are accepted for compatibility only and do not alter Box3D solving. PMX 6-DOF joints tighten 3-axis translation to fixed anchors.
  - PMX 2.1 per-vertex soft-body data is parsed only and not wired into Box3D solving or vertex writeback.
  - glTF ragdolls require an adjacent `.gltf.json` manifest with an explicit `ragdoll` profile; humanoid body topologies are not auto-guessed without a profile.
- **Outfits and animated collisions**:
  - Wardrobe assembly ([Wardrobe Benchmarks](wardrobe-benchmarks.md)) does not perform automatic body fitting or cross-skeleton retargeting; arbitrary mismatched outfits are not guaranteed to be clip-free.
  - Server-side datapack collision loading (`ServerCollisionModels`) automatically supports `.bbmodel` and standalone `.gltf`/`.glb`; other model formats require programmatic wrapping via `ServerCollisionModels.Asset` ([Model Collision and LOD](../guides/world-model-collision-and-lod.md)).

## Offscreen Output, Headless Client, and World UI

- **No built-in fixed-timestep offline video encoder**: `MinecraftFrameOutputs` delivers in-process OpenGL `OutputFrame` / FBO textures on the render thread while game ticks keep running; consumers handle readback, encoding, or IPC ([Offscreen Output and Multi-Camera Rendering](../guides/offline-rendering.md), [MC Final Frame Offscreen Output](../reference/frame-output.md)).
- **Linux EGL and multi-window restriction**: Linux `egl` mode requires the patched GLFW 3.4 Null-platform library (`scripts/build-headless-glfw.sh`) and a desktop-GL-capable EGL driver; stock GLFW is not equivalent. Multi-window (`MinecraftFrameWindows`) is unsupported in Linux Null/EGL mode and fails explicitly.
- **World-space UI raycasting and IME** ([World UI and Free-Cursor Input](../reference/world-ui-input.md)): Plane intersection neither checks block occlusion nor runs texture alpha tests; default max hit distance is 5 blocks. Automated headless smokes do not cover OS IME candidate windows.
- **Blueprint display bounds** ([Blueprint and Nestable World Display](../guides/world-display.md)): `BlueprintImporters` caps inputs at 64 MiB NBT, 131,072 blocks, and 4096 blocks per axis; built-in decoders cover vanilla/Create structures, Litematic v5–v7, and Sponge `.schem` v2–v3 (not legacy numeric-ID `.schematic` files).

## Scripting and Java Shader DSL

- **Javet native download requirement** (`JavetDownloader.java`): `kasuga_lib_javet` throws on Android; if the host platform V8 library is not pre-staged in `<gameDir>/native-libraries/javet/`, first launch requires network access to Maven mirrors or manual placement of the native file.
- **Java Shader DSL typed IR gaps** ([Java Shader DSL Reference](../reference/shader-dsl.md)): The typed API omits user-defined functions, struct uniforms, struct stage inputs/outputs, non-float uniform arrays, arbitrary swizzles, `discard`, derivatives, and advanced texture sampling; those currently require `@DelicateShaderApi` raw GLSL escape hatches or resource shaders.

## Data-Driven v2 and Packaging Boundaries

- **v2 build pipeline boundaries** ([Troubleshooting and Migration](../explanation/data-driven/08-troubleshooting-migration.md)):
  - `DataDrivenBuildPipeline` reload preflight does not scan `globDirectories()` (such as unlisted `state_machines/` files) and does not construct `ResourceLocation`, so uppercase or illegal path characters in `on_reload` can still fail at runtime `/reload`.
  - `build()` has no transactional rollback on `FAILED`; `DataDrivenRegistrationProviders` allows one provider per host; `FactoryRegistry` and `DataDrivenBuildHooks` have no unregister API.
  - World-save compatibility, pack-history guards, and physical pack discovery belong to the host mod rather than KasugaLib.
- **Create / Sable and JPMS conflicts**:
  - `SableSceneHosts` targets Sable `2.0.6` / Aeronautics `1.3.2` (`Companion 1.6.0`), does not cover Valkyrien Skies or dedicated-server smoke testing, and `ContraptionLod` syncs within 512 blocks without force-loading chunks.
  - Downstream NeoForge mods pulling `kasuga-library` (`-all`) alongside `kasuga-inject` must exclude standalone `lib.kasuga:utils` from runtime classpaths to avoid JPMS `ResolutionException` at startup.
