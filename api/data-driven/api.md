# 数据驱动 API 参考

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

## 6. 诊断门面 Diagnostics

`diagnostics/Diagnostics.java` 是统一诊断出口。错误按两个互不混合的维度寻址。

### 6.1 维度

mod 维：以 mod id 为键的桶，就是既有的 `JsonTreeBuilder` 桶。`report(String, Throwable)` / `errors(String)` 逐字委托给它，所以「断言 `JsonTreeBuilder.getLoadingErrors(modId)` 为空」这条契约继续成立。

域/源键维：以 `(Domain, key)` 为键的桶，给「属于某个源而非某个 mod」的失败使用。**已有生产者写入这一维**：reload 域用 `Domain.RELOAD_DATA`，键 = 命名空间（`ReloadOrchestrator` 与 `FsmReloadHandler` 都走它）。其余域（`RELOAD_ASSETS`、`CONFIG`）仍为预留。

### 6.2 方法表

mod 维：

| 签名 | 职责 | 失败行为 |
|------|------|---------|
| `report(String modId, Throwable error)`（`:64`） | 记一条失败到 mod 桶，等价 `JsonTreeBuilder.addLoadingError` | 无校验 |
| `errors(String modId)`（`:73`） | 该 mod 的不可变快照 | 无桶时返回 `List.of()` |
| `errorsByMod()`（`:78`） | 全部 mod，key 按 id 排序，不可变 | 无桶时返回空 map |
| `clear(String modId)`（`:83`） | 只清该 mod 桶 | 其余 mod 与全部源桶不动 |

域/源键维：

| 签名 | 职责 | 失败行为 |
|------|------|---------|
| `report(Domain domain, String key, Throwable error)`（`:96`） | 记一条失败到某个源的桶 | 三个参数任一为 `null` 抛 `NullPointerException`（前置校验，视为编程错误） |
| `errors(Domain domain, String key)`（`:107`） | 单个源桶的不可变快照 | 无桶时返回 `List.of()` |
| `errors(Domain domain)`（`:116`） | 某域全部源的聚合，源按 key 排序 | 无桶时返回空列表 |
| `clear(Domain domain)`（`:125`） | 清某域全部源桶 | 其他域与全部 mod 桶不动 |

两维共用：

| 签名 | 职责 |
|------|------|
| `clearAll()`（`:130`） | 清空两维 |
| `summarize()`（`:143`） | 每个非空桶一行，见 6.4 |

### 6.3 Domain

```java
public enum Domain { REGISTRATION, RELOAD_DATA, RELOAD_ASSETS, CONFIG }   // Diagnostics.java:40
```

| 值 | 含义 |
|----|------|
| `REGISTRATION` | mod jar 注册内容，mod 构造期读一次 |
| `RELOAD_DATA` | 数据包重载数据，`/reload` 时从包栈读 |
| `RELOAD_ASSETS` | 包栈资源，资源重载时读 |
| `CONFIG` | `config/` 下文件 |

### 6.4 summarize 输出格式

```java
public static List<String> summarize()   // Diagnostics.java:143
```

每行一个非空桶。mod 桶格式为 `<mod>: N error(s)`；源桶格式为 `<DOMAIN>[<key>]: N error(s)`。mod 在前按 id 排序，随后按 `Domain` 声明顺序、源按 key 排序。结果确定，命令与测试可断言。

### 6.5 生产者在用哪一维

reload 域的错误走**域/源键维**（`Domain.RELOAD_DATA`），键 = 出错文件所在的命名空间。`ReloadOrchestrator.reportError`（`:566-573`）与各 handler 的 `reportError` / `reportWarning`（如 `FsmReloadHandler.java:118-130`）调的是 `Diagnostics.report(Domain.RELOAD_DATA, namespace, ...)`。注册期错误仍走 mod 维。两维在输出里用不同 label 区分：mod 维 `[<mod>] <error>`，reload 维 `[RELOAD_DATA[<命名空间>]] <error>`（label 由 `Diagnostics.bucketLabel` 提供，与汇总行同源）。reload 维按轮清空（`runCycle` 开头 `Diagnostics.clear(Domain.RELOAD_DATA)`，`:208`），不累积。

## 7. /kasuga_data errors 命令

`DataDiagnosticsCommands.java` 是诊断门面的游戏内入口。

```java
public static LiteralArgumentBuilder<CommandSourceStack> kasugaDataCommand()   // :42
```

命令语法：

- `/kasuga_data errors`：打印 `Diagnostics.summarize()` 的每桶一行汇总，**两维都在内**——mod 维 `<mod>: N error(s)`、reload 维 `RELOAD_DATA[<命名空间>]: N error(s)`。无错误时打 `Kasuga data: no loading errors recorded`。
- `/kasuga_data errors <mod>`：**同时列出该 mod 的两维错误**——注册期（mod 维）逐条 `[<mod>] <throwable>`；reload 期（`Domain.RELOAD_DATA`，键 = 命名空间）逐条 `[RELOAD_DATA[<mod>]] <throwable>`（label 与汇总行同源，由 `Diagnostics.bucketLabel` 提供）。无错误时打 `Kasuga data: no loading errors for '<mod>'`。

约束与注册：

- 权限等级 2（`requires(source -> source.hasPermission(2))`，`:44`）。专用服务器上该命令输出内部加载诊断，属管理员信息；控制台等级 4 不受影响。
- `kasugaDataCommand()` 不依赖 dispatcher 或事件，纯测试可注册后断言命令树形状。
- 自动发现：类带 `@EventBusSubscriber`（`:28`），FML 扫描到后由 `onRegisterCommands`（`:54`）在 `RegisterCommandsEvent` 上注册命令树。注册在 COMMON 侧，因为门面本身是 common。
- 返回值为打印的行数，供命令系统与测试读取。

## 8. TypeHandler 体系

### 8.1 TypeHandler

