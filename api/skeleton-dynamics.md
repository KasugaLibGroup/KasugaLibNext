# Skeleton Dynamics API Reference

本页对应当前 `render-core` 中的通用骨骼动力学接口。数据转换在模型构建阶段完成，
运行时按 IK 链名控制各实例。算法与所有权说明见 [原理文档](../doc/skeleton-dynamics.md)。
底层 world、力、碰撞、主动布娃娃与 Minecraft 接入见 [Physics API](physics.md)，
格式 manifest/profile 见 [MMD/glTF 配置](mmd-ragdoll.md)。

## 类型与包

| 类型 | 完整包名 | 用途 |
| --- | --- | --- |
| `Skeleton`, `SkeletonDynamics` | `lib.kasuga.rendering.models.uml.structure.skeleton` | 骨骼和共享动力学定义 |
| `SkeletonDynamics.*` | 同上，`SkeletonDynamics` 的嵌套类型 | 物理、IK 和闭环数据 |
| `SkeletonDynamicsBuilder`, `SkeletonDynamicsReader<T>`, `SkeletonBuilder` | `lib.kasuga.rendering.models.uml.loaders` | 构建、校验和外部格式扩展 |
| `SkeletonInstance`, `ModelInstance` | `lib.kasuga.rendering.models.uml.dynamic` | 每实例控制和姿态求值 |
| `CalikoIkSolver` | `lib.kasuga.rendering.models.uml.dynamic.ik` | 求解适配与诊断结果 |
| `IkTargetModule` | `lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler` | 每 tick 外部 IK 目标 |
| `MmdSkeletonDynamicsReader` | `lib.kasuga.rendering.models.uml.typo.miku_miku_dance` | PMX/已转换 PMD 数据转换 |
| `GltfSkeletonDynamicsReader` | `lib.kasuga.rendering.models.uml.typo.gltf` | glTF 节点物理候选转换 |

本页代码块除独立声明外使用以下 imports；`model` 表示已经加载的 `Model`：

```java
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsReader;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import org.joml.Vector3f;
import java.util.List;
```

## 生命周期与单位

1. 加载 Model；格式 metadata 的 `configureSkeleton(Skeleton)` 钩子在 Model 构造时执行。
2. 从 Skeleton 创建动力学 Builder，追加或覆盖定义，然后 `attach()`。
3. 创建 ModelInstance；设置目标、tick loop 模块与物理。
4. 通过实例的姿态管线求值；销毁时关闭 ModelInstance 或解除物理。

动力学配置应在创建实例前完成。共享定义的集合不可修改，向量构造参数和向量访问结果均复制，
但 `Bone` 是结构引用，不能把其定义变换当作实例姿态修改。多个实例各自保存目标和求解修正。

| 数据 | 单位/坐标 |
| --- | --- |
| `RigidBody.position`, `Joint.position`、形状尺寸及平移范围 | 定义坐标，物理适配乘以 `Physics.unitScale` |
| `RigidBody.rotation`, `Joint.rotation`、旋转范围 | XYZ Euler，弧度 |
| IK 角度、`maxRotationStep`、`Rotor`、`Hinge` | 弧度 |
| IK `tolerance` | 当前求值空间的距离单位；导入/根缩放会影响实际对应尺度 |
| `setIkTarget`, `setFrameIkTarget`, `IkTargetModule` 的目标 | 世界坐标，`Vector3f`；浮动原点开启后在求值时减去 double 世界锚点 |
| `MmdRagdoll.Body.position()` 和普通物理查询 | 模拟局部坐标；使用 `worldPosition`/`*World` 接口取得或输入世界坐标 |

float 外部目标本身已受精度限制；浮动原点不会恢复在 `Vector3f` 构造时丢失的世界坐标小数。

## Skeleton 与 SkeletonDynamics

```java
SkeletonDynamics Skeleton.getDynamics();
void Skeleton.setDynamics(SkeletonDynamics dynamics);

new SkeletonDynamics(Physics physics, List<IkChain> ikChains,
                     List<DiamondConstraint> diamonds);
```

Skeleton 默认使用 `SkeletonDynamics.EMPTY`。通常通过 Builder 验证后挂载；直接构造和 setter
不会执行全部骨骼归属、链层级、关节引用与闭环校验。

