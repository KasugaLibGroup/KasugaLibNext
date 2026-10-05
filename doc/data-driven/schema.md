# 数据驱动 JSON 格式规范（schema）

> 类型：Reference（信息向，供查阅）。
> 适用版本：Minecraft 1.21.1、NeoForge 21.1.203、Parchment 2024.11.17、Java 21。本页描述的格式只在该版本区间内成立。
> 代码基准：`KasugaLibNext` 分支 `model-loader`，核验日期 2026-10-04，基准 commit `ec864a1`。文中行号对应当前工作树（未提交的 data-driven 重实现）。
> 权威来源：本页所有规则来自代码与测试，不来自 `doc/data-driven/` 下的旧文档（旧文档描述的是 `sources` 时代的实现，已过时）。每条规则给出类 + 行号或测试名，读者可自行核对。

---

## 0. 术语与范围

### 0.1 三个必须分清的概念

- 注册期（registration phase）：mod 启动、构造期间把对象写入 MC 注册表（如 `BuiltInRegistries.BLOCK`）的阶段，读取来源是 mod jar，一次完成，不可热更。
- reload 期（reload phase）：`/reload` 触发的资源重读，读取来源是包栈（pack stack），数据包可覆盖。
- 索引文件（index manifest）：`data/<namespace>/kasuga_lib/data_driven/` 下的 `.json`，只声明「哪些内容文件属于哪个域」，本身不含任何注册对象。

一个 mod 的索引文件可以只声明注册域、只声明 reload 域，或两者都声明。

### 0.2 本文覆盖什么

覆盖：索引文件格式、注册期内容文件格式（`registry_groups` / `blocks` / `items` / 内嵌 `block_entity`）、重复 id 规则、reload 期内容文件 wrapper 格式、错误的分类与出口。

不覆盖：各 `properties` 键在注册表里的具体效果（见 `guide-content.md`）、状态机定义与动画片段元素内部的完整 codec 字段（见 `fsm.md` 与 `uml/` 下的 codec）、Java 侧 `TypeHandler` 扩展（见 `guide-extension.md`）。

## 1. 索引文件（index manifest）

### 1.1 位置与发现规则

索引目录固定为 **4 段路径**：

```
data/<namespace>/kasuga_lib/data_driven/
```

- 由 `JsonTreeBuilder.indexDirectorySegments(modId)` 锁定，返回 `{"data", modId, "kasuga_lib", "data_driven"}`（`JsonTreeBuilder.java:745-747`）。
- 注意 `kasuga_lib` 是固定拼写，`data_driven` 用下划线。写成 `kasuga-lib` 或漏掉 `kasuga_lib` 这一层都会导致**静默加载 0 条**；该风险由 `JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout()` 用 `assertArrayEquals` 锁死（`JsonTreeBuilderIndexPathTest.java:27-33`）。
- 目录下**所有** `.json` 文件都被当作索引，按文件名升序读取（`JsonTreeBuilder.listIndexFiles`，`:762-774`；`resolveIndexManifests` 按传入顺序聚合）。文件名无约定，`index.json` 只是惯例。
- 目录不存在时只记 DEBUG，不算错误（`JsonTreeBuilder.java:77`）。
- reload 侧读同一目录时复用同一组路径段（`ReloadOrchestrator.indexResourcePath`，`:290`）。

### 1.2 顶层字段

索引文件的顶层字段**只有两个**，各自是字符串数组：

| 字段 | 类型 | 域 | 含义 |
|------|------|----|------|
| `on_register` | `string[]` | 注册期 | 列出在 mod 构造期解析、写入 MC 注册表的内容文件 |
| `on_reload` | `string[]` | reload 期 | 列出在 `/reload` 解析、写入 reload 桶的内容文件 |

两者都**可选**，但至少出现一个（D1/D2）。字段常量在 `JsonTreeBuilder.java:40,42`；解析在 `parseIndexManifest`（`:222-250`）。

一个合法的双域索引：

```json
{
  "on_register": [
    "kasuga_lib_content/blocks.json",
    "kasuga_lib_content/groups.json"
  ],
  "on_reload": [
    "state_machines/panel.json",
    "animation_clips/wheel.json"
  ]
}
```

### 1.3 `sources` 已删除（破坏性变更）

**`sources` 现在是非法字段。** 它不再被识别、不再有别名，走的是「未知顶层字段」通道：记 error + 入桶，并且该文件因此「什么都没声明」（再记一条「两者皆未声明」error）。

```json
{
  "sources": ["kasuga_lib_content/blocks.json"]
}
```

