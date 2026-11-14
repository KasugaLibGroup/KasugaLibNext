# 索引与 JSON 格式

本文描述当前注册期 handler、reload handler 和资源布局。对象内部的完整 codec 字段仍由 handler 定义；三层语法保持一致：manifest 选文件，content 文件顶层字段选 handler，数组元素由 handler 解析。v2 注册路径还要求先固定来源字节，不能直接把正在变化的安装目录当作构建输入。

## 标准目录

以一个 source 根为准：

```text
data/
  example/
    kasuga_lib/
      data_driven/
        index.json
    kasuga_content/
      groups.json
      blocks.json
      state_machines/
        panel.json
assets/
  example/
    blockstates/
    models/
    textures/
    lang/
pack.mcmeta
```

索引目录固定为：

```text
data/<namespace>/kasuga_lib/data_driven/
```

`kasuga_lib` 和 `data_driven` 的拼写是协议的一部分。`ContentSource.indexes()` 只识别 descriptor 中声明过的 namespace，目录层级必须正好是 `data/<ns>/kasuga_lib/data_driven/<file>.json`。索引文件只取这一层的直接子文件，不会递归读取更深目录。

manifest 中列出的路径相对于 `data/<namespace>/`：

```text
kasuga_content/blocks.json
state_machines/panel.json
```

内容文件可以来自普通 mod JAR、外部目录或 ZIP，但生产路径应先由 `ResourceSources` 捕获并固定为 `ResourceSnapshot`。来源根是否还有 `pack.mcmeta` 只影响 MC 资源投影，不影响 `SnapshotContentSource` 能否读取注册 JSON。

## Root 与 Scene indexing

平台身份中的 `rootId` 描述一个具体资源根。本文把“当前注册场景允许从哪些根读取、每个根暴露哪些入口 index”称为 Scene indexing；它不是额外的 JSON schema，也没有同名 Java 类型，具体由以下方法组合实现：

```java
ResourceSnapshot snapshot = fixedRoot.capture();
ContentSource source = ContentSourceFactory.snapshot(
        snapshot,
        descriptor,
        namespaces,
        label,
        declaredIndexes
);
```

- `ContentSource.indexes()` 默认只发现 `data/<declared-namespace>/kasuga_lib/data_driven/` 的直接 `.json` 子文件。
- `ContentSourceFactory.snapshot(..., declaredIndexes)` 把入口集合限制为调用方声明的精确逻辑路径；传入 `null` 才恢复默认扫描。
- 传入空集合表示该 root 没有 manifest，这是合法的纯资源 root；它仍可通过平台 MC 投影提供 assets/data。
- `declaredIndexes` 只筛选 manifest，不裁剪快照文件。manifest 指向的同根内容仍可由 `open(path)` 读取；宿主必须自己验证声明索引存在且属于允许布局。
- 每个 index 在它自己的 `(sourceId, rootId)` 内解析。不同根即使有同名 manifest、同 namespace 或同逻辑路径，也保留独立定义来源和独立 claims。

常见车包布局把 pack 根映射为 `main`，把 manifest 按顺序列出的 parcel 分别映射为 `parcel-*`。`main` 可自动发现 pack 根 index；parcel 根应只暴露 `parcel.json.indexes` 中声明的入口。`<namespace>:<parcel>` 这类业务 ID 不能直接充当平台 `rootId`，因为平台 ID 不允许冒号；宿主应保存稳定映射。上述名字是宿主约定，不是 KasugaLib 强制语义。

每个有效 `(sourceId, rootId)` 必须有唯一、非负的全局 rank。rank 必须在所有 pack 的所有 main/parcel roots 间连续分配或至少互不重复，不能每个 pack 都从 0 重起。`DataDrivenBuildPipeline` 会拒绝重复 rank；LEGACY 仍可能继续 apply，但带 error 的结果不应视为健康。

## Index Manifest

一个 manifest 是 JSON object，只允许两个顶层字段：

```json
{
  "on_register": [
    "kasuga_content/groups.json",
    "kasuga_content/blocks.json"
  ],
  "on_reload": [
    "animation_clips/wheels.json"
  ]
}
```

