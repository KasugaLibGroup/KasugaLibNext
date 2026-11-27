# 迁移指南

本页整理 `KasugaLibNext` 在数据驱动注册、模块化打包、渲染后端与物理/模型子系统演进过程中涉及的破坏性变更与对应迁移路径。

## 1. 数据驱动：从 v1 (`JsonTreeBuilder`) 迁移到 v2 (`DataDrivenBuildPipeline`)

早期单文件平铺布局与旧注册入口已被 v2 多来源严格管线取代（细节与逐条排查见 [排查与迁移](../explanation/data-driven/08-troubleshooting-migration.md) 与 [数据驱动注册专题](../reference/data-driven/README.md)）：

| 变更点 | 旧写法（已废弃 / 兼容受限） | 现行写法（v2） |
| --- | --- | --- |
| 索引清单路径 | `data/<modid>/kasugalib/*.json`（旧目录当前不再读取） | `data/<namespace>/kasuga_lib/data_driven/<name>.json` |
| 清单顶层字段 | `"sources": [...]` | `"on_register": [...]`（注册期）与 `"on_reload": [...]`（reload 期），路径相对 `data/<namespace>/` 且互不重叠 |
| 重载文件发现 | 依赖单一路径清单或隐式扫描 | `state_machines/` 支持目录 glob 发现；`animation_clips/` 必须在 `"on_reload"` 显式列出 |
| 注册期宿主接入 | 默认单一来源 `JsonTreeBuilder` 自动扫描 | 通过 `DataDrivenRegistrationProviders.install(modId, ...)` 接管宿主，使用 `DataDrivenBuildPipeline`（推荐 `BuildOptions.STRICT`） |
| 内容身份标识 | 裸 `id` 或 `minecraft:id` 自动改写为宿主命名空间 | `STRICT` 模式下要求显式 `"namespace:path"`（裸 `id` 会触发 `INVALID_IDENTITY`，`minecraft:` 会按字面原版命名空间处理） |
| Handler 与工厂安装 | 依赖静态隐式加载 | 自定义/复用管线在首次 prepare 前显式调用 `JsonTreeBuilder.ensureHandlersRegistered()` 与 `FsmBlockEntityFactories.registerBuiltin()`；自定义 `TypeHandler` 声明 `STRICT_PREFLIGHT` 及唯一角色（`REGISTRY_CLAIMS` 或 `NON_REGISTRATION`） |
| 冲突防线 | 仅依赖注册时报错 | 构建前用 `RegistryClaimSnapshot.collect(hostRoot)` 收集代码侧保留 key；按 `BuildResult` / `BuildDiagnostic` 阻断发布 |

> 如果只需要单一内置 JAR、无多包覆盖且不需要严格 claim 预检，`JsonTreeBuilder` 兼容入口仍可保留，但新索引路径与 `on_register` / `on_reload` 字段同样适用。

## 2. 模块结构、打包与依赖配置变更

近期 `master` 架构整理引入了以下工程与类加载边界调整：

- **统一生成唯一的子项目 JAR 名称**（根 `build.gradle`）：
  - 所有子项目统一设置 `base { archivesName = "kasuga-${project.name}" }`，移除了各子模块把 JAR 改名为 `kasuga_lib` 的配置，避免 ModDev 运行时 JPMS 模块层把多个同名 JAR 合并而导致 `NoClassDefFoundError`。
- **单包所有者约束（`kasuga-library` 与 `kasuga-utils`）**：
  - `modules/library` 的 `shadowJar`（`-all`）内嵌了 `modules/utils` 与重定位后的 `lib.kasuga.vendors.*`（Micronaut / Jakarta / `inject`）。
  - 外部模组同时依赖 `lib.kasuga:kasuga_lib`（`Bundling.SHADOWED`）与 `lib.kasuga:inject` 时，需要在 `runtimeClasspath*` / `additionalRuntimeClasspath` 中排除独立的 `lib.kasuga:utils`，否则 FML 启动阶段会因 `lib.kasuga.utils` 双重导出抛出 `ResolutionException`（参考 [使用方](users.md)）。
