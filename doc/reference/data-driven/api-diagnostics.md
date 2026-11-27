# 数据驱动诊断 API（Diagnostics、命令与错误分类）

## 6. 诊断门面 Diagnostics

`diagnostics/Diagnostics.java` 是 v1 注册路径与 reload 域共用的统一诊断出口。错误按两个互不混合的维度寻址：

> **v1 / reload 桶与 v2 `BuildDiagnostic` 的边界**：`Diagnostics` 面向 v1 `JsonTreeBuilder` 与 `ReloadOrchestrator` / `FsmReloadHandler`（游戏内由 `/kasuga_data errors` 查看）。v2 多来源管线 `DataDrivenBuildPipeline` 返回结构化的 `List<BuildDiagnostic>`（挂在 `PreparedContent.diagnostics()` 与 `BuildResult.diagnostics()` 上，见第 19 节），**不会**写入 `Diagnostics` 桶。

### 6.1 维度

- **mod 维**：以 mod id 为键的桶，即既有的 `JsonTreeBuilder` 桶。`report(String, Throwable)` / `errors(String)` 逐字委托给它，因此「断言 `JsonTreeBuilder.getLoadingErrors(modId)` 为空」的 v1 契约继续成立。
- **域/源键维**：以 `(Domain, key)` 为键的桶，用于「属于某个源而非某个 mod」的失败。**已有生产者写入这一维**：reload 域用 `Domain.RELOAD_DATA`，键 = 命名空间（`ReloadOrchestrator` 与 `FsmReloadHandler` 都走它）。其余域（`RELOAD_ASSETS`、`CONFIG`）仍为预留。

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
| `report(Domain domain, String key, Throwable error)`（`:96`） | 记一条失败到某个源的桶 | 三个参数任一为 `null` 抛 `NullPointerException` |
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
| `REGISTRATION` | mod jar 注册内容（域键维预留） |
| `RELOAD_DATA` | 数据包重载数据，`/reload` 时从包栈读 |
| `RELOAD_ASSETS` | 包栈资源（预留） |
| `CONFIG` | `config/` 下文件（预留） |

### 6.4 summarize 输出格式

```java
public static List<String> summarize()   // Diagnostics.java:143
```

每行一个非空桶：mod 桶格式为 `<mod>: N error(s)`；源桶格式为 `<DOMAIN>[<key>]: N error(s)`。mod 在前按 id 排序，随后按 `Domain` 声明顺序、源按 key 排序。

### 6.5 生产者在用哪一维

reload 域错误走**域/源键维**（`Domain.RELOAD_DATA`），键 = 出错文件所在命名空间。`ReloadOrchestrator.reportError`（`:617-627`）与各 handler 的 `reportError` / `reportWarning`（如 `FsmReloadHandler.java:118-130`）均调用 `Diagnostics.report(Domain.RELOAD_DATA, namespace, ...)`。v1 注册期错误走 mod 维。两维输出使用 `Diagnostics.bucketLabel`（`:166`）：mod 维 `[<mod>] <error>`，reload 维 `[RELOAD_DATA[<命名空间>]] <error>`。reload 维在每轮 `runCycle` 开头清空（`Diagnostics.clear(Domain.RELOAD_DATA)`，`ReloadOrchestrator.java:208`），不累积。

## 7. /kasuga_data errors 命令

`DataDiagnosticsCommands.java` 是 `Diagnostics` 门面的游戏内命令入口（代理层诊断命令 `/kasuga_proxy list` 见 19.2）。

```java
public static LiteralArgumentBuilder<CommandSourceStack> kasugaDataCommand()   // DataDiagnosticsCommands.java:45
```

命令语法：

- `/kasuga_data errors`（`printSummary`，`:63`）：打印 `Diagnostics.summarize()` 的每桶一行汇总，包含 mod 维与源维；无错误时打印 `Kasuga data: no loading errors recorded`。
- `/kasuga_data errors <mod>`（`printMod`，`:81`）：同时列出该 mod 的两维错误（mod 维 `[<mod>] <throwable>` 与 `RELOAD_DATA[<mod>]` 源维 `[RELOAD_DATA[<mod>]] <throwable>`）；无错误时打印 `Kasuga data: no loading errors for '<mod>'`。

约束与注册：

- 权限等级 2（`requires(source -> source.hasPermission(2))`，`:47`）。
- `kasugaDataCommand()` 不依赖 dispatcher 或事件，纯测试可直接构造后断言命令树形状。
- 类带 `@EventBusSubscriber`（`:31`），由 `onRegisterCommands`（`:57`）在 `RegisterCommandsEvent` 注册，返回打印行数。

## 12. 错误分类（v1 注册桶与 reload 域）

