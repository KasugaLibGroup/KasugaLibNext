# 数据驱动重载 API（含可断言的纯函数面与调用示例）

## 13. 重载 API

重载域由 data-driven 的 `ReloadOrchestrator` 编排，扩展点是 `ReloadHandler`（对称于注册期的 `TypeHandler`）。编排器不硬编码任何类型名——它遍历 `ReloadHandlerRegistry`，把每个 handler 的 `typeName()` 当作合法顶层键。

### 13.1 ReloadHandler（扩展点）

`reload/ReloadHandler.java:41`。一个 handler 管一种 reload 类型的 decode / register / 收尾校验。

```java
public interface ReloadHandler<T> {
    String typeName();                                              // :50 顶层键，兼作去重碰撞空间
    default String describeFile() { return "reload-domain file"; }  // :60 decode 诊断里的名词
    default Set<String> globDirectories() { return Set.of(); }      // :72 默认空 = 仅索引
    void clearBucket();                                             // :85 每周期一次，register 前
    Decoded<T> decode(JsonObject root);                             // :97 纯解码，不读文件
    void register(T payload);                                       // :107 last-wins 胜者注册
    default void afterReload(List<Reloaded<T>> registered) {}       // :117 跨类型校验
}
```

| 方法 | 职责与约束 | 失败行为 |
|------|-----------|---------|
| `typeName()` | 认领的顶层 JSON 键（如 `"state_machines"`）；注册表唯一键兼去重碰撞空间 | 同名后注册者原地替换先注册者 |
| `describeFile()` | 用于 decode 诊断的名词，如 `"state machine file"` | 默认 `"reload-domain file"` |
| `globDirectories()` | `data/<ns>/` 下按目录 glob 发现的目录；默认空 = 仅索引 | 返回有序集合以保发现顺序 |
| `clearBucket()` | 每周期一次，发现之前；应只清 reload 来源保留 SCRIPT | 抛异常被 `runClear`（`:250-258`）就地隔离并记入 `Domain.RELOAD_DATA` |
| `decode(root)` | `root` 已保证为 JSON 对象；不得读文件、不得因畸形内容抛异常 | 抛异常被编排器按文件隔离并记账 |
| `register(payload)` | 只对 last-wins 胜者调用，且在整轮发现完成之后 | 应抛异常让编排器隔离并归属命名空间 |
| `afterReload(registered)` | 所有 handler 完成 `register` 后，仅对本轮注册条目调用 | 抛异常归属库 mod（`KasugaLib.MODID`），不阻断后续 handler |

### 13.2 ReloadHandlerRegistry

`reload/ReloadHandlerRegistry.java:27`。静态、按 `typeName()` 索引、`LinkedHashMap` 保序。

```java
public static void register(ReloadHandler<?> handler);       // :37
public static ReloadHandler<?> get(String typeName);         // :45
public static Collection<ReloadHandler<?>> all();            // :50 不可变视图，保注册顺序
public static void clearForTest();                           // :58 仅测试，生产不调
```

- **顺序有承载力**：编排器按 `all()` 顺序执行 dispatch 与 register。
- **按 typeName 幂等替换**：重复注册同一 `typeName` 原地替换并保留原顺序。

### 13.3 Decoded / Entry

`reload/Decoded.java:19`：

```java
public record Decoded<T>(List<Entry<T>> entries, List<String> errors) {   // :19
    public record Entry<T>(String id, T payload) {}                        // :28
    public static <T> Decoded<T> empty();                                  // :36
}
```

- `Entry.id` 是条目在 handler 类型内的有效 id（去重判定键）；整个文件被拒时 `entries` 为空、`errors` 非空。

### 13.4 Reloaded

`reload/Reloaded.java:14`：

```java
public record Reloaded<T>(String modId, T payload) {}
```

携带通过去重并交付 `register` 的 `payload` 及来源命名空间 `modId`，供 `afterReload` 按命名空间归因诊断。

### 13.5 ReloadOrchestrator

