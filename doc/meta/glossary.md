# 中英术语对照

本站的双语文档、源码与 issue 用同一套术语。撰写文档、报 issue 或读另一语言页面时，按本页取词。

标识符（类名、JSON 键、命令、模块名、路径、版本串）在两种语言里都保持原文，本页不列它们的译名。

## 核心术语

按子系统分组。左列是文档里实际出现的中文说法，右列是英文侧对应的写法。表里没列出的日常用语按普通技术英语写。

### 模型与动画

| 中文术语 | English | 说明 |
| --- | --- | --- |
| Pose / 姿态 | pose | 代码类型名为 `Pose`；不要写 posture / stance |
| 静态姿态 | static pose | 恒定采样的片段，通道所有权和重置规则与其它片段相同 |
| 姿态混合 | pose blending | 多个层按确定顺序合成：基础层给基准，累加层叠加局部动作，覆盖层按骨骼掩码替换指定范围 |
| 姿态供应器 | pose provider | 出现 `Supplier<WorldCameraView>` 时指 Java 接口，照写 `Supplier` |
| PoseDriver | PoseDriver | 类名，不译 |
| 片段 | clip | 「动画剪辑」是同一概念的另一种说法 |
| 命名片段库 | named clip library | |
| 采样器 | sampler | |
| 采样 | sample / sampling | |
| 求值 | evaluate / evaluation | 不要用 compute：`compute` 是 formula 模块的算术引擎包名 |
| 时间轴 | timeline | |
| 共享时间轴 | shared timeline | 由一个宿主统一驱动，模型和摄像机只跟随 |
| 时钟 | clock | 独立播放器有自己的时钟：游戏 tick 推进时间，渲染帧只按 partial tick 采样 |
| 关键帧 | keyframe | |
| 分量轨道 | component track | 每条轨道独立驱动 `x / y / z / yaw / pitch / roll / zoom` 中的一个分量 |
| 缓动 | easing | 沿用模型的 `Easing`，作用于关键帧的下一段 |
| morph | morph | 不译；不要写 deformation / blend shape |
| 材质帧 | material frame | |
| 骨骼掩码 | bone mask | 只影响指定骨骼组 |
| 层 / 状态层 | layer | 多个 layer 并行运行，每 tick 合成各层姿态写进模型 |
| 分层状态机 | layered state machine | FSM 是 Mecanim 风格的分层状态机 |
| 转移 | transition | |
| 守卫 | guard | 全真才允许转移 |
| 触发器 | trigger | 指向布尔变量名 / id，置位时触发 |
| 状态变量 | state variable | |
| 淡入淡出 | cross-fade | 「交叉淡入淡出」同译 cross-fade；0 表示立即切换 |
| 变体 | variant | 不要写 version / edition：那两个词在这里指资源版本 |
| 变体选择器 | variant selector | |
| 上下文 | context | Multiplexer 的上下文快照；与渲染框架里的「宿主帧上下文」不是同一个概念 |
| 重定向 | retargeting | 不同骨架之间的拓扑重定向由接入方负责；代码入口 `AnimationPlayback.retarget` |
| 公式轨道 | formula track | 每帧求值 |

### 骨骼、物理与布娃娃