上例会产生**两条** error：(1) `contains unsupported field 'sources'`；(2) `declares neither 'on_register' nor 'on_reload'`。写法见 `JsonTreeBuilder.parseIndexManifest`（`:226-230` 未知字段、`:240-244` 两者皆未声明）；由 `JsonTreeBuilderIndexSchemaTest.legacySourcesFieldIsReportedAsUnknown()` 锁定。

旧字段 `sources` 与旧平铺布局 `data/<namespace>/kasugalib/` 都已硬迁移删除，不做兼容期。若磁盘上存在旧目录且没有新索引目录，会打一条 WARN + 入桶并提示迁移到 `on_register`（`JsonTreeBuilder.java:65-79`）。

### 1.4 字段级规则 D1–D6

这些规则的**可执行版本**是 `JsonTreeBuilderIndexSchemaTest`（8 个用例，见 §6）。规则以 `parseIndexManifest`（单文件）与 `resolveIndexManifests`（跨文件聚合）实现。

- **D1 至少声明一个字段**：两个字段都不出现（空文件、只有未知字段的文件）→ error + 入桶，该文件不贡献任何路径。空文件（GSON 解析为 `null`）同样报错（`JsonTreeBuilder.java:240-244`；测试 `declaringNeitherDomainIsReportedAndConsumesNothing` / `emptyFileIsReportedInsteadOfSilentlyIgnored`）。
- **D2 空数组合法**：`{"on_register": []}` 是合法的，且**计为已声明**（`JsonTreeBuilder.java:237`；测试 `emptyArrayIsValidAndCountsAsDeclared`）。
- **D3 单字段失败不连累兄弟字段**：`on_register` 非数组时只报该字段错，`on_reload` 照读，反之亦然（`readPathArray`，`:256-277`；测试 `nonArrayFieldDoesNotAbortTheSibling`）。数组里非字符串的元素报错并跳过，其余元素保留（`:268-274`）。
- **D4 聚合告警按「是否声明过」分支**：只声明 `on_reload` 的纯 reload mod **不报**「未声明注册域」；只有**任何文件都没声明任一字段**时，聚合阶段才记一条 WARN + 入桶（`resolveIndexManifests`，`:323-326`；测试 `pureReloadModDoesNotRaiseTheAggregateError`）。单文件层面都声明失败时聚合错误照报（测试 `allBrokenSchemaStillRaisesTheAggregateError`）。
- **D5 `sources` 是未知字段**，不是别名（`:226-230`；测试 `legacySourcesFieldIsReportedAsUnknown`）。
- **D6 同一路径双列 fail-closed**：同一路径（同文件内或跨文件）同时出现在两个数组 → error + 入桶，且该路径**两个域都不消费**（从两个列表里都删除）。判定跨该 mod 的**全部**索引文件（`resolveIndexManifests`，`:308-316`；测试 `crossListedPathIsConsumedByNeitherDomain`）。

### 1.5 路径条目规则

数组元素是相对于 `data/<namespace>/` 的路径，**必须**带 `.json` 后缀。校验在 `JsonTreeBuilder.validateSourcePath`（`:383-392`），reload 侧复用同一函数（`ReloadOrchestrator.collectIndex`，`:369`）。违反任一条 → error + 入桶并跳过该条：

| 规则 | 违反时的消息片段 |
|------|------------------|
| 非空 | `path is empty` |
| 不以 `/` 开头（必须相对） | `path must be relative` |
| 以 `.json` 结尾 | `path must include the '.json' suffix` |
| 无空段（`//`） | `path contains an empty segment` |
| 不含 `.` 或 `..` 段 | `path must not contain '.' or '..'` |

补充：

- 路径**不可跨命名空间**是结构性的：`parseSource` 永远把路径拼在 `data/<modId>/` 之下（`JsonTreeBuilder.java:414-420`）。也正因如此，指向 `assets/` 的条目不可能生效。
- 同一路径在同一 mod 内被多处列出 → **静默去重**，只解析一次（`parseSource` 的 `parsedSources`，`:404`），不报错。
- reload 侧还要求路径能构成合法 `ResourceLocation`（只允许 `[a-z0-9/._-]`）；大写等非法字符会单独报错（`ReloadOrchestrator.java:369`）。
- 路径非法、文件缺失、JSON 不可读都会 error + 入桶（`parseSource`，`:395-436`）。

### 1.6 多清单合并

同一 mod 的全部索引文件先**全部读入**，再统一消费：路径按「索引文件名升序 → 文件内数组书写顺序」拼接（`JsonTreeBuilder.java:92-96,296-301`）。因此 D6 的双列检测能跨文件生效，每条内容文件在一轮加载内至多解析一次。

## 2. 注册期内容文件（列在 `on_register`）

### 2.1 文件顶层结构

