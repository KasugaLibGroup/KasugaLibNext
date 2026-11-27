# 侧边模型

本文档说明 KasugaLibNext 中物理侧（`Dist` / `@BeanOnlyIn`）、逻辑侧（`LogicalSide` / `Level.isClientSide`）、Mixin 侧别隔离、资源与脚本路由，以及各主要子系统的侧别归属。

---

## 1. 物理侧检测与 Micronaut 条件过滤

### 1.1 `Envs` 工具类

`modules/inject` 提供了两个同构的环境检测工具：

- `lib.kasuga.inject.class_loader.Envs`（对 `FMLLoader.getDist() == null` 安全，适合无 FML 的单元测试）：
  - `isDevEnvironment()`：`!FMLLoader.isProduction()`
  - `isClient()`：`dist != null && dist.isClient()`
  - `isDedicatedServer()`：`dist != null && dist.isDedicatedServer()`
- `lib.kasuga.inject.Envs`：直接调用 `FMLLoader.getDist().isClient()` 与 `isDedicatedServer()`（未初始化 FML 时不判空）。

### 1.2 `@BeanOnlyIn` 与 `MinecraftDist`

`lib.kasuga.inject.class_loader.BeanOnlyIn` 通过 Micronaut `@Requires(condition = ...)` 将 Bean 限定在特定运行环境：

| 注解 | 对应 `Condition`（`MinecraftDist`） | 激活条件 | 典型使用 |
| --- | --- | --- | --- |
| `@BeanOnlyIn.Client` | `MinecraftDist.IsClient` | `Envs.isClient()` | `KasugaLibClientApplication`、`ClientRenderingRegistry`、`DefaultClientRegisterContextDispatchers`、`WeatherFilterConfig`、`ScriptingClientApplication`、`ClientCreateRegistrationBridge`、`ClientBogeyRendererRegistry` |
| `@BeanOnlyIn.DedicatedServer` | `MinecraftDist.IsDedicatedServer` | `Envs.isDedicatedServer()` | `ScriptingServerApplication` |
| `@BeanOnlyIn.Development` | `MinecraftDist.IsDevelopment` | `Envs.isDevEnvironment()` | 仅开发期 Bean |
| `@BeanOnlyIn.Never` | `MinecraftDist.Never` | 恒为 `false` | 禁用 Bean |
| `@CreateModBeans`（`lib.kasuga.create`） | `IfCreateModLoaded` | `ModList.get() != null && ModList.get().isLoaded("create")` | `CreateApplication`、`CreateConfig`、`CreateRegistrationDispatcher`、`ClientCreateRegistrationBridge`、`ClientBogeyRendererRegistry` |

此外，`MinecraftDist.ModLoaded` 提供抽象 `Condition` 基类（通过 `ModList.get().isLoaded(getModId())` 判断可选模组依赖）。

#### 客户端替换通用 Bean 的典型模式

`DefaultRenderingRegistry`（`@Singleton`）在服务端提供空实现（记录 `DEBUG` 日志并忽略渲染器注册）；`ClientRenderingRegistry` 同时标注 `@Singleton`、`@Replaces(DefaultRenderingRegistry.class)` 与 `@BeanOnlyIn.Client`，因此在物理客户端会自动替换默认实现并真正注册方块实体、实体与文档组件渲染器。

---

## 2. 注册调度与 Mixin 的侧别边界

### 2.1 `RegisterContextRegistry.Side`

`lib.kasuga.registration.core.RegisterContextRegistry.Side` 定义两个注册侧：

- `COMMON`：由 `KasugaLibApplication` 触发 `registry.register(modEventBus)`，向所有物理侧派发 `NEW_REGISTRY`、`CreateRegistrationStages.PREPARE`、`REGISTER_EVENT`、`REGISTER_CAPABILITIES`、`PAYLOAD_REGISTRATION`、`CREATIVE_TAB_CONTENT_REGISTRATION`。
- `CLIENT`：由 `KasugaLibClientApplication`（`@BeanOnlyIn.Client`）触发 `registry.registerClient(modEventBus)`，仅在物理客户端派发 `MENU_SCREEN_BINDING`、`REGISTER_CLIENT_EXTENSIONS`、`BAKING_COMPLETE`。

