# Contributor Documentation

This page records `KasugaLibNext` branching conventions, coding guidelines, module isolation rules, build and verification tasks, CI workflows, and documentation authoring practices.

## Branch and PR Conventions

Per workspace guidelines (`AGENTS.md`):

- Work directly on the `master` branch across workspace repositories; **do not create separate feature branches**.
- Always use `master` as the source branch when opening pull requests.

## Coding and Architecture Conventions

- **Reuse existing infrastructure**: Prefer existing KasugaLib APIs; when infrastructure is missing, extend shared general-purpose APIs instead of duplicating plumbing inside feature packages.
- **Keep GLSL in resource files**: Place GLSL shader sources in resource files (e.g., `src/main/resources/assets/.../shaders/`) or generate them via `builder.kasuga.shader` / the Java Shader DSL; never embed GLSL source code as Java/Kotlin string literals.
- **Control flow and explicit types**:
  - Keep control flow flat with clear conditions and early returns.
  - Minimize `var`; prefer explicit type declarations so types and data flow stay clear.
  - Prefer readable loops over complex `Stream` chains where direct loops are clearer.
  - Prefer explicit behavior over implicit side effects, obscure abbreviations, or unnecessary abstractions.
- **Module isolation boundaries**:
  - `modules/render-core` must stay free of Minecraft, NeoForge, Fabric, LWJGL, and concrete graphics-API imports, and core subsystems must not depend on concrete format packages (`uml.typo`). `:modules:render-core:checkGraphicsApiIsolation` and `:modules:render-core:checkModelFormatIsolation` enforce this on every `compileJava` and `check`.
  - Keep `modules/modelling/src/main/native/box3d` byte-identical to the pinned upstream commit; implement adaptations in `kasuga_box3d_jni.c`, `CMakeLists.txt`, or Java adapters. `:modules:modelling:verifyBox3DUpstream` checks `BOX3D_VERSION` and all 113 SHA-256 entries in `BOX3D_SOURCES.json` before native configuration and on `check`.
  - Root `build.gradle` assigns each subproject a unique `archivesName = "kasuga-${project.name}"`; do not override subproject JAR names back to `kasuga_lib`, which causes JPMS module-layer collisions in ModDev runs.

## Local Build and Verification Commands

Run all commands from the `KasugaLibNext` root (`./gradlew` on Linux/macOS, `.\gradlew.bat` on Windows). The project pins **Java 21** and Gradle Wrapper **8.14.3**:

| Scope | Command | Notes |
| --- | --- | --- |
| Full build and release gate | `./gradlew build renderingReleaseCheck` | Builds and verifies `gradle-plugin`, `shader`, `shader-graal`, `render-core`, `render-opengl`, `modelling`, `iris-compat`, and `library` |
| Modelling plain-JVM tests | `./gradlew :modules:modelling:modelUnitTest` | Runs without the NeoForge dedicated-server launcher; builds and tests `kasuga_box3d` if `cmake` is on `PATH`, otherwise excludes `box3d`-tagged tests |
| Other plain-JVM module tests | `./gradlew :modules:formula:test :modules:shader:test :modules:shader-graal:check :modules:scripting:scriptUnitTest` | Requires neither Minecraft startup nor a GPU context |
| Data-driven and Core tests | `./gradlew :modules:data-driven:test :modules:core:test` | Runs parser, v2 build pipeline, and FML JUnit-gated tests |
| Standalone OpenGL regression | `./gradlew :modules:modelling:renderGlTest` | Starts a standalone LWJGL/OpenGL context; `renderPreview` and `renderBench` share the same harness |
| Native Box3D build | `./gradlew :modules:modelling:buildBox3D` | Requires `cmake` on `PATH`; outputs to `modules/modelling/build/native/box3d/resources/native/<os>-<arch>/` |
| Opt-in legacy UI browsing | `./gradlew -PkasugaIncludeOld=true ...` | Excluded from default builds; `modules/old` applies Gradle's `base` plugin for source browsing only |

> For client/headless smoke flags, `report.json` acceptance rules, and evidence boundaries, see [Build and Verification Entries](../guides/verification.md).

## CI Workflows and Publishing

`.github/workflows/` defines two active workflows (both skip runs when only `**.md`, `doc/**`, `.gitignore`, or `LICENSE.txt` change):

1. **`build.yml` (Build)**:
   - `ubuntu-latest`: runs `./gradlew build renderingReleaseCheck`, uploading the base `kasuga-modelling` JAR and Linux Box3D native library.
   - `windows-latest` and `macos-latest`: run `:modules:modelling:buildBox3D` and upload platform `kasuga_box3d` natives.
   - `package-universal`: merges all three platform libraries under `native/<os>-<arch>/` into `kasuga-modelling-universal.jar` and verifies at least 3 platform natives are packed.
2. **`render-harness.yml` (Standalone rendering)**:
   - `ubuntu-latest` (`mesa-regression`): installs `xvfb` and Mesa software OpenGL (`LIBGL_ALWAYS_SOFTWARE=1`), runs `xvfb-run -a ./gradlew :modules:modelling:renderGlTest --console=plain`, and uploads reports/failure images.
   - `windows-latest` and `macos-latest` (`portable-harness-build`): compiles `renderHarnessClasses` / `jmhClasses` and runs `modelUnitTest`.

Submodules configure `maven-publish` (`mavenJava`) targeting per-module local directories (`file://${project.projectDir}/repo`); no automated remote Maven release workflow is configured in `.github/workflows/`.

## Documentation Contributions and Site Builds

Documentation prose lives exclusively under `doc/` in `KasugaLibNext`, while `KasugaLibGroup/KasugaLib-Documentation` serves as the deploy-only MkDocs repository:

- **Bilingual layout**: Pair `page.md` (Chinese) with `page.en.md` (English) in the same directory.
- **Cross-page links**: Always **omit the `.en` suffix** (`limitations.md`, not `limitations.en.md`) so `mkdocs-static-i18n` resolves each locale cleanly. Avoid relative `../../` links escaping `doc/`, because `mkdocs.yml` sets `docs_dir: .docs-src/doc`.
- **Terminology**: Follow [Chinese-English Glossary](glossary.md) and its no-translate whitelist.
- **Navigation and deployment**: `KasugaLib-Documentation/mkdocs.yml` maintains `nav` and `nav_translations` manually, matching each page's H1 verbatim. Update `mkdocs.yml` whenever adding or renaming pages; `deploy-docs.yml` polls `KasugaLibNext@master` roughly every 6 hours (or runs via `workflow_dispatch`), builds with `mkdocs build` (deliberately without `--strict`) when `doc/` changed, and publishes to `gh-pages`.
