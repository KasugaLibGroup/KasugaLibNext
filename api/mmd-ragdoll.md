# PMX 与 glTF 布娃娃配置

接口、配置和调用参考。原理说明见 [mmd-ragdoll.md](../doc/mmd-ragdoll.md)。

物理定义、IK 与菱形闭环现统一挂在 `Skeleton.getDynamics()` 下。扩展 Reader/Builder 和
Caliko 接入说明见 [skeleton-dynamics.md](skeleton-dynamics.md)。`SkeletonRagdoll` 保留历史 API 名称，
运行时只读取通用骨骼定义。

`SkeletonRagdoll` 有两种构建方式：

- `enablePhysics()` 使用 PMX 中全部刚体，因此会包含裙摆、头发等次级物理刚体。
- `enablePhysics(profile)` 可用于 PMX 或 glTF，使用 profile 的 `bodies` 生成稳定的人形胶囊和
  语义质量；PMX profile 可通过 `include_secondary_bodies` 同时保留未登记的裙摆、头发、饰品等
  作者刚体链。惯量由 Box3D 根据 shape 与质量生成。glTF 不允许无 profile 自动猜测人体结构。

物理求解由 vendored Box3D C17 引擎完成。`uml/dynamic/physics/core` 中的
`RigidBodyWorld`、`SimBody`、`GenericRigidBody` 和 `BallJoint` 只负责 Java 对象映射、
固定步长、生命周期与状态同步；`SkeletonRagdoll` 是 PMX/骨骼适配器。任何代码都可以直接创建世界：

## 大坐标精度

启用 `SkeletonRagdoll` 时，模型根节点的世界平移会从 float 骨骼矩阵中移出，作为独立的 double
`worldOrigin` 保存。骨骼求值、Box3D 刚体、关节以及缓存的方块碰撞均使用原点附近的局部坐标；
渲染阶段先以 double 计算 `worldOrigin - cameraPosition`，然后才写入 float pose matrix。

`Body.position()` 与 `RayHit.point()` 是模拟局部坐标；需要世界位置时使用
`ragdoll.worldPosition(body)`。相机、实体等 double 世界坐标应通过 `raycastWorld(...)`、
`beginDragWorld(...)`、`updateDragTargetWorld(...)`、`addStaticBoxColliderWorld(...)` 或
`worldToSimulation(...)` 进入物理世界。
`MinecraftRagdollDeployments.Request` 的带 `Vector3d worldOrigin` 构造器可保留部署位置的小数部分；
旧构造器仍兼容，但只能继承原 `Transform` 已有的 float 精度。

```java
var crate = GenericRigidBody.box(new Vector3f(0.5f), 8f).at(2f, 5f, 1f);
var world = new RigidBodyWorld(List.of(crate), List.of(),
        RigidBodyWorld.DEFAULT_SUBSTEP_COUNT);
world.addGroundPlane(0f, 0.8f, 0f);
world.step(dt, RigidBodyWorld.KinematicDriver.none());
```

## 官方实现：物理方块

客户端服务 `MinecraftBlockPhysics` 把 Minecraft 方块的真实 `VoxelShape` 转成 compound Box3D
刚体（`MinecraftBlockRigidBody`）：质量取自方块硬度、摩擦取自滑腻度，视觉用原版方块渲染器
绘制，地形碰撞复用布娃娃的增量方块环境，并通过 kinematic AABB proxy 与本地玩家交互。
完整 API 与客户端/服务端边界见 [physics.md](physics.md)。命令入口：

- `/kasuga_physics block [1-32]`：在视线前方生成物理方块。
- `/kasuga_physics status` / `clear`：查看或清除全部物理方块。

上限 `MAX_PROPS = 256`；掉出世界底部的方块自动回收，切换维度时整体清空。

## 调试命令