```java
public interface TypeHandler<T> {                                  // TypeHandler.java:8
    int PHASE_GROUPS = 0;                                          // :11
    int PHASE_CONTENT = 1;                                         // :14
    int PHASE_EMBEDDED = 2;                                        // :17
    String getTypeName();                                          // :20
    int getPhase();                                                // :27
    T parse(JsonObject json);                                      // :29
    void apply(T definition, BuildContext context);                // :31
    default String getParentTypeName() { return null; }            // :38
    default String getEmbeddedKeyName() { return null; }           // :48
    default List<JsonObject> extractEmbedded(JsonObject parentJson) { return null; }  // :50
    default String resolveIdentity(String modId, T definition) { return null; }       // :64
}
```

| 方法 | 职责与约束 | 失败行为 |
|------|-----------|---------|
| `getTypeName()` | 声明的顶层 JSON 字段名，在 `TypeHandlerRegistry` 中唯一 | 同名后注册者覆盖先注册者，无告警 |
| `getPhase()` | 应用阶段，见 2.4 | 无 |
| `parse` | 把 JSON 对象转成定义对象 | 建议纯函数；抛异常只丢当前条目、兄弟照常解析，产一条 `entry <i> in '<key>' failed to parse: <msg>; entry skipped` |
| `apply` | 把定义注册进注册树 | 异常被 `applyUnchecked` 捕获并转入桶，不中断其他条目 |
| `getParentTypeName()` | 非 null 表示这是内嵌类型，值是其父类型字段名 | `null` 表示顶层类型 |
| `getEmbeddedKeyName()` | 内嵌类型在父条目里的键名，仅诊断用 | 内嵌类型未覆写时返回 `null`，诊断少一段提示 |
| `extractEmbedded(parentJson)` | 从父条目抽出内嵌对象列表 | 返回 `null` 表示没有内嵌；不参与顶层分发 |
| `resolveIdentity(modId, definition)` | 去重判定 identity | 默认 `null` 表示退出去重 |

### 8.2 TypeHandlerRegistry

```java
public static <T> void register(TypeHandler<T> handler);            // :11
public static TypeHandler<?> get(String typeName);                  // :15
public static Collection<TypeHandler<?>> all();                     // :19
public static TypeHandler<?> findByParent(String parentType);       // :23
```

全局静态表（`LinkedHashMap`），所有 mod 共享，无命名空间隔离。`all()` 返回不可变视图，保注册顺序。`findByParent` 线性查找，只返回第一个匹配，故一个父类型当前最多挂一个内嵌子类型。

### 8.3 顶层与内嵌

顶层类型占一个顶层 JSON 字段（如 `blocks`）。内嵌类型不占顶层字段，只能经 `extractEmbedded` 从父条目进入。`BlockEntityTypeHandler` 是参考实现（`getParentTypeName` 返回 `"blocks"`，`getEmbeddedKeyName` 返回 `"block_entity"`）：把父条目的 `id` 拷进内嵌对象的 `_parent_block`，apply 时据此用 `context.getBlockReg(...)` 找回父方块（`BlockEntityTypeHandler.java:33-44, 71-101`）。写错成顶层字段时，`dispatchContent` 按未知字段报错并给出正确键名，文件其余字段照常分发。

### 8.4 RegTypeHandler

`handler/RegTypeHandler.java` 是 `blocks` / `items` 共用的抽象基类，固化注册流程。

```java
protected abstract String resolveRawId(T definition);                       // :19
protected abstract Reg<?, ?> createRegistration(T definition, String path); // :21
protected String resolveType(T definition) { return getTypeName(); }        // :28
protected void configureTypeSpecific(T definition, Reg<?, ?> reg) {}        // :30
protected String resolveRegistryGroup(T definition) { return null; }        // :32
protected JsonObject resolveItemProperties(T definition) { return null; }   // :34
protected ResourceLocation resolveCreativeTab(T definition) { return null; }// :36
protected String resolveNamespace(String rawId)                              // :38
protected String resolvePath(String rawId)                                   // :43
public String resolveIdentity(String modId, T definition)                    // :58
public void apply(T definition, BuildContext baseContext)                    // :63
```

`apply` 的固定流程：拆 id 为 namespace 与 path（无 `:` 时 namespace 视为 `minecraft`）；`createRegistration` 返回 `null` 时打 `No factory for type '<type>' (id '<id>')` WARN 并跳过；namespace 非 `minecraft` 时给 Reg 挂 `ResourceLocation` 映射，这是命名空间重写的机制；挂分组（找不到则挂根组）；解析创造标签页（定义级优先，否则继承分组级）；执行类型特定配置；把 Reg 放进 `context.putReg(getTypeName(), ...)` 供跨 handler 查找。

`RegTypeHandler.apply` 内部捕获异常，打 WARN 后返回（`:107-111`）。`resolveItemProperties` 当前在实现里声明但**没有调用点**，实现类 `BlockTypeHandler` / `ItemTypeHandler` 分别在自己的 `configureTypeSpecific` 里解析属性（`BlockTypeHandler.java:68-88`、`ItemTypeHandler.java:64-72`）。这条 hook 是否会被接线待确认。

### 8.5 内置 handler

| Handler | getTypeName | getPhase | parse 产物 | 出处 |
|---------|-------------|----------|-----------|------|
| `RegistryGroupHandler` | `registry_groups` | `PHASE_GROUPS` | `RegistryGroupDef(id, parent, properties, item_properties)` | `RegistryGroupHandler.java:20-33` |
| `BlockTypeHandler` | `blocks` | `PHASE_CONTENT` | `BlockDef(id, type, registry_group, properties, item_properties, params)` | `BlockTypeHandler.java:19-34` |
| `ItemTypeHandler` | `items` | `PHASE_CONTENT` | `ItemDef(id, type, registry_group, properties, params)` | `ItemTypeHandler.java:16-30` |
| `BlockEntityTypeHandler` | `block_entities` | `PHASE_EMBEDDED` | `BlockEntityDef(type, parentBlockId, params)`，由 `extractEmbedded` 从 `block_entity` 键生成 | `BlockEntityTypeHandler.java:21-53` |

### 8.6 新增一种类型

