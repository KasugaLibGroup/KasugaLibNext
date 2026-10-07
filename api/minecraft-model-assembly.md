# Minecraft 穿搭资源与实例切换 API

通用几何/骨架组装见 [Model Assembly](model-assembly.md)。本页只描述 Minecraft 的资源查找、
发布和句柄接入，Model 及其公共子系统没有模型格式白名单。

## 资源包定义

文件放在 `assets/<namespace>/model_assemblies/<path>.json`，定义 ID 为 `<namespace>:<path>`。
例如 `assets/example/model_assemblies/winter.json` 对应 `example:winter`。
来源模型仍需通过已有 `model_proxy` 配置预加载，见 [模型加载](modelling/README.zh-CN.md)。

```json
{
  "body": {
    "id": "body",
    "model": "example:models/character.bbmodel",
    "regions": { "torso": [0, 1, 2] },
    "hide_regions": ["torso"]
  },
  "parts": [
    {
      "id": "shirt",
      "model": "example:models/shirt.glb",
      "bone_mappings": { "shirt_root": "root" }
    },
    {
      "id": "coat",
      "model": "example:models/coat.mmd.zip",
      "model_name": "coat",
      "include_dynamics": true
    }
  ],
  "bind_tolerance": 0.0001
}
```

| 字段 | 默认/要求 |
| --- | --- |
| `body` | 必需的组件 object，`id` 默认 `body` |
| `parts` | 可选组件数组，保持顺序；每项 `id` 必需且不可重复 |
| `model` | 每组件必需的资源位置；路由复用已注册模型管线 |
| `model_name` | 可选内部模型名，供需要内部 entry 查找的 Reader 使用 |
| `bone_mappings` | 源骨名到输出骨名的 object，默认 `{}`；身体不可设置别名 |
| `regions` | 区域名到源面索引数组的 object，默认 `{}` |
| `hide_regions` | 隐藏区域名数组，默认 `[]`，未知名称报错 |
| `hide_meshes` | 附加隐藏面索引数组，默认 `[]`；索引须为整数 |
| `match_body_bones` | boolean，默认 true |
| `include_dynamics` | boolean，默认 true |
| `bind_tolerance` | 有限正数，默认 `1e-4` |

定义先在资源准备阶段读取；纹理完成后在 game thread 发布。错误 JSON 会使该次准备失败，
不会发布半份穿搭定义。来源缺失时定义仍可注册，但 `mount` 返回 false，下一 tick 重试。
绑定矩阵、隐藏面范围等依赖实际模型的校验在完整来源就绪后执行。
身体与衣服须已在同一绑定空间；这不是自动体型拟合接口。

## McModelHandle

包：`lib.kasuga.rendering.models.mc.api`。

```java
McModelHandle handle = McModelHandle.ofAssembly(
        ResourceLocation.parse("example:winter"), instanceId, entity.position());
if (handle.mount()) {
    handle.scheduleVanillaRenderer();
    handle.markRenderedThisFrame();
}

boolean switched = handle.switchAssembly(ResourceLocation.parse("example:summer"));
```

| 方法 | 行为 |
| --- | --- |
| `static McModelHandle ofAssembly(ResourceLocation id, ResourceLocation instanceId, @Nullable Vec3 pos)` | 使用全局组装服务，允许早于资源发布创建 |
| `boolean switchAssembly(ResourceLocation id)` | 使用全局服务换装；服务或来源未就绪时返回 false |
| `boolean switchAssembly(McModelAssemblies service, ResourceLocation id)` | 显式指定服务，适合自定义管线 |
| `McModelHandle onInstanceChanged(BiConsumer<ModelInstance, ModelInstance> callback)` | 新实例完成基础状态恢复后调用，可安装自定义驱动/物理；null 清除回调 |

换装先解析完整来源、取得缓存模型、挂载新实例，再释放该句柄的旧实例。
来源未就绪时不改变当前穿搭；绑定/回调异常会清理新实例并向调用方抛出，旧实例保留。
回调在公共驱动迁移之前执行，设置的新驱动优先；回调自身的外部副作用由调用方负责。

位置、旋转、缩放与 double 浮动原点、可见性模式、距离限制和 ambient multiplier 保留。
兼容名称的骨骼局部输入、morph 激活值、IK 开关与持久目标复制到新实例。
临时逐帧 IK 目标、物理模拟结果、tick-loop 自定义模块与锚点回调不复制；在回调中重新配置。
手动材质帧如需按组件重映射，也在回调中处理。

