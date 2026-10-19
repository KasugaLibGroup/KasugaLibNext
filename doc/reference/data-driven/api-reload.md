# 数据驱动重载 API（含可断言的纯函数面与调用示例）

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

> 数据驱动专题其它页：[注册期 API](api-registration.md) · [扩展 API](api-extension.md) · [诊断 API](api-diagnostics.md) · [重载 API](api-reload.md) · [专题索引](README.md)
