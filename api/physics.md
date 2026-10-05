# Physics、IK 与 ModelTickLoop API

接口、配置和调用参考。原理说明见 [physics.md](../doc/physics.md)。

Kasuga modelling 的原生物理由官方 Box3D C17 引擎负责。Java 层只处理对象映射、固定步长、Minecraft
碰撞数据、动画目标与骨骼写回；没有 Java 备用积分器或接触求解器。

当前 vendored 版本固定为 Box3D 官方 `main` 提交
`30c67b5e6d0a3a66f0f506c69ce9e9e0587e3b7c`。源码与许可证分别位于
`modules/modelling/src/main/native/box3d` 和 `modules/modelling/THIRD_PARTY_NOTICES.md`。

CMake 与 Box3D 原生库都是可选能力。构建机找不到 CMake 时，Gradle 会输出 warning、跳过
Box3D JNI 编译并继续生成不含原生物理的 modelling 包。运行时可通过
`NativeBox3D.available()` 查询能力；缺少原生库时，高层 `enablePhysics`/`attach` API 返回
`null`，部署与方块生成 API 返回空结果，物理命令显示禁用原因，其余模型加载、渲染与动画功能不受影响。

## 1. 每帧求值顺序

所有程序化阶段都挂在 `ModelTickLoop` 这一条有序管线上。框架通过标准槽位提供排序，
物理、IK 等模块只实现自身内部逻辑：

```text
kasuga:apply -> [user pre-IK modules] -> kasuga:ik -> [user post-IK modules]
  -> kasuga:physics -> [user post-physics modules] -> kasuga:anchor
```

带物理的模型一帧内的完整数据流：

```text
PoseDriver.sample(partialTick)
  -> SkeletonApplyModule（flush pending transforms）
  -> pre-IK modules（写 transient IK target；需要 flush pending 的模块应放在 apply 前）
  -> IkModule（层级求值 + 通用 Caliko IK；物理接管姿态时跳过）
  -> post-IK modules（读同帧 IK 结果、提交 controller force）
  -> RagdollModule（Box3D fixed steps + bone writeback）
  -> post-physics modules（观察最终姿态）
  -> AnchorModule（重算显示子物体锚点世界变换）
  -> renderer upload
```

`MinecraftRagdollRuntime` 在 `RenderFrame.Pre` 中先采样动画再推进物理，backend 随后的 `sample` 会消费
本帧已准备标记，不会用动画重新覆盖物理结果。动画/IK 目标按输入 mutation epoch 求值和缓存，
输入不变时，fixed steps 复用同一动画目标；原生的力累计与 fixed-step 执行仍由 world 管理。

手动模式可以调用：

```java
instance.animate(gameTickSeconds);
instance.sample(partialTick);
instance.simulatePhysics(frameSeconds);   // 内部走 tickLoop.tick(dt)
instance.tick(frameSeconds);              // 直接推进整条管线
```

需要一次完成动画采样和物理推进时使用：

```java
instance.evaluatePhysicsFrame(partialTick, frameSeconds);
```

## 2. 通用刚体 API

主要包：

```text
lib.kasuga.rendering.models.uml.dynamic.physics.core
```

### 2.1 Shape 与 body

`BodyShape` 是 body-local 的 sealed API：

- `BodyShape.Sphere(center, radius)`
- `BodyShape.Box(center, rotation, halfExtents)`
- `BodyShape.Capsule(centerA, centerB, radius)`

`SimBody.collisionShapes()` 可返回任意数量的 child shape，因此一个 native body 可以是 compound body。
兼容字段 `shape()` 与 `shapeSizeRef()` 仍会自动映射成一个居中 primitive。

`GenericRigidBody` 是非 PMX 内容的默认实现：

```java
GenericRigidBody sphere = GenericRigidBody.sphere(0.3f, 2f)
        .at(0f, 4f, 0f)
        .friction(0.7f)
        .restitution(0.1f)
        .damping(0.05f, 0.1f)
        .filter(2, 1 << 5);

GenericRigidBody compound = GenericRigidBody.compound(List.of(
        new BodyShape.Box(new Vector3f(-0.5f, 0f, 0f), new Vector3f(0.25f)),
        new BodyShape.Box(new Vector3f( 0.5f, 0f, 0f), new Vector3f(0.25f))
), 4f);

GenericRigidBody movingPlatform = GenericRigidBody.kinematic(List.of(
        new BodyShape.Box(new Vector3f(1f, 0.1f, 1f))
));
```

运行时状态方法包括 `position()`、`rotation()`、`teleport(...)`、`setLinearVelocity(...)` 和
`setAngularVelocity(...)`。`SimBody` 的 `*Ref()` 方法返回内部可变存储，只供高频适配与状态同步使用。

