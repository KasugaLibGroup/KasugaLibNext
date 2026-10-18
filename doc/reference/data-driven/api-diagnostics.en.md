# Data-Driven Diagnostics API (Diagnostics, Commands, and Error Classification)

## 6. The Diagnostics Facade

`diagnostics/Diagnostics.java` is the unified diagnostics outlet. Errors are addressed along two dimensions that never mix.

### 6.1 Dimensions

mod dimension: buckets keyed by mod id, which are the existing `JsonTreeBuilder` buckets. `report(String, Throwable)` / `errors(String)` delegate to them verbatim, so the contract "assert that `JsonTreeBuilder.getLoadingErrors(modId)` is empty" keeps holding.

domain/source-key dimension: buckets keyed by `(Domain, key)`, for failures that "belong to a source rather than a mod". **Producers already write to this dimension**: the reload domain uses `Domain.RELOAD_DATA`, key = namespace (both `ReloadOrchestrator` and `FsmReloadHandler` go through it). The remaining domains (`RELOAD_ASSETS`, `CONFIG`) are still reserved.

### 6.2 Method Table

mod dimension:

| Signature | Responsibility | Failure behavior |
|------|------|---------|
| `report(String modId, Throwable error)` (`:64`) | Records one failure into the mod bucket, equivalent to `JsonTreeBuilder.addLoadingError` | No validation |
| `errors(String modId)` (`:73`) | An immutable snapshot of that mod | Returns `List.of()` when there is no bucket |
| `errorsByMod()` (`:78`) | All mods, keys sorted by id, immutable | Returns an empty map when there is no bucket |
| `clear(String modId)` (`:83`) | Clears that mod's bucket only | The other mods and every source bucket are untouched |

domain/source-key dimension:

| Signature | Responsibility | Failure behavior |
|------|------|---------|
| `report(Domain domain, String key, Throwable error)` (`:96`) | Records one failure into some source's bucket | A `null` in any of the three parameters throws `NullPointerException` (an up-front check, treated as a programming error) |
| `errors(Domain domain, String key)` (`:107`) | An immutable snapshot of a single source bucket | Returns `List.of()` when there is no bucket |
| `errors(Domain domain)` (`:116`) | The aggregate over all sources of one domain, sources sorted by key | Returns an empty list when there is no bucket |
| `clear(Domain domain)` (`:125`) | Clears every source bucket of one domain | The other domains and every mod bucket are untouched |

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
| `REGISTRATION` | Content registered by the mod jar, read once during mod construction |
| `RELOAD_DATA` | Reload data from data packs, read from the pack stack on `/reload` |
| `RELOAD_ASSETS` | Pack-stack assets, read on resource reload |
| `CONFIG` | Files under `config/` |

### 6.4 summarize Output Format

```java
public static List<String> summarize()   // Diagnostics.java:143
```

One line per non-empty bucket. The mod bucket format is `<mod>: N error(s)`; the source bucket format is `<DOMAIN>[<key>]: N error(s)`. Mods come first, sorted by id, then `Domain` declaration order, with sources sorted by key. The result is deterministic, so commands and tests can assert it.

### 6.5 Which Dimension Producers Use

Reload-domain errors go through the **domain/source-key dimension** (`Domain.RELOAD_DATA`), key = the namespace the failing file sits in. `ReloadOrchestrator.reportError` (`:566-573`) and each handler's `reportError` / `reportWarning` (for example `FsmReloadHandler.java:118-130`) call `Diagnostics.report(Domain.RELOAD_DATA, namespace, ...)`. Registration-phase errors still go through the mod dimension. The two dimensions are told apart in the output by different labels: mod dimension `[<mod>] <error>`, reload dimension `[RELOAD_DATA[<namespace>]] <error>` (the label is provided by `Diagnostics.bucketLabel`, the same source as the summary line). The reload dimension is cleared per round (`Diagnostics.clear(Domain.RELOAD_DATA)` at the start of `runCycle`, `:208`) and does not accumulate.

## 7. The /kasuga_data errors Command

`DataDiagnosticsCommands.java` is the in-game entry point of the diagnostics facade.

```java
public static LiteralArgumentBuilder<CommandSourceStack> kasugaDataCommand()   // :42
```

Command syntax:

