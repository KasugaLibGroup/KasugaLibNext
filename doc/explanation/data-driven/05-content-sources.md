# 内容来源、快照与安全

v2 的注册来源有两层：

1. core 的 `ResourceSource` 负责捕获物理来源，返回不可变的 `ResourceSnapshot`；
2. data-driven 的 `ContentSource` 负责把一个具体 root 暴露为 manifest/JSON 读取视图。

生产接线应使用 `ResourceSource -> ResourceSnapshot -> ContentSourceFactory.snapshot(...) -> DataDrivenBuildPipeline`。直接打开目录或 ZIP 的 `ContentSourceFactory.directory/zip/modJar/open` 仍保留给旧入口和测试兼容，不再代表 v2 的固定启动快照模型。

```java
ResourceSourceDescriptor descriptor = new ResourceSourceDescriptor(
        "example",
        "example-pack",
        "main",
        17,
        ResourceSourceDescriptor.Origin.EXTERNAL
);

try (ResourceSource fixed = ResourceSources.fixed(
             descriptor,
             ResourceSources.mod(descriptor, modRoot, InputPolicy.DEFAULT),
             InputPolicy.DEFAULT);
     ContentSource source = ContentSourceFactory.snapshot(
             fixed.capture(),
             descriptor,
             Set.of("example"),
             "example pack root main"
     )) {
    PreparedContent prepared = pipeline.prepare(
            List.of(source),
            BuildOptions.STRICT
    );
}
```

## 平台身份

`ResourceSourceDescriptor(ownerId, sourceId, rootId, rank, origin)` 是 core 的唯一提供者身份：

| 字段 | 约束 | 用途 |
| --- | --- | --- |
| `ownerId` | 匹配平台 ID 规则，最多 128 字符 | 定义所有权、依赖可见性和访问授权 |
| `sourceId` | 匹配同一规则 | 一个宿主逻辑 source，通常对应一个 pack |
| `rootId` | 匹配同一规则 | source 内的具体根 |
| `rank` | 非负整数 | 全局资源层顺序；越大越优先 |
| `origin` | `EXTERNAL`、`MC_MANAGED`、`DERIVED` | 来源类别和投影限制 |

平台 ID 不允许冒号。`example_railway:m1` 可以是业务 parcel ID，但不能直接作为 `rootId`；宿主必须保存稳定映射，例如 `parcel-m1`。

data-driven 的 `SourceDescriptor` 保留四字段旧构造器和新的平台构造器。由 `SourceDescriptor.of(ResourceSourceDescriptor, namespaces, label)` 创建时：

- `sourceId()` / `ownerId()` 与平台 descriptor 相同；
- `rootId()`、`rank()`、`origin()` 直接读取平台 descriptor；
- `resource()` 返回原平台 descriptor；
- `namespaces` 是注册读取允许的 namespace 集合，必须非空并稳定排序；
- `diagnosticLabel` 只用于人工定位。

旧四字段 `SourceDescriptor.of(sourceId, ownerId, namespaces)` 的 `resource()` 为 `null`。pipeline 只为这种兼容来源按输入位置分配 order；平台来源始终使用显式 rank。

## ContentSource 接口

```java
public interface ContentSource extends AutoCloseable {
    SourceDescriptor descriptor();
    Optional<InputStream> open(LogicalPath path) throws IOException;
    boolean exists(LogicalPath path);
    List<LocatedFile> files();
    default List<LocatedFile> indexes();
    default InputStream requireOpen(LogicalPath path) throws IOException;
}
```

`files()` 是打开该视图时固定的、按逻辑路径排序的文件列表。`LocatedFile` 包含 descriptor、`LogicalPath`、size，以及平台快照可提供的 `ResourceUri`、SHA-256 revision 和 tags。

`indexes()` 默认只识别 descriptor namespace 下的：

```text
data/<namespace>/kasuga_lib/data_driven/<name>.json
```