### 2.2 World 生命周期与固定步长

```java
try (RigidBodyWorld world = new RigidBodyWorld(
        List.of(sphere, compound), List.of(), RigidBodyWorld.DEFAULT_SUBSTEP_COUNT)) {
    world.setSimulationHertz(120f);
    world.setGravity(new Vector3f(0f, -9.80665f, 0f));
    world.addGroundPlane(0f, 0.8f, 0f);
    world.step(deltaSeconds, RigidBodyWorld.KinematicDriver.none());
}
```

对象与关节管理：

- `add(SimBody)` / `remove(SimBody)`
- `add(BallJoint)` / `remove(BallJoint)`（PMX/gltf ragdoll 使用的球关节）
- `add(PhysicsJoint)` / `remove(PhysicsJoint)`（weld/distance/revolute/prismatic，见 2.2.1）
- `bodies()` / `joints()` / `physicsJoints()`
- `excludePair(bodyA, bodyB)`
- `close()`

移除 body 时，挂在它上面的两类关节都会自动销毁。

### 2.2.1 通用关节类型

Box3D 的四种常用关节已通过 JNI 完整移植（约束求解全部在 native 内）：

```java
// Weld：把两个 prop 刚性粘接（0 hertz = 最硬），可调软硬度
WeldJoint weld = WeldJoint.atCurrentPose(blockA, blockB).build();
world.add(weld);

// Distance：绳/弹簧/绞盘，支持 spring、长度限制和 motor
DistanceJoint rope = DistanceJoint.between(anchor, payload)
        .length(1f)
        .spring(true, 3f, 0.9f)
        .limits(true, 0.5f, 1.5f)
        .motor(true, 1f, 50f)      // 绞盘收绳
        .build();

// Revolute：铰链（门、盖、风车），可带角度限制与电机
RevoluteJoint hinge = RevoluteJoint.between(frame, door)
        .hingeAxis(new Vector3f(0, 1, 0))
        .limits(true, -(float) Math.PI * 0.25f, (float) Math.PI * 0.25f)
        .motor(true, -6f, 40f)
        .build();

// Prismatic：滑轨（活塞、抽屉），可带行程限制与电机
PrismaticJoint piston = PrismaticJoint.between(rail, slider)
        .slideAxis(new Vector3f(1, 0, 0))
        .limits(true, -1.2f, 1.2f)
        .motor(true, 2f, 200f)
        .build();
```

运行时调参走各关节的 setter（`setLimits`、`setMotor`、`setSpring`、`setLength`）；
未绑定到 world 的关节调用 setter 会抛出 `IllegalStateException`。

`KinematicDriver.beginStep(world)` 在每个 fixed step 前执行；`kinematicTarget(body)` 返回同一步的目标
位置和旋转。`lastFixedStepCount()`、`droppedSimulationTime()` 与 `interpolationAlpha()` 用于诊断固定步长。

主要调参：

- `setSimulationHertz(10..1000)`
- `setSubstepCount(1..50)`
- `setMaxFixedStepsPerUpdate(1..1000)`
- `setConstraintTuning(hertz, dampingRatio)`
- `setSpeedLimits(linear, angular)`；当前 Box3D 只提供 world linear speed 映射
- `setRestitutionThreshold(value)`
- `setSleepingEnabled(boolean)` / `setSleepingThresholds(...)` / `wake()`
- `setContinuousCollisionEnabled(boolean)`
- `setCollisionsEnabled(boolean)`
- `setSelfCollisionsEnabled(boolean)`

`setSolverIterations` 是旧配置兼容入口。Box3D 不公开同名迭代数，因此它只校验和保存值。

### 2.3 Impulse、force 与 body 控制

所有向量均为世界坐标：

```java
world.applyImpulse(body, impulse);
world.applyImpulse(body, impulse, worldPoint);
world.applyAngularImpulse(body, angularImpulse);

world.applyForce(body, force);
world.applyForce(body, force, worldPoint);
world.applyTorque(body, torque);

world.setGravityScale(body, 0.25f);
float scale = world.gravityScale(body);
world.setAwake(body, true);
boolean awake = world.awake(body);
```

Impulse 立即改变速度；force/torque 由下一个 native Box3D step 积分。方法在 body 不属于该 world、
body 不是 dynamic 或输入非有限数时返回 `false`。负 gravity scale 会反转该 body 的重力。

### 2.4 Collision、contact 与 query

静态环境入口：