`reload/ReloadOrchestrator.java:121`。实现 `ScopedResourceManagerConsumer` 与 `ScopedResourcePackListener`，是重载域唯一的编排器。

```java
public ReloadOrchestrator()                                      // :133 DI 入口，由 init():155 注册到 ResourceSystem
public ReloadOrchestrator(ReloadHandler<?>... handlers)          // :145 登记传入 handler 后运行
public void reload(ResourceManager resourceManager)              // :186
public static String indexResourcePath(String modId)             // :290 -> "kasuga_lib/data_driven"
```

- 顶层键集合由 `knownTypeNames()`（`:528`）动态从 `ReloadHandlerRegistry.all()` 派生；D9 反向提示里的注册类型示例由 `registrationFieldsExample()`（`:543`）从 `TypeHandlerRegistry.all()` 派生。
- `reload(ResourceManager)` 外覆最后兜底 catch（`:188-193`），逃逸异常归入 `KasugaLib.MODID`。
- 周期（`runCycle`，`:198`）执行顺序：
  1. 清空 `Diagnostics.clear(Domain.RELOAD_DATA)`（`:208`）并按注册表构建 `HandlerCycle`（`:633`）；
  2. 逐 handler 调 `runClear` → `clearBucket()`（`:250-258`）；
  3. 聚合所有 `handler.globDirectories()`；
  4. 逐命名空间发现（`collectNamespace`，`:266`）：`readReloadPaths`（`:307`）读索引清单 `on_reload` → `collectGlob`（`:365`）扫目录 glob（跳过已列路径；受 `ManagedReloadAllowlist` 管控的来源只接受白名单内条目）→ `collectIndex`（`:392`）按显式索引读取并校验受管白名单；
  5. 分派与解码（`dispatch`，`:484`；`readAndDispatch`，`:460`）：未知顶层键报错并附 D9 提示（`:494-501`），命中 handler 则 `decode`；
  6. 去重（`resolveAndReport`，`:602`）并逐 handler 注册（`register`，`:562`，glob 候选在前、索引候选在后，同键 last-wins 覆盖；败者不调用 `handler.register`）；
  7. 收尾校验（`runAfterReload`，`:585`）。

### 13.6 异常守卫

| 层 | 范围 | 出处 |
|----|------|------|
| 每 handler 清桶 | `clearBucket()` 抛异常 | `runClear:250-258` |
| 每文件 | 读取与解码异常，含解码器自身抛异常 | `readAndDispatch:460-476` |
| 每命名空间 | 目录 glob、清单读取抛异常 | `collectNamespace:266-288` |
| 受管白名单越权 | 受管资源包索引越出批准清单 | `collectIndex:430-434` |
| 每条目 | 注册单个条目抛异常 | `register:562-580` |
| 每 handler 收尾 | `afterReload` 抛异常 | `runAfterReload:585-600` |
| 兜底 | 以上都没能归因的异常，归到 `KasugaLib.MODID` | `reload:186-193` |

### 13.7 StateMachineDefinitionLoader

纯文件层解码器（`modules/modelling/.../dynamic/fsm/StateMachineDefinitionLoader.java:36-103`）：

```java
public static final String PATH = "state_machines";                                        // :39
public static final String FIELD_STATE_MACHINES = "state_machines";                        // :42
public record DecodedFile(List<StateMachineDefinition> definitions, List<String> errors) {}// :55
public static DecodedFile decodeFile(JsonElement json);                                    // :67
```

文件形状为 `{"state_machines": [...]}`。非对象根、缺顶层键、值非数组均整文件拒绝；额外顶层键忽略（由编排器判定未知键）；元素非对象或 decode 失败只跳过当前元素。

### 13.8 AnimationClipLoader

纯文件层解码器（`modules/modelling/.../dynamic/fsm/AnimationClipLoader.java:44-135`）：

```java
public static final String FIELD_ANIMATION_CLIPS = "animation_clips";                      // :47
public record DecodedFile(List<AnimationClip> clips, List<String> errors) {}               // :59
public static DecodedFile decodeFile(JsonElement json);                                    // :71
public static void register(FsmAnimationClips clips, AnimationClip clip);                  // :127
```

