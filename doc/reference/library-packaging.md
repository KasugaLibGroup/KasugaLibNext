# 库打包、阴影重定位与多版本结构参考

本页记录 KasugaLibNext 根构建与发行子模块（`:modules:library`、`:modules:annotations`、`:modules:multiversion` 及各 `GAMELIBRARY` 子模块）的模块边界、Shadow 打包策略、`neoforge.mods.toml` 元数据装配与依赖版本基准。

> 相关插件细节见 [Gradle 插件、代码生成与资源编译参考](gradle-and-codegen.md)；JavaScript 脚本配套 mod `:external:slp:javet` 的加载与原生库下载机制见 [Scripting 与 Javet 运行时参考](scripting.md)。

## Monorepo 全局构建约定

根 `settings.gradle` 包含 `includeBuild './modules/gradle-plugin'` 与 23 个 `include` 子工程（22 个无条件引入，`:modules:old` 仅在 `-PkasugaIncludeOld=true` 时引入）。根 `build.gradle` 对全部子工程统一执行三项约束：

1. **Java 21 工具链**：`java.toolchain.languageVersion = JavaLanguageVersion.of(21)`（`options.encoding = 'UTF-8'` 由各子项目在各自的 `JavaCompile` / `Javadoc` 任务中单独配置）。
2. **唯一归档名（防 JPMS 模块名碰撞）**：对所有应用 `base` 插件的子工程设置 `archivesName = "kasuga-${project.name}"`。ModDevGradle 会按 JAR 文件名推导 JPMS 运行期模块名，因此不能使用共享的 `mod_id`（`kasuga_lib`）作为各子项目 JAR 名。
3. **全局 Lombok 注入**：为每个 `SourceSet` 自动向 `compileOnly` 与 `annotationProcessor` 加入 `org.projectlombok:lombok:${lombok_version}`（`gradle.properties` 中为 `1.18.42`）。

### 基准版本参数（`gradle.properties`）

| 属性 | 当前值 | 说明 |
| --- | --- | --- |
| `minecraft_version` / `minecraft_version_range` | `1.21.1` / `[1.21.1]` | 目标 Minecraft 版本 |
| `neo_version` / `loader_version_range` | `21.1.203` / `[1,)` | NeoForge 与 FML loader 范围（ModDevGradle `2.0.107`） |
| `parchment_minecraft_version` / `parchment_mappings_version` | `1.21.1` / `2024.11.17` | Parchment 参数映射 |
| `mod_id` / `mod_name` / `mod_version` / `mod_group_id` | `kasuga_lib` / `KasugaLib Next` / `1.0.0` / `lib.kasuga` | 共享 mod 元数据与 Maven 坐标组 |
| `create_version` / `ponder_version` / `flywheel_version` / `registrate_version` | `6.0.6-98` / `1.0.59` / `1.0.4` / `MC1.21-1.3.0+62` | Create 生态兼容基准 |
| `cct_version` | `1.116.1` | CC:Tweaked 对接版本 |

## 子模块打包分类

| 分类 | 子模块 | 打包机制 / Manifest 属性 | 消费方式与 JPMS 隔离原因 |
| --- | --- | --- | --- |
| 主发行聚合 mod | `:modules:library` | `useModProject` 合并 8 个 mod 源集 + `shadowJar`（`classifier = 'all'`） | 最终单 JAR 分发包，统一承载 `kasuga_lib` mod 层 |
| 纯 JVM 独立库 | `:modules:shader`、`:modules:shader-graal`、`:modules:render-core`、`:modules:render-opengl` | 普通 `java-library` JAR（`render-core` 打入 `caliko-1.3.8.jar` 并运行 `checkGraphicsApiIsolation` / `checkModelFormatIsolation`） | 无 Minecraft/LWJGL 绑定（`render-opengl` 含 LWJGL/OpenGL）；通过 `useModProject` 或 `api` 进入 mod 层 |
| NeoForge `GAMELIBRARY` | `:modules:utils`、`:modules:inject`、`:modules:formula`、`:modules:data-driven`、`:modules:annotations` | JAR manifest 写入 `FMLModType: "GAMELIBRARY"` | 被下游 mod 层使用时通常由 `shadowLibrary` 嵌入或经 `useModProject(..., false)` 并入类输出，避免同一包名同时出现在 `GAMELIBRARY` 模块和 `kasuga_lib` mod 层中导致 JPMS split-package 失败 |
| 可选独立配套 mod | `:external:slp:javet` | 独立 mod ID `kasuga_lib_javet`；`runtimeOnly` 消费 `:modules:library` 的 `Bundling.SHADOWED` 产物 | 运行期与 `kasuga_lib` 保持单模块边界，自带 Javet 5.0.1 与 `httpclient:4.5.13` |
| 内部工具与构建期模块 | `:modules:gradle-plugin`、`:modules:generator`、`:modules:resource-compiler` | 仅构建期 / JavaExec 工具 | 不进入运行期模组包 |
| 多版本适配层 | `:modules:multiversion` | `neoforge` + `shadowJar`（`zip64 = true`）骨架模块 | 详见下文 |