- `addPlaneCollider(normal, offset, friction, restitution)`
- `addGroundPlane(y, friction, restitution)`
- `addStaticBoxCollider(minimum, maximum, friction, restitution)`
- `addEnvironmentMesh(friction, restitution)`
- `setCollisionEnvironment(CollisionEnvironment)`

`StaticEnvironmentMesh` 通过 `putCell/removeCell` 增量同步 Minecraft terrain，Java 不做碰撞求解。

查询：

```java
Optional<RayHit> hit = world.raycast(origin, direction, maximumDistance);
List<BodyContact> contacts = world.contacts(body);
```

`RayHit` 返回 Java `SimBody`、世界命中点与距离。`BodyContact` 字段：

| 字段 | 含义 |
| --- | --- |
| `body` | 查询视角中的 body |
| `other` | 另一 Java body；native static terrain 时为空 |
| `point` | 世界接触点 |
| `normal` | 从 `other` 指向 `body` 的单位法线 |
| `separation` | 负值为穿透 |
| `normalImpulse` | 当前 solver point 的 normal impulse |
| `totalNormalImpulse` | 包含累计 warm-start 的 normal impulse |
| `normalVelocity` | pre-solve normal velocity |
| `touching()` | 分离量或 impulse 表明当前接触 |

汇总计数可用 `selfContactCount()` 与 `staticContactBodyCount()`。

### 2.5 Ray drag

```java
world.setDragSettings(DragSettings.DEFAULT);
world.beginDrag(hit);                 // 或 beginDrag(body, worldPoint)
world.updateDragTarget(target, frameSeconds);
world.endDrag();
```

拖拽在 native Box3D 中创建 kinematic anchor 与 spherical joint，不 teleport body，也不运行 Java 稳定器。
当前一个 world 同时只有一个 drag target。

## 3. PMX 与 glTF ragdoll API

```java
MmdRagdoll ragdoll = instance.enablePhysics();
// 或只生成 profile 中注册的主体胶囊
MmdRagdoll profiled = instance.enablePhysics(profile);
```

查询：

- `bodies()` / `joints()`
- `body(definitionIndex)`：通用 bodies 列表索引；MMD Reader 保持原刚体顺序，glTF Reader 保持 node 顺序
- `body(Bone)` / `body(boneName)`
- `animationTarget(body)`：本帧 animation + IK 后、physics 前的 body 目标
- `raycast(...)`

`MmdRagdoll` 直接转发通用 impulse、force、torque、gravity、collision、drag、fixed-step、sleep 与
environment 控制。`Body` 另提供 `position()`、`rotation()`、`shapeSize()`、速度、`toWorldPoint(...)`、
`toLocalPoint(...)`、`teleport(...)` 和速度 setter。

模式语义：

- PMX mode 0：kinematic，跟随动画骨骼。
- PMX mode 1：dynamic，完整物理写回。
- PMX mode 2：dynamic，保留动画平移并写回物理旋转。
- profile ragdoll：PMX 与 glTF 都可按公共 UML 骨骼段生成胶囊和语义质量；默认关闭内部
  self-collision。PMX 可通过 `include_secondary_bodies` 保留作者制作的裙摆/头发刚体链；这些
  次级刚体在独立的 collision group/mask 命名空间中保留 PMX 内部过滤关系，并通过 kinematic
  proxy 单向挂到主体，不继承主体的负 group 过滤，也不会反向拖动主体。glTF 必须提供显式 profile，
  不会从骨骼名猜测人体拓扑。

配置格式、profile role 与 Minecraft 部署详见 [mmd-ragdoll.md](mmd-ragdoll.md)。

MMD/glTF ragdoll 使用每实例的 double 世界锚点和 origin-local float 物理坐标。Minecraft 方块网格、
射线、鼠标拖拽和实体挂点都会先减去该锚点；渲染也使用 double 的相机相对位移。这避免在十万格到
世界边界范围内把小型碰撞体、骨骼段和关节误差放大到厘米甚至整格。

## 4. IK API

`SkeletonInstance` 支持持久外部目标：

```java
skeleton.setIkTarget("右足ＩＫ", worldTarget, 1f);
skeleton.clearIkTarget("右足ＩＫ");
skeleton.clearIkTargets();

skeleton.setIkEnabled("右足ＩＫ", false);
skeleton.resetIkEnabled();
```

`weight` 范围为 `[0,1]`，在该链 controller 位置与外部世界目标之间混合。名称表示通用 IK 链名，
未定义链返回 `false`。完整构造、限位、闭环与诊断见 [Skeleton Dynamics API](skeleton-dynamics.md)。

