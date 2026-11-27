# 配置项

本文列出 KasugaLibNext 当前源码中的所有配置机制与生效项，涵盖 NeoForge `ModConfigSpec`（通过 `@Context` / `@Singleton` + `Configurable` 注册）、JSON / TOML 运行时配置文件，以及 JVM 系统属性与环境变量。

> 说明：`modules/core` 中不存在名为 `CoreConfig` 的类；核心渲染配置位于 `ClientRenderingRegistry`，其余模组配置分别由各自模块中的 `Configurable` Bean、JSON 持久化或系统属性提供。

---

## 1. NeoForge `ModConfigSpec` 与 `Configurable` 机制

在 [生命周期与初始化顺序](lifecycle.md) 的 DI 启动阶段，`ModApplicationContext.init(ApplicationContext, IEventBus, ModContainer)` 会在 `context.start()` 之后调用 `lib.kasuga.inject.auto_configure.Configurable.configure(context, modEventBus, modContainer)`：

1. 通过 `context.getBeansOfType(Configurable.class)` 收集当前上下文中所有启用（满足 `@BeanOnlyIn`、`@CreateModBeans` 等条件）的 `Configurable` Bean。
2. 为四种 `ModConfig.Type`（`CLIENT`、`SERVER`、`COMMON`、`STARTUP`）各创建一个 `ModConfigSpec.Builder`。
3. 依次调用每个 Bean 的 `onConfigure(ModConfig.Type, ModConfigSpec.Builder)`，该方法默认按类型分派到 `configureClient`、`configureServer`、`configureCommon` 或 `configureStartup`。
4. 对每个非空（`!spec.isEmpty()`）的 `ModConfigSpec` 调用 `modContainer.registerConfig(type, spec)`。

### 1.1 `CLIENT` 配置项（仅物理客户端生效）

以下配置由 `@BeanOnlyIn.Client` Bean 注册，仅在物理客户端加载并生成客户端配置文件：

| 配置类（全限定名） | TOML 键 | 类型 | 默认值 / 约束 | 语义说明 |
| --- | --- | --- | --- | --- |
| `lib.kasuga.registration.beans.rendering.ClientRenderingRegistry` | `ignore_block_entity_renderer` | `boolean` | `false` | 为 `true` 时跳过通过 `ClientRenderingRegistry.registerBlockEntityRenderer(...)` 注册方块实体渲染器并记录 `WARN` 日志 |
| `lib.kasuga.rendering.effect.builtin.weather.WeatherFilterConfig` | `enable_weather_filter` | `boolean` | `true` | 开启室内实体 / 场景空间的 3D 世界空间天气遮挡（挡雨、挡雪、溅射粒子与雨声衰减） |
| `lib.kasuga.rendering.effect.builtin.weather.WeatherFilterConfig` | `included_entity_types` | `List<String>` | `[]`（每项须通过 `ResourceLocation.tryParse` 校验） | 额外参与天气遮挡的实体类型 ID 列表（例如 `"minecraft:boat"`） |
| `lib.kasuga.rendering.effect.builtin.weather.WeatherFilterConfig` | `excluded_entity_types` | `List<String>` | `[]`（每项须通过 `ResourceLocation.tryParse` 校验） | 从自动天气遮挡中排除的实体类型 ID 列表（例如 `"create:carriage_contraption"`） |

> 即使处于没有 Micronaut / FML 配置引导的纯 JVM 测试环境中，`WeatherFilterConfig` 也会安全回退到默认返回值（`isWeatherFilterEnabled()` 返回 `true`，包含/排除列表为空）。

### 1.2 `SERVER` 配置项（仅独立服务端生效）

| 配置类（全限定名） | TOML 键 | 类型 | 默认值 | 语义说明 |
| --- | --- | --- | --- | --- |
| `lib.kasuga.scripting.ScriptingServerApplication` | `ignore_scripting_errors` | `boolean` | `false` | 类上标注 `@BeanOnlyIn.DedicatedServer`。在 `ServerResourceReloadFinishEvent` 检测到缺失脚本引擎时：为 `true` 仅打 `WARN` 日志，为 `false` 则抛出 `RuntimeException` 中止。注：若 `packageSystem.getEngineErrors()` 非空，仍会抛出 `RuntimeException` |

### 1.3 `COMMON` 配置项（`CreateConfig`）

