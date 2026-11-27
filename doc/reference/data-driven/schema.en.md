# Data-driven JSON format specification (schema)

> Type: Reference (information-oriented, for looking things up).
> Applies to: Minecraft 1.21.1, NeoForge 21.1.203, Parchment 2024.11.17, Java 21. The format described on this page holds only within this version range.
> Authoritative source: rules on this page are verified against the current `modules/data-driven`, `modules/core`, and `modules/modelling` source code and tests, covering both the v2 multi-source build pipeline (`DataDrivenBuildPipeline` in `STRICT` / `LEGACY` mode) and the legacy single-mod entry point (`JsonTreeBuilder`). The old `sources` layout has been migrated to the index manifest; each rule cites the class, line number, or test name so you can check it directly.

---

## 0. Terms and scope

### 0.1 Three concepts you must keep apart

- registration phase: the phase before mod construction / registry events finish, where object intents are written into `Reg` trees and then into Minecraft registries (such as `BuiltInRegistries.BLOCK`). It completes once and cannot hot reload on `/reload`. On the v2 path (`DataDrivenRegistrationProvider` + `DataDrivenBuildPipeline`), input comes from host-captured immutable `ResourceSnapshot` / `ContentSource` instances (e.g. `SnapshotContentSource`); when no provider is installed, `JsonTreeBuilder.buildForMod` falls back to reading the mod JAR.
- reload phase: the resource re-read triggered on initial `ResourceManager` attachment and on `/reload`; the source it reads is the active Minecraft pack stack, so data packs can override it (managed `ApprovedResourcePacks` sources are additionally filtered by `ManagedReloadAllowlist`).
- index file (index manifest): a `.json` under `data/<namespace>/kasuga_lib/data_driven/` that declares only which content files belong to which domain; it contains no registration objects of its own.

A mod or source's index files can declare only the registration domain, only the reload domain, or both.

### 0.2 What this page covers

Covered: the index file format; the registration-phase content file format (`registry_groups` / `blocks` / `items` / embedded `block_entity`); built-in `proxy_model` / `proxy_model_be` `params` schema; duplicate id, `OVERLAY`, and `REGISTRY_CLAIMS` rules; the wrapper format of reload-phase content files; `STRICT` vs `LEGACY` decoding and validation differences; and error classification and outlets.

Not covered: the concrete gameplay effect of each `properties` key in the registry (see `../../guides/data-driven/guide-content.md`); the complete codec fields inside state machine definitions and animation clip elements (see `../fsm.md` and the codecs under `uml/`); Java-side `TypeHandler` extensions in depth (see `api-extension.md` and `../../guides/data-driven/guide-extension.md`).

## 1. The index file (index manifest)

### 1.1 Location and discovery rules

The index directory is fixed at a **4-segment path**:

```text
data/<namespace>/kasuga_lib/data_driven/
```

- Pinned by `JsonTreeBuilder.indexDirectorySegments(modId)`, which returns `{"data", modId, "kasuga_lib", "data_driven"}` (`JsonTreeBuilder.java:766-768`).
- Note that `kasuga_lib` is a fixed spelling and `data_driven` uses an underscore. Writing `kasuga-lib`, or dropping the `kasuga_lib` level, leads to **silently loading 0 entries**; that risk is pinned by `JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout()` with `assertArrayEquals`.
- By default, **every** direct child `.json` file in the directory is treated as an index and read in ascending path/filename order (`JsonTreeBuilder.listIndexFiles`, `:783-795`; `ContentSource.indexes()`, `ContentSource.java:37-65`). Subdirectories are not scanned recursively. There is no filename convention; `index.json` is only a habit.
- In v2 `SnapshotContentSource`, a host can pass `declaredIndexes` to limit which manifest paths are exposed for the current registration scene (for example a parcel root exposing only `parcel.json.indexes`); `null` restores default directory discovery, and an empty collection represents a pure asset/data root with zero manifests. See [api-registration.md](api-registration.md) §17.
- In the legacy entry point, a missing directory only logs DEBUG and does not count as an error (`JsonTreeBuilder.java:83`); in the v2 pipeline, a source with no manifests yields zero definitions and succeeds when there are no blocking errors.
- The reload side reuses the same path segments when it reads the same directory (`ReloadOrchestrator.indexResourcePath`, `ReloadOrchestrator.java:290-297`).

### 1.2 Top-level fields

An index file has **only two** top-level fields, each a string array:

| Field | Type | Domain | Meaning |
|------|------|----|------|
| `on_register` | `string[]` | registration phase | Lists the content files parsed during registration build and written into `Reg` / MC registries |
| `on_reload` | `string[]` | reload phase | Lists the content files parsed on `/reload` and written into reload buckets (also preflight-decoded by `DataDrivenBuildPipeline` in `STRICT` mode) |

Both are **optional**, but at least one must appear (D1/D2). The legacy field constants are in `JsonTreeBuilder.java:45,47` and parsing in `parseIndexManifest` (`:228-257`); v2 pipeline parsing is in `DataDrivenBuildPipeline.readManifest` (`DataDrivenBuildPipeline.java:513-588`).

A legal index declaring both domains:

```json
{
  "on_register": [
    "kasuga_lib_content/blocks.json",
    "kasuga_lib_content/groups.json"
  ],
  "on_reload": [
    "state_machines/panel.json",
    "animation_clips/wheel.json"
  ]
}
```

### 1.3 `sources` is removed (breaking change)

**`sources` is now an illegal field.** It is no longer recognized and has no alias; it goes down the "unknown top-level field" path: an error is logged plus a diagnostic (`MANIFEST_INVALID` in v2), and the file thereby "declares nothing" (which logs another "neither is declared" error / `MANIFEST_EMPTY`).

```json
{
  "sources": ["kasuga_lib_content/blocks.json"]
}
```

The example above produces **two** errors: (1) `contains unsupported field 'sources'` (or `Unsupported index field 'sources'` in v2); (2) `declares neither 'on_register' nor 'on_reload'`. See `JsonTreeBuilder.parseIndexManifest` (`:231-238` unknown field, `:247-253` neither declared) and `DataDrivenBuildPipeline.readManifest` (`:553-581`); pinned by `JsonTreeBuilderIndexSchemaTest.legacySourcesFieldIsReportedAsUnknown()`.

The old `sources` field and the old flat layout `data/<namespace>/kasugalib/` were both removed by a hard migration, with no compatibility window. If the old directory exists on disk and there is no new index directory, the legacy loader logs one WARN plus a bucket entry hinting at migration to `on_register` (`JsonTreeBuilder.java:73-86`).

### 1.4 Field-level rules D1–D6

