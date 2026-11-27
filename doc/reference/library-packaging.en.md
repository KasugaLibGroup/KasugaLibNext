# Library Packaging, Shade Relocation, and Multiversion Structure Reference

This reference documents the root build conventions and packaging modules (`:modules:library`, `:modules:annotations`, `:modules:multiversion`, and `GAMELIBRARY` subprojects), including Shadow relocation rules, `neoforge.mods.toml` metadata assembly, and dependency version baselines.

> For the Gradle plugins themselves see [Gradle Plugins, Codegen, and Resource Compiler Reference](gradle-and-codegen.md); for the companion scripting mod `:external:slp:javet` see [Scripting and Javet Runtime Reference](scripting.md).

## Monorepo Build Conventions

Root `settings.gradle` registers `includeBuild './modules/gradle-plugin'` and 23 `include` subprojects (22 unconditional, plus `:modules:old` only when `-PkasugaIncludeOld=true`). Root `build.gradle` applies three global rules:

1. **Java 21 toolchain**: `java.toolchain.languageVersion = JavaLanguageVersion.of(21)` (`options.encoding = 'UTF-8'` is configured per subproject on `JavaCompile` / `Javadoc` tasks rather than in root `build.gradle`).
2. **Unique archive names (JPMS safety)**: every subproject with `base` receives `archivesName = "kasuga-${project.name}"`. ModDevGradle derives the runtime JPMS module name from each JAR filename, so subprojects must never share `mod_id` (`kasuga_lib`) as their archive name.
3. **Global Lombok injection**: every `SourceSet` automatically adds `org.projectlombok:lombok:${lombok_version}` (`1.18.42` in `gradle.properties`) to `compileOnly` and `annotationProcessor`.

### Baseline Properties (`gradle.properties`)

| Property | Value | Meaning |
| --- | --- | --- |
| `minecraft_version` / `minecraft_version_range` | `1.21.1` / `[1.21.1]` | Target Minecraft release |
| `neo_version` / `loader_version_range` | `21.1.268` / `[1,)` | NeoForge & FML loader range (ModDevGradle `2.0.107`) |
| `parchment_minecraft_version` / `parchment_mappings_version` | `1.21.1` / `2024.11.17` | Parchment parameter mappings |
| `mod_id` / `mod_name` / `mod_version` / `mod_group_id` | `kasuga_lib` / `KasugaLib Next` / `1.0.0` / `lib.kasuga` | Shared mod metadata and Maven coordinates |
| `create_version` / `ponder_version` / `flywheel_version` / `registrate_version` | `6.0.11-346` / `1.0.92` / `1.0.7` / `MC1.21-1.3.0+67` | Create ecosystem dependency versions |
| `cct_version` | `1.116.1` | CC:Tweaked integration version |

## Packaging Categories

| Category | Modules | Packaging / Manifest | Consumption & JPMS Rationale |
| --- | --- | --- | --- |
| Aggregate mod distribution | `:modules:library` | Merges 8 mod source sets via `useModProject` + `shadowJar` (`classifier = 'all'`) | Single distributable JAR carrying the unified `kasuga_lib` mod layer |
| Pure-JVM libraries | `:modules:shader`, `:modules:shader-graal`, `:modules:render-core`, `:modules:render-opengl` | Standard `java-library` JARs (`render-core` embeds `caliko-1.3.8.jar` and enforces `checkGraphicsApiIsolation` / `checkModelFormatIsolation`) | Minecraft-free (`render-opengl` adds LWJGL/OpenGL); pulled into mod layers via `useModProject` or `api` |
| NeoForge `GAMELIBRARY` | `:modules:utils`, `:modules:inject`, `:modules:formula`, `:modules:data-driven`, `:modules:annotations` | Manifest `'FMLModType': "GAMELIBRARY"` | Embedded via `shadowLibrary` or `useModProject(..., false)` so a package never appears on both a `GAMELIBRARY` module and the `kasuga_lib` mod layer simultaneously |
| Optional companion mod | `:external:slp:javet` | Separate mod ID `kasuga_lib_javet`; `runtimeOnly` consumes `:modules:library` with `Bundling.SHADOWED` | Keeps `kasuga_lib` as one JPMS module at dev/runtime while shipping Javet `5.0.1` + `httpclient:4.5.13` |
| Build-time tools | `:modules:gradle-plugin`, `:modules:generator`, `:modules:resource-compiler` | Build/JavaExec tooling only | Not packaged into runtime mod JARs |
| Multiversion scaffold | `:modules:multiversion` | `neoforge` + `shadowJar` (`zip64 = true`) | See below |