`lib.kasuga.create.CreateConfig` 标注了 `@Context` 与 `@CreateModBeans`（仅当 `ModList.get().isLoaded("create")` 为真时启用），其内部通过 `KasugaLibCreateApi` 读取部分项的默认值：

| TOML 键 | 类型 | 默认值 / 取值范围 | 静态读取入口（`CreateConfig`） | 语义说明 |
| --- | --- | --- | --- | --- |
| `enable_multi_bogey_carriage` | `boolean` | `KasugaLibCreateApi.getMultiBogeyCarriageDefault()`（初始 `false`） | `isMultiBogeyCarriageEnabled()` | 允许列车车厢装配超过原版两个转向架；为 `false` 时装配、持久化、同步与渲染均走原版双转向架路径 |
| `enable_carriage_y_offset` | `boolean` | `KasugaLibCreateApi.getCarriageYOffsetDefault()`（初始 `false`） | `isCarriageYOffestEnabled()` | 允许车厢拥有垂直（Y 轴）偏移 |
| `max_bogey_offset` | `double` | `1.0D`（`[0.0D, Double.MAX_VALUE]`） | `maxBogeyOffset()` | 列车过弯时中间转向架相对其车厢局部方块位置允许的最大横向偏移（单位：方块） |
| `max_carriage_y_offset` | `double` | `0.5D`（`[0.0D, Double.MAX_VALUE]`） | `maxCarriageYOffset()` | 车厢车体相对转向架允许的最大垂直偏移幅度（单位：方块） |
| `derailed_on_bogey_offset_overflow` | `boolean` | `KasugaLibCreateApi.getDerailedOnBogeyOffsetDefault()`（初始 `false`） | `derailedOnBogeyOffsetEnabled()` | 转向架偏移达到上限时是否触发列车出轨 |
| `enable_contraption_lod` | `boolean` | `true` | `isContraptionLodEnabled()` | 是否向客户端发送活动 Create 机械动力的远景（LOD）渲染网格 |
| `enable_contraption_weather_filter` | `boolean` | `KasugaLibCreateApi.getContraptionWeatherFilterDefault()`（初始 `true`） | `isContraptionWeatherFilterEnabled()` | 自动在 Create 机械动力（如列车）内部遮挡雨雪 |
| `enable_sable_weather_filter` | `boolean` | `true` | `isSableWeatherFilterEnabled()` | 自动在 Sable / Aeronautics 物理子维度（sublevel）内部遮挡雨雪 |

#### 下游模组默认值覆盖 API（`KasugaLibCreateApi`）

`lib.kasuga.create.KasugaLibCreateApi` 允许下游模组在构造阶段（配置加载之前）调整以下四个 `COMMON` 项的生成默认值；若用户的配置文件中已显式写入对应键，配置文件中的值仍优先：

- `KasugaLibCreateApi.setMultiBogeyCarriageDefault(boolean)`
- `KasugaLibCreateApi.setCarriageYOffsetDefault(boolean)`
- `KasugaLibCreateApi.setDerailedOnBogeyOffsetDefault(boolean)`
- `KasugaLibCreateApi.setContraptionWeatherFilterDefault(boolean)`

当前无任何 Bean 实现 `configureStartup(ModConfigSpec.Builder)`，`STARTUP` 规格默认为空且不会被注册。

---

## 2. JSON 与 TOML 文件配置

