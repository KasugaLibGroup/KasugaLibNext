# 版本兼容矩阵

本页汇总 `KasugaLibNext` 在仓库构建配置（`gradle.properties`、`build.gradle`、`gradle/wrapper/gradle-wrapper.properties`、各子模块 `build.gradle`）以及既有验证文档中直接可查的版本基线与可选集成矩阵。

## 核心基线版本

以下基线来自仓库根目录的 `gradle.properties`、`build.gradle` 与 `gradle/wrapper/gradle-wrapper.properties`：

| 组件 | 声明基线 / 范围 | 来源 |
| --- | --- | --- |
| KasugaLib (`mod_id`: `kasuga_lib`) | `1.0.0`（下游范围 `[1.0.0,)`） | `gradle.properties` |
| Maven 组名 | `lib.kasuga` | `gradle.properties` |
| Java 工具链 | Java `21`（CI 使用 Temurin JDK 21） | `build.gradle`、`.github/workflows/build.yml` |
| Gradle Wrapper | `8.14.3` | `gradle/wrapper/gradle-wrapper.properties` |
| ModDevGradle | `2.0.107` | `build.gradle` |
| Shadow 插件 | `9.0.2` | `modules/*/build.gradle` |
| Minecraft | `1.21.1`（`minecraft_version_range=[1.21.1]`） | `gradle.properties` |
| NeoForge | `21.1.268`（`neoforge.mods.toml` 模板声明 `[21.1.268,)`；`loader_version_range=[1,)`） | `gradle.properties`、`modules/*/src/main/templates/` |
| Parchment 映射 | `1.21.1` / `2024.11.17` | `gradle.properties` |
| Lombok | `1.18.42`（全部 Java 子项目全局配置） | `gradle.properties`、`build.gradle` |
| Micronaut (`inject` / `aop`) | `4.9.10`（`inject` 单测附带 `micronaut-test-junit5:4.8.1`） | `modules/inject/build.gradle`、`modules/library/build.gradle` |

> 上述坐标随源码处于持续开发中，当前仓库没有单独的 `gradle/libs.versions.toml`，所有依赖版本均直接声明在 `gradle.properties` 与各模块 `build.gradle` 中。

## 子模块内置与传递依赖

| 模块 | 主要依赖与版本 | 说明 |
| --- | --- | --- |
| `modules/render-core` | `joml:1.10.5`、`datafixerupper:8.0.16`、`gson:2.10.1`、`guava:32.1.2-jre`、`jgltf-model:2.0.4`、`jgltf-impl-v2:2.0.4`、`caliko-1.3.8.jar` | 纯 Java 库；`checkGraphicsApiIsolation` 禁止引入 Minecraft、NeoForge 或 LWJGL |
| `modules/render-opengl` | `lwjgl:3.3.3`、`lwjgl-opengl:3.3.3`、`lwjgl-glfw:3.3.3`、`slf4j-api:2.0.9` | 不应用 ModDev；由宿主提供当前 OpenGL 上下文与线程 |
| `modules/modelling` | 固定 vendored Box3D C17（提交 `336263320469e5e9febfe18697ab562488f7e97a`）、`modules/iris-compat`、JMH `1.37`（仅 benchmark） | 运行时需要对应平台 CMake 编译出的 `kasuga_box3d` JNI 库；默认开发运行可加载本地 Iris/Sodium jar |
| `modules/core` | `jgrapht-core:1.5.2`、`zxing:core:3.5.3`、Micronaut `4.9.10` | `shadowJar`（`-all`）内嵌 `jgrapht-core`、`zxing`、`utils` 与重定位后的 Micronaut / `inject` |
| `modules/library` | `zxing:core:3.5.3`、Micronaut `4.9.10`、CC:Tweaked `1.116.1`、Create 生态依赖 | `shadowJar`（`-all`）聚合各子模块与 `jgltf`、`caliko`、`zxing`、`formula`、`shader` 等（注意 `jgrapht-core` 声明在 `modules/core` 而非 `modules/library` 的 `shadowLibrary` 中） |
| `modules/generator` | `javaparser-core:3.27.1`、`javaparser-symbol-solver-core:3.27.1` | 仅构建期代码生成使用 |
| `modules/resource-compiler` | `night-config:toml:3.6.7`、`gson:2.10.1` | 仅构建期 `compileResources` 使用 |
| `modules/shader-graal` | `graalvm.polyglot` / `js-language` / `truffle-runtime` `24.1.2` | 可选模块；无需启动 Minecraft 即可在 JVM 验证 |

## 可选模组与适配兼容矩阵

下列可选集成的声明版本与实际验证版本分别记录在 `gradle.properties`、各适配模块 `build.gradle` 与专项验证文档中：