- **废弃键拦截（`:100-108`）**：`decodeFile` 显式拒绝元素中使用已移除键 `"functions"` 或 `"formula_parts"` 的片段，报错 `"animation_clips[i] uses removed key '<legacyKey>'; replace it with 'formula_transforms'; element skipped"`，仅跳过当前片段不影响兄弟元素。
- **单条隔离与注册**：`AnimationClip.CODEC` 读取元素自身 `id`；`register` 调用 `clips.registerResource` 写入 RESOURCE 半区，保留同 id SCRIPT 优先。动画片段无目录 glob，只能经 `on_reload` 索引到达。

### 13.9 建模侧两个 handler 与注册 bean

位于 `modules/modelling` `lib.kasuga.rendering.models.mc.dynamic.fsm`：

| 类 | `typeName` | 发现方式 | 角色 |
|----|------------|----------|------|
| `FsmReloadHandler`（`:42`） | `state_machines` | glob `state_machines/` + 索引 | `decode` → `StateMachineDefinitionLoader.decodeFile`；`register` 写 `FsmDefinitions` RESOURCE 半区；`afterReload` 检查悬空 clip 引用并写入 `Domain.RELOAD_DATA` |
| `FsmClipsReloadHandler`（`:24`） | `animation_clips` | 仅索引（`globDirectories` 为空集） | `decode` → `AnimationClipLoader.decodeFile`；`register` → `AnimationClipLoader.register` |
| `FsmReloadHandlerRegistrar`（`:23`） | — | — | `@Context` + `@PostConstruct`（`:25-31`），按 **fsm → clips** 顺序登记到 `ReloadHandlerRegistry`，绑定 `FsmRegistries.GLOBAL` |

### 13.10 FsmDefinitions 与 FsmAnimationClips

两个分桶存储均在 `render-core`，按来源隔离 RESOURCE（`clearResource` 可清）与 SCRIPT（重载时存活）：

`FsmAnimationClips`（`FsmAnimationClips.java:30`）：

```java
public enum ClipSource { RESOURCE, SCRIPT }                                              // :35
public record Entry(AnimationSampler<?> sampler, Object data, ClipSource source) {}      // :43
public void register(Id id, AnimationSampler<?> sampler, Object data)                    // :53 (SCRIPT)
public void registerResource(Id id, AnimationSampler<?> sampler, Object data)            // :66 (RESOURCE)
public Entry get(Id id)                                                                  // :87
public boolean remove(Id id)                                                             // :92
public void clearResource()                                                              // :100
public void clearAll()                                                                   // :108
public void clear()                                                                      // :117 (alias of clearResource)
```

`FsmDefinitions`（`FsmDefinitions.java:27`）同构：`register`（`:59`）、`registerResource`（`:71`）、`clearResource`（`:84`）、`clearAll`（`:95`）、`remove`（`:107`）、`get`（`:116`）、`hash`（`:126`）、`addListener`（`:142`）。

### 13.11 ManagedReloadAllowlist（受管资源包白名单）

`reload/ManagedReloadAllowlist.java:22-163` 为服务端下发/批准的资源包提供进程级只读白名单门禁，仅过滤 `ApprovedResourcePacks` 投影的受管来源，不影响传统 mod 与数据包的历史重载行为：

```java
public static synchronized void install(
        String hostModId,
        List<LocatedIndexPath> indexes,
        List<LocatedContentPath> reloadFiles)                                                // :28
public static synchronized void install(
        String hostModId,
        List<SourceDescriptor> sources,
        List<LocatedIndexPath> indexes,
        List<LocatedContentPath> reloadFiles)                                                // :36
public static synchronized void install(
        String hostModId,
        List<SourceDescriptor> sources,
        Map<String, String> projectedSourcePackIds,
        List<LocatedIndexPath> indexes,
        List<LocatedContentPath> reloadFiles)                                                // :45
public static synchronized boolean isManaged(String sourcePackId)                            // :96
public static synchronized boolean allowsIndex(String sourcePackId, ResourceLocation loc)    // :101
public static synchronized boolean allowsContent(String sourcePackId, ResourceLocation loc)  // :109
public static synchronized int priority(String sourcePackId)                                 // :117
public static synchronized void clearForTests()                                              // :126
```