它是这一层 `data_driven/` 的直接子文件，不递归。`data/<namespace>` 的 namespace 必须出现在 `SourceDescriptor.namespaces` 中。

`ContentSource` 是读取协议，不是沙箱。路径和归档结构由实现约束，但 handler、JSON codec 和 factory 仍在宿主 JVM 中执行；不可信包仍需要宿主限制依赖、资源预算和副作用。

## Scene indexing、main 与 parcel roots

本文把“当前注册场景选择哪些 root，每个 root 暴露哪些入口 manifest”称为 Scene indexing。它是组合语义，不是新增的 Java 或 JSON 类型。

平台级组合是：

```text
pack owner/source
├─ main       (低 rank，使用默认 indexes() 或宿主声明的 pack 根入口)
├─ parcel-m1  (较高 rank，只暴露 parcel.json.indexes)
├─ parcel-m2  (再高 rank，只暴露 parcel.json.indexes)
└─ shared     (宿主定义时的共享根，仍有自己的唯一 rank/index 集)
```

`main`、`parcel-*` 和 `shared` 是宿主约定。KasugaLib 只要求：

- 每个 `(sourceId, rootId)` 在一次 prepare 中唯一；
- 每个 root 的 `rank` 非负且在本次 prepare 的所有 roots 间唯一；
- root ID 不含冒号；
- 注册、MC 资源发现和 reload 消费引用同一个冻结 root；
- 被禁用的 root 不进入 source 列表，也不进入 MC Approved 资源注册。

rank 必须按所有 pack 的全局顺序分配。若每个 pack 都从 0 重起，不同 root 的同路径会出现 `ROUTING_CONFLICT`，pipeline 也会报告重复 rank。依赖拓扑只决定准备/引用顺序，不能重排 rank。

## ResourceSnapshot

`ResourceSnapshot.capture()` 的结果是一份不可变字节集合：

- 每个文件保存实际读取字节、size、检测出的 type/subtype、SHA-256 revision 和 logical path；
- 小于等于 `InputPolicy.memoryThresholdBytes` 的文件保存在内存；
- 更大的文件写入随机命名的私有临时 spool 文件；
- 捕获时逐字节实施 JSON/普通文件/总量/文件数预算；
- 同一 snapshot 不允许重复 `ResourceUri` 或重复 logical path，也拒绝文件和目录前缀冲突。

`LocatedResourceFile` 的身份是具体 provider URI：

```text
ksg://<source>/roots/<root>/<path>[#<child>]
```

有 `childKey` 时，文件 descriptor 的 `origin` 变为 `DERIVED`，rank/owner/source/root 仍继承父 provider；`parentUri()` 返回去掉 child key 的文件 URI。运行期派生产物因此能保留来源血缘，而不会伪装成新的外部 source。

`readBytes()` 读取一次完整内容。`open()` 创建一个临时读取 lease；`open(existingLease)` 为 stream 增加引用，stream 关闭后释放该引用。

调用 `ResourceSnapshot.close()` 会 retire 快照：

- 之后不能创建新 lease；
- 已存在且有效的 lease/stream 仍可完成读取；
- 最后一个引用释放时才清除内存并删除私有 spool 文件；
- `close()` 可重复调用。

`ResourceSnapshot.Builder.copy(file)` 保留原 URI、logical path、type/subtype/tags，同时重新捕获字节和 revision。它只接受 owner/source/root 完全相同的文件，不能把一个 provider 的内容偷换到另一个 provider 身份下。

## FixedResourceSource

`ResourceSources.fixed(descriptor, liveSource, policy)` 在创建时立即执行一次 `liveSource.capture()`，随后关闭 live source。返回的 `FixedResourceSource` 持有这份 baseline snapshot。

之后每次 `fixed.capture()`：

1. 在同一 descriptor 下创建新的 `ResourceSnapshot.Builder`；
2. 对 baseline 中每个文件调用 `Builder.copy`；
3. 返回一份独立、可关闭的 snapshot。