| 字段 | 类型 | 用途 | 空数组 |
| --- | --- | --- | --- |
| `on_register` | `string[]` | 启动时解析并构建 `Reg` | 合法，表示显式声明但没有注册文件 |
| `on_reload` | `string[]` | Manager 首次挂接及每次 `/reload` 时从包栈解析 | 合法，表示显式声明但没有 reload 文件 |

两个字段都可省略，但不能同时省略。一个只知道 `on_reload` 的包是合法的纯 reload 来源。

路径必须：

- 非空；
- 相对路径，不以 `/` 开头；
- 用 `/` 分隔；
- 不能含反斜杠、Windows drive prefix、空 segment、`.`、`..`、NUL 或其他 ISO control 字符；
- 以 `.json` 结尾；
- 只能通过相对拼接落在 `data/<namespace>/` 之下。

同一路径列在 `on_register` 和 `on_reload` 时，manifest 诊断记为 `MANIFEST_INVALID`，该路径在两个域中都不消费。`DataDrivenBuildPipeline` 会在同一域重复列出时静默去重；旧注册入口也会用 `parsedSources` 防止同一注册文件重复解析。运行期 reload 的索引读取当前不会对同一 `on_reload` 列表中的重复路径额外去重，最好保持每个路径只出现一次。

旧字段 `sources` 已删除。出现它会得到 `MANIFEST_INVALID`，不会被当作 `on_register` 的别名。

一个 source 可以包含多个 manifest。它们的声明会按 namespace 聚合；新管线会先找出跨域冲突，再按逻辑内容路径排序后解析 `on_register` 文件。

多来源管线和运行期 reload 最终都把 `on_reload` 路径构造成 `ResourceLocation`。路径大小写和字符集必须满足 `[a-z0-9/._-]`；但 `DataDrivenBuildPipeline` 的 reload 预检只走 `LogicalPath` 和来源存在性检查，不会提前构造 `ResourceLocation`。因此严格预检通过仍不能代替运行期 reload 的资源位置校验。

STRICT 下本节的“JSON object”由 `StrictResourceJson` 落实：

- 输入必须是合法 UTF-8，且整份文件恰好是一个 object；
- `reader.setLenient(false)`，拒绝注释、单引号字段、尾逗号等非标准 JSON；
- 对象出现重复键立即失败，不会静默保留最后一个；
- 默认最大深度 64，最多 100,000 个节点，文本长度上限 8 MiB；
- manifest 未知字段、缺失双域、非数组字段、非字符串入口和跨域同路径都是 blocking diagnostic。

LEGACY 使用 Gson 的宽容解析。即使 LEGACY 的 manifest schema 检查报告 error，它也可能继续 apply，所以启动成功状态不能代表 schema 健康。

## 注册期内容顶层字段

内容文件是 object，顶层键是 handler 类型，值为 object 数组：

```json
{
  "registry_groups": [],
  "blocks": [],
  "items": []
}
```

内建顶层 handler：

| 字段 | handler | phase | 是否占用 registry |
| --- | --- | --- | --- |
| `registry_groups` | `RegistryGroupHandler` | `PHASE_GROUPS = 0` | 否 |
| `blocks` | `BlockTypeHandler` | `PHASE_CONTENT = 1` | 是 |
| `items` | `ItemTypeHandler` | `PHASE_CONTENT = 1` | 是 |
| 内嵌 `block_entity` | `BlockEntityTypeHandler` | `PHASE_EMBEDDED = 2` | 是 |

`block_entities` 是 `BlockEntityTypeHandler.getTypeName()`，但该 handler 的 `getParentTypeName()` 是 `blocks`，`getEmbeddedKeyName()` 是 `block_entity`。它只能出现在方块条目的 `block_entity` 键中，不能作为顶层字段。

一个内容文件可以声明多个顶层字段。未知顶层字段在 LEGACY 中是 warning，在 STRICT 中是 error。已知字段不是数组、数组元素不是 object、以及 `parse` 抛异常时始终记录 error；LEGACY 仍会继续应用可解析的兄弟内容，STRICT 则在 apply 前拒绝整批。

handler 内部的未知 object 键是否报错由 handler 自己决定。内建 Gson definition 通常忽略未声明字段；文件顶层字段由管线统一检查。

## `registry_groups`

属性组用于给方块和物品提供从父到子的属性继承。