`ReloadOrchestrator` 在以下三处接入 `ManagedReloadAllowlist`：

- `readReloadPaths`（`:317-332`）：若某个索引栈中存在 `ManagedReloadAllowlist.isManaged(sourcePackId(resource))` 匹配的受管来源，仅保留通过 `allowsIndex(sourcePackId, loc)` 的索引并按 `priority(sourcePackId)` 排序（全部未命中则跳过）；
- `collectGlob`（`:375-380`）：过滤 `!ManagedReloadAllowlist.isManaged(sourcePackId(resource))`，使受管来源不参与无索引的目录 glob；
- `collectIndex`（`:419-437`）：对受管来源按 `allowsContent(sourcePackId, loc)` 过滤并按 `priority(sourcePackId)` 排序；若索引指向未获白名单授权的受管路径，则拒绝加载并向 `Domain.RELOAD_DATA` 报告错误。

### 13.12 v2 启动期 `on_reload` 预检

`DataDrivenBuildPipeline`（`DataDrivenBuildPipeline.java:919-1025`，`validateReloadContentFile`）在注册期 `prepare` 阶段对所有 `on_reload` 声明的文件执行**只读预检**：

- 按顶层字段调用 `ReloadHandlerRegistry.get(field)`；未知键按 `UNKNOWN_TYPE` 报告（`STRICT` 为 `ERROR`，`LEGACY` 为 `WARNING`）。
- 对匹配 handler 调用纯解码 `handler.decode(root)`，将返回的所有 `Decoded.errors()` 或异常转为 `Stage.PARSE` / `Code.CONTENT_INVALID` 诊断。
- **零桶副作用**：预检阶段绝不调用 `clearBucket()`、`register(...)` 或 `afterReload(...)`。

---

## 14. 测试可断言的纯函数面

以下 API 无 NeoForge 运行时硬依赖，可在纯 JVM 单测中直接断言契约：

| API | 断言对象 |
|-----|---------|
| `JsonTreeBuilder.indexDirectorySegments` / `validateSourcePath` | 索引目录段拼写与内容路径规则 |
| `JsonTreeBuilder.parseIndexManifest` / `resolveIndexManifests` | 索引 schema D1–D6（聚合与双列 fail-closed） |
| `JsonTreeBuilder.parseContentBody` / `listIndexFiles` / `applyUnchecked` | v1 内容分派、内嵌类型、D9 提示与 apply 异常入桶 |
| `DuplicateIdResolver` / `EffectiveId.of` / `EffectiveId.ofLegacy` | last-wins 冲突判定与 `STRICT` / `LEGACY` 身份归一化表 |
| `Diagnostics` 全维方法 / `DataDiagnosticsCommands.kasugaDataCommand` | 两维隔离、清空、`summarize` 格式与命令树形状 |
| `SnapshotContentSource.open` / `ContentSourceFactory.snapshot` / `LogicalPath` / `SourceDescriptor` | v2 快照封装、路径字面规则、`declaredIndexes` 过滤与关闭后 `STALE` 守卫 |
| `DataDrivenBuildPipeline.prepare` / `build`（配 `SnapshotContentSource`） | v2 多来源发现、严格 JSON 解码、handler 能力校验、跨定义引用、`claims` 冲突与 `BuildResult` 三态 |
| `HandlerCapability` / `TypeHandler.strictValidationImplemented` / `ValidationContext`（含 `find` / `findAllVisible` / `findAll(String)` / `claimAt`） | `OVERLAY` 候选保留、`find` 单可见语义、`findAll(typeDomain)` 全量枚举与严格完备性检查 |
| `RegistryClaimSnapshot.collect` | `Reg` 树 BFS 收集（跳过 `dispatchEnabled == false` 分组） |
| `JsonPropertyParser.validateStrict` / `JsonItemParser.validateStrict` | 严格方块/物品属性校验（类型/范围/保留 `tab` 规则）与扩展 `Predicate` 注册 |
| `ManagedReloadAllowlist` / `ReloadOrchestrator` / `ReloadHandlerRegistry` | 重载周期、受管资源包白名单拦截、按表保序与测试隔离 |
| `StateMachineDefinitionLoader.decodeFile` / `AnimationClipLoader.decodeFile` / `register` | 状态机与动画片段解码（含 `functions`/`formula_parts` 废弃键拒绝）与 script-wins 注册 |
| `ProxyModelBlockFactories.parseModelSettings` / `validateBlockParams` / `ProxyModelSelection` | 代理方块 `params` 解析、分组 part 一致性校验与 `model_by_state` 规则 |
| `ContentStructure.body` / `unknownKeys` / `field` / `joinWithOr` / `describe` | 共享结构判定层 |