- `/kasuga_ragdoll deploy`：使用内置演示模型在脚下前方部署布娃娃。
- `/kasuga_ragdoll deploy <model资源位置>`：读取模型 manifest，部署任意已发布且声明了 ragdoll 的 glTF。
- `/kasuga_ragdoll list`、`remove all`：查看与卸载部署。

生命周期：维度切换/退出世界时自动清理全部部署并释放鼠标拖拽；
方块碰撞环境的失效通知由 core 的 `ClientBlockUpdateHooks`
（`ClientLevel#sendBlockUpdated` mixin）实时转发到附近环境，
周期性刷新仅作为兜底。

## Box3D 原生后端

- Box3D 固定在官方 `main` 提交 `30c67b5e6d0a3a66f0f506c69ce9e9e0587e3b7c`，
  源码位于 `modules/modelling/src/main/native/box3d`；许可证见
  `modules/modelling/THIRD_PARTY_NOTICES.md`。
- CMake 将 Box3D 静态链接进 `kasuga_box3d` JNI 库；Gradle 把当前平台产物放入
  `native/<os>-<arch>/`，运行时从 jar 解压并加载。CI 在 Linux x86-64、Windows x86-64、
  macOS ARM64 上分别构建和测试，再合并为 `kasuga-modelling-universal` 通用 jar。
- Box3D 独占积分、broad phase、窄相位、接触、CCD、休眠和关节求解；Java 不包含备用求解器。
- PMX 球、盒、胶囊直接映射为 Box3D shape。PMX 六轴关节映射为 spherical joint；
  旋转范围近似为 cone/twist，三轴平移范围当前收紧为固定锚点。
- Minecraft 方块碰撞盒映射为增量创建/销毁的 Box3D 静态 hull。无限平面由沿法线放置的
  大型静态薄盒近似。
- Box3D 没有流体体积/浮力接口，因此旧的自研流体力模型已移除；Minecraft 流体变化仍会触发
  邻接碰撞缓存刷新，但不会施加浮力或流动阻力。

Minecraft 侧可用 `MinecraftRagdollConfig.load(resourceManager, location)` 从资源包读取配置，
再调用 `config.attach(instance, levelSupplier)`。内置示例位于
`assets/kasuga_lib/ragdolls/test_mmd.json`，资源包可以覆盖它或提供新的资源位置。
当前内置渲染演示改用 `test3.mmd.zip` 中的 `TDA Bunny Miku 2.0.pmx`，其主体刚体映射位于
`assets/kasuga_lib/ragdolls/tda_bunny_miku.json`。不同 PMX 的刚体索引不可复用。

模型尺寸由压缩包旁边的 `.mmd.json` 控制。例如 `test2.mmd.zip` 对应
`test2.mmd.json`：

```json
{
  "encoding": "gbk",
  "model_scale": 0.1
}
```

`model_scale` 可以是统一比例数字，也可以是 `[x, y, z]`。省略时仍使用兼容旧资源的
`1/12`。这个比例会统一用于顶点、骨骼、刚体形状和关节位置，避免渲染与碰撞尺寸不一致。

glTF 使用相邻的 `.gltf.json` model manifest。比如 `character.glb` 对应 `character.gltf.json`：

```json
{
  "model_scale": 1.3,
  "ragdoll": "example:ragdolls/character.json"
}
```

manifest 是唯一的自动关联入口：loader 从这里取得模型比例，部署器从这里取得 ragdoll profile；
Java 代码不会按 `maribel`、`renko` 或任何骨骼名称写死选择逻辑。没有 `ragdoll` 字段的 glTF 仍可
正常渲染和播放动画，但通用 ragdoll 部署会明确失败。

## 字段

- `bodies`：主体刚体拓扑。PMX 中 `rigid_body` 与 `parent` 是 PMX 刚体索引；glTF profile 中它们是
  manifest 所属模型的 glTF node index；根节点省略 `parent`。
  MMD 适配器会优先沿真实骨骼树寻找最近的已注册物理祖先；只有找不到物理祖先时
  才使用 `parent` 桥接分离的 PMX 分支（例如同属“腰”下的“上半身”和“下半身”）。