下表涵盖写入 `Diagnostics` 桶与日志降级的条目（v2 `DataDrivenBuildPipeline` 结构化诊断表见第 19 节）。

进 error 桶（记 ERROR 日志并以异常入桶——v1 注册期入 mod 维；reload 域入 `Domain.RELOAD_DATA` 源维）：

| 情形 | 出处 |
|------|------|
| 索引文件读不出或非 JSON | `JsonTreeBuilder.readIndexManifest:194-204` |
| 索引未知顶层字段（含旧字段名 `sources`） | `JsonTreeBuilder.parseIndexManifest:231-238` |
| 索引两字段都未声明 | `JsonTreeBuilder.parseIndexManifest:247-253` |
| 字段非数组或数组元素非字符串 | `JsonTreeBuilder.readPathArray:263-279` |
| 同路径双列两域（fail-closed） | `JsonTreeBuilder.resolveIndexManifests:330-345` |
| 所有索引都未声明任一域 | `JsonTreeBuilder.resolveIndexManifests:347-352` |
| 索引目录无法列出 | `JsonTreeBuilder.listIndexFiles:783-795` |
| 内容路径非法 | `JsonTreeBuilder.parseSource:420-426` |
| 找不到 mod 文件 | `JsonTreeBuilder.parseSource:432-438` |
| 内容文件缺失 | `JsonTreeBuilder.parseSource:447-452` |
| 内容文件读取/JSON 异常 | `JsonTreeBuilder.parseSource:458-461` |
| 内容未知顶层字段（含顶层 `block_entities`） | `JsonTreeBuilder.dispatchContent:496-518` |
| 类型字段非数组或数组元素非对象 | `JsonTreeBuilder.dispatchContent:523-535` |
| 内嵌抽取失败 | `JsonTreeBuilder.dispatchContent:554-560` |
| 单条目 `parse` 异常 | `JsonTreeBuilder.parseAndCollect:613-620` |
| handler `apply` 抛异常 | `JsonTreeBuilder.applyUnchecked:733-742` |
| 重复 id 冲突 | `JsonTreeBuilder.resolveDuplicates:688-692` |
| 旧布局目录存在 | `JsonTreeBuilder.buildForMod:83` |
| `buildAll` 单 mod 异常 | `JsonTreeBuilder.buildAll:177-181` |
| reload 解码/注册/校验失败 | `ReloadOrchestrator.reportError:617-627`、`FsmReloadHandler.reportError:118-121` |
| reload 试图读取未获受管白名单授权的索引清单 | `ReloadOrchestrator.collectIndex:430-434` |
| reload 收尾发现悬空 clip 引用 | `FsmReloadHandler.reportWarning:127-130` |

只进日志、不进桶（v1 / 非严格模式降级）：

| 情形 | 出处 |
|------|------|
| 非严格模式下找不到工厂 / apply 异常 | `RegTypeHandler:162-166, 193-197` |
| 分组引用环（`topoSortGroupDefs` WARN） | `JsonTreeBuilder:907-909` |
| 未知方块属性 / 非法属性值 | `JsonPropertyParser:152-159, 181, 191` |
| 未知物品属性 / 解析失败 | `JsonItemParser:142-148` |
| BE 父方块找不到 / BE 类型未知 / 异常 | `BlockEntityTypeHandler:141-152` |
| 某 mod 不声明 `on_register`（纯 reload mod） | `JsonTreeBuilder:106-109`（INFO） |
| 索引目录不存在且无旧布局 | `JsonTreeBuilder:73-77`（DEBUG） |

说明：D9 双向提示依然在两个方向分别提示——注册侧未知字段指向 `on_reload`（`JsonTreeBuilder.reloadDomainHint:580-591`，枚举 `ReloadHandlerRegistry`），重载侧未知字段指向 `on_register`（`ReloadOrchestrator.dispatch:494-501`）。

---

## 19. v2 结构化 BuildDiagnostic 与代理诊断

### 19.1 BuildDiagnostic

v2 多来源构建管线 `DataDrivenBuildPipeline` 不依赖全局异常桶，而是把每次 `prepare` 与 `build` 的全部告警与阻断错误以不可变记录 `pipeline/BuildDiagnostic.java:9-76` 收集返回：

```java
public record BuildDiagnostic(
        Severity severity,
        Stage stage,
        Code code,
        @Nullable String sourceId,
        @Nullable String ownerId,
        @Nullable String logicalPath,
        @Nullable String jsonPointer,
        @Nullable String handler,
        String message,
        @Nullable String suggestion
) {
    public boolean blocking() { return severity == Severity.ERROR; }
    public enum Severity { WARNING, ERROR }
    public enum Stage { SOURCE, MANIFEST, PARSE, SELECTION, VALIDATION, CLAIMS, APPLY, FINALIZE }
    public enum Code { ... }
}
```

