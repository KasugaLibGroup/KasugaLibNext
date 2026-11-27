# Migration Guide

This page summarizes breaking changes and migration paths across `KasugaLibNext` data-driven registration, module packaging, rendering backends, and physics/model subsystems.

## 1. Data-Driven: Migrating from v1 (`JsonTreeBuilder`) to v2 (`DataDrivenBuildPipeline`)

The legacy flat directory layout and single-source registration flow have been superseded by the v2 multi-source strict pipeline (see [Troubleshooting and Migration](../explanation/data-driven/08-troubleshooting-migration.md) and [Data-Driven Registration](../reference/data-driven/README.md) for detailed checklists):

| Area | Legacy Pattern (Deprecated / Restricted) | Current v2 Pattern |
| --- | --- | --- |
| Index manifest path | `data/<modid>/kasugalib/*.json` (legacy directory is no longer read) | `data/<namespace>/kasuga_lib/data_driven/<name>.json` |
| Manifest top-level keys | `"sources": [...]` | `"on_register": [...]` (registration phase) and `"on_reload": [...]` (reload phase), relative to `data/<namespace>/`, disjoint |
| Reload discovery | Single manifest or implicit scanning | `state_machines/` retains directory glob discovery; `animation_clips/` must be listed explicitly in `"on_reload"` |
| Registration entry | Default single-source `JsonTreeBuilder` scan | Install a host provider via `DataDrivenRegistrationProviders.install(modId, ...)` backed by `DataDrivenBuildPipeline` (`BuildOptions.STRICT` recommended) |
| Definition identities | Bare `id` or `minecraft:id` remapped to host mod ID | `STRICT` mode requires explicit `"namespace:path"` (bare IDs trigger `INVALID_IDENTITY`; `minecraft:` targets the literal vanilla namespace) |
| Handler & factory setup | Relied on implicit static loading | Explicitly call `JsonTreeBuilder.ensureHandlersRegistered()` and `FsmBlockEntityFactories.registerBuiltin()` before first prepare; custom `TypeHandler`s declare `STRICT_PREFLIGHT` and exactly one role (`REGISTRY_CLAIMS` or `NON_REGISTRATION`) |
| Claim conflicts | Relied on late registry exceptions | Collect code registrations beforehand via `RegistryClaimSnapshot.collect(hostRoot)` and gate publishing on `BuildResult` / `BuildDiagnostic` |

> Single-JAR mods with no multi-pack overrides or strict preflight requirements can still use `JsonTreeBuilder`, but must still adopt the new `kasuga_lib/data_driven/` layout and `on_register`/`on_reload` keys.

## 2. Module Layout, Packaging, and Build Migrations

Recent cleanups on `master` introduced the following structural and classloading rules:

- **Unique per-subproject JAR names** (root `build.gradle`):
  - Every subproject uses `base { archivesName = "kasuga-${project.name}" }`; do not override subproject archive names to `kasuga_lib`, which causes ModDev JPMS module layers to merge unrelated jars and trigger `NoClassDefFoundError`.
- **Single package owner for `lib.kasuga.utils`**:
  - `modules/library`'s `shadowJar` (`-all`) bundles `modules/utils` and relocates `io.micronaut`, `jakarta.*`, and `lib.kasuga.inject` under `lib.kasuga.vendors.*`.
  - Downstream NeoForge mods depending on both `lib.kasuga:kasuga_lib` (`Bundling.SHADOWED`) and `lib.kasuga:inject` must exclude standalone `lib.kasuga:utils` from runtime classpaths to avoid JPMS `ResolutionException` at startup (see the `KuaYue` configuration in [Consumers](users.md)).
- **Iris compatibility isolated to `:modules:iris-compat` (`c39864f4`)**:
  - Iris/Sodium mixins (`WorldViewIris*`), `IrisVertexBuffer`, and `CameraIrisSession` moved out of `modules/modelling` into `modules/iris-compat` (`kasuga_lib_iris.mixins.json`, `IrisCameraMixinPlugin`, `IrisRenderingIntegration`), while legacy `IrisConstants` in `modules/modelling` was deleted and replaced by `IrisModelFormats`, `IrisRenderingIntegration`, and `IrisWorldFramebuffer`.
  - `modelling` retains only `IrisIntegration` and `CompatCapability` probing; distribution artifacts include `iris-compat` classes while excluding `libs:iris` and `libs:sodium`.
- **Legacy modelling UI moved to opt-in `:modules:old` (`293ac7b5`)**:
  - `modules/modelling/old` moved to `modules/old` and is omitted by default; pass `-PkasugaIncludeOld=true` only to inspect historical prototype sources.
- **Global Lombok (`050cf87d`)**:
  - Root `build.gradle` applies `org.projectlombok:lombok:1.18.42` globally to every Java subproject's source sets.
- **Render core / OpenGL backend split** ([Rendering Backend Integration Reference](../reference/rendering-backends.md)):
  - Graphics-API-neutral UML model, scheduling, `UploadRing`, and `FrameOutputRouter` live in `modules/render-core`; OpenGL classes (`GpuUploadRing`, `MacTboUploadDevice`, `FrameTexture`, `FramePreviewWindow`) live in `modules/render-opengl`. New graphics backends depend only on `render-core`.

## 3. Model, Physics, and Coordinate Migrations

- **Unified dynamics & Box3D physics** ([PMX and glTF Ragdoll Configuration](../reference/mmd-ragdoll.md)):
  - Physics, IK, and diamond closures live under `Skeleton.getDynamics()`.
  - Vendored Box3D C17 (`kasuga_box3d`) replaced the legacy fluid-force experiments: legacy `simulation.solver_iterations`, `max_angular_speed`, `limit_stiffness`, and `sleeping.delay_seconds` remain readable in JSON for compatibility only without altering Box3D solving; prefer swing/twist limits (`max_swing_degrees`, `min_twist_degrees`, `max_twist_degrees`) over legacy `rotation_min_degrees` / `rotation_max_degrees`.
- **Floating origin for large coordinates**:
  - Avoid storing far-away world translations inside float `Transform` / `Matrix4f` values. Use `MinecraftRagdollDeployments.Request` with double `Vector3d worldOrigin`, `*World(...)` ragdoll interaction APIs, and `SceneModels.Entry.position(Vec3)` ([Blueprint and Nestable World Display](../guides/world-display.md)).
- **Proxy blocks and Blockbench loader boundaries** ([Modelling Module](../reference/modelling/README.md)):
  - Prefer generic `proxy_model` / `proxy_model_be` data-driven factories with `ProxyComposition` / `ScopedPlaybackCommandApplier` over custom block/BE boilerplate.
  - Keep `.bbmodel` pixel-UV normalization and intrinsic Z→Y→X Euler ordering isolated inside `KsgBbModelLoader`; never inject pixel UV normalization or Bedrock sign flips into shared `FlatModelData`.

## 4. Scoped Resource Platform and VFS

- For assets shared across registration, resource packs, and scripts, migrate ad-hoc file reads to `ResourceSystem`, `ResourceSources`, `ResourceSnapshot`, and `VirtualFileSystem` (`VfsPath`, `MountTableVfs`, `VfsPackResourcesBridge`) so registration and runtime pack layers share frozen snapshots ([Unified Resource Platform](../reference/resource-platform.md), [VFS](../reference/vfs.md)).
- When binding lifecycle events, use `ResourceEvent.HOST_BOOTSTRAP` (`host_bootstrap`); earlier drafts occasionally referred to `bootstrap`, which is not a valid enum value.
