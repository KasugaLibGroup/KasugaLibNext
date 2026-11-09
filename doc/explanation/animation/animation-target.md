# AnimationTarget：把动画结果写入业务目标

动画播放解决“在什么时间得到什么姿态或运动”，`AnimationTarget<O>` 解决“这个结果最后写到哪里”。例如，一个状态机可能同时驱动角色骨骼和一个悬浮特效的位置；骨骼结果写到模型的 pose 槽位，位置结果写到另一个 property 或 root 槽位。两者共享同一次逻辑提交，却拥有不同的输出类型和写入规则。

Target 是业务结果与渲染对象之间的窄接口。它不认识动画片段、FSM 或播放时间，只接收已经求值好的不可变结果。这样新增模型、输出槽位或第三方适配器时，不需要让动画控制器和渲染后端互相感知。

## 结果型 Target 的业务价值

传统实现常让每个渲染写入点持有动画源，在渲染线程重新采样片段。这样会产生三个问题：

- 同一 tick 的多个部件可能读取到不同代次的状态。
- 逻辑状态机和动画源可能各自推进时间，造成双速、倒退或循环错位。
- 物理预采样、backend 正式采样和模型更新可能重复写入同一个槽位。

结果型 Target 把逻辑步骤和渲染采样分开：

```text
逻辑阶段
  Scope commit
    -> TargetOutputEvaluator<O>.evaluate(...)
    -> TargetPublication<O>
    -> Target.publish(...)

渲染阶段
  ModelInstance.sample(RenderSampleContext)
    -> Target.sample(publication, context)
    -> 插值 O
    -> 写入该 Target 独占的模型或属性槽位
```

动画源只在逻辑提交阶段求值。渲染帧只决定 previous/current 结果之间的显示相位。

## 稳定身份

```text
AnimationTargetRef
  channel: TargetChannel
  address: String
```

`channel` 决定输出契约，`address` 在所属 Scope 内定位独占槽位。常用 channel 包括：

| channel | 业务输出 | 典型目标 |
|---|---|---|
| `kasuga:model_pose` | 骨骼、morph、material frame、IK 等组合后的姿态 | 角色、方块模型、机械臂 |
| `kasuga:part_root` | 一个刚体部件的 root 变换 | 载具轮组、转向架、独立车厢部件 |
| `kasuga:camera_pose` | 位置、朝向、roll 和光学缩放组成的镜头姿态 | `CameraAnimationPlayer` |
| `kasuga:property` | 普通数值、开关或平台适配属性 | 特效强度、扩展字段、第三方对象 |

channel 可以扩展，但第三方必须同时提供不可变输出类型、求值器、连续性规则和写入 adapter。`(channel, address)` 在一个 Scope 中只能注册一次；同一部件不能因为配置重载或重复初始化而拥有两个 root owner。

身份稳定并不意味着对象永久存在。模型资源重载后，Target 可以 rebind 到新实例；地址保持不变，后端写入对象替换，最新 publication 和逻辑进度继续保留。

## publication 表达什么

```text
TargetPublication<O>
  ref
  revision
  Active(previousOutput, currentOutput, continuity)
  or Cleared(reason)
```

publication 是“这次逻辑提交给这个目标的结果”，不包含完整播放树。`Active` 让渲染端在两个已提交结果之间插值；`Cleared` 表示目标明确失效或被移除，避免留下上一代视觉结果。

连续性由目标结果工厂决定：

| 场景 | 连续性 | 业务表现 |
|---|---|---|
| 普通逻辑步推进 | `INTERPOLATE` | 模型在两个提交结果间平滑显示 |
| 零 delta 但状态重新发布 | `HOLD_CURRENT` | 保持当前结果，不制造无意义过渡 |
| 首次发布、seek 或结果声明边界不连续 | `SNAP` | 直接采用新结果，不跨越无关时间段插值 |
| 播放停止或目标失效 | `Cleared` | 移除目标输出，等待后续重新激活 |

`revision` 用来识别同一批结果，不表示时间。一个 Target batch 中的所有 publication 具有相同 revision；渲染采样进入时捕获一个完整 batch，发布中的下一批不会混入本次采样。

## 逻辑发布与渲染采样

`TargetRegistry` 分两阶段工作：

```text
publish:
  按注册顺序 evaluate O
  为每个目标生成 publication
  先完成整批结果
  再原子替换 latest render batch

sample:
  捕获一个完整 batch
  检查 RenderFrameToken
  插值每个目标的结果
  写入各自独占槽位
```

