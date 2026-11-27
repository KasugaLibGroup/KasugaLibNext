# 数据驱动 JSON 格式规范（schema）

> 类型：Reference（信息向，供查阅）。
> 适用版本：Minecraft 1.21.1、NeoForge 21.1.203、Parchment 2024.11.17、Java 21。本页描述的格式只在该版本区间内成立。
> 权威来源：本页规则依据当前工作树中 `modules/data-driven`、`modules/core` 与 `modules/modelling` 源码及测试整理，同时覆盖 v2 多来源构建管线（`DataDrivenBuildPipeline`，含 `STRICT` / `LEGACY`）与兼容单 mod 入口（`JsonTreeBuilder`）。旧 `sources` 布局已迁移到索引清单；每条规则给出类、行号或测试名，读者可自行核对。

---

## 0. 术语与范围

### 0.1 三个必须分清的概念

- 注册期（registration phase）：在 mod 启动、构造及注册事件分派前把对象意图写入 `Reg` 树并进入 Minecraft 注册表（如 `BuiltInRegistries.BLOCK`）的阶段，一次完成，不可在 `/reload` 热更。在 v2 路径（`DataDrivenRegistrationProvider` + `DataDrivenBuildPipeline`）中，输入来自宿主捕获并固定的 `ResourceSnapshot` / `ContentSource`（如 `SnapshotContentSource`）；未安装 provider 时，兼容入口 `JsonTreeBuilder.buildForMod` 从 mod JAR 读取。
- reload 期（reload phase）：Manager 首次挂接及 `/reload` 触发的资源重读，读取来源是当前 Minecraft `ResourceManager` 包栈（pack stack），数据包可覆盖（受管 `ApprovedResourcePacks` 来源另受 `ManagedReloadAllowlist` 约束）。
- 索引文件（index manifest）：`data/<namespace>/kasuga_lib/data_driven/` 下的 `.json`，只声明「哪些内容文件属于哪个域」，本身不含任何注册对象。

一个 mod 或来源的索引文件可以只声明注册域、只声明 reload 域，或两者都声明。

### 0.2 本文覆盖什么

覆盖：索引文件格式、注册期内容文件格式（`registry_groups` / `blocks` / `items` / 内嵌 `block_entity`）、内建 `proxy_model` / `proxy_model_be` 参数 schema、重复 id 与 `OVERLAY` / `REGISTRY_CLAIMS` 规则、reload 期内容文件 wrapper 格式、STRICT 与 LEGACY 解码/校验差异、错误的分类与出口。

不覆盖：各 `properties` 键在注册表里的具体游戏玩法效果（见 `../../guides/data-driven/guide-content.md`）、状态机定义与动画片段元素内部的完整 codec 字段（见 `../fsm.md` 与 `uml/` 下的 codec）、Java 侧 `TypeHandler` 扩展细节（见 `api-extension.md` 与 `../../guides/data-driven/guide-extension.md`）。

## 1. 索引文件（index manifest）

### 1.1 位置与发现规则

索引目录固定为 **4 段路径**：

```text
data/<namespace>/kasuga_lib/data_driven/
```

- 由 `JsonTreeBuilder.indexDirectorySegments(modId)` 锁定，返回 `{"data", modId, "kasuga_lib", "data_driven"}`（`JsonTreeBuilder.java:766-768`）。
- 注意 `kasuga_lib` 是固定拼写，`data_driven` 用下划线。写成 `kasuga-lib` 或漏掉 `kasuga_lib` 这一层都会导致**静默加载 0 条**；该风险由 `JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout()` 用 `assertArrayEquals` 锁死。
- 默认情况下，目录下**所有**直接子 `.json` 文件都被当作索引，按路径/文件名升序读取（`JsonTreeBuilder.listIndexFiles`，`:783-795`；`ContentSource.indexes()`，`ContentSource.java:37-65`）。不递归子目录。文件名无约定，`index.json` 只是惯例。
- 在 v2 `SnapshotContentSource` 中，宿主可通过 `declaredIndexes` 参数显式指定当前注册场景可见的索引路径集合（例如 parcel 根只暴露 `parcel.json.indexes`）；传 `null` 恢复默认目录扫描，传空集合表示纯资源 root（零 manifest）。详见 [api-registration.md](api-registration.md) §17。
- 旧入口中目录不存在时只记 DEBUG，不算错误（`JsonTreeBuilder.java:83`）；新管线中无索引的来源产生零定义，无 blocking error 时同样合法。
- reload 侧读同一目录时复用同一组路径段（`ReloadOrchestrator.indexResourcePath`，`ReloadOrchestrator.java:290-297`）。

### 1.2 顶层字段

索引文件的顶层字段**只有两个**，各自是字符串数组：

| 字段 | 类型 | 域 | 含义 |
|------|------|----|------|
| `on_register` | `string[]` | 注册期 | 列出在注册构建阶段解析、写入 `Reg` 与 MC 注册表的内容文件 |
| `on_reload` | `string[]` | reload 期 | 列出在 `/reload` 解析、写入 reload 桶的内容文件（`DataDrivenBuildPipeline` 在 `STRICT` prepare 中也会预检 decode） |

两者都**可选**，但至少出现一个（D1/D2）。旧入口字段常量在 `JsonTreeBuilder.java:45,47`，解析在 `parseIndexManifest`（`:228-257`）；新管线解析在 `DataDrivenBuildPipeline.readManifest`（`DataDrivenBuildPipeline.java:513-588`）。

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

**`sources` 现在是非法字段。** 它不再被识别、不再有别名，走的是「未知顶层字段」通道：记 error（新管线中 code 为 `MANIFEST_INVALID`）+ 诊断记录，并且该文件因此「什么都没声明」（再记一条「两者皆未声明」error / `MANIFEST_EMPTY`）。

```json
{
  "sources": ["kasuga_lib_content/blocks.json"]
}
```

上例会产生**两条** error：(1) `contains unsupported field 'sources'`（新管线为 `Unsupported index field 'sources'`）；(2) `declares neither 'on_register' nor 'on_reload'`。写法见 `JsonTreeBuilder.parseIndexManifest`（`:231-238` 未知字段、`:247-253` 两者皆未声明）与 `DataDrivenBuildPipeline.readManifest`（`:553-581`）；由 `JsonTreeBuilderIndexSchemaTest.legacySourcesFieldIsReportedAsUnknown()` 锁定。

旧字段 `sources` 与旧平铺布局 `data/<namespace>/kasugalib/` 都已硬迁移删除，不做兼容期。若磁盘上存在旧目录且没有新索引目录，旧入口会打一条 WARN + 入桶并提示迁移到 `on_register`（`JsonTreeBuilder.java:73-86`）。

### 1.4 字段级规则 D1–D6

这些规则的**可执行版本**是 `JsonTreeBuilderIndexSchemaTest`（见 §6）。规则以 `parseIndexManifest`（单文件）与 `resolveIndexManifests`（跨文件聚合）实现，新管线 `readManifest` / `resolveNamespace` 遵循同一语义：

