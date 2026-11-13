# Virtual file systems

`modules/core` provides `VirtualFileSystem`, logical `VfsPath` values, writable directory and memory backends, read-only ZIP/JAR access, overlay resolution, mount routing, transactions, and change subscriptions. The VFS package has no Minecraft client dependencies. `VfsPackResourcesBridge` exposes a VFS to Minecraft; `VfsScopedPackResourcesAdapter` supplies the existing scoped resource interfaces.

## Reading, writing, and metadata

```java
try (DirectoryVfs files = new DirectoryVfs("authoring", authoringDirectory)) {
    VfsPath model = VfsPath.of("assets/demo/models/train.json");
    files.writeString(model, json);
    String contents = files.readString(model);
    VfsNodeStat metadata = files.stat(model);
    VfsNodeStat fingerprint = files.statWithHash(model);
}
```

`stat()` is a metadata query and may omit its optional SHA-256. Use `statWithHash()` when a current content digest is required. Memory files have immutable content versions, so an existing read stream remains stable across subsequent writes. `readBytes()` returns a defensive copy.

Directory `openWrite()` streams into a hidden temporary file and publishes on successful close. It checks the destination again at publication, then performs atomic replacement with a same-directory staging fallback. Memory output uses immutable chunks. Both avoid accumulating multiple full-size temporary arrays.

Logical paths use `/` and reject traversal segments, internal empty segments, control characters, backslashes and colons. Physical directory resolution additionally checks portable filenames and existing symlink ancestors. Archive logical paths can address Minecraft-valid names such as `con.json`; these names need not be valid physical Windows filenames. The legacy scoped directory adapter continues to accept safe `./` workspace paths.

## Mounts and transactions

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

Mounts resolve by longest logical prefix, including synthesized parent directories. The returned mount handle owns that specific mount entry; closing an old handle cannot remove a replacement mounted at the same path. Directory moves preserve empty descendants, including across backends. Active mounts and their ancestors are protected against deletion or replacement by a move.

Transactions stage inputs, reserve participating backends in a stable order, apply all operations, and release every reservation before invoking change listeners. A failed preparation or apply restores affected content and internal watch baselines without a successful notification. Directory recovery retains before-images, including empty directories and permissions. If the filesystem prevents recovery, the original error reports recovery failures and retains the backup location.

`putStream(path, source)` accepts a deferred source whose returned stream is owned and closed by the transaction. Inputs are materialized before any backend reservation; keep them readable until staging completes. Custom backends can implement `stage()` and reversible `prepare()` participants. `coordinationKey()` identifies a shared backend reservation; unsupported coordinated commits fail before applying any participant.

The protocol covers in-process validation and I/O failures. It does not provide power-loss recovery or isolation against external processes rewriting the same files.

## Changes and resource integration

`watch(prefix, listener)` / `watchAll(listener)` return closeable subscriptions. Internal writes emit `INTERNAL_WRITE`; directory polling emits `EXTERNAL_DISK`. A mount-table transaction produces one combined notification after all backend locks are released. Callbacks execute synchronously, so expensive reload work should be scheduled by the consumer.

Directory polling remains a strict full-content SHA-256 check, including same-size changes with restored timestamps. It uses streaming buffers and scans outside the state lock, then validates the internal-write version before publication. Under sustained write contention it retries and eventually reserves the original locked scan for progress. Full verification therefore remains linear in total file content; it does not become a metadata-only watcher.

`VfsWatchService` is optional and uses one daemon polling thread with a fixed delay after each cycle. Close it and its subscriptions with their owning lifecycle. `ResourceSystem.getUnifiedVfs()` exposes a process-level mount table; server-scoped mount handles and watch services can be registered with `registerManagedCloseable()` for shutdown cleanup. Read-only ZIP indexes are shared only between adapters for the same open `ZipFile` instance; a new handle builds a fresh index.

## Validation and diagnostic benchmarks

Run the regression suites:

```sh
./gradlew :modules:core:test :modules:scripting:test
```

The reported validation has 103 core tests and 69 scripting tests passing with no failures, errors or skips. An additional deterministic before/after probe compared 4,600 memory/directory operation states, including rejected transactions, trees, contents and polling results. Windows host execution and full Minecraft GUI performance have not been measured locally.

Build classes and run the checked-in diagnostic harness with the same JDK for each checkout:

```sh
./gradlew :modules:core:testClasses :modules:core:writeNeoForgeTestClasspath
python3 scripts/benchmark-vfs.py --scenario main
python3 scripts/benchmark-vfs.py --scenario extra
python3 scripts/benchmark-vfs.py --scenario zip-reuse
```

Select a JDK with `--java /path/to/jdk-21/bin/java` or `VFS_PERF_JAVA`. `--repo /path/to/other/checkout` uses that checkout's compiled classes while retaining this harness, and `--output` chooses a separate JSONL result. Do not rebuild the measured class directory while a benchmark is running. The pre-optimization implementation is commit `67ceefe312d52c7c32f776d6370848e779760d69`.

The harness uses three warmup batches, nine measurement batches, G1, a 512 MiB initial/1.5 GiB maximum heap, temporary synthetic data, and warm filesystem caches. JSONL includes per-operation batch medians, ranges, CPU time and Java allocations. It is a diagnostic benchmark, not JMH or a game-FPS measurement. Allocation totals are not retained heap or physical I/O.

Representative results on an Apple M3 Max with native Java 21.0.3:

| Scenario | Before | After |
|---|---:|---:|
| One 1 KiB transaction in a 10,000-file tree | 34.68 ms | 0.46 ms |
| File-type query through a mount, 64 MiB file | 48.25 ms | 0.026 ms |
| Move one byte across two 10,000-file memory mounts | 37.88 ms | 0.035 ms |
| Reader overlapping a full directory scan | 126.87 ms | 0.083 ms |
| 64 MiB chunked directory output, allocated heap | 192 MiB | 0.069 MiB |
| Re-adapt the same open 10,000-file ZIP | 11.91 ms | 0.0011 ms |

The unchanged 250 MiB polling workload took about 122 ms before and 124 ms after; allocations fell from about 253 MiB to 1.77 MiB. Some already short writes have additional constant overhead. These results cover the same 78 original scenarios plus the same-handle ZIP scenario; they do not imply every operation becomes faster. The reader probe starts after the scan worker has consumed 5 ms of CPU, and was rerun with that same observation method against both versions.
