# 数据驱动注册系统

接口、配置和调用参考。原理说明见 [data-driven-registration.md](../doc/data-driven-registration.md)。

> **WIP** — 本文档描述正在开发中的功能，API 和行为可能发生变化。

## 概述

数据驱动注册系统允许模组开发者通过 JSON 文件注册方块（Block）、物品（Item）和方块实体（BlockEntity），无需编写 Java 代码。系统自动扫描模组资源目录下的 JSON 定义文件，解析后构建虚拟注册树，与现有的 Java 注册体系集成。

目标：让非开发者也能参与内容创作，同时保持现有 Java API 的向后兼容。

---

## 架构

```
JSON 文件 (data/<modid>/kasugalib/*.json)
        │
        ▼
JsonTreeBuilder          — 扫描目录、解析 JSON、构建 RawData
        │
        ├──▶ JsonPropertyParser   — 将 JSON 属性转换为 Function<BlockBehaviour.Properties, BlockBehaviour.Properties>
        ├──▶ JsonItemParser       — 将 JSON 属性转换为 Function<Item.Properties, Item.Properties>
        │
        ▼
FactoryRegistry          — 根据 type 字符串创建对应的 Reg 实例
        │                  (BlockFactory / ItemFactory / BlockEntityFactory)
        ▼
JsonRegistryGroup        — 虚拟注册组，挂载到主注册树
        │
        ▼
RegisterEvent 分发       — 复用现有事件链，tab 通过 CreativeTabModifiers 注入
```

### 集成时机

```
Mod 加载
  │
  ├── @Context 类 static 初始化
  │     └── FactoryRegistry.register()   ← 注册工厂类型
  │
  └── Registry dispatch（首次分派时）
        └── JsonTreeBuilder.buildForMod()  ← 惰性构建 JSON 树
              └── modRegistry.addChild()   ← 挂载到注册树
```

**时序保证**：JSON 树的构建被推迟到首次 Registry dispatch 时执行，确保所有 `@Context` 类的 static 初始化（包括 `FactoryRegistry` 注册）已完成。

---

## JSON 文件

### 位置

```
src/main/resources/data/<modid>/kasugalib/<任意文件名>.json
```

文件会被自动扫描，支持多文件分散定义，按文件名排序处理。

### 结构

```json
{
  "registry_groups": [ ... ],  // (可选) Group 定义数组
  "blocks": [ ... ],           // (可选) Block 定义数组
  "items": [ ... ]             // (可选) 独立 Item 定义数组（不依附于 Block）
}
```

Block 通过 `block_entity` 字段引用方块实体，不需要独立的顶层 `block_entities` 数组。

### Registry Groups（组）

组用于定义共用的属性集和层级结构，支持嵌套（通过 `parent` 字段）。JSON 顶层 key 为 `registry_groups`。

