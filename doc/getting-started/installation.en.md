# Installation and Deployment

This page is for server owners, modpack authors, and release packagers. It explains how to build deployable artifacts from source, install the right JARs into `mods/`, handle optional mods and native libraries, and configure dedicated servers and clients.

## 1. Pre-Flight Checklist

| Item | Requirement |
| --- | --- |
| Minecraft | `1.21.1` |
| Mod Loader | NeoForge `21.1.203` or newer (`[21.1.203,)`) |
| Runtime Java | Java 21 |
| Core Artifact | `kasuga-library-1.0.0-all.jar` (the `-all` Shadow JAR) |
| Companion Mods (Aggregate JAR) | Create `6.0.6-98` + Flywheel `1.0.4` + Ponder `1.0.59` + Registrate `MC1.21-1.3.0+62` (see Section 3) |

> **Never deploy the unshaded thin JAR or split sub-module JARs**
>
> - Do not install `kasuga-library-1.0.0.jar` (~44 KB); it omits relocated Micronaut/Caliko/jgltf/ZXing classes and embedded sub-modules.
> - Do not install individual sub-module JARs (such as `kasuga-core-*.jar` or `kasuga-modelling-*.jar`) alongside `kasuga_lib`; FML/JPMS module resolution will fail on split packages or duplicate mod IDs.

## 2. Building Deployable Artifacts from Source

The repository currently defines only local file-system publishing (`file://${project.projectDir}/repo`). From the repository root (with JDK 21), build the aggregate mod JAR with:

```bash
./gradlew :modules:library:assemble
```

Copy the resulting shaded artifact into your client or server `mods/` directory:

- `modules/library/build/libs/kasuga-library-1.0.0-all.jar`

To run the full compile, JVM unit test, Javadoc, and plugin verification suite (without launching a real Minecraft world):

```bash
./gradlew renderingReleaseCheck
```

See [Build and Verification Entries](../guides/verification.md) for the scope of each Gradle verification task.

## 3. Required and Optional Mods in `mods/`

### 3.1 Create and Its Dependencies

Although KasugaLibNext gates its Create DI beans behind `@CreateModBeans` (`IfCreateModLoaded`), the `:modules:library` `META-INF/neoforge.mods.toml` currently registers all three Mixin configs:

- `kasuga_lib.mixins.json`
- `kasuga_lib_iris.mixins.json` (`required: false`)
- `kasuga_lib_create.mixins.json` (`required: true`, plugin `CreateMixinPlugin`)

`CreateMixinPlugin` inspects `com/simibubi/create/content/trains/track/BezierConnection.class` when applying track Mixins and throws `IllegalStateException("Create BezierConnection is missing")` if absent. When deploying the full `kasuga-library-*-all.jar`, install a compatible `1.21.1` Create build (`6.0.6-98` and its dependencies Ponder, Flywheel, and Registrate) in `mods/`.

### 3.2 Javet JavaScript Engine (`kasuga_lib_javet`)

Install `kasuga_lib_javet` (`external/slp/javet`) only if your modpack, datapack, or dependent mods load script packages (`package.toml`) targeting the `javascript` engine.

> **Packaging caveat**: Unlike `modules/library/build.gradle`, `external/slp/javet/build.gradle` currently leaves `shadowJar` on the default `runtimeClasspath` configuration. As a result, `:external:slp:javet:shadowJar` produces a ~116 MB `kasuga-javet-1.0.0-all.jar` that bundles `net.minecraft.*` and `kasuga_lib` classes and **must not be dropped into a production `mods/` folder as-is**. Meanwhile, the thin `kasuga-javet-1.0.0.jar` omits `com.caoccao.javet:javet:5.0.1` and `org.apache.httpcomponents:httpclient:4.5.13`. Before shipping in a modpack, package only `javet` and `httpclient` with the Javet adapter classes without bundling Minecraft or `kasuga_lib`.

**Offline / air-gapped V8 native deployment**:

1. At runtime `JavetDownloader` looks under `<gameDir>/native-libraries/javet/` for the host's V8 native library (`libjavet-v8-<os>-<arch>.*`).
2. If absent, first startup attempts to download `javet-<runtime>-<os>-<arch>-5.0.1.jar` from `maven.aliyun.com` or `repo1.maven.org` and extract the native binary.
3. For offline servers, pre-extract the matching native library from `com.caoccao.javet:javet-v8-<os>-<arch>:5.0.1` into `<gameDir>/native-libraries/javet/`.
4. Dedicated-server escape hatch: if a server must boot without `kasuga_lib_javet` while scripts declare `javascript`, set `ignore_scripting_errors = true` in `config/kasuga_lib-server.toml` (logs a `WARN` instead of throwing).

### 3.3 Optional Client / Companion Mods

