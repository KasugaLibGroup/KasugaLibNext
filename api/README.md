# API Reference

接口签名、构造参数、配置 schema、调用示例和开发命令统一放在本目录。
算法、模块边界、数据流及资源所有权的解释集中在 [doc/](../doc/README.md)。
引用按当前源码整理；历史性能/客户端验收记录会标明当时的环境，文档迁移不表示本次重新验证。

## 模型、骨骼与动画

| 文档 | 内容 |
| --- | --- |
| [Skeleton Dynamics](skeleton-dynamics.md) | 通用刚体/关节、Reader/Builder、IK 限位与反向映射、菱形闭环、实例控制和诊断 |
| [Physics 与 tick loop](physics.md) | 原生 world、复合形状、关节、力/冲量、环境、拖拽、主动布娃娃、锚点和方块物理 |
| [MMD/glTF 配置与部署](mmd-ragdoll.md) | model manifest、profile 字段、角色限位、运行时部署和命令 |
| [模型加载与渲染（中文）](modelling/README.zh-CN.md) / [English](modelling/README.md) | 资源格式、model_proxy、实例创建、Blockbench 与运行入口 |
| [摄像机与动画](camera-animation.md) | 直接控制、关键帧、JSON、共享时间轴与模型片段绑定 |
| [FSM](fsm.md) | 状态变量、层/状态/转移、姿态 schema 与宿主绑定 |
| [Multiplexer](multiplexer.md) | 变体选择 DSL、Builder、实例状态及 FSM 组合 |

## 渲染与输出

| 文档 | 内容 |
| --- | --- |
| [自定义渲染、Effects 与 Shader DSL](EFFECT_RENDERING.md) | 注册/作用域、管线描述、shader、参数、DSL、效果和后处理图 |
| [UML framework](uml-render-framework.md) | backend/context/factory、调度与 buffer 的公共接口和生命周期契约 |
| [后端扩展](rendering-backends.md) | 独立后端模块、宿主帧上下文、上传与输出适配 |
| [调度与模型句柄](render-scheduling.md) | McModelHandle、可见性、Entity/BlockEntity renderer 接入 |
| [最终画面输出](frame-output.md) | 订阅、共享帧、MIRROR/OFFSCREEN_ONLY、异常与关闭 |
| [多机位、窗口与 headless](offline_rendering_and_multi_cam.md) | 相机生命周期、资源包、独立窗口、启动、FBO 消费和录制 |
| [PBR](PBR.md) | 玩家 JSON、Java 转换规则、上下文、优先级和诊断命令 |
| [Alpha/OIT](render-alpha-passes.md) | 材质 pass、调试场景、命令、快捷键和验收指标 |
| [体积云](volumetric-clouds.md) | 多层天空配置、单云实例、质量、预览与验证入口 |

## 数据与工具

| 文档 | 内容 |
| --- | --- |
| [数据驱动注册](data-driven-registration.md) | 内容 schema、工厂与属性扩展、诊断与 reload；实际内容在 [../doc/data-driven/](../doc/data-driven/intro.md)（五篇） |
| [Formula（中文）](formula/README.zh-CN.md) / [English](formula/README.md) | 表达式、Namespace、函数/变量、运算符和错误 |
| [构建与验证入口](verification.md) | Gradle 任务、独立 GL、客户端验收与证据边界 |

## 文档维护

新增可调用接口时在本目录补全包名、签名、单位、默认值、错误条件与生命周期，并链接到对应原理。
修改接口时同步调用示例与配置表。迁移或改名时更新本索引、根 README、相对链接与锚点。
原理文档可以使用类型名、数学表达式和流程图解释机制；方法参考、配置表和操作步骤在本目录维护。