| 中文术语 | English | 说明 |
| --- | --- | --- |
| 骨架 / Skeleton | skeleton | 结构统一译 skeleton；不要写 framework |
| 骨骼 / Bone | bone | |
| 绑定姿态 | bind pose | 代码字段 `bindPoseFollowers` |
| 蒙皮 | skinning | 名词与动名词同形；不要写 skin：`Skin` / `SkinInstance` 在 glTF/PMX 语境指格式里的皮肤数据 |
| 蒙皮权重 | skin weight | |
| 刚体 | rigid body | 非 PMX 内容的默认实现是 `GenericRigidBody` |
| 关节 | joint | |
| 约束 | constraint | |
| 限位 | limit | 只用 limit / limits；不写 limiter、position limit |
| 反向映射 | reverse mapping | `ikChainsByBone()` 给出驱动骨到控制它的全部链的映射 |
| 逆向运动学 / IK | inverse kinematics (IK) | 缩写 IK 不译 |
| 菱形闭环 | diamond closure | 两个 base 在绑定位置共享 pivot，两个末端表示同一个闭合点。不要写 diamond constraint / diamond loop |
| 闭环残差 | closure residual | 同时计入末端间距和 base 间距；停止迭代不等于收敛，诊断要保留 residual 与 satisfied |
| 驱动骨 | driver bone | |
| 末端骨 / effector | effector bone | 字段名 effector 保留；只描述闭合点时用 endpoint |
| 链 / IK 链 | chain / IK chain | 从 base 到 tip，最终 link 指向 effector |
| 写回 | writeback | 不要写 feedback / write into |
| 固定步长 | fixed step | `KinematicDriver.beginStep(world)` 在每个 fixed step 前执行 |
| 物理写回 | physics writeback | 写回后只重建层级，不能再求解 IK |
| 布娃娃 | ragdoll | |
| 主动布娃娃 | active ragdoll | 同帧动画/IK 目标转为受限 PD 弹簧力和力矩，接触与关节仍由 Box3D 处理 |
| 次级运动 | secondary motion | 不要写 secondary physics；`include_secondary_bodies` 之类的键名照抄不译 |
| 主体 profile | body profile | profile 保留原文；按实际骨骼段生成胶囊、语义质量与关节 |
| 浮动原点 | floating origin | 模型根节点的世界平移移出 float 骨骼矩阵，作为独立的 double `worldOrigin` 保存 |
| 大坐标 | large coordinates | |
| 世界锚点 | world anchor | double 世界锚点与局部 float 物理坐标分开，环境碰撞、射线和拖拽先转到同一模拟坐标系 |
| 求解器 | solver | `CalikoIkSolver` 提供 `solve()` 与 `results()` |
| 休眠 | sleeping | 整组刚体休眠；调试碰撞或持续空中模拟时建议关闭 |
| 冲量 | impulse | 立即改变速度；不要写 momentum；代码入口 `applyImpulse` |
| 力 / 力矩 | force / torque | 由下一个原生 Box3D step 积分；不要写 strength |
| 拖拽 | drag | 在原生 Box3D 中创建 kinematic anchor 与 spherical joint；不要写成 teleport body |
| 环境网格 | environment mesh | `StaticEnvironmentMesh` 通过 `putCell` / `removeCell` 增量同步 MC 地形，Java 侧不做碰撞求解 |
| 接触 | contact | `separation` 为负值表示穿透 |
| 接管 | take over | IK 接管链骨作者局部姿态；物理启用时由物理接管姿态求值 |
| 准星拾取 | crosshair picking | 鼠标键（`0` 为左键）与最大拾取距离 |
| 素体 | base body | 未穿衣服的基础人体模型；不要写 bare model |

### 渲染与输出

