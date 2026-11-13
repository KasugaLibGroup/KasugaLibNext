# 统一资源平台与 Script Ticket

平台在现有 VFS 之上提供稳定身份、不可变内容捕获、目录路由、代际发布和可撤销访问能力。Java 宿主批准源及 Entry Point；JSON 只描述已批准源的路由，不挂载路径、不下载文件、不注册 Java 实现。

## 身份与内容

具体身份采用 `ksg://<source>/roots/<root>/<path>#<child>`。`source` 和 `root` 由宿主分配，不能由绝对路径、枚举序号或 pack 显示名临时生成。URI 不包含 scope/generation；运行时句柄另外绑定这两项。

路径保留 Unicode、空格及大小写；序列化使用规范 UTF-8 percent encoding。拒绝绝对路径、反斜线、空段、`.`/`..`、控制字符、query、非法 UTF-8 和编码后的分隔符。为拒绝二次解码，原始名称也不能含 `%` 后紧跟两位十六进制字符。使用 `ResourceUri.file(...)`/`.child(...)`，不要手工拼 URI。

例如原始 `模型/列车 A.bbmodel` 与子资源 `#texture/uuid/…` 各有独立身份。`MinecraftNameCodec` 在保留命名空间 `kasuga_generated` 中以版本前缀 `v1/` 和完整 URI 的十六进制 UTF-8 编码生成可逆名称。MC 适配器再加 `models/`、`textures/` 和格式后缀。已有 `assets/<namespace>/...`、`data/<namespace>/...` 保留原生 ResourceLocation，必须符合原版小写命名规则。

`ResourceSourceDescriptor` 携带 owner/source/root、显式 rank 及来源类别。rank 从低到高叠加；同逻辑路径同 rank 冲突会失败，不依赖 HashMap 顺序。`ResourceCatalog` 同时保存全部 provider 和可见 winner。权限先过滤 provider，再选择 winner；winner 类型错误会报错，不会悄悄回退到低层。

`ResourceSources` 支持宿主批准的目录、普通 ZIP/JAR、展开的 mod 目录和任意已有 VFS。它捕获实际字节并计算 SHA-256，连续捕获一致才返回快照；修改或替换归档后下次捕获会重新打开。拒绝穿越、重复归档项、文件/目录冲突、符号链接、特殊文件、加密或分卷 ZIP，当前也不支持 ZIP64。没有文件名能触发自动联网。

默认 `InputPolicy` 为 JSON 8 MiB、单文件 256 MiB、源总量 1 GiB、50,000 文件、JSON 深度 64、超过 1 MiB 暂存到私有临时文件。实际解压/派生字节也计入限额。深度由 JSON 解码器检查；bbmodel 适配器使用传入 policy 的深度。跨文件相互依赖的源仍应由宿主通过事务或原子替换发布。

`ResourceTypeDetector` 最多查看 512 字节：PNG/JPEG/GIF/WebP、WAV/Ogg/FLAC/带 ID3 的 MP3、GLB/PMX、ZIP 优先根据 magic number 判断。无固定签名的文本格式在 UTF-8/控制字符检查后才使用扩展名提示；JSON 前缀可识别无扩展名文本。descriptor 的 `typeEvidence` 区分 `MAGIC`、`TEXT_PREFIX`、`EXTENSION`、`DECLARED`、`UNKNOWN`。签名只是发现证据，不能替代格式解码、权限判断或脚本执行授权。

## Schema 与结构路由

可校验的编辑器 schema 位于 [`resource-routing.schema.json`](resource-routing.schema.json) 和 [`resource-template.schema.json`](resource-template.schema.json)。运行时还检查重复 JSON 键、未知字段、来源授权、模板环、重复 ID 和路径安全。格式版本为 1。

```json
{
  "format_version": 1,
  "bindings": [
    {"id": "root", "source_id": "example", "schema_id": "minecraft_resource_schema"},
    {
      "id": "scripts", "source_id": "example", "source_prefix": "scripts",
      "outputs": [{
        "id": "blocks", "entry_point_id": "kasuga:script_blocks",
        "when": "resource_reload", "side": "server", "target_prefix": "scripts"
      }]
    }
  ]
}
```

`source_prefix` 按完整路径段匹配。先选择最长匹配 binding，再检查其输出的 event/side；子 binding 没有当前端侧输出时不会回到父 binding。`a/b` 不匹配 `a/bc`。路径剥去 source_prefix 后加 target_prefix；根级 `kasuga.resources.json` 不作为普通消费者输入。