内容文件的**顶层字段名 = 顶层注册类型**，值必须是**对象数组**。当前有 3 个顶层类型：

| 顶层字段 | handler | phase | 作用 |
|----------|---------|-------|------|
| `registry_groups` | `RegistryGroupHandler` | 0 | 建立属性分组（父/子） |
| `blocks` | `BlockTypeHandler` | 1 | 注册方块 |
| `items` | `ItemTypeHandler` | 1 | 注册独立物品 |

`block_entities` **不是**顶层字段，它是内嵌类型，写法见 §2.5。

一个内容文件可同时包含多个顶层字段（例如 `registry_groups` 与 `blocks` 各一组），由 `dispatchContent` 逐个分派（`JsonTreeBuilder.java:461`）。

```json
{
  "registry_groups": [
    { "id": "mymod:panels", "properties": { "no_occlusion": true } }
  ],
  "blocks": [
    {
      "id": "mymod:panel",
      "type": "simple_block",
      "registry_group": "mymod:panels",
      "properties": { "destroy_time": 2.0 },
      "item_properties": { "tab": "mymod:main" }
    }
  ]
}
```

顶层字段的校验：

- 未知顶层字段 → error + 入桶，且**同文件其余字段照常分派**（半应用；`JsonTreeBuilder.java:475-490`）。
- 已知字段但值不是数组 → error + 入桶，其余字段照常（`:504-510`）。
- 数组元素不是 JSON 对象 → error + 入桶，该元素跳过，其余元素照常（`:504-516`）。

### 2.2 `registry_groups` 条目

解析见 `RegistryGroupHandler.parse`（`RegistryGroupHandler.java:26-33`），应用见 `store`（`:54-90`）。

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | 是 | — | 分组 id。**按原字符串匹配**，不做命名空间归一化 |
| `parent` | string | 否 | `null` → 挂根组 | 父分组 id；父不存在时回退挂根组（`:58-63`） |
| `properties` | object | 否 | — | 传给子内容的方块属性（见 §2.6） |
| `item_properties` | object | 否 | — | 传给子内容的物品属性；其中 `tab` 单独抽出作为该组的创造栏（`:73-76`） |

- 分组**没有 `type` 字段**。
- 方块/物品通过 `registry_group` 用原字符串查组；因此 `id` 不归一化，`mymod:panels` 与 `panels` 是两个不同的组（`RegistryGroupHandler.resolveIdentity`，`:49-52`）。
- 应用前按 `parent` 做拓扑排序：父先于子；环只打 WARN，不报错，回退原顺序（`JsonTreeBuilder.topoSortGroupDefs`，`:869`）。

### 2.3 `blocks` 条目

解析见 `BlockTypeHandler.parse`（`BlockTypeHandler.java:25-34`），通用应用见 `RegTypeHandler.apply`（`RegTypeHandler.java:62-112`）。

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | 是 | — | 方块 id，`namespace:path` 或裸路径 |
| `type` | string | 是 | — | 工厂名，查 `FactoryRegistry.BlockFactory`（`BlockTypeHandler.createRegistration`，`:59-64`） |
| `registry_group` | string | 否 | `null` → 挂根组 | 所属分组 id（原串匹配） |
| `properties` | object | 否 | — | 方块属性，见 §2.6 |
| `item_properties` | object | 否 | — | 方块对应的物品属性；`tab` 决定创造栏（`BlockTypeHandler.resolveCreativeTab`，`:51-57`） |
| `params` | object | 否 | `null` | 原样传给工厂 `create(path, params)` |
| `state_machine` | string | 否 | — | 仅当条目内含 `block_entity` 时被转发进 BE params（见 §2.5） |

- `id` 与 `type` 缺失时 `parse` 抛异常（`json.get("id").getAsString()`），该异常被注册期元素级容错捕获 → error + 入桶，**只丢该条目、同文件兄弟条目照常注册**（`JsonTreeBuilder` 产出 `entry <i> in '<key>' failed to parse: <msg>; entry skipped`）。其余字段缺失走默认值。
- `type` 找不到对应工厂 → 只打 WARN，条目跳过，**不入桶**（`RegTypeHandler.java:70-74`）。
- 对象的**未知键被静默忽略**（parse 只读已知键），这一点与文件顶层未知键报错相反，见 §2.7。

### 2.4 `items` 条目

解析见 `ItemTypeHandler.parse`（`ItemTypeHandler.java:22-30`）。

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | 是 | — | 物品 id |
| `type` | string | 是 | — | 工厂名，查 `FactoryRegistry.ItemFactory`（`:56-60`） |
| `registry_group` | string | 否 | 挂根组 | 所属分组 id |
| `properties` | object | 否 | — | 物品属性，见 §2.6；`tab` 决定创造栏（`:48-53`） |
| `params` | object | 否 | `null` | 传给工厂 |

