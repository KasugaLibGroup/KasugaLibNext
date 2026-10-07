# Model 组装与穿搭缓存 API

包：`lib.kasuga.rendering.models.uml.loaders.assembly`。本接口接收任意来源的公共 Model，
将几何、材质、骨架、morph、命名动画与动力学合成一个 Model。原理与所有权见
[模型子系统](../doc/model-subsystems.md)，姿态控制见 [ModelPosing](model-posing.md)。
Minecraft 的资源定义、自动失效和实例换装入口见 [穿搭资源与切换](minecraft-model-assembly.md)。

这里的 Mesh 是面定义。组装结果是一个 Model 和一套 Skeleton，交给现有后端形成几何缓冲；
不会把多个面的顶点塞进一个 Mesh，也不承诺不同材质/pass 只有一次 draw。

## 最小调用

```java
import lib.kasuga.rendering.models.uml.loaders.assembly.*;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;

// 缓存由衣装/模型管理服务持有；不要每帧新建。
ModelAssemblyCache cache = new ModelAssemblyCache(32, 1_000_000);
ModelAssembly outfit = new ModelAssemblyBuilder("body", bodyModel, bodyRevision,
        body -> body.region("torso", 0, 1, 2).hideRegions("torso"))
        .part("shirt", shirtModel, shirtRevision)
        .part("coat", coatModel, coatRevision,
                coat -> coat.mapBone("coat_root", "root"))
        .assemble(cache);

var model = outfit.model();
ModelInstance instance = new ModelInstance(model, null, null, null,
        new MaterialSetInstance(model.getMaterialSet()), null);
instance.getPosing().play("walk", true);       // 身体片段名保留
instance.getPosing().play("shirt/fit", false); // 衣服的片段名加 part 前缀
```

来源模型须已加载完成且使用同一绑定空间/单位。对共用骨骼，源与目标的绑定绝对矩阵差异不得
超过 `bindTolerance`；默认 `1e-4`，按矩阵分量比较。组装不自动缩放衣服、拟合体型或重定向
不同骨架的动画。共同骨骼使用身体层级，衣服独有骨骼保留原绑定绝对姿态。
所有默认映射和几何克隆在缓存未命中时执行；每帧只更新合并实例的姿态。

## ModelAssemblyBuilder

```java
new ModelAssemblyBuilder(String bodyId, Model body, long revision);
new ModelAssemblyBuilder(String bodyId, Model body, long revision,
                         Consumer<ModelAssemblyBuilder.PartBuilder> configure);
```

第一个 part 为身体。revision 是调用方管理的资源版本，允许任何 long；原地修改资源后必须
更新 revision 或调用缓存失效接口。源码 Model 通过对象身份区分，不读取格式类型，也不计算
整个资源的内容哈希。

| 方法 | 行为 |
| --- | --- |
| `ModelAssemblyBuilder part(String id, Model model, long revision)` | 按顺序追加组件，默认匹配身体同名骨并保留动力学 |
| `ModelAssemblyBuilder part(String id, Model model, long revision, Consumer<PartBuilder> configure)` | 配置映射、隐藏区域和动力学接入后追加 |
| `ModelAssemblyBuilder bindTolerance(float tolerance)` | 有限正数，默认 `1e-4` |
| `ModelAssembly.Request build()` | 复制并校验请求；不生成几何或 GPU 资源 |
| `ModelAssembly assemble()` | 使用标准组装器直接组装，不使用缓存 |
| `ModelAssembly assemble(ModelAssemblyCache cache)` | 构造请求并取得缓存结果 |

part id 非空、不可全为空白且不能重复。身体不能设置骨骼别名。配置 callback 同步执行，
request 保存其结果，不保存 callback。组件顺序影响材质索引和渲染顺序，因此属于缓存键。

### PartBuilder

| 方法 | 行为 |
| --- | --- |
| `PartBuilder mapBone(String source, String target)` | 源骨名到输出骨名；源骨立即解析，目标在组装时校验 |
| `PartBuilder mapBone(Bone source, String target)` | 使用源 Bone 引用，必须属于该来源模型 |
| `PartBuilder matchBodyBones(boolean enabled)` | 默认 true；没有显式映射时匹配身体的同名骨 |
| `PartBuilder includeDynamics(boolean enabled)` | 默认 true；false 排除该组件的继承、固定轴、IK/闭环和物理定义 |
| `PartBuilder hideMeshes(int... indices)` | 按来源 Model 的 Mesh 数组索引移除面 |
| `PartBuilder region(String name, int... indices)` | 在此组件内定义一个具名区域，同名替换 |
| `PartBuilder hideRegions(String... names)` | 隐藏这些区域，构造 Part 时展开成面索引 |