因此：

- 原目录或归档在固定之后被修改，不影响后续消费；
- 不同消费者关闭各自快照不会让另一个消费者收到 `STALE_REFERENCE`；
- fixed source 本身关闭后，新的 `capture()` 失败；
- 关闭 fixed source 会 retire baseline；已经返回给调用方的 snapshot 有独立引用计数，不受 baseline 关闭影响。

如果 baseline 中混入不同 owner/source/root 的文件，capture 失败；它不会用传入 descriptor 重新标记 foreign provider。

`ApprovedResourcePacks` 的每次 discovery 和 `ResourceCoordinator` 的每轮 prepare 都会调用 `capture()`。给这些消费者传入 fixed source，才能让多次 discovery 与注册解析继续使用启动字节。直接把 `ResourceSources.directory/archive` 的 live source 传给它们，会重新读取安装内容。

## SnapshotContentSource

`ContentSourceFactory.snapshot(...)` 把 `ResourceSnapshot` 投影成注册视图，并把 snapshot ownership 交给返回的 `SnapshotContentSource`：

```java
ContentSource source = ContentSourceFactory.snapshot(
        snapshot,
        resourceDescriptor,
        namespaces,
        diagnosticLabel,
        declaredIndexes // 可选
);
```

构造时执行以下检查：

- snapshot 中每个文件必须属于传入的 owner/source/root；
- logical path 不得重复；
- 文件重新按 path 排序，并暴露 URI、revision、tags 和 size；
- descriptor 采用 `SourceDescriptor.of(resourceDescriptor, namespaces, label)`。

`declaredIndexes` 的语义很窄：它替换 `indexes()` 的候选集合，并保留其中存在、按 path 排序的文件。它不会：

- 删除 snapshot 中未声明的文件；
- 限制 manifest 后续 `open()` 的内容路径；
- 自动验证索引是否位于标准目录；
- 自动为缺失声明生成诊断。

传入空集合是“显式零 index”，适用于只提供原生 resources/data 的 root；传入 `null` 才是默认扫描标准索引目录。

车包宿主应在 build 前自行验证每个 parcel index 存在于该 root snapshot 中；`PackResourceSnapshotter` 一类宿主实现通常在切片时做这项检查。

关闭 `SnapshotContentSource` 会关闭它拥有的 snapshot。调用方不得再把同一 snapshot 交给第二个 owner 关闭；需要多个消费者时先从 fixed source 分别 capture。

## ResourceSources 适配器

### 目录

```java
ResourceSource live = ResourceSources.directory(
        descriptor,
        directory,
        InputPolicy.DEFAULT
);
```

`directory` 在构造时检查根目录和整棵树：

- 根及后代不得是 symbolic link；
- 只允许 directory 和 regular file；
- 节点数量有上限；
- capture 前后各枚举/读取一次，并比较文件 descriptor/revision，检测捕获期间变化。

它通过 `DirectoryVfs` 读取。该 VFS 构造可能先扫描或哈希现有树，超限检查不能撤回这部分前置成本；对不可变启动输入应尽早 `ResourceSources.fixed(...)`。

### 只读、非默认 NIO 根

```java
ResourceSource source = ResourceSources.readOnlyDirectory(
        descriptor,
        unionFilesystemRoot,
        InputPolicy.DEFAULT
);
```

该适配器用于不支持创建目录或 `NOFOLLOW_LINKS` open option 的只读 provider，例如 NeoForge 展开 mod 的 union filesystem。它仍拒绝 symbolic link 和特殊文件，并做两次 capture 一致性检查。

### Mod 根

```java
ResourceSource source = ResourceSources.mod(
        descriptor,
        pathFromFindResource,
        InputPolicy.DEFAULT
);
```

选择规则：

- 非目录的普通文件按 archive 处理；
- 默认文件系统目录走 `directory`；
- 非默认文件系统目录走 `readOnlyDirectory`。