The **executable version** of these rules is `JsonTreeBuilderIndexSchemaTest` (see section 6). The rules are implemented in `parseIndexManifest` / `resolveIndexManifests` and mirrored in `DataDrivenBuildPipeline.readManifest` / `resolveNamespace`:

- **D1 Declare at least one field**: neither field appears (an empty file, or a file with unknown fields only) → error + diagnostic, and the file contributes no paths. An empty file (parsed as `null`) is reported too (`JsonTreeBuilder.java:247-253`; `DataDrivenBuildPipeline.java:538-550,570-581`).
- **D2 An empty array is legal**: `{"on_register": []}` is legal and **counts as declared** (`JsonTreeBuilder.java:244`; `DataDrivenBuildPipeline.java:570`).
- **D3 A failure in one field does not implicate its sibling**: when `on_register` is not an array only that field is reported and `on_reload` is still read, and vice versa (`readPathArray`, `:263-279`; `readPathField`, `DataDrivenBuildPipeline.java:590-633`). A non-string element in the array is reported and skipped, and the remaining elements are kept (`:272-277`; `:617-629`).
- **D4 The aggregate warning branches on "was anything ever declared"**: a pure reload mod that declares only `on_reload` does **not** raise "registration domain not declared"; only when **no file declares either field** does the aggregation stage record a warning/diagnostic (`resolveIndexManifests`, `:347-352`; `resolveNamespace`, `DataDrivenBuildPipeline.java:642-653`). When every file fails its single-file declaration, the aggregate error is still raised.
- **D5 `sources` is an unknown field**, not an alias (`:231-238`).
- **D6 A path listed in both arrays fails closed**: the same path (within one file or across files) appearing in both arrays → error + diagnostic, and that path is **consumed by neither domain** (removed from both lists). The check spans **all** index files of that mod or source namespace (`resolveIndexManifests`, `:330-345`; `resolveNamespace`, `DataDrivenBuildPipeline.java:661-678`).

### 1.5 Path entry rules

Array elements are paths relative to `data/<namespace>/` and **must** carry the `.json` suffix. Validation is in `JsonTreeBuilder.validateSourcePath` (`:407-416`), reused by both the reload side (`ReloadOrchestrator.collectIndex`, `:395`) and the v2 pipeline (`DataDrivenBuildPipeline.locateContent`, `:712-762`); v2 additionally constructs a `LogicalPath`. Violating any rule → error + diagnostic, and that entry is skipped:

| Rule | Message fragment / constraint when violated |
|------|------------------|
| Non-empty | `path is empty` |
| Does not start with `/` (must be relative) | `path must be relative` |
| Ends with `.json` | `path must include the '.json' suffix` |
| No empty segment (`//`) | `path contains an empty segment` |
| No `.` or `..` segment | `path must not contain '.' or '..'` |
| `LogicalPath` character constraints (v2 `DataDrivenBuildPipeline`) | Forbids `\`, Windows drive prefixes (e.g. `C:`), NUL, and ISO control characters (`LogicalPath.java:18-47`) |

Also:

- The fact that a path **cannot cross namespaces** is structural: `parseSource` always joins the path under `data/<modId>/` (`JsonTreeBuilder.java:441-446`), and `DataDrivenBuildPipeline.locateContent` joins under `data/<namespace>/` (`DataDrivenBuildPipeline.java:724`). An entry pointing at `assets/` can never take effect.
- The same path listed in several places within the same mod / source namespace and domain → **silently deduplicated** during registration (`parsedSources` in `JsonTreeBuilder.parseSource`, `:427`; `seenRegister` / `seenReload` in `DataDrivenBuildPipeline.resolveNamespace`, `:680-709`). Runtime `ReloadOrchestrator.collectIndex` does not additionally deduplicate repeated entries within a single `on_reload` list, so keep each path listed once.
- The reload side additionally requires the path to form a legal `ResourceLocation` (only `[a-z0-9/._-]` is allowed); illegal characters such as uppercase are reported separately (`ReloadOrchestrator.java:401-412`). Note that `DataDrivenBuildPipeline` preflight on `on_reload` checks `LogicalPath` and source existence without constructing `ResourceLocation` ahead of time.
- An illegal path, a missing file, and an unreadable JSON file all produce errors (`parseSource`, `:419-462`; `locateContent` / `parseContentFile`, `DataDrivenBuildPipeline.java:712-800`).

### 1.6 Merging multiple manifests and strict JSON

All index files of the same mod or `ContentSource` are **fully read in** first and then consumed together:

- Legacy `JsonTreeBuilder`: paths are concatenated by "ascending index filename → array writing order within the file" (`JsonTreeBuilder.java:97-117,318-356`).
- v2 `DataDrivenBuildPipeline`: sources are processed in ascending global `rank`; within each source, manifests are aggregated per namespace, cross-listed paths are rejected and same-domain duplicates removed, and `on_register` / `on_reload` paths are sorted lexicographically by `logicalPath` before parsing (`DataDrivenBuildPipeline.java:463-511`).
- **STRICT JSON decoding (`StrictResourceJson`)**: when `DataDrivenBuildPipeline` runs with `BuildOptions.STRICT`, manifests, registration content files, and `on_reload` preflight files all pass through strict UTF-8 decoding (rejecting malformed byte sequences) and `StrictResourceJson.parse` (`DataDrivenBuildPipeline.java:1559-1574`, `StrictResourceJson.java:14-51`):
  - `reader.setLenient(false)`: rejects comments, single quotes, trailing commas, and other non-standard JSON;
  - requires the entire file to be exactly one JSON object with no trailing documents;
  - rejects **duplicate keys** inside any JSON object immediately rather than silently keeping the last value;
  - enforces a maximum text length of 8 MiB, default max depth of 64, and max 100,000 nodes.
- `LEGACY` mode and `JsonTreeBuilder` use Gson's tolerant parsing, and in `LEGACY` a `MANIFEST_INVALID` diagnostic does not block `build` apply on its own.

## 2. Registration-phase content files (listed under `on_register`)

### 2.1 File top-level structure

In a content file the **top-level field name is the top-level registration type**, and the value must be an **array of objects**. There are 3 built-in top-level types and 1 embedded type:

| Top-level field | handler | phase | Effect |
|----------|---------|-------|------|
| `registry_groups` | `RegistryGroupHandler` | 0 | Establish property groups (parent/child) |
| `blocks` | `BlockTypeHandler` | 1 | Register blocks |
| `items` | `ItemTypeHandler` | 1 | Register standalone items |
| `block_entities` (embedded) | `BlockEntityTypeHandler` | 2 | Embedded inside `blocks[].block_entity`; invalid as a top-level field |

One content file can contain several top-level fields at once (for example one `registry_groups` set and one `blocks` set), dispatched one by one by `dispatchContent` (`JsonTreeBuilder.java:485`) or `DataDrivenBuildPipeline.parseContentFile` (`DataDrivenBuildPipeline.java:764-917`).

```json
{
  "registry_groups": [
    { "id": "mymod:panels", "properties": { "no_occlusion": true } }
  ],
  "blocks": [
    {
      "id": "mymod:panel",
      "type": "data_driven_block",
      "registry_group": "mymod:panels",
      "properties": { "destroy_time": 2.0 },
      "item_properties": { "tab": "mymod:main" }
    }
  ]
}
```

Validation of top-level fields:

- Unknown top-level field → `ERROR` (`UNKNOWN_TYPE`) in `JsonTreeBuilder` and `STRICT`, `WARNING` in `LEGACY`; `JsonTreeBuilder` and `LEGACY` keep dispatching **the remaining fields in the same file** (partial apply), whereas `STRICT` records a blocking diagnostic and rejects the whole batch before apply (`REJECTED`).
- A known field whose value is not an array → `ERROR` (`CONTENT_INVALID`), remaining fields proceed.
- An array element that is not a JSON object → `ERROR` (`CONTENT_INVALID`), that element is skipped, remaining elements proceed.

### 2.2 `registry_groups` entries

Parsing is in `RegistryGroupHandler.parse` (`RegistryGroupHandler.java:45-49`), application in `store` (`:108-141`), and strict validation in `validate` (`:71-98`).

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | yes | — | Group id. **Matched by the raw string**, with no namespace normalization |
| `parent` | string | no | `null` → attached to the root group | Parent group id; in the legacy loader an absent parent falls back to the root group (`:111-116`); v2 `STRICT` verifies the parent exists and is visible |
| `properties` | object | no | — | Block properties passed down to child content (see section 2.6) |
| `item_properties` | object | no | — | Item properties passed down to child content; `tab` is singled out as this group's creative tab (`:126-127`) |

- A group has **no `type` field**.
- Blocks and items look their group up through `registry_group` using the raw string; therefore `id` is not normalized and `mymod:panels` and `panels` are two different groups (`RegistryGroupHandler.resolveIdentity`, `:66-68`).
- Before applying, a topological sort is done by `parent`: parents before children; in the legacy loader and `LEGACY` mode a cycle only gets a WARN / warning diagnostic and falls back to the original order (`JsonTreeBuilder.topoSortGroupDefs`, `:890`), while in v2 `STRICT` it records a `GROUP_CYCLE` blocking error and rejects the build (`DataDrivenBuildPipeline.java:1233-1297`).
- In v2 `STRICT`, `JsonPropertyParser.validateStrict` and `JsonItemParser.validateStrict` validate `properties` and `item_properties` on groups, emitting `CONTENT_INVALID` errors for unknown keys or invalid values.

### 2.3 `blocks` entries

Parsing is in `BlockTypeHandler.parse` (`BlockTypeHandler.java:31-36`), generic application in `RegTypeHandler.apply` (`RegTypeHandler.java:155-205`), and strict validation in `RegTypeHandler.validate` (`:102-141`) and `BlockTypeHandler.validate` (`:70-105`).

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | yes | — | Block id; `STRICT` requires explicit `namespace:path`, legacy/`LEGACY` allow bare paths |
| `type` | string | yes | — | Factory name, looked up in `FactoryRegistry.BlockFactory` (`BlockTypeHandler.createRegistration`, `:126-133`) |
| `registry_group` | string | no | `null` → attached to the root group | Owning group id (raw-string match) |
| `properties` | object | no | — | Block properties, see section 2.6 |
| `state_properties` | object | no | — | Minecraft block-state properties; see [block-state-properties.md](block-state-properties.md) |
| `item_properties` | object | no | — | Item properties for the block's item; `tab` decides the creative tab (`BlockTypeHandler.resolveCreativeTab`, `:64-67`) |
| `params` | object | no | `null` | Passed to the factory verbatim by `create(path, params)` (`StatefulBlockFactory` also receives `state_properties`) |
| `state_machine` | JSON primitive | no | — | Forwarded into the BE `params.state_machine` only when the entry contains a `block_entity` (see section 2.5) |
| `block_entity` | object | no | — | Embedded block entity definition (see section 2.5) |

- When `id` or `type` is missing, `parse` throws (`Objects.requireNonNull`); in the legacy loader only that entry is lost while siblings proceed, while in `STRICT` it records `PARSE_FAILED` and rejects the whole build.
- Missing factory `type`: in the legacy loader without `state_properties`, only a WARN is logged and the entry is skipped (`RegTypeHandler.java:162-166`). In v2 `STRICT`, any missing factory records `UNKNOWN_FACTORY`, and declaring `state_properties` for a factory that does not implement `StatefulBlockFactory` also records `UNKNOWN_FACTORY` (`BlockTypeHandler.java:92-104`).
- **Unknown keys on the block entry itself** are silently ignored during Gson deserialization, but in `STRICT` mode keys inside `properties`, `item_properties`, and `state_properties` are strictly validated.
- Built-in stateful block factories are `data_driven_block` and `proxy_model` (see section 2.9).

### 2.4 `items` entries

Parsing is in `ItemTypeHandler.parse` (`ItemTypeHandler.java:30-35`), strict validation in `:59-71`.

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | yes | — | Item id; `STRICT` requires explicit `namespace:path` |
| `type` | string | yes | — | Factory name, looked up in `FactoryRegistry.ItemFactory` (`:74-78`) |
| `registry_group` | string | no | attached to the root group | Owning group id |
| `properties` | object | no | — | Item properties, see section 2.6; `tab` decides the creative tab (`:53-56`) |
| `params` | object | no | `null` | Passed to the factory |

### 2.5 `block_entities` (embedded type)

**A block entity has no id of its own**; it is carried by its host block, and the way to write one is to put a `block_entity` object inside that block's entry:

```json
{
  "blocks": [
    {
      "id": "mymod:be_block",
      "type": "be_block",
      "block_entity": {
        "type": "test_be",
        "params": { "tick_interval": 4 }
      }
    }
  ]
}
```

- The top-level field name is `block_entities`, but the embedded key name is `block_entity` (`BlockEntityTypeHandler.getTypeName` `:29`, `getParentTypeName` `:48`, `getEmbeddedKeyName` `:51`).
- Extraction logic: `BlockEntityTypeHandler.extractEmbedded` (`:54-66`) injects the host block's `id` into the embedded object's `_parent_block`; if the host block has a top-level `state_machine` of JSON primitive type (string / number / boolean), it is forwarded into the embedded object's `params.state_machine` (`:59-64`). The test is `isJsonPrimitive()`, so number and boolean pass too, converted to text by `getAsString()` before being stored in `params`; objects, arrays, and null are ignored.

Fields of the embedded `block_entity` object:

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `type` | string | yes | — | Block entity factory name, looked up in `FactoryRegistry.BlockEntityFactory` (for example built-in `proxy_model_be` / `fsm_be`) |
| `params` | object | no | `null` | Passed to the factory; concrete keys are interpreted by the factory (see section 2.9 for `proxy_model_be`) |
| `_parent_block` | string | — | injected | **Do not write this by hand**; it is injected by `extractEmbedded` |

- Duplicate detection: two BE entries attached to the **same host block** count as duplicates (`resolveIdentity`, `:87-96`, using the host block's effective id).
- Registry claim: in v2 it emits `minecraft:block_entity_type` / `<block_ns>:<block_path>_be` (`claims`, `:124-134`).
- Host block not found / unknown BE factory: the legacy loader logs a WARN (`BlockEntityTypeHandler.apply`, `:141-152`); v2 `STRICT` validates host block visibility and BE factory availability (`MISSING_REFERENCE` / `REFERENCE_NOT_DECLARED` / `UNKNOWN_FACTORY`), and `apply` propagates runtime exceptions in strict contexts (`:163`).

### 2.6 Inner keys of `properties` / `item_properties`

A top-level field's `properties` and `item_properties` are claimed by two parsers. **Mode difference**: the legacy loader and `LEGACY` pipeline log a WARN for unknown keys or most malformed values and ignore that modifier; v2 `STRICT` invokes `JsonPropertyParser.validateStrict` / `JsonItemParser.validateStrict` and turns unknown keys or out-of-range/wrong-type values into `CONTENT_INVALID` blocking errors (`BlockTypeHandler.java:72-91`, `ItemTypeHandler.java:61-70`, `RegistryGroupHandler.java:78-97`).

**Block properties `properties`** (`JsonPropertyParser.buildCompilers`, `JsonPropertyParser.java:248-274`; strict validation `:45-109`):

| Key | Value type | Key | Value type |
|----|--------|----|--------|
| `no_occlusion` | bool | `light_emission` | int (`STRICT` requires `0..15`) |
| `no_collision` / `no_collission` (spelling alias) | bool | `friction` | float (finite) |
| `requires_correct_tool` | bool | `speed_factor` | float (finite) |
| `replaceable` | bool | `jump_factor` | float (finite) |
| `dynamic_shape` | bool | `destroy_time` | float (finite) |
| `random_ticks` | bool | `explosion_resistance` | float (finite) |
| `no_loot_table` | bool | `strength` | float or a `[destroy, resist]` two-finite-number array |
| `ignited_by_lava` | bool | `map_color` | `DyeColor` name (lowercase) |
| `liquid` | bool | `sound_type` | sound type name (see below) |
| `force_solid_on` | bool | | |
| `air` | bool | | |
| `no_terrain_particles` | bool | | |

- The legal values of `sound_type` come from `JsonPropertyParser.buildSoundTypeMap` (`:197-246`), such as `stone` / `wood` / `metal` / `glass`.
- `map_color` uses MC `DyeColor` names.

**Item properties `item_properties` (block entries) / `properties` (item entries)** (`JsonItemParser`, `JsonItemParser.java:23-40`; strict validation `:57-123`):

| Key | Value type | Meaning |
|----|--------|------|
| `stacks_to` | int | Maximum stack size (`STRICT` requires `1..99`) |
| `rarity` | string | `COMMON` / `UNCOMMON` / `RARE` / `EPIC` (case-insensitive) |
| `fire_resistant` | bool | Fire resistance |
| `durability` | int | Durability (`STRICT` requires `>= 1`) |
| `no_repair` | bool | Cannot be repaired |
| `tab` | string | Creative tab `ResourceLocation`; consumed separately (`STRICT` requires valid `namespace:path`) |

Properties are inherited along the parent chain, and a child entry overrides its parent (`RegTypeHandler.apply` sets `registry_group` as the parent, `Reg.applyProperties` applies parents before children).

### 2.7 Unknown keys inside an object vs unknown top-level file keys

- An unknown **top-level file** field → `ERROR` (`UNKNOWN_TYPE`) in `JsonTreeBuilder` and `STRICT`, `WARNING` in `LEGACY` (`JsonTreeBuilder.java:496-518`, `DataDrivenBuildPipeline.java:807-823`). A misspelled `blokcs` is caught.
- An unknown key **inside an entry object** → ignored by Gson deserialization in each handler's `parse`, except inside `state_properties` (which always rejects unknown fields via `StatePropertySet.requireKeys`) and inside `properties` / `item_properties` in `STRICT` mode (which reject unknown keys via `validateStrict`).

### 2.8 An embedded type written as a top-level field by mistake

Writing `block_entities` as a top-level field (instead of putting it in the `block_entity` key of a `blocks` entry) goes down the "unknown top-level field" path:

- In legacy `JsonTreeBuilder`: `ERROR` + bucket entry naming the `block_entity` key of a `blocks` entry as where it belongs, and **the rest of the file's `blocks` dispatch as usual** (`JsonTreeBuilder.java:496-518`).
- In v2 `DataDrivenBuildPipeline`: reported as `UNKNOWN_TYPE` (`ERROR` blocking the build in `STRICT`, `WARNING` in `LEGACY`).

```json
{
  "block_entities": [ { "type": "test_be" } ],
  "blocks": [ { "id": "mymod:ok", "type": "simple_block" } ]
}
```

Legacy partial-dispatch behavior is pinned by `JsonTreeBuilderEmbeddedTypeDispatchTest.topLevelEmbeddedTypeIsReportedAndTheRestOfTheFileStillDispatches()`. When an embedded object itself fails to parse from a missing field, the legacy loader discards only that embedded element while siblings proceed, whereas `STRICT` records `PARSE_FAILED` and rejects the batch.

### 2.9 Built-in `proxy_model` / `proxy_model_be` schema

`modules/modelling` provides a built-in generic proxy-model block/BE pair (`ProxyModelBlockFactories.java`, registered automatically at startup by its `@Context` bean or explicitly via `ProxyModelBlockFactories.registerBuiltin()`):

- **Block factory `type: "proxy_model"`** (`ProxyModelBlockFactories.BLOCK_TYPE`): implements `StatefulBlockFactory` and registers `createsBlockItem = true`, producing `ProxyModelBlock` plus a default same-id block item.
  - Composes with normal `state_properties`: if vanilla `facing` (`HORIZONTAL_FACING`) or `hinge` (`DOOR_HINGE`) is declared, `ProxyModelBlock` automatically wires placement, rotation, and mirroring (`ProxyModelBlock.java:71-113`).
  - Always uses `RenderShape.ENTITYBLOCK_ANIMATED` so a missing baked block model does not show the purple/black missing texture.
  - **Rejects block-level `params`**: `ProxyModelBlockFactories.validateBlockParams` (`:77-84`) throws `IllegalArgumentException` if `blocks[].params` is non-empty (implement custom block interactions in a dedicated block subclass and factory).
- **Embedded block entity `block_entity.type: "proxy_model_be"`** (`ProxyModelBlockFactories.BLOCK_ENTITY_TYPE`): creates `ProxyModelBlockEntity`.

Supported fields of `proxy_model_be`'s `block_entity.params` (`ProxyModelBlockFactories.java:155-401`):

| Field | Type | Required | Default | Meaning and validation rules |
|---|---|---|---|---|
| `model` | string (`ResourceLocation`) | either `model` or `model_groups` | — | Single-model shorthand: builds a `CompiledModelGroup` with id `"default"` and one part (id `"body"`). Throws if neither `model` nor `model_groups` is provided |
| `model_name` | string | no | `null` | Sub-model name inside the file (MMD `.pmx` only), applied with `params.model` |
| `default_model` | string | no | see right | Group id in `model_groups` used when no `model_by_state` rule matches. When omitted, uses `"default"` if `params.model` was provided, or the first entry in `model_groups` otherwise. Throws if unknown |
| `yaw_offset` | number (float) | no | `0.0` | Extra horizontal yaw offset in degrees added on top of the blockstate facing yaw |
| `facing_yaw` | object (`string -> float`) | no | `{}` | Explicit yaw overrides in degrees keyed by `HORIZONTAL_FACING` serialized name (`"north"`, `"east"`, `"south"`, `"west"`); overrides `facingYaw(state) + yaw_offset` when present |
| `lighting_mode` | string | no | `null` | `"pbr"` (`ModelLightingMode.PBR`) or `"vanilla_block"` (`ModelLightingMode.BLOCK`); omitting preserves the model asset's default; any non-string or unknown value throws `IllegalArgumentException` at registration |
| `model_groups` | object (`<group_id> -> group`) | no (required if `model` omitted) | — | Named multi-part compiled model groups; `<group_id>` must match `[a-z0-9_.:/-]+` |
| `model_by_state` | object (`<prop> -> {<val>: <target>}`) | no | `{}` | Ordered blockstate selector rules evaluated in JSON key order; the first matching rule wins |
| `animation` | — | **forbidden** | — | Explicitly rejected (`IllegalArgumentException`); state-driven playback belongs in a Java `ProxyModelBlock.createProxyComposition` override |

`model_groups.<group_id>` object schema:

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `y_offset` | number (float) | no | `0.0` | Finite vertical offset applied to the proxy root when this group is active |
| `parts` | object[] | yes | — | Non-empty ordered array of part objects (`CompiledModelPart`) |

`parts[]` element schema:

| Field | Type | Required | Default | Meaning |
|---|---|---|---|---|
| `id` | string | yes | — | Non-blank part identifier, must match `[a-z0-9_.-]+` and be unique within the group |
| `model` | string (`ResourceLocation`) | yes | — | Model resource location |
| `model_name` | string | no | `null` | Sub-model name inside the file (`.pmx` only) |
| `local` | object[] | no | `[]` | Ordered array of local `Transform` operations relative to the proxy root; each object must contain **exactly one** of `translate`, `rotate`, or `scale` as a 3-element finite number array (`rotate` is in degrees) |

Key invariants (`ProxyModelSelection.java:40-47, 80-98`):

- **Every group must share the exact same ordered `part.id` list**: all groups in a `proxy_model_be` (including `"default"` from `params.model` and any implicit single-part `"body"` groups synthesized by `model_by_state`) must declare identical ordered `part.id` lists, or registration fails with `IllegalArgumentException`.
- **`model_by_state` resolution and registration-time validation**: `<target>` is resolved first by group id against `model_groups`; if not found there, it is parsed as a `ResourceLocation` and wrapped as a single-part (`"body"`) group (`"legacy_<ns>_<path>"`). At `onRegistered` time, `ProxyModelSelection.validate(block.defaultBlockState())` verifies that every referenced property name and state value exists on the host block's `BlockState`.

Minimal single-model example:

```json
{
  "blocks": [
    {
      "id": "example:sign_post",
      "type": "proxy_model",
      "state_properties": {
        "facing": { "type": "vanilla", "property": "HORIZONTAL_FACING", "default": "north" }
      },
      "block_entity": {
        "type": "proxy_model_be",
        "params": {
          "model": "example:models/block/sign_post.bbmodel",
          "yaw_offset": 180.0,
          "lighting_mode": "vanilla_block"
        }
      }
    }
  ]
}
```

Multi-part `model_groups` + `model_by_state` + `facing_yaw` example:

```json
{
  "blocks": [
    {
      "id": "example:sliding_gate",
      "type": "proxy_model",
      "state_properties": {
        "facing": { "type": "vanilla", "property": "HORIZONTAL_FACING", "default": "north" },
        "open": { "type": "vanilla", "property": "OPEN", "default": false }
      },
      "block_entity": {
        "type": "proxy_model_be",
        "params": {
          "default_model": "closed",
          "facing_yaw": { "north": 0, "east": 90, "south": 180, "west": 270 },
          "lighting_mode": "pbr",
          "model_groups": {
            "closed": {
              "y_offset": 0.0,
              "parts": [
                { "id": "frame", "model": "example:models/block/gate_frame.bbmodel" },
                {
                  "id": "leaf",
                  "model": "example:models/block/gate_leaf.bbmodel",
                  "local": [ { "translate": [0, 0, 0] } ]
                }
              ]
            },
            "opened": {
              "y_offset": 0.05,
              "parts": [
                { "id": "frame", "model": "example:models/block/gate_frame.bbmodel" },
                {
                  "id": "leaf",
                  "model": "example:models/block/gate_leaf.bbmodel",
                  "local": [
                    { "translate": [0.75, 0, 0] },
                    { "rotate": [0, 15, 0] }
                  ]
                }
              ]
            }
          },
          "model_by_state": {
            "open": {
              "false": "closed",
              "true": "opened"
            }
          }
        }
      }
    }
  ]
}
```

At runtime, use `/kasuga_proxy list` (`ProxyCommands.java`) to inspect live `ProxyInstance` `stage`, `model`, `parts`, and `mounted` status.

## 3. Duplicate ids, OVERLAY, and claim checks

### 3.1 Legacy `JsonTreeBuilder` deduplication (last-wins)

In legacy `JsonTreeBuilder`, deduplication happens **before any registration side effect**: once parsing has finished, conflicts are resolved first and losers never reach `apply` (`JsonTreeBuilder.resolveDuplicates`, `:673`), because MC's `MappedRegistry.register` does not reject duplicate keys (`DuplicateIdResolver.java:11-14`).

The conflict space is `(typeName, identity)`:

| Type | identity (legacy / `LEGACY`) | identity (v2 `STRICT`) |
|------|----------|--------|
| `blocks` / `items` | `EffectiveId.ofLegacy(modId, id)`: no namespace or `minecraft:` → `<modId>:path`; other namespaces verbatim | `EffectiveId.of(modId, id)`: bare id resolves to `<modId>:path` (though `validate` rejects bare ids with `INVALID_IDENTITY`); **explicit `minecraft:` stays `minecraft:path`** |
| `block_entities` | host block's `EffectiveId.ofLegacy` | host block's `EffectiveId.of` |
| `registry_groups` | raw `id` string (not normalized) | raw `id` string (not normalized) |

In `JsonTreeBuilder`, "last" in last-wins is the **position in the load sequence**:

1. Between index files: ascending filename order;
2. Within one index file: the writing order of `on_register`;
3. Within one content file: array order;
4. Embedded BEs: follow their host `blocks` entry.

Each conflict records a **WARN log line plus one loading-error bucket entry** (`JsonTreeBuilder.java:688-696`).

### 3.2 v2 `DataDrivenBuildPipeline` selection and claims checks

The v2 pipeline branches by `TypeHandler.capabilities()` and `BuildOptions` (`DataDrivenBuildPipeline.selectDefinitions`, `:1149-1183`):

- **Configuration types (`NON_REGISTRATION` without `OVERLAY`) and `LEGACY` ordinary types**: picks the winner with highest `(sourceOrder, declarationOrder)` (last-wins), where `sourceOrder` comes from the platform `ResourceSourceDescriptor.rank()` (or input position for legacy `SourceDescriptor`). All parsed candidates remain in `PreparedContent.allDefinitions()`.
- **Overlay types (`HandlerCapability.OVERLAY`)**: in both `STRICT` and `LEGACY`, **all** candidates sharing a `DefinitionKey` stay selected so the handler can merge or overlay them.
- **Strict registration types (`STRICT` with `REGISTRY_CLAIMS` and without `NON_REGISTRATION`)**: retains all candidates sharing a `DefinitionKey`, validates each one, and collects all `RegistryClaim` entries before apply (`collectClaims`, `:1304-1401`):
  - collision with host `reservedRegistryClaims` (e.g. from `RegistryClaimSnapshot.collect`) → `CLAIM_CONFLICT` (`ERROR` in both modes);
  - two definitions claiming the same `(registry, id)` → `CLAIM_CONFLICT` (`ERROR` + batch `REJECTED` in `STRICT`, `WARNING` in `LEGACY`).

## 4. Reload-phase content files (listed under `on_reload`)

### 4.1 The two content types and how they are discovered

`ReloadOrchestrator` is the only orchestrator in the reload domain (`ReloadOrchestrator.java:121`), and it knows no type name itself: the `typeName()` of every registered `ReloadHandler` is a legal top-level key. The two handlers registered by modelling are `state_machines` and `animation_clips` (`FsmReloadHandler` / `FsmClipsReloadHandler`, see section 4.6).

| Content | Top-level key | Discovery |
|------|--------|----------|
| State machine definitions | `state_machines` | **directory glob** `data/<ns>/state_machines/*.json` **+** listed in `on_reload` |
| Animation clips | `animation_clips` | **only** listed in `on_reload` |

- `state_machines/` has a directory glob: `FsmReloadHandler.globDirectories()` (`FsmReloadHandler.java:69-71`) declares `StateMachineDefinitionLoader.PATH` (`StateMachineDefinitionLoader.java:39`), enumerated by `ReloadOrchestrator.collectGlob` (`ReloadOrchestrator.java:365-385`) using `ResourceManager.listResourceStacks`. Unmanaged mod/datapack files under `state_machines/*.json` are read even if no index lists them.
- **Managed packs (`ManagedReloadAllowlist`)**: packs projected by `ApprovedResourcePacks` are filtered by `ManagedReloadAllowlist` (`ReloadOrchestrator.java:317-332,375-380,419-437`): managed sources only load approved index and `on_reload` content paths at their prepared priority and do not participate in unmanaged directory glob discovery.
- `animation_clips/` **has no directory glob**. A clip file not listed by an index is **never read** (`AnimationClipLoader.java:31-32`; `FsmClipsReloadHandler.globDirectories` keeps the default empty set).
- `state_machines/` files listed by an index are skipped by the glob pass to avoid reading them twice (`ReloadOrchestrator.collectGlob`, `:372-374`).
- Application order: glob-discovered first, index-listed second; on the same id the latter wins (class javadoc `:82-94`; `register`, `:562-577`).
- **Startup preflight**: `DataDrivenBuildPipeline.prepare` also reads `on_reload` files declared in source manifests, parses them under `StrictResourceJson` in `STRICT` mode, and runs matching `ReloadHandler.decode` from `ReloadHandlerRegistry` without calling `clearBucket()` or `register()` (`DataDrivenBuildPipeline.validateReloadContentFile`, `:919-1025`).

### 4.2 `state_machines` wrapper

Each file is a wrapper object carrying the `state_machines` top-level key, whose value must be an array of definitions (extra top-level keys no longer reject the file, see section 4.4):

```json
{
  "state_machines": [
    {
      "id": "mymod:panel",
      "layers": [
        {
          "id": "base",
          "initial_state": "idle",
          "states": [
            { "id": "idle", "duration_ticks": 20 }
          ]
        }
      ]
    }
  ]
}
```

- One file can hold several definitions, and **the array order is the in-file last-wins order** (`StateMachineDefinitionLoader.java:26-28`).
- The element schema is decided by `StateMachineDefinition.CODEC`; this page specifies only the wrapper, and element fields are in `../fsm.md` and the corresponding codec.

### 4.3 `animation_clips` wrapper

Likewise a wrapper object carrying the `animation_clips` top-level key:

```json
{
  "animation_clips": [
    {
      "id": "mymod:wheel_spin",
      "duration_seconds": 2.5,
      "bones": [
        {
          "bone": "wheel_r",
          "keyframes": [
            { "time": 0.0, "transform": { "rotate": [0, 0, 0] } }
          ]
        }
      ]
    }
  ]
}
```

- The element carries its own `id` (the very id `states[].clip` references), and the loader does not extract an id separately (`AnimationClipLoader.java:23-26`).
- Keys in the element that the codec does not name are ignored, and can serve as comments or editor metadata (`:26-27`). Removed legacy keys `functions` and `formula_parts` are explicitly rejected by `decodeFile` with a hint to use `formula_transforms` (`AnimationClipLoader.java:100-108`).
- The file takes effect only in `on_reload` and registers into the reload bucket; a SCRIPT clip of the same name is not overridden by the file (`AnimationClipLoader.register`, `:127-132`).

### 4.4 File-level strictness, element-level tolerance

The two decoders isolate failures identically (`StateMachineDefinitionLoader.decodeFile` `:67-100`; `AnimationClipLoader.decodeFile` `:71-116`):

- **File level**: a non-object body, a missing top-level key, or a non-array value → **the whole file is rejected**, with one error. `{"state_machines": {}}` is rejected as a whole (`StateMachineDefinitionLoaderTest.nonArrayValueRejectsWholeFile`).
- **File level (genuinely unknown key)**: a top-level key claimed by no reload type → one `unsupported top-level field 'extra'` (or `UNKNOWN_TYPE` in `DataDrivenBuildPipeline`), while at runtime **known keys still contribute as usual** (`StateMachineDefinitionLoaderTest.extraTopLevelKeyDoesNotAffectThisType()`; `AnimationClipLoaderTest.ignoresAnExtraTopLevelKey()`).
- **Multiple top-level keys are legal**: one file can carry both `state_machines` and `animation_clips`, and the two decoders each read their own key and contribute their own entries (`AnimationClipReloadTest.mixedTopLevelKeysAreConsumedByBothHandlers`).
- **Element level**: an array element that is not an object, fails to decode, or uses removed `functions`/`formula_parts` keys → only that element is skipped, its siblings proceed as usual, and each failure is reported.
- An empty array is legal (`AnimationClipLoaderTest.emptyClipArrayIsValid()`).

### 4.5 Reload domain routing and the reverse D9 hint

- The domain is decided by the **index array**, and the content type by the **top-level key**.
- An unknown top-level key in a reload file → error + `RELOAD_DATA` bucket entry, with a **reverse hint** attached: if these fields are registration content (such as `blocks`/`items`/`registry_groups`), list the file under `on_register` instead (`ReloadOrchestrator.java:494-501`).
- One file can carry both content types at once: each handler claims its own top-level key and contributes its own entries (`ReloadOrchestrator.dispatch`, `:484-525`).
- The reload wrap-up re-checks the clips referenced by state machines; a dangling reference records a WARN + `RELOAD_DATA` entry, without affecting registration (`FsmReloadHandler.afterReload`, `:95-115`).

### 4.6 Extending to a new reload type

Reload types are not hardcoded: implement `ReloadHandler<T>` and register it via `ReloadHandlerRegistry.register(handler)` from a `@Context` bean's `@PostConstruct`. `typeName()` is the top-level key this type claims and doubles as the deduplication collision space. See [guide-extension.md](../../guides/data-driven/guide-extension.md) §6 and [api-reload.md](api-reload.md) §13.

## 5. Error classification and outlets

### 5.1 Errors (error log + diagnostic entry)

- **Legacy registration loader & reload domain** (lands in `JsonTreeBuilder` mod buckets or `Diagnostics.Domain.RELOAD_DATA`):

| Case | Source |
|------|------|
| Unknown index field / field not an array / element not a string | `JsonTreeBuilder.java:231-277` |
| A single file declares neither domain; every file declares neither | `:247-253` (error) / `:347-352` (WARN + bucket) |
| The same path listed in both arrays | `:330-345` |
| Failure reading an index file or directory | `:194-204`, `:783-795` |
| Unknown top-level field in a content file, field not an array, element not an object | `:496-535` |
| Illegal content file path / missing file / parse exception | `:419-462` |
| handler `apply` throws | `:733-742` |
| Duplicate id conflict | `:688-692` (WARN log + bucket entry) |
| Reload side: manifest read failure, illegal path, missing file, unauthorized managed source, wrapper shape violation, unknown top-level key | `ReloadOrchestrator.java:341-447,484-525` |
| Duplicate id on the reload side | `:602-610` (WARN log + bucket entry) |
| Dangling clip reference at reload wrap-up | `FsmReloadHandler.java:105-113` (WARN log + bucket entry) |

- **v2 `DataDrivenBuildPipeline`**: produces structured `BuildDiagnostic` entries (with `Severity.ERROR`/`WARNING`, `Stage`, `Code`, `sourceId`, `ownerId`, `logicalPath`, `jsonPointer`, `handler`, `message`, `suggestion`) stored in `PreparedContent.diagnostics()` and `BuildResult.diagnostics()`. They do **not** write into `Diagnostics` or `/kasuga_data errors` automatically; see [api-diagnostics.md](api-diagnostics.md) §19.

### 5.2 Warnings (warn, no legacy bucket entry / mode-dependent)

In the legacy loader the cases below only log a WARN without entering the error bucket, whereas v2 `STRICT` promotes most of them to `ERROR` blocking diagnostics:

- Without `state_properties`, an unknown factory `type` skips the entry with a WARN (`RegTypeHandler.java:162-166`). Missing/incompatible factories with `state_properties`, or any missing factory in v2 `STRICT`, report `UNKNOWN_FACTORY`.
- Unknown `properties` / `item_properties` keys or invalid values → legacy logs WARN and ignores (`JsonPropertyParser.java:152-159`, `JsonItemParser.java:147`); v2 `STRICT` validates via `validateStrict` and emits `CONTENT_INVALID` errors.
- BE host block not found / unknown BE factory → legacy logs WARN (`BlockEntityTypeHandler.java:141-152`); v2 `STRICT` emits `MISSING_REFERENCE` / `REFERENCE_NOT_DECLARED` / `UNKNOWN_FACTORY`.
- Group reference cycle → legacy logs WARN (`JsonTreeBuilder.java:907-909`); v2 `LEGACY` emits `WARNING`, `STRICT` emits `ERROR` (`GROUP_CYCLE`).

### 5.3 Reading diagnostics

- Legacy registration & reload domain:
  - `JsonTreeBuilder.getLoadingErrors(modId)` / `getLoadingErrorsByMod()` / `getLoadingErrors()` (`JsonTreeBuilder.java:827-859`).
  - `Diagnostics`: `Diagnostics.errors(modId)`, `Diagnostics.errors(Domain, key)`, `Diagnostics.summarize()` (`Diagnostics.java:64-168`).
  - In-game commands: `/kasuga_data errors [mod]` (`DataDiagnosticsCommands.java`) and `/kasuga_proxy list` (`ProxyCommands.java`).
- v2 `DataDrivenBuildPipeline`: inspect `prepared.diagnostics()` / `result.diagnostics()` and `prepared.hasBlockingDiagnostics()`; hosts export or log them directly.

### 5.4 D9 hint text (content listed under the wrong domain)

Conditional hints in both directions (part of the diagnostic contract for `JsonTreeBuilder` and `ReloadOrchestrator`):

- **Registration side**: `on_register` lists a reload-domain file → goes down the unknown-field path and ends with "list this file under `'on_reload'` instead", enumerating registered reload type names from `ReloadHandlerRegistry` at runtime (`JsonTreeBuilder.java:580-591`; test `JsonTreeBuilderReloadHintTest.reloadDomainContentUnderOnRegisterIsReportedWithOnReloadHint()`).
- **Reload side**: `on_reload` lists a registration-domain file → ends with "list the file under `'on_register'` instead", enumerating top-level registration types from `TypeHandlerRegistry` (`ReloadOrchestrator.java:494-501,543-554`).

Partial-apply guarantee: in `JsonTreeBuilder` and `ReloadOrchestrator`, listing one wrong-domain field still leaves valid sibling fields in the same file processed (`JsonTreeBuilderReloadHintTest.halfApplyStillDispatchesSiblingRegistrationFields()`).

## 6. Rule-to-source table

| Rule | Source (class:line / test method) |
|------|--------------------------|
| 4-segment index path | `JsonTreeBuilder.indexDirectorySegments` `:766-768`; `JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout` |
| Only `on_register`/`on_reload` at the top level | `JsonTreeBuilder.java:45,47,231-238`; `DataDrivenBuildPipeline.java:55,553-566` |
| D1 at least one domain declared | `JsonTreeBuilder.java:247-253`; `DataDrivenBuildPipeline.java:570-581` |
| D2 an empty array is legal | `JsonTreeBuilder.java:244`; `emptyArrayIsValidAndCountsAsDeclared` |
| D3 siblings are not implicated | `JsonTreeBuilder.java:263-279`; `DataDrivenBuildPipeline.java:590-633` |
| D4 a pure reload mod is not falsely reported | `JsonTreeBuilder.java:347-352`; `pureReloadModDoesNotRaiseTheAggregateError` |
| D5 `sources` is illegal | `JsonTreeBuilder.java:231-238`; `legacySourcesFieldIsReportedAsUnknown` |
| D6 listing in both arrays fails closed | `JsonTreeBuilder.java:330-345`; `DataDrivenBuildPipeline.java:661-678` |
| Path entry rules & safety | `JsonTreeBuilder.validateSourcePath` `:407-416`; `LogicalPath.java:18-47` |
| Strict JSON (`STRICT`) | `StrictResourceJson.java:14-51`; `DataDrivenBuildPipeline.parseJson` `:1559-1574` |
| Top-level type table | `JsonTreeBuilder.java:485-541`; `DataDrivenBuildPipeline.java:802-868` |
| `blocks` / `items` / `registry_groups` fields | `BlockTypeHandler` `:31-36`; `ItemTypeHandler` `:30-35`; `RegistryGroupHandler` `:45-49` |
| Embedded `block_entity` | `BlockEntityTypeHandler` `:29-96`; `JsonTreeBuilder.java:544-569`; `DataDrivenBuildPipeline.java:870-916` |
| Built-in `proxy_model` / `proxy_model_be` | `ProxyModelBlockFactories.java:40-401`; `ProxyModelSelection.java`; `CompiledModelGroup.java`; `ProxyModelBlockFactoriesTest` |
| Strict property validation | `JsonPropertyParser.validateStrict` `:45-109`; `JsonItemParser.validateStrict` `:57-123` |
| last-wins / OVERLAY / claims | `EffectiveId.of` / `ofLegacy` `:39-67`; `DuplicateIdResolver` `:73-126`; `DataDrivenBuildPipeline.selectDefinitions` `:1149-1183`; `collectClaims` `:1304-1401` |
| The two reload wrappers & removed keys | `StateMachineDefinitionLoader` `:36-102`; `AnimationClipLoader` `:44-134` |
| Directory glob vs index-only vs allowlist | `ReloadOrchestrator.collectGlob` `:365-385`; `collectIndex` `:392-449`; `ManagedReloadAllowlist.java` |
| D9 hints in both directions | `JsonTreeBuilder.java:580-591`; `ReloadOrchestrator.java:494-501` |
| Diagnostic buckets & structured diagnostics | `JsonTreeBuilder.java:827-880`; `Diagnostics.java:40-182`; `BuildDiagnostic.java:9-75` |

## 7. Boundaries with other documents

- This page (`schema.md`): the authoritative reference for the JSON format, covering fields, types, built-in `proxy_model` parameters, requiredness, path rules, `STRICT`/`LEGACY` differences, and error classification.
- **[intro.md](../../guides/data-driven/intro.md)**: an introductory tutorial answering "what is this, and why use it".
- **[guide-content.md](../../guides/data-driven/guide-content.md)**: a task-oriented guide answering "how do I build my first content file".
- **[guide-extension.md](../../guides/data-driven/guide-extension.md)**: Java-side extensions (custom `TypeHandler` / factories / property compilers / `ReloadHandler`).
- **[api-registration.md](api-registration.md) / [api-extension.md](api-extension.md) / [api-diagnostics.md](api-diagnostics.md) / [api-reload.md](api-reload.md)**: Java API references (covering both the v2 multi-source pipeline and legacy entry points).
- **[block-state-properties.md](block-state-properties.md)**: full `state_properties` constraints and the Java `StateProperty` API.
- **[fsm.md](../fsm.md)**: codec fields **inside** state machine definition and animation clip elements.
- **[Data-Driven System Explanations](../../explanation/data-driven/README.md)**: v2 fixed snapshots, multi-source rank ordering, host wiring, and migration.

## Appendix A: Not covered / to be confirmed

- The **complete field table and defaults** of `state_machines` / `animation_clips` elements: this page specifies only the wrapper. For the element schema, treat `StateMachineDefinition.CODEC`, `AnimationClip.CODEC`, and [fsm.md](../fsm.md) as the authority.
- The runtime behavior when `tab` points at a creative tab that does not exist: format validation only checks that `tab` parses as a valid `ResourceLocation` (`JsonItemParser.validateStrict` / `RegTypeHandler.resolveCreativeTab`) and does not check whether that tab is registered at runtime.
- **World-save compatibility policy across restarts**: `DataDrivenBuildPipeline` supplies `PreparedContent.claims()` and `BuildResult`, while comparing historical pack sets/claims and refusing incompatible worlds belongs to the host layer.