- `include_secondary_bodies`：仅对 PMX 生效，默认 `false`。开启后，未被 `bodies` 替换且不与主体
  骨骼冲突的作者刚体及其关节会一并进入物理世界，适用于常见的骨骼驱动裙摆和头发。它不等同于
  PMX 2.1 的逐顶点 soft-body。混合 profile 下次级骨骼保留动画平移、只写回物理旋转，以保证发束和
  裙摆骨长不被主体胶囊的不同参考系拉伸。主体挂点通过随主体移动的 kinematic proxy 单向驱动
  次级链，并使用独立的 16 层碰撞命名空间，避免大量次级刚体反向拖动主体或与生成胶囊互相弹飞。
  内置 TDA 示例使用 8 个 Box3D substep；逐顶点 soft-body 当前只完成格式读取，尚未接入 Box3D
  求解与顶点写回。
- `role`：`pelvis`、`spine`、`chest`、`neck`、`head`、`shoulder`、`upper_arm`、
  `lower_arm`、`hand`、`upper_leg`、`lower_leg`、`foot` 或 `toe`。
- 顶层 `limits`：可按 role 统一声明 swing/twist 限位，避免每个 body 重复；body 自身字段优先。
- 人体 profile 默认使用 swing/twist 限位：`max_swing_degrees` 控制圆锥摆动，
  `min_twist_degrees` / `max_twist_degrees` 控制沿骨骼轴的扭转。`limit_stiffness` 是旧 profile
  兼容字段；Box3D 自行管理限位修正强度。
  三个角度字段必须一起提供。省略时使用 `role` 对应的人体默认值。
- `rotation_min_degrees` / `rotation_max_degrees`：旧的三轴 Euler 盒限位兼容路径；必须同时出现，
  且不能与 swing/twist 字段混用。
- `simulation.hertz`：固定世界步频率，不使用可变帧长直接求解。
- `simulation.update_mode`：`render_frame` 由独立 runtime 推进，不再依赖模型是否进入 backend
  的实际 draw；`manual` 由调用方使用 `MinecraftRagdollRuntime.step(instance, dt)` 或
  `instance.simulatePhysics(dt)` 推进。
- `simulation.substeps`：每个固定世界步的求解子步数。长头发、裙摆等多节次级链建议使用 `8`；
  内置 TDA 配置采用该值。
- `simulation.solver_iterations`：旧配置兼容字段；Box3D 不公开该迭代次数，当前仅校验而不改变求解。
- `simulation.constraint_hertz` / `constraint_damping_ratio`：关节约束软化参数。
- `simulation.max_linear_speed`：映射到 Box3D 世界最大线速度。`max_angular_speed` 是旧配置兼容字段；
  当前 Box3D API 没有对应的世界级设置。
- `simulation.max_fixed_steps_per_update`：单次 tick/帧更新最多补算的固定世界步，默认 `12`，
  超过预算的积压时间会被丢弃，防止一次卡顿造成持续的 physics spiral。
- `sleeping.enabled`：是否启用整组刚体休眠。调试碰撞或持续空中模拟时建议关闭。
- `sleeping.linear_speed` / `angular_speed`：按各 shape 的尺寸折算为 Box3D 每刚体的最远点速度阈值。
  `delay_seconds` 是旧配置兼容字段；实际休眠延迟由 Box3D 固定管理。
- `collision.self_collision`：同一布娃娃内部是否互撞。`false` 使用 Box3D 负 group index；主体人形建议关闭。
  为 `false` 时 Box3D 过滤整个布娃娃内部的 shape pair，而不只是忽略相邻关节。