1. 实现 `TypeHandler<T>`。若产物要进 MC 注册表，改继承 `RegTypeHandler<T>`，实现 `resolveRawId` 与 `createRegistration`。
2. 调 `TypeHandlerRegistry.register(handler)` 声明 typeName；顶层字段名即 `getTypeName()`。
3. 若走 `RegTypeHandler`，用 `FactoryRegistry` 注册对应工厂（见第 10 节），`handler` 按 JSON `type` 字段取值构造 Reg。
4. 需要去重时覆写 `resolveIdentity`；需要内嵌时覆写 `getParentTypeName` / `getEmbeddedKeyName` / `extractEmbedded`。

`TestGenericFactory.java` 是可在测试中照抄的最小例子：自定义 handler + `FactoryRegistry.registerGeneric`，`parse` 内从 `FactoryRegistry.getGeneric` 取工厂。真实链路的分派由 `JsonTreeBuilder` 完成，该例手工遍历 `TypeHandlerRegistry.all()` 只为断言解析结果。

### 8.7 ContentStructure（共享结构层）

`structure/ContentStructure.java:30`。注册域（`JsonTreeBuilder` 分派器）与 reload 域（`ReloadOrchestrator` 及两个 decoder）共用的**结构判定层**——纯函数，只依赖 Gson 与 `java.util`，无 Minecraft / 加载器类型，全部规则由纯 JVM 单测锁定。

```java
public final class ContentStructure {
    public enum Kind { BODY_NOT_OBJECT, UNKNOWN_KEY, MISSING_KEY, VALUE_NOT_ARRAY, ELEMENT_NOT_OBJECT }  // :33

    public record Issue(Kind kind, String key, int index, JsonElement actual) {}   // :55
    public record Field(List<Element> elements, List<Issue> issues) {}             // :65
    public record Element(int fileIndex, JsonObject body) {}                       // :76

    public static Issue body(JsonElement raw);                        // :84 非对象 body → 一个 issue，否则 null
    public static List<Issue> unknownKeys(JsonElement body, Set<String> knownKeys);  // :98 每个未认领键一个 issue
    public static Field field(JsonElement body, String typeKey);      // :123 单字段结构拆分：有效元素 + issues
    public static String joinWithOr(List<String> items);              // :162 用 " or " 连接，供两处提示复用
    public static String describe(JsonElement json);                  // :177 JSON 类型的人类可读描述
}
```

- `body`：文件 body 必须是 JSON 对象；`null` 视为非对象。返回 `null` 表示通过。
- `unknownKeys`：逐键检查，**不在** `knownKeys` 里的各产一个 `UNKNOWN_KEY`；非对象 body 返回空列表（该情形归 `body` 管）。
- `field`：拆一个顶层字段。缺键 / 值非数组各产**一个** issue 且无元素（调用方据此整字段拒绝）；非对象元素产 issue 而**兄弟保留**（元素级隔离）。
- `describe`：共享的 JSON 类型描述（如 `"an array"` / `"a primitive (5)"`），取代此前三处逐字节相同的私有副本。

**判定唯一、渲染分侧**：`ContentStructure` 只判定结构、**从不产出面向用户的文案**。`Issue` 携带判定结果与肇事值，各调用方用**自己的模板**渲染。这样两侧措辞才能各自保持——尤其 `ReloadHandler.describeFile()` 仍是 reload 域自己的文案契约，且两侧的跨域提示方向相反（D9：注册域提示「改列到 `on_reload`」，reload 域提示「改列到 `on_register`」），而形状规则本身只有一处实现。

**分层位置**：它停在「结构」层，不越过「元素 → T」——把元素映射到定义类型仍是各 handler 自己的 `parse`（条目级）/ `decode`（文件级）的职责。所以加入此层后，六个 handler 的代码一行未改。`TypeHandler.parse` / `ReloadHandler.decode` 以 `field(...)` 返回的 `Field.elements()`（`List<Element>`）为输入，各自再做自己的类型转换与校验；`Element.fileIndex()` 保留元素在顶层数组里的下标，供诊断引用原文位置。

## 9. BuildContext / RegBuildContext

```java
// BuildContext（基类）
String getModId();                                            // BuildContext.java:17
JsonRegistryGroup getRootGroup();                             // :19
<T> void putMeta(String typeName, String id, T value);        // :22
<T> T getMeta(String typeName, String id);                    // :27

// RegBuildContext（注册场景子类）
void putRegistryGroup(String id, JsonRegistryGroup group);    // RegBuildContext.java:22
JsonRegistryGroup getRegistryGroup(String id);                // :26
void setRegistryGroupCreativeTab(String groupId, ResourceLocation tab);  // :30
ResourceLocation getRegistryGroupCreativeTab(String groupId); // :34
void putReg(String typeName, String id, Reg<?, ?> reg);       // :40
Reg<?, ?> getReg(String typeName, String id);                 // :44
Reg<?, Block> getBlockReg(String id);                         // :50
```

- `getRegistryGroup` / `getReg` 找不到返回 `null`，调用方应回退根组或跳过。
- `putMeta` / `getMeta` 是自定义类型间建立引用关系的通用通道，不要求值是 `Reg`。
- `putReg` / `getReg` 的 `typeName` 维度即 `getTypeName()`；`getBlockReg(id)` 等价 `getReg("blocks", id)`。
- 生命周期为一次 `buildForMod`，跨 mod 不共享。

## 10. FactoryRegistry

`modules/core/src/main/java/lib/kasuga/registration/factory/FactoryRegistry.java`。data-driven 的 handler 从这里按 JSON `type` 字段取工厂。

