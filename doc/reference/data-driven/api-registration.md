# 数据驱动注册期 API（JsonTreeBuilder、索引与内容分派）

> 本篇是 reference（查阅用）。对象是 KasugaLibNext data-driven 的 Java API：类、方法、签名、调用时机与失败行为。
> 适用版本：Minecraft 1.21.1 / NeoForge 21.1.203 / Parchment 2024.11.17 / Java 21。文中签名与行号对应当前工作树（`modules/data-driven`、`modules/core` 与 `modules/modelling`），覆盖 v2 多来源构建管线（`DataDrivenRegistrationProviders`、`ContentSourceFactory.snapshot`、`DataDrivenBuildPipeline`）与兼容单 mod 入口（`JsonTreeBuilder`）。
> 边界：JSON 字段写哪个键、什么类型、是否必填，归 [schema.md](schema.md)；上手教程归 [intro.md](../../guides/data-driven/intro.md)，内容作者与扩展作者的步骤归 [guide-content.md](../../guides/data-driven/guide-content.md) / [guide-extension.md](../../guides/data-driven/guide-extension.md)。
> 术语：**域（domain）** 指索引清单里的两个数组 `on_register`（注册域）与 `on_reload`（重载域）；**桶（bucket）** 指旧入口与 reload 域的诊断列表（mod 维按 mod id，源键维 `RELOAD_DATA[<命名空间>]`），而 v2 多来源构建管线产生结构化 `BuildDiagnostic`（不写入 `Diagnostics`）。

## 1. 模块与包结构

代码根：`modules/data-driven/src/main/java/lib/kasuga/registration/data_driven/`。

```text
data_driven/
├── DataDrivenRegistrationProvider.java   # v2 宿主接管函数式接口（替代旧 mod-JAR 扫描）
├── DataDrivenRegistrationProviders.java  # 进程级 provider 安装/查询/关闭点
├── JsonTreeIntegration.java              # 与 RegisterContextRegistry 的接线点（先查 provider，再回退 JsonTreeBuilder）
├── TypeHandler.java                      # 处理器接口：顶层/内嵌、phase、identity、capabilities/validate/claims
├── TypeHandlerRegistry.java              # 全局静态处理器表（LinkedHashMap 保序，供旧入口与复用）
├── builder/
│   └── JsonTreeBuilder.java              # 兼容单 mod 注册入口与纯 JVM 索引/内容解析函数
├── context/
│   ├── BuildContext.java                 # 构建上下文基类（hostModId / rootGroup / currentDefinition / strict / meta）
│   ├── RegBuildContext.java              # 注册场景上下文（group / creativeTab / reg 存取）
│   └── JsonRegistryGroup.java            # JSON 创建的根组/属性组（RegistryGroup 子类）
├── dedup/
│   ├── DuplicateIdResolver.java          # 纯函数 last-wins 去重
│   └── EffectiveId.java                  # id 归一化（of / ofLegacy）
├── diagnostics/
│   ├── Diagnostics.java                  # 诊断门面（mod 维 + 域/源键维）
│   └── DataDiagnosticsCommands.java      # /kasuga_data errors [mod]
├── handler/
│   ├── MetaTypeHandler.java              # 标记基类
│   ├── RegistryGroupDef.java / RegistryGroupHandler.java    # registry_groups，PHASE_GROUPS
│   ├── RegTypeHandler.java               # 注册类 handler 抽象基类（blocks/items 共用）
│   ├── BlockDef.java / BlockTypeHandler.java                # blocks，PHASE_CONTENT
│   ├── ItemDef.java / ItemTypeHandler.java                  # items，PHASE_CONTENT
│   └── BlockEntityDef.java / BlockEntityTypeHandler.java    # block_entities（内嵌），PHASE_EMBEDDED
├── json/
│   └── DataJson.java                     # StatePropertySet 与 creativeTab 的 Gson 绑定
├── pipeline/                             # v2 多来源构建管线
│   ├── BuildDiagnostic.java              # 结构化诊断（Severity / Stage / Code）
│   ├── BuildFinalizer.java               # apply 完成后的会话收尾钩子
│   ├── BuildOptions.java                 # LEGACY / STRICT 策略
│   ├── BuildResult.java                  # 构建结果（Status / rootGroup / diagnostics / finalizerResults）
│   ├── DataDrivenBuildHooks.java         # 进程级按 hostModId 分桶的 finalizer 登记点
│   ├── DataDrivenBuildPipeline.java      # prepare / build 两阶段多来源管线
│   ├── DefinitionKey.java                # (typeDomain, id) 逻辑定义键
│   ├── HandlerCapability.java            # STRICT_PREFLIGHT / NON_REGISTRATION / REGISTRY_CLAIMS / OVERLAY
│   ├── LocatedContentPath.java / LocatedDefinition.java / LocatedIndexPath.java
│   ├── PipelineValidationContext.java    # ValidationContext 的管线实现
│   ├── PreparedContent.java              # prepare 产出的不可变会话快照
│   ├── RegistrationTarget.java           # 创建 RegBuildContext 的工厂钩子
│   └── RegistryClaim.java / RegistryClaimSnapshot.java  # 注册表占坑声明与宿主快照
├── property/
│   ├── JsonPropertyParser.java / JsonItemParser.java        # 方块/物品属性解析与 validateStrict
│   ├── StrictPropertyIssue.java / StrictPropertyValues.java
│   └── compiler/                         # PropertyCompiler / RLCompiler
├── reload/                               # 重载域：扩展点 + 编排器 + 受管白名单
│   ├── ReloadHandler.java / ReloadHandlerRegistry.java
│   ├── ReloadOrchestrator.java / ManagedReloadAllowlist.java
│   └── Decoded.java / Reloaded.java
├── resource/
│   └── ScriptBlockReferenceDecoder.java
├── source/                               # v2 内容来源抽象与适配器
│   ├── ContentSource.java / ContentSourceFactory.java / SnapshotContentSource.java
│   ├── SourceDescriptor.java / LogicalPath.java / LocatedFile.java
│   ├── DirectoryContentSource.java / ZipContentSource.java / ModJarContentSource.java / NioRootContentSource.java
│   └── SourceAccessException.java / SourceLimits.java / LimitedInputStream.java / ZipSecurity.java
├── state/                                # 方块状态属性支持
│   ├── DataDrivenBlock.java / DataDrivenBlockFactory.java
│   └── StateProperty.java / StatePropertyContext.java / StatePropertySet.java / StatefulBlockFactory.java / StringStateProperty.java
├── structure/
│   └── ContentStructure.java             # 共享纯函数结构检查层
└── validation/
    └── ValidationContext.java            # 预检只读验证上下文接口
```

