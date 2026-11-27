# 依赖与运行环境

本页列出 KasugaLibNext 的基础运行环境、构建工具链、仓库坐标、模块划分与可选运行时依赖，均以当前仓库源码与 Gradle 构建配置为准。

## 运行环境与工具链版本

下表来自根目录 `gradle.properties`、`build.gradle`、`gradle/wrapper/gradle-wrapper.properties` 与 `modules/library/src/main/templates/META-INF/neoforge.mods.toml`：

| 项目 | 要求 / 锁定版本 | 来源 |
| --- | --- | --- |
| Java | Java 21（`JavaLanguageVersion.of(21)`） | `build.gradle` |
| Gradle | `8.14.3`（Wrapper `gradle-8.14.3-bin.zip`） | `gradle/wrapper/gradle-wrapper.properties` |
| Minecraft | `1.21.1`（范围 `[1.21.1]`） | `gradle.properties` |
| Mod Loader | NeoForge `21.1.203`（`javafml`，FML 范围 `[1,)`） | `gradle.properties`、`neoforge.mods.toml` |
| ModDevGradle | `net.neoforged.moddev` `2.0.107` | `build.gradle` |
| Parchment 映射 | MC `1.21.1` / `2024.11.17` | `gradle.properties` |
| Shadow 插件 | `com.gradleup.shadow` `9.0.2` | `modules/library/build.gradle` |
| Lombok | `1.18.42`（子工程编译期自动注入） | `build.gradle`、`gradle.properties` |

> **不支持 Fabric**：仓库中所有模组工程仅配置了 `net.neoforged.moddev` 与 `javafml`。
>
> **关于版本目录**：本仓库未使用 `gradle/libs.versions.toml`；所有共享版本属性集中定义在根目录 `gradle.properties`（个别扩展子模块如 `external/uip/ldlib-compose` 在各自 `build.gradle` / `gradle.properties` 中覆写，例如其 `neo_version` 为 `21.1.238`）。

## 模组标识与发布产物

聚合模组 `:modules:library` 在运行期注册的 NeoForge 元数据如下：

| 字段 | 值 | 说明 |
| --- | --- | --- |
| `modId` | `kasuga_lib` | 运行时模组 ID（`KasugaLib.MODID`） |
| `displayName` | `KasugaLib Next` | 显示名称 |
| `group` | `lib.kasuga` | Gradle `mod_group_id` |
| `version` | `1.0.0` | 当前 `mod_version`，下游依赖范围 `kasuga_lib_version_range = [1.0.0,)` |
| 默认归档名 | `kasuga-library-1.0.0-all.jar` | 根 `build.gradle` 将子项目 `archivesName` 设为 `kasuga-${project.name}`；`shadowJar` 带 `all` 分类器 |

> **不要直接部署或依赖未重定位的瘦 JAR**：根 `build.gradle` 与 `modules/library/build.gradle` 明确要求整个聚合模组以单个 JAR 加载。若同时存在多个共享同包的子模块 JAR，FML / JPMS 模块层会拆分包空间并在启动时触发类加载错误。

### 当前仓库与发布状态

当前仓库（`modules/library/build.gradle` 等）的 `publishing` 块仅配置了本地目录仓库 `file://${project.projectDir}/repo`，**尚未在构建脚本中配置远端公开 Maven 仓库地址**。在同一个工作空间或源码构建环境中，下游模组（如工作空间中的 `KuaYue`）通过 Gradle **Composite Build（`includeBuild`）+ 依赖替换**接入。

## 模块结构与坐标映射

`settings.gradle` 包含以下子工程与构建插件工程：

### 1. 聚合模组与运行期内部模块

`:modules:library` 通过 `builder.kasuga.monorepo` 插件的 `useModProject(...)` 将以下模块汇总进 `kasuga_lib` 单一模组层（`kasuga-library-<version>-all.jar`）：