```java
public interface BlockFactory        { Reg<?, Block> create(String id, @Nullable JsonObject params); }        // :18
public interface ItemFactory         { Reg<?, Item> create(String id, @Nullable JsonObject params); }         // :23
public interface BlockEntityFactory  { Reg<?, ?> create(String id, Supplier<Block[]> validBlocks, @Nullable JsonObject params); }  // :28
public interface GenericFactory      { Reg<?, ?> create(String id, JsonObject params); }                       // :33

public static void register(String type, BlockFactory factory);            // :44
public static BlockFactory get(String type);                              // :48
public static boolean contains(String type);                              // :52
public static void registerItem(String type, ItemFactory factory);         // :58
public static ItemFactory getItemFactory(String type);                    // :62
public static boolean containsItem(String type);                          // :66
public static void registerBlockEntity(String type, BlockEntityFactory factory);  // :74
public static BlockEntityFactory getBlockEntityFactory(String type);      // :79
public static boolean containsBlockEntity(String type);                   // :83
public static Set<String> getBlockEntityTypes();                          // :87
public static void registerGeneric(String type, GenericFactory factory);  // :93
public static GenericFactory getGeneric(String type);                     // :95
public static boolean containsGeneric(String type);                       // :97
```

- 内部为 `ConcurrentHashMap`，重复注册同一 type 静默覆盖。
- `id` 参数只含 path，namespace 已由 handler 剥离并交给 Reg 的命名空间重写；工厂内不要再拼 namespace。
- `params` 是定义里 `params` 字段的原始对象，可为 `null`，语义由工厂定义。
- `BlockEntityFactory.validBlocks` 是 `Supplier<Block[]>`，惰性求值；工厂应存 Supplier，不要在构造时立即 `get()`。
- `get*` 未注册时返回 `null`，由调用方转成诊断。

## 11. 属性解析器

### 11.1 JsonPropertyParser

```java
public static final JsonPropertyParser INSTANCE;    // :23
public static JsonPropertyParser getInstance();     // :217
public List<Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> parseBlockProperties(JsonObject json);  // :28
public void registerCompiler(PropertyCompiler compiler);   // :197
public void registerCompiler(String key, BiFunction<String, JsonElement, Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> supplier);  // :201
public void removeCompiler(PropertyCompiler compiler);     // :209
public int compilerSize();                                 // :213
```

- `parseBlockProperties(null)` 返回空列表。
- compiler 按列表顺序尝试，首个 `valid(key, value)` 命中即停。内置键用精确 `ResourceLocation` 匹配（`RLCompiler`，`:189`）。
- 未匹配键打 `Unknown block property: <key>` WARN 并跳过，不入桶。
- 值不合法时打 `Failed to parse block property '<key>'` WARN 并跳过。
- `buildCompilers`（`:148`）注册内置键，含布尔开关、`friction`、`strength`、`map_color`、`sound_type`、`light_emission` 等。

### 11.2 JsonItemParser

```java
public static final JsonItemParser INSTANCE;        // :15
public List<Function<Item.Properties, Item.Properties>> parseItemProperties(JsonObject json);  // :38
public void registerParser(String key, ItemPropertyParser parser);  // :67
public interface ItemPropertyParser { Function<Item.Properties, Item.Properties> parse(String key, JsonElement value); }  // :72
```

- `parseItemProperties(null)` 返回空列表。
- `tab` 是保留键：parser 阶段直接跳过（`:52`），由 handler 层翻译成创造标签页绑定。
- 未知键打 `Unknown item property: <key>` WARN 并跳过。
- 内置键：`stacks_to`、`rarity`、`fire_resistant`、`durability`、`no_repair`（`:23-35`）。

### 11.3 属性继承与覆盖

属性生效顺序由 Reg 树的 property 链决定，父先子后，后应用者覆盖先应用者：根组属性、父分组、子分组、方块级，方块级最后应用因而优先级最高。物品属性同理，分组 `item_properties` 先于方块级 `item_properties`。这是靠应用顺序实现的覆盖，不做字段合并；同名键整键覆盖。

## 12. 错误分类

进 error 桶（记 ERROR 日志并以异常入桶——注册期入 mod 维，经 `JsonTreeBuilder.addLoadingError` 或 `Diagnostics.report(String, …)`；reload 期入 `Domain.RELOAD_DATA` 源维，经 `Diagnostics.report(Domain, namespace, …)`）：

| 情形 | 出处 |
|------|------|
| 索引文件读不出或非 JSON | `readIndexManifest:188` |
| 索引未知顶层字段（含旧字段名 `sources`，见 3.7） | `parseIndexManifest:226-230` |
| 索引两字段都未声明 | `parseIndexManifest:240-244` |
| 字段非数组 | `readPathArray:256-263` |
| 数组元素非字符串 | `readPathArray:268-274` |
| 同路径双列两域 | `resolveIndexManifests:308-316` |
| 所有索引都未声明任一域 | `resolveIndexManifests:323-326` |
| 索引目录无法列出 | `listIndexFiles:767-771` |
| 内容路径非法 | `parseSource:396-402` |
| 找不到 mod 文件 | `parseSource:408-412` |
| 内容文件缺失 | `parseSource:423-427` |
| 内容文件读取/JSON 异常 | `parseSource:434-436` |
| 内容未知顶层字段（含顶层 `block_entities`） | `dispatchContent:475-490` |
| 类型字段非数组 | `dispatchContent:504-510` |
| 数组元素非对象 | `dispatchContent:504-516` |
| handler.apply 抛异常 | `applyUnchecked:712` |
| 重复 id 冲突 | `resolveDuplicates:667-670` |
| 旧布局目录存在 | `buildForMod:66-74` |
| buildAll 单 mod 异常 | `buildAll:171` |
| reload 解码/注册/校验失败 | `ReloadOrchestrator.reportError:566-573`、`FsmReloadHandler.reportError:118-125` |
| reload 收尾发现有悬空 clip 引用 | `FsmReloadHandler.reportWarning:127-130` |

只进日志、不进桶（WARN 级降级）：

| 情形 | 出处 |
|------|------|
| 找不到工厂 | `RegTypeHandler:71-73` |
| 分组引用环 | `JsonTreeBuilder:823-825` |
| 未知方块属性 / 值不合法 | `JsonPropertyParser:50-57` |
| 未知物品属性 | `JsonItemParser:63` |
| BE 父方块找不到 / BE 类型未知 | `BlockEntityTypeHandler:76-86` |
| 重复 id 意外到达 apply 的兜底告警 | `RegTypeHandler:101-105` |
| 某 mod 不声明 `on_register`（纯 reload mod） | `JsonTreeBuilder:100-104`（INFO） |
| 索引目录不存在且无旧布局 | `JsonTreeBuilder:74`（DEBUG） |