相关外部模块：

- `modules/core/src/main/java/lib/kasuga/core/resource/`：`ResourceSource`、`ResourceSnapshot`、`FixedResourceSource`、`ResourceSources`、`ResourceSourceDescriptor`、`InputPolicy`、`StrictResourceJson`、`ApprovedResourcePacks`、`MappedPackResources`；以及 `lib.kasuga.registration.RegistryGroup`（`dispatchWhen`）、`lib.kasuga.registration.factory.FactoryRegistry`。
- `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/dynamic/fsm/`：`FsmReloadHandler`、`FsmClipsReloadHandler`、`FsmReloadHandlerRegistrar`、`FsmBlockEntityFactories`、`StateMachineDefinitionLoader`、`AnimationClipLoader`。
- `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/proxies/`：`ProxyModelBlockFactories`（`proxy_model` / `proxy_model_be`）、`ProxyModelBlock`、`ProxyModelBlockEntity`、`ProxyModelSelection`、`CompiledModelGroup`、`CompiledModelPart`、`ProxyCommands`（`/kasuga_proxy list`）。

依赖方向：`modelling → data-driven → core`。`data-driven` 不依赖 `modelling`。

## 2. 注册期兼容入口：JsonTreeBuilder

`builder/JsonTreeBuilder.java` 是单 mod JAR 的**兼容注册入口**（同时对外暴露纯 JVM 索引/内容解析函数）。所有方法为 `static`，类不可实例化（`:882`）。多来源、外部包、依赖隔离、严格预检与 claim 检查应使用 §16–§18 的 v2 `DataDrivenRegistrationProvider` + `DataDrivenBuildPipeline`。

### 2.1 buildForMod

```java
public static JsonRegistryGroup buildForMod(String modId)   // JsonTreeBuilder.java:67
```

职责：加载一个 mod JAR 内的 `on_register` 内容，构建并返回它的根注册组 `<modId>:json_root`。

参数约束：`modId` 必须能在 FML 的 `ModList` 中找到对应 mod 文件（内部经 `findModFile`，`:744`）。找不到时返回 `null`，不报错。

执行顺序（一次调用）：

1. `ensureHandlersRegistered()`（`:57`），幂等，注册 `DataDrivenBlockFactory` 与四个内置 `TypeHandler` 一次。
2. `clearLoadingErrors(modId)`（`:69`），只清该 mod 的桶。
3. 定位索引目录 `data/<modId>/kasuga_lib/data_driven/`（`findIndexDir`，`:797`）。
4. 逐个读取索引清单（`listIndexFiles` 扫目录，`readIndexManifest` 解析），再聚合（`resolveIndexManifests`）。
5. 只解析 `on_register` 指向的内容文件；`on_reload` 这一段在这里被识别、校验，但不解析（`:106-117`）。
6. 在任何 apply 副作用之前解决重复 id（`:125`）。
7. 按 `getPhase()` 升序 apply 各 handler；`registry_groups` 在 apply 前额外拓扑排序（`:128-152`）。
8. 返回 `rootGroup`（`:154`）。

返回 `null` 的情形：

| 情形 | 位置 | 是否记错误 |
|------|------|-----------|
| 索引目录不存在，且旧布局目录 `data/<mod>/kasugalib/` 也不存在 | `:82-85` | 否，只记 DEBUG |
| 索引目录不存在，但旧布局目录存在 | `:73-81` | 是，WARN + 入桶，提示迁到 `on_register` |
| 索引目录存在，但没有任何清单声明任一域（含空目录） | `:103-105` | 是，聚合阶段记「declares neither ... in any index file」 |

注意：只声明 `on_reload` 的 mod 不算 `nothingDeclared()`，所以它返回一个非 null 的空根组（`:106-109` 只记 INFO），不是 `null`。

### 2.2 buildAll

```java
public static Map<String, JsonRegistryGroup> buildAll()   // JsonTreeBuilder.java:157
```

职责：遍历 `ModList` 的全部 mod 文件，对每个 mod 调 `buildForMod`，汇总非 null 结果，key 为 mod id，`LinkedHashMap` 保序。同一 mod 文件路径只处理一次（`visitedRoots`，`:168`）。单个 mod 抛异常时，`catch` 记 ERROR 并把异常包成 `IllegalStateException` 入该 mod 桶（`:177-181`），继续处理下一个 mod。目前生产接线走 `JsonTreeIntegration`（逐 mod 按回调分派），全仓没有 `buildAll()` 的调用点。

### 2.3 JsonTreeIntegration 的接线时机

`JsonTreeIntegration`（`JsonTreeIntegration.java:13`）是 Micronaut `@Context` bean。`@PostConstruct`（`:21`）向 `RegisterContextRegistry` 注册一个 **COMMON 侧回调**（`:24-42`）：