---

## 15. 调用示例

以下片段均按当前公开源码签名编写，可直接编译或作为单测骨架参考。

### 15.1 v2 多来源管线 `prepare` + `build`（纯 JVM `SnapshotContentSource`）

```java
import java.util.List;
import lib.kasuga.core.resource.source.InputPolicy;
import lib.kasuga.core.resource.source.ResourceSourceDescriptor;
import lib.kasuga.core.resource.source.ResourceSources;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.registration.data_driven.TypeHandlerRegistry;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.pipeline.BuildOptions;
import lib.kasuga.registration.data_driven.pipeline.BuildResult;
import lib.kasuga.registration.data_driven.pipeline.DataDrivenBuildPipeline;
import lib.kasuga.registration.data_driven.pipeline.PreparedContent;
import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.ContentSourceFactory;

JsonTreeBuilder.ensureHandlersRegistered();

ResourceSourceDescriptor resDesc = new ResourceSourceDescriptor(
        "kuayue", "kuayue_main", "root_0", 0, ResourceSourceDescriptor.Origin.EXTERNAL);

try (var vfs = new InMemoryVfs("kuayue_main")) {
    vfs.writeString(VfsPath.of("data/kuayue/kasuga_lib/data_driven/index.json"),
            "{\"on_register\": [\"blocks/demo.json\"]}");
    vfs.writeString(VfsPath.of("data/kuayue/blocks/demo.json"),
            "{\"blocks\": [{\"id\": \"kuayue:demo_block\", \"type\": \"data_driven_block\"}]}");

    try (var snapshot = ResourceSources.fixed(
                    resDesc,
                    ResourceSources.vfs(resDesc, vfs, false, "", "", InputPolicy.DEFAULT),
                    InputPolicy.DEFAULT).capture();
         ContentSource source = ContentSourceFactory.snapshot(snapshot, resDesc, List.of("kuayue"), "main")) {

        DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                "kuayue",
                TypeHandlerRegistry.all(),
                List.of());
        PreparedContent prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);
        if (!prepared.hasBlockingDiagnostics()) {
            BuildResult result = pipeline.build(prepared);
            boolean ok = result.applied(); // true 仅当 Status.APPLIED
        }
    }
}
```

### 15.2 v2 宿主安装 `DataDrivenRegistrationProviders` 与预检上下文 `ValidationContext.findAll`

```java
import java.util.List;
import lib.kasuga.registration.data_driven.DataDrivenRegistrationProviders;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.LocatedDefinition;
import lib.kasuga.registration.data_driven.validation.ValidationContext;

// 安装宿主接管入口（测试结束或卸载时关闭 Attachment）
try (var attachment = DataDrivenRegistrationProviders.install("kuayue", hostModId -> {
    // 使用 DataDrivenBuildPipeline.prepare + build 构建并返回 JsonRegistryGroup
    return null;
})) {
    // ...
}

// 在自定义 TypeHandler.validate(..., ValidationContext context) 中：
void validateCustomRef(ValidationContext context, String targetBlockId) {
    // 1. 若引用唯一可见定义，requireDefinition 自动检查缺失/未声明依赖/歧义
    context.requireDefinition(new DefinitionKey("blocks", targetBlockId), "/target_block");

    // 2. 枚举本轮所有已解析的方块定义（跨所有来源）
    List<LocatedDefinition<?>> allBlocks = context.findAll("blocks");
}
```