说明：`Diagnostics.report` 不重抛、前置拒绝 `null` 参数，桶在加载进行中保持可读（每个 key 单独同步）。所有错误都被吞进桶，mod 继续启动。D9 的条件式提示出现在两类消息里：注册侧未知字段消息指向 `on_reload`（`JsonTreeBuilder:559-570`，reload 键名运行时从 `ReloadHandlerRegistry` 枚举）；重载侧未知字段消息指向 `on_register`（`ReloadOrchestrator:439-446`）。reload 域的桶**按轮清空**（`runCycle` 开头 `Diagnostics.clear(Domain.RELOAD_DATA)`，`:208`），连续 `/reload` 同一坏文件不会累积。

## 13. 重载 API

重载域由 data-driven 的 `ReloadOrchestrator` 编排，扩展点是 `ReloadHandler`（对称于注册期的 `TypeHandler`）。编排器不认识任何类型名——它遍历 `ReloadHandlerRegistry`，把每个 handler 的 `typeName()` 当作合法顶层键。

### 13.1 ReloadHandler（扩展点）

`reload/ReloadHandler.java:41`。一个 handler 管一种 reload 类型的 decode/register，功能上对应该类型「原来的 decoder + 注册步骤 + 收尾校验」。

```java
public interface ReloadHandler<T> {
    String typeName();                                    // :50 顶层键，兼作去重碰撞空间
    default String describeFile() { return "reload-domain file"; }  // :60 decode 诊断里的名词
    default Set<String> globDirectories() { return Set.of(); }      // :72 默认空 = 仅索引
    void clearBucket();                                   // :85 每周期一次，register 前
    Decoded<T> decode(JsonObject root);                   // :97 纯解码，转调现有 decodeFile
    void register(T payload);                             // :107 last-wins 胜者注册
    default void afterReload(List<Reloaded<T>> registered) {}  // :117 跨类型校验
}
```

| 方法 | 职责与约束 | 失败行为 |
|------|-----------|---------|
| `typeName()` | 认领的顶层 JSON 键，e.g. `"state_machines"`；在注册表中唯一，也是去重的碰撞空间 | 同名后注册者原地替换先注册者 |
| `describeFile()` | 用于 decode 诊断的名词，e.g. `"state machine file"` → `"Failed to decode state machine file '<path>'"` | 默认 `"reload-domain file"`；诊断文案被测试锁死的 handler 应覆写 |
| `globDirectories()` | `data/<ns>/` 下额外按目录 glob 发现的目录；默认空 = index-only | 返回有序集合以保发现顺序 |
| `clearBucket()` | 每周期一次，发现之前。**只丢 reload 来源**（"script wins"），故委托桶的 `clearResource()` | 抛异常被编排器就地隔离（`runClear`，`:250-261`）并记入 `Domain.RELOAD_DATA`，其余 handler 照常 |
| `decode(root)` | `root` 已保证是 JSON 对象。**不得读文件**（编排器管 I/O），**不得为畸形内容抛异常**（作为 `Decoded.errors()` 返回） | 抛异常被编排器按文件隔离并记账 |
| `register(payload)` | 只对 last-wins 胜者调用，且在整轮发现完成之后 | 应抛异常（编排器隔离并按命名空间归因），不要吞 |
| `afterReload(registered)` | 在所有 handler 的 register 阶段都完成后，只对本轮注册的条目调用；跨类型校验放这里 | 抛异常被归到库 mod（`KasugaLib.MODID`），不阻断后续 handler |

### 13.2 ReloadHandlerRegistry

`reload/ReloadHandlerRegistry.java:27`。`ReloadHandlerRegistry` 是 `TypeHandlerRegistry` 的 reload 侧对偶：静态、按 `typeName()` 索引、`LinkedHashMap` 保序。

```java
public static void register(ReloadHandler<?> handler);       // :37
public static ReloadHandler<?> get(String typeName);         // :45
public static Collection<ReloadHandler<?>> all();            // :50 不可变视图，保注册顺序
public static void clearForTest();                           // :58 仅测试，生产不调
```

- **顺序有承载力**：编排器遍历 `all()` 决定 dispatch 顺序与 register 顺序，二者共同决定跨类型 last-wins。需要特定顺序（如 state machines 先于 clips）就按该顺序注册。
- **注册按 type name 幂等**：重复注册同一 `typeName` 原地替换，保留原位置；故 `@Context` bean 在测试里重跑不会重复键。
- `clearForTest()` 是给测试的钩子（`TypeHandlerRegistry` 没有对应物，测试注册的 handler 会泄漏到后续测试，是已记录的设计坑）；生产代码不调。

### 13.3 Decoded / Entry

`reload/Decoded.java:19`。一次 `decode` 的结果：解出的条目 + 人类可读诊断。

```java
public record Decoded<T>(List<Entry<T>> entries, List<String> errors) {   // :19
    public record Entry<T>(String id, T payload) {}                        // :28
    public static <T> Decoded<T> empty();                                  // :36
}
```

- `Entry.id` 是该条目在 handler 类型内的有效 id（去重判定键）；payload 是回传给 `register` 的值。身份由 handler 自己选，编排器从不知道 handler 怎么拼 id。
- 整个文件被拒 → 只有 `errors`、`entries` 为空。

### 13.4 Reloaded

`reload/Reloaded.java:14`。一个通过去重、已交给 `register` 的 payload，加上它的来源命名空间。编排器带它走完收尾阶段，让 handler 的 `afterReload` 能把诊断归到产生该条目的 mod——泛型 handler 从 payload 本身拿不到该命名空间。

```java
public record Reloaded<T>(String modId, T payload) {}
```

### 13.5 ReloadOrchestrator

`reload/ReloadOrchestrator.java:121`。实现 `ScopedResourceManagerConsumer` 与 `ScopedResourcePackListener`，是重载域唯一的编排器。DI 入口 `ReloadOrchestrator()`（`:133`）经 `init()`（`:155`）注册进 `ResourceSystem`；host 入口 `ReloadOrchestrator(ReloadHandler<?>...)`（`:145`）先把传入的 handler 登记进注册表，再跑周期——测试或自带桶的宿主用它绕开建模侧注册 bean。