```json
{
  "registry_groups": [
    {
      "id": "example:base",
      "properties": {
        "strength": [2.0, 6.0]
      }
    },
    {
      "id": "example:panels",
      "parent": "example:base",
      "item_properties": {
        "tab": "example:main",
        "stacks_to": 16
      }
    }
  ]
}
```

| 字段 | 类型 | 必填 | 含义 |
| --- | --- | --- | --- |
| `id` | string | 是 | 组的查找键，按原始字符串匹配 |
| `parent` | string | 否 | 父组 id；缺省时挂到 JSON 根组 |
| `properties` | object | 否 | 方块属性修饰器 |
| `item_properties` | object | 否 | 物品属性修饰器；`tab` 也会存为该组默认创造栏 |

组 id 不做 `EffectiveId` 归一化。`panels` 和 `example:panels` 是两个不同组。方块或物品的 `registry_group` 必须与组 id 原字符串一致。

父组先于子组 apply。STRICT 会检查父组是否存在、是否对当前 owner 可见，并拒绝 parent 环。

## `blocks`

```json
{
  "blocks": [
    {
      "id": "example:control_panel",
      "type": "data_driven_block",
      "registry_group": "example:panels",
      "properties": {
        "destroy_time": 2.0,
        "requires_correct_tool": true
      },
      "state_properties": {
        "active": {
          "type": "boolean",
          "default": false
        }
      },
      "item_properties": {
        "tab": "example:main",
        "stacks_to": 64
      },
      "params": {
        "factory_specific": true
      },
      "block_entity": {
        "type": "example_be",
        "params": {
          "model": "example:models/block/control_panel"
        }
      }
    }
  ]
}
```

| 字段 | 类型 | 必填 | 用途 |
| --- | --- | --- | --- |
| `id` | string | 是 | 方块 id |
| `type` | string | 是 | `FactoryRegistry` 中的 block factory 类型 |
| `registry_group` | string | 否 | 属性组 id |
| `properties` | object | 否 | `BlockBehaviour.Properties` 修饰 |
| `item_properties` | object | 否 | 同 id 方块物品的属性修饰和创造栏 |
| `state_properties` | object | 否 | 真实 `BlockState` 属性集合 |
| `params` | object | 否 | 原样传给 factory 的开放参数 |
| `state_machine` | JSON primitive | 否 | 有内嵌方块实体时转发到 BE 的 `params.state_machine` |
| `block_entity` | object | 否 | 内嵌方块实体定义 |

严格模式要求 `id` 是显式的合法 `namespace:path`，旧 `JsonTreeBuilder` 入口允许裸 id。`EffectiveId` 的两种规则对显式 `minecraft:` 处理不同：

| identity 规则 | `panel` | `minecraft:panel` | `example:panel` |
| --- | --- | --- | --- |
| `EffectiveId.ofLegacy` | `<hostModId>:panel` | `<hostModId>:panel` | `example:panel` |
| `EffectiveId.of` | `<hostModId>:panel`，STRICT validate 另行拒绝裸 id | `minecraft:panel` | `example:panel` |

因此 STRICT 会真正占用显式 `minecraft:` key；宿主必须明确允许这种覆盖并与原版 reserve 合并检查。旧入口的映射是兼容行为，不是 v2 包协议。

`DataDrivenBuildPipeline` 按 `BuildOptions` 选择三参数 identity 的 strict 分支：LEGACY 使用历史上表，STRICT 保留显式 namespace。真正的旧 JAR 入口仍是 `JsonTreeBuilder.buildForMod`。车包和外部包应使用 `BuildOptions.STRICT`。

`type` 必须对应已注册 factory。声明 `state_properties` 时 factory 还必须实现 `StatefulBlockFactory`。

`properties` 与 `item_properties` 的具体键见下表。内建 parser 对未知键以及多数值类型错误只记录 warning 并忽略该修饰器；STRICT 不会自动把所有这类问题升级为 blocking error。发布流程若需要严格字段校验，应扩展 parser 或在专用 handler 中补充 `validate`。

### 方块属性 `properties`