模板使用 `id`、单个 `extends`、`remove_outputs`、`outputs`。继承输出按稳定 output ID 整项替换，不能删后又新增同 ID；删除不存在的 ID 失败。单个 binding 可以显式输出到多个消费者；同一文件投给同消费者同目标的重复输出失败。模板最长继承链为 32。模板注册表由宿主传给 `ResourceRouting.decode`，JSON 中没有外部 `$ref` 或远程 schema 加载。

事件为 `bootstrap`、`client_startup`、`server_startup`、`resource_reload`、`remote_connect`；端侧为 `client`、`server`、`both`、`none`。`both` 是声明值，每次实际 invocation 使用一个具体逻辑端；集成服务端的 server 脚本仍按 server 执行。`none` 只供不依赖 MC 上下文的消费者使用。声明不能超过 Java `EntryPointDescriptor` 允许的事件和端侧。

## 首次 MC 发现与模型

原生 pack 投影在 **AddPackFindersEvent/RepositorySource 阶段** 加入。宿主在首次 discovery 前调用：

```java
ResourceSource source = ResourceSources.directory(
    new ResourceSourceDescriptor("my-mod", "example", "main", 100,
        ResourceSourceDescriptor.Origin.EXTERNAL), approvedDirectory, InputPolicy.DEFAULT);
ResourceRouting routing = ResourceRouting.decode(json, ResourceRouting.builtinTemplates(), Set.of("example"));
AutoCloseable registration = resourceSystem.getApprovedPacks().register("example", List.of(source), routing);
```

`ResourceSystem` 已将其 finder 订阅到 mod bus。批准的目录须由宿主提供；由宿主读取配置文本并显式解码。可选的 `ModelResourceSource` 包装应在注册之前完成。每个 source/root 是独立 MC pack 层，真实 MC ResourceManager 决定 winner 与 stack。注册 handle 只解除注册，不关闭宿主 source；已打开的 pack/stream 使用自己的 lease。

`McManagedResourceView` 借用 MC manager，保留其真实 stack，并可按宿主提供的稳定 pack provenance 捕获为 `MC_MANAGED` 源。它不关闭 MC manager/pack。`MC_MANAGED` 输入禁止再次投影进 MC，避免资源发现环。

非 MC bbmodel 使用 `ModelResourceSource`：保留原始文件，提取内嵌 PNG 为有 parent URI 的派生纹理，使用 texture UUID 或显式 index 区分同名纹理，并生成引用这些纹理的 MC 模型副本。模型与纹理在同次发现前准备，现有模型扫描、解码、材质和图集管线接收它们，无须第二轮 reload。像素数量有宿主限额。

| 输入 | 当前接入 |
|---|---|
| 原生 assets/data 模型与纹理 | 保持已有加载路径和原生 ID |
| 非 MC bbmodel + 内嵌 PNG | 完整捕获、派生、首次模型/图集加载、替换及删除 |
| OBJ/PMX/glTF/FMT 等 | 提供 `ResourceModelInput` 与 text/json/binary Source 适配器；各解码器的容器/外部依赖迁移需对应宿主适配 |
| bbmodel 外部图片、非 PNG 内嵌图片 | 当前拒绝，须显式依赖适配器 |

`ResourceModelInput.sibling` 只允许同一具体 source/root 内的相对依赖，不能跨根搜索或从低优先级 provider 偷取依赖。平台没有另建 GPU 后端。

## Scope 与发布

Java 消费者实现 `ResourceEntryPoint`，声明输入类型、事件、端侧、依赖和 reload 能力；`prepare(ResourceBatch, InvocationContext)` 返回异步 `PreparedConsumerBatch`。整份计划先验证，再捕获，再按依赖拓扑顺序准备，最后在宿主 publication executor 发布并一次替换 coordinator 的 current generation。新请求使旧的未发布准备失效；prepare 失败保留已发布 generation。

通过 `ResourceSystem.createClientResourceScope`、`createServerResourceScope(server, id)`、`createProcessResourceScope` 选择正确 owner；server 停止只关闭所属 scope。调用 `coordinator.reload(sources, routing, event, side, prepareExecutor, publishExecutor)` 发起工作。宿主负责订阅合适事件/可选 VFS watch 和合并 reload 请求；声明配置本身不会创建 watcher。

publication executor 必须存活到最后一个 generation lease 释放。`ResourceGeneration.lease()` 可保留旧内容；退役立即撤销脚本调用，最后一个 lease 释放后在 publication executor 关闭消费者和快照。`disposal()` 报告最终释放结果。`ResourceSnapshot` 的 file/stream lease 独立保留 captured bytes，关闭后不再允许新读取。调用方取消 reload 返回的 future 只是放弃观察，不跳过内部清理或取消已提交的发布。