1. 先查 `DataDrivenRegistrationProviders.find(modRegistry.getModId()).orElse(null)`（`:25-26`）：
   - 若已安装 provider：调用 `provider.build(modRegistry.getModId())`；返回非 `null` 时挂为 `modRegistry` 子节点；**返回 `null` 时直接返回，不会回退到 `JsonTreeBuilder`**；provider 抛出的异常也不被此处的 `try/catch` 吞掉（由宿主自行管理诊断）。
2. 若未安装 provider：在 `try/catch` 中回退调用 `JsonTreeBuilder.buildForMod(modRegistry.getModId())`，非 `null` 时挂为子节点（`:34-41`）。

该回调惰性触发：首次该 mod 的注册事件分派时才执行，因此所有 `@Context` 静态工厂注册及早期 provider 安装只要在此之前完成，就会先于 JSON 解析。

### 2.4 阶段语义

| 常量 | 值 | 使用它的 handler | 原因 |
|------|-----|-----------------|------|
| `TypeHandler.PHASE_GROUPS` | 0 | `registry_groups` | 分组必须先于内容存在 |
| `TypeHandler.PHASE_CONTENT` | 1 | `blocks`、`items` | 常规内容 |
| `TypeHandler.PHASE_EMBEDDED` | 2 | `block_entities` | BE 要挂在已存在的方块 Reg 上 |

同一 phase 内的顺序在 `JsonTreeBuilder` 中为 `TypeHandlerRegistry` 的注册顺序（`LinkedHashMap`）；在 v2 `DataDrivenBuildPipeline` 中还会依次按 handler 列表位置、`sourceOrder`、`logicalPath`、`jsonPointer`、`declarationOrder` 与 `parentDefinition` 拓扑顺序排序（见 §18）。`JsonTreeBuilder` 中 `registry_groups` 另经 `topoSortGroupDefs`（`:890`）按 `parent` 引用拓扑排序；有环时打 `Cycle detected among registry groups` WARN，环上节点按原序 apply，找不到的 parent 回退挂根组（v2 `STRICT` 下则报 `GROUP_CYCLE` blocking error）。

## 3. 索引 API

索引目录：`data/<modId>/kasuga_lib/data_driven/`。目录下每个 `.json` 都是一份清单，清单只声明内容文件路径，不声明类型。

### 3.1 IndexManifest

```java
public record IndexManifest(boolean declaredOnRegister, boolean declaredOnReload,
                            List<String> onRegisterPaths, List<String> onReloadPaths)
// JsonTreeBuilder.java:368
```