### 2.5 `block_entities`（内嵌类型）

**方块实体没有自己的 id**，它由宿主方块承载，写法是把一个 `block_entity` 对象放进该方块条目里：

```json
{
  "blocks": [
    {
      "id": "mymod:be_block",
      "type": "be_block",
      "block_entity": {
        "type": "test_be",
        "params": { "tick_interval": 4 }
      }
    }
  ]
}
```

- 顶层字段名是 `block_entities`，但内嵌键名是 `block_entity`（`BlockEntityTypeHandler.getTypeName` :21、`getParentTypeName` :27、`getEmbeddedKeyName` :30）。
- 提取逻辑：`BlockEntityTypeHandler.extractEmbedded`（`:33-44`）把宿主方块的 `id` 注入内嵌对象的 `_parent_block`；若宿主方块顶层有 JSON 原始类型（primitive：string / number / boolean）的 `state_machine`，再转发进内嵌对象的 `params.state_machine`（`:37-42`）。判据是 `isJsonPrimitive()`，故 number / boolean 也放行，经 `getAsString()` 转成文本后存入 `params`；对象、数组、null 被忽略。

内嵌 `block_entity` 对象的字段：

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `type` | string | 是 | — | 方块实体工厂名，查 `FactoryRegistry.BlockEntityFactory` |
| `params` | object | 否 | `null` | 传给工厂；常见键 `model` / `state_machine` 由具体工厂解释 |
| `_parent_block` | string | — | 注入 | **不要手写**，由 `extractEmbedded` 注入 |

- 重复判定：两条 BE 条目挂在**同一个宿主方块**上即视为重复（`resolveIdentity`，`:66-68`，用宿主方块的 effective id）。
- 宿主方块找不到 / 未知 BE 工厂 → 只打 WARN，不入桶（`BlockEntityTypeHandler.apply`，`:75-86`）。

### 2.6 `properties` / `item_properties` 内部键

顶层字段的 `properties` 与 `item_properties` 是开放对象，键由两个解析器认领；未知键只打 WARN 并忽略，不报错。

**方块属性 `properties`**（`JsonPropertyParser.buildCompilers`，`JsonPropertyParser.java:148-174`）：

| 键 | 值类型 | 键 | 值类型 |
|----|--------|----|--------|
| `no_occlusion` | bool | `light_emission` | int |
| `no_collision` / `no_collission`（拼写别名） | bool | `friction` | float |
| `requires_correct_tool` | bool | `speed_factor` | float |
| `replaceable` | bool | `jump_factor` | float |
| `dynamic_shape` | bool | `destroy_time` | float |
| `random_ticks` | bool | `explosion_resistance` | float |
| `no_loot_table` | bool | `strength` | float 或 `[destroy, resist]` 两元数组 |
| `ignited_by_lava` | bool | `map_color` | `DyeColor` 名（小写） |
| `liquid` | bool | `sound_type` | 声音类型名（见下） |
| `force_solid_on` | bool | | |
| `air` | bool | | |
| `no_terrain_particles` | bool | | |

- `sound_type` 的合法值来自 `JsonPropertyParser.buildSoundTypeMap`（`:97-146`），如 `stone` / `wood` / `metal` / `glass` 等；未知值只 warn。
- `map_color` 用 MC `DyeColor` 名，未知值只 warn。

**物品属性 `item_properties`（方块条目）/ `properties`（物品条目）**（`JsonItemParser`，`JsonItemParser.java:20-36`）：

| 键 | 值类型 | 含义 |
|----|--------|------|
| `stacks_to` | int | 最大堆叠数 |
| `rarity` | string | `COMMON` / `UNCOMMON` / `RARE` / `EPIC`（大小写不敏感） |
| `fire_resistant` | bool | 防火 |
| `durability` | int | 耐久 |
| `no_repair` | bool | 不可修复 |
| `tab` | string | 创造栏 `ResourceLocation`；由 loader 单独消费，不进入上述修饰器 |

属性沿父链继承，子条目覆盖父条目（`RegTypeHandler.apply` 设 `registry_group` 为父，`Reg.applyProperties` 父先子后）。

### 2.7 对象内未知键 vs 文件顶层未知键

两种位置的严格度不同，是最容易踩的差异：

- **文件顶层**未知字段 → error + 入桶（`dispatchContent`，`JsonTreeBuilder.java:475-490`）。拼错 `blokcs` 会被抓住。
- **对象内**未知键 → 静默忽略（各 handler 的 `parse` 只读已知键），不报错。例如 block 条目里多写一个 `foo` 不产生任何诊断。

