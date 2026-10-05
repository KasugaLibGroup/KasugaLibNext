# 原理与设计

本目录解释算法、数据流、模块边界、时序和资源所有权。具体接口、参数、schema、接入代码与命令
集中在 [API reference](../api/README.md)。

| 主题 | 原理文档 |
| --- | --- |
| 共享骨骼定义、Caliko 逆向映射和菱形闭环 | [Skeleton Dynamics](skeleton-dynamics.md) |
| 固定步长、原生求解、动画目标与物理写回 | [Physics](physics.md) |
| 作者物理、主体 profile、次级运动与大坐标 | [MMD/glTF](mmd-ragdoll.md) |
| 摄像机分量轨道、光学缩放与共享时钟 | [Camera Animation](camera-animation.md) |
| 分层状态、动作与姿态混合 | [FSM](fsm.md) |
| 共享变体定义与每实例选择状态 | [Multiplexer](multiplexer.md) |
| 描述/执行分离、shader 准备、效果和后处理图 | [自定义渲染](EFFECT_RENDERING.md) |
| 层级职责、挂载发布和在途 GPU 读取 | [UML Framework](uml-render-framework.md) |
| 核心、图形 API 与宿主适配边界 | [Rendering Backends](rendering-backends.md) |
| 原版可见性、逐视角标记与采样去重 | [Render Scheduling](render-scheduling.md) |
| 最终 blit、只读借用和共享捕获 | [Frame Output](frame-output.md) |
| 同进程机位隔离、世界订阅与 GPU 同步 | [多机位与离屏](offline_rendering_and_multi_cam.md) |
| 透明 pass、WBOIT 与深度合成 | [Alpha Rendering](render-alpha-passes.md) |
| 风格化纹理、材质变体与分代缓存 | [PBR](PBR.md) |
| 天气密度层、光线步进与遮光 | [体积云](volumetric-clouds.md) |
| 内容树、属性转换、工厂与注册分派 | [数据驱动注册](data-driven-registration.md) |