全部 25 个 `BuildDiagnostic.Code` 按发生阶段归类：

| `Stage` | 涉及的 `Code` | 说明 |
|---|---|---|
| `SOURCE` | `INVALID_SOURCE`, `UNKNOWN_DEPENDENCY`, `SOURCE_FILE_MISSING`, `SOURCE_READ_FAILED` | 来源 `sourceId`/`rootId`/`rank` 重复、`dependencies` 引用未知 owner、索引指向的物理文件缺失或快照/IO 读取失败 |
| `MANIFEST` | `MANIFEST_INVALID`, `MANIFEST_EMPTY`, `INVALID_PATH` | 索引 JSON 非对象、D1–D6 违规（含旧键 `sources` 与同路径双列）、非法 `LogicalPath`、或没有任何索引声明 `on_register`/`on_reload` |
| `PARSE` | `CONTENT_INVALID`, `UNKNOWN_TYPE`, `PARSE_FAILED`, `IDENTITY_FAILED`, `MISSING_IDENTITY`, `HANDLER_NOT_STRICT_READY` | `StrictResourceJson` 语法/重复键违规、未知顶层字段、`on_reload` 预检解码错误、`TypeHandler.parse` 失败、`resolveIdentity` 抛异常或返回空、严格模式下 handler 未完备声明 `STRICT_PREFLIGHT` + `strictValidationImplemented()` + `NON_REGISTRATION`/`REGISTRY_CLAIMS` |
| `VALIDATION` | `INVALID_IDENTITY`, `UNKNOWN_FACTORY`, `UNKNOWN_REGISTRY_GROUP`, `MISSING_REFERENCE`, `REFERENCE_NOT_DECLARED`, `REFERENCE_AMBIGUOUS`, `GROUP_CYCLE`, `VALIDATION_FAILED` | 非法或缺失命名空间的 `ResourceLocation`、未知工厂、未知 `registry_group`、缺失或未声明依赖的跨定义引用、多依赖可见歧义（`findAllVisible.size() > 1`）、`parentDefinition` 形成环（`STRICT` 为 `ERROR`，`LEGACY` 为 `WARNING`）、以及属性/params 严格预检失败 |
| `CLAIMS` | `CLAIM_CONFLICT`, `CLAIM_FAILED` | 两个定义竞争同一 `(registry, id)`、或与 `reservedRegistryClaims`（如宿主 Java 注册项）冲突、或 `TypeHandler.claims` 抛异常 |
| `APPLY` / `FINALIZE` | `APPLY_FAILED`, `FINALIZER_FAILED` | `build` 阶段 `apply` 或 `BuildFinalizer.afterBuild` 抛出运行时异常（同时导致 `BuildResult.status() == Status.FAILED`） |

### 19.2 `/kasuga_proxy list` 与 `ProxyDebug`

代理渲染与动态模型宿主（含 `proxy_model` / `proxy_model_be`）除加载期诊断外，还提供运行时状态快照与追踪工具：

- **命令 `/kasuga_proxy list`**（`ProxyCommands.java:40-76`，`@EventBusSubscriber` 自动注册，双端可用）：
  - **权限等级 0**（`source.hasPermission(0)`，单人测试存档无需作弊权限即可使用）。
  - 输出摘要行 `[proxy] instances=N live=L mounted=M`，随后最多打印 64 条（`MAX_LINES = 64`）`ProxyInstance.describe()`（`ProxyInstance.java:410-420`）：
    ```text
    <identity|<unresolved>> side=client|server|? element=<class> stage=NEW|LIVE|FAILED|RELEASED machine=<id> model=<loc> [parts=M/T] mounted=<bool> lastTick=never|<gameTime>
    ```
  - 排查含义：`stage=NEW` 表示宿主尚未 tick、`FAILED` 表示 `configure` 返回 `null` 或抛异常、`LIVE` 且 `mounted=false` 指向客户端资源发布/模型加载失败、`lastTick=never` 表示未进入 tick 驱动。
- **JVM 追踪开关 `-Dkasuga.proxy.debug=true`**（`ProxyDebug.java:20-49`）：`[proxy]` 追踪日志默认写到 DEBUG（仅进 `logs/debug.log`）；开启 `ProxyDebug.PROPERTY` 后提升至 INFO 实时输出到控制台与 `latest.log`。

> 数据驱动专题其它页：[注册期 API](api-registration.md) · [扩展 API](api-extension.md) · [诊断 API](api-diagnostics.md) · [重载 API](api-reload.md) · [Schema 规范](schema.md) · [方块状态属性](block-state-properties.md) · [专题索引](README.md)
