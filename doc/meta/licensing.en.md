# License and Redistribution

This page documents the licensing status and redistribution boundaries of `KasugaLibNext` and its bundled or referenced components based strictly on the license files, build scripts, and third-party notices present in the repository.

## Repository and Mod Metadata Declarations

License declarations in the repository are split across separate scopes:

| File / Setting | Declared Term | Scope |
| --- | --- | --- |
| Root `LICENSE.txt` | MIT License (`Copyright (c) 2023 NeoForged project`) | The file text explicitly states: *"This license applies to the template files as supplied by github.com/NeoForged/MDK"*; it does not grant a blanket open-source license over KasugaLibNext's own source code or assets |
| `gradle.properties` (`mod_license`) and module `neoforge.mods.toml` files | `All Rights Reserved` | `core`, `library`, `modelling`, `create`, `scripting`, `multiversion`, `javet` (`external/slp/javet/gradle.properties`), and `ldlib` (`external/uip/ldlib/gradle.properties`) expand `mod_license=All Rights Reserved` into `src/main/templates/META-INF/neoforge.mods.toml`; `ldlib-compose` and `modernui` have no `gradle.properties` or template and declare `license="All Rights Reserved"` literally in `src/main/resources/META-INF/neoforge.mods.toml` |
| `modules/modelling/src/main/native/kasuga_box3d_jni.c` | `// SPDX-License-Identifier: LGPL-3.0-or-later` | Box3D JNI C bridge source, statically linked with vendored Box3D via `CMakeLists.txt` into the `kasuga_box3d` shared library (`native/<os>-<arch>/`) |

> **Redistribution and `jarJar` boundary**: In the current repository state, `gradle.properties` and every generated `neoforge.mods.toml` declare `All Rights Reserved`, and `LICENSE.txt` applies only to the NeoForged MDK template files. Because no open-source grant is declared for KasugaLibNext's own Java/Groovy/GLSL code, downstream projects should **not** `jarJar`, unpack, or repackage KasugaLibNext artifacts as bundled third-party code without explicit permission from the KasugaLib authors. Consume KasugaLibNext as a declared mod/library dependency (or via workspace `includeBuild`). Any distribution containing the `kasuga_box3d` native binary from `modules/modelling` must also respect the `LGPL-3.0-or-later` notice on `kasuga_box3d_jni.c` and the bundled third-party notices.

## Bundled Third-Party Components and Notices

The repository includes explicit third-party license and notice files for:

| Component | License | Repository and JAR Locations | Notes |
| --- | --- | --- | --- |
| Box3D (commit `336263320469e5e9febfe18697ab562488f7e97a`) | MIT License (`Copyright (c) 2026 Erin Catto`) | Sources under `modules/modelling/src/main/native/box3d/`, license in `box3d/LICENSE`; packaged into `META-INF/THIRD_PARTY_NOTICES.md` and `META-INF/licenses/box3d-LICENSE.txt` | Upstream directory is kept immutable (`:modules:modelling:verifyBox3DUpstream`) and statically linked into `kasuga_box3d` |
| Caliko `1.3.8` | MIT License (`Copyright (c) 2016-2020 Alastair Lansley / Federation University Australia`) | `modules/render-core/libs/caliko-1.3.8.jar` and `caliko-LICENSE.txt`; packaged into `META-INF/licenses/caliko-LICENSE.txt` in `render-core`, `modelling`, and `library` JARs | Unmodified official `1.3.8` core JAR (SHA-256 recorded in `modules/render-core/libs/README.md`) |
| JetBrains Mono font | SIL Open Font License 1.1 | `modules/modelling/src/contentTesting/resources/assets/kasuga_demo/font/OFL-JetBrainsMono.txt` | Resides in `contentTesting` only; excluded from production JARs |

## `shadowJar` Embedding vs. External Runtime Dependencies

The Gradle build explicitly separates embedded libraries from external dependencies:

1. **Aggregate distribution `modules/library` (`shadowJar` `-all`)**:
   - Aggregates `core`, `modelling`, `iris-compat`, `render-core`, `render-opengl`, `create`, `data-driven`, `scripting`, `inject`, `utils`, `shader`, and `formula` into one NeoForge mod JAR (`mod_id=kasuga_lib`).
   - Uses `MicronautServiceShadowTransformer` and Shadow relocations to relocate `io.micronaut`, `jakarta.inject`, `jakarta.annotation`, and `lib.kasuga.inject` under `lib.kasuga.vendors.*`.
   - Embeds `de.javagl:jgltf-model:2.0.4`, `de.javagl:jgltf-impl-v2:2.0.4`, `caliko-1.3.8.jar`, and `com.google.zxing:core:3.5.3` (`modules/core`'s `-all` JAR also embeds `org.jgrapht:jgrapht-core:1.5.2`).
   - Note: These Maven dependencies (Micronaut, jgltf, ZXing, JGraphT, etc.) do not have separate upstream `LICENSE`/`NOTICE` text files checked into the repository; any authorized distribution of shaded `-all` artifacts must preserve `META-INF/THIRD_PARTY_NOTICES.md` and `META-INF/licenses/` and verify the upstream terms of those Maven libraries.
2. **Explicitly excluded or optional external components**:
   - **Iris and Sodium**: `modules/modelling/build.gradle` explicitly excludes `libs:iris:.*` and `libs:sodium:.*` from `shadowJar`; runtime integration goes through `modules/iris-compat`.
   - **Sable / Aeronautics, Create, CC:Tweaked, LDLib2, LDLib2 Compose, ModernUI**: Supplied by the user mod environment or optional adapter mods; not embedded in `kasuga-library`.
   - **Javet V8 natives** (`external/slp/javet`): The adapter declares the Java `javet:5.0.1` dependency (and `httpclient:4.5.13`), while platform V8 shared libraries are fetched at runtime by `JavetDownloader` into `<gameDir>/native-libraries/javet/` rather than bundled inside the mod JAR.
   - **GraalJS** (`modules/shader-graal`): Declared as standard Maven dependencies (`polyglot`, `js-language`, `truffle-runtime`), not shaded into `kasuga-library`.
3. **JPMS module-layer split-package constraint**:
   - Under ModDevGradle / FML's JPMS module layer, two JARs on the runtime module path cannot export the same Java packages. Downstream mods consuming `kasuga-library`'s shadowed `-all` JAR alongside `kasuga-inject` must exclude standalone `lib.kasuga:utils` from runtime classpaths to avoid startup `ResolutionException` (see the `KuaYue` setup in [Consumers](users.md)).