```java
public ReloadOrchestrator()                                      // :133 DI 入口
public ReloadOrchestrator(ReloadHandler<?>... handlers)          // :145 登记后运行
public void reload(ResourceManager resourceManager)              // :186
public static String indexResourcePath(String modId)             // :290
```

- 顶层键集合**不再是写死的常量**：由 `knownTypeNames()`（`:473`）从 `ReloadHandlerRegistry.all()` 派生。加类型 = 注册 handler，不改编排器。
- D9 反向提示里的注册类型示例由 `registrationFieldsExample()`（`:488`）从 `TypeHandlerRegistry.all()` 派生（顶层注册类型 = 无 parent 的 handler）；注册表为空时不编造。
- `reload(ResourceManager)` 是每周期入口，外覆一层最后兜底 catch（`:188-190`）：任何逃过各 stage 自身守卫的 `RuntimeException` 都归到库自身 mod id（`KasugaLib.MODID`）并记入桶。因为方法在重载监听器路径上且桶在该 stage 之前已清，异常不能外逃。
- `indexResourcePath(modId)` 从 `JsonTreeBuilder.indexDirectorySegments(modId)` 退掉前两段，返回包栈路径 `kasuga_lib/data_driven`。它与注册侧的目录拼写共用同一来源。

一个周期（`runCycle`，`:194`）的 stage 顺序：

1. **构周期**：对注册表里每个 handler 各建一个 `HandlerCycle`（`:199-201`）。
2. **清桶**：逐 handler 调 `runClear(entry)` → `clearBucket()`，一轮恰好一次（`:213-215`；`runClear`，`:250-261`）。只清 reload 来源，脚本/代码注册的条目不受影响；某个 `clearBucket()` 抛异常被就地隔离并记账，其余 handler 照常。
3. **聚合 glob 目录**：把每个 handler 的 `globDirectories()` 收进一个去重的有序集合（`:222-225`）；两个 handler 共享的目录只列一次，文件按顶层键路由。
4. **逐命名空间发现**（`collectNamespace`，`:266`）：先 `readReloadPaths` 读该命名空间索引清单的 `on_reload`，再 `collectGlob`（`:348-367`）扫描聚合目录（跳过已被索引列出的路径），最后 `collectIndex`（`:369`）读取 `on_reload` 指向的文件。
5. **注册阶段**：逐 handler 调 `register(entry)`（`:507-526`），按注册表顺序；glob 候选在前、索引候选在后（`HandlerCycle.candidates()`，`:595`）。同类里后条目 last-wins。
6. **收尾阶段**：逐 handler 调 `runAfterReload(entry)`（`:530-538`），只对本轮实际注册的条目，且在所有 register 都完成之后——跨类型校验才能看到别的 handler 的桶。

**分派**（`dispatch`，`:429`）：按顶层键路由。未知顶层键先报错并附 D9 反向提示（`:439-446`）；对每个非未知键，逐个 handler 检查 `root.has(typeName())`，命中则 `decode`，把 `Decoded.Entry` 包成 `DuplicateIdResolver.Candidate(typeName, id, label, new Reloaded<>(namespace, payload))`。

**去重**（`resolveAndReport`，`:545`）：用共享的 `DuplicateIdResolver`。败者**根本不进 handler 的 `register`**（"loser never has a side effect"），每个冲突记 WARN + 一条桶条目，归到败者所在 mod。

`indexResourcePath` 与 `runCycle` 都无 Minecraft 之外的特殊依赖；`reload` 需要一个 `ResourceManager`。

### 13.6 异常守卫

四层参与者级守卫加一层兜底：

| 层 | 范围 | 出处 |
|----|------|------|
| 每 handler 清桶 | `clearBucket()` 抛异常 | `runClear:250-261` |
| 每文件 | 读取与解码异常，含解码器自身抛异常 | `readAndDispatch:405-415` |
| 每命名空间 | 发现机制本身（目录 glob、清单读取）抛异常 | `collectNamespace:266-288` |
| 每条目 | 注册单个条目抛异常 | `register:507-526` |
| 每 handler 收尾 | `afterReload` 抛异常 | `runAfterReload:530-538` |
| 兜底 | 以上都没能归因的异常，归到库 mod id | `reload:186-193` |

一个周期内全部条目失败时，桶保持清空状态，这是有意为之：空是真实结果，错误在诊断桶里。

### 13.7 StateMachineDefinitionLoader

仍留在 modelling，已降级为纯文件层解码器，不再持监听器、不清桶、也不列举文件（`StateMachineDefinitionLoader.java:14-33`）。

```java
public static final String PATH = "state_machines";                     // :39
public static final String FIELD_STATE_MACHINES = "state_machines";     // :42
public record DecodedFile(List<StateMachineDefinition> definitions, List<String> errors) {}  // :55
public static DecodedFile decodeFile(JsonElement json);                  // :67
```

`StateMachineDefinitionLoader` 的目录列举方法**已删除**（0 调用方）。目录 glob 现由 `ReloadOrchestrator` 自己的 `collectGlob`（`:348-367`）用 `ResourceManager.listResources` 完成，`FsmReloadHandler.globDirectories()`（`:69-71`）声明要扫的目录；`StateMachineDefinitionLoader.PATH`（`:39`）保留，供 `globDirectories()` 引用。

`decodeFile`：文件形状为 `{"state_machines": [ <definition>, ... ]}`。整体非对象、缺顶层键、值非数组都拒绝整个文件并返回一条 error；**额外顶层键不再拒绝文件**（真未知键由编排器报错、本 decoder 只忽略），数组元素非对象或 decode 失败只跳过该元素，兄弟元素照常。返回的 `errors` 为空表示文件干净。它现在被 `FsmReloadHandler.decode` 转调，编排器不再直接认识它。

### 13.8 AnimationClipLoader

同样为纯文件层组件（`AnimationClipLoader.java:14-39`）。