公共 `ModelPosing`、`AnimationPlayer`、`FsmPoseDriver` 实现 `RebindablePoseDriver`。
命名 ModelPosing 会从新 Model 的片段库刷新数据，并保留原时间轴、播放时间、速度和暂停状态；
缺失片段恢复空姿态。外部共享时钟不被重启，跟随该播放器自有时钟的其他目标也保留同一时钟。
普通 AnimationPlayer/FSM 的 rebind 迁移写入目标；需要更新其外部片段数据时由回调接入。
自定义驱动实现同一接口或在回调中创建新驱动。

源模型替换、删除或穿搭资源重载会撤掉借用旧资源的实例。句柄保留基础状态，随后 `mount()`
自动重新解析并挂载；注册表中的穿搭定义在来源暂缺期间保留。
`destroy()` 回收当前实例及发布订阅，释放句柄持有的待恢复状态。
全局 Actor 的 instanceId 应由宿主唯一管理；同一服务不应让两个句柄同时管理同一穿搭/instanceId。

## 程序注册与自定义服务

```java
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyDefinition;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;

var definition = ModelAssemblyDefinition.builder("body",
        new McModelAssemblies.Reference(bodyLocation))
        .part("shirt", new McModelAssemblies.Reference(shirtLocation),
                part -> part.mapBone("shirt_root", "root"))
        .build();
var service = PipelineRegistry.assemblies(); // 客户端内置管线初始化前为 null
if (service != null) service.register(outfitId, definition);
```

`PipelineRegistry.ASSEMBLY` 的值为 `assembly`，其管线没有格式 Reader，接受派生公共 Model。
普通 `of(modelLoc, ...)` 继续按文件扩展名路由；穿搭定义使用 `ofAssembly`，不需要伪造文件扩展名。

`McModelAssemblies` 完整接口：

```java
new McModelAssemblies(
    ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> output,
    Function<McModelAssemblies.Reference, McModelAssemblies.PublishedSource> sources,
    ModelAssemblyCache cache);

new McModelAssemblies.Reference(ResourceLocation model);
new McModelAssemblies.Reference(ResourceLocation model, @Nullable String modelName);
new McModelAssemblies.PublishedSource(
    ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline, ResourceLocation key);

void register(ResourceLocation id, ModelAssemblyDefinition<McModelAssemblies.Reference> definition);
boolean unregister(ResourceLocation id);
void replaceResourceDefinitions(Map<ResourceLocation, ModelAssemblyDefinition<McModelAssemblies.Reference>> next);
Map<ResourceLocation, ModelAssemblyDefinition<McModelAssemblies.Reference>> definitions();
@Nullable ModelAssembly resolve(ResourceLocation id);
@Nullable ModelInstance createAndBind(ResourceLocation id, ResourceLocation instanceId, @Nullable Transform root);
McModelHandle handle(ResourceLocation id, ResourceLocation instanceId);
ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline();
ModelAssemblyCache.Stats cacheStats();
void close();
```

程序定义优先于同名资源包定义，并跨资源 reload 保留。`unregister` 移除程序覆盖，回收该定义
的实例；若有资源包定义则恢复它，返回值表示是否移除了程序定义。
`replaceResourceDefinitions` 应用已成功解析的完整资源快照，清理派生模型及组装缓存。
`close` 幂等，回收派生实例、缓存引用和来源发布订阅，关闭后调用解析/注册接口会报错。
服务操作与模型发布/渲染挂载须在 game thread 串行执行；基础缓存的独立同步语义仍见通用 API。

默认来源适配复用 `KasugaModelPipelines.publishedSource(Reference)`；资源 reload 正在进行时不解析
旧发布代的资源。自定义来源函数可接入任意已发布 Model 管线，不限制其格式。

## 通用资源定义与发布生命周期

以下接口在 render-core，不依赖 Minecraft：

```java
static <K> ModelAssemblyDefinition.Builder<K> ModelAssemblyDefinition.builder(String bodyId, K source);
static <K> ModelAssemblyDefinition.Builder<K> ModelAssemblyDefinition.builder(
        String bodyId, K source, Consumer<ModelAssemblyDefinition.PartBuilder> configure);
ModelAssemblyDefinition.Builder<K> part(String id, K source);
ModelAssemblyDefinition.Builder<K> part(String id, K source, Consumer<ModelAssemblyDefinition.PartBuilder> configure);
ModelAssemblyDefinition.Builder<K> bindTolerance(float tolerance);
ModelAssemblyDefinition<K> build();
@Nullable ModelAssembly.Request ModelAssemblyDefinition.resolve(Function<? super K, ModelAssembly.Source> sources);
```

