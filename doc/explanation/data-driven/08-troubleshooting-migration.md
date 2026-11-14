# 排查与迁移

数据驱动错误通常落在四层：资源是否进入来源、manifest 是否正确、handler/schema 是否匹配、最终 claim 是否冲突。按层定位比只看“内容没加载”更快。

## 先确认当前走哪条注册入口

| 现象 | 判断 |
| --- | --- |
| 日志出现宿主记录的 `PreparedContent`、`BuildResult`、diagnostic code | 使用 provider + 多来源管线 |
| 日志出现 `Loaded N JSON entries ... for mod` | 使用旧 `JsonTreeBuilder` |
| 两种日志都没有 | 没有索引、provider 没安装、mod 没参与注册，或内容为零 |

pipeline 本身不强制打印固定启动日志；“新管线”是否存在应以 provider 注册和结构化 `BuildDiagnostic` 为准，不能依赖某一句话。

在旧入口中检查：

```text
No data-driven index directory for mod '<id>'
Loaded ... JSON entries ... for mod '<id>'
Failed to load JSON registrations for mod '<id>'
```

在新入口中检查宿主记录的：

```java
PreparedContent.sources()
PreparedContent.registerFiles()
PreparedContent.reloadFiles()
PreparedContent.definitions()
PreparedContent.claims()
PreparedContent.diagnostics()
```

`PreparedContent` 是启动时刻的事实快照。出现“文件明明存在但没加载”时，先核对它记录的 source 和逻辑路径，而不是直接假设 resource pack 路径等价。

若同一 source 中已经解析出部分 definition，随后文件枚举或读取抛异常，prepare 会保留先前结果并追加 `SOURCE_READ_FAILED`。STRICT 会拒绝整批；LEGACY 可能继续 apply，所以不能把“存在部分 definition”解释成该 source 全部健康。

## 常见症状

### 加载 0 个定义

检查顺序：

1. `SourceDescriptor.namespaces` 是否包含 `data/<ns>` 中的 namespace；
2. `SnapshotContentSource` 的 `declaredIndexes` 是否包含实际 index，或普通来源是否把它放在标准 `data/<ns>/kasuga_lib/data_driven/` 直接子层；
3. manifest 是否声明 `on_register`，不是旧字段 `sources`；
4. content 路径是否相对于 `data/<ns>/`；
5. 内容文件是否真的存在于同一个 source/root snapshot；
6. source 是否已关闭、fixed source 是否已关闭，或 snapshot 是否被提前 retire；
7. provider 是否在第一次注册分派前安装。

新管线允许真正没有 index 的来源，因此“0 定义”不一定是错误；纯资源包应通过 `registerFiles().isEmpty()` 和宿主预期确认。

对 main/parcel 车包还要检查：

- main 与每个 parcel 是否有不同 rootId；
- rootId 是否不含冒号；
- 所有 pack/root 的 rank 是否全局唯一；
- parcel 是否只声明自己的 index，global main 是否没有误把未声明 parcel index 自动扫入；
- 注册和 Approved pack 投影是否使用同一冻结 root。

### `INVALID_SOURCE`

`INVALID_SOURCE` 不只表示一个重复 sourceId。v2 还会检查：

```text
(sourceId, rootId) 重复
全局 rank 重复
```

同一个 sourceId 下出现 main、parcel-m1、parcel-m2 是合法的；`rootId` 必须区分它们。每个 root 都有独立 rank，不能每个 pack 都从 0 重起。

### `MANIFEST_INVALID`

逐项检查：

- 顶层只能是 `on_register`、`on_reload`；
- 值为字符串数组；
- 两个域至少声明一个，空数组合法；
- 每个路径相对、slash 分隔、带 `.json`；
- 同一路径不能同时出现在两个域。

manifest 诊断始终是 `ERROR`。LEGACY build 可能继续执行，但不应把带 `MANIFEST_INVALID` 的结果当成健康加载。

STRICT 还会拒绝重复 JSON 键、注释、尾逗号、额外顶层文档、非法 UTF-8、超过 64 层的 JSON 及超过 100,000 个节点的文档。出现这些错误时，先按标准 JSON 重新生成文件，不要依赖 Gson 的兼容行为修复。

### `SOURCE_FILE_MISSING`

manifest 路径以 `data/<ns>/` 为基准：

```text
manifest: "content/blocks.json"
physical: data/example/content/blocks.json
```

如果写成了 `data/example/content/blocks.json`，会实际查 `data/example/data/example/content/blocks.json`。

