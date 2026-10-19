# 数据驱动注册期 API（JsonTreeBuilder、索引与内容分派）

> 本篇是 reference（查阅用）。对象是 KasugaLibNext data-driven 的 Java API：类、方法、签名、调用时机与失败行为。
> 版本基准：Minecraft 1.21.1 / NeoForge 21.1.203 / Parchment 2024.11.17 / Java 21。文中行号对应当前工作树（未提交的 data-driven 重实现），基准 commit `ec864a1`；签名随代码演进，引用处都标了 `类:行`，可回代码核对。
> 边界：JSON 字段写哪个键、什么类型、是否必填，归 `schema.md`；上手教程归 `intro.md`，内容作者与扩展作者的步骤归 `guide-content.md` / `guide-extension.md`。本篇只在「哪个方法读它、读失败怎么办」的层面提字段名。
> 术语：**域（domain）** 指索引清单里的两个数组 `on_register`（注册域）与 `on_reload`（重载域）；**桶（bucket）** 指存放诊断的列表，分两维——**mod 维**按 mod id 分（注册期错误），**域/源键维**按 `(Domain, key)` 分（reload 期错误用 `RELOAD_DATA[<命名空间>]`）。

## 1. 模块与包结构

代码根：`modules/data-driven/src/main/java/lib/kasuga/registration/data_driven/`。

```text
data_driven/
├── TypeHandler.java                 # 处理器接口：顶层/内嵌类型、phase、identity
├── TypeHandlerRegistry.java         # 全局静态处理器表（LinkedHashMap 保序）
├── JsonTreeIntegration.java         # 与 RegisterContextRegistry 的接线点
├── builder/
│   └── JsonTreeBuilder.java         # 注册期入口：索引解析、内容分派、去重、apply、错误桶
├── context/
│   ├── BuildContext.java            # 构建上下文基类（modId / rootGroup / meta 存取）
│   ├── RegBuildContext.java         # 注册场景上下文（group / reg 存取）
│   └── JsonRegistryGroup.java       # JSON 创建的分组（RegistryGroup 子类）
├── handler/
│   ├── MetaTypeHandler.java         # 标记基类，无附加行为
│   ├── RegistryGroupHandler.java    # registry_groups，PHASE_GROUPS
│   ├── RegTypeHandler.java          # 注册类 handler 抽象基类（blocks/items 共用）
│   ├── BlockTypeHandler.java        # blocks，PHASE_CONTENT
│   ├── ItemTypeHandler.java         # items，PHASE_CONTENT
│   └── BlockEntityTypeHandler.java  # block_entities（内嵌于 blocks），PHASE_EMBEDDED
├── dedup/
│   ├── DuplicateIdResolver.java     # 纯函数 last-wins 去重
│   └── EffectiveId.java             # id 归一化
├── reload/                          # 重载域：扩展点 + 编排器
│   ├── ReloadHandler.java           # 扩展接口：一种 reload 类型的 decode/register
│   ├── ReloadHandlerRegistry.java   # 全局静态 handler 表（LinkedHashMap 保序）
│   ├── ReloadOrchestrator.java      # 重载周期编排器：清桶/发现/去重/注册/收尾
│   ├── Decoded.java                 # 一次 decode 的结果（entries + errors）
│   └── Reloaded.java                # 注册落地条目 + 来源命名空间
├── diagnostics/
│   ├── Diagnostics.java             # 诊断门面（mod 维 + 域/源键维）
│   └── DataDiagnosticsCommands.java # /kasuga_data errors [mod]
└── property/
    ├── JsonPropertyParser.java      # 方块属性解析
    ├── JsonItemParser.java          # 物品属性解析
    └── compiler/                    # 自定义属性匹配器
```

重载域（reload）的编排与解码分两处：

- `modules/data-driven/src/main/java/lib/kasuga/registration/data_driven/reload/`（编排器 `ReloadOrchestrator`、扩展点 `ReloadHandler` / `ReloadHandlerRegistry` / `Decoded` / `Reloaded`）
- `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/dynamic/fsm/`（消费方：两个 handler `FsmReloadHandler` / `FsmClipsReloadHandler`、注册 bean `FsmReloadHandlerRegistrar`，以及纯解码器 `StateMachineDefinitionLoader.java`、`AnimationClipLoader.java`）
- `modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/fsm/FsmAnimationClips.java`、`FsmDefinitions.java`（分桶存储）

