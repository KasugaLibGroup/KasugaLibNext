# Changelog and Version Policy

This page records the current versioning policy and verified development milestones of `KasugaLibNext` grounded in `gradle.properties`, `neoforge.mods.toml` templates, and the `master` Git history.

## Version Policy

- **Current version identifier**: `gradle.properties` sets `mod_version=1.0.0`, `kasuga_lib_version_range=[1.0.0,)`, targeting `Minecraft 1.21.1` and `NeoForge [21.1.268,)` (`minecraft_version_range=[1.21.1]`).
- **Branching and release status**:
  - Per workspace rules ([Contributor Documentation](contributing.md)), active work lands directly on `master` without long-lived feature branches.
  - Up to `HEAD` (`293ac7b5`), there are **no Git release tags** (`git tag -l` is empty) and no automated remote Maven/CurseForge/Modrinth release workflow; aside from the early `v0.1 commit` (`7ec47eb8`), integration happens via Gradle `includeBuild` or per-module local `maven-publish` repositories (`file://${project.projectDir}/repo`).
  - Accordingly, the summary below documents verified Git commits and merged PR milestones rather than fabricated version numbers.
- **Compatibility guardrails**:
  - **Pinned native Box3D upstream**: `modules/modelling/src/main/native/BOX3D_VERSION` and `BOX3D_SOURCES.json` pin the exact upstream commit and verify SHA-256 checksums before building.
  - **PBR disk cache versioning**: `PbrBakeProfile.VERSION` invalidates stale PMX-to-PBR disk caches whenever channel semantics or baking algorithms change.
  - **Runtime Iris/Sodium probing**: `modules/iris-compat` checks bytecode and reflection signatures via `IrisCameraMixinPlugin` and `CompatCapability` so incompatible versions degrade gracefully.

## Development Milestones (from Git History)

### 2025-09 — 2025-12: Repository Bootstrap, DI, and Code Generation

- `64191bfd` (2025-09-12): Initial repository commit.
- `8772165a` (2025-10-06): Split `modules/inject` and introduced `ModApplicationContext.init()` Micronaut DI bootstrap.
- `a0a56d4a` (2025-10-17): Added RPC, networking payloads, and `modules/generator` codegen.
- `7ec47eb8` (2025-12-06): Early `v0.1 commit` milestone.

### 2026-02 — 2026-04: DI Updates and Model Loader Phase 1

- `1755d239`, `dc0e4be9` (2026-03-11): Dependency injection updates.
- `cb9589cd` – `bbddd717` (2026-03-23 – 2026-03-28): Initial model-loader stages, PBR `emissiveMap` textures, shader-side `PoseStack` handling, and initial Iris/LabPBR alignment.
- `8e1273bb` – `d7b2e384` (2026-04-02 – 2026-04-25, PR #1): Model resource scanner, `ObjLoader`, `MaterialSetInstance`, `.mmd.zip` / PMD / PMX support, JE loader, and MMD render hot-path optimizations.

### 2026-08 — 2026-10: FSM, Render Core Split, Early Data-Driven, and UI Infrastructure

- `aa95bd25` (2026-08-13, PR #34): Merged layered animation state machine (FSM).
- `91b72894` (2026-09-28, PR #52): Merged initial `data-driven` registration and reload subsystem.
- `1c7f77ff` (2026-10-03): Separated the graphics-API-neutral `:modules:render-core` from the OpenGL backend `:modules:render-opengl`.
- `0b53a98b` (2026-10-19, PR #63): Restructured `doc/` into bilingual `.md` + `.en.md` pairs for the MkDocs `static-i18n` site.
- `bc12c7c6` (2026-10-22, PR #64) and `d1bea6ec` (2026-10-25, PR #68): Expanded model pipeline and world-space UI / LDLib2 / ModernUI infrastructure.
- `08456753`, `087dbe88`, `65bc7f27` (2026-10-20 – 2026-11-10): Integrated Create railway/contraption support (`modules/create`).

### 2026-11: Nebula & Shader DSL, VFS & Resource Platform, Data-Driven v2, World Lights, and Weather Occlusion

- **Block-state properties, Nebula, and rendering pipeline integration** (early-to-mid November):
  - `72f3247f` (PR #70, 2026-11-07): Added data-driven block-state properties.
  - `f5df8ccd` (2026-11-09): Integrated the Nebula demo pipeline (`597db1b8` / PR #76), multi-layer volumetric sky clouds, and `RenderShaderHandle` / Java Shader DSL rendering APIs.
- **VFS and Scoped Resource Platform** (2026-11-13):
  - `67ceefe3`, `03043138`, `cefdf7e7` (PR #86): Added transactional writable virtual file systems (`VirtualFileSystem`, `MountTableVfs`, `VfsPackResourcesBridge`).
  - `24068ae8` (PR #87): Added scoped resource platform (`ResourceSystem`, `ResourceSnapshot`) and async `ScriptTicket` handling.
- **Dummy groups, block dimensions, and depth bias** (2026-11-14 – 2026-11-16):
  - `d22a2a55` (PR #88): Added transform-only dummy model groups.
  - `99109778` (PR #89): Exposed block model dimensions (`BlockDimensions`).
  - `ff9052c3` (PR #91): Added scoped model depth bias (`DepthBias`) for coplanar overlays.
- **Data-Driven v2 strict build pipeline** (2026-11-16 – 2026-11-24):
  - `2a8a57ba`, `3146f5e7`: Completed the `STRICT` multi-source `DataDrivenBuildPipeline` while preserving legacy reload compatibility.
  - `d767c83b` (PR #94 `a094e925`): Added type-wide `ValidationContext.findAll(String)` queries.
  - `6112c2f0` (PR #96 `98d5d482`): Added `HandlerCapability.OVERLAY` overlay definition selection.
- **World-space lights, automatic instancing, generic proxy blocks, and collisions** (2026-11-17 – 2026-11-24):
  - `58759758`, `ab66967b`, `252f35c9` (PR #92): Added movable point/area/volumetric `WorldLights` and Blockbench light locators following bone poses.
  - `a71bd21c` (PR #93): Added automatic hardware instancing for compatible world models (`GlobalModelBatcher`).
  - `c27d7e0a`, `4379b89e`, `2712adc4` (PR #95 `fd49ba31`), `6f964ab1`, `378e6e5e`, `c8c22e57`, `33145724`: Added generic `proxy_model` / `proxy_model_be` blocks, compiled model groups and scoped playback, animated model collisions and 512-block Contraption LOD, optional vanilla block lighting (`ModelLightingMode.BLOCK`), Create contraption proxy rendering/interaction, per-item GUI scaling, and carriage vertical pose / bogey tracking.
- **Weather occlusion, global Lombok, Iris OIT fixes, and module cleanup** (2026-11-25 – 2026-11-26):
  - `050cf87d`, `23fab04b`, `6d027cf1`, `a2544ab2` (PR #97): Configured global Lombok `1.18.42`; added 3D indoor weather occlusion (`WeatherFilters`, `WeatherFilterVolume`) for scenes, Create contraptions, and Sable.
  - `1c98f0f0`, `a382c5c1` (PR #98): Fixed weighted OIT across Iris `1.8.12` and `1.8.14-beta.1` with graceful fallback to sorted translucency.
  - `c39864f4`, `5fe807bb`, `293ac7b5` (current `HEAD`): Isolated Iris integration into `:modules:iris-compat`; consolidated modelling/OpenGL/VFS internals; moved legacy modelling UI out into opt-in `:modules:old`.