`MinecraftResourceEntryPoint.Projection.openLayers()` 供自行管理 MC discovery/reload barrier 的宿主使用。coordinator 的原子 publication 保证平台视图一致；原版 MC reload 及消费者 `publish()` 的外部副作用仍遵循各自生命周期，平台不能撤销任意 Java 回调已经产生的副作用。

## 脚本块与 Ticket

数据文件只声明引用和预期契约：

```json
{
  "package": "ksg://example/roots/main/scripts/package.toml",
  "block": "increment",
  "side": "server",
  "contract": {"arguments": ["number"], "result": "number"}
}
```

静态阶段调用 `ScriptReferenceCodec.decode` 或 data-driven 的 opt-in `ScriptBlockReferenceDecoder`，校验结构、逻辑端侧及宿主可见性，不执行脚本。包启动后在沙箱的 `ScriptThread` 中显式注册：

```javascript
ScriptBlocks.register("increment", '{"arguments":["number"],"result":"number"}', value => value + 1);
```

宿主注册 `ManagedScriptEntryPoint(engineLookup, trustedSourcePredicate, referencePermissionPredicate)` 并路由包文件。每代使用独立 module registry，绑定 scope/generation/ScriptPackage/ScriptEngine/ScriptThread。包准备完成后 `ScriptBlockRegistry.bind(ref, context)` 才产生 `ScriptBlockHandle`；名称、契约或代际不匹配会失败。普通 `module.exports` 不自动变成外部可调用块。可信判定由宿主来源策略给出，不能相信包内的自报字段。

```java
ScriptTicket ticket = handle.submit(List.of(41)); // 仅复制数据并入队，立即返回
ticket.poll().ifPresent(outcome -> consume(outcome));
ticket.onComplete(callerExecutor, outcome -> consume(outcome));
```

这是 io_uring 式提交/完成 token 的 Java 实现，不使用 Linux 原生 io_uring。每个目标线程有有界 MPSC ring，默认容量 1024；`ScriptThread` 按最多 64 个请求/2 ms 的批次预算在 owner 线程执行。预算在回调之间检查，不能抢占单个正在执行的函数。参数/结果只允许复制的有限深度数据，不能泄露引擎值或宿主对象。

ticket 状态为 `QUEUED/RUNNING/SUCCEEDED/FAILED/CANCELLED/REJECTED`；满环或参数错误立即拒绝，异常回填为类型/消息。调用方可取消；代际退役或 scope 关闭立即取消所有未完成 ticket，迟到结果不能覆盖取消。ticket 没有阻塞 `get/join` API；完成回调使用调用方 executor，MC 主线程不得同步等待脚本。

当前 Javet 的通用沙箱能力标为 `UNVERIFIED`，因此新入口默认拒绝不可信脚本。只有引擎显式证明 host 类/反射、文件、网络、执行预算、内存及取消隔离能力后才可允许不可信包；本改动没有宣称完成该沙箱。legacy 脚本入口保持兼容，其 reload 改为异步等待退役，不在游戏线程 join。

可选 `AccessFactory` 给包分配 `ResourceAccess`，脚本获得 data-only `Resources` facade。grant 限定 owner/source/root/prefix 和 read/query/write/create/delete；读取有单次及总量预算。写入仅进入宿主创建的有界内存 Workspace，不改源 pack、任意磁盘路径或世界存档。scope 退役撤销访问，宿主负责 Workspace 的最终关闭。

## 验证

```sh
./gradlew :modules:core:test :modules:data-driven:test :modules:scripting:test :modules:modelling:modelUnitTest
./gradlew :modules:modelling:test --tests '*ModelResourceSourceTest' :external:slp:javet:test --tests '*ResourceScriptTicketIntegrationTest'
./gradlew :modules:modelling:runClientHeadless -PkasugaClientDirectory="$PWD/build/resource-platform-client" -PkasugaTestResources=true
```

客户端 smoke 在菜单中验证首次模型与 GPU 纹理、同 ID 新像素、删除后的模型与 sprite，写 `debug/resource-platform/report.json` 并退出，不打开世界。FML 集成测试使用真实 pack/manager，Javet 集成测试实际注册和执行 JS。纯 JVM `modelUnitTest` 不运行带 `minecraft-resource-integration` 标记的 vanilla metadata 集成场景；该场景在正常 `test` 中执行。