## `:modules:library` 聚合与 Shadow 重定位

`modules/library/build.gradle` 应用 `java-library`、`maven-publish`、`net.neoforged.moddev`（`2.0.107`）、`com.gradleup.shadow`（`9.0.2`）、`builder.kasuga.generator` 与 `builder.kasuga.monorepo`，并配置 `assemble.dependsOn shadowJar`。

### 合并的 Mod 源集（`useModProject`）

`modules/library/build.gradle` 按顺序合并以下 8 个子项目的 `main` 类与资源（同时排除各自的 `META-INF/neoforge.mods.toml` 与 `META-INF/mods.toml`），并汇总其 `contentTesting` 源集至 `neoForge.mods.kasuga_lib`：

1. `:modules:scripting`
2. `:modules:modelling`
3. `:modules:iris-compat`
4. `:modules:render-core`
5. `:modules:render-opengl`
6. `:modules:core`
7. `:modules:create`
8. `:modules:data-driven`

`sourcesJar` 设为 `DuplicatesStrategy.EXCLUDE`，并显式依赖 `:modules:core`、`:modules:scripting`、`:modules:modelling`、`:modules:create` 的 `generateModMetadata` 任务。

### `shadowLibrary` 嵌入与包重定位

`shadowJar` 的 `archiveClassifier` 固定为 `'all'`（即产出 `kasuga-library-1.0.0-all.jar`），使用 `configurations = [project.configurations.shadowLibrary]` 与 `from(sourceSets.main.output)`（并由 `useModProject` 自动追加上述 8 个子项目编译输出）。

`shadowLibrary` 显式打入以下非原版/非公共模块依赖：

- 项目依赖：`:modules:inject`、`:modules:utils`、`:modules:shader`、`:modules:formula`
- 第三方依赖：
  - `de.javagl:jgltf-model:2.0.4` 与 `de.javagl:jgltf-impl-v2:2.0.4`
  - `modules/render-core/libs/caliko-1.3.8.jar`（`processResources` 同时将 `caliko-LICENSE.txt` 复制到 `META-INF/licenses/`）
  - `com.google.zxing:core:3.5.3`

`shadowJar` 配置：

- `mergeServiceFiles()`：合并 `META-INF/services/`。
- `transform(MicronautServiceShadowTransformer) {}`：按 `relocate` 规则重写 `META-INF/micronaut/**` 路径。
- 重定位映射（防止与玩家环境或其它模组的 Micronaut / Jakarta 版本冲突）：
  - `io.micronaut` -> `lib.kasuga.vendors.io.micronaut`
  - `jakarta.inject` -> `lib.kasuga.vendors.jakarta.inject`
  - `jakarta.annotation` -> `lib.kasuga.vendors.jakarta.annotation`
  - `lib.kasuga.inject` -> `lib.kasuga.vendors.inject`
- 排除签名文件：`META-INF/*.SF`、`META-INF/*.DSA`、`META-INF/*.RSA`。

开发与运行期测试类路径还通过 `implementation` / `additionalRuntimeClasspath` / `compileOnly` 引入 Micronaut `4.9.10`（排除 `org.slf4j:slf4j-api`）、Create `6.0.6-98:slim`（`transitive = false`）、Ponder `1.0.59`、Flywheel API/Runtime `1.0.4`、Registrate `MC1.21-1.3.0+62` 与 CC:Tweaked `1.116.1`。

### `neoforge.mods.toml` 与 Mixin 聚合