| 访问器 | 返回值 |
| --- | --- |
| `physics()` | 共用物理定义 |
| `ikChains()` | 保留定义顺序的 IK 链列表 |
| `diamonds()` | 闭环约束列表 |
| `chainsByName()` | 只读 `Map<String, IkChain>`；链名必须唯一 |
| `ikChainsByBone()` | 只读 `Map<Bone, List<IkChain>>`；驱动骨到控制它的全部链的反向映射 |

## SkeletonDynamicsBuilder

```java
new SkeletonDynamicsBuilder(Skeleton skeleton);
```

构造时复制 Skeleton 已挂载的定义到 Builder；不会自动清空原 rig。

| 完整方法 | 行为 |
| --- | --- |
| `Skeleton skeleton()` | 当前目标骨架 |
| `Bone bone(String name)` | 按骨名解析结构引用；未知名称抛 `IllegalArgumentException` |
| `<T> SkeletonDynamicsBuilder read(T source, SkeletonDynamicsReader<? super T> reader)` | 同步执行 Reader，返回此 Builder |
| `SkeletonDynamicsBuilder physics(Physics definition)` | 替换整个物理定义，保留 IK/闭环 |
| `SkeletonDynamicsBuilder physics(List<RigidBody> bodies, List<Joint> joints)` | 替换 bodies/joints；单位缩放、无强制 profile、affine 写回、半径比例 1、无绑定跟随骨 |
| `SkeletonDynamicsBuilder ik(IkChain chain)` | 添加链；相同链名替换已有链 |
| `SkeletonDynamicsBuilder diamond(DiamondConstraint diamond)` | 添加约束；相同约束名替换已有约束 |
| `SkeletonDynamics build()` | 校验并返回定义；不挂载、不创建求解器或 native world |
| `SkeletonDynamics attach()` | 先 `build()`，再写入 Skeleton，返回挂载的定义 |

`build()`/`attach()` 的结构校验包括：

- 非空 body 骨、绑定跟随骨、IK controller/effector/link 都属于目标 Skeleton。
- 关节的两刚体索引有效且不同；索引对应 `Physics.bodies()`，不是 `Bone.index`。
- IK 链非空，驱动骨不重复，后续 link/effector 必须是前一 link 的后代；允许跳过中间骨。
- 闭环引用两条已存在的 POSITION 链；同一链不能属于多个闭环。
- 闭环分支不共享驱动骨、末端不同、分支 base 的绑定位置相距不超过闭环 tolerance，
  且一分支的驱动骨不能是另一 base 的祖先。

非法结构抛 `IllegalArgumentException`。必需参数为 null 时通常抛 `NullPointerException`。

## Reader 与已有 SkeletonBuilder

```java
@FunctionalInterface
public interface SkeletonDynamicsReader<T> {
    void read(T source, SkeletonDynamicsBuilder builder);
}
```

Reader 只向 Builder 填充通用定义；它本身不自动 `attach()`。外部格式可以使用任何 source 类型：

```java
Skeleton skeleton = model.getSkeleton();
SkeletonDynamicsReader<String> reader = (chainName, builder) -> builder.ik(
        new IkChain(chainName, builder.bone("hand-target"), builder.bone("hand"),
                List.of(new IkLink(builder.bone("upper-arm")),
                        new IkLink(builder.bone("forearm")))));

new SkeletonDynamicsBuilder(skeleton).read("left-arm", reader).attach();
```

已有 `SkeletonBuilder` 提供：

```java
SkeletonBuilder dynamics(Consumer<SkeletonDynamicsBuilder> configure);
```

回调在骨架 `build(...)` 的 Bone/Anchor/Vertex 绑定完成后执行，并自动校验、挂载。
重复调用 `dynamics(...)` 替换配置回调；`clear()` 同时清除它。自定义 `ModelData` 可覆盖
`void configureSkeleton(Skeleton skeleton)`，在 Model 构建时接入 Reader。
格式 metadata 的 Model 构造钩子晚于 SkeletonBuilder 回调，可能覆盖物理表或同名链；
需要覆盖格式默认值的配置应放在 Model 加载完成后、ModelInstance 创建前。

## 物理数据

### Physics

