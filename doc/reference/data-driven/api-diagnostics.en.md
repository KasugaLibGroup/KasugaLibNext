# Data-Driven Diagnostics API (Diagnostics, Commands, and Error Classification)

## 6. The Diagnostics Facade

`diagnostics/Diagnostics.java` is the unified diagnostics facade shared by the v1 registration path and the reload domain. Errors are addressed along two dimensions that never mix:

> **v1/reload buckets vs. v2 `BuildDiagnostic`**: `Diagnostics` backs v1 `JsonTreeBuilder` and `ReloadOrchestrator` / `FsmReloadHandler` (and is surfaced by `/kasuga_data errors`). The v2 multi-source `DataDrivenBuildPipeline` instead returns structured `List<BuildDiagnostic>` values on `PreparedContent.diagnostics()` and `BuildResult.diagnostics()` (see section 19), and **does not** write into `Diagnostics`.

### 6.1 Dimensions

- **mod dimension**: buckets keyed by mod id, which are the existing `JsonTreeBuilder` buckets. `report(String, Throwable)` and `errors(String)` delegate to them verbatim, keeping "assert `JsonTreeBuilder.getLoadingErrors(modId)` is empty" intact.
- **domain/source-key dimension**: buckets keyed by `(Domain, key)`, for failures that belong to a source rather than a mod. **Reload producers already write here**: the reload domain uses `Domain.RELOAD_DATA` keyed by namespace (`ReloadOrchestrator` and `FsmReloadHandler`). `RELOAD_ASSETS` and `CONFIG` remain reserved.

### 6.2 Method Table

mod dimension:

| Signature | Responsibility | Failure behavior |
|------|------|---------|
| `report(String modId, Throwable error)` (`:64`) | Records one failure into the mod bucket, equivalent to `JsonTreeBuilder.addLoadingError` | No validation |
| `errors(String modId)` (`:73`) | Immutable snapshot for that mod | Returns `List.of()` when empty |
| `errorsByMod()` (`:78`) | All mods, keys sorted by id, immutable | Returns an empty map when empty |
| `clear(String modId)` (`:83`) | Clears that mod's bucket only | Other mods and source buckets are untouched |

domain/source-key dimension:

| Signature | Responsibility | Failure behavior |
|------|------|---------|
| `report(Domain domain, String key, Throwable error)` (`:96`) | Records one failure into a source bucket | Throws `NullPointerException` if any argument is `null` |
| `errors(Domain domain, String key)` (`:107`) | Immutable snapshot of one source bucket | Returns `List.of()` when empty |
| `errors(Domain domain)` (`:116`) | Aggregate across all sources of a domain, sorted by key | Returns an empty list when empty |
| `clear(Domain domain)` (`:125`) | Clears all source buckets of one domain | Other domains and mod buckets are untouched |

Shared by both dimensions:

| Signature | Responsibility |
|------|------|
| `clearAll()` (`:130`) | Clears both dimensions |
| `summarize()` (`:143`) | One line per non-empty bucket, see 6.4 |

### 6.3 Domain

```java
public enum Domain { REGISTRATION, RELOAD_DATA, RELOAD_ASSETS, CONFIG }   // Diagnostics.java:40
```

| Value | Meaning |
|----|------|
| `REGISTRATION` | Mod jar registration content (reserved in domain dimension) |
| `RELOAD_DATA` | Reload data from data packs, read from the pack stack on `/reload` |
| `RELOAD_ASSETS` | Pack-stack assets (reserved) |
| `CONFIG` | Files under `config/` (reserved) |

### 6.4 summarize Output Format

```java
public static List<String> summarize()   // Diagnostics.java:143
```

One line per non-empty bucket: mod buckets format as `<mod>: N error(s)`; source buckets format as `<DOMAIN>[<key>]: N error(s)`. Mods come first sorted by id, then `Domain` declaration order with sources sorted by key.

### 6.5 Which Dimension Producers Use

Reload-domain errors go to the **domain/source-key dimension** (`Domain.RELOAD_DATA`) keyed by the namespace owning the file. `ReloadOrchestrator.reportError` (`:617-627`) and handler reporters (`FsmReloadHandler.java:118-130`) call `Diagnostics.report(Domain.RELOAD_DATA, namespace, ...)`. v1 registration errors go to the mod dimension. Labels come from `Diagnostics.bucketLabel` (`:166`): `[<mod>] <error>` vs. `[RELOAD_DATA[<namespace>]] <error>`. The reload dimension is cleared at the start of every `runCycle` (`Diagnostics.clear(Domain.RELOAD_DATA)`, `ReloadOrchestrator.java:208`).

## 7. The /kasuga_data errors Command

`DataDiagnosticsCommands.java` exposes the `Diagnostics` facade in-game (see 19.2 for `/kasuga_proxy list`).

```java
public static LiteralArgumentBuilder<CommandSourceStack> kasugaDataCommand()   // DataDiagnosticsCommands.java:45
```