显式映射优先于自动同名匹配；目标可为身体骨或之前组件已引入的骨，不能引用之后的组件。
未映射的组件骨命名为 `partId/sourceName`；无名称的骨使用 `bone_<源索引>`。
新骨保留源绑定绝对矩阵，相对于已映射父骨重算局部绑定；独立根接在身体根下。
输出骨名冲突、无效映射、不兼容绑定或来源层级环会抛 `IllegalArgumentException`。

隐藏索引必须在来源 Mesh 数组范围内；未知区域报错。仅用于被隐藏面的顶点和其 morph 会移除，
共用顶点仍保留；隐藏几何不自动移除骨骼或物理。区域由资源接入方提供，不自动猜测遮挡。
纯三角形与四边形可以组合为 MIXED，由现有后端处理；线条与表面需要分别组装。

## ModelAssembly 与引用重映射

```java
Model ModelAssembly.model();
ModelAssembly.Request ModelAssembly.request();
Map<String, ModelAssembly.Remap> ModelAssembly.parts();
ModelAssembly.Remap ModelAssembly.part(String id);

new ModelAssembly.Source(Model model, long revision);
new ModelAssembly.Part(String id, ModelAssembly.Source source, Map<Bone, String> boneMappings,
                       Set<Integer> hiddenMeshes, boolean matchBodyBones, boolean includeDynamics);
new ModelAssembly.Request(List<ModelAssembly.Part> parts, float bindTolerance);
new ModelAssembly.PartMorphId(String part, Object original);
```

结果映射与 request 的集合不可修改，未知 part id 报错。身体保留原来的 morph/片段/锚点名称；
组件 morph 标识为 `PartMorphId`，避免字符串连接导致标识碰撞；片段和锚点使用 `partId/name`。

`Remap` 的完整方法：

| 方法 | 返回值/行为 |
| --- | --- |
| `String id()`, `Model source()` | 来源组件标识与共享 Model |
| `Bone bone(Bone source)` | 输出骨骼引用；未知源报错 |
| `Vertex vertex(Vertex source)`, `Mesh mesh(Mesh source)` | 输出元素；隐藏或未使用时为 null |
| `Material material(Material source)` | 输出材质；未知源报错 |
| `Object morphId(Object source)` | 身体原标识或组件 PartMorphId |
| `String boneName(String source)` | 来源骨名的输出名称；未知名称保留 |
| `String ikName(String source)` | 输出链名；相同公共链可能复用身体名称 |
| `String animationName(String source)`, `String anchorName(String source)` | 输出名称 |
| `int rigidBodyIndex(int sourceIndex)` | 源物理表到输出表的索引；组件排除动力学时为 -1 |
| `Pose pose(Pose source)` | 转换骨名、morph、IK 和材质帧引用 |
| `<T> AnimationSampler<T> sampler(AnimationSampler<T> source)` | 包装采样器，保留时长、原插值与公式 Namespace |
| `MorphType<?, ?, ?> morph(MorphType<?, ?, ?> source)` | 为组装扩展复制 morph；隐藏目标返回 null |

```java
Object fit = outfit.part("shirt").morphId("fit");
instance.getMorph().activateMorph(fit, 0.8f);
int clothBody = outfit.part("coat").rigidBodyIndex(2);
```

顶点、UV、法线、Mesh、Bone、Material 和 Sprite/SpriteSet 定义分别复制。Texture 及不可变
原始 metadata 借用来源资源，Texture 按对象身份去重。材质和帧状态在组件间保持独立。
不要修改缓存结果的共享定义；每角色的变换、morph、材质帧、动画时钟和物理状态放在 ModelInstance。

数字/数字字符串的 Pose 材质引用按来源材质索引重映射为输出 Material；无效数字引用忽略。
`MaterialResolver.forInstance` 支持直接属于当前 MaterialSetInstance 的 Material 引用。
自定义具名材质引用原样传递，宿主可通过自定义 MaterialResolver 处理。

## 动力学合并

继承与固定轴转换到输出 Bone；同一输出骨的相同约束复用，冲突约束报错。
公共 IK 的 controller、effector、link 与全部求解设置一致时复用身体链，否则生成组件前缀链。
菱形闭环保留；完全相同的闭环复用，冲突及重叠链关系继续由通用 Builder 校验。
完整骨架衣服使用身体已有动力学时，也可显式 `includeDynamics(false)`。

物理坐标及尺寸按每个来源 `Physics.unitScale` 转换，输出 `unitScale = (1,1,1)`；角度仍为弧度。
绑定在同一输出骨且全部属性相同的刚体复用；无绑定骨的刚体独立保留。关节索引重映射，
完全相同的关节复用；映射后自连接报错。绑定跟随骨合并。
只要一个有物理体的来源要求 profile，结果就要求显式 profile；任一来源需要 affine 写回时，
组合物理使用 affine 写回。非空来源的 profileRadiusScale 必须一致，冲突时报错。
profile 和拖拽接口使用输出物理索引，来源索引通过 Remap 转换。