依赖方向：`modelling → data-driven`。data-driven 不依赖 modelling。

## 2. 注册期入口：JsonTreeBuilder

`builder/JsonTreeBuilder.java` 是注册域的唯一入口。所有方法为 `static`，类不可实例化（`:798`）。

### 2.1 buildForMod

```java
public static JsonRegistryGroup buildForMod(String modId)   // JsonTreeBuilder.java:61
```

职责：加载一个 mod 的 `on_register` 内容，构建并返回它的根注册组 `<modId>:json_root`。

参数约束：`modId` 必须能在 FML 的 `ModList` 中找到对应 mod 文件（内部经 `findModFile`，`:723`）。找不到时返回 `null`，不报错。

执行顺序（一次调用）：

1. `ensureHandlersRegistered()`（`:52`），幂等，只注册四个内置 handler 一次。
2. `clearLoadingErrors(modId)`（`:63`），只清该 mod 的桶。
3. 定位索引目录 `data/<modId>/kasuga_lib/data_driven/`（`findIndexDir`，`:776`）。
4. 逐个读取索引清单（`listIndexFiles` 扫目录，`readIndexManifest` 解析），再聚合（`resolveIndexManifests`）。
5. 只解析 `on_register` 指向的内容文件；`on_reload` 这一段在这里被识别、校验，但不解析（`:105-110`）。
6. 在任何 apply 副作用之前解决重复 id（`:119`）。
7. 按 `getPhase()` 升序 apply 各 handler；`registry_groups` 在 apply 前额外拓扑排序（`:135-146`）。
8. 返回 `rootGroup`（`:148`）。

返回 `null` 的情形：

| 情形 | 位置 | 是否记错误 |
|------|------|-----------|
| 索引目录不存在，且旧布局目录 `data/<mod>/kasugalib/` 也不存在 | `:75-79` | 否，只记 DEBUG |
| 索引目录不存在，但旧布局目录存在 | `:66-74` | 是，WARN + 入桶，提示迁到 `on_register` |
| 索引目录存在，但没有任何清单声明任一域（含空目录） | `:95-99` | 是，聚合阶段记「declares neither ... in any index file」 |

注意：只声明 `on_reload` 的 mod 不算 `nothingDeclared()`，所以它返回一个非 null 的空根组（`:100-104` 只记 INFO），不是 `null`。

### 2.2 buildAll

```java
public static Map<String, JsonRegistryGroup> buildAll()   // JsonTreeBuilder.java:151
```

职责：遍历 `ModList` 的全部 mod 文件，对每个 mod 调 `buildForMod`，汇总非 null 结果，key 为 mod id，`LinkedHashMap` 保序。

参数：无。约束：同一 mod 文件路径只处理一次（`visitedRoots`，`:159`）。

失败行为：单个 mod 抛异常时，`catch` 记 ERROR 并把异常包成 `IllegalStateException` 入该 mod 桶（`:168-172`），继续处理下一个 mod。

注：全仓没有 `buildAll()` 的调用点（只有声明处），当前实际接线走 `buildForMod`。它是否为预留 API 待确认。

### 2.3 JsonTreeIntegration 的接线时机

`JsonTreeIntegration`（`JsonTreeIntegration.java:13`）是 Micronaut `@Context` bean。`@PostConstruct`（`:21`）向 `RegisterContextRegistry` 注册一个 **COMMON 侧回调**，回调体内调 `JsonTreeBuilder.buildForMod(modRegistry.getModId())`，非 null 时挂为 `modRegistry` 的子节点（`:24-33`）。

该回调惰性触发：首次该 mod 的注册事件分派时才执行。由此得到的保证是：所有 `@Context` 静态工厂注册先于 JSON 解析，不依赖显式排序。

### 2.4 阶段语义

