# 更新日志与版本策略

本页依据 `gradle.properties`、`neoforge.mods.toml` 模板与主仓 `master` 分支 Git 历史，记录 `KasugaLibNext` 当前的版本号策略与已落地的主要开发里程碑。

## 版本策略

- **当前版本号**：`gradle.properties` 将 `mod_version` 声明为 `1.0.0`，下游依赖范围 `kasuga_lib_version_range` 为 `[1.0.0,)`，并面向 `Minecraft 1.21.1` 与 `NeoForge [21.1.268,)`（`minecraft_version_range=[1.21.1]`）。
- **分支与发布形态**：
  - 仓库工作区约定直接在 `master` 分支推进，不另建长期功能分支（见 [贡献者文档](contributing.md)）。
  - 截至当前 `HEAD`（`293ac7b5`），仓库**尚未打过任何 Git release tag**（`git tag -l` 为空），也未配置向远程 Maven 仓库或 CurseForge/Modrinth 自动发布的 CI 工作流；除早期的 `v0.1 commit`（`7ec47eb8`）外，各子模块主要通过工作区 `includeBuild` 或本地 `maven-publish`（`file://${project.projectDir}/repo`）供下游使用。
  - 因此，下文按真实 Git 提交与合并 PR 的时间线记录演进里程碑，不虚构未发布过的版本编号。
- **兼容性演进护栏**：
  - **Box3D 原生上游钉死**：`modules/modelling/src/main/native/BOX3D_VERSION` 与 `BOX3D_SOURCES.json` 锁定上游提交，构建时强制校验文件集合与 SHA-256。
  - **PBR 磁盘缓存分代**：调整 PMX → PBR 烘焙通道语义或算法时提升 `PbrBakeProfile.VERSION`，使旧磁盘缓存自动失效重建。
  - **Iris / Sodium 运行期探测**：`modules/iris-compat` 通过 `IrisCameraMixinPlugin` 与 `CompatCapability` 进行字节码与反射探测，在不支持的渲染器版本上自动降级。

## 主要开发里程碑（源自 Git 历史）

### 2025-09 — 2025-12：仓库初始化与 DI / 代码生成基座

- `64191bfd`（2025-09-12）：初始化仓库与基础模组工程骨架。
- `8772165a`（2025-10-06）：拆分 `modules/inject` 与 `ModApplicationContext.init()`，建立基于 Micronaut 的依赖注入引导流程。
- `a0a56d4a`（2025-10-17）：落地 RPC、网络 payload 与 `modules/generator` 代码生成。
- `7ec47eb8`（2025-12-06）：早期里程碑 `v0.1 commit`。

### 2026-02 — 2026-04：DI 更新与模型加载器第一阶段

- `1755d239`、`dc0e4be9`（2026-03-11）：依赖注入框架重构更新。
- `cb9589cd` — `bbddd717`（2026-03-23 — 2026-03-28）：落地模型加载器初版、PBR 纹理组 `emissiveMap`、PoseStack 着色器迁移以及初期 Iris / LabPBR 适配。
- `8e1273bb` — `d7b2e384`（2026-04-02 — 2026-04-25，PR #1）：完成模型资源扫描器、`ObjLoader`、`MaterialSetInstance`、`.mmd.zip` / PMD / PMX 支持、JE 模型加载与 MMD 渲染热点优化。

### 2026-08 — 2026-10：状态机、渲染内核拆分、数据驱动初版与 UI 基础设施

- `aa95bd25`（2026-08-13，PR #34）：合入分层动画状态机（FSM）。
- `91b72894`（2026-09-28，PR #52）：合入 `data-driven` 注册与重载体系统初期架构。
- `1c7f77ff`（2026-10-03）：拆分与图形 API 无关的 `:modules:render-core` 和 OpenGL 后端 `:modules:render-opengl`。
- `0b53a98b`（2026-10-19，PR #63）：重组 `doc/` 为中文主文档 + `.en.md` 英文双语结构，对接 MkDocs `static-i18n` 站点发布。
- `bc12c7c6`（2026-10-22，PR #64）与 `d1bea6ec`（2026-10-25，PR #68）：合入模型管线扩展与世界空间 UI / LDLib2 / ModernUI 基础设施迭代。
- `08456753`、`087dbe88`、`65bc7f27`（2026-10-20 — 2026-11-10）：合入 Create 集成（`modules/create`）并持续与主线同步。