| 中文术语 | English | 说明 |
| --- | --- | --- |
| 管线 | pipeline | 不要写 conduit / duct |
| 模型管线 | model pipeline | `ModelPipeLine` |
| 调度 | scheduling | `ModelRenderScheduler` 为每个挂载实例维护一条策略，默认 `ALWAYS` |
| 可见性 | visibility | 持续绘制、宿主显式控制和原版 renderer 标记是三种策略 |
| 剔除 | culling | 不要写 cropping / clipping |
| 视锥 | frustum | 固定说法 view frustum |
| 视距 | render distance | `setMaxRenderDistance(instance, blocks)`，按相机距离平方裁剪，0 表示不限 |
| 帧标记 | frame mark | 宿主 renderer 在可见帧内调 `markRenderedThisFrame`，第一模型 pass 调一次 `flipFrame` |
| 门控 / 闸门 | gate（动词 gating） | 两个中文说法同义，统一到 gate；距离与视锥是模式之外的额外门控 |
| 挂载 | mount | 注册表按调用方的 key 匹配挂载；不要写 installation |
| 装备挂接 | attachment | `McModelHandle.attachToAnchor` / `detachFromAnchor` |
| 机位 | camera view | 同一客户端进程里的一个渲染单元；不要写 camera / rig / station |
| 多机位 | multi-camera | 不要写 multi-view（见「容易译错的词」） |
| 视角 | view | `view ID` 标识已经渲染完成的视角；不要与「机位」混用 |
| 主视角 | main view | 不写 main camera |
| 主画面 | main frame | `MIRROR` 同时交付 FBO 和显示窗口 |
| 离屏 | offscreen | |
| 借用 | borrow | 输出是渲染线程上的同步只读借用，消费者不能改尺寸或删纹理 |
| 只读借用 | read-only borrow | |
| 合成 | compositing | |
| 效果 | effect | 同类效果共享渲染器和管线状态 |
| 后处理 | post-processing | |
| 作用域 | scope | 面向动态内容的注册器，会关闭自己拥有的资源；父作用域拥有子作用域 |
| 描述 | descriptor | 定义某项资源配置的不可变值 |
| 分代 | generation | shader 句柄跨资源重载保持稳定，内部编译实例按代替换 |
| 上传 | upload | GPU 上传接收完整快照，脏范围描述变更元素 |
| 在途读取 | in-flight read | GPU 在途读取期间不能覆盖 storage |
| 上传环 | upload ring | `UploadRing` 负责槽位轮换、脏元素合并、版本追踪和资源生命周期 |
| 图集 | atlas | 基础色、法线和 specular 分别拼接到对应 atlas |
| 材质变体 | material variant | 相同源纹理、不同参数时生成独立变体和独立法线 / specular sprite |
| 直通 alpha | straight alpha | |
| 半透明回放 | translucent replay | 每次 draw 和透明回放都记录消费，本帧数据没变也不能漏掉完成标记 |
| 光线步进 | ray marching | 云场通过一次覆盖天空的光线步进和一次合成绘制，不逐朵创建全屏 pass |
| 自遮光 | self-shadowing | 云场沿太阳方向采样附近密度以近似自身遮光 |
| 合批 | batching | 静态方块几何适合 section mesh 合批 |
| 宿主 | host | 宿主负责帧与视角入口，后端负责资源与绘制 |
| 后端 | backend | |
| 几何适配器 | geometry adapter | 负责模型表示转换；资源工厂负责创建某个实例的资源 |
| 帧输出 | frame output | 最终画面的 WYSIWYG 输出入口 |
| 渲染线程 | render thread | 在渲染线程注册，保存返回的 registration |

### 数据驱动

| 中文术语 | English | 说明 |
| --- | --- | --- |
| 数据驱动 | data-driven | |
| 注册期 | registration phase | mod 启动、构造期间把对象写入 MC 注册表（如 `BuiltInRegistries.BLOCK`）；表头简称 registration |
| reload 期 | reload phase | `/reload` 触发的资源重读，来源是包栈，数据包可覆盖 |
| 两个时机 | the two phases | 指注册期与 reload 期；不要直译成 two timings |
| 域 | domain | 索引清单里的两个数组 `on_register`（注册域）与 `on_reload`（重载域）；代码 `Diagnostics.Domain` |
| 注册域 / 重载域 | registration domain / reload domain | |
| 桶 | bucket | 存放诊断的列表，分 mod 维与类型维；不要写 basket / pail |
| 索引清单 | index manifest | 只列路径，不含对象定义；简称「索引文件」同译 index manifest，不要写 list / inventory |
| 内容文件 | content file | 方块、物品、分组等定义的唯一事实来源 |
| 顶层类型 | top-level type | 内容文件的顶层字段名即顶层注册类型，值必须是对象数组 |
| 内嵌类型 | embedded type | 如 `block_entities`，它不是顶层字段 |
| 工厂 | factory | `type` 找不到工厂时打一条 WARN 然后跳过，不进错误桶 |
| 工厂注册键 | factory registration key | JSON 里的 `type` 字段 |
| 属性解析器 | property parser | 方块属性那一层是 `JsonPropertyParser.registerCompiler`，API 名为 compiler |
| 注册组 | registry group | 建立属性分组（父 / 子） |
| 内嵌方块实体 | embedded block entity | |
| 后写覆盖 / last-wins | last-wins | 同一有效标识取后写者，资源包覆盖也按包栈顺序处理；代码注释直接写 `last-wins` |
| 去重 | deduplication | `DuplicateIdResolver` 是纯函数 last-wins 去重 |
| 编排器 | orchestrator | 另见「编排者」，同义；代码 `ReloadOrchestrator` |
| 分派 | dispatch | 代码 `dispatchContent` |
| 阶段 | phase | 与「注册期 / reload 期」并列存在，指 handler 的执行阶段（`PHASE_GROUPS`） |
| 热更 | hot reload | `/reload` 后新内容立即生效；有些内容一次完成、不可热更 |
| 包栈 | pack stack | 读数据包而非 jar，所以数据包可以覆盖这些文件 |
| 目录 glob | directory glob | glob 保留原文；由 `FsmReloadHandler.globDirectories()` 声明 |
| 内容树 | content tree | 解析层只构建原始内容树 |
| 诊断门面 | diagnostics facade | `diagnostics/Diagnostics.java` 是统一诊断出口 |
| 动画剪辑 | animation clip | 一段按时间轴采样的骨骼 / morph / 材质帧轨道；JSON 键 `animation_clips` |
| 解码器 | decoder | 文件层组件（`StateMachineDefinitionLoader` / `AnimationClipLoader`）；parse、decode、solve 是三个独立机制 |