| 常量 | 值 | 使用它的 handler | 原因 |
|------|-----|-----------------|------|
| `TypeHandler.PHASE_GROUPS` | 0 | `registry_groups` | 分组必须先于内容存在 |
| `TypeHandler.PHASE_CONTENT` | 1 | `blocks`、`items` | 常规内容 |
| `TypeHandler.PHASE_EMBEDDED` | 2 | `block_entities` | BE 要挂在已存在的方块 Reg 上 |

同一 phase 内的顺序为注册顺序（`TypeHandlerRegistry` 用 `LinkedHashMap`）。`registry_groups` 的应用顺序另经 `topoSortGroupDefs`（`:869`）按 `parent` 引用拓扑排序；有环时打 `Cycle detected among registry groups` WARN，环上节点按原序 apply，`RegistryGroupHandler.store` 对找不到的 parent 回退挂根组。因此引用环不会丢分组，只丢层级。

## 3. 索引 API

索引目录：`data/<modId>/kasuga_lib/data_driven/`。目录下每个 `.json` 都是一份清单，清单只声明内容文件路径，不声明类型。

### 3.1 IndexManifest

```java
public record IndexManifest(boolean declaredOnRegister, boolean declaredOnReload,
                            List<String> onRegisterPaths, List<String> onReloadPaths)
// JsonTreeBuilder.java:344
```

一份清单解析后的结果，未被消费。`empty()`（`:347`，包内可见）返回「什么都没声明」的实例，用于清单文件整体读不出时。

| 成员 | 含义 |
|------|------|
| `declaredOnRegister` | `on_register` 以数组形式出现（空数组也算，D2） |
| `declaredOnReload` | `on_reload` 以数组形式出现 |
| `onRegisterPaths` | `on_register` 的条目，保持文件内顺序 |
| `onReloadPaths` | `on_reload` 的条目，保持文件内顺序 |

### 3.2 ResolvedIndex

```java
public record ResolvedIndex(boolean declaredOnRegister, boolean declaredOnReload,
                            List<String> onRegisterPaths, List<String> onReloadPaths)
// JsonTreeBuilder.java:362
// public boolean nothingDeclared()   // :365
```

一个 mod 的全部清单聚合、并剔除跨域冲突后的结果。`nothingDeclared()` 是 D4 判据：为 true 时没有任何清单声明过任一域，调用方必须直接返回、不注册任何东西。

### 3.3 parseIndexManifest

```java
public static IndexManifest parseIndexManifest(String modId, String label, JsonObject root)
// JsonTreeBuilder.java:222
```

职责：解析单份内存中的清单，不消费任何文件。无 NeoForge 依赖，可在纯 JVM 测试中调用。

约束：`root` 为 `null` 视为空文件。只认 `on_register` 与 `on_reload`；其他顶层字段走未知字段通道报错（见 3.6）。返回永不 `null`。

失败行为：所有违规都记 ERROR 并入 `modId` 的桶，不抛异常；同一次调用里一个字段坏了不影响兄弟字段（D3）。

### 3.4 resolveIndexManifests

```java
public static ResolvedIndex resolveIndexManifests(String modId, List<IndexManifest> manifests)
// JsonTreeBuilder.java:294
```

职责：聚合一个 mod 的全部清单，做跨域 fail-closed 检查，返回两个域各自可消费的路径。纯函数，可测。

约束：`manifests` 按加载顺序传入，聚合保持该顺序。

失败行为：

- 同一路径同时出现在 `on_register` 与 `on_reload`（同一份或跨清单均算），该路径报 ERROR 入桶（D6），并从**两个域**都移除，谁都不消费。
- 没有任何清单声明任一域时，记一条聚合 WARN + 入桶（D4），返回的 `nothingDeclared()` 为 true。

### 3.5 listIndexFiles

```java
public static List<Path> listIndexFiles(Path indexDir, String modId)   // JsonTreeBuilder.java:762
```

职责：列出索引目录下以 `.json` 结尾的文件，按路径字符串排序。

失败行为：目录无法列出时，记 ERROR 并入桶，返回空列表。空结果本身是合法结果（表示没有清单），由 `buildForMod` 另行判断。

### 3.6 D1 到 D6 在 API 层的体现

规则编号沿用设计文档。每条对应一处可测行为：