`setFrameIkTarget(...)` 与 `clearFrameIkTargets()` 是 tick loop 的瞬态入口。`ModelTickLoop.tick`
在每次求值开始时清空，pre-IK 模块重新写入后供同一次求值中的 IK 与物理共同消费；普通调用方通常应使用
`IkTargetModule` 而不是直接管理生命周期。

## 5. ModelTickLoop 模块

每个 `ModelInstance` 自带一条 `ModelTickLoop` 管线（`instance.getTickLoop()`），默认安装四个内置模块：

| 槽位 | 模块 | 职责 |
| --- | --- | --- |
| `kasuga:apply` | `SkeletonApplyModule` | 把 pending transforms flush 进骨架；identity 槽位跳过，不覆盖动画写好的骨骼 |
| `kasuga:ik` | `IkModule` | 层级求值 + 通用 Caliko IK；物理启用时由物理接管姿态，此槽自动跳过 |
| `kasuga:physics` | `RagdollModule` | 推进 MmdRagdoll（Box3D）并写回；未启用物理时 no-op |
| `kasuga:anchor` | `AnchorModule` | 重算锚点世界变换并通知显示子物体 |

用户模块用类型化助手挂到标准槽位之间，排序完全由管线提供：

```java
ModelTickLoop loop = instance.getTickLoop();
loop.addPreIk("my-target", module);      // 默认 apply 之后、IK 之前
loop.addPostIk("my-controller", module); // IK 之后、物理之前
loop.addPostPhysics("my-observer", module); // 物理之后、锚点之前
loop.module(ModelTickLoop.SLOT_ANCHOR, AnchorModule.class); // 取内置模块实例
```

需要写 pending transforms 并让默认 apply 消费时，用
`loop.getPipeline().addBefore(ModelTickLoop.SLOT_APPLY, "my-pose", module)`。
也可以通过 `loop.getPipeline()` 使用 `addFirst/addLast/addBefore/addAfter/remove` 做任意插入。
`tick()` 开始时会清空 transient IK targets，因此它们的生命周期精确为一 tick。

### 5.1 `IkTargetModule`

```java
IkTargetModule hand = new IkTargetModule("右手ＩＫ", new Vector3f(1f, 2f, 0f), 1f);
instance.getTickLoop().addPreIk("hand", hand);

hand.setTarget(new Vector3f(1.2f, 2f, 0f));
hand.setTarget(target, 0.6f);
hand.setEnabled(false);
```

它只在 pre-IK 阶段写瞬态世界目标，不修改 authored animation 数据。

### 5.2 `ActiveRagdollModule`

该 controller 在 post-IK 阶段将 dynamic body 拉向同帧动画/IK body target：先调用
`MmdRagdoll.evaluateAnimationTarget()` 刷新 kinematic target，再计算 PD spring 的 force/torque；
积分、碰撞、joint、sleep 仍全部由 Box3D 在 physics 阶段执行。

控制全部 dynamic body：

```java
ActiveRagdollModule active = new ActiveRagdollModule(
        5f,    // frequency Hz
        1f,    // damping ratio
        800f,  // maximum force
        250f   // maximum torque
);
instance.getTickLoop().addPostIk("active-ragdoll", active);
```

只控制指定骨骼 body：

```java
var upperBody = new ActiveRagdollModule(
        List.of("上半身", "首", "頭"), 6f, 1f, 600f, 180f, 0.7f);
```

运行时可使用 `setWeight`、`setSettings` 与 `setEnabled`。空骨骼集合表示全部 dynamic body。

`IkTargetModule` 与 `ActiveRagdollModule` 可以同时使用：前者先修改目标，Caliko 求解通用 IK 链，后者再让
Box3D body 追随 IK 后姿态。这是 animation、IK 与 active physics 的推荐组合。

### 5.3 `AnchorModule` 与显示子物体

模型库中的 `Anchor`（`uml.structure.skeleton.Anchor`）是绑定在蒙皮骨骼上的显示子物体锚点，
例如玩家手中拿的剑。`SkeletonInstance.anchorTransform(name)` 按 BDEF 蒙皮同样的规则混合
绑定骨骼的 bind→current delta 得到锚点世界变换：

```java
// 每帧把手部锚点的世界变换交给宿主渲染逻辑
instance.attachToAnchor("hand_r", worldTransform -> {
    if (worldTransform == null) { /* 锚点失效，隐藏持物 */ return; }
    placeHeldItem(worldTransform.getPosition(), worldTransform.getRotation());
});
instance.detachFromAnchor("hand_r", attachment);
```

`AnchorModule` 在每条管线的最后阶段（`kasuga:anchor`）推送这些变换，因此锚点永远跟随最终姿态
（含 IK 与物理）。也可以直接调 `instance.anchorTransform(name)` 立即查询。