```java
public static final String FIELD_ANIMATION_CLIPS = "animation_clips";   // :45
public record DecodedFile(List<AnimationClip> clips, List<String> errors) {}  // :57
public static DecodedFile decodeFile(JsonElement json);                  // :69
public static void register(FsmAnimationClips clips, AnimationClip clip);// :113
```

`decodeFile`：形状为 `{"animation_clips": [ <clip>, ... ]}`，失败隔离规则与 `StateMachineDefinitionLoader.decodeFile` 相同。clip 的 id 由元素自身的 `id` 字段承载（`AnimationClip.CODEC` 读它），没有单独的 id 键；记录未命名的键被 codec 忽略，因此可写注释类字段。被 `FsmClipsReloadHandler.decode` 转调。

`register`：把解码出的 clip 写入桶的 RESOURCE 半区，调 `clips.registerResource`。`clips` 或 `clip` 为 `null` 时是 no-op。它现在被 `FsmClipsReloadHandler.register` 转调。动画片段文件只能经 `on_reload` 到达，没有 `animation_clips/` 目录 glob。

### 13.9 建模侧两个 handler 与注册 bean

这三个类在 modelling 的 `lib.kasuga.rendering.models.mc.dynamic.fsm` 包，是消费方，不随编排器搬进 data-driven：

| 类 | typeName | 发现 | 角色 |
|----|----------|------|------|
| `FsmReloadHandler`（`:42`） | `state_machines` | glob `state_machines/` | `decode` → `StateMachineDefinitionLoader.decodeFile`；`register` 写 `FsmDefinitions` 的 RESOURCE 半区；`afterReload` 跑悬空 clip 检查 |
| `FsmClipsReloadHandler`（`:24`） | `animation_clips` | index-only（保持默认空集） | `decode` → `AnimationClipLoader.decodeFile`；`register` → `AnimationClipLoader.register` |
| `FsmReloadHandlerRegistrar`（`:23`） | — | — | `@Context` + `@PostConstruct`（`:25-31`），按 **fsm → clips** 顺序把两个 handler 登记进 `ReloadHandlerRegistry`，绑定进程级 `FsmRegistries.GLOBAL` 桶 |

### 13.10 FsmDefinitions 与 FsmAnimationClips

两个分桶存储都在 `render-core`。每个桶按来源分 RESOURCE（重载路径，可被 `clearResource` 清掉）与 SCRIPT（运行时/脚本路径，重载时存活）。

`FsmAnimationClips`（`FsmAnimationClips.java:30`）：

```java
public enum ClipSource { RESOURCE, SCRIPT }                              // :35
public record Entry(AnimationSampler<?> sampler, Object data, ClipSource source) {}  // :43
public void register(Id id, AnimationSampler<?> sampler, Object data)    // :53
public void registerResource(Id id, AnimationSampler<?> sampler, Object data)  // :66
public Entry get(Id id)                                                  // :87
public boolean remove(Id id)                                             // :92
public void clearResource()                                              // :100
public void clearAll()                                                   // :108
public void clear()                                                      // :117
```

- `register` 写 SCRIPT 条目，会替换同 id 的 RESOURCE 条目并打 WARN（script wins）。
- `registerResource` 写 RESOURCE 条目，不覆盖同 id 的 SCRIPT 条目；后写的 RESOURCE 覆盖先写的。
- `get` 找不到返回 `null`；id 为 `null` 也返回 `null`。
- `register` / `registerResource` 对 `null` id、sampler、data 抛 `IllegalArgumentException`（`validate:76`）。
- `clearResource` 只清 RESOURCE；`clearAll` 清全部；`clear()` 是 `clearResource()` 的别名，不再清代码注册条目。

`FsmDefinitions`（`FsmDefinitions.java:27`）同构：`register`（SCRIPT，`:59`）、`registerResource`（RESOURCE，`:71`）、`clearResource`（`:84`）、`clearAll`（`:95`）、`remove`（`:107`）、`get`（`:116`）、`hash`（`:126`）、`addListener`（`:142`）。身份变化（覆盖、移除、清空）会通知 `InvalidationListener`。`hash(id)` 返回内容哈希，缺席时为 0，供同步层做 identity 检查。

script-wins 是写侧不变量：单一 map 加 `registerResource` 的守卫，`get` 是普通查询，结果与注册顺序无关。

## 14. 测试可断言的纯函数面

以下 API 无 NeoForge 依赖或可在纯 JVM 调用，专门暴露给测试断言契约：

| API | 断言对象 |
|-----|---------|
| `JsonTreeBuilder.indexDirectorySegments` | 索引目录段数组，锁死拼写与层级 |
| `JsonTreeBuilder.validateSourcePath` | 内容路径规则 |
| `JsonTreeBuilder.parseIndexManifest` | 索引 schema D1 到 D5 |
| `JsonTreeBuilder.resolveIndexManifests` | 聚合 D4 与双列 fail-closed D6 |
| `JsonTreeBuilder.parseContentBody` | 内容分派、内嵌类型、半应用、D9 提示 |
| `JsonTreeBuilder.listIndexFiles` | 目录扫描的失败分支与排序 |
| `JsonTreeBuilder.applyUnchecked` | apply 失败入桶 |
| `DuplicateIdResolver.resolve` / `describe` / `toLoadingError` | last-wins 与冲突消息 |
| `EffectiveId.of` | id 归一化表 |
| `Diagnostics` 全维方法 | 两维隔离、清空、summarize 格式 |
| `DataDiagnosticsCommands.kasugaDataCommand` | 命令树形状 |
| `ReloadOrchestrator.reload` / `indexResourcePath` | 重载周期、双入口、跨入口去重、诊断 |
| `ReloadHandler` 实现（fixture） | 新 reload 类型的接入：`ReloadHandlerExtensionPointTest` 用一个 fake handler 断言 dispatch/去重/注册与 after 阶段顺序 |
| `ReloadHandlerRegistry.register` / `all` / `clearForTest` | 注册表保序、同名替换、测试隔离 |
| `StateMachineDefinitionLoader.decodeFile` | 包装层解码契约（目录 glob 由 `ReloadOrchestrator.collectGlob` 负责，decoder 不再列举文件） |
| `AnimationClipLoader.decodeFile` / `register` | 动画片段文件解码与 script-wins 注册 |
| `ContentStructure.body` / `unknownKeys` / `field` / `joinWithOr` / `describe` | 共享结构判定：body 检查、未知键、字段拆分（`Field`/`Element`）、提示拼接、类型描述 |