一份清单解析后的结果，未被消费。`empty()`（`:371`，包内可见）返回「什么都没声明」的实例，用于清单文件整体读不出时。

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
// JsonTreeBuilder.java:386
// public boolean nothingDeclared()   // :389
```

一个 mod 的全部清单聚合、并剔除跨域冲突后的结果。`nothingDeclared()` 是 D4 判据：为 true 时没有任何清单声明过任一域，调用方必须直接返回、不注册任何东西。

### 3.3 parseIndexManifest

```java
public static IndexManifest parseIndexManifest(String modId, String label, JsonObject root)
// JsonTreeBuilder.java:228
```

职责：解析单份内存中的清单，不消费任何文件。无 NeoForge 依赖，可在纯 JVM 测试中调用。

约束：`root` 为 `null` 视为空文件。只认 `on_register` 与 `on_reload`；其他顶层字段走未知字段通道报错（见 3.6）。返回永不 `null`。

失败行为：所有违规都记 ERROR 并入 `modId` 的桶，不抛异常；同一次调用里一个字段坏了不影响兄弟字段（D3）。

### 3.4 resolveIndexManifests

```java
public static ResolvedIndex resolveIndexManifests(String modId, List<IndexManifest> manifests)
// JsonTreeBuilder.java:318
```

职责：聚合一个 mod 的全部清单，做跨域 fail-closed 检查，返回两个域各自可消费的路径。纯函数，可测。

约束：`manifests` 按加载顺序传入，聚合保持该顺序。

失败行为：

- 同一路径同时出现在 `on_register` 与 `on_reload`（同一份或跨清单均算），该路径报 ERROR 入桶（D6），并从**两个域**都移除，谁都不消费。
- 没有任何清单声明任一域时，记一条聚合 WARN + 入桶（D4），返回的 `nothingDeclared()` 为 true。

### 3.5 listIndexFiles

```java
public static List<Path> listIndexFiles(Path indexDir, String modId)   // JsonTreeBuilder.java:783
```

职责：列出索引目录下以 `.json` 结尾的文件，按路径字符串排序。目录无法列出时记 ERROR 并入桶，返回空列表。

### 3.6 D1 到 D6 在 API 层的体现

| 规则 | 含义 | 落点 |
|------|------|------|
| D1 | 两字段各自可选；两者都不出现（含空文件、只含未知字段）报错且不消费 | `JsonTreeBuilder.parseIndexManifest:247-253` |
| D2 | 空数组合法，且计为「已声明」 | `parseIndexManifest:244`、`resolveIndexManifests:347` 分支 |
| D3 | 未知顶层字段报错并继续；某字段非数组报错并继续处理兄弟字段 | `parseIndexManifest:231-238`、`readPathArray:263-279` |
| D4 | 聚合告警按「声明过哪个字段」分支，而非按解析出的条目 | `resolveIndexManifests:347-352` |
| D5 | `sources` 不是别名，按未知字段报错 | `parseIndexManifest:231-238` |
| D6 | 同路径双列 fail-closed，两域都不消费 | `resolveIndexManifests:330-345` |

v2 `DataDrivenBuildPipeline`（`readManifest:513-588`、`resolveNamespace:635-710`）在结构化诊断模型下贯彻同一套 D1–D6 规则（`MANIFEST_INVALID` / `MANIFEST_EMPTY`）。

### 3.7 废弃字段说明

旧索引字段名为 `sources`。硬迁移（D5）后它不再是合法字段：写 `sources` 会走未知字段通道报错并入桶 / 记为 `MANIFEST_INVALID`，该清单不声明任何域。当前的合法域字段只有 `on_register` 与 `on_reload`。

### 3.8 路径契约

```java
public static String validateSourcePath(String path)              // JsonTreeBuilder.java:407
public static String[] indexDirectorySegments(String modId)       // JsonTreeBuilder.java:766
```

`validateSourcePath`：内容文件路径校验。返回 `null` 表示合法，否则返回人类可读原因。规则：非空、无前导 `/`、以 `.json` 结尾、无空段、无 `.`/`..` 段。路径相对 `data/<mod>/` 解析。纯函数，无 NeoForge 依赖。

`indexDirectorySegments`：返回索引目录相对 mod 文件根的段数组，固定为 `{"data", modId, "kasuga_lib", "data_driven"}`。每次调用返回**新数组**。`ReloadOrchestrator.indexResourcePath` 会退掉前两段，得到包栈路径 `kasuga_lib/data_driven`。

## 4. 内容分派与 apply

### 4.1 parseSource（private）

`parseSource`（`JsonTreeBuilder.java:419`）负责按路径读取并解析一个内容文件：

1. `validateSourcePath` 校验；非法则 ERROR 入桶并返回。
2. 用 `parsedSources` 集合按路径字符串去重；同一内容文件在一次 `buildForMod` 内只解析一次，即使被多份清单引用。
3. 用 `findModFile` 定位 mod 文件，拼接 `data/<modId>/<sourcePath>` 读取。
4. 文件不存在记 `Source file not found` ERROR 入桶。
5. 读取/解析异常记 ERROR 入桶（`:458-461`）。
6. 交给 `dispatchContent`。

### 4.2 dispatchContent（private）

`dispatchContent`（`JsonTreeBuilder.java:485`）把内容文件按顶层字段分发给 handler：

- 先收集所有 `getParentTypeName() == null` 的 handler 名到 `knownFields`，所有内嵌 handler 按 typeName 放进 `embeddedByField`。
- 不在 `knownFields` 里的顶层字段一律报错入桶。若该字段名恰是某个内嵌类型，消息追加提示，指名应写进哪个父条目的哪个键（`:501-506`）。
- 每条未知字段消息还追加条件式提示（D9）：若这些字段属于 reload 域内容，应把文件改列到 `on_reload`（`:515, 580-591`）。
- 对每个已知顶层 handler：字段必须是数组，数组元素必须是对象，否则各自报错入桶并跳过。
- 内嵌类型只经 `extractEmbedded(parentJson)` 进入，不占顶层分发位（`:544-569`）。

### 4.3 parseContentBody

```java
public static Map<String, List<Object>> parseContentBody(String modId, String contentLabel, JsonObject root)
// JsonTreeBuilder.java:641
```

职责：内容管线的内存半段。`parseSource` 负责定位与读取，读完后把 body 交给它。返回按「handler typeName 到定义列表」分组的不可变 map，分派顺序保序。只解析与收集，不注册、不 apply；所有畸形输入经桶报告、不抛异常，无 NeoForge 依赖。

### 4.4 applyUnchecked

```java
public static void applyUnchecked(TypeHandler handler, Object definition, BuildContext context, String modId)
// JsonTreeBuilder.java:733
```

职责：执行一次 `handler.apply`，把异常转成入桶的诊断（`Failed to apply '<type>' handler for mod '<mod>'` ERROR，原异常保留为 cause）。

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
- `resolve` 为 last-wins：同一键取候选序列中最后出现的那个。
- `describe` / `toLoadingError` 产出人类可读消息，含类型字段、identity、败者与胜者各自的 source 文件。

### 5.2 EffectiveId

```java
public static String of(String modId, String rawId)         // EffectiveId.java:39
public static String ofLegacy(String modId, String rawId)   // EffectiveId.java:54
```

职责：把原始 id 归一到它最终注册的 `ResourceLocation` 字符串。两种模式对显式 `minecraft:` 前缀处理不同：

| 原始 id | `EffectiveId.of`（v2 `STRICT`） | `EffectiveId.ofLegacy`（旧入口 & `LEGACY`） |
|---------|---------|---------|
| `foo` | `<modId>:foo`（`STRICT` validate 另拒绝裸 id） | `<modId>:foo` |
| `minecraft:foo` | **`minecraft:foo`**（保持原 namespace） | `<modId>:foo`（历史重写） |
| `othermod:foo` | `othermod:foo`（原样） | `othermod:foo`（原样） |

`rawId` 为 `null` 时返回 `null`。只按第一个 `:` 切分命名空间与路径。

### 5.3 各类型的 identity

| 类型 | identity | 出处 |
|------|----------|------|
| `blocks` / `items` | `EffectiveId.of`（strict）或 `EffectiveId.ofLegacy`（legacy） | `RegTypeHandler.resolveIdentity`（`RegTypeHandler.java:77-86`） |
| `block_entities` | 宿主方块的 effective id（同上 mode-aware 规则） | `BlockEntityTypeHandler.resolveIdentity`（`:87-96`） |
| `registry_groups` | `id` 原串，不归一化 | `RegistryGroupHandler.resolveIdentity`（`:66-68`） |
| 自定义 handler | `TypeHandler.resolveIdentity` 默认返回 `null` | `TypeHandler.java:112-120` |

`registry_groups` 不归一化的原因：分组不进 MC 注册表，且 loader 内处处按原串引用，只归一化判定键会制造「判定重复、引用找不到」的错配。

### 5.4 last-wins 顺序（旧入口）

`resolveDuplicates`（`JsonTreeBuilder.java:673`）按 `parsed` 的加载顺序构造候选：

1. 索引文件按文件名升序；
2. 单份清单内按 `on_register` 数组书写顺序；
3. 单内容文件内按类型字段数组书写顺序；
4. 内嵌 `block_entities` 跟随宿主 `blocks` 条目的位置。

败者整条不进入 apply；每条冲突记一条 WARN 并作为 `IllegalStateException` 入该 mod 桶。v2 `DataDrivenBuildPipeline` 的多来源选择与 `OVERLAY` / `REGISTRY_CLAIMS` 语义见 §18 与 [api-extension.md](api-extension.md) §8.8。

---

## 16. v2 宿主接管：`DataDrivenRegistrationProvider` 与 `DataDrivenRegistrationProviders`

### 16.1 DataDrivenRegistrationProvider

`DataDrivenRegistrationProvider.java:8-11`：

```java
@FunctionalInterface
public interface DataDrivenRegistrationProvider {
    JsonRegistryGroup build(String hostModId);
}
```

职责：由宿主 mod 接管指定 `hostModId` 的 JSON 注册树构建（取代 `JsonTreeBuilder.buildForMod` 对该 mod JAR 的扫描）。返回 `null` 表示本轮不挂载 JSON 根组；返回非 `null` 时由 `JsonTreeIntegration` 挂入 `modRegistry`。

### 16.2 DataDrivenRegistrationProviders

`DataDrivenRegistrationProviders.java:11-55`：

```java
public static Attachment install(String hostModId, DataDrivenRegistrationProvider provider)  // :14
public static Optional<DataDrivenRegistrationProvider> find(String hostModId)                // :25

