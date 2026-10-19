# 数据驱动诊断 API（Diagnostics、命令与错误分类）

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


> 数据驱动专题其它页：[注册期 API](api-registration.md) · [扩展 API](api-extension.md) · [诊断 API](api-diagnostics.md) · [重载 API](api-reload.md) · [专题索引](README.md)