对于 Create 集成，`CreateRegistrationDispatcher` 在 `COMMON` 侧执行，并从 `Registry` 附件读取 `CreateClientBridge`：物理客户端由 `ClientCreateRegistrationBridge` 在 `CLIENT` 侧写入真实实现（绑定 `PartialModel`、`TrackModel` 与 `BogeyStyle.SizeRenderer`），专用服务端回退至 `CreateClientBridge.noop()`。

### 2.2 Mixin 侧别与字节码 ABI 门控

| Mixin 配置 | 插件类 | 侧别与门控行为 |
| --- | --- | --- |
| `kasuga_lib.mixins.json` | `lib.kasuga.mixins.KasugaLibMixinPlugin`（继承 `ServiceMixinPlugin`） | 仅在检测到 `lib/kasuga/mixins/modelling/PeelShaderLoaderMixin.class` 时注入建模模块 mixin：`Side != CLIENT` 仅注入 3 个公共/服务端类（`CameraChunkServerMixin`、`CameraEntityDataAccessor`、`ModelCollisionLevelMixin`）；`Side == CLIENT` 注入完整客户端世界视角、无窗口与天气 mixin |
| `kasuga_lib_create.mixins.json` | `lib.kasuga.create.compat.CreateMixinPlugin` | `"mixins"` 列表在双端生效；`"client"` 列表仅在客户端生效（同时按 `BezierConnection.getBakedSegments` 字节码签名在 `BezierConnectionMixin` 与 `ModernBezierSegmentsMixin` / `ModernBezierGirdersMixin` 间二选一） |
| `kasuga_lib_iris.mixins.json`（`"required": false`） | `lib.kasuga.compat.iris.IrisCameraMixinPlugin` | 仅当 `MixinEnvironment.Side.CLIENT` 且 Iris + Sodium 所需类/字段/方法签名校验全部通过时返回客户端 Iris mixin（含可选 `WorldViewIrisCloudMixin`） |

---

## 3. 逻辑侧（`LogicalSide`）、状态监听侧与脚本入口侧

物理侧与逻辑侧（单人游戏内置服务端 vs. 客户端逻辑世界）在资源系统、方块状态监听与脚本包中存在重要差异：

### 3.1 `LogicalSide` 与 `ResourceRouting`

`lib.kasuga.core.resource.lifecycle.LogicalSide` 包含：`CLIENT`、`SERVER`、`BOTH`、`NONE`：

- `ResourceRouting.route(catalog, event, side)` **要求传入具体逻辑侧**（若传入 `LogicalSide.BOTH` 会直接抛出 `IllegalArgumentException`）。
- 路由输出约束：`CLIENT_STARTUP` 事件不能声明 `SERVER` 或 `BOTH`；`SERVER_STARTUP` 事件不能声明 `CLIENT` 或 `BOTH`。
- `ResourceCoordinator` 会把 `LogicalSide.NONE` 的输出绑定为中立执行上下文（`context.neutral()`），且不允许同一个 `ResourceEntryPoint` 在同一次调度的输入中混用 `NONE` 与游戏侧。
- `ManagedScriptEntryPoint` 与 `ScriptBlockRegistry` 同样拒绝 `LogicalSide.BOTH`，且 `ScriptBlockRegistry.bind(ref, context)` 要求 `context.side() == side && ref.side() == side`（客户端与服务端脚本块不可跨侧绑定）。

### 3.2 `ScriptPackage.start`（旧版物理侧） vs. `ScriptPackage.startManaged`（托管逻辑侧）

`package.toml` 支持 `[entry]` 下的 `server`、`client`、`common` 三组入口脚本，两种启动路径的过滤规则不同：