| 集成目标 | 构建声明 / 依赖版本 | 已记录的实际验证组合 | 对应模块与参考文档 |
| --- | --- | --- | --- |
| Create / Ponder / Flywheel / Registrate | 默认：`Create 6.0.11-346`、`Ponder 1.0.92+mc1.21.1`、`Flywheel 1.0.7`、`Registrate MC1.21-1.3.0+67` | 此前在 NeoForge `21.1.228` 下回归过 `Create 6.0.10-280`、`Flywheel 1.0.6`、`Registrate MC1.21-1.3.0+67` | `modules/create`、[移动结构宿主](../reference/moving-scene-hosts.md)、[Sable 场景宿主](../reference/sable-scene-hosts.md) |
| CC:Tweaked | `1.116.1`（`cc-tweaked-1.21.1-forge:1.116.1`） | 随 `modules/create` 与 `modules/library` 编译及测试配置解析 | `gradle.properties`、`modules/create/build.gradle` |
| Iris / Sodium | 本地编译依赖：Iris `1.8.14-beta.1`、Sodium `0.8.14-beta.2`（Minecraft 1.21.1） | 历史记录包含 Iris `1.8.12` + Sodium `0.6.13` 的 BSL 8.2.04 冒烟、多机位与 OIT 验证；加权 OIT 也曾在 Iris `1.8.14-beta.1` 上验证排序回退 | `modules/iris-compat`、[模型材质 Alpha 渲染](../reference/render-alpha-passes.md)、[换装基线](wardrobe-benchmarks.md) |
| Sable / Aeronautics | 仅 `compileOnly` 依赖 `sable-companion-common-1.21.1:1.6.0` | macOS ARM64 上与官方 Sable `2.0.6`、航空学 `1.3.2`、Companion `1.6.0`、NeoForge `21.1.228`、Create `6.0.10-280`、Flywheel `1.0.6` 完成实机验收 | `modules/create`、[Sable 场景宿主](../reference/sable-scene-hosts.md) |
| Voxy | 可选客户端集成 | NeoForge `21.1.228`、Sodium `0.6.13`、Voxy NeoForge `0.6.0`、Forgified Fabric API `0.116.7+2.2.0`（要求 NeoForge >= `21.1.217`） | [模型碰撞与远景](../guides/world-model-collision-and-lod.md) |
| LDLib2 | `external/uip/ldlib/gradle.properties` 声明 `ldlib2_version=2.2.44` 与 `neo_version=21.1.268`（模板范围 `[2.2.44,)`） | `runClientDemo` / `runClientWorldDemo` / `runClientModelDemo` 均针对 `ldlib2-neoforge-1.21.1:2.2.44:all` 运行 | `external/uip/ldlib`、[UI 模型预览](../reference/ui-model-preview.md) |
| LDLib2 Compose | `ldlib2-compose-1.21.1:21.0.0.1:all`、Kotlin `2.1.20`、Compose `1.8.2` | 显式固定 `neoForge.version = '21.1.268'`（`neoforge.mods.toml` 要求 `kasuga_lib_ldlib2 [1.0.0,)` 与 `ldlib2_compose [21.0.0.1,)`） | `external/uip/ldlib-compose`、[LDLib2 Compose 接入](../reference/ldlib-compose.md) |
| ModernUI | `modern-ui:eMf1VSQd`（`neoforge.mods.toml` 要求 `modernui [3.13.0.1,)`） | NeoForge `21.1.268`，仅客户端预览适配器 | `external/uip/modernui` |
| Javet (`kasuga_lib_javet`) | `com.caoccao.javet:javet:5.0.1`、`httpcomponents:httpclient:4.5.13` | `JavetDownloader` 按系统拉取 `windows-x86_64`、`linux-x86_64/arm64`、`macos-x86_64/arm64` 原生库（不支持 Android） | `external/slp/javet` |

## 平台与图形运行环境

- **CI 覆盖矩阵**（`.github/workflows/build.yml`、`.github/workflows/render-harness.yml`）：
  - `ubuntu-latest`（Linux x86-64）：完整 `./gradlew build renderingReleaseCheck`、Box3D 原生编译，以及 `xvfb-run` + Mesa 软件 OpenGL（`LIBGL_ALWAYS_SOFTWARE=1`）的 `renderGlTest`。
  - `windows-latest`（Windows x86-64）：Box3D 原生编译、`renderHarnessClasses` / `jmhClasses` 编译与 `modelUnitTest`。
  - `macos-latest`（macOS ARM64）：Box3D 原生编译、`renderHarnessClasses` / `jmhClasses` 编译与 `modelUnitTest`。
- **实机图形与无窗口测试记录**（[离屏输出与多机位渲染](../guides/offline-rendering.md)、[天空体积云](../reference/volumetric-clouds.md)）：
  - macOS ARM64（Apple M3 Max，OpenGL 4.1 Metal）上执行过主视角、Iris/Sodium 多机位、多窗口、`hidden` 无窗口模式与体积云基准。
  - Linux ARM64（Debian 12 aarch64，Mesa `22.3.6` `llvmpipe` / OpenGL 4.5）上验证过无 `DISPLAY`/`WAYLAND_DISPLAY` 的 GLFW Null + EGL pbuffer 模式。
  - `hidden` 模式（macOS / Windows 默认）仍依赖桌面显示服务；独立专用服务器路径、NVIDIA/AMD 硬件 EGL 以及任意未列出的第三方光影包不在既有实机验收覆盖范围内。

> 完整的边界与已知限制见 [已知限制](limitations.md)；运行对应验证任务前请查阅 [构建与验证入口](../guides/verification.md)。