- **D1 至少声明一个字段**：两个字段都不出现（空文件、只有未知字段的文件）→ error + 诊断，该文件不贡献任何路径。空文件（解析为 `null`）同样报错（`JsonTreeBuilder.java:247-253`；`DataDrivenBuildPipeline.java:538-550,570-581`）。
- **D2 空数组合法**：`{"on_register": []}` 是合法的，且**计为已声明**（`JsonTreeBuilder.java:244`；`DataDrivenBuildPipeline.java:570`）。
- **D3 单字段失败不连累兄弟字段**：`on_register` 非数组时只报该字段错，`on_reload` 照读，反之亦然（`readPathArray`，`:263-279`；`readPathField`，`DataDrivenBuildPipeline.java:590-633`）。数组里非字符串的元素报错并跳过，其余元素保留（`:272-277`；`:617-629`）。
- **D4 聚合告警按「是否声明过」分支**：只声明 `on_reload` 的纯 reload mod **不报**「未声明注册域」；只有**任何文件都没声明任一字段**时，聚合阶段才记一条告警/诊断（`resolveIndexManifests`，`:347-352`；`resolveNamespace`，`DataDrivenBuildPipeline.java:642-653`）。单文件层面都声明失败时聚合错误照报。
- **D5 `sources` 是未知字段**，不是别名（`:231-238`）。
- **D6 同一路径双列 fail-closed**：同一路径（同文件内或跨文件）同时出现在两个数组 → error + 诊断，且该路径**两个域都不消费**（从两个列表里都删除）。判定跨该 mod / 该 source namespace 的**全部**索引文件（`resolveIndexManifests`，`:330-345`；`resolveNamespace`，`DataDrivenBuildPipeline.java:661-678`）。

### 1.5 路径条目规则

数组元素是相对于 `data/<namespace>/` 的路径，**必须**带 `.json` 后缀。校验在 `JsonTreeBuilder.validateSourcePath`（`:407-416`），reload 侧（`ReloadOrchestrator.collectIndex`，`:395`）与 v2 管线（`DataDrivenBuildPipeline.locateContent`，`:712-762`）均复用同一函数；v2 管线还会构造 `LogicalPath` 进行额外安全检查。违反任一条 → error + 诊断并跳过该条：

