# Dependencies and Runtime Environment

This page documents the runtime environment, build toolchain, artifact coordinates, module layout, and optional runtime dependencies of KasugaLibNext, grounded in the repository's build scripts and source metadata.

## Runtime Environment and Toolchain Versions

The table below reflects the root `gradle.properties`, `build.gradle`, `gradle/wrapper/gradle-wrapper.properties`, and `modules/library/src/main/templates/META-INF/neoforge.mods.toml`:

| Item | Requirement / Pinned Version | Source |
| --- | --- | --- |
| Java | Java 21 (`JavaLanguageVersion.of(21)`) | `build.gradle` |
| Gradle | `8.14.3` (Wrapper `gradle-8.14.3-bin.zip`) | `gradle/wrapper/gradle-wrapper.properties` |
| Minecraft | `1.21.1` (range `[1.21.1]`) | `gradle.properties` |
| Mod Loader | NeoForge `21.1.268` (`javafml`, FML range `[1,)`) | `gradle.properties`, `neoforge.mods.toml` |
| ModDevGradle | `net.neoforged.moddev` `2.0.107` | `build.gradle` |
| Parchment Mappings | MC `1.21.1` / `2024.11.17` | `gradle.properties` |
| Shadow Plugin | `com.gradleup.shadow` `9.0.2` | `modules/library/build.gradle` |
| Lombok | `1.18.42` (injected on every Java subproject) | `build.gradle`, `gradle.properties` |

> **Fabric is not supported**: Every mod subproject targets `net.neoforged.moddev` and `javafml`.
>
> **About version catalogs**: This repository does not use `gradle/libs.versions.toml`; shared version properties live in the root `gradle.properties`, with the external subprojects also declaring NeoForge `21.1.268` in their own build files when needed.

## Mod Identity and Distribution Artifact

The aggregate `:modules:library` mod registers the following NeoForge metadata at runtime:

| Field | Value | Notes |
| --- | --- | --- |
| `modId` | `kasuga_lib` | Runtime mod ID (`KasugaLib.MODID`) |
| `displayName` | `KasugaLib Next` | Display name |
| `group` | `lib.kasuga` | Gradle `mod_group_id` |
| `version` | `1.0.0` | Current `mod_version`; downstream range `kasuga_lib_version_range = [1.0.0,)` |
| Archive name | `kasuga-library-1.0.0-all.jar` | Root `build.gradle` sets `archivesName = "kasuga-${project.name}"`; `shadowJar` adds classifier `all` |

> **Never deploy unshaded thin JARs or split sub-module JARs**: As documented in the root `build.gradle`, ModDev/FML derives module-layer names from JAR filenames; splitting packages or loading individual sub-module JARs alongside `kasuga_lib` breaks JPMS/FML classloading.

### Repository Publishing Status

In the current repository (`modules/library/build.gradle` and sibling `publishing` blocks), only a local file-system Maven repository (`file://${project.projectDir}/repo`) is declared; **no public remote Maven repository URL is configured for `lib.kasuga:*`**. Within the workspace, downstream mods (such as `KuaYue`) consume KasugaLibNext via Gradle **composite builds (`includeBuild`) with dependency substitution**.

## Module Structure and Coordinates

`settings.gradle` declares the following subprojects and included builds:

### 1. Aggregate Mod and Bundled Runtime Modules

`:modules:library` aggregates the following modules into the single `kasuga_lib` mod layer (`kasuga-library-<version>-all.jar`) via `builder.kasuga.monorepo`'s `useModProject(...)`:

| Subproject | Responsibility | Notes |
| --- | --- | --- |
| `:modules:library` | Aggregate distribution & Shadow packaging | Produces `kasuga-library-*-all.jar` |
| `:modules:core` | Registration DSL (`Reg` / `Registry`), lifecycle, resource platform, VFS, RPC, saved data | Uses `@RegGenerator` codegen and `kasuga_lib.mixins.json` |
| `:modules:data-driven` | JSON data-driven registration and `/reload` pipeline | See [Data-driven registration](../reference/data-driven/README.md) |
| `:modules:modelling` | Client model loaders, animation FSM, skeleton physics/IK, render scheduling, effects, multi-camera | Includes Box3D JNI bridge and `KasugaLibMixinPlugin` |
| `:modules:iris-compat` | Optional Iris/Sodium compatibility adapter | Gated by ASM ABI checks in `IrisCameraMixinPlugin` (`kasuga_lib_iris.mixins.json`) |
| `:modules:create` | Create railway/contraption/bogey & Sable integration | Ships `kasuga_lib_create.mixins.json` |
| `:modules:scripting` | Script package discovery, sandbox, and lifecycle host (`PackageSystem`) | Engine-agnostic; JS engine lives in companion mod |
| `:modules:inject` | Micronaut `4.9.10` DI bootstrap, `@Configurable`, `@BeanOnlyIn` | Relocated to `lib.kasuga.vendors.inject` inside `-all.jar` |
| `:modules:utils` | Gson bindings, glob/path matchers, shared annotations | Shaded into `-all.jar` |
| `:modules:formula` | Pure-JVM formula expression and namespace evaluator | See [Formula Module](../reference/formula/README.md) |
| `:modules:shader` | Typed Shader DSL, IR, and GLSL code generator | See [Java Shader DSL Reference](../reference/shader-dsl.md) |
| `:modules:render-core` | Graphics-API-isolated rendering core, Caliko IK, glTF/PMX definitions | `checkGraphicsApiIsolation` forbids MC/LWJGL imports |
| `:modules:render-opengl` | Shared LWJGL `3.3.3` OpenGL backend | — |
| `:modules:annotations` | Annotation library placeholder (`FMLModType=GAMELIBRARY`) | Currently contains no Java sources |
| `:modules:multiversion` | Multi-version / smoke-test harness shell | Contains no `main/java` sources |

> **Shadow relocation note**: `modules/library/build.gradle` relocates `io.micronaut`, `jakarta.inject`, `jakarta.annotation`, and `lib.kasuga.inject` under `lib.kasuga.vendors.*`, and embeds `de.javagl:jgltf-model:2.0.4`, `de.javagl:jgltf-impl-v2:2.0.4`, `caliko-1.3.8.jar`, and `com.google.zxing:core:3.5.3`. Downstream mods that define their own Micronaut `@Context` beans via `ModApplicationContext` must explicitly add `:modules:inject` and Micronaut `4.9.10` dependencies on their compile/processor/runtime classpaths; mods using only `KasugaLibRegistry` and `Reg` APIs do not need Micronaut in their own build.

### 2. Standalone Tooling, Gradle Plugins, and Optional Companion Modules

| Project Path | Artifact / Plugin IDs | Purpose |
| --- | --- | --- |
| `./modules/gradle-plugin` | `builder.kasuga.monorepo`<br>`builder.kasuga.generator`<br>`builder.kasuga.resource-compiler`<br>`builder.kasuga.shader` | `useModProject`, parallel JSON deep-merge, `@RegGenerator` codegen, `compileResources`, `generateKasugaShaders` |
| `:modules:generator` | `lib.kasuga.generator:generator` | `@RegGenerator` / `@CodeTemplate` processor (JavaParser `3.27.1`) |
| `:modules:resource-compiler` | `lib.kasuga.resource:resource-compiler` | `ResourceCompilerMain` & `JsonMergeTransformer` |
| `:modules:shader-graal` | `lib.kasuga:shader-graal` | Optional GraalVM Polyglot/JS `24.1.2` Shader DSL frontend (runs on plain JVM) |
| `:external:slp:javet` | `lib.kasuga.slp.javet` (`modId`: `kasuga_lib_javet`) | Optional Javet `5.0.1` V8 JavaScript engine companion mod |
| `:external:uip:ldlib` | `lib.kasuga.uip.ldlib` (`modId`: `kasuga_lib_ldlib2`) | Optional LDLib2 `2.2.44` world-space UI provider |
| `:external:uip:ldlib-compose` | `lib.kasuga.uip.compose` (`modId`: `kasuga_lib_ldlib_compose`) | Optional LDLib2 Compose `21.0.0.1:all` (Kotlin `2.1.20`, NeoForge `21.1.268`) |
| `:external:uip:modernui` | `lib.kasuga.uip.modernui` (`modId`: `kasuga_lib_modernui`) | Optional ModernUI `eMf1VSQd` model-preview demo integration |

