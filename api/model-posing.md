# 通用模型姿态与动画 API

运行时接口不要求 Model 来自某种格式，`ModelData` / `BoneData` 可以为 null。
原理见 [模型子系统边界](../doc/model-subsystems.md)，IK、继承、固定轴和物理定义见
[Skeleton Dynamics](skeleton-dynamics.md)。

## Model 与格式接入

| 接口 | 包名 | 行为 |
| --- | --- | --- |
| `AnimationLibrary Model.getAnimations()` | `lib.kasuga.rendering.models.uml.structure` | Model 共享的命名片段库 |
| `ModelPosing ModelInstance.getPosing()` | `lib.kasuga.rendering.models.uml.dynamic` | 延迟创建该实例的通用姿态控制器；取用本身不安装驱动 |
| `void ModelData.configureSkeleton(Skeleton)` | `lib.kasuga.rendering.models.uml.structure.data` | 构造 Model 时转换骨架能力，默认空实现 |
| `void ModelData.configureModel(Model)` | 同上 | Model 的公共字段初始化完成后接入通用子系统，默认空实现 |

glTF Converter 将所加载的动画注册到片段库；未加载的轨道不在库中。
Blockbench Loader 将文件内的动画注册到同一个库。两种格式的未命名片段使用 `animation_<文件索引>`。
VMD/VPD、通用 AnimationClip 或自定义格式使用 sampler/data 接入，不需要新建模型类型。
骨骼名、morph 标识、材质引用仍须与目标模型匹配；此接口不自动重定向不同拓扑的骨架。

## AnimationLibrary

包：`lib.kasuga.rendering.models.uml.dynamic.animation`。

```java
new AnimationLibrary();
<T> AnimationLibrary.Clip<T> register(String name, AnimationSampler<T> sampler, T data);
AnimationLibrary.Clip<?> get(String name);
boolean contains(String name);
Map<String, AnimationLibrary.Clip<?>> clips();

new AnimationLibrary.Clip<T>(String name, AnimationSampler<T> sampler, T data);
float AnimationLibrary.Clip.duration();
Pose AnimationLibrary.Clip.sample(float seconds);
Pose AnimationLibrary.Clip.sample(float seconds, Namespace namespace);
AnimationLibrary.Clip<Pose> AnimationLibrary.pose(Pose pose);
```

`register` 的名称非空且不可全为空白，同名注册替换已有绑定；`get` 未命中返回 null。
`clips()` 返回不可修改的视图。构建/加载时注册，完成注册后再创建播放器；注册不是线程安全操作。
sampler/data 非 null；外部 data 的不可变性由接入方保证。库不复制原始动画，也不改写其插值方式。
片段的时间和时长为秒，实际启动播放时由公共播放器检查时长是否有限。
`AnimationLibrary.SAMPLER` 是供 AnimationPlayer/FSM 使用的公共片段适配器。
FSM 的公式 Namespace 透传给原 sampler；命名绑定不会丢失公式上下文。
`pose` 生成未命名、时长为 0 的常量片段。

```java
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdSampler;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd.VpdSampler;

// model 是任意来源的 Model；motion / vpd 是对应 Reader 已读取的数据。
model.getAnimations().register("dance", new VmdSampler(), motion);
model.getAnimations().register("stance", VpdSampler.INSTANCE, vpd);
ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
instance.getPosing().play("dance", true);

// 静态姿态也可以直接使用通用 Pose。
instance.getPosing().pose(new Pose.Builder()
        .bone("head", new Transform(), lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode.REPLACE)
        .ikEnabled("left-arm", false).build());
```

## ModelPosing

包：`lib.kasuga.rendering.models.uml.dynamic`；实现 `PoseDriver`。