注册顺序用于稳定诊断和确定性发布。单个 Target 求值、发布或采样失败会被隔离，并记录错误；其他 Target 仍可完成同一步。已经提交的逻辑 revision 不因某个模型写入失败而回滚。

同一模型、同一 `RenderFrameToken` 的重复采样必须 no-op。这保证物理预采样和 backend 正式渲染不会重复写姿态。模型级入口按稳定注册顺序采样；同一 batch 同时包含 root 与 pose 时，composition root 负责先注册 root。两个槽位互不清理。

## 当前生产 Target

| Target | 逻辑结果 | 渲染写入 | 典型业务 |
|---|---|---|---|
| `ModelPoseTarget` | `PoseOutput` | `ModelInstancePoseSink.applyPose(...)` | FSM、显式模型姿态 Target |
| `ScopedPlayerPoseTarget` | `TimedPose` 历史 | `ModelInstancePoseSink.applyPose(...)` | `AnimationPlayer` standalone/follow |
| `ScopedCameraPoseTarget` | `TimedPose` 历史 | 产生 `CameraPose` 供相机层应用 | `CameraAnimationPlayer` standalone/follow |
| `RigidPartBinding` | `RigidPartOutput` | 一个 `ModelInstance` 的 root | 多部件载具、列车、机械结构 |

`ModelPoseTarget` 使用 `PoseOutputInterpolator` 处理骨骼、morph、离散材质帧和清除规则。`RigidPartBinding` 在逻辑阶段保存 root、局部 motion 和本地 placement，在渲染阶段组合 `root × local × motion`，只写 root，不占用 pose 槽位。
FSM 的公式轨道在逻辑步内使用冻结的 `query.*` 快照求值，渲染阶段只插值最终 Pose；非线性公式的中间值按逻辑步采样近似。FSM 跨 loop 边界时会把该步标为不连续，防止 clip 尾端向起点插值。
`ScopedPlayerPoseTarget` 与 `ScopedCameraPoseTarget` 保存当前宿主区间的已提交结果历史，
按 unwrapped 逻辑时间选择区间；两者都是私有 composition endpoint，公共入口仍是
`AnimationPlayer` 与 `CameraAnimationPlayer`。循环边界会提交 end/start 两侧结果。

parent/child 播放或 frame-domain motion 可以参与同一模型的最终结果，但 Target 只接收最终结果。播放图负责表达时间和来源，Target 负责表达输出位置和写入方式。
模型组还会让 `ModelGroupRenderPort` 在同一个锁内把当前 frame input 注入已提交 Target batch 的 `RenderSampleContext`，避免 root、tick motion 和 frame motion 来自不同发布代次。

## 生命周期与重绑

- Target 在注册时确定地址；同一地址重复注册直接失败。
- 模型重建时先准备 fresh Target，再切换 sink；失败不应让旧 owner 失效。
- `rebind` 可以更换模型实例或 sink，但不应重置 publication、revision 或播放进度。
- `close()` 必须幂等，并释放模型、sink、frame token 等运行时引用。
- Scope 关闭时只关闭已由它拥有并注册的 Target；播放实例不拥有 Target 生命周期。

这些规则把资源重载、区块卸载和视觉重建统一成“替换写入端点”的操作，而不是重建整个动画状态。

## 何时应新增 Target

以下需求适合新增 Target：

- 结果需要独立插值，例如位置、颜色、权重或摄像机参数。
- 多个播放实例可能共同产生一个最终值。
- 写入对象不属于现有 pose/root 槽位。
- 需要重绑到另一种平台对象，同时保持逻辑播放状态。

以下需求不应放进 Target：

- 判断业务条件是否满足。
- 推进状态机、RootTimeline 或播放实例。
- 扫描时间事件并创建下一条命令。
- 读取另一个 Target 的结果并递归驱动它。
- 在 render sample 中重新采样源动画。

前两类属于 Controller 或 Scope，时间和播放命令属于 Playback，资源生命周期属于 Host/Proxy。保持这些边界，Target 才能成为稳定、可测试且可独立失败隔离的输出端点。

相关实现：

- [`AnimationTarget.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/scope/AnimationTarget.java)
- [`TargetRegistry.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/scope/TargetRegistry.java)
- [`TargetPublication.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/scope/TargetPublication.java)
- [`ModelPoseTarget.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/target/ModelPoseTarget.java)
- [`ScopedPlayerPoseTarget.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/ScopedPlayerPoseTarget.java)
- [`ScopedCameraPoseTarget.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/output/camera/ScopedCameraPoseTarget.java)
- [`RigidPartBinding.java`](../../../modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/proxies/RigidPartBinding.java)