`modules/library/src/main/templates/META-INF/neoforge.mods.toml` 由 `generateModMetadata` 任务展开 `gradle.properties` 属性并输出至 `build/generated/sources/modMetadata/META-INF/neoforge.mods.toml`（同时通过 `neoForge.ideSyncTask generateModMetadata` 挂载 IDE 同步）：

- **加载器与模组信息**：`modLoader = "javafml"`、`loaderVersion = "${loader_version_range}"`、`license = "${mod_license}"`、`modId = "${mod_id}"`、`version = "${mod_version}"`、`displayName = "${mod_name}"`。
- **声明的 Mixin 配置**：
  - `kasuga_lib.mixins.json`（来自 `:modules:core`，声明核心公共与客户端 mixin，`required: true`，插件 `lib.kasuga.mixins.KasugaLibMixinPlugin`）
  - `kasuga_lib_iris.mixins.json`（来自 `:modules:iris-compat`，`required: false`，插件 `lib.kasuga.compat.iris.IrisCameraMixinPlugin` 按 Iris/Sodium ABI 门控）
  - `kasuga_lib_create.mixins.json`（来自 `:modules:create`，`required: true`，插件 `lib.kasuga.create.compat.CreateMixinPlugin`）
- **必需依赖**：`neoforge`（`[${neo_version},)`，`side="BOTH"`）与 `minecraft`（`${minecraft_version_range}`，`side="BOTH"`）。

测试验证：`modules/library/src/test/java/lib/kasuga/library/LibraryModMetadataTest.java` 在单测中校验生成后的 `META-INF/neoforge.mods.toml` 包含 `kasuga_lib.mixins.json` 与 `kasuga_lib_create.mixins.json`，且 `kasuga_lib_create.mixins.json` 声明 `client.BezierConnectionMixin`。`src/contentTesting/java/test/kasuga/TestKasugaLibApplication.java` 提供用于测试环境的 `@Context` 入口，向 `modEventBus` 注册 `KasugaLibRegistry.getRegistryOf(KasugaLib.MODID)`。

## `:modules:annotations`

`modules/annotations/build.gradle` 配置了 Java 21 工具链、NeoForge ModDev `2.0.107`（启用 `unitTest`）与 JUnit 5，并在 `jar.manifest` 中设置 `'FMLModType': "GAMELIBRARY"`（输出 `kasuga-annotations-1.0.0.jar`）。

当前仓库中的 `:modules:annotations` **尚未包含 Java 源文件**，作为保留的 `GAMELIBRARY` 占位坐标被 `:modules:modelling`（`implementation`）与 `:external:slp:javet`（`compileOnly`）引用；实际用于代码生成的注解位于 `:modules:generator`（`@CodeTemplate`、`@RegGenerator`、`@Prototype`），脚本安全注解 `@Api` 位于 `:modules:scripting`，条件 Mixin / 注册生成注解位于 `:modules:utils`。

## `:modules:multiversion`

`modules/multiversion/` 是用于跨版本/兼容构建装配的独立 mod 工程骨架：

- `modules/multiversion/build.gradle` 应用 `java-library`、`maven-publish`、`net.neoforged.moddev`（`2.0.107`）与 `com.gradleup.shadow`（`9.0.2`，开启 `shadowJar { zip64 = true }`）。
- `neoForge.mods."${mod_id}"` 将 `:modules:core` 的 `sourceSets.main` 与本地 `sourceSets.main` 绑定在同一 mod 中；依赖 `:modules:inject`、Micronaut `4.9.10`、Create `6.0.6-98:slim`、Ponder `1.0.59`、Flywheel `1.0.4` 与 Registrate `MC1.21-1.3.0+62`。
- 当前 `HEAD` 下该模块仅保留 `build.gradle` 与 `src/main/templates/META-INF/neoforge.mods.toml`（基础模组元数据模板，未声明 `[[mixins]]` 段），无已跟踪的 `src/main/java` 源文件。

## 验证命令

```sh
# 校验 :modules:library 元数据测试与全套发布组装（包含 gradle-plugin、shader、render、modelling、library:assemble）
./gradlew :modules:library:test
./gradlew renderingReleaseCheck

# 单独生成库聚合 shadowJar（*-all.jar）
./gradlew :modules:library:shadowJar
```
