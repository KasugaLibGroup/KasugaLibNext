# Content Sources, Snapshots, and Security

In v2, registration sources have two layers:

1. `core`'s `ResourceSource` captures a physical source and returns an immutable `ResourceSnapshot`;
2. `data-driven`'s `ContentSource` exposes a concrete root as a manifest and JSON read view.

Production wiring should follow `ResourceSource -> ResourceSnapshot -> ContentSourceFactory.snapshot(...) -> DataDrivenBuildPipeline`. `ContentSourceFactory.directory`, `zip`, `modJar`, and `open`—which open directories or ZIPs directly—remain available for legacy entry points and test compatibility, and no longer represent the v2 fixed-startup-snapshot model.

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

## Platform Identity

`ResourceSourceDescriptor(ownerId, sourceId, rootId, rank, origin)` is the single provider identity in `core`:

| Field | Constraints | Purpose |
| --- | --- | --- |
| `ownerId` | Matches platform ID rule (`[A-Za-z0-9_-][A-Za-z0-9._-]{0,127}`, not `.` or `..`) | Defines ownership, dependency visibility, and access grants |
| `sourceId` | Matches the same rule | One logical host source, typically one pack |
| `rootId` | Matches the same rule | Concrete root within the source |
| `rank` | Non-negative integer | Global resource layer order; higher values take higher priority |
| `origin` | `EXTERNAL`, `MC_MANAGED`, `DERIVED` | Source category and projection restrictions |

Colons are disallowed in platform IDs. A business parcel ID such as `example_railway:m1` cannot be used directly as a `rootId`; the host must maintain a stable mapping such as `parcel-m1`.

`data-driven`'s `SourceDescriptor` retains both the four-argument legacy constructor and the platform-descriptor constructor. When created via `SourceDescriptor.of(ResourceSourceDescriptor, namespaces, label)`:

- `sourceId()` and `ownerId()` match the platform descriptor;
- `rootId()`, `rank()`, and `origin()` delegate directly to the platform descriptor;
- `resource()` returns the underlying platform descriptor;
- `namespaces` is the allowed set of namespaces for registration reads, required to be non-empty and stably sorted;
- `diagnosticLabel` is used for human-readable diagnostic reporting only.

For the legacy four-argument `SourceDescriptor.of(sourceId, ownerId, namespaces)`, `resource()` is `null`. Only for such legacy compatibility sources does the pipeline assign `sourceOrder` by input list position; platform sources always use their explicit `rank`.

## The `ContentSource` Interface

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

`files()` returns the immutable file list fixed when the view was opened, sorted by logical path. `LocatedFile` holds the descriptor, `LogicalPath`, size, and—when backed by a platform snapshot—the `ResourceUri`, SHA-256 revision, and tags.

By default, `indexes()` matches only files under declared namespaces at:

```text
data/<namespace>/kasuga_lib/data_driven/<name>.json
```

Only direct children of `data_driven/` are matched, without recursion. The namespace segment in `data/<namespace>` must appear in `SourceDescriptor.namespaces`.

`ContentSource` is a read protocol, not a sandbox. While path traversal and archive layout are constrained by the implementations, handlers, JSON codecs, and factories still execute inside the host JVM; untrusted packs still require host-side restrictions on dependencies, resource budgets, and side effects.

## Scene Indexing, `main`, and `parcel` Roots

In these pages, "scene indexing" refers to selecting which roots participate in the current registration scene and which entry manifests each root exposes. It is a composition convention, not a separate Java or JSON type.

At the platform level, a pack typically maps as:

```text
pack owner/source
├─ main       (lower rank; uses default indexes() or host-declared pack root entries)
├─ parcel-m1  (higher rank; exposes only parcel.json.indexes)
├─ parcel-m2  (even higher rank; exposes only parcel.json.indexes)
└─ shared     (host-defined shared root, still with its own unique rank and index set)
```

`main`, `parcel-*`, and `shared` are host conventions. KasugaLib requires only that:

- Each `(sourceId, rootId)` is unique within a single `prepare` call;
- Each root's `rank` is non-negative and unique across all roots in that `prepare` call;
- `rootId` contains no colons;
- Registration, MC resource discovery, and reload consumers reference the same frozen root;
- Disabled roots are omitted from both the `ContentSource` list and MC `ApprovedResourcePacks` registration.

Ranks must be allocated in global order across all packs. Restarting from 0 for each pack causes `ROUTING_CONFLICT` on shared paths and duplicate-rank errors in the pipeline. Dependency topology governs preparation and reference visibility, and must never reorder ranks.

## `ResourceSnapshot`