| Mod | Side | Behavior |
| --- | --- | --- |
| **Iris** (`1.8.12` tested) & **Sodium** (`0.6.13` tested) | Client-only | `kasuga_lib_iris.mixins.json` is non-required; `IrisCameraMixinPlugin` and `IrisCompat` probe Iris/Sodium ABIs at startup and cleanly fall back to native rendering when absent or incompatible |
| **Voxy** | Client-only | `VoxyCompat` checks `ModList.get().isLoaded("voxy")` automatically |
| **Sable** | Both (optional) | Enables Sable scene hosts and `enable_sable_weather_filter` only when Sable is installed |
| **LDLib2** (`2.2.44`) / **ModernUI** | Optional | Required only when installing the matching `external/uip/*` companion integration mod |

## 4. Box3D Native Library and Cross-Platform Packaging

Ragdoll and rigid-body physics ([PMX and glTF Ragdoll Configuration](../reference/mmd-ragdoll.md)) run on the vendored Box3D C17 engine via the `kasuga_box3d` JNI library. `NativeBox3D` extracts `/native/<os>-<arch>/<mappedName>` from the JAR:

- Building locally with `./gradlew :modules:library:assemble` compiles only the **build host's OS/architecture** when `cmake` is on `PATH` (for example `native/macos-aarch64/libkasuga_box3d.dylib` on Apple Silicon).
- If `cmake` is missing on `PATH`, Gradle logs a warning and omits the native binary; at runtime `NativeBox3D.availableOrWarn()` disables Box3D physics gracefully while all non-physics modelling and rendering features keep working.
- **Cross-platform release JARs**: To support Linux x86_64, Windows x86_64, and macOS ARM64 from a single JAR, follow the `package-universal` workflow in `.github/workflows/build.yml` (compile `buildBox3D` on each OS and pass `-PkasugaBox3DNativeResources=<dir>` or merge all `native/<os>-<arch>/` directories at the top level of the JAR).

## 5. Server/Client Configuration and Headless Launches

Via `@Configurable`, KasugaLibNext registers NeoForge `ModConfigSpec` files under `config/`:

| Config File | Key Settings and Defaults | Notes |
| --- | --- | --- |
| `config/kasuga_lib-server.toml` | `ignore_scripting_errors = false` | Controls whether missing script engines on a dedicated server log a warning instead of throwing (`ScriptingServerApplication`) |
| `config/kasuga_lib-common.toml` | `enable_multi_bogey_carriage = false`<br>`enable_carriage_y_offset = false`<br>`max_bogey_offset = 1.0`<br>`max_carriage_y_offset = 0.5`<br>`derailed_on_bogey_offset_overflow = false`<br>`enable_contraption_lod = true`<br>`enable_contraption_weather_filter = true`<br>`enable_sable_weather_filter = true` | Create train, multi-bogey carriage, contraption LOD, and indoor weather-filter toggles (`CreateConfig`, registered when Create is loaded) |
| `config/kasuga_lib-client.toml` | `ignore_block_entity_renderer = false`<br>`enable_weather_filter = true`<br>`included_entity_types = []`<br>`excluded_entity_types = []` | Client BER registration guard (`ClientRenderingRegistry`) and 3D indoor weather filter (`WeatherFilterConfig`) |

For the full option catalog, see [Configuration Options](../reference/configuration.md) and [PBR](../reference/pbr.md).

### Dedicated Servers and Headless Clients

- **Dedicated servers (`DEDICATED_SERVER`)**: No OpenGL context or display server is needed. `KasugaLibMixinPlugin` and `IrisCameraMixinPlugin` skip all client mixins, and `@BeanOnlyIn.Client` beans are not instantiated.
- **Headless / CI preview clients**: When running a client without a visible window (`runClientHeadless`), NeoForge's early splash window must be disabled beforehand (`earlyWindowControl = false` in `config/fml.toml`), and `-Dkasuga.headless=hidden` (desktop hidden window) or `-Dkasuga.headless=egl` (Linux EGL pbuffer) must be set. See [Offscreen Output and Multi-Camera Rendering](../guides/offline-rendering.md).

## 6. Modpack Reloading and Diagnostic Commands

Keep the two phases distinct when maintaining modpacks (see [Data-driven getting started](../guides/data-driven/intro.md)):

- **Registration phase (requires restart)**: `on_register` entries under `data/<namespace>/kasuga_lib/data_driven/*.json` are read from mod JARs only at startup; edits require restarting both server and client.
- **Reload phase and client assets (hot-reloadable)**: `on_reload` entries (`state_machines/`, `animation_clips/`) come from the pack stack and update on `/reload`; client `assets/<namespace>/models/model_proxy.json` and model/texture packs reload on `F3 + T`.

Built-in diagnostic commands (`/kasuga_data` requires permission level 2):

- `/kasuga_data errors` / `/kasuga_data errors <mod>` — inspect registration and reload error buckets.
- `/kasuga_proxy list` — list registered `proxy_model` / `proxy_model_be` entries.
- `/kasuga_effects` / `/kasuga_pbr status` — inspect client effect pipelines and PMX→PBR state.