## ModelAssemblyCache

```java
new ModelAssemblyCache(); // 32 entries, 1_000_000 vertices
new ModelAssemblyCache(int maximumEntries, long maximumVertices);
new ModelAssemblyCache(int maximumEntries, long maximumVertices, ModelAssembler assembler);

ModelAssembly getOrAssemble(ModelAssembly.Request request);
int invalidate(Model source);
int invalidate(String partId);
void clear();
ModelAssemblyCache.Stats stats();
```

两种容量必须为正数。LRU 同时受条目数和合并 Model 的顶点数限制；统计不是字节大小或 GPU
显存用量。超出顶点预算的单个结果仍可使用，但不缓存。所有操作同步，并发请求仅组装一次；
不同 miss 也串行执行，组装服务应在加载/工作线程上使用。

缓存键覆盖组件顺序、来源 Model 的对象身份及 revision、骨骼映射、最终隐藏面集合、动力学
开关、同名匹配开关与绑定容差。无效资源/组装异常不存入缓存。
`invalidate` 返回移除条目数，`clear` 清空引用但保留累计统计。
`Stats` 提供 `hits()`、`misses()`、`builds()`、`entries()`、`vertices()`；builds 只计成功组装。

缓存拥有 CPU 共享定义，没有 GPU 资源，不需要 close。失效/淘汰不修改已返回结果；仍在使用
旧 ModelInstance 的宿主负责其渲染资源生命周期。资源 reload 时先停止旧实例并使对应缓存失效，
然后再释放借用的 Texture/metadata。

## 扩展接口

```java
@FunctionalInterface
interface ModelAssembler {
    ModelAssembly assemble(ModelAssembly.Request request);
    static ModelAssembler standard();
    static ModelAssembler standard(MorphRemapper morphs);
}

@FunctionalInterface
interface MorphRemapper {
    MorphType<?, ?, ?> remap(MorphType<?, ?, ?> source, ModelAssembly.Remap mapping);
    static MorphRemapper standard();
}
```

标准实现支持现有顶点位置/法线/UV/tangent、骨变换、材质颜色/高光/环境/边缘、材质帧/
sprite 帧、Flip 和嵌套 Group morph。未知 morph 类型报错；自定义类型通过 remapper 复制其
引用与参数，然后回退到标准实现。没有模型格式白名单。

```java
MorphRemapper defaults = MorphRemapper.standard();
ModelAssembler assembler = ModelAssembler.standard((morph, mapping) -> {
    // 可在此处理自己的 morph 类型，重映射目标、标识与参数。
    return defaults.remap(morph, mapping);
});
ModelAssemblyCache cache = new ModelAssemblyCache(32, 1_000_000, assembler);
```

自定义组装器须返回与输入 request 相等的 request，否则缓存拒绝存储。组装期间资源应为只读；
remapper 只在组装/扩展复制阶段使用，播放时使用结果的不可变映射。

## 本地真实素材探针与缓存基准

```sh
./gradlew :modules:modelling:modelAssemblyProbe -PkasugaAssemblyFixtures=/absolute/path/to/extracted-fixtures
./gradlew :modules:modelling:renderGlTest -PkasugaAssemblyFixtures=/absolute/path/to/extracted-fixtures
```

该可选探针用于当前九包测试素材：解包目录为 `pack_1` 到 `pack_9`，`pack_6/7` 是素体、
`pack_3` 是兼容裙装；只调用生产 PMX 转换与公共组装，不解码或展示素材贴图。
检查全部 PMX 加载和素体/部件绑定兼容性，再验证兼容裙装、1/10/100 个真实小部件的组装、
中性蒙皮位置、缓存共享和来源失效。压力用例只共享绑定兼容的骨，其余骨保留组件命名空间；
这不能解释为 100 件不同衣服已拟合素体。

默认报告在 `build/reports/modelAssemblyProbe/report.json`；`kasugaAssemblyReport` 可指定输出，
`kasugaAssemblyOrder=cached-first` 或默认 `uncached-first` 控制测量顺序，便于新 JVM 交替顺序重复。
记录首次组装、暖机后的重组装、已有 request 的缓存查找、构建 request 加缓存查找，以及当前线程分配。
时间排除文件加载与贴图解码；批次平均值的 p50/p95 与逐调用延迟分布不同。分配不包含 GPU/原生内存。
这些数值只验证 CPU 组装与缓存，不代表游戏 FPS 或每帧蒙皮/绘制提速。

可选 GL 路径对兼容素体+裙装的全部合并顶点比较 CPU 和生产 GPU 蒙皮，包含真实绑定类型与骨骼旋转；
使用 transform feedback 且禁止光栅化，不输出素体图像。成功标识为 `REAL_OVERLAY_SKINNING_PASS`。