The output of `ResourceSnapshot.capture()` is an immutable byte collection:

- Each file retains its captured bytes, size, detected type/subtype, SHA-256 revision, and logical path;
- Files up to `InputPolicy.memoryThresholdBytes` are kept in memory;
- Larger files are written to randomly named private temporary spool files;
- Per-JSON, per-file, total-byte, and file-count budgets are enforced byte-by-byte during capture;
- A snapshot forbids duplicate `ResourceUri`s, duplicate logical paths, and file/directory prefix conflicts.

A `LocatedResourceFile` is identified by its concrete provider URI:

```text
ksg://<source>/roots/<root>/<path>[#<child>]
```

When a `childKey` is present, the file descriptor's `origin` becomes `DERIVED` while `rank`, `owner`, `source`, and `root` inherit from the parent provider; `parentUri()` returns the URI without the child key. Runtime derived outputs therefore preserve provenance without impersonating a new external source.

`readBytes()` reads the full file contents once. `open()` acquires a temporary read lease, and `open(existingLease)` increments the snapshot reference count until the returned stream is closed.

Calling `ResourceSnapshot.close()` retires the snapshot:

- No new leases can be acquired afterward;
- Existing valid leases and open streams can still finish reading;
- Memory buffers are cleared and private spool files are deleted only when the final reference releases;
- `close()` is idempotent.

`ResourceSnapshot.Builder.copy(file)` preserves the original URI, logical path, type, subtype, and tags while re-capturing bytes and computing the revision. It accepts only files with the exact same `ownerId`, `sourceId`, and `rootId`, preventing foreign provider content from being re-attributed under another identity.

## `FixedResourceSource`

`ResourceSources.fixed(descriptor, liveSource, policy)` immediately executes `liveSource.capture()` on creation and then closes `liveSource`. The returned `FixedResourceSource` retains this baseline snapshot.

On every subsequent `fixed.capture()`:

1. A new `ResourceSnapshot.Builder` is created under the same descriptor;
2. `Builder.copy` copies every file from the baseline;
3. An independent, closeable snapshot is returned.

Consequently:

- Modifying the original directory or archive after pinning does not affect later reads;
- Closing one consumer's snapshot never causes `STALE_REFERENCE` in another consumer;
- Closing the `FixedResourceSource` itself causes future `capture()` calls to fail;
- Closing the fixed source retires the baseline, while snapshots already handed out keep independent reference counts and remain readable.

If the baseline contains files from a different `ownerId`, `sourceId`, or `rootId`, capture fails rather than re-labeling a foreign provider.

Every discovery pass in `ApprovedResourcePacks` and every `prepare` cycle calls `capture()`. Passing a fixed source ensures all consumers read the startup bytes; passing a live `ResourceSources.directory` or `archive` source instead re-reads the install location.

## `SnapshotContentSource`

`ContentSourceFactory.snapshot(...)` projects a `ResourceSnapshot` into a registration view and transfers ownership of that snapshot to the returned `SnapshotContentSource`:

```java
ContentSource source = ContentSourceFactory.snapshot(
        snapshot,
        resourceDescriptor,
        namespaces,
        diagnosticLabel,
        declaredIndexes // optional
);
```

Construction checks that:

- Every file in the snapshot belongs to the supplied `ownerId`, `sourceId`, and `rootId`;
- No logical path is duplicated;
- Files are sorted by path and expose URI, revision, tags, and size;
- The descriptor is built via `SourceDescriptor.of(resourceDescriptor, namespaces, label)`.

`declaredIndexes` has narrow semantics: it replaces the candidate set returned by `indexes()`, keeping those listed paths that exist in the snapshot sorted by path. It does **not**:

- Delete undeclared files from the snapshot;
- Restrict content paths opened later by `open()`;
- Automatically verify that declared indexes sit inside the standard directory;
- Automatically emit diagnostics for missing declared index paths.

Passing an empty collection declares "explicitly zero indexes", suited for roots providing ordinary assets/data only; passing `null` restores standard index-directory discovery.

Train-pack hosts should verify before building that every parcel index exists in its root snapshot; host components such as a pack resource snapshotter typically perform this check during slicing.

Closing `SnapshotContentSource` closes the snapshot it owns. Callers must not pass the same snapshot instance to a second owner to close; capture separate snapshots from the `FixedResourceSource` when multiple consumers need independent lifetimes.

## `ResourceSources` Adapters

### Directory

```java
ResourceSource live = ResourceSources.directory(
        descriptor,
        directory,
        InputPolicy.DEFAULT
);
```

`directory` inspects the root and the entire tree:

- Neither the root nor any descendant may be a symbolic link;
- Only directories and regular files are permitted;
- Entry count is capped;
- Each `capture()` enumerates/reads twice and compares file descriptors and revisions to detect mutations mid-capture.

It reads through `DirectoryVfs`. Constructing that VFS may scan or hash the existing tree up front, so budget checks cannot undo that initial I/O cost; pin immutable startup inputs early with `ResourceSources.fixed(...)`.

### Read-Only Non-Default NIO Roots

```java
ResourceSource source = ResourceSources.readOnlyDirectory(
        descriptor,
        unionFilesystemRoot,
        InputPolicy.DEFAULT
);
```

This adapter handles read-only filesystem providers that do not support directory creation or `LinkOption.NOFOLLOW_LINKS` on stream open, such as NeoForge's union filesystem for expanded mods. It still rejects symbolic links and special files and performs the two-pass capture consistency check.

### Mod Root

```java
ResourceSource source = ResourceSources.mod(
        descriptor,
        pathFromFindResource,
        InputPolicy.DEFAULT
);
```

Dispatch rules:

- Non-directory regular files use `archive`;
- Default-filesystem directories use `directory`;
- Non-default-filesystem directories use `readOnlyDirectory`.

Expanded mods, ordinary JARs, and union filesystems therefore work without requiring the host to guess the backend from the path string.

### Archive

```java
ResourceSource source = ResourceSources.archive(
        descriptor,
        zipOrJar,
        InputPolicy.DEFAULT
);
```

The v2 `archive` entry copies the archive within size limits, validates the raw central directory, and inflates each entry while counting actual bytes. It rejects:

- Non-regular files, symlinks, encrypted archives, and split archives;
- ZIP64 archives;
- Duplicate raw paths or logical paths;
- File/directory path conflicts;
- Special Unix entries such as symlinks, devices, or FIFOs;
- Exceeding JSON, single-file, total-byte, or file-count budgets;
- Entry size or CRC mismatches.

The archive is captured twice and descriptors/revisions are compared so a package modified mid-capture is never accepted silently.

### VFS

`ResourceSources.vfs(descriptor, files, owned, sourcePrefix, logicalPrefix, policy)` captures a bounded snapshot from a host's existing `VirtualFileSystem`. `sourcePrefix` is the path inside the VFS root for the URI, while `logicalPrefix` is the path prefix exposed in the registration view; keep the two separate. Unless the VFS itself already performs strict archive/link validation, wrapping a generic VFS does not synthesize those guarantees.

## `InputPolicy` and Limits

`InputPolicy.DEFAULT`:

| Limit | Default |
| --- | --- |
| Single JSON file (`maxJsonBytes`) | 8 MiB |
| Single regular file (`maxFileBytes`) | 256 MiB |
| Total per snapshot/root (`maxTotalBytes`) | 1 GiB |
| File count (`maxFiles`) | 50,000 |
| JSON nesting depth (`maxJsonDepth`) | 64 |
| Memory-to-spool threshold (`memoryThresholdBytes`) | 1 MiB |

Construction requires positive counts/sizes, `maxJsonBytes <= maxFileBytes <= maxTotalBytes`, depth between 1 and 256, and a spool threshold up to `Integer.MAX_VALUE`.

Budgets apply per `ResourceSnapshot.Builder`—that is, per concrete root. If a host splits a pack across `main`, `parcel`, and `shared` roots, it should enforce pack-wide budgets explicitly, because separate valid roots do not share a single 1 GiB cap.

`StrictResourceJson` also enforces an 8 MiB text limit and a 100,000-node limit; inputs should still be bounded by `InputPolicy` before reaching that parser so unbounded reads cannot happen first.

## Streams, Leases, and Closing

```java
try (InputStream input = source.requireOpen(path);
     Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
    // read while the source is open
}
```

- `open` returns `Optional.empty()` for missing paths;
- `requireOpen` throws `SourceAccessException(NOT_FOUND)` for missing paths;
- Once a `SnapshotContentSource` is closed, `open`, `exists`, and `files` throw `IllegalStateException`;
- The pipeline never closes `ContentSource` instances;
- Standard definitions copy their parsed data during `prepare`, so `ContentSource` instances can usually be closed before `build`;
- Custom definitions that defer file reads must acquire and close their own streams/leases.

## Strict JSON

In STRICT mode, `DataDrivenBuildPipeline` decodes bytes with a strict UTF-8 decoder first and then calls `StrictResourceJson.parse`:

- Rejects malformed UTF-8;
- Disables Gson lenient mode, rejecting comments, single quotes, and trailing commas;
- Requires the file to contain exactly one JSON object;
- Rejects duplicate keys;
- Enforces default limits of 64 nesting levels and 100,000 nodes;
- Applies this path uniformly to manifests, registration content, and reload preflight.

LEGACY mode uses `JsonParser.parseString` for tolerant compatibility. Note that runtime `ReloadOrchestrator` reads from Minecraft's `ResourceManager` using Gson's `JsonParser` rather than inheriting the provider's STRICT byte snapshot; pipeline reload preflight validates schema in the frozen sources, whereas runtime `/reload` reads whichever packs MC selects.

## Legacy `ContentSource` Adapters

The following APIs remain in the codebase:

```java
ContentSourceFactory.directory(path, descriptor);
ContentSourceFactory.zip(path, descriptor);
ContentSourceFactory.modJar(IModFile, descriptor);
ContentSourceFactory.open(path, descriptor);
```

- `DirectoryContentSource` fixes the file list on open, but reads live file bytes when streams are opened;
- `ZipContentSource` supports ordinary archives and ZIP64 central-directory metadata bounded by `SourceLimits.maxFiles`, keeping the `ZipFile` handle open until closed;
- `ModJarContentSource` reads `modFile.getSecureJar().getRootPath()` without closing the shared secure JAR filesystem.

These adapters are suitable for legacy entry points, import tools, or compatibility tests. New registration providers that must guarantee registration, initial resource discovery, and reload never observe a changing install directory should use `core`'s `ResourceSources`, `ResourceSnapshot`, and `FixedResourceSource`.

## Source Priority and Dependencies

Shared platform sources are processed in ascending `ResourceSourceDescriptor.rank` order:

```text
main rank 10
parcel-m1 rank 11
parcel-m2 rank 12
next-pack main rank 13
```

Larger values have higher priority. Non-registration definitions select a winner by priority (unless using `OVERLAY`), while STRICT registration definitions retain all candidates and check final claims. Dependencies and priority are orthogonal dimensions:

- `rank` determines override order and deterministic traversal order;
- `dependencies` determines whether one owner can reference definitions and resources owned by another owner;
- Dependency topology never reorders ranks or promotes a source.

## Relationship to MC Resource Projection

The same `ResourceSnapshot` can feed both the registration view and MC pack layers:

- Registration side: `SnapshotContentSource`;
- MC side: `ApprovedResourcePacks` discovery via `MappedPackResources`, where each `(sourceId, rootId)` becomes a separate pack layer.

`MappedPackResources` holds snapshot leases for its files and exposes only host-routed `assets/`, `data/`, `pack.mcmeta`, and `pack.png` paths. When `pack.mcmeta` is absent, the projection layer synthesizes runtime metadata for the current `PackType` without altering the author's snapshot.

Approved pack discovery is an independent consumer path that may invoke `capture()` repeatedly. Only a `FixedResourceSource` guarantees that every discovery uses startup bytes. Registration providers should update the single approved-root set only after strict preflight, dependency closure, and `build` succeed, before resource discovery publishes those roots.

Source priority does not set Minecraft's resource pack order automatically, so both the root list, order, and ranks should derive from the same frozen snapshot selection. When using `MappedPackResources`, avoid materializing a second authoritative directory copy on disk to re-scan as a plain path pack.

## `SourceAccessException`

Legacy `ContentSource` adapters report failures via `SourceAccessException.Reason`:

| Reason | Meaning |
| --- | --- |
| `NOT_FOUND` | Source or logical file does not exist |
| `ACCESS_DENIED` | Filesystem denied access |
| `OUT_OF_BOUNDS` | Reserved error reason for out-of-bounds paths |
| `SYMLINK_REJECTED` | Root, tree node, or archive entry is a symbolic link |
| `DUPLICATE_ENTRY` | Duplicate raw or logical entry |
| `INVALID_ENTRY` | Special file, malformed directory entry, invalid name, etc. |
| `LIMIT_EXCEEDED` | File count or byte budget exceeded |
| `CORRUPT_ARCHIVE` | Invalid ZIP structure or inflate failure |
| `SOURCE_CLOSED` | `open` called after `close()` |
| `IO_ERROR` | Other I/O failure |

`core`'s `ResourceSources` throws `IOException` with stable message prefixes such as `SOURCE_UNAVAILABLE`, `UNSAFE_PATH`, `DUPLICATE_ENTRY`, `LIMIT_EXCEEDED`, and `INVALID_ARCHIVE`. Host error reporting should preserve the original message and source/root context rather than collapsing them into a generic "file not found".