| 键 | 值 |
| --- | --- |
| `no_occlusion` | boolean |
| `no_collision` / `no_collission` | boolean，两个拼写均被接受 |
| `requires_correct_tool` | boolean |
| `replaceable` | boolean |
| `dynamic_shape` | boolean |
| `random_ticks` | boolean |
| `no_loot_table` | boolean |
| `ignited_by_lava` | boolean |
| `liquid` | boolean |
| `force_solid_on` | boolean |
| `air` | boolean |
| `no_terrain_particles` | boolean |
| `light_emission` | integer |
| `friction` | number |
| `speed_factor` | number |
| `jump_factor` | number |
| `destroy_time` | number |
| `explosion_resistance` | number |
| `strength` | number 或 `[destroyTime, explosionResistance]` |
| `map_color` | `DyeColor` 名 |
| `sound_type` | 已支持的 `SoundType` 名 |

### 物品属性

方块使用 `item_properties`；独立物品使用 `properties`。

| 键 | 值 | 说明 |
| --- | --- | --- |
| `stacks_to` | integer | 最大堆叠数 |
| `rarity` | string | `common`、`uncommon`、`rare`、`epic`，大小写不敏感 |
| `fire_resistant` | boolean | 防火 |
| `durability` | integer | 最大耐久 |
| `no_repair` | boolean | 不可修复 |
| `tab` | string | 创造栏 `ResourceLocation`，由 loader 单独消费 |

属性沿 `registry_group` 的 parent 链从父到子 apply。子条目或更具体的方块/物品属性覆盖父组中的同项设置。

## `items`

```json
{
  "items": [
    {
      "id": "example:manual",
      "type": "simple_item",
      "registry_group": "example:common_items",
      "properties": {
        "tab": "example:main",
        "stacks_to": 1
      },
      "params": {
        "factory_specific": "value"
      }
    }
  ]
}
```

| 字段 | 类型 | 必填 | 用途 |
| --- | --- | --- | --- |
| `id` | string | 是 | 物品 id |
| `type` | string | 是 | item factory 类型 |
| `registry_group` | string | 否 | 属性组 id |
| `properties` | object | 否 | 物品属性和创造栏 |
| `params` | object | 否 | 传给 item factory |

独立物品只 claim `minecraft:item`。方块物品由 block factory 的“同时创建设方块物品”能力派生，见 [工厂与注册组](07-factories-groups-integration.md)。

## 内嵌 `block_entity`

方块实体没有 JSON 中的独立 id。loader 用宿主方块的 effective id 作为 definition identity，并生成通常为 `<blockPath>_be` 的 block entity type id。

```json
{
  "blocks": [
    {
      "id": "example:machine",
      "type": "example_block",
      "state_machine": "example:machine_fsm",
      "block_entity": {
        "type": "example_be",
        "params": {
          "model": "example:models/block/machine"
        }
      }
    }
  ]
}
```

`BlockEntityTypeHandler.extractEmbedded` 会：

- 复制 `block_entity`；
- 注入宿主 `id` 为 `_parent_block`；
- 如果宿主存在 JSON primitive 类型的 `state_machine`，写入 `params.state_machine`；
- 把结果交给方块实体 handler 解析。

内嵌对象的公开字段是 `type` 和 `params`。`_parent_block` 是注入字段，内容作者不应手写。

如果 `block_entities` 被写成顶层字段，STRICT 会以未知顶层字段拒绝整批；LEGACY 会报告错误并继续处理同一文件中的有效 `blocks`。诊断包含应使用的父字段和内嵌键提示。

## 方块状态属性

`state_properties` 创建 Java `Property` 和 `BlockState` 组合，只在注册期生效。

| `type` | 必需字段 | 说明 |
| --- | --- | --- |
| `boolean` | `default` 可选 | 新建 boolean property |
| `integer` | `min`、`max`、`default` 可选 | 有范围整数 |
| `enum` | `values`、`default` 可选 | 字符串值集合，使用 `StringStateProperty` |
| `vanilla` | `property`、`default` 可选 | 引用原版 `BlockStateProperties` 属性 |

属性名、enum 值名必须符合 `[a-z0-9_]+`；integer 的 `min` 和 `max` 必填，范围必须非负、至少包含 2 个值、最多包含 64 个值，且 `max` 不能是 `Integer.MAX_VALUE`。boolean 的默认值为 `false`。`vanilla.property` 必须是 `BlockStateProperties` 的 uppercase public static field 名，字段中的原版属性名还必须与 JSON 属性名一致。一个方块定义声明的状态组合数最多 4096。原版属性键不自动等价于同名的 boolean 属性。