## 6. glTF 动画兼容

`.glb` 与 `.gltf` 已注册为内置资源管线。parser API 位于
`lib.kasuga.rendering.models.uml.typo.gltf`：

```java
GltfAsset asset = GltfLoader.load(path);                    // 不读动画 track
GltfAsset animated = GltfLoader.load(path, Set.of("walk"));
GltfAsset all = GltfLoader.loadAllAnimations(path);
Model model = GltfModelConverter.convert(all);
```

`GltfModelConverter` 将全部 glTF node 合并到统一 UML Skeleton，并把每个 skin 的 `JOINTS_0` 重映射到
全局 Bone，所以现有 CPU/GPU skinning 与 tick loop 模块可直接使用。Minecraft 资源管线会把 embedded
base-color 图片放入现有 texture atlas。

```java
ModelInstance instance = ...;
GltfAnimationPoseDriver animation = new GltfAnimationPoseDriver(instance);
instance.setPoseDriver(animation);
animation.play("walk", true);
```

driver 支持 translation/rotation/scale、STEP、LINEAR 与 CUBIC_SPLINE。资源包流式 `.gltf` 只支持
embedded/data URI；需要外部 `.bin` 或图片 URI 时使用 `Path` API，或打包为 `.glb`。

Minecraft 中可在模型旁放置 `<model>.gltf.json` manifest：

```json
{
  "model_scale": 1.3,
  "ragdoll": "example:ragdolls/character.json"
}
```

`model_scale` 同时作用于顶点、骨骼和生成的 ragdoll；`ragdoll` 指向 profile/模拟配置。部署逻辑只读
manifest，不按文件名或模型名称选择 profile。通用命令为：

```text
/kasuga_ragdoll deploy example:models/character.glb
```

## 7. Minecraft physics block

`MinecraftBlockRigidBody` 将 `BlockState#getCollisionShape` 的每个 voxel AABB 转成一个
body-local `BodyShape.Box`，而不是统一使用完整单位方块。因此 slab、fence、柱体和组合形状会保留真实
碰撞空隙。visual 仍由 vanilla block renderer 绘制。

`MinecraftBlockPhysics` 使用一个共享 Box3D world：

- physics blocks 之间发生真实 native collision；
- terrain 由 `MinecraftBlockRagdollEnvironment` 增量同步为 static Box3D boxes；
- 碰撞分组：props 位于 group 1；local player AABB 是一个 group 15 的 dynamic proxy，并通过
  non-collision mask 排除 group 0（terrain 与其它默认 body），因此玩家与地形之间的求解噪音不会
  经由 proxy 传给贴地的 prop。spectator 与死亡玩家不创建 proxy；
- 地面手感由接触回写合成：修正量只取最深接触的 MTV（按朝向拆分——顶面接触只做垂直抬升、
  不产生水平漂移；侧面接触像真实墙体一样挡住行走），proxy 自身的求解位移不反馈给玩家，
  避免行走时被求解器反推"打滑"；支撑成立时每帧重设 onGround 并清零 fallDistance
  （vanilla 每 tick 会因脚下无真实方块而清除该状态），使地面摩擦、跳跃与摔落伤害判定与
  真实方块一致；
- 默认上限 `MAX_PROPS = 256`，掉出世界或切换维度自动释放；
- 空 collision shape 的 block 不会生成。

调试命令：

```text
/kasuga_physics block [1-32]
/kasuga_physics status
/kasuga_physics clear
```

这是客户端 playground。它对本地玩家表现为可推、可站立的 collision prop，但不是服务端注册的
Block/Entity，不参与服务端权威碰撞、保存、区块序列化或多人同步。若需要“像正常方块”包含这些网络和
存档语义，应在上层增加服务端 entity 与同步协议；不要把当前 local collision bridge 当作反作弊安全边界。

## 8. 当前限制

- Box3D 没有内建 Minecraft 流体体积/浮力 API；旧 Java 浮力模型已删除。
- PMX 六轴 joint 当前映射为 spherical joint；旋转盒近似为 cone/twist，三轴平移收紧为固定 anchor。
- 一个 world 当前只有一个 drag constraint。
- glTF morph targets、第二套 skin weights、非 triangle primitive 与材质扩展尚未转换。
- glTF parser 保存 metallic/roughness factor，但当前 Minecraft adapter 只直接上传 base-color；Kasuga 的
  stylized PBR 转换仍使用 [PBR.md](PBR.md) 中的独立规则和 atlas baker。

渲染 pipeline、shader 与 effect API 见 [EFFECT_RENDERING.md](EFFECT_RENDERING.md)。