### 2026-11：Nebula 与 Shader DSL、VFS 与资源平台、数据驱动 v2、世界光源与天气遮挡

- **方块状态属性、Nebula 与渲染管线整合**（11 月上中旬）：
  - `72f3247f`（PR #70，2026-11-07）：数据驱动方块状态属性扩展。
  - `f5df8ccd`（2026-11-09）：合并 Nebula 演示管线（`597db1b8` / PR #76）、多层天空体积云与 `RenderShaderHandle` / Java Shader DSL 等共享渲染接口。
- **VFS 与统一资源平台**（2026-11-13）：
  - `67ceefe3`、`03043138`、`cefdf7e7`（PR #86）：实现可写事务型虚拟文件系统（`VirtualFileSystem`、`MountTableVfs`、`VfsPackResourcesBridge`）。
  - `24068ae8`（PR #87）：引入作用域资源平台（`ResourceSystem`、`ResourceSnapshot`）与异步脚本票据（`ScriptTicket`）。
- **模型变换、尺寸与深度偏移**（2026-11-14 — 2026-11-16）：
  - `d22a2a55`（PR #88）：新增仅变换虚拟模型组（dummy model groups）。
  - `99109778`（PR #89）：暴露方块模型边界尺寸接口（`BlockDimensions`）。
  - `ff9052c3`（PR #91）：为共面覆盖模型加入作用域深度偏移（`DepthBias`）。
- **数据驱动 v2 严格管线**（2026-11-16 — 2026-11-24）：
  - `2a8a57ba`、`3146f5e7`：完成 `STRICT` 多来源构建管线（`DataDrivenBuildPipeline`）并兼容旧资源重载。
  - `d767c83b`（PR #94 `a094e925`）：在 `ValidationContext` 中暴露按类型域检索接口 `findAll(String)`。
  - `6112c2f0`（PR #96 `98d5d482`）：支持 `HandlerCapability.OVERLAY` 覆盖定义选择。
- **世界空间光源、自动实例化、通用 Proxy 与碰撞**（2026-11-17 — 2026-11-24）：
  - `58759758`、`ab66967b`、`252f35c9`（PR #92）：新增可移动点/面/体积世界空间光源与跟随骨骼姿态的 Blockbench 光源 Locator 导入。
  - `a71bd21c`（PR #93）：支持兼容世界模型的自动硬件实例化（`GlobalModelBatcher`）。
  - `c27d7e0a`、`4379b89e`、`2712adc4`（PR #95 `fd49ba31`）、`6f964ab1`、`378e6e5e`、`c8c22e57`、`33145724`：新增通用 `proxy_model` / `proxy_model_be` 方块、编译模型组与作用域播放、动画模型服务端碰撞与 512 格内 Contraption LOD、可选原版方块光照（`ModelLightingMode.BLOCK`）、Create 结构内 Proxy 渲染/交互、物品 GUI 独立缩放，以及车厢垂直姿态与转向架轨道贴合。
- **天气遮挡、全局 Lombok、Iris OIT 修复与模块边界整理**（2026-11-25 — 2026-11-26）：
  - `050cf87d`、`23fab04b`、`6d027cf1`、`a2544ab2`（PR #97）：全局启用 Lombok `1.18.42`；新增 3D 室内天气遮挡（`WeatherFilters`、`WeatherFilterVolume`）与 Create / Sable 挡雨过滤。
  - `1c98f0f0`、`a382c5c1`（PR #98）：修复 Iris `1.8.12` 与 `1.8.14-beta.1` 的加权 OIT 并在不兼容时优雅降级为排序透明。
  - `c39864f4`、`5fe807bb`、`293ac7b5`（当前 `HEAD`）：将 Iris 兼容层隔离至 `:modules:iris-compat`；整理 modelling / OpenGL / VFS 内部实现；将历史建模 UI 原型移至 opt-in 模块 `:modules:old`。
