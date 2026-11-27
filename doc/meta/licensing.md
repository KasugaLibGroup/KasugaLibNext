# 许可证与再分发

本页依据仓库内实际声明的许可证文件、构建配置与第三方声明，说明 `KasugaLibNext` 及其内嵌/引用组件的授权状态与再分发边界。

## 仓库与模组元数据声明

当前仓库中的许可证声明分为两部分，使用前务必区分：

| 文件 / 配置 | 声明内容 | 适用范围 |
| --- | --- | --- |
| 仓库根目录 `LICENSE.txt` | MIT License（Copyright (c) 2023 NeoForged project） | 文件正文明确限定：**仅适用于从 `github.com/NeoForged/MDK` 提供的模板文件**，不自动覆盖 KasugaLibNext 自身的源码与资源 |
| `gradle.properties`（`mod_license`）及各模块 `neoforge.mods.toml` | `All Rights Reserved` | `core`、`library`、`modelling`、`create`、`scripting`、`multiversion`、`javet`（`external/slp/javet/gradle.properties`）与 `ldlib`（`external/uip/ldlib/gradle.properties`）通过 `src/main/templates/META-INF/neoforge.mods.toml` 展开 `mod_license=All Rights Reserved`；`ldlib-compose` 与 `modernui` 无独立 `gradle.properties` 或模板，在 `src/main/resources/META-INF/neoforge.mods.toml` 直接声明 `license="All Rights Reserved"` |
| `modules/modelling/src/main/native/kasuga_box3d_jni.c` | `// SPDX-License-Identifier: LGPL-3.0-or-later` | Box3D JNI C 桥接源码；构建时通过 `CMakeLists.txt` 静态链接 Box3D 生成 `kasuga_box3d` 共享库（`native/<os>-<arch>/`） |

> **再分发与 `jarJar` 边界**：在当前仓库状态中，`gradle.properties` 与全部 `neoforge.mods.toml` 均声明 `All Rights Reserved`，且 `LICENSE.txt` 仅覆盖 NeoForged MDK 模板本身，仓库未附带面向 KasugaLibNext 自身 Java/Groovy/GLSL 代码的开源授权。因此，下游项目不得未经作者明确授权将 KasugaLibNext 的产物直接 `jarJar` 打包、拆包重制或作为自有代码再分发；正常接入应以独立模组依赖或源码工作区 `includeBuild` 方式使用。若分发包含 `modules/modelling` 原生物理库（`kasuga_box3d`）的构建产物，还须遵守 `kasuga_box3d_jni.c` 声明的 `LGPL-3.0-or-later` 与随包第三方声明要求。

## 随产物内嵌的第三方组件与声明

仓库中明确附带了以下第三方许可证与声明文件：

| 组件 | 许可证 | 仓库位置与打包位置 | 说明 |
| --- | --- | --- | --- |
| Box3D（提交 `336263320469e5e9febfe18697ab562488f7e97a`） | MIT License（Copyright (c) 2026 Erin Catto） | 源码位于 `modules/modelling/src/main/native/box3d/`，许可位于 `box3d/LICENSE`；打包进 JAR 的 `META-INF/THIRD_PARTY_NOTICES.md` 与 `META-INF/licenses/box3d-LICENSE.txt` | 保持上游文件不可变（由 `:modules:modelling:verifyBox3DUpstream` 校验），静态链接至 `kasuga_box3d` |
| Caliko `1.3.8` | MIT License（Copyright (c) 2016-2020 Alastair Lansley / Federation University Australia） | `modules/render-core/libs/caliko-1.3.8.jar` 与 `caliko-LICENSE.txt`；打包进 `render-core`、`modelling` 与 `library` JAR 的 `META-INF/licenses/caliko-LICENSE.txt` | 未修改的官方 `1.3.8` core jar（SHA-256 记录于 `modules/render-core/libs/README.md`） |
| JetBrains Mono 字体 | SIL Open Font License 1.1 | `modules/modelling/src/contentTesting/resources/assets/kasuga_demo/font/OFL-JetBrainsMono.txt` | 仅位于 `contentTesting` 源集，不进入发布的生产 JAR |

## `shadowJar` 内嵌与外部运行时依赖边界

在工程打包层面，各模块已经显式划分了哪些库会被打进发布产物、哪些保留为外部依赖：

1. **聚合分发包 `modules/library`（`shadowJar` `-all`）**：
   - 将 `core`、`modelling`、`iris-compat`、`render-core`、`render-opengl`、`create`、`data-driven`、`scripting`、`inject`、`utils`、`shader`、`formula` 合并为单个 NeoForge 模组 JAR（`mod_id=kasuga_lib`）。
   - 使用 `MicronautServiceShadowTransformer` 与 Shadow 重定位规则，将 `io.micronaut`、`jakarta.inject`、`jakarta.annotation`、`lib.kasuga.inject` 重定位至 `lib.kasuga.vendors.*`。
   - 同时内嵌 `de.javagl:jgltf-model:2.0.4`、`de.javagl:jgltf-impl-v2:2.0.4`、`caliko-1.3.8.jar` 与 `com.google.zxing:core:3.5.3`；`modules/core` 的 `-all` 产物另内嵌 `org.jgrapht:jgrapht-core:1.5.2`。
   - 注意：上述 Maven 依赖（Micronaut、jgltf、ZXing、JGraphT 等）未在仓库目录中单独存放各自的上游 `LICENSE`/`NOTICE` 文本；内部或授权环境打包分发 `-all` 产物时，除保留 `META-INF/THIRD_PARTY_NOTICES.md` 与 `META-INF/licenses/` 外，需自行核对其上游授权条款。
2. **明确排除、不随核心库打包的外部组件**：
   - **Iris 与 Sodium**：`modules/modelling/build.gradle` 明确从 `shadowJar` 中排除了 `libs:iris:.*` 与 `libs:sodium:.*`，仅在运行期通过 `modules/iris-compat` 可选适配。
   - **Sable / Aeronautics、Create、CC:Tweaked、LDLib2、LDLib2 Compose、ModernUI**：均由玩家环境或外部适配模组自行提供，不内嵌进 `kasuga-library`。
   - **Javet V8 原生库**（`external/slp/javet`）：模组仅声明 Java 端 `javet:5.0.1`（及开发期 `httpclient:4.5.13`），各平台 V8 原生动态库在首次启动时由 `JavetDownloader` 从 Maven 镜像下载到游戏目录 `native-libraries/javet/`，不随模组 JAR 跨平台打包。
   - **GraalJS**（`modules/shader-graal`）：`polyglot`、`js-language`、`truffle-runtime` 作为普通 Maven 依赖声明，不打入 `kasuga-library`。
3. **JPMS 模块层与单包冲突约束**：
   - 基于 ModDevGradle / FML 的 JPMS 模块层要求同一个 Java 包不能由多个独立的 `GAMELIBRARY` / 模组 JAR 同时导出。外部工程使用 `kasuga-library` 的 shadowed `-all` 产物时，必须避免将独立的 `kasuga-utils` JAR 一并放进运行时类路径，否则会在启动早期触发 `ResolutionException`（详见 [使用方](users.md) 中的 `KuaYue` 配置）。
