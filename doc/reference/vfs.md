# 虚拟文件系统（VFS）

`modules/core` 提供 `VirtualFileSystem`、逻辑 `VfsPath` 值、可写目录与内存后端、只读 ZIP/JAR 访问、叠加解析、挂载路由、事务以及变更订阅。VFS 包不依赖 Minecraft 客户端。`VfsPackResourcesBridge` 将 VFS 暴露给 Minecraft；`VfsScopedPackResourcesAdapter` 提供既有的作用域资源接口。

## 读取、写入与元数据

```java
try (DirectoryVfs files = new DirectoryVfs("authoring", authoringDirectory)) {
    VfsPath model = VfsPath.of("assets/demo/models/train.json");
    files.writeString(model, json);
    String contents = files.readString(model);
    VfsNodeStat metadata = files.stat(model);
    VfsNodeStat fingerprint = files.statWithHash(model);
}
```

`stat()` 是元数据查询，可以省略可选的 SHA-256；需要当前内容摘要时请使用 `statWithHash()`。内存文件具有不可变的内容版本，因此已有读取流在后续写入期间仍保持稳定。`readBytes()` 返回防御性副本。

目录的 `openWrite()` 先流式写入隐藏临时文件，在成功关闭时发布；发布前会再次校验目标路径，随后执行原子替换，并在需要时回退到同目录暂存替换。内存输出使用不可变分块。两者都会避免累积多份完整大小的临时数组。

逻辑路径使用 `/` 分隔，拒绝路径穿越段、内部空段、控制字符、反斜线与冒号。物理目录解析还会额外校验可移植文件名及已存在的符号链接祖先目录。归档中的逻辑路径可以寻址合法的 Minecraft 名称（如 `con.json`）；这些名称不必是物理 Windows 合法文件名。既有的作用域目录适配器继续接受安全的 `./` 工作区路径。

## 挂载与事务

```java
try (InMemoryVfs runtime = new InMemoryVfs("runtime");
     DirectoryVfs pack = new DirectoryVfs("pack", packDirectory);
     MountTableVfs files = new MountTableVfs("assets")) {
    files.mount(VfsPath.of("runtime"), runtime);
    files.mount(VfsPath.of("packs/train"), pack);
    try (VfsWriteTransaction tx = files.beginTransaction()) {
        tx.putString(VfsPath.of("runtime/index.json"), indexJson);
        tx.putString(VfsPath.of("packs/train/model.json"), modelJson);
        tx.commit();
    }
}
```

挂载按最长逻辑前缀解析，包含自动合成的父目录。返回的挂载句柄仅拥有该次挂载条目本身；关闭旧句柄不会移除挂载到同一路径的新替代挂载。目录移动会保留空后代目录，包括跨后端移动。活跃挂载点及其祖先目录受保护，不能被移动操作删除或替换。

事务先暂存输入，再按稳定顺序预留参与方后端，应用全部操作，并在调用变更监听器之前释放所有预留。准备或应用失败时，会恢复受影响的内容和内部监控基线，且不发出成功通知。目录恢复会保留前像，包括空目录和权限。若文件系统阻止恢复，原始错误会一并报告恢复失败信息并保留备份位置。

`putStream(path, source)` 接受延迟源，其返回的流由事务拥有并关闭。输入会在任何后端预留之前完成物化；在暂存完成前请保持其可读。自定义后端可实现 `stage()` 与可逆的 `prepare()` 参与方。`coordinationKey()` 标识共享的后端预留；不支持协调提交的后端会在应用任何参与方之前失败。

该协议覆盖进程内校验与 I/O 失败，不提供断电恢复或对外部进程重写同一文件的隔离保护。

## 变更与资源集成

`watch(prefix, listener)` / `watchAll(listener)` 返回可关闭的订阅。内部写入发出 `INTERNAL_WRITE`；目录轮询发出 `EXTERNAL_DISK`。挂载表事务在所有后端锁释放后发出一次合并通知。回调同步执行，因此耗时的重载工作应由消费者自行调度。

目录轮询保持严格的完整内容 SHA-256 校验，包括大小相同且恢复了时间戳的变更。它使用流式缓冲区并在状态锁之外执行扫描，发布前再校验内部写入版本号。在持续写入竞争下会重试，并在必要时回退到原有的加锁扫描以保证进展。因此完整校验的开销仍与文件总内容呈线性关系，不会退化为仅检查元数据的监控器。