### 2.8 内嵌类型误写为顶层字段

把 `block_entities` 当顶层字段写（而不是放进 `blocks` 条目的 `block_entity` 键）会走「未知顶层字段」通道：error + 入桶，消息里点名「应写在 `blocks` 条目的 `block_entity` 键」，并且**同文件其余的 `blocks` 照常分派**。

```json
{
  "block_entities": [ { "type": "test_be" } ],
  "blocks": [ { "id": "mymod:ok", "type": "simple_block" } ]
}
```

行为由 `JsonTreeBuilderEmbeddedTypeDispatchTest.topLevelEmbeddedTypeIsReportedAndTheRestOfTheFileStillDispatches()` 锁定。这只说明「误写为顶层字段」的情形；内嵌对象自身若因缺字段解析失败，异常同样被元素级容错捕获，**只丢该内嵌元素、同文件其余条目照常**（同 §2.3 的 `id`/`type` 缺失）。

## 3. 重复 id 与 last-wins

去重在**任何注册副作用之前**完成：解析全部结束后先裁决，败者根本不进入 `apply`（`JsonTreeBuilder.resolveDuplicates`，`:652`）。这是因为 MC 的 `MappedRegistry.register` 不拒绝重复 key，重复 apply 会留下不一致条目（`DuplicateIdResolver.java:11-14`）。

### 3.1 判定 identity

冲突空间是 `(typeName, identity)`，不同类型同 id 不算冲突。各类型的 identity（`TypeHandler.resolveIdentity`）：

| 类型 | identity | 归一化 |
|------|----------|--------|
| `blocks` / `items` | `EffectiveId.of(modId, id)` | 无命名空间或 `minecraft:` 前缀 → `<modId>:path`；其它命名空间原样保留 |
| `block_entities` | 宿主方块的 effective id | 同上 |
| `registry_groups` | `id` 原字符串 | **不归一化** |

`EffectiveId.of` 见 `EffectiveId.java:39-52`，表测试 `EffectiveIdTest`（`unnamespacedIdsResolveUnderTheModNamespace` / `explicitMinecraftNamespaceResolvesToTheOwningMod` / `otherNamespacesAreKeptVerbatim`）。

### 3.2 「后」的确切含义

last-wins 的「后」是**加载序列中的位置**，不是路径的字典序：

1. 索引文件之间：文件名升序；
2. 一个索引文件内：`on_register` 数组书写顺序；
3. 一个内容文件内：类型字段数组书写顺序；
4. 内嵌 BE：跟随宿主 `blocks` 条目的位置。

依据：`JsonTreeBuilder` 按上述顺序构造候选序列（`:105-110,655`），`DuplicateIdResolver.resolve` 只按**传入下标**取最后一个（`:73-100`）。`DuplicateIdResolverTest.lastMeansLastInSequenceNotLexicographicallyLastSource()` 专门锁定「路径字典序靠前但加载序靠后时仍胜出」。

### 3.3 结果

- 胜者进入 `apply`；败者被整条丢弃，其内嵌 BE 一并隔离（`JsonTreeBuilder.java:678`）。
- 每个冲突记 **WARN 日志 + 一条 loading error 桶条目**（`:667-670`）。注意：日志级别是 WARN，但**落在错误桶里**。
- 有冲突时另记一条汇总 WARN（`:672-674`）。
- 诊断消息含类型字段、id、胜者与败者的来源路径（`DuplicateIdResolver.describe`，`:109-115`）。

## 4. reload 期内容文件（列在 `on_reload`）

### 4.1 两类内容与发现方式

`ReloadOrchestrator` 是 reload 域唯一的编排者（`ReloadOrchestrator.java:121`），它本身不认识任何类型名：每个已注册的 `ReloadHandler` 的 `typeName()` 就是一个合法顶层键，编排器遍历 `ReloadHandlerRegistry` 得到键集合。当前 modelling 侧注册的两个 handler 是 `state_machines` 与 `animation_clips`（`FsmReloadHandler` / `FsmClipsReloadHandler`，见 §4.6）。

| 内容 | 顶层键 | 发现方式 |
|------|--------|----------|
| 状态机定义 | `state_machines` | **目录 glob** `data/<ns>/state_machines/*.json` **+** `on_reload` 列出 |
| 动画片段 | `animation_clips` | **仅** `on_reload` 列出 |