| 子工程 | 职责概览 | 说明 |
| --- | --- | --- |
| `:modules:library` | 聚合与 Shadow 打包入口 | 产出完整 `kasuga-library-*-all.jar` |
| `:modules:core` | 注册 DSL（`Reg` / `Registry`）、生命周期、资源平台、VFS、RPC、存档等 | 依赖 `Builder` 代码生成与 `kasuga_lib.mixins.json` |
| `:modules:data-driven` | JSON 数据驱动注册与 `/reload` 重载管线 | 见 [数据驱动注册专题](../reference/data-driven/README.md) |
| `:modules:modelling` | 客户端模型加载、动画 FSM、骨骼物理/IK、渲染调度、特效与多机位 | 含 Box3D JNI 桥接与 `KasugaLibMixinPlugin` |
| `:modules:iris-compat` | 可选 Iris/Sodium 兼容适配层 | 通过 ASM 字节码探测门控 `kasuga_lib_iris.mixins.json` |
| `:modules:create` | Create 铁路/动态结构/转向架与 Sable 适配 | 含 `kasuga_lib_create.mixins.json` |
| `:modules:scripting` | 脚本包发现、沙箱与生命周期宿主（`PackageSystem`） | 不直接绑定 JS 引擎，引擎由伴生模组提供 |
| `:modules:inject` | 基于 Micronaut `4.9.10` 的 DI、`@Configurable`、`@BeanOnlyIn` | 在 `-all.jar` 内重定位至 `lib.kasuga.vendors.inject` |
| `:modules:utils` | Gson 绑定、Glob/Path 匹配与注解基础工具 | Shadow 打入 `-all.jar` |
| `:modules:formula` | 纯 JVM 表达式与命名空间求值引擎 | 见 [Formula 模块](../reference/formula/README.md) |
| `:modules:shader` | 类型化 Shader DSL、IR 与 GLSL 生成器 | 见 [Java Shader DSL 参考](../reference/shader-dsl.md) |
| `:modules:render-core` | 图形 API 无关的渲染核心、Caliko IK、glTF/PMX 模型定义 | `checkGraphicsApiIsolation` 强制零 MC/LWJGL 依赖 |
| `:modules:render-opengl` | 基于 LWJGL `3.3.3` 的共享 OpenGL 实现 | — |
| `:modules:annotations` | 运行期注解占位模块（`FMLModType=GAMELIBRARY`） | 当前无 Java 源码 |
| `:modules:multiversion` | 多版本/冒烟测试宿主壳模块 | 无 `main/java` 源码 |

> **Shadow 重定位注意**：`modules/library/build.gradle` 会将 `io.micronaut`、`jakarta.inject`、`jakarta.annotation` 与 `lib.kasuga.inject` 重定位至 `lib.kasuga.vendors.*`（并嵌入 `de.javagl:jgltf-model:2.0.4`、`de.javagl:jgltf-impl-v2:2.0.4`、`caliko-1.3.8.jar`、`com.google.zxing:core:3.5.3`）。因此下游模组如果自己也要在编译/开发期使用 `@Context` 与 `ModApplicationContext`，需额外声明 `:modules:inject` 与 Micronaut 依赖；若仅使用 `KasugaLibRegistry` 与 `Reg` API，则无需配置 Micronaut。

### 2. 独立工具链、Gradle 插件与可选扩展模块

| 工程路径 | 产物 / 插件 ID | 用途 |
| --- | --- | --- |
| `./modules/gradle-plugin` | `builder.kasuga.monorepo`<br>`builder.kasuga.generator`<br>`builder.kasuga.resource-compiler`<br>`builder.kasuga.shader` | `useModProject`、JSON 深合并、`@RegGenerator` 代码生成、`compileResources`、`generateKasugaShaders` |
| `:modules:generator` | `lib.kasuga.generator:generator` | `@RegGenerator` / `@CodeTemplate` 注解处理器（依赖 JavaParser `3.27.1`） |
| `:modules:resource-compiler` | `lib.kasuga.resource:resource-compiler` | `ResourceCompilerMain` 资源编译与 `JsonMergeTransformer` |
| `:modules:shader-graal` | `lib.kasuga:shader-graal` | 可选 GraalVM Polyglot/JS `24.1.2` Shader DSL 前端（纯 JVM 可运行） |
| `:external:slp:javet` | `lib.kasuga.slp.javet`（`modId`: `kasuga_lib_javet`） | 可选 Javet `5.0.1` V8 JavaScript 脚本引擎支持模组 |
| `:external:uip:ldlib` | `lib.kasuga.uip.ldlib`（`modId`: `kasuga_lib_ldlib2`） | 可选 LDLib2 `2.2.44` 世界空间 UI 提供方 |
| `:external:uip:ldlib-compose` | `lib.kasuga.uip.compose`（`modId`: `kasuga_lib_ldlib_compose`） | 可选 LDLib2 Compose `21.0.0.1:all`（Kotlin `2.1.20`、NeoForge `21.1.238`） |
| `:external:uip:modernui` | `lib.kasuga.uip.modernui`（`modId`: `kasuga_lib_modernui`） | 可选 ModernUI `eMf1VSQd` 模型预览演示接入 |

> `:modules:old` 为旧版 UI 原型归档目录，未作为功能模块纳入当前开发。

## Gradle 下游接入配置

### Composite Build 接入（推荐）

在下游模组的 `settings.gradle` 中引入本地源码树与 Gradle 插件构建（参照同工作空间 `KuaYue/settings.gradle`）：

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

在模组的 `build.gradle` 中请求 Shadowed 变体（`Bundling.SHADOWED`），使复合构建解析到 `kasuga-library-*-all.jar` 而非未打包的瘦 JAR：

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

若模组自身还使用 `ModApplicationContext`、`@Context`、`@Configurable` 或 `@BeanOnlyIn`，再补充 Micronaut 注解处理器与 `lib.kasuga:inject`：

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

## 第三方 Maven 仓库与版本

构建与运行完整 `KasugaLibNext`（及其 `:modules:library` / `:modules:create` / `:external:*`）时用到的 Maven 仓库与版本如下：