## 15. 调用示例

以下片段都能对着当前签名编译。每个标注它模仿的真实测试用例，括号内为测试类。

注册一个 mod 并在之后检查错误桶（模仿 `JsonTreeBuilderIndexPathTest.contentTestingIndexLoadsWithoutErrors`）：

```java
import java.util.List;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;

JsonTreeBuilder.clearLoadingErrors("kasuga_lib");
JsonRegistryGroup root = JsonTreeBuilder.buildForMod("kasuga_lib");
// root 为 null 表示没有声明任何 on_register 内容；非 null 时是挂在 mod 注册树上的根组
List<Throwable> errors = JsonTreeBuilder.getLoadingErrors("kasuga_lib");
```

解析一份索引清单并聚合（模仿 `JsonTreeBuilderIndexSchemaTest.declaringNeitherDomainIsReportedAndConsumesNothing` 与 `crossListedPathIsConsumedByNeitherDomain`）：

```java
import java.util.List;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder.IndexManifest;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder.ResolvedIndex;

JsonObject doc = JsonParser.parseString(
        "{\"on_register\": [\"carriages/m1/blocks.json\"]}").getAsJsonObject();
IndexManifest manifest = JsonTreeBuilder.parseIndexManifest("kuayue", "index.json", doc);
ResolvedIndex index = JsonTreeBuilder.resolveIndexManifests("kuayue", List.of(manifest));
if (!index.nothingDeclared()) {
    for (String path : index.onRegisterPaths()) {
        // path 相对 data/kuayue/，已通过双列 fail-closed 检查
    }
}
```

只解析内容文件、不注册（模仿 `JsonTreeBuilderEmbeddedTypeDispatchTest.topLevelEmbeddedTypeIsReportedAndTheRestOfTheFileStillDispatches`）：

```java
import java.util.List;
import java.util.Map;
import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;

JsonObject body = /* 已解析的内容文件 */ new JsonObject();
Map<String, List<Object>> collected =
        JsonTreeBuilder.parseContentBody("kuayue", "carriages/m1/blocks.json", body);
// collected 的 key 是 handler 的 typeName；解析错误进 JsonTreeBuilder.getLoadingErrors("kuayue")
```

写读诊断桶（模仿 `DiagnosticsTest.modDimensionIsTheExistingBucket` 与 `summarizePrintsOneLinePerNonEmptyBucketDeterministically`）：

```java
import java.util.List;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;

Diagnostics.report("kuayue", new IllegalStateException("boom"));
List<Throwable> perMod = Diagnostics.errors("kuayue");
// perMod 与 JsonTreeBuilder.getLoadingErrors("kuayue") 是同一个桶
List<String> lines = Diagnostics.summarize();   // 每行 "<mod>: N error(s)"（mod 维）或 "<DOMAIN>[<key>]: N error(s)"（源维）
Diagnostics.clear("kuayue");
```

注册并执行诊断命令（模仿 `DataDiagnosticsCommandsTest.onRegisterCommandsRegistersKasugaDataErrorsIntoTheDispatcher`）：

```java
import com.mojang.brigadier.CommandDispatcher;
import lib.kasuga.registration.data_driven.diagnostics.DataDiagnosticsCommands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
DataDiagnosticsCommands.onRegisterCommands(
        new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null));
dispatcher.execute("kasuga_data errors kuayue", source);   // source 权限需 >= 2
```

跑一个重载周期（模仿 `ReloadOrchestratorTest.loadsValidDefinitionsIntoInjectedBucket`）：

```java
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmClipsReloadHandler;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmReloadHandler;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import net.minecraft.server.packs.resources.ResourceManager;

FsmDefinitions definitions = new FsmDefinitions();
FsmAnimationClips clips = new FsmAnimationClips();
// varargs 构造器把这两个 handler 登记进 ReloadHandlerRegistry，顺序即 dispatch/注册顺序
ReloadOrchestrator orchestrator = new ReloadOrchestrator(
        new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips));
orchestrator.reload(resourceManager);   // 一个周期：清桶、双入口发现、去重注册、收尾校验
```

> 编排器已搬到 `lib.kasuga.registration.data_driven.reload`（data-driven）；两个 handler 仍留在 modelling 的 `lib.kasuga.rendering.models.mc.dynamic.fsm`，所以这个例子同时 import 两个包。测试里用 varargs 构造器自带桶，生产里 handler 由 `FsmReloadHandlerRegistrar` 登记、DI 构造器无参运行。

解码并注册动画片段（模仿 `AnimationClipLoaderTest.decodesTheWrappedClipArray`，注册由编排器调用）：

```java
import com.google.gson.JsonParser;
import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationClipLoader;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;

FsmAnimationClips clips = new FsmAnimationClips();
AnimationClipLoader.DecodedFile decoded =
        AnimationClipLoader.decodeFile(JsonParser.parseString(json));
for (AnimationClip clip : decoded.clips()) {
    AnimationClipLoader.register(clips, clip);   // 写 RESOURCE 半区，SCRIPT 同 id 条目不被覆盖
}
```

### reference 使用说明

`parseContentBody`、`parseIndexManifest`、`resolveIndexManifests`、`DuplicateIdResolver`、`EffectiveId` 全为纯函数，可在无 NeoForge 运行时的普通 JUnit 测试里直接断言。`buildForMod`、`buildAll`、`ReloadOrchestrator.reload`、`FsmAnimationClips` 的注册侧需要 mod 或游戏运行时；`buildForMod` 类测试通常用 `Assumptions.assumeTrue` 守卫（见 `JsonTreeBuilderIndexPathTest`、`JsonTreeBuilderLoadingErrorTest`）。