### 生命周期、工程与验证

| 中文术语 | English | 说明 |
| --- | --- | --- |
| 生命周期 | lifecycle | |
| 幂等 | idempotent | 作形容词时不要写 idempotency |
| 先准备，再发布 | prepare-then-publish | 新挂载必须准备成功才能发布；替换失败时关闭未发布的新上下文并保留旧挂载 |
| 失效 | invalidate | 失效 / 淘汰不修改已返回结果；代码 `invalidate(Model)` |
| 资源重载 | resource reload | 缓存淘汰不销毁仍在渲染的模型；资源重载需要宿主先结束旧实例再释放其借用资源 |
| 源集 | source set | Gradle source set，如 `contentTesting` |
| 测试夹具 | test fixture | 不随库发布；不要写 clamp / jig |
| 烟雾测试 | smoke test | |
| 验证入口 | verification entry | Gradle 任务、独立 GL、客户端验收与证据边界 |
| 客户端验收 | client acceptance | |
| 成功标识 | success marker | 如 `ANCHOR_EQUIPMENT_SMOKE_PASS`，报告写在 `debug/anchor-equipment.json` |
| 无窗口 | headless | `runClientHeadless` 仍是完整客户端：桌面平台默认 hidden，Linux 默认 EGL |
| 证据边界 | evidence boundary | 验证记录应分别说明编译 / JVM、原生物理、独立 GL、真实客户端和平台 / renderer 组合 |
| 穿搭 | outfit | 代码变量名就是 `ModelAssembly outfit`；不要用 wardrobe，那是一个示例页面 |
| 换装 | swap | 代码 `McModelHandle.switchAssembly`；不要用 switch，与 Java 关键字视觉冲突 |
| 组装 | assembly | 代码 `ModelAssembly` / `ModelAssemblyBuilder` |
| 组件 / 部件 | part | |
| 隐藏区域 | hidden region | 构造 Part 时展开成面索引 |
| 引用重映射 | reference remapping | 代码 `ModelAssembly.Remap` |
| 缓存键 | cache key | 覆盖组件顺序、来源 Model 的对象身份及 revision、骨骼映射、最终隐藏面集合、动力学开关 |
| 锚点 | anchor | 类名 `Anchor` 不译 |
| 显示子物体 | display child | 绑定在蒙皮骨骼上的显示锚点，例如玩家手里拿的剑 |
| 显示上下文 | display context | MC 原生概念 `ItemDisplayContext` |
| 惯用手 | dominant hand | `bindHands` 按实体惯用手选择物理手锚点 |
| 命名空间 | namespace | 与 formula 模块的「命名空间变量」（`Namespace`）不是同一个概念 |
| 资源位置 | resource location | 代码名 `ResourceLocation` 保留 |
| 创建栏 | creative tab | MC 原生概念；原文亦作「创意标签页」，统一 creative tab |
| 光照 | lighting | MC 的光照系统（light engine）；与「光影」严格区分 |
| 光影 | shader pack | 指 Iris shader pack（`ShaderPack` / `PipelineManager`），不是 lighting |