- **Iris 兼容层隔离到 `:modules:iris-compat`（提交 `c39864f4`）**：
  - 原先位于 `modules/modelling` 内的 Iris/Sodium Mixin（`WorldViewIris*`）、`IrisVertexBuffer`、`CameraIrisSession` 等已移入 `modules/iris-compat`（`kasuga_lib_iris.mixins.json`、`IrisCameraMixinPlugin`、`IrisRenderingIntegration`）；旧 `IrisConstants` 已在 `c39864f4` 中删除，并由 `IrisModelFormats`、`IrisRenderingIntegration` 与 `IrisWorldFramebuffer` 接管隔离适配。
  - `modelling` 核心仅保留 `IrisIntegration` 接口与 `CompatCapability` 探测桥接；发布 JAR 会打包 `iris-compat` 编译产物并继续排除 `libs:iris` / `libs:sodium`。
- **历史建模 UI 移出默认构建（提交 `293ac7b5`）**：
  - 原 `modules/modelling/old` 已整体迁移至 `:modules:old`，默认 `settings.gradle` 不再包含它；仅浏览历史原型源码时可通过 `-PkasugaIncludeOld=true` 临时挂入 Gradle。
- **全局 Lombok 启用（提交 `050cf87d`）**：
  - 根 `build.gradle` 为所有 Java 子项目统一注入 `org.projectlombok:lombok:1.18.42`，子模块无需各自重复声明。
- **渲染内核与 OpenGL 后端拆分**（[渲染后端接入参考](../reference/rendering-backends.md)）：
  - 图形 API 无关的 UML 模型结构、调度、`UploadRing` 与 `FrameOutputRouter` 位于 `modules/render-core`；OpenGL 实现（`GpuUploadRing`、`MacTboUploadDevice`、`FrameTexture`、`FramePreviewWindow`）位于 `modules/render-opengl`。接入新图形后端只依赖 `render-core`。

## 3. 模型、物理与坐标系统迁移

- **物理与 IK 统一入口**（[PMX 与 glTF 布娃娃配置](../reference/mmd-ragdoll.md)）：
  - 物理刚体、IK 与菱形闭环配置统一收拢到 `Skeleton.getDynamics()` 下。
  - Box3D C17（`kasuga_box3d`）替代旧的物理/流体实验路径成为唯一求解器：旧自研流体浮力模型已移除；`simulation.solver_iterations`、`max_angular_speed`、`limit_stiffness`、`sleeping.delay_seconds` 在 JSON 中仅保留读取兼容，不再生效；建议从旧欧拉盒限位 `rotation_min_degrees` / `rotation_max_degrees` 迁移至 `max_swing_degrees` + `min_twist_degrees` / `max_twist_degrees`。
- **大坐标浮动原点**：
  - 启用布娃娃或远距离场景显示时，不要把大世界坐标直接塞进单精度 `Transform` / `Matrix4f`；改用带 double `Vector3d worldOrigin` 的 `MinecraftRagdollDeployments.Request`、`*World(...)` 物理交互接口以及 `SceneModels.Entry.position(Vec3)`（[世界显示](../guides/world-display.md)）。
- **Proxy 方块与 Blockbench loader 边界**（[Modelling 模块](../reference/modelling/README.md)）：
  - 简单动画模型方块优先复用通用数据驱动工厂 `proxy_model` / `proxy_model_be` 与 `ProxyComposition` / `ScopedPlaybackCommandApplier`，减少手写方块与方块实体样板代码。
  - Blockbench `.bbmodel` 像素 UV 归一化与 Z→Y→X 内旋欧拉角逻辑限定在 `KsgBbModelLoader` 内部；切勿把像素 UV 归一化或 Bedrock 符号取反写进共享 `FlatModelData` 或其他格式路径。

## 4. 统一资源平台与 VFS 迁移

- 新增或重构跨注册、资源包与脚本的资产读取时，优先通过 `ResourceSystem`、`ResourceSources`、`ResourceSnapshot` 与 `VirtualFileSystem`（`VfsPath`、`MountTableVfs`、`VfsPackResourcesBridge`）传递不可变快照，避免直接读取零散文件路径而造成注册期与运行时包栈不一致（见 [统一资源平台](../reference/resource-platform.md) 与 [VFS 参考](../reference/vfs.md)）。
- 对接资源生命周期事件时，使用 `ResourceEvent.HOST_BOOTSTRAP`（序列化名 `host_bootstrap`），注意旧版部分资料中曾误写为 `bootstrap`。