```java
new Physics(List<RigidBody> bodies, List<Joint> joints, Vector3f unitScale,
            boolean profileRequired, boolean affineWriteback,
            float profileRadiusScale, Set<Bone> bindPoseFollowers);
```

| 参数 | 契约 |
| --- | --- |
| `bodies`, `joints` | 复制后的定义列表；profile/关节引用 bodies 的列表索引 |
| `unitScale` | 三个有限正分量；用于刚体位置/尺寸和关节平移量 |
| `profileRequired` | true 时不能直接无 profile 启用该表；用于候选表 |
| `affineWriteback` | true 时在完整 affine 父层级中逆算局部物理变换，保留 scale/shear |
| `profileRadiusScale` | 有限正数；生成 profile 胶囊的半径比例 |
| `bindPoseFollowers` | 无直接物理体的跟随骨，可按最近物理祖先的绑定到当前 affine delta 跟随 |

`Physics.EMPTY`：空表、`unitScale=(1,1,1)`、`profileRequired=false`、
`affineWriteback=true`、`profileRadiusScale=1`、空跟随集合。

### RigidBody

```java
new RigidBody(String name, String alias, Bone bone, int collisionGroup,
              int nonCollisionMask, int shape, Vector3f size, Vector3f position,
              Vector3f rotation, float mass, float linearDamping,
              float angularDamping, float restitution, float friction, int mode);
```

| 参数 | 契约 |
| --- | --- |
| `name`, `alias` | 非 null 的显示/辅助名称，不要求它们是骨名；alias 可为空字符串 |
| `bone` | 可为 null，表示不绑定骨骼的体；非空时必须属于 Skeleton |
| `collisionGroup` | 整数 `0..63` |
| `nonCollisionMask` | 整数过滤掩码，按物理适配保留排除层语义 |
| `shape` | `SPHERE=0`、`BOX=1`、`CAPSULE=2` |
| `size` | 有限非负向量；球用 x 半径，盒用 xyz 半尺寸，胶囊用 x 半径/y 圆柱段完整高度 |
| `position`, `rotation` | 体在定义坐标中的位置和 XYZ Euler 朝向；均有限 |
| `mass` | 有限非负质量，0 不产生动态逆质量 |
| `linearDamping`, `angularDamping` | 有限非负阻尼 |
| `restitution`, `friction` | 有限非负材质参数 |
| `mode` | 下表中的运动/写回模式 |

| 常量 | 值 | 运行行为 |
| --- | ---: | --- |
| `KINEMATIC` | 0 | 动画目标驱动刚体 |
| `DYNAMIC` | 1 | 物理写回完整姿态 |
| `DYNAMIC_ROTATION` | 2 | 动态体，只写回骨骼旋转，保留动画平移 |

定义构造允许非负尺寸；实际创建碰撞形状时，球/胶囊半径及盒的三个半尺寸必须为正。
创建物理体可能因此进一步拒绝退化形状。

### Joint

```java
new Joint(String name, String alias, int rigidBodyA, int rigidBodyB,
          Vector3f position, Vector3f rotation, Vector3f positionMin,
          Vector3f positionMax, Vector3f rotationMin, Vector3f rotationMax,
          Vector3f positionSpring, Vector3f rotationSpring);
```