- `state_machines/` 有目录 glob：`FsmReloadHandler.globDirectories()`（`FsmReloadHandler.java:69-71`）声明 `StateMachineDefinitionLoader.PATH`（`StateMachineDefinitionLoader.java:39`），由 `ReloadOrchestrator.collectGlob`（`:348-367`）用 `ResourceManager.listResources` 列举。一个 state machine 文件即使没被任何索引列出也会被读。
- `animation_clips/` **没有目录 glob**。未被索引列出的 clip 文件**永远不被读**（`AnimationClipLoader.java:28-29`；`FsmClipsReloadHandler.globDirectories` 保持默认空集）。
- 索引列出的 `state_machines/` 文件会被 glob 入口跳过，避免读两次（`ReloadOrchestrator.collectGlob`，`:348-367`）。
- 应用顺序：glob 发现在前、索引列出在后；同 id 后者胜（类文档 `:77-91`；`register`，`:507-526`）。

### 4.2 `state_machines` wrapper

每个文件是一个**携带** `state_machines` 顶层键的包装对象，值必须是定义数组（多余的顶层键不再拒绝文件，见 §4.4）：

```json
{
  "state_machines": [
    {
      "id": "mymod:panel",
      "layers": [
        {
          "id": "base",
          "initial_state": "idle",
          "states": [
            { "id": "idle", "duration_ticks": 20 }
          ]
        }
      ]
    }
  ]
}
```

- 一个文件可放多个定义，**数组顺序即文件内 last-wins 顺序**（`StateMachineDefinitionLoader.java:14-33`）。
- 元素 schema 由 `StateMachineDefinition.CODEC` 决定；本页只规范 wrapper，元素字段见 `fsm.md` 与对应 codec。

### 4.3 `animation_clips` wrapper

同样是携带 `animation_clips` 顶层键的包装对象：

```json
{
  "animation_clips": [
    {
      "id": "mymod:wheel_spin",
      "duration_seconds": 2.5,
      "bones": [
        {
          "bone": "wheel_r",
          "keyframes": [
            { "time": 0.0, "transform": { "rotate": [0, 0, 0] } }
          ]
        }
      ]
    }
  ]
}
```

- 元素自带 `id`（就是 `states[].clip` 引用的 id），loader 不额外抽取 id（`AnimationClipLoader.java:21-27`）。
- 元素里 codec 未命名的键被忽略，可作注释/编辑器元数据（`:26-27`）。
- 文件只在 `on_reload` 中生效，注册进 reload 桶；同名 SCRIPT 片段不被文件覆盖（`AnimationClipLoader.register`，`:113-121`）。

### 4.4 文件级严格、元素级容错

两个 decoder 的失败隔离一致（`StateMachineDefinitionLoader.decodeFile` :67-100；`AnimationClipLoader.decodeFile` :69-102）：

- **文件级**：非对象 body、缺顶层键、值非数组 → **整个文件拒绝**，报一条 error + 入桶。`{"state_machines": {}}` 会被整体拒绝（`StateMachineDefinitionLoaderTest.nonArrayValueRejectsWholeFile`）。
- **文件级（真未知键）**：顶层出现不被任何 reload 类型认领的键 → 报一条 `unsupported top-level field 'extra'` + 入桶，但**已知键照常贡献**（半应用，不再整文件拒绝）。`{"state_machines": [...], "extra": 1}` 中 `state_machines` 照常注册（`StateMachineDefinitionLoaderTest.extraTopLevelKeyDoesNotAffectThisType()`；`AnimationClipLoaderTest.ignoresAnExtraTopLevelKey()`）。
- **多顶层键合法**：同一文件可同时携带 `state_machines` 与 `animation_clips`，两个 decoder 各读自己的键、各贡献各的条目（`AnimationClipReloadTest.mixedTopLevelKeysAreConsumedByBothHandlers`）。
- **元素级**：数组元素不是对象、或缺字段解不出 → 只跳过该元素，兄弟照常，逐个报错。见 `StateMachineDefinitionLoaderTest.badElementIsSkippedWhileSiblingsDecode()` 与 `AnimationClipLoaderTest.skipsAMalformedElementAndKeepsItsSiblings()` / `skipsAnElementWithoutAnId()`。
- 空数组合法（`AnimationClipLoaderTest.emptyClipArrayIsValid()`）。

### 4.5 reload 的域路由与反向 D9 提示

- 域由**索引数组**决定，内容类型由**顶层键**决定。
- reload 文件里的未知顶层键 → error + 入桶，并附**反向提示**：「若这些字段是注册内容（如 `blocks`/`items`/`registry_groups`），请把文件改列到 `on_register`」（`ReloadOrchestrator.java:439-446`）。
- 一个文件可以同时承载两类内容：两个 handler 各认领自己的顶层键、各贡献各的条目（`ReloadOrchestrator.dispatch`，`:429-470`）。未知顶层键由 `ContentStructure.unknownKeys` 判定，不再有「唯一顶层键」检查。
- reload 收尾会复核状态机引用的 clip；悬空引用只记 WARN + 入桶，不影响注册（`FsmReloadHandler.afterReload`，`:95-116`）。