> `:modules:old` is an archived UI prototype directory and is not wired as a runtime dependency.

## Downstream Gradle Setup

### Composite Build Setup (Recommended)

In your mod's `settings.gradle`, include the `KasugaLibNext` workspace and its Gradle plugin build (matching `KuaYue/settings.gradle`):

```groovy
pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

includeBuild("../KasugaLibNext") {
    dependencySubstitution {
        substitute module("lib.kasuga:kasuga_lib") using project(':modules:library')
        substitute module("lib.kasuga:inject") using project(':modules:inject')
        substitute module("lib.kasuga:generator") using project(':modules:generator')
        substitute module("lib.kasuga:resource-compiler") using project(':modules:resource-compiler')
        substitute module("lib.kasuga:formula") using project(':modules:formula')
    }
}

includeBuild("../KasugaLibNext/modules/gradle-plugin")
```

In your mod's `build.gradle`, request `Bundling.SHADOWED` so Gradle resolves `:modules:library`'s `shadowJar` (`-all`) artifact rather than the unshaded JAR:

```groovy
dependencies {
    implementation("lib.kasuga:kasuga_lib:1.0.0") {
        attributes {
            attribute(Bundling.BUNDLING_ATTRIBUTE, objects.named(Bundling, Bundling.SHADOWED))
        }
        transitive = false
    }
}
```

If your mod also creates its own `ModApplicationContext` with `@Context`, `@Configurable`, or `@BeanOnlyIn` beans, add `lib.kasuga:inject` and Micronaut `4.9.10`:

```groovy
dependencies {
    implementation("lib.kasuga:inject:1.0.0") {
        transitive = false
    }
    implementation("io.micronaut:micronaut-inject:4.9.10") {
        exclude group: 'org.slf4j', module: 'slf4j-api'
    }
    additionalRuntimeClasspath("io.micronaut:micronaut-inject:4.9.10") {
        exclude group: 'org.slf4j', module: 'slf4j-api'
    }
    additionalRuntimeClasspath("io.micronaut:micronaut-aop:4.9.10") {
        exclude group: 'org.slf4j', module: 'slf4j-api'
    }
    annotationProcessor 'io.micronaut:micronaut-inject-java:4.9.10'
}
```

## Third-Party Maven Repositories and Versions

The full `KasugaLibNext` build (`:modules:library`, `:modules:create`, `:external:*`, etc.) resolves dependencies from these repositories:

| Repository URL | Dependencies and Pinned Versions | Declared In |
| --- | --- | --- |
| `mavenCentral()` / `gradlePluginPortal()` | Micronaut `4.9.10`, LWJGL `3.3.3`, JOML `1.10.5`, DataFixerUpper `8.0.16`, Gson `2.10.1`, Guava `32.1.2-jre`, jgltf `2.0.4`, ZXing `3.5.3`, Javet `5.0.1`, GraalVM `24.1.2` | Module `build.gradle` files |
| `https://maven.neoforged.net/releases` | NeoForge `21.1.268`, `testframework`, `render-core` / `render-opengl` | `modules/render-core/build.gradle`, ModDevGradle |
| `https://libraries.minecraft.net` | Shared Minecraft libraries | `modules/render-core/build.gradle` |
| `https://maven.createmod.net` | Create `6.0.11-346`, Ponder `1.0.92+mc1.21.1`, Flywheel `1.0.7` | `modules/library/build.gradle`, `modules/create/build.gradle` |
| `https://maven.ithundxr.dev/snapshots` | Registrate `MC1.21-1.3.0+67` | `modules/library/build.gradle`, `modules/create/build.gradle` |
| `https://maven.squiddev.cc` (group `cc.tweaked`) | CC:Tweaked `cc-tweaked-1.21.1-forge:1.116.1` | `modules/library/build.gradle`, `modules/create/build.gradle` |
| `https://maven.ryanhcode.dev/releases` (group `dev.ryanhcode.sable-companion`) | Sable Companion `sable-companion-common-1.21.1:1.6.0` (`compileOnly`) | `modules/create/build.gradle` |
| `https://maven.firstdark.dev/snapshots` | LDLib2 `2.2.44`, `ldlib2-compose-1.21.1:21.0.0.1:all` | `external/uip/ldlib*/build.gradle` |
| `https://api.modrinth.com/maven` | ModernUI `maven.modrinth:modern-ui:eMf1VSQd` | `external/uip/modernui/build.gradle` |
| Local `flatDir` (`modules/modelling/libs`, `modules/iris-compat/libs`) | Dev-only compile/`localRuntime`: Sodium `0.8.14-beta.2`, Iris `1.8.14-beta.1` for Minecraft 1.21.1 (never bundled in release JARs) | `modules/modelling/build.gradle`, `modules/iris-compat/build.gradle` |

## Required vs. Optional Runtime Dependencies

| Component / Mod | Requirement | Behavior |
| --- | --- | --- |
| Minecraft `1.21.1` + NeoForge `[21.1.268,)` | Required | Enforced in `neoforge.mods.toml` |
| Create `6.0.11-346` + Ponder + Flywheel + Registrate | Required by aggregate `kasuga-library-*-all.jar` | Although DI beans are gated via `@CreateModBeans` (`IfCreateModLoaded`), `:modules:library`'s `neoforge.mods.toml` currently declares `kasuga_lib_create.mixins.json` (`required: true`), and `CreateMixinPlugin` inspects `BezierConnection.class`—throwing `IllegalStateException` on client startup if Create is absent |
| `kasuga_lib_javet` (`external/slp/javet`) | Optional | Supplies the Javet `5.0.1` V8 JavaScript engine; only needed when scripts/packs declare `javascript` packages. On first run `JavetDownloader` fetches V8 natives to `<gameDir>/native-libraries/javet/` |
| Iris `1.8.14-beta.1` + Sodium `0.8.14-beta.2` | Optional (client-only) | `kasuga_lib_iris.mixins.json` sets `required: false`; `IrisCameraMixinPlugin` and `IrisCompat` probe Iris/Sodium ABIs at startup and fall back gracefully when absent or incompatible |
| Voxy / Sable | Optional | `VoxyCompat` and the `Create` Sable adapter detect presence via `ModList` / class checks |
| Box3D native library (`kasuga_box3d`) | Optional native library | Packaged under `native/<os>-<arch>/`. When CMake was absent at build time or the current platform lacks a bundled binary, `NativeBox3D.availableOrWarn()` disables Box3D physics without breaking other features |
| `org.jgrapht:jgrapht-core:1.5.2` | Required only if using graph APIs | Declared in `:modules:core`'s `shadowLibrary` but not in `:modules:library`'s `shadowLibrary` (`kasuga-library-*-all.jar` omits `org/jgrapht`); mods using `lib.kasuga.content.graph.*` must declare `jgrapht-core` themselves |

## Next Steps

- Build a runnable minimal mod: [Minimal Example Project](minimal-example.md)
- Build artifacts and deploy to clients, servers, or modpacks: [Installation and Deployment](installation.md)
- Understand init ordering and client/server splits: [Lifecycle and Initialization Order](../reference/lifecycle.md) and [Sidedness Model](../reference/sidedness.md)