同时检查路径大小写和字符集。注册期 `ContentSource` 可以使用 JVM/文件系统允许的更宽松文件名，但运行期 reload 还要能构造 `ResourceLocation`（`[a-z0-9/._-]`）；`DataDrivenBuildPipeline` 的 reload 预检不会替你完成这一步。

### `UNKNOWN_TYPE`

注册期和 reload 期使用不同 handler 集合：

| 内容 | 正确 manifest 域 | 正确顶层键 |
| --- | --- | --- |
| blocks/items/groups | `on_register` | `blocks`、`items`、`registry_groups` |
| block entity | `on_register` | 内嵌 `blocks[].block_entity` |
| state machine | `on_reload` 或 `state_machines/` glob | `state_machines` |
| animation clip | `on_reload` | `animation_clips` |

常见错误：

- 把 `block_entities` 写成顶层字段；
- reload handler 尚未注册就执行 prepare；
- 把注册文件列入 `on_reload`；
- 把 reload 文件列入 `on_register`。

### `UNKNOWN_FACTORY`

`type` 值不在 `FactoryRegistry`：

```java
FactoryRegistry.contains(type)
FactoryRegistry.containsItem(type)
FactoryRegistry.containsBlockEntity(type)
```

确保 factory bean 在第一次 prepare 前完成初始化。生产代码不要把 contentTesting factory 当作发布 API。

`data_driven_block` 只在 `JsonTreeBuilder.ensureHandlersRegistered()` 运行时按需注册；`fsm_block` / `fsm_be` 需要宿主显式调用 `FsmBlockEntityFactories.registerBuiltin()`。新 pipeline 的 handler 列表和 factory 表是两套独立初始化。

### `UNKNOWN_REGISTRY_GROUP`

组引用按原字符串查：

```json
{"registry_group": "example:panels"}
```

必须对应：

```json
{"id": "example:panels"}
```

`panels` 与 `example:panels` 不相等。若组属于另一个 owner，还需要显式 owner 依赖。

### `REFERENCE_NOT_DECLARED`

定义存在，但当前 owner 不能引用：

```java
Map.of("addon_owner", Set.of("core_owner"))
```

依赖只认直接关系。A 引用 C、A 只依赖 B、B 依赖 C 时仍会失败；A 要显式依赖 C。

### `MISSING_REFERENCE`

同一 `DefinitionKey` 在整个 session 中不存在。检查：

- handler type domain 是否相同；
- identity 是否有 namespace 或经过 `EffectiveId`；
- 引用的定义是否因 parse 失败而没有进入 directory；
- 是否把 registry id 当成配置 identity。

### `INVALID_IDENTITY`

内建 blocks/items 在 STRICT 中要求显式 namespace：

```text
"id": "example:panel"
```

不要写：

```text
"id": "panel"
```

旧 `JsonTreeBuilder` 入口可以把裸 id 映射到 host namespace；严格外部包使用完整 id，便于预检、报告和跨来源诊断。两条 identity 路径对 `minecraft:` 并不等价：

```text
JsonTreeBuilder: minecraft:panel -> <hostModId>:panel
STRICT: minecraft:panel -> minecraft:panel
```

STRICT 包若写 `minecraft:`，会真正占用原版 namespace。裸 `panel` 的 `resolveIdentity` 虽可暂时得到 host id，但 `validate` 仍会以 `INVALID_IDENTITY` 拒绝。`DataDrivenBuildPipeline` 按 `BuildOptions` 选择身份规则；新包使用 STRICT，异常时必须沿 identity -> validate -> apply -> claims 四步核对。

内建 block/item `claims` 从当前 DefinitionKey 读取最终 ID。显式 `minecraft:panel` 在 STRICT 中让 DefinitionKey、claim 和 apply 都使用 `minecraft:panel`；LEGACY 中三者共同使用历史宿主 namespace 映射。

内嵌方块实体没有自己的 `id`。它的校验对象是由 `extractEmbedded` 注入的 `_parent_block`；宿主方块 id 同样必须是显式的 `namespace:path`。

### `CLAIM_CONFLICT`

表示两个 definition 或一个 definition 与宿主代码注册争抢同一最终 key：

```text
(minecraft:block, example:panel)
(minecraft:item, example:panel)
```

检查：

- 两个来源是否改了不同 JSON id，但 namespace 映射后相同；
- block factory 是否声明 `createsBlockItem=true`；
- 独立 item 是否与自动 block item 使用同 id；
- 宿主 reserved snapshot 是否包含已有 code registration；
- 外部来源是否本应通过 disabled pack 被排除。

LEGACY 可能只记录诊断并继续，最终 Minecraft registry 行为不安全。生产多来源接入应使用 STRICT。