`VfsWatchService` 为可选组件，使用单个守护轮询线程并在每次轮询结束后按固定延迟调度。请随其所属生命周期一并关闭该服务及其订阅。`ResourceSystem.getUnifiedVfs()` 暴露进程级挂载表；对于按 `MinecraftServer` 隔离的挂载请使用 `mountScoped(server, ...)` / `registerManagedCloseable(server, ...)`，客户端作用域挂载请使用 `mountClientScoped(...)` / `registerClientManagedCloseable(...)`，避免停止某个服务端时拆毁客户端或其他服务端实例的挂载。只读 ZIP 索引仅在同一个已打开 `ZipFile` 实例的适配器之间共享；新句柄会构建新索引。

`ScopedPackResources.toLogicalPath(...)` 在目录、ZIP（`ScopedVanillaFileResourcePack`）和 VFS（`VfsScopedPackResourcesAdapter`）资源包以及 `PackageSystem.scanWorkspace(...)` 中统一剥离 `.` 恒等段，同时仍拒绝 `..` 穿越与空路径段。由 VFS 支持的自定义资源包实现 `VfsBackedPackResources`（包括 `VfsPackResourcesBridge` 与 `modelling` 的 `KsgPackResource`），`ScopedResourceManager` 会在 `PathPackResources` / `FilePackResources` 之前优先适配它。在 `modelling` 中，`GeneratedShaderResourceProvider` 通过 `VfsPackResourcesBridge` 从 `InMemoryVfs` 提供生成的 shader，而 `LiveModelSync` 通过 `DirectoryVfs` 与 `ResourceSystem.mountClientScoped(...)` 读取 `.kasuga-sync.json` / `pack.mcmeta`。

## 验证与诊断基准测试

运行回归测试套件：

```sh
./gradlew :modules:core:test :modules:scripting:test :modules:modelling:modelUnitTest
```

既有验证记录中，106 项 core 测试和 70 项 scripting 测试全部通过且无失败、报错或跳过；`modelling` 单元测试套件同样通过（`GeneratedShaderResourceProviderTest` 与 `LiveModelSyncStateTest` 0 失败/报错/跳过；`modelUnitTest` 共 1196 项测试，0 失败/报错，8 项既有的环境/资产条件测试跳过）。另有一项确定性的优化前后探针比对了 4,600 项内存/目录操作状态，涵盖被拒绝的事务、目录树、文件内容与轮询结果。Windows 宿主机执行与完整 Minecraft GUI 性能尚未在本地实测。

为每个检出版本使用同一 JDK 构建类文件并运行仓库内置的诊断测试程序：

```sh
./gradlew :modules:core:testClasses :modules:core:writeNeoForgeTestClasspath
python3 scripts/benchmark-vfs.py --scenario main
python3 scripts/benchmark-vfs.py --scenario extra
python3 scripts/benchmark-vfs.py --scenario zip-reuse
```

可选 `--scenario` 取值包括 `main`、`extra`、`contention` 与 `zip-reuse`。通过 `--java /path/to/jdk-21/bin/java` 或 `VFS_PERF_JAVA` 选择 JDK。`--repo /path/to/other/checkout` 会使用该检出目录已编译的类文件，同时保留当前测试框架，而 `--output` 指定独立的 JSONL 结果路径。运行基准测试期间不要重新构建被测类目录。优化前实现对应提交 `67ceefe312d52c7c32f776d69ce9e9e0587e3b7c`。

该测试框架使用 3 批预热、9 批测量、G1 垃圾收集器、512 MiB 初始堆 / 1.5 GiB 最大堆、临时合成数据以及预热后的文件系统缓存。JSONL 输出包含每项操作的批次中位数、范围、CPU 时间与 Java 分配量。它是诊断基准测试，而非 JMH 或游戏 FPS 测量；累计分配量也不等同于常驻堆或物理 I/O。

在配备原生 Java 21.0.3 的 Apple M3 Max 上的代表性结果：

| 场景 | 优化前 | 优化后 |
|---|---:|---:|
| 10,000 文件目录树中的单次 1 KiB 事务 | 34.68 ms | 0.46 ms |
| 经由挂载查询 64 MiB 文件类型 | 48.25 ms | 0.026 ms |
| 在两个 10,000 文件内存挂载间移动 1 字节 | 37.88 ms | 0.035 ms |
| 全目录扫描期间并发读取 | 126.87 ms | 0.083 ms |
| 64 MiB 分块目录输出，分配堆内存 | 192 MiB | 0.069 MiB |
| 重新适配同一个已打开的 10,000 文件 ZIP | 11.91 ms | 0.0011 ms |

未变更的 250 MiB 轮询负载在优化前耗时约 122 ms，优化后约 124 ms；分配量从约 253 MiB 降至 1.77 MiB。部分原本极短的写入会增加少量固定开销。以上结果涵盖原本的 78 个场景外加同句柄 ZIP 场景；它们并不意味着所有操作都会变快。读取探针在扫描工作线程消耗 5 ms CPU 时间后启动，并在两个版本上采用相同的观测方法重新运行。
