# 内容接入：方块与物品的模型资源

本文只回答一件事：**当一个方块或物品交给本项目的渲染管线来画时，`blockstate` 与原版 `models/item/*.json` 应该怎么写。**

接口签名见 [ItemModelRenderers](../reference/item-model-renderers.md)；世界内方块如何绑定状态机/模型见 [FSM](../reference/fsm.md) 与 [ProxyHost 内容接入](../reference/fsm.md)。

## 1. 先选路径

| 内容 | 世界内由谁画 | 物品由谁画 |
| --- | --- | --- |
| 方块（本项目管线） | 方块实体渲染（`RenderShape.ENTITYBLOCK_ANIMATED`） | `ItemModelRenderer`（BEWLR） |
| 物品（本项目管线） | — | 同上 |
| 原版静态方块 / 物品 | 原版 baked 模型 | 原版 baked 模型 |

只有第一种需要 `blockstate`；两种"本项目管线"都需要 item model 的那一行配置。

## 2. `blockstate` 的职责

```json
// assets/<ns>/blockstates/<block_path>.json
{
  "variants": {
    "running=false": {"model": "<ns>:block/<placeholder>"},
    "running=true":  {"model": "<ns>:block/<placeholder>"}
  }
}
```

规则：

1. **文件名 = 方块注册路径**，与 Java 类名无关。
2. **变体键必须覆盖方块的全部属性组合**。无属性方块用 `""`；有 `n` 个布尔属性就要 `2^n` 个键。少了会刷 `Unable to load variant` 并退化为 missing model。
3. 在 `ENTITYBLOCK_ANIMATED` 下，它**不参与世界绘制**，但仍决定三件事：
   - 破坏/击中**粒子贴图**（取自被引用模型的 `particle`）；
   - 资源 bake 校验（找不到变体/模型会报错）；
   - 以后若把 `getRenderShape` 改回 `MODEL`，世界里的外观就是它。
4. 被引用的模型必须是**原版 bakery 能解析的 JSON**（`parent`/`elements`/`textures`）。`.bbmodel`、`.geo.json`、`.glb`、`.mmd.zip` 由本项目自己的 loader 读取，**不能**写在这里。

推荐做法：专门做一个极简的占位模型（一个薄板或立方体），`particle` 指向真实贴图，别借用无关方块的模型。

## 3. item model 的两条路

| 写法 | 谁画 | 适用 |
| --- | --- | --- |
| `{"parent": "minecraft:item/generated"}` 等普通 JSON | 原版 baked 模型 | 图标是 2D 贴图 |
| `{"parent": "minecraft:builtin/entity"}` | 本项目 BEWLR | 图标/手持/地面用本项目模型 |

```json
// assets/<ns>/models/item/<item_path>.json
{"parent": "minecraft:builtin/entity"}
```

`builtin/entity` 会被烘成 `BuiltInModel`（`isCustomRenderer() == true`），`ItemRenderer` 于是**不画 quad**，改调 `renderByItem`。**没有注册对应物品的 BEWLR 时，物品会完全不可见** —— 这是最常见的"写对了却看不见"。

## 4. 三步接入物品渲染

```java
// 1) 声明这个物品用哪个模型（任意时机，只要早于客户端扩展事件）
ItemModelRenderers.register(item, MODEL_ID, null);   // 第三个参数是 MMD 条目名，非 MMD 传 null

// 2) item model JSON 写 builtin/entity（见上）

// 3) 客户端登记扩展，把共享的 BEWLR 交给这个物品
@EventBusSubscriber(modid = "your_mod", value = Dist.CLIENT)
final class ClientSetup {
    @SubscribeEvent
    static void onClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(ItemModelRenderers.clientExtensions(), item);
    }
}
```

库不自动注册任何物品：item id 属于内容自己。

## 5. 显示变换由谁负责

原版方块物品的"等轴测"外观来自 `models/block/block.json` 的 `display` 段。`builtin/entity` 得到的是 `ItemTransforms.NO_TRANSFORMS`，所以**这些变换不会自动生效**，`ItemModelRenderer` 内置了与 `block/block.json` 相同的一张表：

| context | rotation (X,Y,Z) | translation | scale |
| --- | --- | --- | --- |
| `GUI` | 30, 225, 0 | 0 | 0.625 |
| `GROUND` | 0 | 0, 3, 0 | 0.25 |
| `FIXED` | 0 | 0 | 0.5 |
| `THIRD_PERSON_*` | 75, 45, 0 | 0, 2.5, 0 | 0.375 |
| `FIRST_PERSON_*` | 0, 45, 0 | 0 | 0.4 |
| `HEAD` / `NONE` | 不变换 | | |

两个细节：

- `ItemRenderer` 在调用 BEWLR 前已经做过 `translate(-0.5)`（原版模型空间是 `[0,1]`）。本项目模型是**原点居中**的，所以 BEWLR 先 `translate(0.5)` 抵消，再套上表。
- GUI 的 PoseStack 被 `scale(16, -16, 16)` 翻转了 Y，屏幕向下是 **-Y**。`ItemModelRenderer.GUI_DOWN_SHIFT` 就是给单个物品做 GUI 纵向微调用的。

自己写 BEWLR 时，这两步缺一不可，否则图标会以原始尺寸正对镜头。

## 6. 世界路径与物品路径是分开的

| | 世界内 | 物品 |
| --- | --- | --- |
| 驱动 | `ProxyInstance.tick` → `FsmAnimatedModel` / `McModelHandle` | `ItemModelRenderer.renderByItem` |
| 实例身份 | `ProxyIdentity`（维度+坐标 / UUID / 外部 id） | `kasuga_lib:item/<ns>/<path>`，每个物品一个 |
| 调度 | 世界 pass（`ALWAYS` / `VANILLA_RENDERER`） | 仅 `VANILLA_RENDERER`，绘制时临时放开 |
| 状态机 | 有（服务端权威 + 同步） | **没有**：物品没有 tick 通道，只能静态预览 |

因此物品图标展示的是**模型的默认姿态**，不会跟随方块里的 FSM 状态。要让物品显示某个状态的姿态，需要在渲染时对 `ModelInstance` 应用固定参数（目前不在本页范围）。

## 7. 常见问题

| 症状 | 原因 |
| --- | --- |
| 方块/图标是紫黑棋盘 | 占位模型的贴图不存在（`textures/block/...` 缺失） |
| 物品完全不可见 | item model 是 `builtin/entity`，但没注册 `IClientItemExtensions` |
| 物品图标过大、正对镜头 | 自定义 BEWLR 没有套用第 5 节的 display 表 / 没有抵消 `-0.5` |
| 世界里原点处多出一个模型 | 物品实例没有用 `VANILLA_RENDERER` 调度，被世界 pass 画了 |
| 破坏粒子不对 | `blockstate` 指向的占位模型 `particle` 贴图不对 |
| 改属性后方块变紫黑 | blockstate 的变体没有覆盖新的属性组合 |

## 8. 参考实现

`contentTesting` 里的两个 fan 方块就是完整样例：

| 文件 | 作用 |
| --- | --- |
| `FanBlock.java` | `RenderShape.ENTITYBLOCK_ANIMATED`、`RUNNING` 属性、`ProxyTickers` |
| `FanBlockEntity.java` | `ProxyHost` + `Proxies.install`，机器/模型来自构造参数 |
| `FanItemClientExtensions.java` | 两个物品的 `ItemModelRenderers.register` + `registerItem` |
| `blockstates/example_fan*.json` | `running` 双变体，占位模型 |
| `models/item/example_fan*.json` | `builtin/entity` |