### `HANDLER_NOT_STRICT_READY`

handler 缺少：

```java
STRICT_PREFLIGHT
```

或没有恰好一个：

```java
NON_REGISTRATION
REGISTRY_CLAIMS
```

也可能表示同一个 `typeName` 在 pipeline handler 列表中重复。

### `GROUP_CYCLE`

两个或多个 `registry_groups.parent` 形成环。删除一条父边，或抽出无环公共父组。

### `VALIDATION_FAILED`

handler 的 `validate` 抛出了未预期异常。诊断 context 方法用来报告预期内容错误；只有程序错误应转为这个 code。检查 handler 是否在 validate 中访问了尚未初始化的全局服务。

### `APPLY_FAILED`

prepare 通过后，factory 或 apply 仍可能失败。常见原因：

- factory 依赖尚未注册的 deferred holder；
- group id 在 apply meta 中缺失；
- 类型转换错误；
- factory 参数不满足构造要求；
- context 创建时宿主服务不可用。

失败可能发生在部分定义已经应用之后。宿主不要挂载 `FAILED` 的 root，除非它能明确证明哪些副作用安全。

context 创建失败时还没有 definition 被 apply；排序、definition apply 或 finalizer 失败则可能已经产生部分副作用。`BuildResult.Status` 和 `finalizerResults` 只能描述流程进度，不能回滚进程级缓存。

### `FINALIZER_FAILED`

所有 definitions 已 apply，finalizer 在收尾阶段失败。修复 finalizer 或把它改成可诊断、幂等的步骤。`BuildResult.finalizerResults` 只含失败前已成功返回的值。

### Provider 看起来没生效

检查：

- install 使用与 `modRegistry.getModId()` 完全相同的 id；
- install 发生在注册 callback 前；
- 同一 host 没有测试或旧实例留下 attachment；
- provider 的源文件扫描没有返回 null；
- provider 没抛异常；
- 返回 root 只在 `result.successful()` 时提交。

如果 provider 返回 null，`JsonTreeIntegration` 不会退回旧 JAR 扫描；provider 已接管该 host。

### Reload 内容不更新

`on_reload`：

- 是否在 `/reload` 时当前启用的 data pack 中；
- handler `typeName()` 是否已经注册；
- pack 是否真的提供该逻辑路径；
- `Decoded.errors()` 是否非空；
- identity 是否被后加载文件 last-wins 覆盖；
- script 桶是否应当优先。

`state_machines` 未被 manifest 列出仍可由 glob 发现；`animation_clips` 必须列入 `on_reload`。

### Java 内容注册了，但模型或语言缺失

`Reg` 只处理 Java registry。补齐：

```text
assets/<ns>/blockstates/<block>.json
assets/<ns>/models/block/<block>.json
assets/<ns>/models/item/<item>.json
assets/<ns>/textures/...
assets/<ns>/lang/en_us.json
```

并确保资源包进入客户端 `ResourceManager`。

### 数据包覆盖顺序与 source 顺序不一致

平台 rank 只影响数据驱动 prepare/build 和 Approved pack 层的生成顺序；MC `ResourceManager` 仍决定最终单读取。宿主必须从一个冻结选择结果同时：

- 排列 main/parcel/shared `ContentSource`；
- 注册/排序 `PackResources`；
- 记录实际启用的 pack；
- 在服务端与客户端保持一致。

若注册和资源投影各自重新扫描安装目录、重新分配 rank 或使用不同禁用集合，就会出现“Java 注册成功但模型缺失”或“已拒包资源仍可见”。先比较两边的 `(sourceId, rootId, rank)` 清单。

### 重启后旧世界缺 block/item

注册集变化后，旧 world 中保存的 id 可能不存在。KasugaLib 提供 prepared claims 和构建结果，但 world compatibility policy 属于宿主。宿主应在世界加载前比较历史 pack set 和 claims，发现缺失或替换时拒绝启动。

## 诊断出口

### 注册

- `DataDrivenBuildPipeline`：结构化 `BuildDiagnostic`，只存在于 `PreparedContent` / `BuildResult`；
- 旧入口：`JsonTreeBuilder.getLoadingErrors(modId)` / `Diagnostics.errors(modId)`；
- 游戏命令：`/kasuga_data errors [mod]`。

### reload

- 日志中的 handler/file/namespace 上下文；
- `Diagnostics.Domain.RELOAD_DATA`；
- `/kasuga_data errors [namespace]` 会同时显示该 namespace 的 reload 条目。

