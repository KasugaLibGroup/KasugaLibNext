# Version Compatibility Matrix

This page summarizes the version baselines and optional integration matrices directly observable from the repository build configuration (`gradle.properties`, `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, per-module `build.gradle` files) and existing verification records.

## Core Baseline Versions

The following baseline comes from `gradle.properties`, `build.gradle`, and `gradle/wrapper/gradle-wrapper.properties` at the repository root:

| Component | Declared Baseline / Range | Source |
| --- | --- | --- |
| KasugaLib (`mod_id`: `kasuga_lib`) | `1.0.0` (consumer range `[1.0.0,)`) | `gradle.properties` |
| Maven group | `lib.kasuga` | `gradle.properties` |
| Java toolchain | Java `21` (CI uses Temurin JDK 21) | `build.gradle`, `.github/workflows/build.yml` |
| Gradle Wrapper | `8.14.3` | `gradle/wrapper/gradle-wrapper.properties` |
| ModDevGradle | `2.0.107` | `build.gradle` |
| Shadow plugin | `9.0.2` | `modules/*/build.gradle` |
| Minecraft | `1.21.1` (`minecraft_version_range=[1.21.1]`) | `gradle.properties` |
| NeoForge | `21.1.268` (`neoforge.mods.toml` templates declare `[21.1.268,)`; `loader_version_range=[1,)`) | `gradle.properties`, `modules/*/src/main/templates/` |
| Parchment mappings | `1.21.1` / `2024.11.17` | `gradle.properties` |
| Lombok | `1.18.42` (configured globally for all Java subprojects) | `gradle.properties`, `build.gradle` |
| Micronaut (`inject` / `aop`) | `4.9.10` (`inject` unit tests also use `micronaut-test-junit5:4.8.1`) | `modules/inject/build.gradle`, `modules/library/build.gradle` |

> There is no separate `gradle/libs.versions.toml` file in this repository; all dependency versions are declared directly in `gradle.properties` and module `build.gradle` scripts.

## Submodule and Bundled Dependencies

| Module | Key Dependencies and Versions | Notes |
| --- | --- | --- |
| `modules/render-core` | `joml:1.10.5`, `datafixerupper:8.0.16`, `gson:2.10.1`, `guava:32.1.2-jre`, `jgltf-model:2.0.4`, `jgltf-impl-v2:2.0.4`, `caliko-1.3.8.jar` | Plain Java library; `checkGraphicsApiIsolation` forbids Minecraft, NeoForge, and LWJGL imports/dependencies |
| `modules/render-opengl` | `lwjgl:3.3.3`, `lwjgl-opengl:3.3.3`, `lwjgl-glfw:3.3.3`, `slf4j-api:2.0.9` | Does not apply ModDev; the host supplies the active OpenGL context and thread |
| `modules/modelling` | Pinned vendored Box3D C17 (commit `336263320469e5e9febfe18697ab562488f7e97a`), `modules/iris-compat`, JMH `1.37` (benchmark source set only) | Requires CMake on `PATH` to build the native `kasuga_box3d` JNI library; dev runs load local Iris/Sodium jars when enabled |
| `modules/core` | `jgrapht-core:1.5.2`, `zxing:core:3.5.3`, Micronaut `4.9.10` | `shadowJar` (`-all`) embeds `jgrapht-core`, `zxing`, `utils`, and relocated Micronaut / `inject` |
| `modules/library` | `zxing:core:3.5.3`, Micronaut `4.9.10`, CC:Tweaked `1.116.1`, Create ecosystem deps | `shadowJar` (`-all`) aggregates submodules plus `jgltf`, `caliko`, `zxing`, `formula`, and `shader` (note `jgrapht-core` is in `modules/core`'s `shadowLibrary`, not `modules/library`'s) |
| `modules/generator` | `javaparser-core:3.27.1`, `javaparser-symbol-solver-core:3.27.1` | Build-time code generation only |
| `modules/resource-compiler` | `night-config:toml:3.6.7`, `gson:2.10.1` | Build-time `compileResources` only |
| `modules/shader-graal` | `graalvm.polyglot` / `js-language` / `truffle-runtime` `24.1.2` | Optional module; verifiable on plain JVM without launching Minecraft |

## Optional Mods and Adapter Compatibility

The declared build pins and observed verification combinations come from `gradle.properties`, adapter `build.gradle` files, and verification docs:

| Integration Target | Build Declaration / Pinned Artifact | Observed Verified Combination | Module and Reference |
| --- | --- | --- | --- |
| Create / Ponder / Flywheel / Registrate | Default: `Create 6.0.11-346`, `Ponder 1.0.92+mc1.21.1`, `Flywheel 1.0.7`, `Registrate MC1.21-1.3.0+67` | Previously verified against `Create 6.0.10-280`, `Flywheel 1.0.6`, `Registrate MC1.21-1.3.0+67` on NeoForge `21.1.228` | `modules/create`, [Moving Scene Hosts](../reference/moving-scene-hosts.md), [Sable Scene Hosts](../reference/sable-scene-hosts.md) |
| CC:Tweaked | `1.116.1` (`cc-tweaked-1.21.1-forge:1.116.1`) | Resolved in `modules/create` and `modules/library` builds | `gradle.properties`, `modules/create/build.gradle` |
| Iris / Sodium | Local compile jars: Iris `1.8.14-beta.1`, Sodium `0.8.14-beta.2` for Minecraft 1.21.1 | Historical records include Iris `1.8.12` + Sodium `0.6.13` BSL 8.2.04 smoke, multi-camera, and OIT checks; weighted OIT was also tested on Iris `1.8.14-beta.1` with sorted translucency fallback | `modules/iris-compat`, [Model Material Alpha Rendering](../reference/render-alpha-passes.md), [Wardrobe Benchmarks](wardrobe-benchmarks.md) |
| Sable / Aeronautics | `compileOnly` `sable-companion-common-1.21.1:1.6.0` | Verified on macOS ARM64 with official Sable `2.0.6`, Aeronautics `1.3.2`, Companion `1.6.0`, NeoForge `21.1.228`, Create `6.0.10-280`, Flywheel `1.0.6` | `modules/create`, [Sable Scene Hosts](../reference/sable-scene-hosts.md) |
| Voxy | Optional client integration | NeoForge `21.1.228`, Sodium `0.6.13`, Voxy NeoForge `0.6.0`, Forgified Fabric API `0.116.7+2.2.0` (requires NeoForge >= `21.1.217`) | [Model Collision and LOD](../guides/world-model-collision-and-lod.md) |
| LDLib2 | `external/uip/ldlib/gradle.properties` sets `ldlib2_version=2.2.44` and `neo_version=21.1.268` (`neoforge.mods.toml` template `[2.2.44,)`) | Tested via `runClientDemo` / `runClientWorldDemo` / `runClientModelDemo` against `ldlib2-neoforge-1.21.1:2.2.44:all` | `external/uip/ldlib`, [UI Model Previews](../reference/ui-model-preview.md) |
| LDLib2 Compose | `ldlib2-compose-1.21.1:21.0.0.1:all`, Kotlin `2.1.20`, Compose `1.8.2` | Explicitly pins `neoForge.version = '21.1.268'` (`neoforge.mods.toml` requires `kasuga_lib_ldlib2 [1.0.0,)` and `ldlib2_compose [21.0.0.1,)`) | `external/uip/ldlib-compose`, [LDLib2 Compose Integration](../reference/ldlib-compose.md) |
| ModernUI | `modern-ui:eMf1VSQd` (`neoforge.mods.toml` requires `modernui [3.13.0.1,)`) | NeoForge `21.1.268`, client-side preview adapter only | `external/uip/modernui` |
| Javet (`kasuga_lib_javet`) | `com.caoccao.javet:javet:5.0.1`, `httpcomponents:httpclient:4.5.13` | `JavetDownloader` fetches V8 native libraries at runtime for `windows-x86_64`, `linux-x86_64/arm64`, `macos-x86_64/arm64` (Android unsupported) | `external/slp/javet` |

## Platforms and Graphics Environments

- **CI matrix** (`.github/workflows/build.yml`, `.github/workflows/render-harness.yml`):
  - `ubuntu-latest` (Linux x86-64): full `./gradlew build renderingReleaseCheck`, Box3D native build, and `xvfb-run` + Mesa software OpenGL (`LIBGL_ALWAYS_SOFTWARE=1`) `renderGlTest`.
  - `windows-latest` (Windows x86-64): Box3D native build, `renderHarnessClasses` / `jmhClasses` compilation, and `modelUnitTest`.
  - `macos-latest` (macOS ARM64): Box3D native build, `renderHarnessClasses` / `jmhClasses` compilation, and `modelUnitTest`.
- **Recorded hardware and headless verification** ([Offscreen Output and Multi-Camera Rendering](../guides/offline-rendering.md), [Sky Volumetric Clouds](../reference/volumetric-clouds.md)):
  - macOS ARM64 (Apple M3 Max, OpenGL 4.1 Metal): verified main view, Iris/Sodium multi-camera, multi-window, `hidden` headless mode, and volumetric cloud benchmarks.
  - Linux ARM64 (Debian 12 aarch64, Mesa `22.3.6` `llvmpipe` / OpenGL 4.5): verified GLFW Null + EGL pbuffer headless client without `DISPLAY` or `WAYLAND_DISPLAY`.
  - `hidden` headless mode (default on macOS/Windows) still requires a desktop display session; dedicated-server-only mod compatibility, NVIDIA/AMD hardware EGL, and arbitrary third-party shader packs are outside the recorded verification scope.

> See [Known Limitations](limitations.md) for platform and subsystem caveats, and [Build and Verification Entries](../guides/verification.md) before running test tasks.
