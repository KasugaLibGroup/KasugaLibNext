# Unified Resource Platform and Script Tickets

Built on top of the existing VFS, this platform provides stable identity, immutable content capture, directory routing, generational publication, and revocable access. The Java host approves sources and Entry Points; JSON only describes routing for already-approved sources—it never mounts paths, downloads files, or registers Java implementations.

## Identity and Content

Concrete identities take the form `ksg://<source>/roots/<root>/<path>#<child>`. `source` and `root` are assigned by the host and cannot be synthesized on the fly from absolute paths, enum ordinals, or pack display names. URIs do not carry scope or generation; runtime handles bind those two separately.

Paths preserve Unicode, spaces, and case; serialization uses canonical UTF-8 percent-encoding. Absolute paths, backslashes, empty segments, `.` / `..`, control characters, queries, malformed UTF-8, and encoded separators are rejected. To reject double-decoding, raw names also cannot contain `%` followed immediately by two hexadecimal digits. Use `ResourceUri.file(...)` / `.child(...)` instead of hand-assembling URI strings.

For example, an original `模型/列车 A.bbmodel` and its sub-resource `#texture/uuid/…` each have distinct identities. `MinecraftNameCodec` generates reversible names in the reserved `kasuga_generated` namespace with version prefix `v1/` and hexadecimal UTF-8 encoding of the full URI. The MC adapter prepends `models/` or `textures/` and appends a format suffix. Existing `assets/<namespace>/...` and `data/<namespace>/...` retain their native `ResourceLocation` and must obey vanilla lowercase naming rules.

`ResourceSourceDescriptor` carries owner/source/root, an explicit `rank`, and an origin category. Ranks layer from low to high; equal-rank conflicts on the same logical path fail rather than relying on `HashMap` order. `ResourceCatalog` retains both all providers and the visible winner. Permissions filter providers first before choosing the winner; a winner with an incompatible type fails rather than silently falling back to a lower layer.

`ResourceSources` supports host-approved directories, ordinary ZIP/JAR archives, expanded mod directories, and any existing VFS. It captures actual bytes and computes SHA-256, returning a snapshot only when consecutive captures match; modifying or replacing an archive re-opens it on the next capture. Traversal, duplicate archive entries, file/directory conflicts, symbolic links, special files, encrypted or split ZIPs, and currently ZIP64 are rejected. No filename can trigger automatic network access.

`InputPolicy.DEFAULT` enforces 8 MiB for JSON, 256 MiB per file, 1 GiB total per source, 50,000 files, JSON depth 64, and spooling to a private temporary file above 1 MiB. Actual decompressed and derived bytes also count toward these budgets. Depth is checked by the JSON decoder; the bbmodel adapter uses the passed policy's depth. Sources with cross-file dependencies should still be published by the host via transactions or atomic replacement.

`ResourceTypeDetector` inspects at most 512 bytes: PNG/JPEG/GIF/WebP, WAV/Ogg/FLAC/ID3-tagged MP3, GLB/PMX, and ZIP are classified first by magic number. Text formats without fixed signatures use extension hints only after UTF-8 and control-character checks pass; a JSON prefix can identify extensionless text. A descriptor's `typeEvidence` distinguishes `MAGIC`, `TEXT_PREFIX`, `EXTENSION`, `DECLARED`, and `UNKNOWN`. Signatures are discovery evidence only and never substitute for format decoding, permission checks, or script execution authorization.

## Schema and Structural Routing

Validatable editor schemas live in [`resource-routing.schema.json`](resource-routing.schema.json) and [`resource-template.schema.json`](resource-template.schema.json). At runtime, decoding also checks for duplicate JSON keys, unknown fields, source authorization, template cycles, duplicate IDs, and path safety. Format version is 1.

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

`source_prefix` matches on full path segments. The longest-matching binding is chosen first, then its outputs' event and side are checked; if a child binding has no output for the active side, routing does not fall back to a parent binding. `a/b` does not match `a/bc`. Paths strip `source_prefix` and prepend `target_prefix`; root-level `kasuga.resources.json` is not passed as an ordinary consumer input.

Templates use `id`, a single `extends`, `remove_outputs`, and `outputs`. Inherited outputs are replaced whole by stable output ID; you cannot remove and re-add the same ID, and removing a non-existent ID fails. A single binding can emit explicitly to multiple consumers; duplicate emissions of the same file to the same consumer and target path fail. Maximum template inheritance depth is 32. The template registry is passed by the host to `ResourceRouting.decode`; JSON contains no external `$ref` or remote schema loading.