| 配置类 / 模块 | 文件位置 | 加载与重载时机 | 主要用途与参考 |
| --- | --- | --- | --- |
| `lib.kasuga.rendering.models.mc.api.pbr.PbrUserConfig` | `<gameDir>/config/kasuga-pbr.json` | `KasugaModelManager.reload(...)`（客户端资源重载、`/kasuga_pbr reload_config`） | 玩家自定义 PMX → PBR 转换规则。首次缺失时自动生成模板文件；向 `PbrConversionRegistry` 注册 ID 为 `kasuga_lib:user_config`、优先级 `1000` 的规则。详细字段见 [KasugaLib PMX → PBR 转换](pbr.md) |
| `lib.kasuga.rendering.effect.shader.ShaderParameterPersistence` | `<configDir>/kasuga_lib/shader-parameters.json` | `Constants.onClientSetup` 调用 `initialize()`；参数变更后防抖 `200ms` 异步写出，`flush()` 可强制同步落盘 | 持久化 `RenderShaderHandle` 的非默认暴露参数（格式版本 `version = 1`，结构 `shaders.<shader_id>.<param> = [double...]`）。由 `/kasuga_effects parameter` 或 `ShaderParameterScreen` 修改 |
| `lib.kasuga.rendering.models.mc.source.model.ModelProxyConfig` / `ModelProxyConfigLoader` | `assets/<namespace>/models/model_proxy.json` | `KasugaModelManager.reload(...)` 扫描并合并各命名空间的配置 | 声明需要被 Kasuga 模型加载器接管的模型路径 glob（支持 `*`、`**`、`?` 以及 `!` 前缀排除）。详见 [模型加载与渲染](modelling/README.md) |
| `lib.kasuga.rendering.models.mc.dynamic.physics.MinecraftRagdollConfig` | 资源包内 JSON（由调用方 `ResourceLocation` 指定） | `MinecraftRagdollConfig.load(ResourceManager, ResourceLocation)` / `fromJson(JsonObject)` | 配置布娃娃刚体拓扑、`limits`、`include_secondary_bodies`、`simulation`、`collision`、`environment`、`initial_state`、`dragging` 与 `sleeping`。详见 [PMX 与 glTF 布娃娃配置](mmd-ragdoll.md) |
| `lib.kasuga.scripting.discovery.PackageSystem` / `PackageInfo` | 脚本包根目录下的 `package.toml`（默认根目录为资源包 `scripts/`） | `ScriptRuntime`（旧版扫描）或 `ManagedScriptEntryPoint`（托管资源生成） | TOML 顶层键：`name`、`engine`、`description`、`version`、`main`、`workspaces`；入口表 `[entry]` 下含 `server`、`client`、`common` |

---

## 3. JVM 系统属性与环境变量

除配置文件外，渲染、物理与无窗口运行时的部分底层开关通过 JVM `-D` 系统属性或环境变量读取：

| 系统属性（`-D`） | 对应环境变量 | 默认值 | 源码位置 | 说明 |
| --- | --- | --- | --- | --- |
| `kasuga.shaderPreparationWorkers` | — | `0`（自动策略：`min(max(1, (cpus+1)/2), 4)`） | `ShaderPreparationScheduler` | Java Shader DSL 后台准备线程池并发度；`> 0` 时上限为可见处理器数；亦可在运行时通过 `/kasuga_effects workers <count>` 调整 |
| `kasuga.modelGpuSkinning` | `KASUGA_MODEL_GPU_SKINNING` | `false` | `KsgVertexBuffer` | 环境变量优先于系统属性。启用 GPU 蒙皮路径（`isGpuSkinningEnabled()` 在 Iris 激活时返回 `false`，Iris 场景走 `isIrisGpuSkinningEnabled()`） |
| `kasuga.layeredTransparency` | — | `true` | `LayeredTransparency` | 启用分层半透明回放；当 Iris、原版极佳画质（`Minecraft.useShaderTransparency()`）或 Voxy 启用时自动禁用 |
| `kasuga.headless` | — | `"disabled"` | `HeadlessClient` | 无窗口客户端模式：可选 `"disabled"`、`"hidden"` 或 `"egl"`（仅 Linux，且要求已设置 `org.lwjgl.glfw.libname` 指向 pbuffer GLFW） |
| `kasuga.box3d.library` | — | 未设置（从 `/native/<os>-<arch>/` 释放或 `System.loadLibrary`） | `NativeBox3D` | 显式指定原生 Box3D 动态库文件路径 |
| `kasuga.profileModel` | `KASUGA_PROFILE_MODEL` | `false` | `ModelProfiler` | 环境变量优先。启用 `[model-profile]` 周期性耗时日志 |
| `kasuga.profileModel.intervalMs` | `KASUGA_PROFILE_MODEL_INTERVAL_MS` | `2000` | `ModelProfiler` | 环境变量优先。`[model-profile]` 日志输出周期（毫秒，最小 `1`） |
| `kasuga.profileModel.recalculateDynamicTangents` | `KASUGA_PROFILE_MODEL_RECALCULATE_DYNAMIC_TANGENTS` | `false` | `CpuVertexSkinning` | 环境变量优先。控制 CPU 蒙皮时是否重算动态切线 |

## 相关页面

- [生命周期与初始化顺序](lifecycle.md)
- [侧边模型](sidedness.md)
- [KasugaLib PMX → PBR 转换](pbr.md)
- [托管效果与后处理](effects-and-postfx.md)
- [PMX 与 glTF 布娃娃配置](mmd-ragdoll.md)