### 15.3 v1 注册与错误桶检查

```java
import java.util.List;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.context.JsonRegistryGroup;

JsonTreeBuilder.clearLoadingErrors("kasuga_lib");
JsonRegistryGroup root = JsonTreeBuilder.buildForMod("kasuga_lib");
// root 为 null 表示没有声明任何 on_register 内容；非 null 时是挂在 mod 注册树上的根组
List<Throwable> errors = JsonTreeBuilder.getLoadingErrors("kasuga_lib");
```

### 15.4 解析一份索引清单并聚合（v1 纯函数入口）

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

### 15.5 只解析内容文件、不注册（v1 纯函数入口）

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

### 15.6 读写 `Diagnostics` 桶与注册诊断命令

```java
import java.util.List;
import com.mojang.brigadier.CommandDispatcher;
import lib.kasuga.registration.data_driven.diagnostics.DataDiagnosticsCommands;
import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

Diagnostics.report("kuayue", new IllegalStateException("boom"));
List<Throwable> perMod = Diagnostics.errors("kuayue");
List<String> lines = Diagnostics.summarize();
Diagnostics.clear("kuayue");

CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
DataDiagnosticsCommands.onRegisterCommands(
        new RegisterCommandsEvent(dispatcher, Commands.CommandSelection.ALL, null));
dispatcher.execute("kasuga_data errors kuayue", source);   // source 权限需 >= 2
```

### 15.7 运行重载周期与解码动画片段

```java
import com.google.gson.JsonParser;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import lib.kasuga.rendering.models.mc.dynamic.fsm.AnimationClipLoader;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmClipsReloadHandler;
import lib.kasuga.rendering.models.mc.dynamic.fsm.FsmReloadHandler;
import lib.kasuga.rendering.models.uml.dynamic.animation.AnimationClip;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmAnimationClips;
import lib.kasuga.rendering.models.uml.dynamic.fsm.FsmDefinitions;
import net.minecraft.server.packs.resources.ResourceManager;

FsmDefinitions definitions = new FsmDefinitions();
FsmAnimationClips clips = new FsmAnimationClips();
ReloadOrchestrator orchestrator = new ReloadOrchestrator(
        new FsmReloadHandler(definitions, clips), new FsmClipsReloadHandler(clips));
orchestrator.reload(resourceManager);

AnimationClipLoader.DecodedFile decoded =
        AnimationClipLoader.decodeFile(JsonParser.parseString(json));
for (AnimationClip clip : decoded.clips()) {
    AnimationClipLoader.register(clips, clip);   // 写 RESOURCE 半区，SCRIPT 同 id 条目不被覆盖
}
```

### reference 使用说明

- v2 `DataDrivenBuildPipeline` 配合 `SnapshotContentSource`（内存 VFS）可在无 NeoForge 运行时的纯 JUnit 环境直接完成 `prepare` 预检验证；涉及真实 MC `Block` / `Item` 构造的 `build` 与 `apply` 阶段、以及依赖 `ResourceManager` 的 `ReloadOrchestrator.reload` 需在有 NeoForge 注册与资源上下文的环境下运行。
- v1 纯函数 `parseContentBody`、`parseIndexManifest`、`resolveIndexManifests`、`DuplicateIdResolver`、`EffectiveId` 与 `Diagnostics` 可在普通 JUnit 测试直接断言。

> 数据驱动专题其它页：[注册期 API](api-registration.md) · [扩展 API](api-extension.md) · [诊断 API](api-diagnostics.md) · [重载 API](api-reload.md) · [Schema 规范](schema.md) · [方块状态属性](block-state-properties.md) · [专题索引](README.md)