Command syntax:

- `/kasuga_data errors` (`printSummary`, `:63`): prints the per-bucket `Diagnostics.summarize()` summary covering both dimensions, or `Kasuga data: no loading errors recorded` when clean.
- `/kasuga_data errors <mod>` (`printMod`, `:81`): lists both the mod dimension (`[<mod>] <throwable>`) and the `RELOAD_DATA[<mod>]` source dimension (`[RELOAD_DATA[<mod>]] <throwable>`), or `Kasuga data: no loading errors for '<mod>'`.

Constraints and registration:

- Requires permission level 2 (`requires(source -> source.hasPermission(2))`, `:47`).
- `kasugaDataCommand()` has no dispatcher or event dependency, allowing pure unit tests to verify command shape.
- Auto-registered on COMMON via `@EventBusSubscriber` (`:31`) and `onRegisterCommands` (`:57`); returns the number of printed lines.

## 12. Error Classification (v1 Registration Buckets and Reload Domain)

This table covers diagnostics written to `Diagnostics` buckets or downgraded to logs (see section 19 for v2 `DataDrivenBuildPipeline` structured diagnostics).

Bucketed errors (logged at ERROR and appended as `Throwable` — mod dimension in v1 registration, `Domain.RELOAD_DATA` in reload):

| Case | Source |
|------|------|
| Index file unreadable or not JSON | `JsonTreeBuilder.readIndexManifest:194-204` |
| Unknown top-level field in an index (including removed `sources`) | `JsonTreeBuilder.parseIndexManifest:231-238` |
| Neither `on_register` nor `on_reload` declared | `JsonTreeBuilder.parseIndexManifest:247-253` |
| Non-array path field or non-string array element | `JsonTreeBuilder.readPathArray:263-279` |
| Same path listed in both `on_register` and `on_reload` (fail-closed) | `JsonTreeBuilder.resolveIndexManifests:330-345` |
| No index declares either domain | `JsonTreeBuilder.resolveIndexManifests:347-352` |
| Index directory cannot be listed | `JsonTreeBuilder.listIndexFiles:783-795` |
| Illegal content path | `JsonTreeBuilder.parseSource:420-426` |
| Mod file not found | `JsonTreeBuilder.parseSource:432-438` |
| Content file missing | `JsonTreeBuilder.parseSource:447-452` |
| Content file read / JSON exception | `JsonTreeBuilder.parseSource:458-461` |
| Unknown top-level field in content (including top-level `block_entities`) | `JsonTreeBuilder.dispatchContent:496-518` |
| Type field is not an array or element is not an object | `JsonTreeBuilder.dispatchContent:523-535` |
| Embedded extraction failure | `JsonTreeBuilder.dispatchContent:554-560` |
| Single-entry `parse` throws | `JsonTreeBuilder.parseAndCollect:613-620` |
| `handler.apply` throws | `JsonTreeBuilder.applyUnchecked:733-742` |
| Duplicate id conflict | `JsonTreeBuilder.resolveDuplicates:688-692` |
| Legacy layout directory present | `JsonTreeBuilder.buildForMod:83` |
| `buildAll` exception for a single mod | `JsonTreeBuilder.buildAll:177-181` |
| Reload decode/register/validate failure | `ReloadOrchestrator.reportError:617-627`, `FsmReloadHandler.reportError:118-121` |
| Reload attempts to load an index not permitted by `ManagedReloadAllowlist` | `ReloadOrchestrator.collectIndex:430-434` |
| Reload wrap-up finds a dangling clip reference | `FsmReloadHandler.reportWarning:127-130` |

Log only, never bucketed (v1 / non-strict fallback):

| Case | Source |
|------|------|
| Missing factory or apply failure in non-strict mode | `RegTypeHandler:162-166, 193-197` |
| Registry group reference cycle (`topoSortGroupDefs` WARN) | `JsonTreeBuilder:907-909` |
| Unknown block property / invalid property value | `JsonPropertyParser:152-159, 181, 191` |
| Unknown item property / parse failure | `JsonItemParser:142-148` |
| BE parent block not found / unknown BE type / exception | `BlockEntityTypeHandler:141-152` |
| Mod declares no `on_register` (reload-only mod) | `JsonTreeBuilder:106-109` (INFO) |
| Index directory absent and no legacy layout | `JsonTreeBuilder:73-77` (DEBUG) |

Notes: Cross-domain D9 hints keep opposite directions on each side — registration points at `on_reload` (`JsonTreeBuilder.reloadDomainHint:580-591`, enumerating `ReloadHandlerRegistry`), while reload points at `on_register` (`ReloadOrchestrator.dispatch:494-501`).

---

## 19. v2 Structured BuildDiagnostic and Proxy Diagnostics

### 19.1 BuildDiagnostic