| 仓库 URL | 用途与版本 | 声明位置 |
| --- | --- | --- |
| `mavenCentral()` / `gradlePluginPortal()` | Micronaut `4.9.10`、LWJGL `3.3.3`、JOML `1.10.5`、DataFixerUpper `8.0.16`、Gson `2.10.1`、Guava `32.1.2-jre`、jgltf `2.0.4`、ZXing `3.5.3`、Javet `5.0.1`、GraalVM `24.1.2` 等 | 各模块 `build.gradle` |
| `https://maven.neoforged.net/releases` | NeoForge `21.1.203`、`testframework`、`render-core` / `render-opengl` | `modules/render-core/build.gradle`、ModDevGradle |
| `https://libraries.minecraft.net` | Minecraft 共享库 | `modules/render-core/build.gradle` |
| `https://maven.createmod.net` | Create `6.0.6-98`（`slim`）、Ponder `1.0.59`、Flywheel `1.0.4` | `modules/library/build.gradle`、`modules/create/build.gradle` |
| `https://maven.ithundxr.dev/snapshots` | Registrate `MC1.21-1.3.0+62` | `modules/library/build.gradle`、`modules/create/build.gradle` |
| `https://maven.squiddev.cc`（限定组 `cc.tweaked`） | CC:Tweaked `cc-tweaked-1.21.1-forge:1.116.1` | `modules/library/build.gradle`、`modules/create/build.gradle` |
| `https://maven.ryanhcode.dev/releases`（限定组 `dev.ryanhcode.sable-companion`） | Sable Companion `sable-companion-common-1.21.1:1.6.0`（`compileOnly`） | `modules/create/build.gradle` |
| `https://maven.firstdark.dev/snapshots` | LDLib2 `2.2.44`、`ldlib2-compose-1.21.1:21.0.0.1:all` | `external/uip/ldlib*/build.gradle` |
| `https://api.modrinth.com/maven` | ModernUI `maven.modrinth:modern-ui:eMf1VSQd` | `external/uip/modernui/build.gradle` |
| 本地 `flatDir`（`modules/modelling/libs`、`modules/iris-compat/libs`） | 开发期可选编译/本地运行：`sodium:neoforge-mc1.21.1-0.6.13`、`iris:neoforge-mc1.21.1-1.8.12`（不打入发布 JAR） | `modules/modelling/build.gradle`、`modules/iris-compat/build.gradle` |

## 必需与可选运行时依赖对照

| 组件 / 模组 | 状态 | 机制与说明 |
| --- | --- | --- |
| Minecraft `1.21.1` + NeoForge `[21.1.203,)` | 必需 | 由 `neoforge.mods.toml` 强制声明 |
| Create `6.0.6-98` + Ponder + Flywheel + Registrate | 聚合 JAR 需提供 | 虽然 DI Bean 使用 `@CreateModBeans`（`IfCreateModLoaded`）条件启用，但 `:modules:library` 的 `neoforge.mods.toml` 无条件声明了 `kasuga_lib_create.mixins.json`（`required: true`），且 `CreateMixinPlugin` 会读取 `BezierConnection.class`，缺 Create 时客户端加载会抛出 `IllegalStateException` |
| `kasuga_lib_javet`（`external/slp/javet`） | 可选 | 提供 JavaScript（Javet `5.0.1`）脚本引擎；仅在加载声明 `javascript` 的脚本包时必需。首次运行会由 `JavetDownloader` 拉取平台 V8 原生库至 `<gameDir>/native-libraries/javet/` |
| Iris `1.8.12` + Sodium `0.6.13` | 可选（仅客户端） | `kasuga_lib_iris.mixins.json` 设为 `required: false`，`IrisCameraMixinPlugin` 与 `IrisCompat` 在启动时做 ASM ABI 探测，不兼容或缺失时自动回退 |
| Voxy / Sable | 可选 | `VoxyCompat` 与 `Create` Sable 适配器均按 `ModList` / 类存在性探测启用 |
| Box3D 原生库（`kasuga_box3d`） | 可选原生加速 | 随平台 `native/<os>-<arch>/` 打包进 JAR；构建机缺少 `cmake` 或目标平台缺失对应原生库时，`NativeBox3D.availableOrWarn()` 仅禁用布娃娃/刚体物理而不影响其余功能 |
| `org.jgrapht:jgrapht-core:1.5.2` | 若用到图模块需自备 | `:modules:core` 将其列入 `shadowLibrary`，但 `:modules:library` 的 `shadowLibrary` 未重新声明该库（当前 `kasuga-library-*-all.jar` 不含 `org/jgrapht`）；若下游模组使用 `lib.kasuga.content.graph.*` 需显式引入（如 `KuaYue/modules/core/build.gradle` 的做法） |

## 下一步

- 搭建第一个可运行的模组：阅读 [最小示例工程](minimal-example.md)
- 构建产出 JAR 或部署到客户端 / 服务端 / 整合包：阅读 [安装与部署](installation.md)
- 查看各系统生命周期与侧别边界：阅读 [生命周期与初始化顺序](../reference/lifecycle.md) 与 [侧边模型](../reference/sidedness.md)