## `:modules:library` Aggregation and Shade Relocation

`modules/library/build.gradle` applies `java-library`, `maven-publish`, `net.neoforged.moddev` (`2.0.107`), `com.gradleup.shadow` (`9.0.2`), `builder.kasuga.generator`, and `builder.kasuga.monorepo`, with `assemble.dependsOn shadowJar`.

### Merged Mod Source Sets (`useModProject`)

`modules/library/build.gradle` merges `main` classes and resources (excluding subproject `META-INF/neoforge.mods.toml` and `META-INF/mods.toml`) and adds `contentTesting` source sets to `neoForge.mods.kasuga_lib` from 8 modules:

1. `:modules:scripting`
2. `:modules:modelling`
3. `:modules:iris-compat`
4. `:modules:render-core`
5. `:modules:render-opengl`
6. `:modules:core`
7. `:modules:create`
8. `:modules:data-driven`

`sourcesJar` uses `DuplicatesStrategy.EXCLUDE` and depends on `generateModMetadata` from `:modules:core`, `:modules:scripting`, `:modules:modelling`, and `:modules:create`.

### `shadowLibrary` Embedding and Relocations

`shadowJar` sets `archiveClassifier.set('all')` (producing `kasuga-library-1.0.0-all.jar`), reading `configurations = [project.configurations.shadowLibrary]` plus `from(sourceSets.main.output)` and the 8 merged subproject outputs appended by `useModProject`.

Explicit `shadowLibrary` inclusions:

- Internal modules: `:modules:inject`, `:modules:utils`, `:modules:shader`, `:modules:formula`
- Third-party libraries:
  - `de.javagl:jgltf-model:2.0.4` and `de.javagl:jgltf-impl-v2:2.0.4`
  - `modules/render-core/libs/caliko-1.3.8.jar` (while `processResources` copies `caliko-LICENSE.txt` into `META-INF/licenses/`)
  - `com.google.zxing:core:3.5.3`

Shadow configuration details:

- `mergeServiceFiles()`: merges `META-INF/services/` descriptors.
- `transform(MicronautServiceShadowTransformer) {}`: rewrites `META-INF/micronaut/**` entries to match package relocations.
- Package relocations (preventing clashes with user environments or other Micronaut/Jakarta mods):
  - `io.micronaut` -> `lib.kasuga.vendors.io.micronaut`
  - `jakarta.inject` -> `lib.kasuga.vendors.jakarta.inject`
  - `jakarta.annotation` -> `lib.kasuga.vendors.jakarta.annotation`
  - `lib.kasuga.inject` -> `lib.kasuga.vendors.inject`
- Excludes signature artifacts: `META-INF/*.SF`, `META-INF/*.DSA`, `META-INF/*.RSA`.

For compilation and ModDev runs, `library/build.gradle` also declares `implementation` / `additionalRuntimeClasspath` / `compileOnly` dependencies on Micronaut `4.9.10` (excluding `org.slf4j:slf4j-api`), Create `6.0.11-346` (`transitive = false`), Ponder `1.0.92+mc1.21.1`, Flywheel API/Runtime `1.0.7`, Registrate `MC1.21-1.3.0+67`, and CC:Tweaked `1.116.1`.