```json
{
  "id": "kuayue:c22_panels",
  "parent": null,
  "properties": { ... },
  "item_properties": { ... }
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 唯一标识，建议带 namespace |
| `parent` | string | 否 | 父组 id，不指定则挂到根组 |
| `properties` | object | 否 | 方块属性，所有子 block 继承 |
| `item_properties` | object | 否 | 物品属性，所有子 block 继承 |

### Blocks（方块）

```json
{
  "id": "kuayue:22_floor",
  "type": "slab",
  "registry_group": "kuayue:c22_panels",
  "properties": { ... },
  "item_properties": { ... },
  "model": "kuayue:models/block/22_floor",
  "textures": {
    "0": "kuayue:block/22_floor"
  },
  "state_machine": "kuayue:panel_state_machine",
  "block_entity": {
    "type": "kuayue:simple_tile",
    "data": { }
  }
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 注册名，格式 `namespace:path` |
| `type` | string | 是 | 工厂类型，需在 `FactoryRegistry` 中注册 |
| `registry_group` | string | 否 | 挂载的组 id |
| `properties` | object | 否 | 方块属性 |
| `item_properties` | object | 否 | 物品属性（如创造标签页） |
| `model` | string | 否 | 模型路径 |
| `textures` | object | 否 | 纹理引用 |
| `state_machine` | string | 否 | 状态机 id（**已实现**：`BlockEntityTypeHandler` 会把它注入 `block_entity.params.state_machine`，经 params 通道传给 BE 工厂） |
| `block_entity` | object | 否 | BlockEntity 绑定 |

**优先级**：block 级属性 > group 级继承属性。当 block 的 `item_properties` 未指定 `tab` 时，自动从所属 group 继承。

### Items（独立物品）

独立物品不依附于 Block，用于定义食物、工具、材料等。通过 `items` 顶层数组定义。

```json
{
  "items": [
    {
      "id": "kuayue:steel_ingot",
      "type": "basic_item",
      "registry_group": "kuayue:materials",
      "properties": {
        "stacks_to": 64,
        "rarity": "uncommon"
      }
    }
  ]
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `id` | string | 是 | 注册名，格式 `namespace:path` |
| `type` | string | 是 | 工厂类型，需在 `FactoryRegistry` 中注册为 `ItemFactory` |
| `registry_group` | string | 否 | 挂载的组 id（继承 group 的 `item_properties`） |
| `properties` | object | 否 | 物品属性 |

**Block Items**：由 Block 工厂自行负责创建。`JsonTreeBuilder` 不会自动为 Block 添加 `BlockItem`，因此工厂必须在返回 `Reg` 前调用 `withDefaultBlockItem()`。例如：

```java
// 推荐 — 提取 helper 方法，解决 BlockReg<T> 无法直接赋值给 Reg<?,Block> 的泛型问题
@SuppressWarnings("unchecked")
private static <T extends Block> Reg<?, Block> blockWithItem(
        String id, Function<BlockBehaviour.Properties, T> blockSupplier) {
    return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(id, blockSupplier).withDefaultBlockItem(id);
}

// 在 static 初始化块中注册
FactoryRegistry.register("simple_block", id -> blockWithItem(id, SimpleBlock::new));

// 错误 — 缺少 withDefaultBlockItem，方块无对应物品
FactoryRegistry.register("simple_block", id -> BlockReg.of(id, SimpleBlock::new));
```

自定义 `Reg` 子类（如 `PanelReg`、`SlabReg`）可在构造函数内部创建 `ItemReg` 并 `setParent(this)`，效果相同。纯装饰方块等不需要物品的场景可省略。

### Block Entities（方块实体）

Block Entity 通过 Block 定义中的 `block_entity` 字段关联，不作为独立顶层类型。

```json
{
  "blocks": [
    {
      "id": "kuayue:signal_block",
      "type": "signal",
      "block_entity": {
        "type": "kuayue:signal_be"
      }
    }
  ]
}
```

| 字段 | 类型 | 必需 | 说明 |
|------|------|------|------|
| `type` | string | 是 | BE 工厂类型，需在 `FactoryRegistry` 中注册为 `BlockEntityFactory` |
| `params` | object | 否 | 透传给 BE 工厂的参数；顶层 `state_machine` 会自动并入 |

**关联机制**：JsonTreeBuilder 在创建 Block 后，如果 Block 引用了 BE 类型，则：
1. 通过 `FactoryRegistry.getBlockEntityFactory(type)` 获取工厂
2. 以 Block 的 `Supplier<Block[]>` 作为 valid blocks 创建 `BlockEntityReg`
3. 将 BE 注册挂载为 Block 的子节点（`blockReg.addChild(beReg)`）

### 状态机方块（fsm_block / fsm_be）

modelling 模块内置两个工厂（`FsmBlockEntityFactories`，`@Context` 静态注册，幂等）：

| type | 说明 |
|------|------|
| `fsm_block` | `FsmBlock`（`EntityBlock`），BE 类型按 `BuiltInRegistries.BLOCK_ENTITY_TYPE` 扫描 `isValid(state)` 发现；`getTicker` 校验 BE 类型（类型缓存 + instanceof 兜底）后才返回 ticker |
| `fsm_be` | `BlockEntityReg<AnimationBlockEntity>`，从 `params` 读 `state_machine`（缺省 warn 不崩）、`model`、`model_name`，闭包捕获进 BE 构造 |

示例（contentTesting 的 `fsm_blocks.json`）：

```json
{
  "blocks": [
    {
      "id": "kasuga_lib:fsm_test_block",
      "type": "fsm_block",
      "state_machine": "kasuga_lib:fsm_test_panel",
      "block_entity": {
        "type": "fsm_be",
        "params": { "model": "kasuga_lib_test:models/fsm/test_cube.obj" }
      }
    }
  ]
}
```

`state_machine` 指向 `data/<namespace>/state_machines/*.json` 定义（由 modelling 的
`StateMachineDefinitionLoader` 扫描加载）；`model` 为模型资源（`.mmd.zip` / `.obj` /
`.geo.json` / `.json`，按扩展名路由到对应管线）。方块放置后：服务端每 tick 驱动逻辑机
（sink=null）并通过 `FsmSyncServer` 推送状态；客户端绑定模型实例（`ModelInstancePoseSink`）、
应用服务端快照并驱动渲染（详见 modelling `AnimationBlockEntity` / `KasugaModelPipelines`）。

---

## 属性

### 方块属性 (properties)

JSON 中支持的方块属性通过 `JsonPropertyParser` 解析，映射到 `BlockBehaviour.Properties`。

```json
{
  "no_occlusion": true,
  "strength": [1.5, 3.0],
  "map_color": "blue",
  "no_collission": true,
  "requires_correct_tool": true,
  "random_ticks": true,
  "sound_type": "metal",
  "light_emission": 15,
  "friction": 0.8,
  "speed_factor": 0.5,
  "jump_factor": 1.5,
  "dynamic_shape": true,
  "destroy_time": 1.5,
  "explosion_resistance": 3.0
}
```

| 属性 | JSON 类型 | 说明 |
|------|-----------|------|
| `no_occlusion` | boolean | 不遮挡相邻方块 |
| `strength` | number 或 [number, number] | 破坏时间/硬度，两元素数组为 [hardness, resistance] |
| `map_color` | string | 地图颜色（可使用 dye color 名称） |
| `no_collission` | boolean | 无碰撞箱 |
| `requires_correct_tool` | boolean | 需要合适工具才能掉落 |
| `random_ticks` | boolean | 随机刻更新 |
| `sound_type` | string | 放置/破坏音效类型 |
| `light_emission` | number | 亮度等级 (0-15) |
| `friction` | number | 摩擦力系数 |
| `speed_factor` | number | 行走速度倍率 |
| `jump_factor` | number | 跳跃高度倍率 |
| `dynamic_shape` | boolean | 动态碰撞箱 |
| `replaceable` | boolean | 是否可替换 |
| `destroy_time` | number | 破坏时间（单个值，与 `strength` 互斥） |
| `explosion_resistance` | number | 爆炸抗性 |
| `no_loot_table` | boolean | 无战利品表 |
| `ignited_by_lava` | boolean | 可被熔岩点燃 |
| `liquid` | boolean | 流体 |
| `force_solid_on` | boolean | 强制固实 |
| `air` | boolean | 空气 |
| `no_terrain_particles` | boolean | 无地形粒子 |

### 物品属性 (item_properties)

`item_properties` 用于 Block 和独立 Item 的创造标签页等设置。

```json
{
  "tab": "kuayue:train_panel_tab"
}
```

| 属性 | JSON 类型 | 说明 |
|------|-----------|------|
| `tab` | string | 创造模式标签页的 ResourceLocation |

### 物品属性 (properties，独立 Item 使用)

独立 Item 的 `properties` 通过 `JsonItemParser` 解析，映射到 `Item.Properties`。

```json
{
  "stacks_to": 64,
  "rarity": "uncommon",
  "fire_resistant": true,
  "durability": 256,
  "no_repair": true
}
```

| 属性 | JSON 类型 | 说明 |
|------|-----------|------|
| `stacks_to` | number | 最大堆叠数 |
| `rarity` | string | 稀有度：`common`、`uncommon`、`rare`、`epic`，无效值会 warn 并跳过 |
| `fire_resistant` | boolean | 防火（不会被岩浆/火焰烧毁） |
| `durability` | number | 最大耐久度 |
| `no_repair` | boolean | 禁止铁砧修复 |

`tab` 字段也可以写在独立 Item 的 `properties` 中，效果与写在 `item_properties` 中相同。

---

## 自定义属性扩展

第三方 mod 可以通过 `JsonItemParser.registerParser()` 和 `JsonPropertyParser.registerCompiler()` 注册自定义属性解析器，无需修改 KasugaLib 源码。

### 物品属性扩展

```java
// 注册自定义物品属性解析器
JsonItemParser.INSTANCE.registerParser("my_custom_property", (key, value) -> {
    // 解析 JSON 值
    String data = value.getAsString();
    // 返回 Consumer<Item.Properties>，直接调用原版 API
    return props -> {
        // 自定义逻辑
    };
});
```

注册后即可在 JSON 中使用：

```json
{
  "id": "mymod:my_item",
  "type": "basic_item",
  "properties": {
    "my_custom_property": "some_value"
  }
}
```

### 方块属性扩展

```java
// 方式 1：使用 registerCompiler（基于 ResourceLocation 匹配）
JsonPropertyParser.getInstance().registerCompiler("mymod:custom_prop", (key, value) -> {
    int data = value.getAsInt();
    return props -> props.strength(data);
});

// 方式 2：使用 registerCompiler(ModifierCompiler)（自定义匹配逻辑）
JsonPropertyParser.getInstance().registerCompiler(new ModifierCompiler(
    (key, value) -> key.startsWith("mymod:"),  // 自定义 key 匹配
    (key, value) -> {
        // 自定义解析逻辑
        return props -> { };
    }
));
```

注册后即可在 JSON 中使用：

```json
{
  "id": "mymod:my_block",
  "type": "simple_block",
  "properties": {
    "mymod:custom_prop": 10
  }
}
```

---

## 工厂类型

工厂类型需在 `FactoryRegistry` 中通过 Java 注册，然后才能被 JSON 引用。支持三种工厂：

| 工厂类型 | 注册方法 | JSON 用途 |
|----------|----------|-----------|
| `BlockFactory` | `FactoryRegistry.register()` | Block `type` 字段 |
| `ItemFactory` | `FactoryRegistry.registerItem()` | Item `type` 字段 |
| `BlockEntityFactory` | `FactoryRegistry.registerBlockEntity()` | Block Entity `type` 字段 |

### 内置 Block 工厂

当前已在 `C22JsonFactory` 中注册的工厂类型：

| type | 说明 | Java 类 |
|------|------|---------|
| `train_panel` | 普通面板 | `PanelReg<TrainPanelBlock>` |
| `slab` | 下半砖 | `SlabReg<SlabBlock>` |
| `slab_top` | 上半砖 | `SlabReg<SlabBlock>` (top=true) |
| `ladder` | 梯子 | `SlabReg<LadderBlock>` |
| `train_small_window` | 小窗户 | `PanelReg<TrainSmallWindowBlock>` |
| `train_openable_window_1` | 单格可开关窗 | `PanelReg<TrainOpenableWindowBlock>` (width=1) |
| `train_openable_window_2` | 双格可开关窗 | `PanelReg<TrainOpenableWindowBlock>` (width=2) |
| `train_hinge_panel` | 铰链面板 | `PanelReg<TrainHingePanelBlock>` |
| `air_vent` | 通风口 | `SlabReg<AirVentBlock>` |

### 注册新工厂类型

```java
@Context
public class MyFactory {

    // helper 方法：创建 BlockReg 并附带 BlockItem，解决泛型边界不兼容问题
    @SuppressWarnings("unchecked")
    private static <T extends Block> Reg<?, Block> blockWithItem(
            String id, Function<BlockBehaviour.Properties, T> blockSupplier) {
        return (Reg<?, Block>) (Reg<?, ?>) BlockReg.of(id, blockSupplier).withDefaultBlockItem(id);
    }

    static {
        // Block 工厂（使用 BlockReg + withDefaultBlockItem）
        FactoryRegistry.register("simple_block", id -> blockWithItem(id, SimpleBlock::new));

        // Block 工厂（使用自定义 Reg 子类，构造函数内自行创建 ItemReg）
        FactoryRegistry.register("train_panel", id -> new PanelReg<>(id, TrainPanelBlock::new));

        // Item 工厂
        FactoryRegistry.registerItem("my_item_type", id -> ItemReg.of(id, MyItem::new));

        // Block Entity 工厂
        FactoryRegistry.registerBlockEntity("my_be_type", (id, validBlocks) -> {
            BlockEntityReg<MyBlockEntity> reg = BlockEntityReg.of(id, MyBlockEntity::new);
            // validBlocks 是 Supplier<Block[]>，由 JsonTreeBuilder 在关联时提供
            reg.configure(BlockEntityRegModifiers.ValidBlocksType.of(
                "validBlocks", original -> {
                    original.addAll(Arrays.asList(validBlocks.get()));
                    return original;
                }));
            return reg;
        });
    }
}
```

必须使用 `@Context` 注解以保证 static 初始化在 JSON 树构建前完成。

---

## 实际示例：C22 车厢

### Java 代码保留部分

构造器或初始化逻辑复杂的部分保留在 Java 中：

- `DOOR_22` — CustomRenderedDoorBlock，多纹理、多位置参数
- `C22_END_FACE` — CustomRenderedEndFaceBlock，需要传递 DoorType 和渲染信息

### 从 Java 迁移到 JSON 的部分

14 个方块的注册定义被移入 JSON，包括 floor、panel、window、ladder、coupler、carport 等组件。Group 定义了共用属性（`no_occlusion`, `strength`, `map_color`）和创造标签页。

---

## 已知限制 / WIP

- [ ] **模型/纹理**：JSON 中未明确指定模型和纹理路径时，按 Minecraft 约定路径自动查找（`assets/<namespace>/models/block/<path>.json`），需手动补全
- [ ] **翻译键**：自动推导为 `block.<namespace>.<path>`，需在 lang 文件中手动添加
- [ ] **FactoryRegistry**：目前仅有少数工厂类型，尚未覆盖所有 KuaYue block 变体
- [x] **SlabReg.getEntry()** 返回 `null` — 已通过 `ChildrenUtils.traverseRI()` 递归子节点修复
- [ ] **Group 属性继承**：子 block 的 tab 会从 group 继承，但其他 `item_properties` 字段的继承尚未完全覆盖
- [ ] **循环依赖检测** — 计划使用 `GraphCycleDetector.topologicalSort()` 实现 Group parent 链检测
- [ ] **Item 属性扩展**：`JsonItemParser` 当前支持基础属性，food 属性、component 等尚未添加（可通过 `registerParser` 扩展）
- [ ] **BE data_type**：Block Entity 的 `data_type`（DataFixer 类型）尚未支持从 JSON 配置