新管线的 build diagnostics 不会自动写入 `Diagnostics`。provider 应把它们写入宿主报告、日志或自己的诊断 outlet。

运行期 reload manifest 复用旧 manifest parser，因此其 schema 错误可能进入 per-mod 兼容错误桶；文件 decode、未知顶层键和 register 失败通常进入 `RELOAD_DATA`。`RELOAD_DATA` 每轮清空，per-mod 兼容桶不由 reload 周期统一清空。命令权限等级为 2。

常用 code 的定位顺序：

| Code | 先查什么 |
| --- | --- |
| `INVALID_SOURCE` | `(sourceId, rootId)` 或全局 rank 是否重复；LEGACY 也会记录为 error |
| `UNKNOWN_DEPENDENCY` | dependency 是否拼错，对应 owner 是否有 source |
| `MANIFEST_INVALID` / `MANIFEST_EMPTY` | 顶层字段、数组类型、双域冲突、是否至少声明一个域 |
| `INVALID_PATH` / `SOURCE_FILE_MISSING` | 路径是否相对 `data/<ns>/`、扩展名、traversal、实际文件 |
| `SOURCE_READ_FAILED` | 文件是否消失、变更后超限、ZIP/目录句柄是否已关闭 |
| `UNKNOWN_TYPE` | 注册文件和 reload 文件是否放错域，handler 是否已安装 |
| `PARSE_FAILED` / `CONTENT_INVALID` | 元素 codec、必填字段、数组 shape |
| `MISSING_IDENTITY` / `INVALID_IDENTITY` | `resolveIdentity` 结果和内建 handler 的显式 namespace |
| `HANDLER_NOT_STRICT_READY` | 重复 type name，或缺少 `STRICT_PREFLIGHT` 和唯一 role |
| `CLAIM_FAILED` / `CLAIM_CONFLICT` | handler 漏报、最终 `(registry,id)` 冲突、reserved snapshot |
| `APPLY_FAILED` / `FINALIZER_FAILED` | factory 初值、context 服务、已执行的部分副作用 |

## 迁移步骤

### 1. 统一索引

把旧平铺布局迁移到：

```text
data/<ns>/kasuga_lib/data_driven/
```

把 `sources` 改为 `on_register`。reload 内容移到 `on_reload`。所有路径相对 `data/<ns>/`。

一个典型迁移如下：

```text
旧：data/example/kasugalib/blocks.json
新：data/example/kasuga_lib/data_driven/main.json
    data/example/kasuga_content/blocks.json
```

```json
{
  "on_register": ["kasuga_content/blocks.json"],
  "on_reload": ["animation_clips/wheels.json"]
}
```

`state_machines/` 可以继续依靠目录 glob，但 `animation_clips/` 必须由 `on_reload` 明确列出。旧目录 `kasugalib/` 当前不再读取。

### 2. 决定入口

| 需求 | 入口 |
| --- | --- |
| 单 mod JAR，无多来源，无严格 claim | `JsonTreeBuilder` 兼容 |
| 多个内置/外部包、依赖、严格冲突检查、finalizer | provider + `DataDrivenBuildPipeline` |

### 3. 设计 source/owner

为每个包分配稳定 source id、owner id、namespace 和全局 rank。再为每个 main/parcel/shared 根分配稳定 root id；不要把含冒号的业务 parcel ID 直接当 root id。不要把“文件来自哪个 ZIP”与“内容归哪个 owner”混成一个概念。

### 4. 声明依赖

把真实的直接引用转换为 `dependencies` map。依赖不会自动由内容路径、namespace 或 source order 推断。

### 5. 让 handler 支持 STRICT

- 注册类型加 `STRICT_PREFLIGHT` + `REGISTRY_CLAIMS`；
- 配置类型加 `STRICT_PREFLIGHT` + `NON_REGISTRATION`；
- 实现无副作用 validate；
- 报告所有最终 claims；
- 稳定实现 parentDefinition。

同时在第一次 prepare 前显式安装 handler 和 factory。复用内建注册 handler/普通方块 factory 可调用 `JsonTreeBuilder.ensureHandlersRegistered()`；FSM factory 需要调用 `FsmBlockEntityFactories.registerBuiltin()`；自定义实现则直接加入当前 pipeline 或 `FactoryRegistry`。

### 6. 收集 reserved claims

在 build 前把宿主 code registration 加入：

```java
RegistryClaimSnapshot.collect(hostRoot)
```

或由宿主提供等价的完整 key 集合。

### 7. 接入 provider

在注册分派前 install。provider 内：

- 打开来源；
- STRICT prepare；
- 记录 diagnostics；
- 只在没有 blocking error 时 build；
- 只提交 `APPLIED` root；
- 保存 source 顺序、claims 和 status。