改变方块状态属性会改变 world 中保存的状态编码。发布后增删或重排属性可能使旧数据回退，框架不提供自动迁移。详细规则和 Java API 见 [方块状态属性参考](../../reference/data-driven/block-state-properties.md)。

## Reload 内容

reload 内容的顶层键必须由已注册的 `ReloadHandler` 认领。

### `state_machines`

```json
{
  "state_machines": [
    {
      "id": "example:panel",
      "layers": [
        {
          "id": "base",
          "initial_state": "idle",
          "states": [
            {
              "id": "idle",
              "duration_ticks": 20
            }
          ]
        }
      ]
    }
  ]
}
```

`state_machines/` 是当前唯一带目录 glob 的 reload 类型。放在 `data/<ns>/state_machines/` 下的 `.json` 文件即使没有 manifest 也能被发现；被 `on_reload` 列出的文件不会被 glob 重复读取。目录位置只负责发现，文件最终仍按顶层键路由。

### `animation_clips`

```json
{
  "animation_clips": [
    {
      "id": "example:wheel_spin",
      "duration_seconds": 2.5,
      "bones": []
    }
  ]
}
```

动画片段当前只通过 `on_reload` 发现。`animation_clips/` 没有 glob，未列入 manifest 的文件不会被读取。

一个 reload 文件可以同时包含多个已知顶层类型，各 handler 独立贡献条目；未知顶层字段会被报告，但已知类型仍可贡献。

一个空的注册内容文件 `{}` 结构合法，产生零个 definition。一个空的 reload 内容文件会由具体 decoder 判断；当前 FSM decoder 会以缺少目标顶层键报错。

## 文件顺序

v2 构建管线的确定顺序为：

1. 共享平台来源按显式 `rank` 升序；旧 `SourceDescriptor` 才按输入位置分配从 0 开始的 order；
2. 每个 source 内，manifest 与同名域中的重复路径先聚合、去重；
3. `on_register` 内容路径按完整逻辑路径字典序排序；
4. 同一内容文件内，顶层 handler 按传入 pipeline 的 handler 顺序处理；
5. 同一 handler 数组中保持书写顺序；
6. 内嵌类型跟随父数组元素顺序。

后来源、后文件或后数组元素在同 identity 的非注册定义上胜出。旧 `JsonTreeBuilder` 仍按它自己的索引文件顺序和 manifest 数组顺序扫描；迁移到多来源管线时，不要假定两套入口在重复路径上的文件顺序完全相同。STRICT 注册类型保留全部候选，最终是否可应用由 `(registry, id)` claims 决定，而不是简单 last-wins。

`on_register` 的路径在 manifest 层会去重，再由新管线按完整逻辑路径排序；JSON 对象顶层字段的内部顺序不参与排序。数组元素保持文件书写顺序，但最终 apply 仍会受到 phase、handler 顺序、来源顺序和 `parentDefinition` 的约束。

## 最小注册包

```text
data/example/kasuga_lib/data_driven/index.json
data/example/kasuga_content/groups.json
data/example/kasuga_content/items.json
assets/example/lang/en_us.json
pack.mcmeta
```

`index.json`：

```json
{
  "on_register": [
    "kasuga_content/groups.json",
    "kasuga_content/items.json"
  ]
}
```

`groups.json`：

```json
{
  "registry_groups": [
    {
      "id": "example:items",
      "item_properties": {
        "tab": "example:main"
      }
    }
  ]
}
```

`items.json`：

```json
{
  "items": [
    {
      "id": "example:token",
      "type": "simple_item",
      "registry_group": "example:items",
      "properties": {
        "stacks_to": 16
      }
    }
  ]
}
```

这只能完成 Java 注册。仍需提供 `assets/example/models/item/token.json`、纹理和语言；需要 resource/data pack 覆盖时，还要提供合法的 `pack.mcmeta`。

示例假定宿主已注册 `simple_item` factory。发布包应把示例 type 换成自己稳定注册的非 `contentTesting` 类型。