`PartBuilder` 提供 `mapBone(String,String)`、`matchBodyBones(boolean)`、`includeDynamics(boolean)`、
`hideMeshes(int...)`、`region(String,int...)` 和 `hideRegions(String...)`。
此资源定义的 `hideRegions` 在调用时解析，所以先定义 region 再隐藏它。
定义可直接构造为 `ModelAssemblyDefinition(List<Part<K>>, float)`；嵌套 `Part<K>` 的参数依次是
`id, source, Map<String,String> boneMappings, Set<Integer> hiddenMeshes, matchBodyBones, includeDynamics`。
任意 K 可由宿主提供；解析来源缺失返回 null，不生成部分几何。

```java
new ModelAssemblyRegistry<K, I>(Function<? super K, ModelAssembly.Source> sources, ModelAssemblyCache cache);
void register(I id, ModelAssemblyDefinition<K> definition);
ModelAssemblyDefinition<K> definition(I id);
Map<I, ModelAssemblyDefinition<K>> definitions();
boolean remove(I id);
@Nullable ModelAssembly resolve(I id);
Set<I> invalidate(Model source);
void clearResolved();
ModelAssemblyCache.Stats cacheStats();
```

registry 保持资源定义与最新完整结果，返回集合视图只读；来源更新时调用 `invalidate`，其返回
依赖该旧 Model 的定义 ID，宿主应据此撤掉派生实例。暂缺来源不会生成半份结果。

`ModelPipeLine` 增加：

| 方法 | 行为 |
| --- | --- |
| `Model getModel(StorageIdentifierType key)` | 未发布时为 null |
| `long modelRevision(StorageIdentifierType key)` | 当前发布版本；不存在时为 0 |
| `StorageIdentifierType modelKeyOf(ModelInstance instance)` | 查找注册键，未登记为 null；绑定后保留键以便常数时间检查 |
| `AutoCloseable onModelChanged(Consumer<ModelChange<StorageIdentifierType>> listener)` | game thread 通知，在旧实例 close 前执行；关闭订阅即注销 |
| `void publishModels(Map<StorageIdentifierType,Model>)` | 合并发布；相同对象跳过；替换时回收该键的实例 |
| `void replaceModels(Map<StorageIdentifierType,Model>)` | 完整替换，删除快照中缺失的模型 |
| `boolean removeModel(StorageIdentifierType key)` | 移除该键和实例；不存在返回 false |
| `Builder.buildForPublishedModels()` | 无 SourceManager/Loader 的派生管线，仍需 bridge 和 backend |

`ModelChange<K>` 为 `key, previous, current, revision`，删除时 current 为 null。
listener 异常不会跳过其他 listener 或旧实例回收，错误在清理完成后汇总抛出。
实例按资源键登记，共享同一个 Model 的不同键互不回收；相同键/instanceId 的 createInstance 幂等。
纯发布管线调用 prepare/load 会报错；SourceManager/Loader 管线的旧 build 方法继续要求 Reader。

`ModelPublicationBatch<K>.publish(prepared)` 发布一个资源管理者的完整代：prepared 的键为
`ModelPipeLine<?,?,K,?,?>`，值为 `Map<K,Model>`。下一代缺失的旧受管键会删除，其他发布者的键保留。
所有受管管线都有清理机会，即使较早的一条管线清理失败。

## 驱动和输入迁移

```java
interface RebindablePoseDriver extends PoseDriver { void rebind(ModelInstance fresh); }
void ModelPosing.rebind(ModelInstance fresh);
ModelInstance ModelPosing.model();
void AnimationPlayer<T>.rebind(ModelInstance fresh, AnimationSampler<T> sampler, T data);
void AnimationPlayback<T,R>.retarget(AnimationSource<T,R> source, T data);
void SkeletonInstance.copyPoseInputsTo(SkeletonInstance target);
void MorphInstance<Id>.copyInputsTo(MorphInstance<Id> target);
```

骨骼迁移只复制命名局部变换、IK 开关及持久目标；根变换和浮动原点由宿主另行迁移。
morph 迁移复制标识对应的激活值/因子，不复制结果缓存或源结构对象。目标不存在的名称忽略。
`AnimationPlayback.retarget` 保持时钟对象/所有权；自有时钟按新片段时长更新，外部时钟不修改。
这些控制操作须与 tick 串行，render sample 消费既有快照机制。
