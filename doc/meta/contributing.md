# 贡献者文档

本页记录 `KasugaLibNext` 的分支约定、代码风格、模块隔离规则、常用构建与验证任务、CI 流水线以及文档协作规范。

## 分支与 PR 规范

根据工作区约定（`AGENTS.md`）：

- 全部仓库直接在 `master` 分支上开发，**不另建功能分支**。
- 创建 Pull Request 时，统一以 `master` 作为源分支。

## 代码与架构约定

- **优先复用基础设施**：优先使用 KasugaLib 已有能力；能力不足时扩展通用 API，避免在具体功能或测试适配层里重复实现基础设施。
- **GLSL 独立资源化**：GLSL shader 源码必须放在独立资源文件（如 `src/main/resources/assets/.../shaders/`）并通过资源系统加载，或通过 `builder.kasuga.shader` / Java Shader DSL 生成；不要把 GLSL 源码硬编码在 Java、Kotlin 或其他程序字符串里。
- **控制流与类型可读性**：
  - 优先使用清晰的条件判断与提前返回（early return），避免深层嵌套。
  - 少用 `var`，优先显式声明变量类型，保持数据流与所有权清晰。
  - 保持代码简洁；直接循环比 Stream 更清楚时使用循环。
  - 明确优于隐晦，避免依赖隐式行为、晦涩缩写与过度抽象。
- **模块依赖边界**：
  - `modules/render-core` 不得引入 Minecraft、NeoForge、Fabric、LWJGL 或具体图形 API 类，也不得让公共子系统反向依赖 `uml.typo` 具体模型格式；该约束在每次 `compileJava` 与 `check` 时由 `:modules:render-core:checkGraphicsApiIsolation` 与 `:modules:render-core:checkModelFormatIsolation` 自动检查。
  - `modules/modelling/src/main/native/box3d` 必须与上游固定提交保持字节一致，禁止直接补丁或改写上游目录；所有局部适配必须写在 `kasuga_box3d_jni.c`、`CMakeLists.txt` 或 Java 适配器中。`:modules:modelling:verifyBox3DUpstream` 会在 `configureBox3D` 与 `check` 前校验 `BOX3D_VERSION` 和 `BOX3D_SOURCES.json` 中的 113 个文件 SHA-256。
  - 根 `build.gradle` 统一将每个子项目 `archivesName` 设为 `"kasuga-${project.name}"`，不要在子模块里把 JAR 名称改回 `kasuga_lib`，以免 ModDev 运行时模块层因同名 JAR 合并导致类加载故障。

## 本地构建与验证命令

所有命令在 `KasugaLibNext` 根目录运行（Linux/macOS 使用 `./gradlew`，Windows 使用 `.\gradlew.bat`），Java 工具链固定为 **Java 21**，Gradle Wrapper 为 **8.14.3**：

| 场景 | 命令 | 说明 |
| --- | --- | --- |
| 完整编译与发布检查 | `./gradlew build renderingReleaseCheck` | 包含 `gradle-plugin` 测试、shader / shader-graal / render-core / render-opengl / modelling / iris-compat / library 的组装与纯 JVM 检查 |
| Modelling 纯 JVM 单测 | `./gradlew :modules:modelling:modelUnitTest` | 绕开 NeoForge dedicated-server 启动器；若 `PATH` 存在 `cmake`，会先编译并测试 `kasuga_box3d`，否则自动排除 `box3d` 标签 |
| 其他核心模块纯 JVM 单测 | `./gradlew :modules:formula:test :modules:shader:test :modules:shader-graal:check :modules:scripting:scriptUnitTest` | 不需要启动 Minecraft 或图形界面 |
| 数据驱动与 Core 测试 | `./gradlew :modules:data-driven:test :modules:core:test` | 运行数据驱动解析、v2 构建管线与 FML JUnit 门控测试 |
| 独立 OpenGL 回归 | `./gradlew :modules:modelling:renderGlTest` | 启动独立 LWJGL/OpenGL 上下文运行渲染核心测试；另有 `renderPreview`、`renderBench` |
| 本地 Box3D 原生编译 | `./gradlew :modules:modelling:buildBox3D` | 需要 `cmake` 在 `PATH` 中；产物输出到 `modules/modelling/build/native/box3d/resources/native/<os>-<arch>/` |
| 历史 UI 原型浏览 | `./gradlew -PkasugaIncludeOld=true ...` | 默认构建跳过 `modules/old`；该模块仅使用 Gradle `base` 插件保留源码，不参与编译 |

> 更多客户端与 headless 冒烟参数、`report.json` 验收标准以及证据边界规则，见 [构建与验证入口](../guides/verification.md)。

## CI 流水线与发布机制

仓库 `.github/workflows/` 中包含两套主要工作流（修改 `**.md`、`doc/**`、`.gitignore`、`LICENSE.txt` 时自动跳过）：

1. **`build.yml`（Build）**：
   - `ubuntu-latest`：执行 `./gradlew build renderingReleaseCheck`，上传 `kasuga-modelling` 基础 JAR 与 Linux Box3D 原生库。
   - `windows-latest` 与 `macos-latest`：分别执行 `:modules:modelling:buildBox3D`，上传对应平台的 `kasuga_box3d` 原生库。
   - `package-universal`：在 Ubuntu 上将三平台 `native/<os>-<arch>/` 动态库合并进同一个 `kasuga-modelling-universal.jar`，并校验 JAR 内至少存在 3 个平台的原生库文件。
2. **`render-harness.yml`（Standalone rendering）**：
   - `ubuntu-latest`（`mesa-regression`）：安装 `xvfb` 与 Mesa 软件 OpenGL（`LIBGL_ALWAYS_SOFTWARE=1`），运行 `xvfb-run -a ./gradlew :modules:modelling:renderGlTest --console=plain` 并上传测试报告与失败截图。
   - `windows-latest` 与 `macos-latest`（`portable-harness-build`）：验证 `renderHarnessClasses`、`jmhClasses` 编译与 `modelUnitTest` 通过。

各主要子模块配置了 `maven-publish`（`mavenJava` publication），默认仓库指向各模块本地目录 `file://${project.projectDir}/repo`；当前仓库未启用自动向远程 Maven 发布的 CI 工作流。

## 文档贡献与站点构建

文档站点由独立部署仓库 `KasugaLibGroup/KasugaLib-Documentation` 托管，所有正文源码均位于主仓库 `KasugaLibNext` 的 `doc/` 下：

- **双语文件布局**：中文页为 `page.md`，英文页为同目录下的 `page.en.md`。
- **链接写法**：跨页链接**禁止带 `.en` 后缀**（例如写 `[已知限制](limitations.md)` 而非 `limitations.en.md`）；i18n 插件会自动按当前语言路由。同时注意避免从 `doc/` 内使用 `../../` 链出仓库根目录，因为在站点部署仓库中 `docs_dir` 只拉取 `doc/`。
- **术语一致性**：新增或修订双语文档时，严格遵循 [中英术语对照](glossary.md) 的译法与不译白名单。
- **导航与部署同步**：`KasugaLib-Documentation` 的 `mkdocs.yml` 手工维护 `nav` 与英文 `nav_translations`，其标签与各页真实一级标题（H1）逐字一致。增删或重命名页面后，需要同步更新 `mkdocs.yml`；`deploy-docs.yml` 约每 6 小时（或手动 `workflow_dispatch`）拉取 `KasugaLibNext@master` 的 `doc/`，在检测到 `doc/` 改动后执行 `mkdocs build`（刻意不开启 `--strict`）并推送至 `gh-pages`。
