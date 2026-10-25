# ItemModelRenderers / ItemModelRenderer

物品侧的模型入口：把 `Item` 映射到管线模型，并提供一个 `BlockEntityWithoutLevelRenderer`（BEWLR），让 GUI、手持与掉落物使用本项目渲染。

包名：`lib.kasuga.rendering.models.mc.api.item`
源码：`modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/api/item/`

接入步骤与 JSON 写法见 [内容接入：方块与物品的模型资源](../guides/model-content.md)。

## ItemModelRenderers

静态注册表 + 共享扩展，全部方法线程安全的前提是"在客户端初始化阶段写、渲染线程读"。

| 方法 | 签名 | 说明 |
| --- | --- | --- |
| `register` | `static void register(Item item, ResourceLocation model, @Nullable String modelName)` | 绑定物品与模型。`modelName` 仅 MMD 使用，其余传 `null`。后写覆盖先写。 |
| `binding` | `static @Nullable Binding binding(Item item)` | 查绑定；未注册返回 `null`。 |
| `items` | `static Set<Item> items()` | 当前已注册的物品（诊断用，只读）。 |
| `clientExtensions` | `static IClientItemExtensions clientExtensions()` | 交给 `RegisterClientExtensionsEvent#registerItem` 的共享扩展。 |
| `renderer` | `static ItemModelRenderer renderer()` | 共享 BEWLR；首次调用时创建。 |

```java
public record Binding(ResourceLocation model, @Nullable String modelName) {}
```

## ItemModelRenderer

`extends BlockEntityWithoutLevelRenderer`。`renderByItem` 的约束：

| 条件 | 行为 |
| --- | --- |
| 物品未在 `ItemModelRenderers` 注册 | 立即返回，不画任何东西 |
| 模型尚未发布（资源未加载完） | 本帧返回，下一帧重试；不刷日志 |
| `PipelineRegistry.backend()` 为 null（非客户端运行期） | 返回 |
| 正常 | 取/建该物品的 `ModelInstance`，标记调度，`MCBackend.renderInstance` 绘制三个 pass |

行为细节：

- **实例身份**：`kasuga_lib:item/<namespace>/<path>`，每个 `Item` 一个，不区分 `ItemStack`。
- **调度模式**：实例常驻 `VANILLA_RENDERER`，绘制前临时改为 `ALWAYS`、绘制后恢复。这保证世界 pass 不会在世界原点再画一次。
- **采样**：每次绘制都释放采样标记，因此同帧多次绘制（GUI + 手持 + 掉落物）都会按当前 `partialTick` 重新采样。
- **变换**：`ItemRenderer` 已应用 `translate(-0.5)`（原版 `[0,1]` 模型空间），BEWLR 先 `translate(0.5)` 抵消，再套用与 `block/block.json` 一致的 display 表（见指南第 5 节）。
- **GUI 微调**：`private static final float GUI_DOWN_SHIFT = -0.5f`，负值向下（GUI 的 Y 被翻转），单位是 0.625 缩放前的模型局部空间，约等于 5 px @ GUI scale 1。
- **生命周期**：实例常驻到客户端结束；没有逐物品销毁路径。资源重载后由 `McModelHandle.mount()` 幂等自愈。

## 与 MCBackend 的接口

| 方法 | 说明 |
| --- | --- |
| `boolean MCBackend#renderInstance(ModelInstance model, MCBackendContext context)` | 在世界 pass 之外按给定 PoseStack 绘制单个实例；返回是否找到该实例。 |

调用方负责构造 `MCBackendContext`：`camera` 与 `frustum` 传 `null` 时跳过距离/视锥剔除，`poseStack` 由调用方拥有，`modelViewMatrix`/`projectionMatrix` 取自 `RenderSystem`。

## 失败与诊断

| 现象 | 记录点 | 含义 |
| --- | --- | --- |
| 物品不可见 | 无日志 | 通常是 item model 未写 `builtin/entity`，或没有 `registerItem` |
| 模型延迟出现 | `[KasugaModelPipelines] bound ...` | 资源刚发布，属正常懒加载 |
| 物品固定不可见 | 无 | 检查 `ItemModelRenderers.binding(item)` 是否为 `null` |
