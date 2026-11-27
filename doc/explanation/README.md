# 原理与设计

本目录解释算法、数据流、模块边界、时序和资源所有权。具体接口、参数、schema、接入代码与命令
集中在 [API reference](../reference/README.md)。

## 架构与线程

| 主题 | 原理文档 |
| --- | --- |
| 线程封闭、有界/无锁跨线程移交与分代原子发布 | [线程模型](threads.md) |

## 模型、物理、动画与 Proxy

| 主题 | 原理文档 |
| --- | --- |
| 格式 Reader 与公共子系统、姿态组合和穿搭缓存边界 | [Model 子系统](model-subsystems.md) |
| 共享骨骼定义、Caliko 逆向映射和菱形闭环 | [Skeleton Dynamics](skeleton-dynamics.md) |
| 固定步长、原生求解、动画目标与物理写回 | [Physics](physics.md) |
| 作者物理、主体 profile、次级运动与大坐标 | [MMD/glTF](mmd-ragdoll.md) |
| 摄像机分量轨道、光学缩放与共享时钟 | [Camera Animation](camera-animation.md) |
| 分层状态、动作与姿态混合 | [FSM](fsm.md) |
| 共享变体定义与每实例选择状态 | [Multiplexer](multiplexer.md) |
| 逻辑时间、输入/控制事务与同代结果发布 | [动画作用域](animation/animation-scope.md) |
| 类型化输出槽位、连续性与安全重绑 | [动画目标](animation/animation-target.md) |
| 元素适配、注册/会话产权、类型化 Port、tick/frame 边界与生命周期 | [Proxy](proxy/README.md) |

## 渲染与输出

| 主题 | 原理文档 |
| --- | --- |
| 描述/执行分离、shader 准备、效果和后处理图 | [自定义渲染](effect-rendering.md) |
| 层级职责、挂载发布和在途 GPU 读取 | [UML Framework](uml-render-framework.md) |
| 核心、图形 API 与宿主适配边界 | [Rendering Backends](rendering-backends.md) |
| 原版可见性、逐视角标记与采样去重 | [Render Scheduling](render-scheduling.md) |
| 屏幕反投影、世界 UI 与输入所有权 | [World UI Input](world-ui-input.md) |
| 最终 blit、只读借用和共享捕获 | [Frame Output](frame-output.md) |
| 同进程机位隔离、世界订阅与 GPU 同步 | [多机位与离屏](offline-rendering-and-multi-cam.md) |
| 透明 pass、WBOIT 与深度合成 | [Alpha Rendering](render-alpha-passes.md) |
| 风格化纹理、材质变体与分代缓存 | [PBR](pbr.md) |
| 天气密度层、光线步进与遮光 | [体积云](volumetric-clouds.md) |

## 数据驱动系统

| 主题 | 原理文档 |
| --- | --- |
| 内容树、属性转换、工厂与注册分派概览 | [数据驱动注册](data-driven-registration.md) |
| v2 固定来源、双注册入口、四数据域与关键不变量总览 | [KasugaLib 数据驱动系统](data-driven/README.md) |
| 固定来源、注册域、reload 域、`prepare`/`build` 与启动时序 | [概念与生命周期](data-driven/01-concepts-lifecycle.md) |
| 标准布局、Scene indexing、main/parcel roots、manifest 与严格 JSON | [索引与 JSON 格式](data-driven/02-index-and-content-format.md) |
| `TypeHandler` 生命周期、identity、依赖可见性、校验与 registry claims | [TypeHandler 与预检](data-driven/03-handlers-and-preflight.md) |
| `ReloadOrchestrator`、包栈发现、FSM handler、去重与诊断 | [ReloadHandler 与资源重载](data-driven/04-reload-pipeline.md) |
| `ResourceSource`、`ResourceSnapshot`、`ContentSourceFactory.snapshot` 与限额 | [内容来源、快照与安全](data-driven/05-content-sources.md) |
| 全局 rank、`prepare`/`build`、LEGACY/STRICT、诊断、finalizer 与 provider | [多来源构建管线](data-driven/06-build-pipeline.md) |
| `FactoryRegistry`、派生 claim、`dispatchWhen`、固定根与 MC 资源投影 | [工厂、注册组与宿主接线](data-driven/07-factories-groups-integration.md) |
| 常见症状、错误定位、旧入口兼容与迁移检查表 | [排查与迁移](data-driven/08-troubleshooting-migration.md) |

## Create 集成

| 主题 | 原理文档 |
| --- | --- |
| Create 车站装配、多转向架捕获、折叠、持久化与同步、弯道贴轨与出轨判定 | [多转向架列车车厢](create/multi-bogey-carriage.md) |
| 姿态事件、车厢权威值、客户端追赶、持久化与渲染消费 | [车厢垂直姿态](create/carriage-posture.md) |