The v2 multi-source `DataDrivenBuildPipeline` replaces global exception buckets with immutable `pipeline/BuildDiagnostic.java:9-76` records attached to `PreparedContent.diagnostics()` and `BuildResult.diagnostics()`:

```java
public record BuildDiagnostic(
        Severity severity,
        Stage stage,
        Code code,
        @Nullable String sourceId,
        @Nullable String ownerId,
        @Nullable String logicalPath,
        @Nullable String jsonPointer,
        @Nullable String handler,
        String message,
        @Nullable String suggestion
) {
    public boolean blocking() { return severity == Severity.ERROR; }
    public enum Severity { WARNING, ERROR }
    public enum Stage { SOURCE, MANIFEST, PARSE, SELECTION, VALIDATION, CLAIMS, APPLY, FINALIZE }
    public enum Code { ... }
}
```

All 25 `BuildDiagnostic.Code` constants grouped by `Stage`:

| `Stage` | `Code` values | Meaning |
|---|---|---|
| `SOURCE` | `INVALID_SOURCE`, `UNKNOWN_DEPENDENCY`, `SOURCE_FILE_MISSING`, `SOURCE_READ_FAILED` | Duplicate `(sourceId, rootId)` or `rank`, unknown dependency owner, missing content file referenced by manifest, or snapshot/I/O read error |
| `MANIFEST` | `MANIFEST_INVALID`, `MANIFEST_EMPTY`, `INVALID_PATH` | Non-object manifest, D1–D6 violation (including removed `sources` key or dual-listed path), invalid `LogicalPath`, or zero declared `on_register`/`on_reload` paths |
| `PARSE` | `CONTENT_INVALID`, `UNKNOWN_TYPE`, `PARSE_FAILED`, `IDENTITY_FAILED`, `MISSING_IDENTITY`, `HANDLER_NOT_STRICT_READY` | `StrictResourceJson` syntax/duplicate-key error, unknown top-level key, `on_reload` preflight decode error, `TypeHandler.parse` failure, `resolveIdentity` throw or null/blank return, or used handler failing strict capability checks |
| `VALIDATION` | `INVALID_IDENTITY`, `UNKNOWN_FACTORY`, `UNKNOWN_REGISTRY_GROUP`, `MISSING_REFERENCE`, `REFERENCE_NOT_DECLARED`, `REFERENCE_AMBIGUOUS`, `GROUP_CYCLE`, `VALIDATION_FAILED` | Bare/invalid `ResourceLocation`, missing factory, unknown `registry_group`, missing or undeclared cross-definition reference, ambiguous visible matches (`findAllVisible.size() > 1`), `parentDefinition` cycle (`ERROR` in `STRICT`, `WARNING` in `LEGACY`), or strict property/param check failure |
| `CLAIMS` | `CLAIM_CONFLICT`, `CLAIM_FAILED` | Two definitions competing for the same `(registry, id)`, collision with `reservedRegistryClaims`, or `TypeHandler.claims` exception |
| `APPLY` / `FINALIZE` | `APPLY_FAILED`, `FINALIZER_FAILED` | Runtime exception thrown by `apply` or `BuildFinalizer.afterBuild` (also transitioning `BuildResult.status()` to `Status.FAILED`) |

### 19.2 `/kasuga_proxy list` and `ProxyDebug`

For runtime inspection of proxy-rendered hosts (including `proxy_model` / `proxy_model_be`), `modules/modelling` exposes:

- **In-game command `/kasuga_proxy list`** (`ProxyCommands.java:40-76`, auto-registered via `@EventBusSubscriber` on both sides):
  - **Permission level 0** (`source.hasPermission(0)` — usable in dev worlds without cheats).
  - Prints `[proxy] instances=N live=L mounted=M`, followed by up to 64 (`MAX_LINES`) `ProxyInstance.describe()` lines (`ProxyInstance.java:410-420`):
    ```text
    <identity|<unresolved>> side=client|server|? element=<class> stage=NEW|LIVE|FAILED|RELEASED machine=<id> model=<loc> [parts=M/T] mounted=<bool> lastTick=never|<gameTime>
    ```
  - Field interpretation: `NEW` = not yet ticked, `FAILED` = `ElementProxy#configure` returned `null` or threw, `LIVE` with `mounted=false` = client-side model publishing or loader rejection, `lastTick=never` = proxy is not being ticked.
- **Verbose tracing flag `-Dkasuga.proxy.debug=true`** (`ProxyDebug.java:20-49`): `[proxy]` trace lines normally emit at DEBUG (`logs/debug.log`); setting `ProxyDebug.PROPERTY` promotes them to INFO (`latest.log` and console).

> Other pages in the data-driven section: [Registration API](api-registration.md) · [Extension API](api-extension.md) · [Diagnostics API](api-diagnostics.md) · [Reload API](api-reload.md) · [Schema Reference](schema.md) · [Block State Properties](block-state-properties.md) · [Section index](README.md)