### 4.6 扩展到新 reload 类型

reload 类型不是硬编码的：继承/实现 `ReloadHandler<T>`，用一个 `@Context` bean 的 `@PostConstruct` 调 `ReloadHandlerRegistry.register(handler)` 注册即可，不需要改编排器。`typeName()` 就是该类型认领的顶层键，也兼作去重碰撞空间。详细步骤与最小示例见 [guide-extension.md](guide-extension.md) §6，接口签名见 [api.md](api.md) §13。

## 5. 错误的分类与出口

### 5.1 错误（error 日志 + 入桶）

注册期与 reload 期的格式错误基本都在此列：

| 情形 | 出处 |
|------|------|
| 索引未知字段 / 字段非数组 / 元素非字符串 | `JsonTreeBuilder.java:226-274` |
| 单文件未声明任一域；全部文件都未声明 | `:240-244`（error）/ `:323-326`（WARN + 桶） |
| 同一路径双列 | `:308-316` |
| 索引文件或目录读取失败 | `:188`、`:767-771` |
| 内容文件顶层未知字段、字段非数组、元素非对象 | `:475-516` |
| 内容文件路径非法 / 文件缺失 / 解析异常 | `:396-436` |
| handler `apply` 抛异常 | `:712` |
| 重复 id 冲突 | `:667-670`（WARN 日志 + 桶条目） |
| reload 侧：清单读取失败、路径非法、文件缺失、wrapper 形状违反、未知顶层键 | `ReloadOrchestrator.java:307,369,405-470` |
| reload 重复 id | `:545-556`（WARN 日志 + 桶条目） |
| reload 收尾悬空 clip 引用 | `FsmReloadHandler.java:111-116`（WARN 日志 + 桶条目） |

### 5.2 警告（warn，不入桶）

以下情形只打日志、不写桶，因为条目被降级而非格式错误：

- 未知工厂 `type` → 条目跳过（`RegTypeHandler.java:70-74`）。
- 未知 `properties` / `item_properties` 键 → 该键忽略（`JsonPropertyParser.java:50-52`；`JsonItemParser.java:63`）。
- 未知 `map_color` / `sound_type`（`JsonPropertyParser.java:80-82,90-92`）。
- BE 宿主方块找不到 / 未知 BE 工厂（`BlockEntityTypeHandler.java:77,83`）。
- 分组引用环（`JsonTreeBuilder.java:823-825`）。

### 5.3 诊断读取

所有错误按 **mod id** 分桶，读取 API：

- `JsonTreeBuilder.getLoadingErrors(modId)` / `getLoadingErrorsByMod()` / `getLoadingErrors()`（`JsonTreeBuilder.java:743-775`）。
- 统一门面 `Diagnostics`：`Diagnostics.errors(modId)`、`Diagnostics.errors(Domain, key)`、`Diagnostics.summarize()`（`Diagnostics.java:64-166`）。mod 维逐字委托 `JsonTreeBuilder` 的桶（注册期）；域 × source 维**已有写入方**——reload 期用 `Domain.RELOAD_DATA`、键 = 命名空间（`Diagnostics.java:21-31,96-105`）。
- 测试断言契约：`assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(modId))`。

### 5.4 D9 提示文案（跨域误列）

两个方向的条件提示，错误消息字符串是公共契约：

- **注册侧**：`on_register` 列了 reload 域文件（顶层是 `state_machines` 之类）→ 走未知字段通道，消息尾部附「list this file under `'on_reload'` instead」。示例里的键名从 `ReloadHandlerRegistry` 在运行时枚举得出，不再字面写死；注册表为空（纯 JVM 单测）时省略示例。约束仍是 data-driven 不依赖 modelling——两张注册表都由 data-driven 拥有，handler 由 modelling 侧注册，见 `JsonTreeBuilder.java:559-570`、§4.6；测试 `JsonTreeBuilderReloadHintTest.reloadDomainContentUnderOnRegisterIsReportedWithOnReloadHint()`。
- **reload 侧**：`on_reload` 列了注册域文件 → 消息尾部附「list the file under `'on_register'` instead」，见 `ReloadOrchestrator.java:441-446`。

半应用保证：误列一个域，同文件另一个域仍照常处理（`JsonTreeBuilderReloadHintTest.halfApplyStillDispatchesSiblingRegistrationFields()`）。

## 6. 规则出处对照