### 8. 接入 resource pack

从同一 fixed root 集合建立 Approved pack 层，并统一 source/root rank。等 strict 预检、拒包闭包和 build 成功后再发布资源；不要在 provider 尚未完成 checks 时先把全部包加入资源栈。

### 9. 验证

建议层级：

- `JsonTreeBuilder.parseContentBody` / `parseIndexManifest`：纯 schema；
- `DataDrivenBuildPipelineTest` 风格 fixture：多来源、strict、claims；
- data-driven 和 core 的 JUnit；
- FML/game test：真实 registry event；
- 专用服务器：无客户端依赖的启动；
- 客户端：模型、纹理、语言和 reload；
- 旧 world：注册集变化和 history guard。

常用命令：

```bash
./gradlew :modules:data-driven:test :modules:core:test --console=plain
./gradlew :modules:modelling:modelUnitTest --console=plain
```

## 当前文档边界和实现缺口

以下行为已由源码确认，但当前没有框架级自动化保护：

1. `DataDrivenBuildPipeline` 的 reload preflight 不执行 `globDirectories()` 扫描，未在 manifest 中列出的状态机文件只由运行期编排器发现。
2. `FactoryRegistry`、旧 `TypeHandlerRegistry` 和 `ReloadHandlerRegistry` 是进程级全局表；同名 type 覆盖，没有 production unregister。
3. `DataDrivenRegistrationProviders` 每个 host 只允许一个 provider，没有层级组合。
4. `DataDrivenBuildHooks` 没有注销 API，重复初始化可能重复登记 finalizer。
5. claim registry 字段是字符串，没有集中式 registry-key 类型校验。
6. `build` 没有事务回滚；`FAILED` 可能已留下上下文或 finalizer 副作用。
7. `BuildDiagnostic.Stage.SELECTION` 已定义，但当前 pipeline 没有直接产生该 stage 的诊断。
8. 旧 Directory/ZIP `ContentSource` 固定文件列表或打开句柄，但不构成进程级冻结字节；需要跨注册、discovery 和 reload 固定输入时必须使用 `FixedResourceSource` + `ResourceSnapshot`。
9. library 不注册 resource/data pack，pack discovery、物理路径支持和覆盖顺序由宿主实现。
10. 内建 block handler 只派生同 id block item claim；factory 创建的其他 registry 子项需要专用 handler 或宿主 claim 方案。
11. 世界存档兼容策略、pack history 和缺失 pack 拒绝逻辑由宿主负责。
12. `canReference` 检查“存在可见候选”，`find` 返回“全 session effective winner”；隐藏 owner 的高优先级候选与低优先级可见候选并存时，两者可能指不同来源。
13. 新 pipeline 不会自动安装 `TypeHandler` 或 factory。复用内建项需要显式调用 `JsonTreeBuilder.ensureHandlersRegistered()`；FSM factory 还需要宿主调用 `FsmBlockEntityFactories.registerBuiltin()`。
15. source 准备异常是逐来源隔离而非事务；异常前已解析的路径、候选和 definition 会留在本次 prepare。
16. pipeline 的 reload 预检不构造 `ResourceLocation`，因此运行期仍可能因大小写或非法字符拒绝一个预检通过的 `on_reload` 路径。
17. 运行期 manifest parser 的诊断出口仍复用旧 per-mod 错误桶；`RELOAD_DATA` 只覆盖编排器直接产生的 reload 错误。
18. `dependencies` map 中完全未被某个 source owner 使用的键不会单独报错。
19. `SnapshotContentSource.declaredIndexes` 只筛选 `indexes()`，不会裁剪可读取文件，也不会验证索引路径；宿主必须自行验证声明入口与 parcel 映射。
20. `DataDrivenBuildPipeline` 的 `BuildDiagnostic` 没有 parcelId 字段；宿主需要从冻结 `(sourceId, rootId)` 映射补充车包报告。
21. 注册 provider 未与 Approved pack discovery 共用提交边界时，被拒绝的来源可能先进入 MC 资源栈；发布必须晚于最终注册选择。
22. `DataDrivenBuildPipeline`、内建 handler 的 DefinitionKey、claims 和 apply 按 `BuildOptions` 共用 LEGACY 或 STRICT 身份规则。

这些边界不会阻止当前单来源兼容或受控宿主接入，但跨仓库发布前应明确由哪个宿主层承担每项责任。特别是第 14–17 项会直接影响“配置看似正确、运行时却缺 handler/factory 或诊断落在另一处”的排查。