`name`/`alias` 非 null，所有向量有限并复制。`rigidBodyA/B` 在 Builder 校验时必须有效且不同。
`position/rotation` 是共同关节 frame，`*Min/*Max` 为作者限制，`*Spring` 为作者弹簧参数。
当前模型适配器把这组作者六轴参数映射为 Box3D 球形 cone/twist；平移收紧为固定锚点。
其他原生关节通过 [Physics API](physics.md#221-通用关节类型) 创建，不由此 record 的类型字段选择。

### 不带格式 metadata 的物理示例

```java
var builder = new SkeletonDynamicsBuilder(model.getSkeleton());
var body = new RigidBody("torso-body", "", builder.bone("torso"),
        0, 0, RigidBody.BOX, new Vector3f(0.2f, 0.3f, 0.1f),
        new Vector3f(0, 1, 0), new Vector3f(),
        2f, 0.05f, 0.1f, 0f, 0.6f, RigidBody.DYNAMIC);
builder.physics(List.of(body), List.of()).attach();

var instance = new ModelInstance(model, null, null, null, null, null);
var ragdoll = instance.enablePhysics();
if (ragdoll != null) {
    ragdoll.setGravity(new Vector3f(0, -9.80665f, 0));
    // 由宿主每帧调用一次；已经接入 Minecraft 运行时的实例使用该运行时推进。
    instance.evaluatePhysicsFrame(0f, 1f / 60f);
}
// 生命周期结束时：instance.detachPhysics() 或 instance.close()。
```

高层 `enablePhysics` 在 native 库不可用时返回 null；库可用但 bodies 为空、
或 `profileRequired=true` 且未传 profile 时抛 `IllegalArgumentException`。
`Body.source()` 返回此通用 `RigidBody`，`Joint.source()` 返回此通用 `Joint`。

## IK 数据与限位

### IkLink、RotationLimit 与 IkJoint

```java
new IkLink(Bone bone);
new IkLink(Bone bone, RotationLimit limit);
new IkLink(Bone bone, RotationLimit limit, IkJoint joint);
new RotationLimit(Vector3f minimum, Vector3f maximum);
new Rotor(float angle);
new Hinge(Vector3f axis, Vector3f reference, float minimum, float maximum);
```

| 类型 | 参数与行为 |
| --- | --- |
| `IkLink` | `bone` 必需；`limit`/`joint` 可为 null；两个简化构造器的缺省字段为 null |
| `RotationLimit` | 有限 XYZ Euler 上下界，逐轴 minimum ≤ maximum；作用于 IK 局部修正 |
| `IkJoint` | sealed interface，允许 `Rotor` 与 `Hinge` |
| `Rotor` | 有限角度 `0..π`；首段围绕未修正姿态方向，后续段相对于前一段方向 |
| `Hinge` | axis/reference 在 driver 未修正局部 frame 中；非零、自动归一化、正交误差 ≤ `1e-4`；角度在 `[-π,π]` 且 minimum ≤ maximum |

Rotor/Hinge 在 Caliko 中约束位置求解，RotationLimit 在局部修正上投影。两者同时给出时两种限制
都会参与，但 Euler 盒投影不保证复杂约束的全局收敛。已经到达目标的链不会另选解。

### IkChain

```java
new IkChain(String name, Bone controller, Bone effector, List<IkLink> links);
new IkChain(String name, Bone controller, Bone effector, List<IkLink> links,
            int iterations, float tolerance, float maxRotationStep,
            boolean replaceAuthoredRotation, TargetMode targetMode);
```

| 参数 | 简化构造默认值 | 契约 |
| --- | --- | --- |
| `name` | 必填 | 非空白链名；控制和诊断都以它寻址，不要求等于 controller 骨名 |
| `controller` | 必填 | 默认目标来源骨 |
| `effector` | 必填 | 最终末端骨 |
| `links` | 必填 | 非空，从 base 到 tip；最终 link 指向 effector |
| `iterations` | 32 | `1..256`；限制适配迭代和库的尝试次数 |
| `tolerance` | `1e-4f` | 有限正距离 |
| `maxRotationStep` | π | 有限非负值，每次方向对齐增量的最大角度；0 将该增量限制为零，后续 Euler 投影仍可调整修正 |
| `replaceAuthoredRotation` | false | true 接管链骨作者局部姿态；当前实现从单位局部变换开始，连作者平移/缩放也一并跳过，morph/grant/fixed-axis 后续仍参与 |
| `targetMode` | `POSITION` | 非 null；模式见下 |

`POSITION` 以 controller 的当前绝对位置为默认目标。
`DIRECTION` 在没有外部目标时用绑定骨段方向和 controller 的旋转差确定朝向，忽略其纯平移。
存在持久/单 tick 外部目标时转为位置混合；该模式不会把外部 Vector3f 解释为方向向量。

```java
var builder = new SkeletonDynamicsBuilder(model.getSkeleton());
var elbow = new Hinge(new Vector3f(0, 0, 1), new Vector3f(1, 0, 0),
        -(float) Math.PI / 2f, 0f);
builder.ik(new IkChain("left-arm", builder.bone("hand-target"), builder.bone("hand"),
        List.of(new IkLink(builder.bone("upper-arm")),
                new IkLink(builder.bone("forearm"), null, elbow)))).attach();
```

该 hinge 示例假定前臂绑定方向为局部 +X、弯曲轴为 +Z；实际资产需填写自己的骨骼 frame。

## 菱形闭环

```java
new DiamondConstraint(String name, String firstChain, String secondChain);
new DiamondConstraint(String name, String firstChain, String secondChain,
                      int iterations, float tolerance);
```

简化构造使用 `iterations=32`、`tolerance=1e-4f`。名称非空白，两个链名必需且不同，
iterations 为 `1..256`，tolerance 为有限正数。分支要求由 Builder 验证，见其结构校验列表。

```java
var builder = new SkeletonDynamicsBuilder(model.getSkeleton());
var left = new IkChain("left-linkage", builder.bone("closure-target"), builder.bone("left-tip"),
        List.of(new IkLink(builder.bone("left-base")), new IkLink(builder.bone("left-mid"))));
var right = new IkChain("right-linkage", builder.bone("closure-target"), builder.bone("right-tip"),
        List.of(new IkLink(builder.bone("right-base")), new IkLink(builder.bone("right-mid"))));
builder.ik(left).ik(right)
        .diamond(new DiamondConstraint("linkage", left.name(), right.name()))
        .attach();
```

两个 base 应在绑定姿态位置重合，分别驱动独立层级，两个末端代表同一个闭合点。
两个 controller 可以是同一骨，也可以分别设置目标，求解从两目标的中点开始。
任一链关闭会暂停该闭环；分支属于闭环时不先作独立链求解。
约束只保证位置闭合，不保证末端朝向相等、四边等长或原生机械反馈。

## SkeletonInstance 运行时控制

```java
var skeleton = instance.getSkeletonInstance();
```

下表 `name` 都表示 IK 链名。现有参数名 `boneName`/`controllerBone` 是历史名称。

| 方法签名 | 返回/行为 |
| --- | --- |
| `boolean setIkEnabled(String name, boolean enabled)` | 未定义链返回 false；成功时要求重新求值 |
| `boolean isIkEnabled(String name)` | 读取开关，缺省 true；不是存在性检查，未知链也返回 true |
| `void resetIkEnabled()` | 清除覆盖开关，恢复缺省 true |
| `boolean setIkTarget(String name, Vector3f worldTarget, float weight)` | 持久目标；未定义链 false；复制目标并要求更新 |
| `boolean clearIkTarget(String name)` | 清除持久目标；没有目标返回 false |
| `void clearIkTargets()` | 清除全部持久目标 |
| `boolean setFrameIkTarget(String name, Vector3f worldTarget, float weight)` | 当前 pose pipeline 周期的瞬态目标；未定义链 false |
| `void clearFrameIkTargets()` | 清除瞬态目标；ModelTickLoop 在周期开始自动调用 |
| `void updateTransform()` | 求值层级、IK 与最终层级；手动宿主按自己的求值时序调用 |
| `void updateTransformAfterPhysics()` | 写回后重建层级，保留已求解 IK 修正，不再求解 IK |
| `Map<String, CalikoIkSolver.SolveResult> ikSolveResults()` | 最近一次 IK 求解的只读诊断视图 |

目标必须非 null、有限，weight 必须有限且在 `[0,1]`，否则抛相应参数异常。
混合从 controller 当前位置到外部位置执行，weight 不是“启用 IK 的强度”。
瞬态目标优先于持久目标；未知链在目标数值校验前直接返回 false。
设置目标只标记输入改变，不立即完成求解，随后运行实例的正常求值入口。

```java
var skeleton = instance.getSkeletonInstance();
boolean accepted = skeleton.setIkTarget("left-arm", new Vector3f(1, 2, 0), 1f);
instance.updateImmediate();
var result = skeleton.ikSolveResults().get("left-arm");
```

`SolveResult(float residual, boolean satisfied)` 的访问器为 `residual()`、`satisfied()`。
普通链 residual 是实际末端到本次目标的距离；闭环取两末端距离与两 base 距离的较大值。
闭环分支结果对应最近一次共同目标，不代表其原 controller 目标一定达到。
satisfied 只表达对应的位置残差在 tolerance 内，不是完整角度/动力学验收。
关闭/未运行的链没有该次结果；Map 是实时只读视图，下次求解会清空并更新，需保留快照时自行复制。

### 每 tick 目标：IkTargetModule

```java
new IkTargetModule(String name, Vector3f worldTarget);
new IkTargetModule(String name, Vector3f worldTarget, float weight);
```

二参数构造的 weight 为 1，初始 enabled 为 true。
访问器：`controllerBone()`（链名）、`target()`（复制向量）、`weight()`、`enabled()`。
控制方法：`void setTarget(Vector3f)`、`void setTarget(Vector3f,float)`、`void setEnabled(boolean)`。
设置 target 保留 enabled 状态，一参数 setter 保留当前 weight。

```java
var target = new lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.IkTargetModule(
        "left-arm", new Vector3f(1, 2, 0));
instance.getTickLoop().addPreIk("hand-target-input", target);
target.setTarget(new Vector3f(1.2f, 2, 0), 0.6f);
target.setEnabled(false);
```

构造器只校验名称非空白和目标数值，不验证链是否存在；运行时向实例发布时才按链名解析。
`addPreIk` 把模块放在 IK 前、默认 apply 后，瞬态目标在同周期内供 IK 与物理共同消费。
需要写 pending transforms 并让默认 apply 消费时，使用 tick pipeline 的 `addBefore(SLOT_APPLY, ...)`。

## 格式 Reader 的实际默认行为

| Reader | 读取接口 | 默认映射 |
| --- | --- | --- |
| `MmdSkeletonDynamicsReader` | `void read(MmdModelData, SkeletonDynamicsBuilder)` | 作者刚体顺序、直接 Bone 引用、有效关节、导入 unitScale；不强制 profile，rigid 写回，profile 半径比例 1 |
| 同上 | `static void readIk(SkeletonDynamicsBuilder)` | 仅转换已挂在骨骼上的 PMX IK；可用于无完整 tail 的骨架 |
| `GltfSkeletonDynamicsReader` | `void read(GltfModelData, SkeletonDynamicsBuilder)` | 按 node 顺序生成候选；强制显式 profile，affine 写回，skin 骨绑定跟随；不自动猜测/创建 IK |

MMD 的 controller 骨名作为链名，链从文件的 tip-to-base 反转为 base-to-tip；正的迭代次数
封顶 256，tolerance 为 `1e-4f`，步长角度取原限制绝对值，并开启作者局部姿态接管。
可表达的单轴 Euler 限位转换为 Hinge；其他盒限位保留 RotationLimit。
单 link、controller/effector 绑定位置重合且 controller 有向量 tail 时使用 DIRECTION。
无有效 effector/link 或非正迭代次数的链不进入定义。无效/自连接 PMX 关节不进入可模拟表。

glTF profile 的外部索引继续对应 node 顺序；MMD profile 对应原刚体顺序。
这两种来源在 Reader 边界完成转换，运行时 `Body.source().bone()` 直接得到通用骨引用。

## 自定义宿主的求解适配

`CalikoIkSolver(Skeleton skeleton, PoseAccess pose)` 提供 `void solve()` 与
`Map<String, SolveResult> results()`。通常由 SkeletonInstance 管理，直接使用时宿主实现：

| PoseAccess 方法 | 宿主职责 |
| --- | --- |
| `Transform absolute(Bone bone)` | 已求值的绝对姿态 |
| `Transform correction(Bone bone)` | 可修改、每实例持有的局部 IK 修正 |
| `Vector3f target(IkChain chain)` | 返回本次目标的新向量；闭环会修改该返回值来求共同目标 |
| `boolean enabled(String chain)` | 链开关 |
| `void refresh(Bone driver, Bone endpoint)` | 修正后同步重建受影响层级，后续读取必须看到最新姿态 |

宿主负责动画/物理求值次序、修正清理和线程归属。求解器有可变状态，不能并发求解同一实例。
当前库是官方 Caliko 1.3.8 core；来源和许可证见 [依赖说明](../modules/render-core/libs/README.md)。

## 源码入口

- [SkeletonDynamics](../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/structure/skeleton/SkeletonDynamics.java)
- [SkeletonDynamicsBuilder](../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/loaders/SkeletonDynamicsBuilder.java)
- [SkeletonDynamicsReader](../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/loaders/SkeletonDynamicsReader.java)
- [SkeletonInstance](../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/SkeletonInstance.java)
- [CalikoIkSolver](../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/ik/CalikoIkSolver.java)