因此展开 mod、普通 JAR 和 union filesystem 不再要求宿主按显示路径猜 backend。

### Archive

```java
ResourceSource source = ResourceSources.archive(
        descriptor,
        zipOrJar,
        InputPolicy.DEFAULT
);
```

v2 archive 入口会在限额内复制归档，验证 raw central directory，再 inflate 每个正文并按实际字节计数。它拒绝：

- 非 regular file、symlink、加密、分卷归档；
- ZIP64；
- raw path 或 logical path 重复；
- file/directory 冲突；
- Unix symlink、device、FIFO 等特殊 entry；
- 超出 JSON、单文件、总量或文件数预算；
- entry size/CRC 不匹配。

归档会被捕获两次并比较 descriptor/revision，避免静默接受捕获期间被替换或修改的包。

### VFS

`ResourceSources.vfs(descriptor, files, owned, sourcePrefix, logicalPrefix, policy)` 为宿主已有 `VirtualFileSystem` 建立有界快照。`sourcePrefix` 是 URI 中的根内路径，`logicalPrefix` 是注册视图中的路径前缀；两者不能混用。除非 VFS 本身已经具备严格 archive/link 校验，通用 VFS wrapper 不会凭空获得这些证明。

## InputPolicy 与额外限额

`InputPolicy.DEFAULT`：

| 限额 | 默认值 |
| --- | --- |
| JSON 单文件 | 8 MiB |
| 普通单文件 | 256 MiB |
| 一个 snapshot/root 总量 | 1 GiB |
| 文件数 | 50,000 |
| JSON 深度 | 64 |
| 内存转私有 spool 阈值 | 1 MiB |

构造时要求正数、`maxJsonBytes <= maxFileBytes <= maxTotalBytes`，深度在 1–256 之间，spool 阈值不超过 `Integer.MAX_VALUE`。

限额属于一个 `ResourceSnapshot.Builder`，也就是一个具体 root。宿主若把整包拆成 main、parcel 和 shared roots，应额外按整个 pack 汇总预算；多个合法 root 不会自动共享这 1 GiB。

`StrictResourceJson` 还会限制 JSON 文本 8 MiB、节点 100,000 个；输入在进入该解码器前仍应受 `InputPolicy` 约束，避免先无界读入。

## 流、租约与关闭

```java
try (InputStream input = source.requireOpen(path);
     Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
    // read while the source is open
}
```

- `open` 对缺失路径返回 empty；
- `requireOpen` 对缺失路径抛 `SourceAccessException(NOT_FOUND)`；
- `SnapshotContentSource` 关闭后，`open`、`exists`、`files` 都报 `IllegalStateException`；
- pipeline 不关闭 `ContentSource`；
- 标准 definition 在 prepare 中复制解析结果，因此通常可在 build 前关闭 ContentSource；
- 自定义 definition 若延迟读取文件，必须持有并关闭自己的 stream/lease。

## 严格 JSON

`DataDrivenBuildPipeline` 在 STRICT 中先用严格 UTF-8 decoder，再调用 `StrictResourceJson.parse`：

- 拒绝 malformed UTF-8；
- 关闭 Gson lenient mode，拒绝注释、单引号和尾逗号；
- 要求整份文件恰好一个 object；
- 拒绝重复键；
- 默认深度 64、节点 100,000；
- manifest、register content 和 reload 预检都使用这条路径。

LEGACY 使用 `JsonParser.parseString`，保留宽松兼容。运行期 `ReloadOrchestrator` 仍通过 Minecraft `ResourceManager` 和 `JsonParser` 读取当前包栈，并不自动继承 provider 的 STRICT 字节快照；pipeline 的 reload 预检只能证明冻结来源中的 schema，最终 `/reload` 仍以 MC 当前选中的包为准。

## 旧 ContentSource 适配器

以下 API 仍在源码中：