Events in `ResourceEvent` are `host_bootstrap` (`HOST_BOOTSTRAP`), `client_startup`, `server_startup`, `resource_reload`, and `remote_connect`; sides are `client`, `server`, `both`, and `none`. `both` is a declaration value—each actual invocation uses one concrete logical side, and server scripts on an integrated server still execute as `server`. `none` is only for consumers that do not depend on an MC context. Declarations cannot exceed the events and sides permitted by the Java `EntryPointDescriptor`.

## Initial MC Discovery and Models

Native pack projections are added during the **`AddPackFindersEvent` / `RepositorySource` phase**. Before initial discovery, the host calls:

```java
ResourceSource source = ResourceSources.directory(
    new ResourceSourceDescriptor("my-mod", "example", "main", 100,
        ResourceSourceDescriptor.Origin.EXTERNAL), approvedDirectory, InputPolicy.DEFAULT);
ResourceRouting routing = ResourceRouting.decode(json, ResourceRouting.builtinTemplates(), Set.of("example"));
AutoCloseable registration = resourceSystem.getApprovedPacks().register("example", List.of(source), routing);
```

`ResourceSystem` already subscribes its finder to the mod bus. Approved directories must be supplied by the host; configuration text is read and explicitly decoded by the host. Optional `ModelResourceSource` wrapping should be done before registration. Each source/root is an independent MC pack layer, and Minecraft's real `ResourceManager` determines the winner and pack stack. The registration handle only unregisters the entry without closing the host source; opened packs and streams use their own leases.

`McManagedResourceView` borrows the MC manager, preserves its real pack stack, and can capture resources as `MC_MANAGED` sources under host-supplied stable pack provenance. It does not close the MC manager or packs. `MC_MANAGED` inputs are forbidden from projecting back into MC to avoid resource discovery cycles.

Non-MC `.bbmodel` files use `ModelResourceSource`: it keeps the original file, extracts embedded PNGs as derived textures with parent URIs, distinguishes same-named textures by texture UUID or explicit index, and generates an MC model copy referencing those textures. Models and textures are prepared before the same discovery pass so existing model scanning, decoding, material, and atlas pipelines receive them without a second reload round. Pixel counts are bounded by a host limit.

| Input | Current integration |
|---|---|
| Native `assets`/`data` models and textures | Retains existing loading paths and native IDs |
| Non-MC `.bbmodel` + embedded PNGs | Full capture, derivation, initial model/atlas load, replacement, and deletion |
| OBJ/PMX/glTF/FMT, etc. | Provides `ResourceModelInput` and text/json/binary `Source` adapters; migrating each decoder's container/external dependencies requires corresponding host adaptation |
| `.bbmodel` external images, non-PNG embedded images | Currently rejected; requires an explicit dependency adapter |

`ResourceModelInput.sibling` allows only relative dependencies within the same concrete source/root; it cannot search across roots or steal dependencies from lower-priority providers. The platform does not build a separate GPU backend.

## Scopes and Publication

Java consumers implement `ResourceEntryPoint`, declaring input types, events, sides, dependencies, and reload support; `prepare(ResourceBatch, InvocationContext)` returns an asynchronous `PreparedConsumerBatch`. The entire plan is validated first, then captured, then prepared in dependency topological order, and finally published on the host's `publishExecutor`, replacing the coordinator's `current` generation atomically. A new request invalidates older unpublished preparations; preparation failures keep the already-published generation intact.

Select the appropriate owner via `ResourceSystem.createClientResourceScope`, `createServerResourceScope(server, id)`, or `createProcessResourceScope`; stopping a server closes only scopes belonging to that server. Call `coordinator.reload(sources, routing, event, side, prepareExecutor, publishExecutor)` to start work. The host is responsible for subscribing to appropriate events or optional VFS watchers and coalescing reload requests; declarative config itself does not create watchers.

`publishExecutor` must remain alive until the last generation lease is released. `ResourceGeneration.lease()` retains old content; retirement immediately revokes script calls, and once the last lease closes, consumers and snapshots are closed on `publishExecutor`. `disposal()` reports the final release result. A `ResourceSnapshot` file/stream lease independently retains captured bytes and rejects new reads after close. Calling `cancel` on the future returned by `reload` only abandons observation; it does not skip internal cleanup or roll back an already-committed publication.

