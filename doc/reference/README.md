# API Reference

接口签名、构造参数、配置 schema、调用示例和开发命令统一放在本目录。
算法、模块边界、数据流及资源所有权的解释集中在 [原理与设计](../explanation/README.md)。
引用按当前源码整理；历史性能/客户端验收记录会标明当时的环境，文档迁移不表示本次重新验证。

## 核心、依赖注入与构建

| 文档 | 内容 |
| --- | --- |
| [Core 模组入口与注册系统](core-registration.md) | `KasugaLib` 入口、`Reg` / `RegistryGroup` / `Registry`、阶段分发与 Minecraft/自定义注册 DSL |
| [Core 运行时基础设施与内容子系统](core-runtime.md) | 网络 RPC、`SavedSystem`、`CompatCapability`、Codec/Holder、核心渲染钩子与设备/文档/图/QR 子系统 |
| [Inject 依赖注入与环境装配](inject.md) | Micronaut 4.9.10 引导、`@BeanOnlyIn`、`Configurable`、`Saved<T>` 与 `ServiceMixinPlugin` |
| [Utils 工具模块](utils.md) | 条件 Mixin、代码生成标记注解、`LazyRecomputable`/`MapSet`/`Pair`、路径/glob 匹配与 `GsonBindings` |
| [生命周期与初始化顺序](lifecycle.md) | 模组加载、Micronaut DI 启动、`RegistrationStage` 派发顺序、渲染/脚本初始化与停机清理 |
| [配置项](configuration.md) | `Configurable` / `ModConfigSpec` 配置项、JSON/TOML 运行时配置、JVM 系统属性与环境变量 |
| [扩展点目录](extensions.md) | DI、注册阶段、数据驱动、脚本引擎、模型与渲染管线的公开 SPI 目录与注销契约 |
| [侧边模型](sidedness.md) | 物理侧（`Dist` / `@BeanOnlyIn`）、逻辑侧（`LogicalSide`）、Mixin 隔离与各子系统侧别矩阵 |
| [Gradle 插件、代码生成与资源编译参考](gradle-and-codegen.md) | `builder.kasuga.*` 四插件、`@RegGenerator`/`@CodeTemplate` 代码生成与 `compileResources` |
| [库打包、阴影重定位与多版本结构参考](library-packaging.md) | 根构建约定、`:modules:library` Shadow 重定位、`neoforge.mods.toml` 与多版本模块边界 |

## 模型、骨骼与动画

| 文档 | 内容 |
| --- | --- |
| [模型加载与渲染](modelling/README.md) | 资源格式（`.bbmodel`/`.geo.json`/`.json`/`.obj`/`.mmd.zip`/`.glb`/`.mtb`/`.fmtb`/`.bob`/`.beo`/`.fmf`）、`model_proxy`、光照模式、全局合批、实例化与实时同步 |
| [通用模型姿态与动画 API](model-posing.md) | Model 片段库、格式接入、ModelPosing、静态 Pose、IK 通道和接口迁移 |
| [Model 组装与穿搭缓存 API](model-assembly.md) | 组件 Builder、共享骨架、隐藏区域、引用重映射、LRU 缓存和扩展接口 |
| [Minecraft 穿搭资源与实例切换 API](minecraft-model-assembly.md) | 资源包 JSON、程序注册、句柄换装、资源重载和实例状态迁移 |
| [Minecraft 模型附加组件 v1](mc-model-components.md) | Blockbench 插件的数据格式、IK/物理/粒子扩展与游戏端加载契约 |
| [Anchor 与 Minecraft 装备挂接](anchors.md) | 通用锚点、相机相对坐标、手持/头戴物品、自定义装备 renderer 和生命周期 |
| [Skeleton Dynamics](skeleton-dynamics.md) | 通用刚体/关节、Reader/Builder、IK 限位与反向映射、菱形闭环、实例控制和诊断 |
| [Physics 与 tick loop](physics.md) | 原生 world、复合形状、关节、力/冲量、环境、拖拽、主动布娃娃、锚点和方块物理 |
| [MMD/glTF 配置与部署](mmd-ragdoll.md) | model manifest、profile 字段、角色限位、运行时部署和命令 |
| [摄像机与动画](camera-animation.md) | 直接控制、关键帧、JSON、共享时间轴与模型片段绑定 |
| [状态机与动画剪辑 JSON 参考](fsm.md) | 状态变量、层/状态/转移、姿态 schema 与宿主绑定 |
| [模型变体选择（Multiplexer）参考](multiplexer.md) | 变体选择 DSL、Builder、实例状态及 FSM 组合 |
| [真实素材换装示例](../guides/wardrobe-example.md) | RibbonDress 换纹理/换模型、MC 手持物品、客户端截图和性能对照 |
| [Proxy 接入指南](../guides/proxy-integration.md) | Definition/Type 注册、Session 自动挂载、typed Port、tick/frame、生命周期与故障排查 |

## 渲染、预览与世界 UI