- `collision.continuous`：是否启用扫掠碰撞，避免高速穿透。
- `environment`：Minecraft 方块碰撞缓存刷新频率、边界扩张、摩擦和恢复系数。
- `initial_state`：可选的整体初始偏移与根刚体速度，主要用于演示或生成效果。
- `dragging.enabled`：注册该实例供准星拖拽。
- `dragging.mouse_button` / `max_distance`：鼠标键（`0` 为左键）及最大拾取距离。
- `dragging.bias_rate`：映射为 Box3D 拖拽 spherical joint 的 spring hertz。其余旧拖拽调参字段
  仍可读取以兼容资源包，但不运行额外 Java 稳定器。

游戏内将准星对准模型主体并按住左键即可拖动，松开后刚体保留释放瞬间的速度。
profile 人形拾取使用按骨骼生成的胶囊；无 profile 的兼容模式仍使用 PMX 球、盒和胶囊。
抓取的是命中表面的局部点，不会 teleport 整个骨骼。鼠标约束存在期间整组刚体保持唤醒，
因此重力、关节和碰撞不会在按住拖拽时突然冻结。拖拽只对命中刚体的抓取点施加软约束，
不会再对整组刚体施加额外线性或角阻尼。
松开鼠标后，刚体只有在接触静态环境并满足低速阈值时才会正常休眠。

## 运行时部署与删除

客户端主线程可通过 `MinecraftRagdollDeployments` 动态创建实例。模型包必须已被
`models/model_proxy.json` 扫描并发布；尚未发布时 `deploy` 返回空，可在资源加载完成后重试：

```java
var request = new MinecraftRagdollDeployments.Request(
        ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/pmx/test3.mmd.zip"),
        "TDA Bunny Miku 2.0.pmx",
        ResourceLocation.fromNamespaceAndPath("example", "ragdoll/1"),
        ResourceLocation.fromNamespaceAndPath("kasuga_lib", "ragdolls/tda_bunny_miku.json"),
        new Transform().translate(x, y, z),
        true
);
RagdollDeployment deployment = MinecraftRagdollDeployments.deploy(request).orElseThrow();
```

glTF 推荐使用 manifest request，不传写死的配置路径：

```java
var request = new MinecraftRagdollDeployments.Request(
        ResourceLocation.fromNamespaceAndPath("example", "models/character.glb"),
        ResourceLocation.fromNamespaceAndPath("example", "ragdoll/1"),
        new Transform().translate(x, y, z),
        true
);
RagdollDeployment deployment = MinecraftRagdollDeployments.deploy(request).orElseThrow();
```

句柄同时提供 `instance()` 和 `ragdoll()` 供运行时施加速度或修改参数。以下两种删除方式等价，
且可重复调用：

```java
deployment.remove();
MinecraftRagdollDeployments.remove(ResourceLocation.fromNamespaceAndPath("example", "ragdoll/1"));
```

`ragdoll()` 暴露稳定的运行时控制接口：可用 `body(PMX刚体索引)`、`body(骨骼名)` 查询刚体，
并调用 impulse、force、torque、gravity scale 与 drag API。`lastFixedStepCount()`、`droppedSimulationTime()`、
`selfContactCount()`、`staticContactBodyCount()` 与 `sleepTime()` 可用于性能面板和碰撞/休眠诊断。
部署句柄还支持实体挂载：`anchorTo(entity)` 让最近的动态刚体通过软约束悬挂在
实体眼睛位置，`detachAnchor()` 释放；鼠标拖拽会自动接管锚点。

删除会从渲染 backend 和 pipeline 实例表移除模型，注销鼠标拖拽，关闭方块碰撞环境并恢复/释放
物理姿态。`removeAll()` 可用于关卡退出或调用方整体卸载。

普通 PMX 与 profile 人形都使用 Box3D 推荐的四子步。profile 仍负责骨骼胶囊、语义质量、
物理骨回写和辅助骨跟随，但所有物理约束与拖拽锚点均由 Box3D 求解。