`MinecraftResourceEntryPoint.Projection.openLayers()` is provided for hosts that manage their own MC discovery/reload barrier. The coordinator's atomic publication guarantees a consistent platform view; vanilla MC reload and external side effects of consumer `publish()` still follow their own lifecycles, as the platform cannot undo side effects already performed by arbitrary Java callbacks.

## Script Blocks and Tickets

Data files declare only a reference and expected contract:

```json
{
  "package": "ksg://example/roots/main/scripts/package.toml",
  "block": "increment",
  "side": "server",
  "contract": {"arguments": ["number"], "result": "number"}
}
```

Static decoding calls `ScriptReferenceCodec.decode` or data-driven's opt-in `ScriptBlockReferenceDecoder`, validating structure, logical side, and host visibility without executing scripts. After package startup, scripts register explicitly on the sandboxed `ScriptThread`:

```javascript
ScriptBlocks.register("increment", '{"arguments":["number"],"result":"number"}', value => value + 1);
```

The host registers `ManagedScriptEntryPoint(engineLookup, trustedSourcePredicate, referencePermissionPredicate)` (with an optional `AccessFactory` overload) and routes package files. Each generation uses an independent module registry bound to `scope` / `generation` / `ScriptPackage` / `ScriptEngine` / `ScriptThread`. Only after package preparation completes does `ScriptBlockRegistry.bind(ref, context)` yield a `ScriptBlockHandle`; mismatched names, contracts, or generations fail. Ordinary `module.exports` do not automatically become externally callable blocks. Trust is determined by the host's source policy—never by self-reported fields inside the package.

```java
ScriptTicket ticket = handle.submit(List.of(41)); // copies data and enqueues only; returns immediately
ticket.poll().ifPresent(outcome -> consume(outcome));
ticket.onComplete(callerExecutor, outcome -> consume(outcome));
```

This is a Java implementation of an `io_uring`-style submission/completion token and does not use Linux native `io_uring`. Each target thread has a bounded MPSC ring (default capacity 1024); `ScriptThread` drains on the owner thread with a batch budget of at most 64 requests / 2 ms. The budget is checked between callbacks and cannot preempt a single currently executing function. Arguments and results permit only copied bounded-depth data so engine values or host objects cannot leak out.

Ticket states are `QUEUED`, `RUNNING`, `SUCCEEDED`, `FAILED`, `CANCELLED`, and `REJECTED`; a full ring or invalid arguments reject immediately, and exceptions are returned as a failure type and message. Callers may cancel; generation retirement or scope close immediately cancels all pending tickets, and late results cannot overwrite cancellation. Tickets have no blocking `get`/`join` API; completion callbacks run on the caller's executor, and the MC main thread must never synchronously wait on scripts.

Currently, Javet's general sandbox capabilities are marked `UNVERIFIED`, so the new entry rejects untrusted scripts by default. Untrusted packages may be permitted only once an engine explicitly proves isolation for host classes/reflection, files, network, execution budget, memory, and cancellation; this change does not claim to have completed that sandbox. The legacy script entry remains compatible, with its reload changed to wait asynchronously for retirement rather than joining on the game thread.

An optional `AccessFactory` assigns `ResourceAccess` to a package, giving scripts a data-only `Resources` facade. Grants restrict owner/source/root/prefix and `read`/`query`/`write`/`create`/`delete` operations; reads enforce per-call and total byte budgets. Writes go only into a host-created bounded in-memory `Workspace`, never modifying source packs, arbitrary disk paths, or world saves. Scope retirement revokes access, and the host is responsible for closing the `Workspace`.

## Verification

```sh
./gradlew :modules:core:test :modules:data-driven:test :modules:scripting:test :modules:modelling:modelUnitTest
./gradlew :modules:modelling:test --tests '*ModelResourceSourceTest' :external:slp:javet:test --tests '*ResourceScriptTicketIntegrationTest'
./gradlew :modules:modelling:runClientHeadless -PkasugaClientDirectory="$PWD/build/resource-platform-client" -PkasugaTestResources=true
```

Client smoke verifies the initial model and GPU texture, updated pixels under the same ID, and model/sprite cleanup after deletion from the menu, writes `debug/resource-platform/report.json`, and exits without opening a world. FML integration tests use real packs and managers, and Javet integration tests actually register and execute JS. Pure JVM `modelUnitTest` does not run vanilla metadata integration scenarios tagged `minecraft-resource-integration`; those run under normal `test`.