public static final class Attachment implements AutoCloseable {
    @Override public void close()                                                            // :51
}
```

- **进程级安装表**：内部为 `ConcurrentHashMap<String, Attachment>`。
- **唯一性约束**：同一 `hostModId` 第二次调用 `install` 会立即抛 `IllegalStateException`（`:18-21`）；`hostModId` 为 `null` 或空白抛 `IllegalArgumentException`（`:31-36`）。
- **精确卸载**：`Attachment.close()` 使用 `PROVIDERS.remove(hostModId, this)` 只移除自己安装的实例，适用于测试夹具清理或显式卸载（`:51-53`）。
- **安装时机**：必须在 `JsonTreeIntegration` 注册回调首次分派该 mod 前完成安装（通常在宿主 mod 构造早期或 `@Context` 初始化中）。

## 17. v2 内容来源与快照：`ContentSource`、`ContentSourceFactory` 与 `SnapshotContentSource`

### 17.1 平台来源与注册视图分层

v2 生产接线把物理读取与注册视图拆分为两层（源码分属 `modules/core` 与 `modules/data-driven`）：

1. **平台捕获层（`modules/core` `lib.kasuga.core.resource.source`）**：
   - `ResourceSourceDescriptor(String ownerId, String sourceId, String rootId, int rank, Origin origin)`（`ResourceSourceDescriptor.java:6-16`）：唯一平台身份。`ownerId` / `sourceId` / `rootId` 均须符合平台 ID 规则（`ResourcePaths.id`，不含冒号），`rank >= 0`，`origin` 为 `EXTERNAL` / `MC_MANAGED` / `DERIVED`。
   - `ResourceSource`（`ResourceSource.java:5-9`）：通过 `ResourceSources.mod` / `directory` / `readOnlyDirectory` / `archive` / `vfs` 创建；`ResourceSources.fixed(descriptor, source, policy)`（`ResourceSources.java:94-118`）立即捕获一份不可变 `baseline` 并关闭底层 `liveSource`，返回 `FixedResourceSource`，之后每次 `capture()` 返回互相独立的 `ResourceSnapshot`，避免原目录在启动过程中被修改。
   - `ResourceSnapshot`（`ResourceSnapshot.java:17-187`）：不可变捕获字节（不超过 `InputPolicy.memoryThresholdBytes` 存内存，更大文件写私有临时 spool 文件）；`open()` / `lease()` 引用计数保证已打开流在 `snapshot.close()` 后仍可读完，但关闭后不能新建 lease（`STALE_REFERENCE`）。
   - `InputPolicy.DEFAULT`（`InputPolicy.java:6`）：单 JSON 文件 8 MiB、单文件 256 MiB、单 snapshot 总量 1 GiB、最多 50,000 文件、JSON 最大深度 64、内存阈值 1 MiB。
2. **注册视图层（`modules/data-driven` `lib.kasuga.registration.data_driven.source`）**：由 `ContentSource` 将单个已捕获 root 暴露给 `DataDrivenBuildPipeline`。

### 17.2 ContentSource 与辅助值类型

`ContentSource.java:16-66`：

```java
public interface ContentSource extends AutoCloseable {
    SourceDescriptor descriptor();                                // :17
    Optional<InputStream> open(LogicalPath path) throws IOException;  // :24
    boolean exists(LogicalPath path);                             // :26
    List<LocatedFile> files();                                    // :31 按逻辑路径排序的不可变列表
    default List<LocatedFile> indexes()                           // :37 data/<ns>/kasuga_lib/data_driven/*.json
    default InputStream requireOpen(LogicalPath path) throws IOException; // :44 缺失抛 NOT_FOUND
    @Override default void close() throws IOException
}
```

配套值类型：

- `SourceDescriptor`（`SourceDescriptor.java:20-112`）：
  - 新平台工厂：`SourceDescriptor.of(ResourceSourceDescriptor resource, Collection<String> namespaces, String diagnosticLabel)`（`:78-84`），保留 `rootId()`、`rank()`、`origin()` 与 `resource()`。
  - 旧兼容工厂：`SourceDescriptor.of(String sourceId, String ownerId, Collection<String> namespaces)`（`:74-76`），`resource()` 为 `null`，`rank()` 为 `0`。
  - `namespaces` 必须非空且每项符合 `[a-z0-9_.-]+`，构造后按字典序不可变保存。
- `LogicalPath`（`LogicalPath.java:15-83`）：相对、`/` 分隔的字面路径（不做归一化），拒绝空白、前导 `/`、`\`、Windows 盘符、空段、`.`/`..`、NUL 与 ISO 控制字符。
- `LocatedFile`（`LocatedFile.java:11-47`）：记录 `source`、`path`、`size`，以及快照来源的 `uri`（`ResourceUri`）、SHA-256 `revision` 与 `tags`。
- `SourceLimits`（`SourceLimits.java:6-36`）：旧非快照适配器的字节与文件数上限（`DEFAULT` 同为 8 MiB JSON / 256 MiB 单文件 / 1 GiB 总量 / 50,000 文件）。
- `SourceAccessException`（`SourceAccessException.java:9-90`）：携带 `Reason`（`NOT_FOUND`、`ACCESS_DENIED`、`OUT_OF_BOUNDS`、`SYMLINK_REJECTED`、`DUPLICATE_ENTRY`、`INVALID_ENTRY`、`LIMIT_EXCEEDED`、`CORRUPT_ARCHIVE`、`SOURCE_CLOSED`、`IO_ERROR`）、`sourceId` 与可选 `LogicalPath`。

### 17.3 ContentSourceFactory

`ContentSourceFactory.java:21-140`：

| 方法 | 用途 | 适用场景 |
|------|------|---------|
| `snapshot(ResourceSnapshot, ResourceSourceDescriptor, Collection<String> namespaces, String diagnosticLabel)`（`:52`） | 将固定快照包装为 `SnapshotContentSource`（默认扫描全部标准索引） | **v2 生产路径** |
| `snapshot(snapshot, resource, namespaces, diagnosticLabel, Collection<String> declaredIndexes)`（`:61`） | 同上，并显式指定 `indexes()` 可见的索引逻辑路径集合（Scene indexing） | **v2 生产路径**（如 parcel root） |
| `directory(Path, SourceDescriptor[, SourceLimits])`（`:25,29`） | 打开普通目录（拒绝 symlink / 特殊文件） | 兼容 / 纯 JVM 测试 |
| `zip(Path, SourceDescriptor[, SourceLimits])`（`:34,38`） | 打开 ZIP/JAR 归档 | 兼容 / 工具 |
| `modJar(IModFile, SourceDescriptor[, SourceLimits])`（`:43,47`） | 打开 NeoForge `IModFile`（不关闭共享 secure JAR FS） | 兼容 |
| `open(Path, SourceDescriptor[, SourceLimits])`（`:80,84`） | 按物理路径自动选择 `directory` 或 `zip` | 兼容 / CLI |

### 17.4 SnapshotContentSource

`SnapshotContentSource.java:23-145`：

- 静态工厂 `SnapshotContentSource.open(...)`（被 `ContentSourceFactory.snapshot(...)` 直接委派）。
- 构造校验：
  - `snapshot` 中每个文件的 `ownerId`、`sourceId`、`rootId` 必须与传入的 `ResourceSourceDescriptor` 完全一致，否则抛 `IllegalArgumentException`（`:52-57`）；
  - 不允许重复 `logicalPath`（`:43-47`）；
  - 按 `path` 升序排列并保留 `uri`、`revision`、`tags`、`size`。
- **`declaredIndexes` 语义**（`:40, 121-129`）：
  - 传入 `null`：沿用 `ContentSource.super.indexes()`，自动发现所有 `data/<declared-ns>/kasuga_lib/data_driven/*.json`；
  - 传入具体路径集合：只保留路径在 `declaredIndexes` 中的文件作为 `indexes()` 返回值（传空集即零 manifest 的纯资源 root）。注意它**不裁剪** `files()` 或 `open()`，宿主须在构建前自行核对声明的索引路径真实存在于快照中。
- **所有权与关闭语义**：`SnapshotContentSource` 接管传入 `ResourceSnapshot` 的所有权，调用 `close()` 时调用 `snapshot.close()`（`:132-137`）。关闭后访问 `open`、`exists`、`files` 抛 `IllegalStateException`（`:139-144`）。注意：**不要把同一份 `ResourceSnapshot` 交给多个 `SnapshotContentSource` 或消费者**；需要共享同一捕获字节时，应从 `FixedResourceSource` 多次 `capture()`。

---

## 18. v2 多来源构建管线：`DataDrivenBuildPipeline` 与 Hooks

`pipeline/DataDrivenBuildPipeline.java:54-1644` 将多来源的发现、严格 JSON 解码、定义选择、跨来源预检、registry claim 校验、apply 与 finalizer 封装为 `prepare` + `build` 两阶段流程。

### 18.1 构造与选项

```java
public DataDrivenBuildPipeline(
        String hostModId,
        Collection<? extends TypeHandler<?>> handlers,
        Collection<? extends BuildFinalizer> finalizers)   // :64

public DataDrivenBuildPipeline(
        String hostModId,
        Collection<? extends TypeHandler<?>> handlers,
        Collection<? extends BuildFinalizer> finalizers,
        RegistrationTarget registrationTarget,
        Collection<String> reservedSymbols)                // :72

public DataDrivenBuildPipeline(
        String hostModId,
        Collection<? extends TypeHandler<?>> handlers,
        Collection<? extends BuildFinalizer> finalizers,
        RegistrationTarget registrationTarget,
        Collection<String> reservedSymbols,
        Collection<RegistryClaim.RegistryKey> reservedRegistryClaims)  // :89
```

- 管线**不自动读取** `TypeHandlerRegistry` 或 `DataDrivenBuildHooks`：宿主必须显式传入 `handlers` 列表与 `finalizers`（例如先调用 `JsonTreeBuilder.ensureHandlersRegistered()` 后传入 `TypeHandlerRegistry.all()`，并合并 `DataDrivenBuildHooks.finalizersFor(hostModId)`）。
- `handlers` 列表顺序参与同一 phase 内的稳定排序；在 `STRICT` 模式下，重复 `getTypeName()` 会产生 `HANDLER_NOT_STRICT_READY`。
- `BuildOptions`（`BuildOptions.java:6-20`）：
  - `BuildOptions.LEGACY`：保留 Gson 宽容解码、`EffectiveId.ofLegacy` 身份规则，跳过 `STRICT` 能力与 `validate` 预检，不因 `hasBlockingDiagnostics()` 拒绝 `build`；
  - `BuildOptions.STRICT`（`isStrict() == true`）：使用 `StrictResourceJson`、`EffectiveId.of`、强制 handler 能力检查、全量 `validate` 与 claim 冲突检查，存在任意 `ERROR` 诊断时拒绝整批 apply（`REJECTED`）。

### 18.2 `prepare` 阶段与 `PreparedContent`

```java
public PreparedContent prepare(Collection<? extends ContentSource> sources, BuildOptions options)
public PreparedContent prepare(Collection<? extends ContentSource> sources, BuildOptions options,
                               Map<String, ? extends Collection<String>> dependencies)
public PreparedContent prepare(Collection<? extends ContentSource> sources, BuildOptions options,
                               Map<String, ? extends Collection<String>> dependencies,
                               Collection<String> sharedAssetOwners)  // :111-143
```

参数说明：

- `sources`：内容来源集合。凡 `descriptor.resource() != null` 的平台来源均按显式 `rank` 排序作为 `sourceOrder`；仅旧 4 参数 `SourceDescriptor`（`resource() == null`）按输入位置分配顺序。
- `dependencies`：按 `ownerId` 索引的直接依赖集合（仅认直接依赖，不传递）。未知依赖 owner 在 `STRICT` 下报 `UNKNOWN_DEPENDENCY` error，在 `LEGACY` 下报 warning（`:411-461`）。
- `sharedAssetOwners`：允许所有来源的 `ValidationContext.resourceExists(ResourceDomain.ASSETS, ...)` 查询其 `assets/` 资源（但不授予 `data/` 或定义可见性，`:158-164`）。

`prepare` 执行顺序（全过程不调用任何 `TypeHandler.apply`）：

1. 校验 `(sourceId, rootId)` 唯一性与全局 `rank` 唯一性（重复报 `INVALID_SOURCE`，`:368-409`）。
2. 归一化并校验 `dependencies`（`:411-461`）。
3. 逐 source 读取 `indexes()`，聚合各 namespace 的 manifest（D1–D6），解析 `on_register` 文件（`parse` + `resolveIdentity(hostModId, parsed, options.isStrict())`），收集 `on_reload` 路径（`:463-511, 764-917`）。每个 source 异常隔离为 `SOURCE_READ_FAILED`（`:173-185`）。
4. 对所有 `on_reload` 文件按顶层键查找 `ReloadHandlerRegistry.get(field)` 并调用 `decode` 做预检（`validateReloadContentFile`，`:919-1025`），**不调用** `clearBucket()` 或 `register()`。
5. `STRICT` 模式下校验所有被使用 handler 的能力声明（`STRICT_PREFLIGHT` + `strictValidationImplemented()` + 恰好一个 `NON_REGISTRATION` 或 `REGISTRY_CLAIMS`，`:1101-1147`）。
6. 按 `selectDefinitions`（`:1149-1183`）选择生效定义：`OVERLAY` 或 `STRICT && REGISTRY_CLAIMS` 保留同 key 全部候选；否则按 `(sourceOrder, declarationOrder)` 取最后胜者。
7. `STRICT` 模式下按稳定顺序为每个 selected definition 构造 `PipelineValidationContext` 并调用 `validate`（`:1185-1226`）。
8. 对 `parentDefinition` 链做环检测（`GROUP_CYCLE`：`STRICT` 为 `ERROR`，`LEGACY` 为 `WARNING`，`:1233-1297`）。
9. 对带 `REGISTRY_CLAIMS` 的 selected definition 调用 `claims`，校验与 `reservedRegistryClaims` 及互相之间的 `(registry, id)` 冲突（`CLAIM_CONFLICT` / `CLAIM_FAILED`，`:1304-1401`）。

输出为不可变记录 `PreparedContent`（`PreparedContent.java:14-81`）：

```java
public record PreparedContent(
        String hostModId,
        BuildOptions options,
        List<SourceDescriptor> sources,
        List<LocatedDefinition<?>> definitions,
        List<LocatedDefinition<?>> allDefinitions,
        List<LocatedContentPath> registerFiles,
        List<LocatedContentPath> reloadFiles,
        List<LocatedIndexPath> indexFiles,
        List<RegistryClaim> claims,
        List<BuildDiagnostic> diagnostics,
        Map<String, Set<String>> dependencies,
        Set<String> reservedSymbols
) {
    public boolean hasBlockingDiagnostics()  // true when any diagnostic has Severity.ERROR
}
```

其中每条定义包装为 `LocatedDefinition<T>`（`LocatedDefinition.java:22-45`）：保存 `key`（`DefinitionKey`）、`handler`、`definition`、`source`（`SourceDescriptor`）、`sourceOrder`、`logicalPath`、`jsonPointer`、`declarationOrder`。

### 18.3 `build` 阶段、`BuildResult`、`BuildFinalizer` 与 `RegistrationTarget`

```java
public BuildResult build(PreparedContent prepared)                             // :231
public BuildResult build(PreparedContent prepared, RegistrationTarget target)  // :241
```

- 校验 `prepared.hostModId()` 与 pipeline 一致；不一致抛 `IllegalArgumentException`。
- 若 `prepared.options().isStrict() && prepared.hasBlockingDiagnostics()`，立即返回 `Status.REJECTED`（`rootGroup == null`，无任何 apply 或 finalizer 副作用）。
- 创建 `JsonRegistryGroup(hostModId + ":json_root")`，安装默认 `ResourceLocation` 映射（将未显式覆盖的路径映射到 `hostModId`），并通过 `target.createContext(hostModId, root, prepared.options())` 构建 `RegBuildContext`（`:260-266`）。
- 按 `stableApplyOrder`（`:1411-1477`）排序后依次在 `context.withCurrentDefinition(definition, ...)` 中执行 `handler.apply`：
  1. `TypeHandler.getPhase()` 升序；
  2. `handlers` 列表中的首次出现下标；
  3. `sourceOrder` 升序；
  4. `logicalPath` 字典序；
  5. `jsonPointer` 字典序；
  6. `declarationOrder` 升序；
  7. `parentDefinition` 形成的拓扑先序（父先于子）。
- 所有定义 apply 完成后，按顺序执行 `finalizers`（`BuildFinalizer.afterBuild(BuildContext, PreparedContent)`，`BuildFinalizer.java:9-11`），收集返回值。
- 任意一步（创建 context、排序、apply、finalizer）抛 `RuntimeException` 会立即停止并返回 `Status.FAILED`（保留已构建但可能不完整的 `rootGroup` 与 `cause`，**无自动回滚**）。

`BuildResult`（`BuildResult.java:12-50`）：

```java
public record BuildResult(
        String hostModId,
        Status status,
        @Nullable JsonRegistryGroup rootGroup,
        List<BuildDiagnostic> diagnostics,
        List<Object> finalizerResults,
        @Nullable Throwable cause
) {
    public enum Status { REJECTED, APPLIED, FAILED }
    public boolean successful() { return status == Status.APPLIED; }
    public boolean applied()    { return status == Status.APPLIED; }
}
```

> **注意**：`BuildResult.successful()` 与 `BuildResult.applied()` 均只在 `status == Status.APPLIED` 时返回 `true`（`Status.FAILED` 下两者均为 `false`，即使部分 `apply` 已在抛出前执行过；该契约由 `BuildResultTest.onlyAppliedStatusCountsAsApplied` 锁定）。

`RegistrationTarget`（`RegistrationTarget.java:10-17`）：默认 `RegistrationTarget.standard()` 创建 `new RegBuildContext(hostModId, rootGroup, options.isStrict())`，宿主可提供自定义子类扩展上下文。

`RegistryClaimSnapshot`（`RegistryClaimSnapshot.java:15-39`）：

```java
public static Set<RegistryClaim.RegistryKey> collect(Reg<?, ?> root)  // :19
```

从宿主现有 `Reg` 树做 BFS，收集所有启用分组下的 `MinecraftDeferRegistryReg`（自动跳过 `RegistryGroup.isDispatchEnabled() == false` 的子树），生成供 pipeline 构造器使用的 `reservedRegistryClaims`。

### 18.4 进程级钩子：`DataDrivenBuildHooks`

`DataDrivenBuildHooks.java:12-36`：

```java
public static void registerFinalizer(String hostModId, BuildFinalizer finalizer)  // :16
public static List<BuildFinalizer> finalizersFor(String hostModId)                // :23
```

允许扩展模块在宿主 provider 之外按 `hostModId` 登记会话收尾钩子（内部使用 `ConcurrentHashMap` + `CopyOnWriteArrayList`）。没有注销 API，初始化逻辑需避免重复登记；宿主 provider 在构造 `DataDrivenBuildPipeline` 时需显式读取 `DataDrivenBuildHooks.finalizersFor(hostModId)` 传入。

### 18.5 v1 到 v2 迁移摘要

| 关注点 | v1 旧入口（`JsonTreeBuilder`） | v2 多来源管线 |
|---|---|---|
| 触发接入 | `JsonTreeIntegration` 回退 `buildForMod(modId)` | `DataDrivenRegistrationProviders.install(modId, provider)` |
| 来源与快照 | 直接扫当前 mod JAR 文件 | `ResourceSources.fixed` → `ResourceSnapshot` → `ContentSourceFactory.snapshot` |
| 索引筛选 | 全量扫描 `data/<mod>/kasuga_lib/data_driven/*.json` | 默认同左，或通过 `declaredIndexes` 精确指定（Scene indexing） |
| JSON 解码 | Gson 宽容解析 | `BuildOptions.STRICT` 使用 `StrictResourceJson`（拒绝重复键/注释/尾逗号/畸形 UTF-8） |
| `minecraft:` ID | `EffectiveId.ofLegacy` 重写为 `<hostModId>:path` | `STRICT` 使用 `EffectiveId.of` 保留 `minecraft:`，且拒绝裸 ID |
| 诊断出口 | `JsonTreeBuilder.getLoadingErrors` / `Diagnostics` / `/kasuga_data errors` | `PreparedContent.diagnostics()` / `BuildResult.diagnostics()`（`BuildDiagnostic`） |


> 数据驱动专题其它页：[注册期 API](api-registration.md) · [扩展 API](api-extension.md) · [诊断 API](api-diagnostics.md) · [重载 API](api-reload.md) · [专题索引](README.md)