| 规则 | 含义 | 落点 |
|------|------|------|
| D1 | 两字段各自可选；两者都不出现（含空文件、只含未知字段）报错且不消费 | `parseIndexManifest:240-244` |
| D2 | 空数组合法，且计为「已声明」 | `parseIndexManifest:237`、`resolveIndexManifests:323` 分支 |
| D3 | 未知顶层字段报错并继续；某字段非数组报错并继续处理兄弟字段 | `parseIndexManifest:226-230`、`readPathArray:256-277` |
| D4 | 聚合告警按「声明过哪个字段」分支，而非按解析出的条目 | `resolveIndexManifests:323-326` |
| D5 | `sources` 不是别名，按未知字段报错 | 走 `parseIndexManifest:226-230` |
| D6 | 同路径双列 fail-closed，两域都不消费 | `resolveIndexManifests:308-316` |

`readPathArray`（`:256`）为 private：字段存在但非数组、或数组元素非字符串，各自记一条 ERROR 入桶，且不连累兄弟字段。

### 3.7 废弃字段说明

旧索引字段名为 `sources`。硬迁移（D5）后它不再是合法字段：写 `sources` 会走未知字段通道，报 `contains unsupported field 'sources'` 并入桶，该清单不声明任何域。当前的合法域字段只有 `on_register` 与 `on_reload`。

### 3.8 路径契约

```java
public static String validateSourcePath(String path)              // JsonTreeBuilder.java:383
public static String[] indexDirectorySegments(String modId)       // JsonTreeBuilder.java:745
```

`validateSourcePath`：内容文件路径校验。返回 `null` 表示合法，否则返回人类可读原因。规则：非空、无前导 `/`、以 `.json` 结尾、无空段、无 `.`/`..` 段。路径相对 `data/<mod>/` 解析。纯函数，无 NeoForge 依赖。

`indexDirectorySegments`：返回索引目录相对 mod 文件根的段数组，固定为 `{"data", modId, "kasuga_lib", "data_driven"}`。每次调用返回**新数组**，调用方改动不会污染别的调用者。`ReloadOrchestrator.indexResourcePath` 会退掉前两段，得到包栈路径 `kasuga_lib/data_driven`。

## 4. 内容分派与 apply

### 4.1 parseSource（private）

`parseSource`（`:395`）负责按路径读取并解析一个内容文件：

1. `validateSourcePath` 校验；非法则 ERROR 入桶并返回。
2. 用 `parsedSources` 集合按路径字符串去重；同一内容文件在一次 `buildForMod` 内只解析一次，即使被多份清单引用。
3. 用 `findModFile` 定位 mod 文件，拼接 `data/<modId>/<sourcePath>` 读取。
4. 文件不存在记 `Source file not found` ERROR 入桶。
5. 读取/解析异常记 ERROR 入桶（`:431-434`）。
6. 交给 `dispatchContent`。

### 4.2 dispatchContent（private）

`dispatchContent`（`:461`）把内容文件按顶层字段分发给 handler：

- 先收集所有 `getParentTypeName() == null` 的 handler 名到 `knownFields`，所有内嵌 handler 按 typeName 放进 `embeddedByField`。
- 不在 `knownFields` 里的顶层字段一律报错入桶。若该字段名恰是某个内嵌类型，消息追加提示，指名应写进哪个父条目的哪个键（`:475-490`）。
- 每条未知字段消息还追加条件式提示（D9）：若这些字段属于 reload 域内容（例如写成 `state_machines` 数组），应把文件改列到 `on_reload`（`:483-490`）。
- 对每个已知顶层 handler：字段必须是数组，数组元素必须是对象，否则各自报错入桶并跳过。
- 内嵌类型只经 `extractEmbedded(parentJson)` 进入，不占顶层分发位（`:528-535`）。

### 4.3 parseContentBody

```java
public static Map<String, List<Object>> parseContentBody(String modId, String contentLabel, JsonObject root)
// JsonTreeBuilder.java:620
```

职责：内容管线的内存半段。`parseSource` 负责定位与读取，读完后把 body 交给它。返回按「handler typeName 到定义列表」分组的不可变 map，分派顺序保序。

约束与失败行为：只解析与收集，不注册、不 apply。所有畸形输入都经桶报告，不抛异常；调用方拿回仍可解析的部分（半应用语义）。无 NeoForge 依赖，可在纯 JVM 测试中直接调用。