## 不译名单

下列内容在两种语言里都保持原文，例子取自站内实际出现的写法。

| 类别 | 例子 |
| --- | --- |
| 类名 / API 名 | `ModelInstance`、`ModelTickLoop`、`SkeletonDynamicsBuilder`、`CalikoIkSolver`、`ModelAssemblyCache`、`RenderPipelineScope`、`JsonTreeBuilder`、`ReloadOrchestrator`、`Diagnostics`、`McModelHandle` |
| JSON 键 / 字段名 | `on_register`、`on_reload`、`state_vars`、`bone_mask`、`cross_fade_seconds`、`alphaCutoff`、`include_secondary_bodies`、`model_scale`、`registry_groups`、`state_machines`、`animation_clips`、`ik_enabled` |
| 命令 | `/reload`、`/kasuga_data errors`、`/kasuga_ragdoll deploy`、`/kasuga_physics block [1-32]`、`/ksglib debug oit`、`/kasuga_pbr status` |
| 模块 / 目录名 | `modules/modelling`、`modules/render-core`、`modules/render-opengl`、`modules/data-driven`、`modules/formula`、`external/slp/javet`、`contentTesting` |
| 版本串 | `Minecraft 1.21.1`、`NeoForge 21.1.203`、`Parchment 2024.11.17`、`Java 21`、`GLSL 150`、`Iris 1.8.12`、Box3D 提交 `30c67b5e6d0a3a66f0f506c69ce9e9e0587e3b7c` |
| 专有缩写 | FSM、IK、PBR、OIT、WBOIT、MRT、AO、FBO、VBO、TBO、UBO、SSBO、VAO、CCD、AABB、UV、SSS、LabPBR、LRU、LOD、MMD、PMX、PMD、VMD、VPD、glTF、BE（block entity）、D1–D6 |
| 技术 / 库名 | OpenGL、LWJGL、JOML、Blaze3D、Micronaut、DataFixerUpper、Box3D、Caliko、Iris、Sodium、Blockbench、NeoForge、Fabric、Gradle、JUnit |
| 模型格式名 | `.bbmodel`、`.geo.json`、`.obj`、`.glb`、`.gltf`、`.mmd.zip`、`.pmx`、`.pmd`、`.vmd`、`.vpd` |
| 资源路径 / 文件路径 | `data/<namespace>/kasuga_lib/data_driven/`、`assets/<namespace>/models/model_proxy.json`、`modules/modelling/run/client/`、`build/reports/modelAssemblyProbe/report.json` |
| Gradle 任务与属性 | `:modules:modelling:modelUnitTest`、`:modules:modelling:renderGlTest`、`renderingReleaseCheck`、`-PkasugaTestModel=mmd`、`-PkasugaClientDirectory`、`-Dkasuga.renderTestModels=false` |
| 日志串 / 成功标识 | `Loaded N JSON entries across M types for mod '<mod id>'`、`ANCHOR_EQUIPMENT_SMOKE_PASS`、`CAMERA_ANIMATION_SMOKE_PASS`、`REAL_OVERLAY_SKINNING_PASS`、`FRAME_WINDOWS_SMOKE_PASS`、`HEADLESS_CLIENT_SMOKE_PASS`、`OITVisualTestScene` |
| 渲染状态 / 阶段枚举 | `AFTER_ENTITIES`、`AFTER_TRANSLUCENT_BLOCKS`、`OPAQUE`、`MASK`、`BLEND`、`MIRROR`、`OFFSCREEN_ONLY`、`PHASE_GROUPS` / `PHASE_CONTENT` / `PHASE_EMBEDDED`、`READY / PAUSED / FAILED / CLOSED`、`ALWAYS / MANUAL / HOST_RENDERER / VANILLA_RENDERER` |
| 平台 / 驱动术语 | `F3 + T`、`EGL`、`pbuffer`、`surfaceless`、`DPI`、`read/draw FBO`、`viewport`、`fence`、`storage`、`orphan`、`glReadPixels`、`glFinish`、`glBlitFramebuffer` |

## 容易译错的词