### `neoforge.mods.toml` and Mixin Aggregation

`modules/library/src/main/templates/META-INF/neoforge.mods.toml` is expanded by `generateModMetadata` into `build/generated/sources/modMetadata/META-INF/neoforge.mods.toml` (wired to `neoForge.ideSyncTask`):

- **Loader & mod metadata**: `modLoader = "javafml"`, `loaderVersion = "${loader_version_range}"`, `license = "${mod_license}"`, `modId = "${mod_id}"`, `version = "${mod_version}"`, `displayName = "${mod_name}"`.
- **Mixin configs**:
  - `kasuga_lib.mixins.json` (from `:modules:core`, `required: true`, plugin `lib.kasuga.mixins.KasugaLibMixinPlugin`)
  - `kasuga_lib_iris.mixins.json` (from `:modules:iris-compat`, `required: false`, gated by `lib.kasuga.compat.iris.IrisCameraMixinPlugin`)
  - `kasuga_lib_create.mixins.json` (from `:modules:create`, `required: true`, plugin `lib.kasuga.create.compat.CreateMixinPlugin`)
- **Required dependencies**: `neoforge` (`[${neo_version},)`, `side="BOTH"`) and `minecraft` (`${minecraft_version_range}`, `side="BOTH"`).

Verification tests: `modules/library/src/test/java/lib/kasuga/library/LibraryModMetadataTest.java` asserts that the generated `META-INF/neoforge.mods.toml` contains both `kasuga_lib.mixins.json` and `kasuga_lib_create.mixins.json`, and that `kasuga_lib_create.mixins.json` declares `client.BezierConnectionMixin`. `src/contentTesting/java/test/kasuga/TestKasugaLibApplication.java` provides a `@Context` test entrypoint binding `KasugaLibRegistry.getRegistryOf(KasugaLib.MODID)` to `modEventBus`.

## `:modules:annotations`

`modules/annotations/build.gradle` configures Java 21, NeoForge ModDev `2.0.107` (with `unitTest.enable()`), JUnit 5, and sets `'FMLModType': "GAMELIBRARY"` in `jar.manifest` (artifact `kasuga-annotations-1.0.0.jar`).

At present `:modules:annotations` contains **no Java source files**; it acts as a placeholder `GAMELIBRARY` dependency referenced by `:modules:modelling` (`implementation`) and `:external:slp:javet` (`compileOnly`). Active codegen annotations live in `:modules:generator` (`@CodeTemplate`, `@RegGenerator`, `@Prototype`), scripting security annotations (`@Api`) live in `:modules:scripting`, and conditional mixin / registry generation annotations live in `:modules:utils`.

## `:modules:multiversion`

`modules/multiversion/` is a standalone mod-packaging scaffold:

- `modules/multiversion/build.gradle` applies `java-library`, `maven-publish`, `net.neoforged.moddev` (`2.0.107`), and `com.gradleup.shadow` (`9.0.2`, `shadowJar { zip64 = true }`).
- `neoForge.mods."${mod_id}"` binds `:modules:core`'s `sourceSets.main` together with local `sourceSets.main`, depending on `:modules:inject`, Micronaut `4.9.10`, Create `6.0.11-346`, Ponder `1.0.92+mc1.21.1`, Flywheel `1.0.7`, and Registrate `MC1.21-1.3.0+67`.
- On current `HEAD`, only `build.gradle` and `src/main/templates/META-INF/neoforge.mods.toml` (a baseline metadata template with no `[[mixins]]` blocks) are tracked, with no `src/main/java` sources.

## Verification Commands

```sh
# Run :modules:library metadata tests and full release verification check
./gradlew :modules:library:test
./gradlew renderingReleaseCheck

# Assemble only the shaded all-in-one library JAR (*-all.jar)
./gradlew :modules:library:shadowJar
```