- `/kasuga_data errors`: prints the one-line-per-bucket summary of `Diagnostics.summarize()`, **both dimensions included** — mod dimension `<mod>: N error(s)`, reload dimension `RELOAD_DATA[<namespace>]: N error(s)`. With no errors it prints `Kasuga data: no loading errors recorded`.
- `/kasuga_data errors <mod>`: **lists both dimensions of that mod's errors** — registration phase (mod dimension) as `[<mod>] <throwable>` per entry; reload phase (`Domain.RELOAD_DATA`, key = namespace) as `[RELOAD_DATA[<mod>]] <throwable>` per entry (the label comes from the same source as the summary line, provided by `Diagnostics.bucketLabel`). With no errors it prints `Kasuga data: no loading errors for '<mod>'`.

Constraints and registration:

- Permission level 2 (`requires(source -> source.hasPermission(2))`, `:44`). On a dedicated server this command outputs internal loading diagnostics, which counts as administrator information; console level 4 is unaffected.
- `kasugaDataCommand()` does not depend on a dispatcher or an event, so a pure test can register it and then assert the shape of the command tree.
- Auto-discovery: the class carries `@EventBusSubscriber` (`:28`), and once FML scans it, `onRegisterCommands` (`:54`) registers the command tree on `RegisterCommandsEvent`. The registration sits on the COMMON side, because the facade itself is common.
- The return value is the number of lines printed, for the command system and tests to read.

## 12. Error Classification

Go into the error bucket (an ERROR log is written and the failure enters the bucket as an exception — during the registration phase into the mod dimension, through `JsonTreeBuilder.addLoadingError` or `Diagnostics.report(String, …)`; during the reload phase into the `Domain.RELOAD_DATA` source dimension, through `Diagnostics.report(Domain, namespace, …)`):

| Case | Source |
|------|------|
| Index file unreadable or not JSON | `readIndexManifest:188` |
| Unknown top-level field in an index (including the old field name `sources`, see 3.7) | `parseIndexManifest:226-230` |
| Neither index field declared | `parseIndexManifest:240-244` |
| Field is not an array | `readPathArray:256-263` |
| Array element is not a string | `readPathArray:268-274` |
| The same path listed under both domains | `resolveIndexManifests:308-316` |
| No index declares either domain | `resolveIndexManifests:323-326` |
| Index directory cannot be listed | `listIndexFiles:767-771` |
| Illegal content path | `parseSource:396-402` |
| Mod file not found | `parseSource:408-412` |
| Content file missing | `parseSource:423-427` |
| Content file read / JSON exception | `parseSource:434-436` |
| Unknown top-level field in content (including a top-level `block_entities`) | `dispatchContent:475-490` |
| Type field is not an array | `dispatchContent:504-510` |
| Array element is not an object | `dispatchContent:504-516` |
| handler.apply throws | `applyUnchecked:712` |
| Duplicate id conflict | `resolveDuplicates:667-670` |
| Legacy layout directory present | `buildForMod:66-74` |
| buildAll exception for a single mod | `buildAll:171` |
| reload decode/register/validate failure | `ReloadOrchestrator.reportError:566-573`, `FsmReloadHandler.reportError:118-125` |
| reload wrap-up finds a dangling clip reference | `FsmReloadHandler.reportWarning:127-130` |

Log only, never the bucket (downgraded to WARN level):

| Case | Source |
|------|------|
| Factory not found | `RegTypeHandler:71-73` |
| Group reference cycle | `JsonTreeBuilder:823-825` |
| Unknown block property / illegal value | `JsonPropertyParser:50-57` |
| Unknown item property | `JsonItemParser:63` |
| BE parent block not found / BE type unknown | `BlockEntityTypeHandler:76-86` |
| Fallback warning when a duplicate id unexpectedly reaches apply | `RegTypeHandler:101-105` |
| A mod declares no `on_register` (a reload-only mod) | `JsonTreeBuilder:100-104` (INFO) |
| Index directory absent and no legacy layout | `JsonTreeBuilder:74` (DEBUG) |

Notes: `Diagnostics.report` does not rethrow and rejects `null` arguments up front, and the buckets stay readable while loading is under way (each key is synchronized separately). Every error is swallowed into a bucket and the mod keeps starting. The conditional D9 hint shows up in two kinds of message: on the registration side the unknown-field message points at `on_reload` (`JsonTreeBuilder:559-570`; the reload key names are enumerated at runtime from `ReloadHandlerRegistry`); on the reload side the unknown-field message points at `on_register` (`ReloadOrchestrator:439-446`). The reload-domain bucket is **cleared per round** (`Diagnostics.clear(Domain.RELOAD_DATA)` at the start of `runCycle`, `:208`), so repeated `/reload`s of the same broken file do not accumulate.


> Other pages in the data-driven series: [Registration API](api-registration.md) · [Extension API](api-extension.md) · [Diagnostics API](api-diagnostics.md) · [Reload API](api-reload.md) · [Series index](README.md)