| 方法 | 行为 |
| --- | --- |
| `new ModelPosing(ModelInstance instance)` | 通常使用 `instance.getPosing()`；不检查模型格式 |
| `boolean hasClip(String name)` | 查询模型片段库 |
| `String currentClip()` | 当前片段名称；静态姿态/停止后为 null |
| `boolean play(String name, boolean loop)` | 从头播放并安装为实例驱动；缺失名称返回 false，保留当前驱动 |
| `boolean follow(String name, AnimationTimeline timeline)` | 跟随外部时间轴并安装驱动；不重复推进共享时钟 |
| `void pose(Pose pose)` | 安装驱动并持续采样常量姿态；pose 不可为 null |
| `void stop()` | 下一次 render sample 将该驱动写过的 bone/morph/IK 通道恢复默认；材质帧保留 |
| `void pause()`, `void resume()` | 暂停/恢复播放时钟 |
| `void seek(float seconds)` | 跳转到有限、非负秒数 |
| `void setSpeed(float speed)` | 有限、非负倍率；0 冻结时钟 |
| `boolean isPlaying()` | 时钟是否继续推进；静态姿态仍持续采样 |
| `float currentTime()` | 当前时钟秒数；循环时未取模 |
| `AnimationTimeline timeline()` | 当前时间轴，供其他目标跟随 |
| `void tick(float dt)` | 通常由 `ModelInstance.animate(dt)` 推进 |
| `void sample(float partialTick)` | 通常由实例渲染采样入口调用 |

每实例独立持有播放器、时间轴和 PoseSink；静态姿态、命名片段切换复用同一个 sink，
因此前一姿态中消失的通道会自动清除。播放控制和 tick 串行执行，render sample 消费公共播放器
发布的时钟快照。`stop` 的重置在采样阶段执行，不能代替采样或实例更新。
当前 PoseDriver 仍是单个槽位；安装 ModelPosing 会替换当前驱动。
公共驱动实现 RebindablePoseDriver，资源切换与时钟保留见 [输入迁移](minecraft-model-assembly.md#驱动和输入迁移)。
需要动画分层时，用 FSM 的 `State.clip(AnimationLibrary.SAMPLER, clip, loop)` 和 `State.pose(Pose)`。

## Pose 的 IK 通道

包：`lib.kasuga.rendering.models.uml.dynamic.fsm`。

```java
new Pose(Map<Object, Pose.Morph> morphs, Map<String, Pose.Bone> bones,
         Map<Object, Pose.Frame> frames, Map<String, Boolean> ikEnabled);
Map<String, Boolean> Pose.ikEnabled();
Pose.Builder Pose.Builder.ikEnabled(String chain, boolean enabled);
State<Owner> State.ikEnabled(String chain, boolean enabled);
boolean SkeletonInstance.clearIkEnabled(String chain);
```

原来的三参数 Pose 构造方法保留，IK map 默认为空。map 的键为 IK 链名，非模型格式或文件骨索引。
VmdSampler 自动把 property track 的 IK 状态写入 Pose，无需在播放后另行写骨架。
FSM JSON 的 `pose.ik_enabled` 为可选的名称到布尔值的 object，默认 `{}`。

开关在 cross fade 的 alpha 小于 0.5 时取前一端，否则取后一端；单边通道保留该边的值。
与材质帧一样，BASE/ADDITIVE 使用最后一个值，OVERRIDE 优先；BoneMask 只作用于骨骼变换，
不屏蔽 IK 链开关。PoseSink 忽略未知链；通道消失时只清除自身写过的链，其他手动开关保留。
`clearIkEnabled` 恢复该链默认开启状态，有显式状态被移除时返回 true。

## 名称迁移

| 原接口 | 当前接口 |
| --- | --- |
| `GltfAnimationPoseDriver` | `ModelInstance.getPosing()` / 通用 `ModelPosing` |
| `MmdRagdoll` | `lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll` |
| `MmdPhysicsScene` | 同包的 `ModelPhysicsScene` |
| `MmdPhysicsClusterManager` | 同包的 `ModelPhysicsClusterManager` |
| `SkeletonInstance.getPmxBones()` | 格式 Reader 自行维护文件索引映射；实例仅公开通用 Skeleton/Bone |

这些格式专用运行时入口已移除。物理接口的参数和能力保留，引用旧类型的调用方需更新 imports
并重新编译。glTF 的 STEP/LINEAR/CUBIC_SPLINE 继续由 GltfSampler 精确采样。