这几组词在渲染、动画或本仓库的代码里已经另有含义，选错会与既有概念撞车。左列是应采用的写法。

| 中文术语 | ✅ 应写（英文） | ❌ 不要写成 | 说明 |
| --- | --- | --- | --- |
| 多机位 | multi-camera | `multi-view`、`multi-viewport` | `multi-view` 在渲染领域另有含义（多视口渲染），与本项目「同一客户端里多个机位顺序渲染、各自拥有世界缓存与输出」不是一回事 |
| 机位 | camera view | `camera`、`rig`、`station` | 指视点而非物理支架；`camera` 会与 `CameraType`（Player / Free / Fixed）和 MC 原生 Camera 混淆 |
| 视角 | view | `camera`（也不要用「视角」去指机位） | 「视角」指输出侧路由的 view（`view ID`、主视角 = main view），机位才是 camera view |
| 菱形闭环 | diamond closure | `diamond constraint`、`diamond loop` | `constraint` 会与本仓库通用的「约束」（关节约束、姿态约束）混淆 |
| 闭环（通用） | closure / closed loop | `closed chain` | 「链」已被 IK 链占用（`IkChain`、`links`）；闭环不是一条闭合的链，而是对两个末端施加的位置闭合约束 |
| 姿态 | pose | `posture`、`stance`、`gesture` | posture / stance 是人体的姿势，与动画的 pose 不是一个概念；代码类型名就是 `Pose`、`PoseDriver`、`ModelPosing` |
| 管线 | pipeline | `conduit`、`duct`、`line` | conduit / duct 指物理管道 |
| 挂载 | mount | `installation`、`loading`、`equip` | 安装是 install（装模组、装 JDK）；挂载是运行时注册表条目（`mount()` / `add` / `remove`）。装备挂接是 attachment，另一回事 |
| 剔除 | culling | `cropping`、`clipping`、`cutting` | clipping 专指裁剪空间与裁平面，与视锥剔除不是一回事 |
| 视锥 | frustum | `visual cone`、`view cone` | 固定说法 view frustum |
| 半透明 / 透明 | `opaque` / `mask` / `blend`，阶段用 `translucent` | `transparent`（一律） | 材质分三类 `OPAQUE` / `MASK` / `BLEND`，另有 MC 原生的 `translucent` 渲染阶段（`AFTER_TRANSLUCENT_BLOCKS`）；「半透明回放」译 translucent replay。一律写 `transparent` 会抹掉这三类的区别 |
| 光照 | lighting | `light`、`shadow` | MC 的光照系统（light engine） |
| 光影 | shader pack | `lighting`、`light and shadow` | 指 Iris shader pack（每机位有各自的 `ShaderPack`、`PipelineManager`、材质设置） |
| 冲量 | impulse | `momentum` | 动量是 momentum；代码入口 `applyImpulse` |
| 力 / 力矩 | force / torque | `strength` | 代码入口 `applyForce` / `applyTorque` |
| 写回 | writeback | `feedback`、`write into` | `feedback` 在本仓库只用于 framebuffer feedback |
| 求值 | evaluate / evaluation | `compute`、`calculation` | `compute` 是 formula 模块的算术引擎名 |
| 解析 / 解码 / 求解 | parse / decode / solve | 三者混用 | parse 解析字段与 JSON，decode 解码成对象（`Decoded` / `decodeFile`），solve 求解 IK 与物理。三个独立机制，代码各有对应名 |
| 桶 | bucket | `basket`、`pail`、`container` | 代码 `clearBucket()` |
| 域 | domain | `area`、`region`、`field` | 代码 `Diagnostics.Domain`；`region` 已被骨骼区域占用（`PartBuilder.region`） |
| 清单 | manifest | `list`、`inventory` | 代码 `resolveIndexManifests`、`IndexManifest` |
| 夹具 | fixture | `clamp`、`jig` | clamp 在数学与图形里是夹取运算，借给测试夹具会产生歧义 |
| 变体 | variant | `version`、`edition` | version / revision 专指资源版本（`long revision`），是缓存键的一部分 |
| 骨架 | skeleton | `framework` | `framework` 在本仓库指渲染框架（`uml.framework`、UML 渲染 framework） |