| 文档 | 内容 |
| --- | --- |
| [Kasuga 自定义渲染规范](effect-rendering.md) | 宿主作用域、世界管线注册、着色器与后处理接入规范 |
| [Java Shader DSL 参考](shader-dsl.md) | Java/GraalJS Shader DSL、GLSL 生成、Gradle 集成与运行时参数 |
| [托管效果与后处理](effects-and-postfx.md) | 托管效果管线、后处理图、世界空间天气滤镜与诊断命令 |
| [内置效果、水体仿真与空间音频参考](builtin-effects-and-audio.md) | 黑洞引力透镜、浅水自由表面仿真与光学、空间音频与 `/kasuga_effects` |
| [天空体积云与积雨云实例](volumetric-clouds.md) | 多层天空配置、单云实例、质量、预览与验证入口 |
| [UML 渲染 framework 与后端接口](uml-render-framework.md) | backend/context/factory、调度与 buffer 的公共接口和生命周期契约 |
| [渲染后端接入参考](rendering-backends.md) | 独立后端模块、宿主帧上下文、上传与输出适配 |
| [Iris 兼容层参考](iris-compat.md) | `modules/iris-compat` 模块隔离、`CompatCapability` 探测、mixin 门控与 framebuffer/顶点桥接 |
| [模型渲染调度（Render Scheduling）](render-scheduling.md) | McModelHandle、可见性、Entity/BlockEntity renderer 接入 |
| [模型材质 Alpha 渲染参考](render-alpha-passes.md) | 材质 pass、Iris OIT 回退、调试场景、命令、快捷键和验收指标 |
| [KasugaLib PMX → PBR 转换](pbr.md) | 玩家 JSON、Java 转换规则、上下文、优先级和诊断命令 |
| [模型深度偏移](model-depth-bias.md) | `DepthBias` 策略、实例级偏移参数、合批分组与 OpenGL 后端行为 |
| [物品模型渲染](item-model-renderers.md) | `ItemModelRenderers` 映射、共享 BEWLR、`builtin/entity` 前提、显示变换与调度 |
| [MC 最终画面离屏输出](frame-output.md) | 订阅、共享帧、MIRROR/OFFSCREEN_ONLY、异常与关闭 |
| [Player / Free / Fixed Camera](cameras.md) | 相机分类、创建、通用跟随目标、实体绑定、投影和运行时渲染配置 |
| [多机位输出契约](multi-camera-output.md) / [离屏输出实操](../guides/offline-rendering.md) | 相机生命周期、资源包、独立窗口、启动、FBO 消费和录制 |
| [Minecraft 结构与 Create 模型预览](minecraft-model-preview.md) | 统一原生模型源、结构 NBT、Litematica/Forgematica、分层、爆炸图和 UI/scene 后处理接入 |
| [UI 模型预览](ui-model-preview.md) | `GuiModelPreview` 独立实例、局部渲染画布与视图控制 |
| [世界空间 UI 与自由光标输入](world-ui-input.md) | 透明输入层、屏幕反投影、世界面板、拖拽捕获与键盘焦点 |
| [LDLib2 Compose 接入](ldlib-compose.md) | `external/uip/ldlib-compose` 声明式世界 UI 与客户端本地面板接入 |

## 数据驱动、资源、脚本与表达式

| 文档 | 内容 |
| --- | --- |
| [数据驱动注册专题](data-driven/README.md) | 数据驱动总入口：索引、schema、扩展、诊断与重载（六篇参考 + 三篇指南） |
| [数据驱动 JSON 格式规范（schema）](data-driven/schema.md) | 索引清单与内容文件格式、内建 `proxy_model` / `proxy_model_be` 参数、STRICT/LEGACY 与 D1–D6 规则 |
| [数据驱动方块状态属性](data-driven/block-state-properties.md) | `state_properties` 方块状态声明、原版属性引用与工厂扩展 |
| [数据驱动注册期 API](data-driven/api-registration.md) | v2 多来源构建管线、宿主 Provider、快照来源与旧入口 `JsonTreeBuilder` |
| [数据驱动扩展 API](data-driven/api-extension.md) | `TypeHandler`、`HandlerCapability`、`ValidationContext`、`FactoryRegistry` 与属性解析器 |
| [数据驱动诊断 API](data-driven/api-diagnostics.md) | `Diagnostics` 门面、`/kasuga_data` 命令、`BuildDiagnostic`、`/kasuga_proxy list` 与错误分类 |
| [数据驱动重载 API](data-driven/api-reload.md) | `ReloadOrchestrator`、`ReloadHandler`、`ManagedReloadAllowlist` 与纯函数测试面 |
| [统一资源平台与 Script Ticket](resource-platform.md) | 来源/URI、magic number、目录路由、MC 首次发现、scope/generation、脚本注册与异步 Ticket |
| [虚拟文件系统（VFS）](vfs.md) | `VirtualFileSystem`、`VfsPath`、目录/内存/ZIP 后端、叠加挂载、事务与 MC 资源桥接 |
| [Scripting 与 Javet 运行时参考](scripting.md) | `ScriptRuntime`、包扫描、`package.toml`、模块解析、`@Api` 安全拦截、FSM 桥接与 Javet 后端 |
| [Formula 模块](formula/README.md) | 表达式、Namespace、函数/变量、运算符和错误 |
| [构建与验证入口](../guides/verification.md) | Gradle 任务、独立 GL、客户端验收与证据边界 |

## Create 与移动宿主集成

| 文档 | 内容 |
| --- | --- |
| [Create 模块集成参考](create-integration.md) | Create 6 轨道与转向架注册 DSL、列车设备、信号区段、转向架/装配体 Proxy 与交互形状扩展 |
| [移动结构上的场景与 UI](moving-scene-hosts.md) | `SceneHost`、`CreateSceneHosts`、Create Contraption 局部变换与 UI 挂载 |
| [航空学 / Sable 的场景宿主](sable-scene-hosts.md) | `SableSceneHosts` 适配 Sable `SubLevel` plot 坐标空间到 `SceneModels` 与世界 UI |

## 文档维护

新增可调用接口时在本目录补全包名、签名、单位、默认值、错误条件与生命周期，并链接到对应原理。
修改接口时同步调用示例与配置表。迁移或改名时更新本索引、根 README、相对链接与锚点。
原理文档可以使用类型名、数学表达式和流程图解释机制；方法参考、配置表和操作步骤在本目录维护。