- **旧版 `ScriptPackage.start(ScriptThreadGroup)`**（由 `ScriptRuntime` 驱动）：使用**物理侧** `FMLEnvironment.dist` 过滤——`entry.server` 仅在 `Dist.DEDICATED_SERVER` 运行，`entry.client` 仅在 `Dist.CLIENT` 运行，`entry.common` 在双端运行。因此旧版路径中需要在单人集成服务端运行的逻辑必须放入 `entry.common`。
- **托管 `ScriptPackage.startManaged(...)`**（由 `ManagedScriptEntryPoint` 驱动）：使用**逻辑侧** `LogicalSide` 过滤——`LogicalSide.SERVER` 运行 `common + server`，`LogicalSide.CLIENT` 运行 `common + client`。

### 3.3 `StateProperty.Side` 与 `Saved<T>`

- `lib.kasuga.registration.data_driven.state.StateProperty`：
  - `set(ServerLevel, BlockPos, T, int)` 强制要求调用线程等于 `level.getServer().isSameThread()` 且只能在服务端执行。
  - `onChange(Side, Consumer<Change<T>>)` 支持 `Side.SERVER`（默认）、`Side.CLIENT` 与 `Side.BOTH`，按触发时的 `level.isClientSide` 匹配。
- `lib.kasuga.inject.auto_configure.Saved<T>`：由 `SavedFactory` 注入，在 `ServerStartedEvent` 绑定 `MinecraftServer`、在 `ServerStoppingEvent` 卸载；在服务端未启动或客户端无服务端上下文时调用 `get()` 会抛出 `IllegalStateException("The level is not loaded yet.")`。

---

## 4. 模块与核心 API 侧别矩阵

| 类别 | 模块 / API | 允许运行的侧 | 注意事项 |
| --- | --- | --- | --- |
| 纯 JVM / 图形无关核心 | `modules/utils`、`modules/formula`、`modules/data-driven` 管线（`DataDrivenBuildPipeline`）、`modules/render-core`（`ModelInstance.forSimulation`、`ModelTickLoop`、`SkeletonRagdoll`、`ProxyRuntime`） | 任意（双端 + 离线单测） | 物理模拟需加载原生 Box3D 库（`NativeBox3D.availableOrWarn()`），不依赖 Minecraft 或 OpenGL |
| 通用注册、VFS 与网络 | `KasugaLib`、`Registry` / `Reg`、`ResourceSystem`、`VirtualFileSystem`、`RpcSessionManager`、`DeviceRegistries`、`DocumentComponentRegistries` | 双端（`COMMON`） | 注意客户端与服务端各自的 `ScopedResourceManager` 实例隔离 |
| 服务端专属 | `Saved` / `SavedFactory`、`ServerCollisionModels`、`ScriptingServerApplication` | 逻辑服务端 / 专用服务端 | `ScriptingServerApplication` 标注 `@BeanOnlyIn.DedicatedServer` |
| 仅客户端渲染与输出 | `modules/render-opengl`、`modules/iris-compat`、`Constants`、`PipelineRegistry`、`MCBackend`、`EffectRenderPipeline`、`RenderShaderRegistry`、`WorldRenderPipelineRegistry`、`PostProcessGraphRegistry`、`MinecraftWorldViews`、`MinecraftFrameOutputs`、`ItemModelRenderers`、`ModelParticleRuntime`、`ModelLightRuntime`、`ClientBlockUpdateHooks`、`HeadlessClient`、`ScriptingClientApplication`、`ClientBogeyRendererRegistry` | 仅物理客户端（且 GPU 操作需渲染线程） | 在服务端加载客户端渲染类会触发 `Dist` / OpenGL 类缺失异常；`HeadlessClient` 仍运行在完整物理客户端上 |

## 相关页面

- [生命周期与初始化顺序](lifecycle.md)
- [配置项](configuration.md)
- [扩展点目录](extensions.md)
- [线程模型](../explanation/threads.md)
- [统一资源平台与 Script Ticket](resource-platform.md)