| 规则 | 出处（类:行 / 测试方法） |
|------|--------------------------|
| 4 段索引路径 | `JsonTreeBuilder.indexDirectorySegments` `:745-747`；`JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout` |
| 顶层仅 `on_register`/`on_reload` | `JsonTreeBuilder.java:40,42,226-230`；`JsonTreeBuilderIndexSchemaTest.legacySourcesFieldIsReportedAsUnknown` |
| D1 至少声明一个域 | `:240-244`；`declaringNeitherDomainIsReportedAndConsumesNothing` / `emptyFileIsReportedInsteadOfSilentlyIgnored` |
| D2 空数组合法 | `:237`；`emptyArrayIsValidAndCountsAsDeclared` |
| D3 兄弟字段不连累 | `:256-277`；`nonArrayFieldDoesNotAbortTheSibling` |
| D4 纯 reload 不误报 | `:323-326`；`pureReloadModDoesNotRaiseTheAggregateError` / `allBrokenSchemaStillRaisesTheAggregateError` |
| D5 `sources` 非法 | `:226-230`；`legacySourcesFieldIsReportedAsUnknown` |
| D6 双列 fail-closed | `:308-316`；`crossListedPathIsConsumedByNeitherDomain` |
| 路径条目规则 | `validateSourcePath` `:383-392` |
| 顶层类型表 | `JsonTreeBuilder.java:461-470`；各 `*TypeHandler.getTypeName` |
| `blocks` 字段 | `BlockTypeHandler.parse` `:25-34` |
| `items` 字段 | `ItemTypeHandler.parse` `:22-30` |
| `registry_groups` 字段 | `RegistryGroupHandler.parse` `:26-33` |
| 内嵌 `block_entity` | `BlockEntityTypeHandler` `:21-68`；`JsonTreeBuilder.java:528-535` |
| 内嵌误写顶层半应用 | `JsonTreeBuilder.java:475-490`；`JsonTreeBuilderEmbeddedTypeDispatchTest` |
| last-wins 判定 identity | `EffectiveId.of` `:39-52`；`RegTypeHandler.resolveIdentity` `:58-60`；`RegistryGroupHandler.resolveIdentity` `:50-52`；`BlockEntityTypeHandler.resolveIdentity` `:66-68` |
| last-wins 顺序 | `JsonTreeBuilder.java:105-110,655`；`DuplicateIdResolver.resolve` `:73-100`；`DuplicateIdResolverTest.lastMeansLastInSequenceNotLexicographicallyLastSource` |
| 冲突诊断 | `DuplicateIdResolver.describe` `:109-115`；`JsonTreeBuilder.java:667-674` |
| reload 两个 wrapper | `StateMachineDefinitionLoader` `:36-102`；`AnimationClipLoader` `:42-121` |
| 目录 glob vs index-only | `ReloadOrchestrator.collectGlob` `:348-367`；`AnimationClipLoader.java:29`；`FsmReloadHandler.globDirectories` `:69-71` |
| 文件级严格 / 元素级容错 | `StateMachineDefinitionLoaderTest` / `AnimationClipLoaderTest` 全部用例 |
| D9 双向提示 | `JsonTreeBuilder.java:559-570`；`ReloadOrchestrator.java:439-446` |
| 诊断桶与门面 | `JsonTreeBuilder.java:806-866`；`Diagnostics.java:64-166` |

## 7. 与其它文档的边界

- 本页（schema.md）：JSON 格式的权威参考，覆盖字段、类型、必填、路径规则、错误分类。
- **`intro.md`**：入门讲解，回答「这是什么、为什么要用」。
- **`guide-content.md`**：任务向教程，回答「怎么搭第一个内容文件」；其中的属性键语义、排查清单以本页格式为准。
- **`guide-extension.md`**：Java 侧扩展（自定义 `TypeHandler` / 工厂 / 属性编译器）。
- **`api.md`**：Java API 参考；其索引章节若仍写 `sources`，以本页为准。
- **`fsm.md`**：状态机定义与动画片段**元素内部**的 codec 字段。

## 附录 A：本文未覆盖 / 待确认

- `state_machines` / `animation_clips` 元素的**完整字段表与默认值**：本页只规范 wrapper。元素 schema 请以对应 codec（`StateMachineDefinition.CODEC` / `AnimationClip.CODEC`）为最终依据；本页未逐字段核对。
- `tab` 指向不存在的创造栏时的运行时行为：代码只把字符串解析为 `ResourceLocation`（`BlockTypeHandler.resolveCreativeTab`），未在格式层校验该栏是否存在。
- 数据包索引（数据包携带 `on_register` 数据）本轮不做；注册期只读 mod jar，数据包的 `on_register` 段不会被消费。是否需要一个「快照比对 WARN」尚未实现。