### 4.4 applyUnchecked

```java
public static void applyUnchecked(TypeHandler handler, Object definition, BuildContext context, String modId)
// JsonTreeBuilder.java:712
```

职责：执行一次 handler.apply，把异常转成入桶的诊断。

约束：`buildForMod` 对每个存活定义调用它。异常时记 `Failed to apply '<type>' handler for mod '<mod>'` ERROR，并把包装后的 `IllegalStateException` 入桶（原异常保留为 cause）。公开它是因为「失败的 apply 必须留痕」这一契约否则只能靠完整 mod 加载触发。

## 5. 冲突解决

重复 id 在任何 apply 副作用之前解决，避免 `MappedRegistry` 出现重复键。

### 5.1 DuplicateIdResolver

`dedup/DuplicateIdResolver.java`，纯函数，无 Minecraft 类型。

```java
public record Candidate(String typeName, String identity, String sourcePath, Object payload)  // :39
public record Conflict(Candidate loser, Candidate winner)                                     // :47
public record Result(List<Candidate> winners, List<Conflict> conflicts)                       // :65
public static Result resolve(List<Candidate> candidates)                                      // :73
public static String describe(Conflict conflict)                                              // :109
public static Throwable toLoadingError(Conflict conflict)                                     // :124
```

- `Candidate.identity` 为 `null` 表示退出去重：永远是胜者，不与任何条目冲突。
- 冲突键是 `(typeName, identity)`。不同 `typeName` 永不冲突。
- `resolve` 为 last-wins：同一键取候选序列中最后出现的那个。序列顺序即加载顺序，方法内部不排序。
- `describe` / `toLoadingError` 产出人类可读消息，消息含类型字段、identity、败者与胜者各自的 source 文件。

### 5.2 EffectiveId

```java
public static String of(String modId, String rawId)   // EffectiveId.java:39
```

职责：把原始 id 归一到它最终注册的 `ResourceLocation` 字符串，用于去重比较。

| 原始 id | 归一结果 |
|---------|---------|
| `foo` | `<modId>:foo` |
| `minecraft:foo` | `<modId>:foo` |
| `othermod:foo` | `othermod:foo`（原样） |

`rawId` 为 `null` 时返回 `null`。只按第一个 `:` 切分命名空间与路径。

### 5.3 各类型的 identity

| 类型 | identity | 出处 |
|------|----------|------|
| `blocks` / `items` | 归一化后的 effective id | `RegTypeHandler.resolveIdentity`（`RegTypeHandler.java:58`） |
| `block_entities` | 宿主方块的 effective id | `BlockEntityTypeHandler.resolveIdentity`（`:66`） |
| `registry_groups` | `id` 原串，不归一化 | `RegistryGroupHandler.resolveIdentity`（`:50`） |
| 自定义 handler | `TypeHandler.resolveIdentity` 默认返回 `null`，即不参与去重 | `TypeHandler.java:64` |

`registry_groups` 不归一化的原因：分组不进 MC 注册表，且 loader 内处处按原串引用（方块的 `registry_group` 查表、`store` 的 `putRegistryGroup`），只归一化判定键会制造「判定重复、引用找不到」的错配。

### 5.4 last-wins 顺序

`resolveDuplicates`（`:652`）按 `parsed` 的加载顺序构造候选：

1. 索引文件按文件名升序；
2. 单份清单内按 `on_register` 数组书写顺序；
3. 单内容文件内按类型字段数组书写顺序；
4. 内嵌 `block_entities` 跟随宿主 `blocks` 条目的位置。

结果：败者整条不进入 apply，注册表只看到胜者一次；每条冲突记一条 WARN，并作为 `IllegalStateException` 入该 mod 桶；另有 `Resolved N duplicate id(s)` 汇总 WARN。identity 解析失败时降级为 `null`，不逃逸（`identityOf`，`:691`）。


> 数据驱动专题其它页：[注册期 API](api-registration.md) · [扩展 API](api-extension.md) · [诊断 API](api-diagnostics.md) · [重载 API](api-reload.md) · [专题索引](README.md)
