# Scripting 与 Javet 运行时参考

`:modules:scripting`（包前缀 `lib.kasuga.scripting`）提供引擎无关的脚本运行时、包发现、`package.toml` 解析、模块注册、`@Api` 安全拦截、定时器以及动画状态机（FSM）脚本桥接。`:external:slp:javet`（包前缀 `lib.kasuga.slp.javet`，独立 NeoForge mod ID `kasuga_lib_javet`）提供基于 Javet / V8 5.0.1 的 JavaScript 引擎实现与原生库自动下载器。

> 面向统一资源平台的 `ScriptBlockRegistry`、`ScriptSubmissionRing`、`ManagedScriptEntryPoint` 与 `ScriptTicket` 异步调用契约另见 [统一资源平台与 Script Ticket](resource-platform.md#脚本块与-ticket)。

## 模块与依赖坐标

| 模块 | Gradle 项目 | Mod ID / 类型 | 职责 |
| --- | --- | --- | --- |
| 脚本核心运行时 | `:modules:scripting` | 并入 `kasuga_lib`（`useModProject`） | 引擎抽象、包扫描、线程模型、模块注册表、Timer、`@Api` 安全特征与 FSM API |
| Javet V8 后端 | `:external:slp:javet` | `kasuga_lib_javet`（要求 `kasuga_lib`，`AFTER`） | `JavetScriptEngine`、`FastJavetClassConverter`、`JsModuleResolver`、`JavetDownloader` |

`modules/library/build.gradle` 已通过 `useModProject(project(':modules:scripting'))` 将 `:modules:scripting` 打包进主发行包；`:external:slp:javet` 作为可选配套 mod 独立运行（见 [库打包与多版本结构](library-packaging.md)）。

## 引擎抽象与注册表

### ScriptEngine 与 ScriptEngineType

`lib.kasuga.scripting.ScriptEngine` 定义单实例脚本引擎生命周期：

| 方法 | 说明 |
| --- | --- |
| `usePackageRegistry(PackageRegistry packages)` | 在 `init` 前注入作用域隔离的包注册表；默认抛出 `UnsupportedOperationException` |
| `safetyCapabilities()` | 返回 `ScriptSafetyCapabilities`，默认 `ScriptSafetyCapabilities.UNVERIFIED` |
| `init(ScriptConsole console)` | 初始化引擎实例、控制台输出与内置模块 |
| `setFeatures(Map<EngineFeatureType<?>, EngineFeature>)` / `getFeature(type)` | 绑定和读取引擎特征（如 `SecurityEngineFeature`） |
| `createValue(Object object)` | 将 Java 对象包装为 `ScriptValue` |
| `loadModule(ResolvedScript script)` / `getLoadedModule(String sourcePath)` | 按源路径加载并缓存脚本模块，返回 `ScriptModuleHandle` |
| `executeEntry(String entryName, InputStream source[, ResolvedPackage owner])` | 执行入口脚本；传入 `owner` 可让入口首帧相对 `require` 限定在所属包内 |
| `tick()` / `close()` | 每游戏 tick 推进一次引擎与已加载 `ScriptModule`，或在关闭时释放运行时 |
| `registerGlobal(String name, Object api)` | 注册引擎全局对象（如 `Animator`、`AnimatorBuilder`、`ScriptBlocks`） |

`lib.kasuga.scripting.ScriptEngineType<T>` 封装某种引擎的构造工厂与元数据，通过 `ScriptEngineType.builder(Supplier<T>)` 构造：

| Builder 方法 | 字段 / 默认值 | 说明 |
| --- | --- | --- |
| `scriptType(String)` | `scriptType` | 主语言类型标识（如 `"javascript"`） |
| `engineSupplier(Supplier<T>)` | `engineSupplier` | 引擎实例工厂 |
| `resolver(ModuleResolver)` | `resolver` | 相对路径定位器（`locateScript` / `getExtension`） |
| `multiThreadSupporting(boolean)` | `false` | 是否支持多线程元数据声明 |
| `priority(int)` | `0` | 注册表同语言解析优先级（数值越大越优先） |
| `addFeature(EngineFeatureType<?>)` | `featureTypes` | 每次 `create(...)` 时通过 `featureType.build()` 生成独立特征实例 |
| `addGlobalApi(String, Function<ScriptEngine, Object>)` | `globalApis` | 传入已初始化的 `ScriptEngine` 创建全局对象 |
| `addGlobalApi(String, Supplier<Object>)` | `globalApis` | 无引擎反向引用时的简写重载 |

`ScriptEngineType.create(ScriptConsole console[, PackageRegistry packages])` 的初始化顺序为：实例化引擎 -> 若传入 `packages` 则调用 `usePackageRegistry` -> 构建并 `setFeatures` -> `init(console)` -> 按顺序调用 `registerGlobal`。其中任意步骤抛出 `ScriptException` 或 `RuntimeException` 时，会调用 `engine.close()` 并将其作为 suppressed exception 附着在原异常上重新抛出。`isAvailable()` 仅当 `loadingIssues` 为空时返回 `true`。

### ScriptEngineRegistry

`lib.kasuga.scripting.ScriptEngineRegistry`（`@Singleton`）按 `ResourceLocation` 和语言类型名索引可用引擎：

- `register(ResourceLocation location, List<String> types, ScriptEngineType<?> engineType, int priority)`：写入精确 `location` 映射，并为 `types` 中每个类型名将 `(location, priority)` 加入优先级队列（按 `priority` 降序排列）。
- `resolve(String requiredEngine, boolean allowLoadingIssueWhenNoEnginePresent)`：
  1. 若 `requiredEngine` 可解析为合法 `ResourceLocation` 且已注册，直接命中该引擎；当 `allowLoadingIssueWhenNoEnginePresent == false` 且 `loadingIssues` 非空时返回 `null`。
  2. 否则将其视为语言类型名，按 `priority` 降序遍历队列，返回首个 `loadingIssues` 为空的引擎。
  3. 若全部候选引擎均有 `loadingIssues` 且 `allowLoadingIssueWhenNoEnginePresent == true`，返回队列中首个带错误的引擎供诊断界面展示；否则返回 `null`。

### 传统 pack 运行时与资源平台入口

仓库同时存在两条包驱动链路：

- **传统 pack 运行时（`ScriptingSystem` + `ScriptRuntime`）**：`@Context` 单例 `ScriptingApplication` 将 `ScriptingSystem` 注册到 `ResourceSystem`。每个 `ScopedResourceManager`（客户端 `server == null` 或服务端 `MinecraftServer`）对应一个 `ScriptRuntime`。`onReloaded(...)` 串行排队关闭旧 `ScriptThreadGroup`（最多观测 5 秒超时日志，不阻塞游戏线程），并在后台线程扫描 `ScopedPackResources`、构建包树并启动 `ScriptThreadGroup`。`ServerTickEvent.Pre` 与 `ClientTickEvent.Pre` 分别触发对应 `ScriptRuntime.tick()`。
- **受管资源平台入口（`ManagedScriptEntryPoint`）**：ID 为 `"kasuga:script_blocks"`，支持 `RESOURCE_RELOAD`、`CLIENT_STARTUP`、`SERVER_STARTUP` 事件与 `CLIENT`、`SERVER`、`NONE` 端侧。每代创建独立的 `ScriptBlockRegistry`、`PackageRegistry` 与 `ScriptThreadGroup`，通过 `ScriptPackage.startManaged(...)` 启动包并在准备完成后暴露异步 `ScriptBlockHandle` 与 `ScriptTicket`（详见 [统一资源平台与 Script Ticket](resource-platform.md#脚本块与-ticket)）。

### 端侧报错与缺失引擎处理

- **客户端（`ScriptingClientApplication`，`@BeanOnlyIn.Client`）**：监听 `ClientResourceReloadFinishEvent`。当 `PackageSystem.getMissingEngines()` 非空时切换至 `ScriptEngineMissingScreen`（提供打开 `mods` 目录、浏览器跳转 `https://ecosystem.kycraft.cn/search?keyword=...&platform=...` 下载、危险跳过二次确认、退出游戏按钮）；当 `PackageSystem.getEngineErrors()` 非空时切换至 `ScriptEngineErrorScreen` 展示引擎异常详情与日志入口。
- **专用服务端（`ScriptingServerApplication`，`@BeanOnlyIn.DedicatedServer`）**：实现 `Configurable`，在服务端配置中注册布尔项 `ignore_scripting_errors`（默认 `false`）。监听 `ServerResourceReloadFinishEvent`：缺失引擎时在 `ignore_scripting_errors == true` 下打 `WARN`，否则抛出 `RuntimeException`；存在 `engineErrors` 时始终记录错误并抛出 `RuntimeException`。

## 包发现、`package.toml` 与线程模型

### `package.toml` Schema

`PackageSystem.readPackageInfo(ScopedPackResources spr, String packRelativeRoot)` 使用 NightConfig `TomlParser` 以 UTF-8 读取 `<packRelativeRoot>/package.toml`，反序列化为 `PackageInfo`：

| 键 | Java 类型 | 必填 | 说明 |
| --- | --- | --- | --- |
| `name` | `String`（可为 `null`） | 建议填写 | 包唯一名称（如 `"@test/scripting-test"`）；注册进 `PackageRegistry` 时同名冲突会抛出 `DuplicatePackageException` 并记入 `loadingErrors` |
| `engine` | `String` | 运行包必填 | 引擎语言类型（如 `"javascript"`）或 `ResourceLocation` 字符串（如 `"kasuga_lib_javet:javet"`） |
| `description` | `String` | 否 | 描述文本 |
| `version` | `String` | 否 | 版本号 |
| `main` | `String` | 否 | `require("<packageName>")` 时的包主入口（无扩展名逻辑路径，如 `"index"`） |
| `workspaces` | `String` 或 `List<String>` | 否 | 子包目录通配声明（如 `["packages/*"]` 或 `["./packages/*"]`）；仅当 `ScopedPackResources` 实现 `HierarchicalScopedPackResources` 时扫描，去除末尾 `/*` 后遍历子目录中的 `package.toml` |
| `entry.common` | `String` 或 `List<String>` | 否 | 客户端与服务端均执行的入口脚本 |
| `entry.server` | `String` 或 `List<String>` | 否 | 仅服务端执行的入口脚本 |
| `entry.client` | `String` 或 `List<String>` | 否 | 仅客户端执行的入口脚本 |

示例（取自 `external/slp/javet/src/main/resources/scripts/package.toml` 与 `packages/scripting-test/package.toml`）：

```toml
# scripts/package.toml（工作区根包）
engine = "javascript"
workspaces = ["packages/*"]
```

```toml
# scripts/packages/scripting-test/package.toml
name = "@test/scripting-test"
engine = "javascript"
version = "1.0.0"
main = "index"

[entry]
client = ["index"]
```

### 包发现与端侧入口过滤

`PackageSystem.scan(ScopedPackResources)` 首先检查 `scripts/package.toml`，存在且引擎解析成功时将其加入结果，再按根包的 `workspaces` 列表扫描子包目录并注册进 `PackageRegistry`。没有 `package.toml` 的包会被跳过。`PackageSystem` 通过 `loadingErrors`、`engineErrors` 与 `engineMissing` 聚合扫描失败与缺失/故障引擎。

入口脚本过滤规则在 `ScriptPackage.startInternal(...)` 中区分两种模式：

- **传统模式（`ScriptPackage.start(ScriptThreadGroup)`）**：按物理运行端 `FMLEnvironment.dist` 过滤。`Dist.DEDICATED_SERVER` 执行 `entry.common + entry.server`；`Dist.CLIENT` 执行 `entry.common + entry.client`。因此在集成服务端中也需要运行在物理客户端上的脚本，传统模式下需放入 `entry.common`（或使用下方的逻辑端受管模式）。
- **受管模式（`ScriptPackage.startManaged(..., LogicalSide side, ...)`）**：按 `LogicalSide`（禁止传入 `LogicalSide.BOTH`）过滤：`LogicalSide.SERVER` 执行 `common + server`，`LogicalSide.CLIENT` 执行 `common + client`；同时绑定 `ScriptBlockRegistry.PackageSession`（环容量 `1024`），并注入全局 `ScriptBlocks` 与可选 `Resources`（`ScriptVfsFacade`）。

### ScriptThreadGroup 与 ScriptThread

每个包在父线程组下创建子 `ScriptThreadGroup(groupName, parent)` 和独占工作线程 `ScriptThread`（线程名 `Script-<packageName>-<entryName>`）。

`ScriptThread` 的执行模型与有界契约：

- 所有针对引擎的初始化、入口执行、任务投递和释放都在该独占 `ScriptThread` 上完成（`ScriptThread.owns(ScriptEngine)`）。
- `recordCall(Runnable)` 队列上限为 **4096**；队列满或线程正在关闭时抛出 `RejectedExecutionException`。
- 主循环每次唤醒依次执行：（1）消费一条 `pendingTasks`；（2）遍历所有 `ScriptSubmissionRing` 调用 `ring.drain(64, 2_000_000)`（每次最多 64 个请求或 2 ms 预算）；（3）若收到 tick 标记则调用 `engine.tick()`。空闲时在 `wait()` 上挂起。
- 关闭时（`shutdown()`）先关闭所有 `submissionRings`，打断线程，在退出前执行所有通过 `onTermination(Runnable)` 注册的清理动作（如 unpin `ScriptFunction`），最后执行 `engine.close()` 并完成 `shutdownFuture`。

## 模块解析、内置模块与定时器

### ModuleResolver、BuiltinModuleRegistry 与 PackageRegistry

- `lib.kasuga.scripting.module.ModuleResolver`：定义 `locateScript(ResolvedPackage pkg, List<String> relativeSegments)` 与 `getExtension()`，将包内逻辑段定位为 `ResolvedScript(filePath, pack, ModuleId, owner)`。
- `lib.kasuga.scripting.module.BuiltinModuleRegistry`（`@Context`）：
  - `register(String moduleName, Supplier<Object> supplier)`：注册共享全局模块或无状态对象供应商。
  - `registerFactory(ScriptModuleFactory<M> factory)`：注册每引擎独立实例化的 `ScriptModule`（实现 `init()`、`tick()`、`close()`）；每个引擎初始化时调用 `createModules(ScriptEngine)` 并随引擎 `tick()` / `close()` 自动驱动。
- `lib.kasuga.scripting.module.PackageRegistry`（`@Context`）：线程安全存储已解析包（`register` / `lookup` / `all` / `clear`）；包名重复抛出 `DuplicatePackageException`。受管入口在 `ScriptBlockRegistry.modulePackages()` 中持有每代独立的 `PackageRegistry`，不与全局实例共享状态。

### TimerModule 与 KasugaTimer

`lib.kasuga.scripting.timer.TimerModule` 实现 `ScriptModule`，工厂常量 `TimerModule.FACTORY` 的模块名为 `"kasuga:timer"`（由 `KasugaLibJavet` 在启动时注册到 `BuiltinModuleRegistry`）。底层使用基于 `PriorityQueue` 的 tick 调度器 `KasugaTimer`：

| `@Api` 方法 | 签名 | 行为与单位 |
| --- | --- | --- |
| `setInterval` | `int setInterval(ScriptValue callback, int ms)` | 要求 `callback` 为 `ScriptFunction`（否则返回 `-1`）；克隆并 pin 引用，每 `Math.max(1, ms / 50)` 个 tick 重复触发（按 50 ms/tick 换算） |
| `setTimeout` | `int setTimeout(ScriptValue callback, int ms)` | 单次延迟 `Math.max(1, ms / 50)` 个 tick 后触发一次 |
| `clearInterval` | `void clearInterval(@Nullable Integer id)` | 从 `KasugaTimer` 注销并释放对应的 pinned `ScriptFunction` |
| `clearTimeout` | `void clearTimeout(@Nullable Integer id)` | 委托给 `clearInterval(id)` |

`TimerModule.close()` 会清空待执行队列并对所有未清理回调执行 `removePin()` 与 `close()`。

## 脚本 FSM API（`lib.kasuga.scripting.fsm`）

`FsmApiRegistration.install(ScriptEngineType.Builder)` 将 `AnimatorApi`（全局名 `"Animator"`）和 `AnimatorBuilderApi`（全局名 `"AnimatorBuilder"`）挂载到脚本引擎，并与引擎实例绑定以支持回调包装。脚本与 JSON schema 本身参考 [状态机与动画剪辑 JSON 参考](fsm.md)。

### 两层错误语义与自动 Tick

- **`AnimatorBuilderApi`（构建与注册期，快速失败）**：非法 JSON/定义、未知状态机 ID 或缺失引擎引用时直接抛出 `IllegalArgumentException`。
- **`AnimatorApi`（运行时控制面，不抛异常跨边界）**：传入无效句柄或未注册变量 ID 时按 `"category:key"` 在容量为 256 的 LRU 中记录一次 `WARN` 后静默返回默认值（`null`、`false`、`0`、`0.0`、`""`、`-1` 或空数组）。
- **`FsmAutoTickModule`（`FACTORY.name() == "kasuga:fsm_autotick"`）**：每引擎单例 `ScriptModule`，在所属 `ScriptThread` 的每次 `engine.tick()` 上按 tick 推进已注册句柄并驱动 `onTick` / `onStateChanged` 回调，满足 FSM 的单线程 tick 约束。

### AnimatorBuilderApi 方法清单

| 方法 | 说明 |
| --- | --- |
| `String[] varTypes()` | 返回当前 `StateVarType.all()` 的类型 token 数组（内置 `"bool"`、`"int"`、`"float"`、`"string"`、`"vec3"`；若宿主注册额外 `StateVarType` 如 `"resource"` 则一并包含） |
| `String registerStateVar(String namespace, String path, String type[, Object defaultValue[, boolean ephemeral]])` | 注册类型化状态变量（`ephemeral = true` 表示每 tick 末清除的 trigger）；未知类型返回 `""`，已存在且兼容时幂等返回 `"namespace:path"` |
| `void registerCondition(String namespace, String path, ScriptFunction guard)` | 注册 JS 守卫回调；内部克隆并 pin 函数，调用时传入 `ScriptFsmContext`，按 `ScriptValues.isTrue(...)` 判定；抛出异常时记录 `WARN` 并降级返回 `false`（另有 Java 宿主 `Predicate<StateContext<?>>` 重载） |
| `void registerAction(String namespace, String path, ScriptFunction action)` | 注册 JS 动作回调（另有 Java 宿主 `Consumer<StateContext<?>>` 重载） |
| `String registerDefinition(ScriptValue definition)` / `(String json)` | 直接接受 JS 对象字面量或 JSON 字符串，经 `StateMachineDefinition.CODEC` 解码并写入 `FsmDefinitions`，返回定义 ID |
| `int instantiate(String id, Object owner[, Object model])` | 实例化运行期 `StateMachine` 并返回 `int` 句柄（避免 JS `BigInt`）；若 `model` 为 `ModelInstance` 则绑定 `ModelInstancePoseSink`，其他非 `null` 对象打 `WARN` 并以无 sink 模式运行 |

### AnimatorApi 方法清单

| 类别 | 方法 | 返回默认值（句柄/层无效时） | 说明 |
| --- | --- | --- | --- |
| 状态变量 | `get(int handle, String varId)` / `has(int handle, String varId)` / `set(int handle, String varId, Object value)` | `null` / `false` / 无操作 | `varId` 支持完整 `"namespace:path"` 或无歧义短名（不含 `:` 和 `/` 且末尾匹配 `/<shortName>`） |
| 状态变量发现 | `listVars(int handle)` / `declaredVars(int handle)` / `varType(String varId)` | `[]` / `[]` / `""` | `listVars` 仅列显式赋过值的变量；`declaredVars` 列出定义声明的全部变量 |
| 结构读取 | `getState(int handle, String layerId)` / `getStateElapsed(...)` / `getStateDuration(...)` | `""` / `0` / `-1` | 读取指定层当前状态 ID、已运行 tick 数、状态配置时长 |
| 层属性 | `getLayerMode(int handle, String layerId)` / `getLayerWeight(...)` / `isLayerLocked(...)` | `""` / `0.0` / `false` | 返回混合模式序列化名（`"base"` / `"additive"` / `"override"`）、权重与锁定标记 |
| 时钟与版本 | `getTick(int handle)` / `getVersion(int handle)` | `0.0` / `0` | `getTick` 返回 `double` 以在 JS 中保持普通 `Number` |
| 触发与跳转 | `trigger(int handle, String triggerVarId)` / `isTriggered(...)` / `goTo(int handle, String layerId, String stateId)` | 无操作 / `false` / 无操作 | `trigger` 仅接受布尔变量；`goTo` 在下一次 tick 时切换并按序执行退出/进入动作 |
| 推进与释放 | `tick(int handle)` / `tick(int handle, double dt)` / `dispose(int handle)` | 无操作 | `dispose` 同时移出自动 tick 并释放句柄 |
| 自动 Tick | `autoTick(int handle, boolean on)` / `onTick(int handle, ScriptFunction)` / `onStateChanged(int handle, ScriptFunction)` | 无操作 | `onTick` 每次推进后调用；`onStateChanged` 仅在 `machine.version()` 变化时调用 |

### ScriptFsmContext 与 StateValueCoercer

JS 守卫和动作回调收到的 `ctx` 为 `ScriptFsmContext`，仅暴露带 `@Api` 的方法：

- `owner()`：返回传给 `instantiate` 的原始宿主对象（JS 对象保留同一 V8 对象引用，脚本侧对 `ctx.owner()` 的修改会双向同步）。
- `isClientSide()`、`stateElapsedTicks()`、`stateDurationTicks()`、`isLayerLocked()`。
- `get(String varId)`、`has(String varId)`、`set(String varId, Object value)`、`trigger(String triggerId)`、`isTriggered(String triggerId)`（此处 `varId` / `triggerId` 通过 `StateVarRegistry.resolve(...)` 解析完整 ID）。

`StateValueCoercer` 负责 JS（`Integer`/`Double`/`Boolean`/`String`/数组/对象）与 `StateVarType` 之间的类型转换：

- `bool`：仅接受 `Boolean`。
- `int`：接受 `Integer` 或有限整数 `Double`（`d == Math.floor(d)`）。
- `float`：接受 `Float`、`Double`、`Integer` 并转为 `float`。
- `string`：仅接受 `String`。
- `vec3`：接受 `Vector3f`、长度为 3 的 `List` 或含 `x`/`y`/`z` 键的 `Map`；回传给 JS 时装箱为 3 元素 `List<Float>`。
- `ResourceLocation`：接受 `ResourceLocation` 或合法 `ResourceLocation.tryParse(s)` 字符串；回传给 JS 时装箱为字符串。

## `@Api` 导出与安全模型

`lib.kasuga.scripting.security` 提供细粒度的成员级访问控制注解与运行时检查机制：

- **`@Api`**（`RetentionPolicy.RUNTIME`）：
  - `boolean export() default true`：为 `false` 时不向脚本暴露（`typeof obj.hidden === "undefined"`）。
  - `Parameters[] parameters() default {}`：附加键值对元数据（`@Api.Parameters(name = "...", value = "...")`），透传给条件与回调。
  - `Class<? extends SecurityCheckCondition>[] conditions() default {}`：声明调用前必须全部通过的检查条件类。
- **`SecurityEngineFeature` 与 `SecurityEngineFeatureType.INSTANCE`**：
  - 每个引擎实例通过 `SecurityEngineFeatureType.INSTANCE` 获取独立 `SecurityEngineFeature`。
  - `getConditionRegistry()`（`SecurityConditionRegistry`）按条件 `Class` 注册实例（内置无条件放行实现 `AlwaysAllow`）；若 `@Api(conditions = ...)` 中引用了未注册的条件类，`check(engine, api)` **默认拒绝**（fail-closed，返回 `false`）。
  - 全部条件类返回 `true` 后，若通过 `setSecurityCheckCallback(SecurityCheckCallback)` 注册了兜底回调，再执行该回调校验；最终失败时 `ClassAccessor` 抛出 `RuntimeException("Security check denied: " + name)`。

> 注意：`@Api` 控制已暴露 Java 对象上单个方法/字段的访问；而包级不可信代码隔离由 `ScriptSafetyCapabilities` 门控。当前 `JavetScriptEngine` 继承默认 `ScriptSafetyCapabilities.UNVERIFIED`（六项隔离标志全为 `false`），因此 `ManagedScriptEntryPoint` / `ScriptBlockRegistry` 默认拒绝非可信来源的包（见 [统一资源平台与 Script Ticket](resource-platform.md#脚本块与-ticket)）。

## Javet 引擎后端（`:external:slp:javet`）

### KasugaLibJavet 与 JavetDownloader

`KasugaLibJavet`（`@Mod("kasuga_lib_javet")`）在构造期完成：

1. 将 `JavetDownloader` 设为 `JavetLibLoader` 的全局加载监听器（`JavetLibLoader.setLibLoadingListener`）。
2. 加载原生 V8 库（`V8Host.getV8Instance().loadLibrary()`）并执行 `"1 + 1"` 烟雾校验，异常记录到 `ENGINE_RUNTIME_ISSUES`。
3. 在 `KasugaLibStartupEvent`（且原生库加载完成后）向 `BuiltinModuleRegistry` 注册 `TimerModule.FACTORY` 与 `FsmAutoTickModule.FACTORY`，并构建 `KasugaLibJavet.ENGINE_TYPE`（语言 `"javascript"`、`JsModuleResolver`、`SecurityEngineFeatureType.INSTANCE`、`FsmApiRegistration.install`、全局测试 API `ScriptingTestApi`）注册至 `ScriptEngineRegistry`（ID `kasuga_lib_javet:javet`，优先级 `0`）。

`JavetDownloader` 在未检测到本地库时从预设镜像按顺序下载 `javet-{runtime}-{os}-{arch}{i18n}-{version}.jar` 中的原生动态库至 `<gamePath>/native-libraries/javet/`，最多重试 3 次并通过 `ModLoadingManager` / `ModLoadingProgress` 汇报进度：

1. `Alibaba Cloud Mirror`：`https://maven.aliyun.com/repository/public/com/caoccao/javet/javet-{0}-{1}-{2}{3}/{4}/javet-{0}-{1}-{2}{3}-{4}.jar`
2. `Maven Repository`：`https://repo1.maven.org/maven2/com/caoccao/javet/com/caoccao/javet/javet-{0}-{1}-{2}{3}/{4}/javet-{0}-{1}-{2}{3}-{4}.jar`

不支持 Android；缺少匹配 OS/架构时提示使用 GraalJS 模式。`external/slp/javet/build.gradle` 显式引入 `org.apache.httpcomponents:httpclient:4.5.13` 以确保即使本地已缓存原生库也能正常加载 `JavetDownloader` 类。

### JavetScriptEngine 与 FastJavetClassConverter

`JavetScriptEngine` 为每个线程/包创建一个独立的 `V8Runtime`：

- 绑定 `KasugaJavetConsoleInterceptor` 将 `console.{log,info,warn,error,debug,trace}` 转发至 `ScriptConsole`。
- 监听 `setPromiseRejectCallback`，未捕获 Promise rejection（`event.getCode() == 0`）记录到 `console.error`。
- 每 21 次 `tick()`（`gcTicks++ > 20`）调用一次 `runtime.lowMemoryNotification()` 触发 V8 GC。
- 以 CommonJS 形式编译模块与入口函数 `function(require, exports, module)`；加载模块时先写入 `loadedModules.put(sourcePath, null)` 占位以支持循环 `require`。

`FastJavetClassConverter`（继承 `JavetObjectConverter`）与 `ClassAccessor` / `NativeProxyAccessor` / `ScriptConverter` 负责 Java <-> V8 互操作：

- **Java -> V8（`toV8Value`）**：
  - `JavetValue`、`ScriptValue`（交由 `ScriptConverter`）、`V8Value` 直接转换或克隆；基本类型、包装类型、原生数组与 `BigInteger` 走父类原生转换。
  - 其他 Java 对象由 `ClassAccessor.collect(...)` 缓存原型，仅导出带 `@Api(export = true)` 的 `public` 方法与字段（以及 `@FunctionalInterface` 的抽象方法作为可调用 V8 函数）。每个封装对象写入私有属性 `"KasugaLib#Address"`（`System.identityHashCode`），在 `cachedObjects` 中保存 `WeakReference` 并调用 `setWeak()` 配合 V8 GC 回收。
- **V8 -> Java（`toObject`）**：
  - 含 `"KasugaLib#Address"` 私有属性的 `V8ValueObject` 还原为原 Java 实例。
  - 普通 JS 对象通过 `JavetValueBridge.wrap(object.toClone())` 包装为强引用的 `JavetValueObject`（`ScriptValue`），保留跨边界对象身份一致性；数组经 `JavetValueBridge` 优先于普通对象匹配为 `JavetValueArray`，函数匹配为 `JavetValueFunction`，原始值匹配为 `JavetValuePrimitive`。
- **重载与快速路径（`ClassAccessor` + `MethodOverrideMap`）**：
  - 按参数个数分桶并维护 `BitSet converterMask`；凡声明为 `ScriptValue`（及子接口 `ScriptFunction`、`ScriptObject`、`ScriptArray`、`ScriptPrimitive`）的形参会跳过 `toObject`、直接通过 `JavetValueBridge.wrap` 传入包装句柄，使 JS 箭头函数可准确匹配 `ScriptFunction` 重载（如 `AnimatorBuilder.registerCondition`、`TimerModule.setInterval`）。

### JsModuleResolver 与 PackageAwareRequireResolver

`JavetScriptEngine` 内置 `require(moduleName)` 的三级解析顺序：

1. **引擎内 `ScriptModule`**：匹配 `scriptModules`（如 `require("kasuga:timer").default`）。
2. **全局无状态内置模块**：查询 `BuiltinModuleRegistry.resolve(moduleName)`，包装为带 `"default"` 导出的 `JavetModuleHandle`。
3. **`PackageAwareRequireResolver`**：
   - **按包名导入**（如 `require("@my/pkg")`）：查找 `PackageRegistry.lookup(moduleName)`（优先作用域 `scopedPackages`，否则全局 bean），解析包 `main` 入口。
   - **包内相对路径导入**（`./...` 或 `../...`）：根据调用方 `fromSourcePath` 的目录归一化路径段；若 `..` 试图越过包根直接返回 `null`（禁止路径逃逸）。优先在 `engine.getOwningPackage(fromSourcePath)` 所属包内查找，未命中时回退遍历 `packageRegistry.all()`（由于 `PackageRegistry` 内部使用 `HashMap` 并返回 `Map.copyOf(packages)`，遍历顺序不作保证）。

`JsModuleResolver.locateScript(...)` 会再次对路径段执行分割与越界检查（拒绝 `..` 上越包根），并依次尝试 `<basePath>.js` 与 `<basePath>/index.js`。

## 验证入口

```sh
# 纯 JVM 运行 FSM 脚本 API 单元测试（无需启动 NeoForge 服务端）
./gradlew :modules:scripting:scriptUnitTest

# 完整脚本模块测试（包含 EphemeralTestServerProvider 包发现与运行时测试）
./gradlew :modules:scripting:test

# Javet V8 引擎测试（包括安全策略、类型转换、FSM 组装与 Script Ticket 集成）
./gradlew :external:slp:javet:test
./gradlew :external:slp:javet:test --tests '*ResourceScriptTicketIntegrationTest'
```