```java
ContentSourceFactory.directory(path, descriptor);
ContentSourceFactory.zip(path, descriptor);
ContentSourceFactory.modJar(IModFile, descriptor);
ContentSourceFactory.open(path, descriptor);
```

- `DirectoryContentSource` 固定文件列表，但打开时读取当时文件字节；
- `ZipContentSource` 支持普通归档以及受 `SourceLimits.maxFiles` 约束的 ZIP64 元数据，打开期间保持 ZipFile；
- `ModJarContentSource` 读取 `modFile.getSecureJar().getRootPath()`，不关闭共享 secure JAR filesystem。

这些适配器适合旧入口、导入工具或兼容测试。新注册 provider 若要保证“注册、首次资源发现和 reload 不读取变化后的安装目录”，应改用 core 的 `ResourceSources`、`ResourceSnapshot` 和 `FixedResourceSource`。

## 来源优先级与依赖

共享平台来源按 `ResourceSourceDescriptor.rank` 从低到高处理：

```text
main rank 10
parcel-m1 rank 11
parcel-m2 rank 12
next-pack main rank 13
```

数值越大优先级越高。非注册定义按 priority 选 winner；STRICT 注册定义保留所有候选并检查最终 claims。依赖声明和优先级是两个维度：

- rank 决定覆盖和稳定遍历顺序；
- `dependencies` 决定 owner 是否能读取另一个 owner 的定义和资源；
- 依赖拓扑不会重排 rank，也不会临时提升某个 source。

## 与 MC 资源投影的关系

`ResourceSnapshot` 同时可被注册视图和 MC pack 层使用：

- 注册侧：`SnapshotContentSource`；
- MC 侧：`ApprovedResourcePacks` discovery 使用 `MappedPackResources`，每个 `(sourceId, rootId)` 是一个独立 pack 层。

`MappedPackResources` 为文件持有 snapshot lease，只暴露经宿主路由批准的 `assets/`、`data/`、`pack.mcmeta` 和 `pack.png`。缺少 `pack.mcmeta` 时，投影层可按当前 `PackType` 生成运行时 metadata；这不会修改作者快照。

Approved pack discovery 是独立消费路径，可能重复调用 `capture()`。只有 fixed source 才能保证每次 discovery 都来自启动字节。注册 provider 还应在 strict preflight、依赖闭包和 build 成功后更新单一 approved root 集合，再让资源发现发布相同 roots。

来源优先级不会自动决定 MC pack priority，但两边的 root 列表、order 和 rank 应由同一冻结快照生成。若使用 `MappedPackResources`，不要再用“物化到缓存目录并以普通 Path pack 重新扫描”的方式复制第二套权威内容。

## SourceAccessException

旧 ContentSource 适配器使用：

| Reason | 含义 |
| --- | --- |
| `NOT_FOUND` | 来源或逻辑文件不存在 |
| `ACCESS_DENIED` | 文件系统拒绝访问 |
| `OUT_OF_BOUNDS` | 越界路径的保留错误类型 |
| `SYMLINK_REJECTED` | 根、树节点或 archive entry 是 symlink |
| `DUPLICATE_ENTRY` | raw 或 logical entry 重复 |
| `INVALID_ENTRY` | 特殊文件、错误目录 entry、非法名称等 |
| `LIMIT_EXCEEDED` | 超过文件数或字节限额 |
| `CORRUPT_ARCHIVE` | ZIP 结构或 inflate 失败 |
| `SOURCE_CLOSED` | 关闭后继续 open |
| `IO_ERROR` | 其他 I/O 失败 |

core 的 `ResourceSources` 使用带稳定前缀的 `IOException`，例如 `SOURCE_UNAVAILABLE`、`UNSAFE_PATH`、`DUPLICATE_ENTRY`、`LIMIT_EXCEEDED` 和 `INVALID_ARCHIVE`。宿主报告应保留原始 message 和 source/root 上下文，不要在兼容层把它们压缩成“文件不存在”。