| 规则 | 违反时的消息片段 / 约束 |
|------|------------------|
| 非空 | `path is empty` |
| 不以 `/` 开头（必须相对） | `path must be relative` |
| 以 `.json` 结尾 | `path must include the '.json' suffix` |
| 无空段（`//`） | `path contains an empty segment` |
| 不含 `.` 或 `..` 段 | `path must not contain '.' or '..'` |
| `LogicalPath` 字符约束（v2 `DataDrivenBuildPipeline`） | 禁止 `\`、Windows 盘符前缀（如 `C:`）、NUL 与 ISO 控制字符（`LogicalPath.java:18-47`） |

补充：

- 路径**不可跨命名空间**是结构性的：`parseSource` 拼在 `data/<modId>/` 下（`JsonTreeBuilder.java:441-446`），`DataDrivenBuildPipeline.locateContent` 拼在 `data/<namespace>/` 下（`DataDrivenBuildPipeline.java:724`）。指向 `assets/` 的条目不可能生效。
- 同一路径在同一 mod / source namespace 的同一域内被多处列出 → 注册期静默去重，只解析一次（`JsonTreeBuilder.parseSource` 的 `parsedSources`，`:427`；`DataDrivenBuildPipeline.resolveNamespace` 的 `seenRegister` / `seenReload`，`:680-709`）。运行期 `ReloadOrchestrator.collectIndex` 当前不对同一 `on_reload` 列表额外去重，请保持每个路径只列一次。
- reload 侧还要求路径能构成合法 `ResourceLocation`（只允许 `[a-z0-9/._-]`）；大写等非法字符会单独报错（`ReloadOrchestrator.java:401-412`）。注意 `DataDrivenBuildPipeline` 的 `on_reload` 预检只检查 `LogicalPath` 与来源存在性，不会提前构造 `ResourceLocation`。
- 路径非法、文件缺失、JSON 不可读都会产生 error（`parseSource`，`:419-462`；`locateContent` / `parseContentFile`，`DataDrivenBuildPipeline.java:712-800`）。

### 1.6 多清单合并与严格 JSON

同一 mod / 同一 `ContentSource` 的全部索引文件先**全部读入**，再统一消费：

- 旧 `JsonTreeBuilder`：按「索引文件名升序 → 文件内数组书写顺序」拼接路径（`JsonTreeBuilder.java:97-117,318-356`）。
- v2 `DataDrivenBuildPipeline`：按 source 全局 `rank` 升序处理，同一 source 内聚合每个 namespace 的 manifest，先排除跨域冲突并按逻辑路径去重，再按 `logicalPath` 字典序排序后解析 `on_register` 与 `on_reload` 文件（`DataDrivenBuildPipeline.java:463-511`）。
- **STRICT 模式 JSON 解码（`StrictResourceJson`）**：当 `DataDrivenBuildPipeline` 以 `BuildOptions.STRICT` 运行时，manifest、注册内容与 `on_reload` 预检全部先经过严格 UTF-8 解码（拒绝畸形字节）与 `StrictResourceJson.parse`（`DataDrivenBuildPipeline.java:1559-1574`、`StrictResourceJson.java:14-51`）：
  - `reader.setLenient(false)`：拒绝注释、单引号、尾逗号等非标准 JSON；
  - 拒绝单个文档外的多余内容，要求整份文件恰好是一个 JSON object；
  - 对象内出现**重复键**立即报错（不静默取后者）；
  - 最大文本长度 8 MiB、默认最大深度 64、最多 100,000 个节点。
- `LEGACY` 模式与旧 `JsonTreeBuilder` 使用 Gson 宽容解析，且在 `LEGACY` 中遇到 `MANIFEST_INVALID` 时不会自动阻止 `build` 的 apply。

## 2. 注册期内容文件（列在 `on_register`）

### 2.1 文件顶层结构

内容文件的**顶层字段名 = 顶层注册类型**，值必须是**对象数组**。内建顶层类型有 3 个，外加 1 个内嵌类型：

| 顶层字段 | handler | phase | 作用 |
|----------|---------|-------|------|
| `registry_groups` | `RegistryGroupHandler` | 0 | 建立属性分组（父/子） |
| `blocks` | `BlockTypeHandler` | 1 | 注册方块 |
| `items` | `ItemTypeHandler` | 1 | 注册独立物品 |
| `block_entities`（内嵌） | `BlockEntityTypeHandler` | 2 | 内嵌于 `blocks[].block_entity`，不可写为顶层字段 |

一个内容文件可同时包含多个顶层字段（例如 `registry_groups` 与 `blocks` 各一组），由 `dispatchContent`（`JsonTreeBuilder.java:485`）或 `DataDrivenBuildPipeline.parseContentFile`（`DataDrivenBuildPipeline.java:764-917`）逐个分派。

```json
{
  "registry_groups": [
    { "id": "mymod:panels", "properties": { "no_occlusion": true } }
  ],
  "blocks": [
    {
      "id": "mymod:panel",
      "type": "data_driven_block",
      "registry_group": "mymod:panels",
      "properties": { "destroy_time": 2.0 },
      "item_properties": { "tab": "mymod:main" }
    }
  ]
}
```

顶层字段的校验：

- 未知顶层字段：旧入口与 `STRICT` 为 `ERROR`（code `UNKNOWN_TYPE`），`LEGACY` 管线为 `WARNING`；`JsonTreeBuilder` 与 `LEGACY` 下**同文件其余字段照常分派**（半应用），`STRICT` 下会记为 blocking diagnostic 并在 apply 前整批拒绝（`REJECTED`）。
- 已知字段但值不是数组 → `ERROR`（`CONTENT_INVALID`），其余字段照常解析。
- 数组元素不是 JSON 对象 → `ERROR`（`CONTENT_INVALID`），该元素跳过，其余元素照常解析。

### 2.2 `registry_groups` 条目

解析见 `RegistryGroupHandler.parse`（`RegistryGroupHandler.java:45-49`），应用见 `store`（`:108-141`），严格校验见 `validate`（`:71-98`）。

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | 是 | — | 分组 id。**按原字符串匹配**，不做命名空间归一化 |
| `parent` | string | 否 | `null` → 挂根组 | 父分组 id；父不存在时旧入口回退挂根组（`:111-116`），v2 `STRICT` 会校验父组存在且可见 |
| `properties` | object | 否 | — | 传给子内容的方块属性（见 §2.6） |
| `item_properties` | object | 否 | — | 传给子内容的物品属性；其中 `tab` 单独抽出作为该组的创造栏（`:126-127`） |

- 分组**没有 `type` 字段**。
- 方块/物品通过 `registry_group` 用原字符串查组；因此 `id` 不归一化，`mymod:panels` 与 `panels` 是两个不同的组（`RegistryGroupHandler.resolveIdentity`，`:66-68`）。
- 应用前按 `parent` 做拓扑排序：父先于子；环在旧入口与 `LEGACY` 中只打 WARN / warning diagnostic、回退原顺序（`JsonTreeBuilder.topoSortGroupDefs`，`:890`），在 v2 `STRICT` 中记为 `GROUP_CYCLE` blocking error 并拒绝整批构建（`DataDrivenBuildPipeline.java:1233-1297`）。
- v2 `STRICT` 会用 `JsonPropertyParser.validateStrict` 和 `JsonItemParser.validateStrict` 检查组内的 `properties` / `item_properties`，未知键或非法值会产生 `CONTENT_INVALID` error。

### 2.3 `blocks` 条目

解析见 `BlockTypeHandler.parse`（`BlockTypeHandler.java:31-36`），通用应用见 `RegTypeHandler.apply`（`RegTypeHandler.java:155-205`），严格校验见 `RegTypeHandler.validate`（`:102-141`）与 `BlockTypeHandler.validate`（`:70-105`）。

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | 是 | — | 方块 id；`STRICT` 要求显式 `namespace:path`，旧入口/`LEGACY` 允许裸路径 |
| `type` | string | 是 | — | 工厂名，查 `FactoryRegistry.BlockFactory`（`BlockTypeHandler.createRegistration`，`:126-133`） |
| `registry_group` | string | 否 | `null` → 挂根组 | 所属分组 id（原串匹配） |
| `properties` | object | 否 | — | 方块属性，见 §2.6 |
| `state_properties` | object | 否 | — | Minecraft 方块状态属性，见 [block-state-properties.md](block-state-properties.md) |
| `item_properties` | object | 否 | — | 方块对应的物品属性；`tab` 决定创造栏（`BlockTypeHandler.resolveCreativeTab`，`:64-67`） |
| `params` | object | 否 | `null` | 原样传给工厂 `create(path, params)`（`StatefulBlockFactory` 额外收到 `state_properties`） |
| `state_machine` | JSON primitive | 否 | — | 仅当条目内含 `block_entity` 时被转发进 BE `params.state_machine`（见 §2.5） |
| `block_entity` | object | 否 | — | 内嵌方块实体定义（见 §2.5） |

- `id` 与 `type` 缺失时 `parse` 抛异常（`Objects.requireNonNull`），在旧入口中只丢该条目、兄弟照常；在 `STRICT` 管线中记 `PARSE_FAILED` 并拒绝整批构建。
- `type` 找不到对应工厂：在旧入口且未声明 `state_properties` 时只打 WARN 并跳过（`RegTypeHandler.java:162-166`）；在 v2 `STRICT` 中无论是否声明 `state_properties`，缺失工厂均报 `UNKNOWN_FACTORY` error，声明了 `state_properties` 但工厂未实现 `StatefulBlockFactory` 同样报 `UNKNOWN_FACTORY` error（`BlockTypeHandler.java:92-104`）。
- 已知条目对象的**顶层未知键**（如方块对象里写 `foo`）仍被 `BlockDef` Gson 反序列化忽略；但 `STRICT` 下会严格检查 `properties`、`item_properties` 与 `state_properties` 内部键。
- 内建支持 `state_properties` 的普通方块工厂为 `data_driven_block` 与 `proxy_model`（见 §2.9）。

### 2.4 `items` 条目

解析见 `ItemTypeHandler.parse`（`ItemTypeHandler.java:30-35`），严格校验见 `:59-71`。

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | 是 | — | 物品 id；`STRICT` 要求显式 `namespace:path` |
| `type` | string | 是 | — | 工厂名，查 `FactoryRegistry.ItemFactory`（`:74-78`） |
| `registry_group` | string | 否 | 挂根组 | 所属分组 id |
| `properties` | object | 否 | — | 物品属性，见 §2.6；`tab` 决定创造栏（`:53-56`） |
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

- 顶层字段名是 `block_entities`，但内嵌键名是 `block_entity`（`BlockEntityTypeHandler.getTypeName` `:29`、`getParentTypeName` `:48`、`getEmbeddedKeyName` `:51`）。
- 提取逻辑：`BlockEntityTypeHandler.extractEmbedded`（`:54-66`）把宿主方块的 `id` 注入内嵌对象的 `_parent_block`；若宿主方块顶层有 JSON 原始类型（primitive：string / number / boolean）的 `state_machine`，再转发进内嵌对象的 `params.state_machine`（`:59-64`）。判据是 `isJsonPrimitive()`，故 number / boolean 也放行，经 `getAsString()` 转成文本后存入 `params`；对象、数组、null 被忽略。

内嵌 `block_entity` 对象的字段：

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `type` | string | 是 | — | 方块实体工厂名，查 `FactoryRegistry.BlockEntityFactory`（例如内建 `proxy_model_be` / `fsm_be`） |
| `params` | object | 否 | `null` | 传给工厂；具体键由工厂解释（`proxy_model_be` 参数见 §2.9） |
| `_parent_block` | string | — | 注入 | **不要手写**，由 `extractEmbedded` 注入 |

- 重复判定：两条 BE 条目挂在**同一个宿主方块**上即视为重复（`resolveIdentity`，`:87-96`，用宿主方块的 effective id）。
- 注册表 claim：v2 中会派生 `minecraft:block_entity_type` / `<block_ns>:<block_path>_be`（`claims`，`:124-134`）。
- 宿主方块找不到 / 未知 BE 工厂：旧入口只打 WARN（`BlockEntityTypeHandler.apply`，`:141-152`）；v2 `STRICT` 在 `validate` 中检查宿主方块定义可见性与 BE 工厂存在性（`MISSING_REFERENCE` / `REFERENCE_NOT_DECLARED` / `UNKNOWN_FACTORY`），且 `apply` 异常在 strict context 下不再被吞掉而是传播为 `APPLY_FAILED`（`:163`）。

### 2.6 `properties` / `item_properties` 内部键

顶层字段的 `properties` 与 `item_properties` 由两个解析器认领。**行为差异**：旧入口与 `LEGACY` 对未知键或多数非法值只打 WARN 并忽略该修饰器；v2 `STRICT` 会调用 `JsonPropertyParser.validateStrict` / `JsonItemParser.validateStrict`，把未知键和越界/错类型值记为 `CONTENT_INVALID` blocking error（`BlockTypeHandler.java:72-91`、`ItemTypeHandler.java:61-70`、`RegistryGroupHandler.java:78-97`）。

**方块属性 `properties`**（`JsonPropertyParser.buildCompilers`，`JsonPropertyParser.java:248-274`；严格校验 `:45-109`）：

| 键 | 值类型 | 键 | 值类型 |
|----|--------|----|--------|
| `no_occlusion` | bool | `light_emission` | int（STRICT 要求 `0..15`） |
| `no_collision` / `no_collission`（拼写别名） | bool | `friction` | float（有限数） |
| `requires_correct_tool` | bool | `speed_factor` | float（有限数） |
| `replaceable` | bool | `jump_factor` | float（有限数） |
| `dynamic_shape` | bool | `destroy_time` | float（有限数） |
| `random_ticks` | bool | `explosion_resistance` | float（有限数） |
| `no_loot_table` | bool | `strength` | float 或 `[destroy, resist]` 两元有限数数组 |
| `ignited_by_lava` | bool | `map_color` | `DyeColor` 名（小写） |
| `liquid` | bool | `sound_type` | 声音类型名（见下） |
| `force_solid_on` | bool | | |
| `air` | bool | | |
| `no_terrain_particles` | bool | | |

- `sound_type` 的合法值来自 `JsonPropertyParser.buildSoundTypeMap`（`:197-246`），如 `stone` / `wood` / `metal` / `glass` 等。
- `map_color` 用 MC `DyeColor` 名。

**物品属性 `item_properties`（方块条目）/ `properties`（物品条目）**（`JsonItemParser`，`JsonItemParser.java:23-40`；严格校验 `:57-123`）：

| 键 | 值类型 | 含义 |
|----|--------|------|
| `stacks_to` | int | 最大堆叠数（STRICT 要求 `1..99`） |
| `rarity` | string | `COMMON` / `UNCOMMON` / `RARE` / `EPIC`（大小写不敏感） |
| `fire_resistant` | bool | 防火 |
| `durability` | int | 耐久（STRICT 要求 `>= 1`） |
| `no_repair` | bool | 不可修复 |
| `tab` | string | 创造栏 `ResourceLocation`；由 loader 单独消费（STRICT 要求有效 `namespace:path`） |

属性沿父链继承，子条目覆盖父条目（`RegTypeHandler.apply` 设 `registry_group` 为父，`Reg.applyProperties` 父先子后）。

### 2.7 对象内未知键 vs 文件顶层未知键

两种位置的严格度不同：

- **文件顶层**未知字段 → 旧入口与 `STRICT` 为 error（`UNKNOWN_TYPE`），`LEGACY` 为 warning（`JsonTreeBuilder.java:496-518`、`DataDrivenBuildPipeline.java:807-823`）。拼错 `blokcs` 会被抓住。
- **条目对象内**未知键 → 各 handler 的 `parse` 通常只读已知键，顶层条目多写一个 `foo` 不会报解析错；但 `state_properties` 内部的属性定义始终严格检查未知键（`StatePropertySet.requireKeys`），且 `STRICT` 模式下 `properties` / `item_properties` 内部未知键也会报 `CONTENT_INVALID`。

### 2.8 内嵌类型误写为顶层字段

把 `block_entities` 当顶层字段写（而不是放进 `blocks` 条目的 `block_entity` 键）会走「未知顶层字段」通道：

- 旧入口 `JsonTreeBuilder`：error + 入桶，消息点名「应写在 `blocks` 条目的 `block_entity` 键」，并且**同文件其余的 `blocks` 照常分派**（`JsonTreeBuilder.java:496-518`）。
- v2 `DataDrivenBuildPipeline`：按 `UNKNOWN_TYPE` 报告（`STRICT` 为 `ERROR` 并拒绝整批构建，`LEGACY` 为 `WARNING`）。

```json
{
  "block_entities": [ { "type": "test_be" } ],
  "blocks": [ { "id": "mymod:ok", "type": "simple_block" } ]
}
```

旧入口行为由 `JsonTreeBuilderEmbeddedTypeDispatchTest.topLevelEmbeddedTypeIsReportedAndTheRestOfTheFileStillDispatches()` 锁定。内嵌对象自身若因缺字段解析失败，旧入口只丢该内嵌元素、兄弟照常；`STRICT` 则记 `PARSE_FAILED` 并拒绝整批。

### 2.9 内建 `proxy_model` / `proxy_model_be` 参数

`modules/modelling` 提供了一对内置通用模型代理工厂（`ProxyModelBlockFactories.java`，由 `@Context` bean 在启动期自动注册，也可显式调用 `ProxyModelBlockFactories.registerBuiltin()`），用于在纯 JSON 中声明带状态属性和可切换多部件模型组的实体渲染方块：

- **方块工厂 `type: "proxy_model"`**（`ProxyModelBlockFactories.BLOCK_TYPE`）：实现 `StatefulBlockFactory` 并登记 `createsBlockItem = true`，生成 `ProxyModelBlock` 与同 id 默认方块物品。
  - 兼容标准 `state_properties`：若声明原版 `facing`（`HORIZONTAL_FACING`）或 `hinge`（`DOOR_HINGE`），`ProxyModelBlock` 会自动处理放置朝向、铰链左右手、`rotate` 与 `mirror`（`ProxyModelBlock.java:71-113`）。
  - 渲染形状固定为 `RenderShape.ENTITYBLOCK_ANIMATED`，避免缺失原版烘焙方块模型时显示紫黑格。
  - **禁止块级 `params`**：`ProxyModelBlockFactories.validateBlockParams`（`:77-84`）拒绝非空 `blocks[].params`（业务交互应通过专用子类和自定义工厂实现）。
- **内嵌方块实体 `block_entity.type: "proxy_model_be"`**（`ProxyModelBlockFactories.BLOCK_ENTITY_TYPE`）：生成 `ProxyModelBlockEntity`。

`proxy_model_be` 支持的 `block_entity.params` 字段（源码：`ProxyModelBlockFactories.java:155-401`）：

| 字段 | 类型 | 必填 | 默认 | 含义与校验规则 |
|---|---|---|---|---|
| `model` | string (`ResourceLocation`) | 与 `model_groups` 二选一 | — | 单模型语法糖：生成一个 id 为 `"default"`、仅含单个 part（id `"body"`）的 `CompiledModelGroup`。若 `model` 与 `model_groups` 均缺失则抛异常 |
| `model_name` | string | 否 | `null` | 模型文件内部子模型名（仅 MMD `.pmx` 使用），配合 `params.model` 生效 |
| `default_model` | string | 否 | 见右 | 当无 `model_by_state` 规则命中时使用的默认 group id（必须在 `model_groups` 中存在）。省略时若提供了 `params.model` 则使用 `"default"`，否则取 `model_groups` 的第一个条目 |
| `yaw_offset` | number (float) | 否 | `0.0` | 在方块朝向 yaw 基础上叠加的额外水平偏航角（度） |
| `facing_yaw` | object (`string -> float`) | 否 | `{}` | 按 `HORIZONTAL_FACING` 序列化名称（`"north"` / `"east"` / `"south"` / `"west"`）显式覆盖朝向角度（度）；命中时取代 `facingYaw(state) + yaw_offset` |
| `lighting_mode` | string | 否 | `null` | 可选 `"pbr"`（`ModelLightingMode.PBR`）或 `"vanilla_block"`（`ModelLightingMode.BLOCK`）；省略时保留模型资产自带默认设置；传入其他字符串或非字符串会在注册期抛 `IllegalArgumentException` |
| `model_groups` | object (`<group_id> -> group`) | 否（无 `model` 时必填） | — | 命名多部件模型组映射；`<group_id>` 须匹配 `[a-z0-9_.:/-]+` |
| `model_by_state` | object (`<prop> -> {<val>: <target>}`) | 否 | `{}` | 按 JSON 键顺序求值的状态选择规则；命中第一个存在映射的规则即返回对应模型组 |
| `animation` | — | **禁止** | — | 显式不支持，存在即抛 `IllegalArgumentException`；状态驱动播放应在 Java `ProxyModelBlock.createProxyComposition` 中实现 |

`model_groups.<group_id>` 对象结构：

| 字段 | 类型 | 必填 | 默认 | 含义 |
|---|---|---|---|---|
| `y_offset` | number (float) | 否 | `0.0` | 该模型组激活时叠加在 proxy 根变换上的 Y 轴偏移（须为有限浮点数） |
| `parts` | object[] | 是 | — | 非空有序部件数组（`CompiledModelPart`） |

`parts[]` 数组中每个部件对象结构：

| 字段 | 类型 | 必填 | 默认 | 含义 |
|---|---|---|---|---|
| `id` | string | 是 | — | 非空部件标识，须匹配 `[a-z0-9_.-]+` 且在组内唯一 |
| `model` | string (`ResourceLocation`) | 是 | — | 部件模型资源路径 |
| `model_name` | string | 否 | `null` | 部件模型内部子模型名（`.pmx`） |
| `local` | object[] | 否 | `[]` | 依次施加在部件上的局部变换操作数组；每个对象必须包含且仅包含 `translate`、`rotate`、`scale` 三键之一，值为 3 个有限数字数组（`rotate` 单位为度） |

关键约束（`ProxyModelSelection.java:40-47, 80-98`）：

- **part id 集合必须跨组一致**：同一个 `proxy_model_be` 中所有模型组（包含 `default_model` / `model` 生成的 `"default"` 组以及 `model_by_state` 直接写模型路径隐式生成的单 part `"body"` 组）必须声明**完全相同的有序 `part.id` 列表**，否则注册期抛出 `IllegalArgumentException`。
- **`model_by_state` 值解析与注册期校验**：`<target>` 先在 `model_groups` 中按 group id 查找；若未找到，则按 `ResourceLocation` 解析并自动包装成单 part（id `"body"`）的 `"legacy_<ns>_<path>"` 组。方块与方块实体均注册完成后（`onRegistered`），`ProxyModelSelection.validate(block.defaultBlockState())` 会校验每条规则引用的状态属性名与每个值是否真实存在于该方块的 `BlockState` 上。

单模型最小示例：

```json
{
  "blocks": [
    {
      "id": "example:sign_post",
      "type": "proxy_model",
      "state_properties": {
        "facing": { "type": "vanilla", "property": "HORIZONTAL_FACING", "default": "north" }
      },
      "block_entity": {
        "type": "proxy_model_be",
        "params": {
          "model": "example:models/block/sign_post.bbmodel",
          "yaw_offset": 180.0,
          "lighting_mode": "vanilla_block"
        }
      }
    }
  ]
}
```

多部件 `model_groups` + `model_by_state` + `facing_yaw` 示例：

```json
{
  "blocks": [
    {
      "id": "example:sliding_gate",
      "type": "proxy_model",
      "state_properties": {
        "facing": { "type": "vanilla", "property": "HORIZONTAL_FACING", "default": "north" },
        "open": { "type": "vanilla", "property": "OPEN", "default": false }
      },
      "block_entity": {
        "type": "proxy_model_be",
        "params": {
          "default_model": "closed",
          "facing_yaw": { "north": 0, "east": 90, "south": 180, "west": 270 },
          "lighting_mode": "pbr",
          "model_groups": {
            "closed": {
              "y_offset": 0.0,
              "parts": [
                { "id": "frame", "model": "example:models/block/gate_frame.bbmodel" },
                {
                  "id": "leaf",
                  "model": "example:models/block/gate_leaf.bbmodel",
                  "local": [ { "translate": [0, 0, 0] } ]
                }
              ]
            },
            "opened": {
              "y_offset": 0.05,
              "parts": [
                { "id": "frame", "model": "example:models/block/gate_frame.bbmodel" },
                {
                  "id": "leaf",
                  "model": "example:models/block/gate_leaf.bbmodel",
                  "local": [
                    { "translate": [0.75, 0, 0] },
                    { "rotate": [0, 15, 0] }
                  ]
                }
              ]
            }
          },
          "model_by_state": {
            "open": {
              "false": "closed",
              "true": "opened"
            }
          }
        }
      }
    }
  ]
}
```

运行期可通过 `/kasuga_proxy list`（`ProxyCommands.java`）检查所有活跃 `ProxyInstance` 的 `stage`、`model`、`parts` 与 `mounted` 状态。

## 3. 重复 id、OVERLAY 与冲突检查

### 3.1 旧入口 `JsonTreeBuilder` 的去重（last-wins）

在旧入口 `JsonTreeBuilder` 中，去重在**任何注册副作用之前**完成：解析全部结束后先裁决，败者根本不进入 `apply`（`JsonTreeBuilder.resolveDuplicates`，`:673`）。这是因为 MC 的 `MappedRegistry.register` 不拒绝重复 key，重复 apply 会留下不一致条目（`DuplicateIdResolver.java:11-14`）。

冲突空间是 `(typeName, identity)`，不同类型同 id 不算冲突。各类型的 identity：

| 类型 | identity（旧入口 / `LEGACY`） | identity（v2 `STRICT`） |
|------|----------|--------|
| `blocks` / `items` | `EffectiveId.ofLegacy(modId, id)`：无命名空间或 `minecraft:` 前缀 → `<modId>:path`；其它命名空间原样 | `EffectiveId.of(modId, id)`：裸 id 映射 `<modId>:path`（但 `validate` 会以 `INVALID_IDENTITY` 拒绝裸 id）；**显式 `minecraft:` 保持 `minecraft:path`** |
| `block_entities` | 宿主方块的 `EffectiveId.ofLegacy` | 宿主方块的 `EffectiveId.of` |
| `registry_groups` | `id` 原字符串（不归一化） | `id` 原字符串（不归一化） |

`EffectiveId.of` 与 `EffectiveId.ofLegacy` 见 `EffectiveId.java:39-67`。

旧入口 last-wins 的「后」是**加载序列中的位置**，不是路径的字典序：

1. 索引文件之间：文件名升序；
2. 一个索引文件内：`on_register` 数组书写顺序；
3. 一个内容文件内：类型字段数组书写顺序；
4. 内嵌 BE：跟随宿主 `blocks` 条目的位置。

冲突结果：胜者进入 `apply`；败者整条丢弃（其内嵌 BE 一并隔离），记 **WARN 日志 + 一条 loading error 桶条目**（`JsonTreeBuilder.java:688-696`）。

### 3.2 v2 `DataDrivenBuildPipeline` 的选择与 claims 检查

v2 管线按 `TypeHandler.capabilities()` 与 `BuildOptions` 区分处理（`DataDrivenBuildPipeline.selectDefinitions`，`:1149-1183`）：

- **配置类型（`NON_REGISTRATION` 且无 `OVERLAY`）及 `LEGACY` 普通类型**：按 `(sourceOrder, declarationOrder)` 取最大者（last-wins），其中 `sourceOrder` 来自平台 `ResourceSourceDescriptor.rank()`（旧 `SourceDescriptor` 按输入下标）。`allDefinitions` 仍保留全部候选供审计。
- **叠加类型（`HandlerCapability.OVERLAY`）**：无论 `STRICT` 还是 `LEGACY`，同 `DefinitionKey` 的**全部候选均进入 selected**，由 handler 自行合并或叠加。
- **严格注册类型（`STRICT` 且带 `REGISTRY_CLAIMS`、不带 `NON_REGISTRATION`）**：保留同 `DefinitionKey` 的全部候选，逐条执行 `validate`，并在 apply 前收集所有 `RegistryClaim`（`collectClaims`，`:1304-1401`）：
  - 与宿主 `reservedRegistryClaims`（可由 `RegistryClaimSnapshot.collect` 生成）冲突 → `CLAIM_CONFLICT`（两种模式均记 `ERROR`）；
  - 不同定义 claim 同一 `(registry, id)` → `CLAIM_CONFLICT`（`STRICT` 为 `ERROR` 并整批 `REJECTED`，`LEGACY` 为 `WARNING`）。

## 4. reload 期内容文件（列在 `on_reload`）

### 4.1 两类内容与发现方式

`ReloadOrchestrator` 是 reload 域唯一的编排者（`ReloadOrchestrator.java:121`），它本身不认识任何类型名：每个已注册的 `ReloadHandler` 的 `typeName()` 就是一个合法顶层键，编排器遍历 `ReloadHandlerRegistry` 得到键集合。当前 modelling 侧注册的两个 handler 是 `state_machines` 与 `animation_clips`（`FsmReloadHandler` / `FsmClipsReloadHandler`，见 §4.6）。

| 内容 | 顶层键 | 发现方式 |
|------|--------|----------|
| 状态机定义 | `state_machines` | **目录 glob** `data/<ns>/state_machines/*.json` **+** `on_reload` 列出 |
| 动画片段 | `animation_clips` | **仅** `on_reload` 列出 |

- `state_machines/` 有目录 glob：`FsmReloadHandler.globDirectories()`（`FsmReloadHandler.java:69-71`）声明 `StateMachineDefinitionLoader.PATH`（`StateMachineDefinitionLoader.java:39`），由 `ReloadOrchestrator.collectGlob`（`ReloadOrchestrator.java:365-385`）用 `ResourceManager.listResourceStacks` 列举。未受 `ManagedReloadAllowlist` 管控的传统数据包/mod 包中的 `state_machines/*.json` 即使未被索引列出也会被读。
- **受管来源（`ManagedReloadAllowlist`）**：由 `ApprovedResourcePacks` 投影的受管资源包会经过 `ManagedReloadAllowlist` 过滤（`ReloadOrchestrator.java:317-332,375-380,419-437`）：受管包中的索引与 `on_reload` 内容只允许声明白名单批准的路径，且不参与无索引的目录 glob。
- `animation_clips/` **没有目录 glob**。未被索引列出的 clip 文件**永远不被读**（`AnimationClipLoader.java:31-32`；`FsmClipsReloadHandler.globDirectories` 保持默认空集）。
- 索引列出的 `state_machines/` 文件会被 glob 入口跳过，避免读两次（`ReloadOrchestrator.collectGlob`，`:372-374`）。
- 应用顺序：glob 发现在前、索引列出在后；同 id 后者胜（类文档 `:82-94`；`register`，`:562-577`）。
- **启动期预检**：`DataDrivenBuildPipeline.prepare` 也会读取 manifest 中 `on_reload` 列出的文件，以 `STRICT`（`StrictResourceJson`）或 `LEGACY` 解析，并按顶层键调用 `ReloadHandlerRegistry` 的 `decode` 验证格式，但不执行 `clearBucket()` 或 `register()`（`DataDrivenBuildPipeline.validateReloadContentFile`，`:919-1025`）。

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

- 一个文件可放多个定义，**数组顺序即文件内 last-wins 顺序**（`StateMachineDefinitionLoader.java:26-28`）。
- 元素 schema 由 `StateMachineDefinition.CODEC` 决定；本页只规范 wrapper，元素字段见 `../fsm.md` 与对应 codec。

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

- 元素自带 `id`（就是 `states[].clip` 引用的 id），loader 不额外抽取 id（`AnimationClipLoader.java:23-26`）。
- 元素里 codec 未命名的键被忽略，可作注释/编辑器元数据（`:26-27`）。已移除的旧键 `functions` 与 `formula_parts` 会被 `decodeFile` 显式拒绝并提示改为 `formula_transforms`（`AnimationClipLoader.java:100-108`）。
- 文件只在 `on_reload` 中生效，注册进 reload 桶；同名 SCRIPT 片段不被文件覆盖（`AnimationClipLoader.register`，`:127-132`）。

### 4.4 文件级严格、元素级容错

两个 decoder 的失败隔离一致（`StateMachineDefinitionLoader.decodeFile` `:67-100`；`AnimationClipLoader.decodeFile` `:71-116`）：

- **文件级**：非对象 body、缺顶层键、值非数组 → **整个文件拒绝**，报一条 error。`{"state_machines": {}}` 会被整体拒绝（`StateMachineDefinitionLoaderTest.nonArrayValueRejectsWholeFile`）。
- **文件级（真未知键）**：顶层出现不被任何 reload 类型认领的键 → 编排器报一条 `unsupported top-level field 'extra'`（或 `DataDrivenBuildPipeline` 报 `UNKNOWN_TYPE`），但**已知键在运行期仍照常贡献**（`StateMachineDefinitionLoaderTest.extraTopLevelKeyDoesNotAffectThisType()`；`AnimationClipLoaderTest.ignoresAnExtraTopLevelKey()`）。
- **多顶层键合法**：同一文件可同时携带 `state_machines` 与 `animation_clips`，两个 decoder 各读自己的键、各贡献各的条目（`AnimationClipReloadTest.mixedTopLevelKeysAreConsumedByBothHandlers`）。
- **元素级**：数组元素不是对象、或缺字段解不出（或使用已移除的 `functions`/`formula_parts`）→ 只跳过该元素，兄弟照常，逐个报错。
- 空数组合法（`AnimationClipLoaderTest.emptyClipArrayIsValid()`）。

### 4.5 reload 的域路由与反向 D9 提示

- 域由**索引数组**决定，内容类型由**顶层键**决定。
- reload 文件里的未知顶层键 → error + 记入 `RELOAD_DATA`，并附**反向提示**：「若这些字段是注册内容（如 `blocks`/`items`/`registry_groups`），请把文件改列到 `on_register`」（`ReloadOrchestrator.java:494-501`）。
- 一个文件可以同时承载两类内容：两个 handler 各认领自己的顶层键、各贡献各的条目（`ReloadOrchestrator.dispatch`，`:484-525`）。未知顶层键由 `ContentStructure.unknownKeys` 判定。
- reload 收尾会复核状态机引用的 clip；悬空引用只记 WARN + 记入 `RELOAD_DATA`，不影响注册（`FsmReloadHandler.afterReload`，`:95-115`）。

### 4.6 扩展到新 reload 类型

reload 类型不是硬编码的：继承/实现 `ReloadHandler<T>`，用一个 `@Context` bean 的 `@PostConstruct` 调 `ReloadHandlerRegistry.register(handler)` 注册即可，不需要改编排器。`typeName()` 就是该类型认领的顶层键，也兼作去重碰撞空间。详细步骤与最小示例见 [guide-extension.md](../../guides/data-driven/guide-extension.md) §6，接口签名见 [api-reload.md](api-reload.md) §13。

## 5. 错误的分类与出口

### 5.1 错误（error 日志 + 诊断记录）

- **旧入口与 reload 域**（进 `JsonTreeBuilder` mod 桶或 `Diagnostics.Domain.RELOAD_DATA`）：

| 情形 | 出处 |
|------|------|
| 索引未知字段 / 字段非数组 / 元素非字符串 | `JsonTreeBuilder.java:231-277` |
| 单文件未声明任一域；全部文件都未声明 | `:247-253`（error）/ `:347-352`（WARN + 桶） |
| 同一路径双列 | `:330-345` |
| 索引文件或目录读取失败 | `:194-204`、`:783-795` |
| 内容文件顶层未知字段、字段非数组、元素非对象 | `:496-535` |
| 内容文件路径非法 / 文件缺失 / 解析异常 | `:419-462` |
| handler `apply` 抛异常 | `:733-742` |
| 重复 id 冲突 | `:688-692`（WARN 日志 + 桶条目） |
| reload 侧：清单读取失败、路径非法、文件缺失、受管未授权、wrapper 形状违反、未知顶层键 | `ReloadOrchestrator.java:341-447,484-525` |
| reload 重复 id | `:602-610`（WARN 日志 + 桶条目） |
| reload 收尾悬空 clip 引用 | `FsmReloadHandler.java:105-113`（WARN 日志 + 桶条目） |

- **v2 `DataDrivenBuildPipeline`**：产生结构化 `BuildDiagnostic`（带 `Severity.ERROR`/`WARNING`、`Stage`、`Code`、`sourceId`、`ownerId`、`logicalPath`、`jsonPointer`、`handler`、`message`、`suggestion`），保存在 `PreparedContent.diagnostics()` 与 `BuildResult.diagnostics()` 中，**不会自动写入 `Diagnostics` 或 `/kasuga_data errors`**。见 [api-diagnostics.md](api-diagnostics.md) §19。

### 5.2 警告（warn，不入旧错误桶 / 或取决于模式）

旧入口中以下情形只打日志、不写桶，因为条目被降级而非格式错误；但在 v2 `STRICT` 下多数会提升为 `ERROR` blocking diagnostic：

- 未声明 `state_properties` 时，未知工厂 `type` → 旧入口跳过并 WARN（`RegTypeHandler.java:162-166`）；声明 `state_properties` 后工厂缺失或不支持，以及 v2 `STRICT` 下任一方块/物品/BE 缺失工厂，均记录 `UNKNOWN_FACTORY` 错误。
- 未知 `properties` / `item_properties` 键或非法属性值 → 旧入口忽略并 WARN（`JsonPropertyParser.java:152-159`、`JsonItemParser.java:147`）；v2 `STRICT` 通过 `validateStrict` 报 `CONTENT_INVALID` error。
- BE 宿主方块找不到 / 未知 BE 工厂 → 旧入口 WARN（`BlockEntityTypeHandler.java:141-152`）；v2 `STRICT` 报 `MISSING_REFERENCE` / `REFERENCE_NOT_DECLARED` / `UNKNOWN_FACTORY`。
- 分组引用环 → 旧入口 WARN（`JsonTreeBuilder.java:907-909`）；v2 `LEGACY` 为 `WARNING`，`STRICT` 为 `ERROR`（`GROUP_CYCLE`）。

### 5.3 诊断读取

- 旧入口与 reload 域：
  - `JsonTreeBuilder.getLoadingErrors(modId)` / `getLoadingErrorsByMod()` / `getLoadingErrors()`（`JsonTreeBuilder.java:827-859`）。
  - 统一门面 `Diagnostics`：`Diagnostics.errors(modId)`、`Diagnostics.errors(Domain, key)`、`Diagnostics.summarize()`（`Diagnostics.java:64-168`）。mod 维委托 `JsonTreeBuilder` 桶；reload 期写入 `Domain.RELOAD_DATA`、键 = 命名空间。
  - 游戏内命令：`/kasuga_data errors [mod]`（`DataDiagnosticsCommands.java`）与 `/kasuga_proxy list`（`ProxyCommands.java`）。
- v2 `DataDrivenBuildPipeline`：读取 `prepared.diagnostics()` / `result.diagnostics()` 与 `prepared.hasBlockingDiagnostics()`；宿主负责在日志、报告或命令中导出。

### 5.4 D9 提示文案（跨域误列）

两个方向的条件提示，错误消息字符串在旧入口与 `ReloadOrchestrator` 中是公共契约：

- **注册侧**：`JsonTreeBuilder` 中 `on_register` 列了 reload 域文件（顶层是 `state_machines` 之类）→ 走未知字段通道，消息尾部附「list this file under `'on_reload'` instead」，键名示例从 `ReloadHandlerRegistry` 运行时枚举，注册表为空时省略示例（`JsonTreeBuilder.java:580-591`；测试 `JsonTreeBuilderReloadHintTest.reloadDomainContentUnderOnRegisterIsReportedWithOnReloadHint()`）。
- **reload 侧**：`on_reload` 列了注册域文件 → 消息尾部附「list the file under `'on_register'` instead」，键名从 `TypeHandlerRegistry` 派生（`ReloadOrchestrator.java:494-501,543-554`）。

半应用保证：在旧入口与 `ReloadOrchestrator` 中误列一个域，同文件另一个域仍照常处理（`JsonTreeBuilderReloadHintTest.halfApplyStillDispatchesSiblingRegistrationFields()`）。

## 6. 规则出处对照

| 规则 | 出处（类:行 / 测试方法） |
|------|--------------------------|
| 4 段索引路径 | `JsonTreeBuilder.indexDirectorySegments` `:766-768`；`JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout` |
| 顶层仅 `on_register`/`on_reload` | `JsonTreeBuilder.java:45,47,231-238`；`DataDrivenBuildPipeline.java:55,553-566` |
| D1 至少声明一个域 | `JsonTreeBuilder.java:247-253`；`DataDrivenBuildPipeline.java:570-581` |
| D2 空数组合法 | `JsonTreeBuilder.java:244`；`emptyArrayIsValidAndCountsAsDeclared` |
| D3 兄弟字段不连累 | `JsonTreeBuilder.java:263-279`；`DataDrivenBuildPipeline.java:590-633` |
| D4 纯 reload 不误报 | `JsonTreeBuilder.java:347-352`；`pureReloadModDoesNotRaiseTheAggregateError` |
| D5 `sources` 非法 | `JsonTreeBuilder.java:231-238`；`legacySourcesFieldIsReportedAsUnknown` |
| D6 双列 fail-closed | `JsonTreeBuilder.java:330-345`；`DataDrivenBuildPipeline.java:661-678` |
| 路径条目规则与安全 | `JsonTreeBuilder.validateSourcePath` `:407-416`；`LogicalPath.java:18-47` |
| 严格 JSON（STRICT） | `StrictResourceJson.java:14-51`；`DataDrivenBuildPipeline.parseJson` `:1559-1574` |
| 顶层类型表 | `JsonTreeBuilder.java:485-541`；`DataDrivenBuildPipeline.java:802-868` |
| `blocks` / `items` / `registry_groups` 字段 | `BlockTypeHandler` `:31-36`；`ItemTypeHandler` `:30-35`；`RegistryGroupHandler` `:45-49` |
| 内嵌 `block_entity` | `BlockEntityTypeHandler` `:29-96`；`JsonTreeBuilder.java:544-569`；`DataDrivenBuildPipeline.java:870-916` |
| 内建 `proxy_model` / `proxy_model_be` | `ProxyModelBlockFactories.java:40-401`；`ProxyModelSelection.java`；`CompiledModelGroup.java`；`ProxyModelBlockFactoriesTest` |
| 严格属性校验 | `JsonPropertyParser.validateStrict` `:45-109`；`JsonItemParser.validateStrict` `:57-123` |
| last-wins / OVERLAY / claims | `EffectiveId.of` / `ofLegacy` `:39-67`；`DuplicateIdResolver` `:73-126`；`DataDrivenBuildPipeline.selectDefinitions` `:1149-1183`；`collectClaims` `:1304-1401` |
| reload 两个 wrapper 与废弃键 | `StateMachineDefinitionLoader` `:36-102`；`AnimationClipLoader` `:44-134` |
| 目录 glob vs index-only vs 受管白名单 | `ReloadOrchestrator.collectGlob` `:365-385`；`collectIndex` `:392-449`；`ManagedReloadAllowlist.java` |
| D9 双向提示 | `JsonTreeBuilder.java:580-591`；`ReloadOrchestrator.java:494-501` |
| 诊断桶与结构化诊断 | `JsonTreeBuilder.java:827-880`；`Diagnostics.java:40-182`；`BuildDiagnostic.java:9-75` |

## 7. 与其它文档的边界

- 本页（`schema.md`）：JSON 格式的权威参考，覆盖字段、类型、内建 `proxy_model` 参数、必填、路径规则、STRICT/LEGACY 差异与错误分类。
- **[intro.md](../../guides/data-driven/intro.md)**：入门讲解，回答「这是什么、为什么要用」。
- **[guide-content.md](../../guides/data-driven/guide-content.md)**：任务向教程，回答「怎么搭第一个内容文件」。
- **[guide-extension.md](../../guides/data-driven/guide-extension.md)**：Java 侧扩展（自定义 `TypeHandler` / 工厂 / 属性编译器 / `ReloadHandler`）。
- **[api-registration.md](api-registration.md) / [api-extension.md](api-extension.md) / [api-diagnostics.md](api-diagnostics.md) / [api-reload.md](api-reload.md)**：Java API 参考（含 v2 多来源管线与旧入口）。
- **[block-state-properties.md](block-state-properties.md)**：`state_properties` 完整约束与 Java `StateProperty` API。
- **[fsm.md](../fsm.md)**：状态机定义与动画片段**元素内部**的 codec 字段。
- **[数据驱动系统说明](../../explanation/data-driven/README.md)**：v2 固定快照、多来源 rank、宿主接线与迁移路径原理。

## 附录 A：本文未覆盖 / 待确认

- `state_machines` / `animation_clips` 元素的**完整字段表与默认值**：本页只规范 wrapper。元素 schema 请以对应 codec（`StateMachineDefinition.CODEC` / `AnimationClip.CODEC`）与 [fsm.md](../fsm.md) 为最终依据。
- `tab` 指向不存在的创造栏时的运行时行为：格式层只检验字符串能否解析为有效 `ResourceLocation`（`JsonItemParser.validateStrict` / `RegTypeHandler.resolveCreativeTab`），不检查目标创造栏是否已在运行期注册。
- v2 多来源构建中的**世界存档历史兼容性**：`DataDrivenBuildPipeline` 提供 `PreparedContent.claims()` 与 `BuildResult`，但是否拒绝包集合变化后的旧存档由各宿主（如包管理器）自行决定。
