# Data-driven JSON format specification (schema)

> Type: Reference (information-oriented, for looking things up).
> Applies to: Minecraft 1.21.1, NeoForge 21.1.203, Parchment 2024.11.17, Java 21. The format described on this page holds only within this version range.
> Code baseline: `KasugaLibNext` branch `model-loader`, verification date 2026-10-04, baseline commit `ec864a1`. Line numbers in this page refer to the current working tree (the uncommitted data-driven reimplementation).
> Authoritative source: the rules on this page come from the current code and tests. The old `sources` layout has been migrated to the index manifest; each rule gives the class, line number, or test name so you can check it yourself.

---

## 0. Terms and scope

### 0.1 Three concepts you must keep apart

- registration phase: the phase during mod startup and construction in which objects are written into MC registries (such as `BuiltInRegistries.BLOCK`); the source it reads is the mod jar, it completes once, and it cannot hot reload.
- reload phase: the resource re-read triggered by `/reload`; the source it reads is the pack stack, and data packs can override it.
- index file (index manifest): a `.json` under `data/<namespace>/kasuga_lib/data_driven/` that declares only which content files belong to which domain; it contains no registration objects of its own.

A mod's index files can declare only the registration domain, only the reload domain, or both.

### 0.2 What this page covers

Covered: the index file format; the registration-phase content file format (`registry_groups` / `blocks` / `items` / embedded `block_entity`); duplicate id rules; the wrapper format of reload-phase content files; the classification and outlets of errors.

Not covered: the concrete effect of each `properties` key in the registry (see `guide-content.md`); the complete codec fields inside state machine definitions and animation clip elements (see `../../reference/fsm.md` and the codecs under `uml/`); `TypeHandler` extensions on the Java side (see `guide-extension.md`).

## 1. The index file (index manifest)

### 1.1 Location and discovery rules

The index directory is fixed at a **4-segment path**:

```
data/<namespace>/kasuga_lib/data_driven/
```

- Pinned by `JsonTreeBuilder.indexDirectorySegments(modId)`, which returns `{"data", modId, "kasuga_lib", "data_driven"}` (`JsonTreeBuilder.java:745-747`).
- Note that `kasuga_lib` is a fixed spelling and `data_driven` uses an underscore. Writing `kasuga-lib`, or dropping the `kasuga_lib` level, leads to **silently loading 0 entries**; that risk is pinned by `JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout()` with `assertArrayEquals` (`JsonTreeBuilderIndexPathTest.java:27-33`).
- **Every** `.json` file in the directory is treated as an index and read in ascending filename order (`JsonTreeBuilder.listIndexFiles`, `:762-774`; `resolveIndexManifests` aggregates in the order passed in). There is no filename convention; `index.json` is only a habit.
- A missing directory only logs DEBUG and does not count as an error (`JsonTreeBuilder.java:77`).
- The reload side reuses the same path segments when it reads the same directory (`ReloadOrchestrator.indexResourcePath`, `:290`).

### 1.2 Top-level fields

An index file has **only two** top-level fields, each a string array:

| Field | Type | Domain | Meaning |
|------|------|----|------|
| `on_register` | `string[]` | registration phase | Lists the content files parsed during mod construction and written into MC registries |
| `on_reload` | `string[]` | reload phase | Lists the content files parsed on `/reload` and written into the reload bucket |

Both are **optional**, but at least one must appear (D1/D2). The field constants are in `JsonTreeBuilder.java:40,42`; parsing is in `parseIndexManifest` (`:222-250`).

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

**`sources` is now an illegal field.** It is no longer recognized and has no alias any more; it goes down the "unknown top-level field" path: an error is logged plus a bucket entry, and the file thereby "declares nothing" (which logs another "neither is declared" error).

```json
{
  "sources": ["kasuga_lib_content/blocks.json"]
}
```

The example above produces **two** errors: (1) `contains unsupported field 'sources'`; (2) `declares neither 'on_register' nor 'on_reload'`. For the code see `JsonTreeBuilder.parseIndexManifest` (`:226-230` unknown field, `:240-244` neither declared); it is pinned by `JsonTreeBuilderIndexSchemaTest.legacySourcesFieldIsReportedAsUnknown()`.

The old `sources` field and the old flat layout `data/<namespace>/kasugalib/` were both removed by a hard migration, with no compatibility window. If the old directory exists on disk and there is no new index directory, one WARN is logged plus a bucket entry, hinting at migration to `on_register` (`JsonTreeBuilder.java:65-79`).

### 1.4 Field-level rules D1–D6

The **executable version** of these rules is `JsonTreeBuilderIndexSchemaTest` (8 cases, see section 6). The rules are implemented in `parseIndexManifest` (single file) and `resolveIndexManifests` (aggregation across files).

- **D1 Declare at least one field**: neither field appears (an empty file, or a file with unknown fields only) → error + bucket entry, and the file contributes no paths. An empty file (GSON parses it as `null`) is reported too (`JsonTreeBuilder.java:240-244`; tests `declaringNeitherDomainIsReportedAndConsumesNothing` / `emptyFileIsReportedInsteadOfSilentlyIgnored`).
- **D2 An empty array is legal**: `{"on_register": []}` is legal and **counts as declared** (`JsonTreeBuilder.java:237`; test `emptyArrayIsValidAndCountsAsDeclared`).
- **D3 A failure in one field does not implicate its sibling**: when `on_register` is not an array only that field is reported and `on_reload` is still read, and the other way round (`readPathArray`, `:256-277`; test `nonArrayFieldDoesNotAbortTheSibling`). A non-string element in the array is reported and skipped, and the remaining elements are kept (`:268-274`).
- **D4 The aggregate warning branches on "was anything ever declared"**: a pure reload mod that declares only `on_reload` does **not** raise "registration domain not declared"; only when **no file declares either field** does the aggregation stage record one WARN + one bucket entry (`resolveIndexManifests`, `:323-326`; test `pureReloadModDoesNotRaiseTheAggregateError`). When every file at the single-file level fails its declaration, the aggregate error is still raised (test `allBrokenSchemaStillRaisesTheAggregateError`).
- **D5 `sources` is an unknown field**, not an alias (`:226-230`; test `legacySourcesFieldIsReportedAsUnknown`).
- **D6 A path listed in both arrays fails closed**: the same path (within one file or across files) appearing in both arrays → error + bucket entry, and that path is **consumed by neither domain** (it is deleted from both lists). The check spans **all** index files of that mod (`resolveIndexManifests`, `:308-316`; test `crossListedPathIsConsumedByNeitherDomain`).

### 1.5 Path entry rules

Array elements are paths relative to `data/<namespace>/` and **must** carry the `.json` suffix. Validation is in `JsonTreeBuilder.validateSourcePath` (`:383-392`), and the reload side reuses the same function (`ReloadOrchestrator.collectIndex`, `:369`). Violating any rule → error + bucket entry, and that entry is skipped:

| Rule | Message fragment when violated |
|------|------------------|
| Non-empty | `path is empty` |
| Does not start with `/` (must be relative) | `path must be relative` |
| Ends with `.json` | `path must include the '.json' suffix` |
| No empty segment (`//`) | `path contains an empty segment` |
| No `.` or `..` segment | `path must not contain '.' or '..'` |

Also:

- The fact that a path **cannot cross namespaces** is structural: `parseSource` always joins the path under `data/<modId>/` (`JsonTreeBuilder.java:414-420`). For the same reason, an entry pointing at `assets/` can never take effect.
- The same path listed in several places within the same mod → **silently deduplicated**, parsed only once (`parsedSources` in `parseSource`, `:404`), with no error.
- The reload side additionally requires the path to form a legal `ResourceLocation` (only `[a-z0-9/._-]` is allowed); illegal characters such as uppercase are reported separately (`ReloadOrchestrator.java:369`).
- An illegal path, a missing file, and an unreadable JSON file all produce error + bucket entry (`parseSource`, `:395-436`).

### 1.6 Merging multiple manifests

All index files of the same mod are **fully read in** first and then consumed together: paths are concatenated by "ascending index filename → array writing order within the file" (`JsonTreeBuilder.java:92-96,296-301`). That is why D6's double-listing check works across files, and why each content file is parsed at most once per load round.

## 2. Registration-phase content files (listed under `on_register`)

### 2.1 File top-level structure

In a content file the **top-level field name is the top-level registration type**, and the value must be an **array of objects**. There are currently 3 top-level types:

| Top-level field | handler | phase | Effect |
|----------|---------|-------|------|
| `registry_groups` | `RegistryGroupHandler` | 0 | Establish property groups (parent/child) |
| `blocks` | `BlockTypeHandler` | 1 | Register blocks |
| `items` | `ItemTypeHandler` | 1 | Register standalone items |

`block_entities` is **not** a top-level field; it is an embedded type, written as in section 2.5.

One content file can contain several top-level fields at once (for example one `registry_groups` set and one `blocks` set), dispatched one by one by `dispatchContent` (`JsonTreeBuilder.java:461`).

```json
{
  "registry_groups": [
    { "id": "mymod:panels", "properties": { "no_occlusion": true } }
  ],
  "blocks": [
    {
      "id": "mymod:panel",
      "type": "simple_block",
      "registry_group": "mymod:panels",
      "properties": { "destroy_time": 2.0 },
      "item_properties": { "tab": "mymod:main" }
    }
  ]
}
```

Validation of top-level fields:

- Unknown top-level field → error + bucket entry, and **the remaining fields in the same file dispatch as usual** (partial apply; `JsonTreeBuilder.java:475-490`).
- A known field whose value is not an array → error + bucket entry, the remaining fields proceed as usual (`:504-510`).
- An array element that is not a JSON object → error + bucket entry, that element is skipped, the remaining elements proceed as usual (`:504-516`).

### 2.2 `registry_groups` entries

Parsing is in `RegistryGroupHandler.parse` (`RegistryGroupHandler.java:26-33`), application in `store` (`:54-90`).

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | yes | — | Group id. **Matched by the raw string**, with no namespace normalization |
| `parent` | string | no | `null` → attached to the root group | Parent group id; when the parent does not exist it falls back to the root group (`:58-63`) |
| `properties` | object | no | — | Block properties passed down to child content (see section 2.6) |
| `item_properties` | object | no | — | Item properties passed down to child content; `tab` is singled out as this group's creative tab (`:73-76`) |

- A group has **no `type` field**.
- Blocks and items look their group up through `registry_group` using the raw string; therefore `id` is not normalized and `mymod:panels` and `panels` are two different groups (`RegistryGroupHandler.resolveIdentity`, `:49-52`).
- Before applying, a topological sort is done by `parent`: parents before children; a cycle only gets a WARN, not an error, and falls back to the original order (`JsonTreeBuilder.topoSortGroupDefs`, `:869`).

### 2.3 `blocks` entries

Parsing is in `BlockTypeHandler.parse` (`BlockTypeHandler.java:25-34`), generic application in `RegTypeHandler.apply` (`RegTypeHandler.java:62-112`).

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | yes | — | Block id, either `namespace:path` or a bare path |
| `type` | string | yes | — | Factory name, looked up in `FactoryRegistry.BlockFactory` (`BlockTypeHandler.createRegistration`, `:59-64`) |
| `registry_group` | string | no | `null` → attached to the root group | Owning group id (raw-string match) |
| `properties` | object | no | — | Block properties, see section 2.6 |
| `item_properties` | object | no | — | Item properties for the block's item; `tab` decides the creative tab (`BlockTypeHandler.resolveCreativeTab`, `:51-57`) |
| `params` | object | no | `null` | Passed to the factory verbatim by `create(path, params)` |
| `state_machine` | string | no | — | Forwarded into the BE params only when the entry contains a `block_entity` (see section 2.5) |

- When `id` or `type` is missing, `parse` throws (`json.get("id").getAsString()`); the registration phase's element-level tolerance catches that exception → error + bucket entry, so **only that entry is lost while sibling entries in the same file register as usual** (`JsonTreeBuilder` produces `entry <i> in '<key>' failed to parse: <msg>; entry skipped`). Missing values for the other fields fall back to their defaults.
- `type` finds no matching factory → only a WARN is logged, the entry is skipped, and **it does not go into the bucket** (`RegTypeHandler.java:70-74`).
- **Unknown keys inside the object are silently ignored** (parse reads known keys only), the opposite of the error for an unknown top-level file key; see section 2.7.

### 2.4 `items` entries

Parsing is in `ItemTypeHandler.parse` (`ItemTypeHandler.java:22-30`).

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | yes | — | Item id |
| `type` | string | yes | — | Factory name, looked up in `FactoryRegistry.ItemFactory` (`:56-60`) |
| `registry_group` | string | no | attached to the root group | Owning group id |
| `properties` | object | no | — | Item properties, see section 2.6; `tab` decides the creative tab (`:48-53`) |
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

- The top-level field name is `block_entities`, but the embedded key name is `block_entity` (`BlockEntityTypeHandler.getTypeName` :21, `getParentTypeName` :27, `getEmbeddedKeyName` :30).
- Extraction logic: `BlockEntityTypeHandler.extractEmbedded` (`:33-44`) injects the host block's `id` into the embedded object's `_parent_block`; if the host block has a top-level `state_machine` of JSON primitive type (string / number / boolean), it is forwarded into the embedded object's `params.state_machine` (`:37-42`). The test is `isJsonPrimitive()`, so number and boolean pass too, converted to text by `getAsString()` before being stored in `params`; objects, arrays, and null are ignored.

Fields of the embedded `block_entity` object:

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `type` | string | yes | — | Block entity factory name, looked up in `FactoryRegistry.BlockEntityFactory` |
| `params` | object | no | `null` | Passed to the factory; the common keys `model` / `state_machine` are interpreted by the concrete factory |
| `_parent_block` | string | — | injected | **Do not write this by hand**; it is injected by `extractEmbedded` |

- Duplicate detection: two BE entries attached to the **same host block** count as duplicates (`resolveIdentity`, `:66-68`, using the host block's effective id).
- Host block not found / unknown BE factory → only a WARN, no bucket entry (`BlockEntityTypeHandler.apply`, `:75-86`).

### 2.6 Inner keys of `properties` / `item_properties`

A top-level field's `properties` and `item_properties` are open objects whose keys are claimed by two parsers; an unknown key only gets a WARN and is ignored, never an error.

**Block properties `properties`** (`JsonPropertyParser.buildCompilers`, `JsonPropertyParser.java:148-174`):

| Key | Value type | Key | Value type |
|----|--------|----|--------|
| `no_occlusion` | bool | `light_emission` | int |
| `no_collision` / `no_collission` (spelling alias) | bool | `friction` | float |
| `requires_correct_tool` | bool | `speed_factor` | float |
| `replaceable` | bool | `jump_factor` | float |
| `dynamic_shape` | bool | `destroy_time` | float |
| `random_ticks` | bool | `explosion_resistance` | float |
| `no_loot_table` | bool | `strength` | float or a `[destroy, resist]` two-element array |
| `ignited_by_lava` | bool | `map_color` | `DyeColor` name (lowercase) |
| `liquid` | bool | `sound_type` | sound type name (see below) |
| `force_solid_on` | bool | | |
| `air` | bool | | |
| `no_terrain_particles` | bool | | |

- The legal values of `sound_type` come from `JsonPropertyParser.buildSoundTypeMap` (`:97-146`), such as `stone` / `wood` / `metal` / `glass`; an unknown value only warns.
- `map_color` uses MC `DyeColor` names; an unknown value only warns.

**Item properties `item_properties` (block entries) / `properties` (item entries)** (`JsonItemParser`, `JsonItemParser.java:20-36`):

| Key | Value type | Meaning |
|----|--------|------|
| `stacks_to` | int | Maximum stack size |
| `rarity` | string | `COMMON` / `UNCOMMON` / `RARE` / `EPIC` (case-insensitive) |
| `fire_resistant` | bool | Fire resistance |
| `durability` | int | Durability |
| `no_repair` | bool | Cannot be repaired |
| `tab` | string | Creative tab `ResourceLocation`; consumed separately by the loader and not passed into the modifiers above |

Properties are inherited along the parent chain, and a child entry overrides its parent (`RegTypeHandler.apply` sets `registry_group` as the parent, `Reg.applyProperties` applies parents before children).

### 2.7 Unknown keys inside an object vs unknown top-level file keys

The two positions differ in strictness, which is the difference easiest to trip over:

- An unknown **top-level file** field → error + bucket entry (`dispatchContent`, `JsonTreeBuilder.java:475-490`). A misspelled `blokcs` is caught.
- An unknown key **inside an object** → silently ignored (each handler's `parse` reads known keys only), with no error. For example, an extra `foo` in a block entry produces no diagnostic at all.

### 2.8 An embedded type written as a top-level field by mistake

Writing `block_entities` as a top-level field (instead of putting it in the `block_entity` key of a `blocks` entry) goes down the "unknown top-level field" path: error + bucket entry, with the message naming the `block_entity` key of a `blocks` entry as where it belongs, and **the rest of the file's `blocks` dispatch as usual**.

```json
{
  "block_entities": [ { "type": "test_be" } ],
  "blocks": [ { "id": "mymod:ok", "type": "simple_block" } ]
}
```

The behavior is pinned by `JsonTreeBuilderEmbeddedTypeDispatchTest.topLevelEmbeddedTypeIsReportedAndTheRestOfTheFileStillDispatches()`. This covers only the case of writing an embedded type as a top-level field; if the embedded object itself fails to parse from a missing field, the exception is likewise caught by element-level tolerance, so **only that embedded element is lost while the rest of the file's entries proceed as usual** (the same as a missing `id`/`type` in section 2.3).

## 3. Duplicate ids and last-wins

Deduplication happens **before any registration side effect**: once parsing has finished entirely the conflict is adjudicated first, and a loser never reaches `apply` at all (`JsonTreeBuilder.resolveDuplicates`, `:652`). This is because MC's `MappedRegistry.register` does not reject duplicate keys, and applying a duplicate would leave inconsistent entries (`DuplicateIdResolver.java:11-14`).

### 3.1 Determining identity

The conflict space is `(typeName, identity)`; the same id under different types is not a conflict. The identity of each type (`TypeHandler.resolveIdentity`):

| Type | identity | Normalization |
|------|----------|--------|
| `blocks` / `items` | `EffectiveId.of(modId, id)` | No namespace or a `minecraft:` prefix → `<modId>:path`; other namespaces are kept verbatim |
| `block_entities` | the host block's effective id | same as above |
| `registry_groups` | the raw `id` string | **not normalized** |

`EffectiveId.of` is at `EffectiveId.java:39-52`, with the table test `EffectiveIdTest` (`unnamespacedIdsResolveUnderTheModNamespace` / `explicitMinecraftNamespaceResolvesToTheOwningMod` / `otherNamespacesAreKeptVerbatim`).

### 3.2 What "last" means exactly

The "last" in last-wins is a **position in the load sequence**, not a path's lexicographic order:

1. Between index files: ascending filename order;
2. Within one index file: the writing order of the `on_register` array;
3. Within one content file: the writing order of the type-field arrays;
4. Embedded BEs: they follow the position of the host `blocks` entry.

Rationale: `JsonTreeBuilder` builds the candidate sequence in the order above (`:105-110,655`), and `DuplicateIdResolver.resolve` takes the last one purely by **the index passed in** (`:73-100`). `DuplicateIdResolverTest.lastMeansLastInSequenceNotLexicographicallyLastSource()` pins the case where a source that comes earlier lexicographically but later in load order still wins.

### 3.3 Result

- The winner reaches `apply`; the loser is discarded whole, and its embedded BE is isolated along with it (`JsonTreeBuilder.java:678`).
- Each conflict records a **WARN log line plus one loading-error bucket entry** (`:667-670`). Note that the log level is WARN, but it **lands in the error bucket**.
- When there are conflicts, one summary WARN is recorded as well (`:672-674`).
- The diagnostic message contains the type field, the id, and the source paths of the winner and the loser (`DuplicateIdResolver.describe`, `:109-115`).

## 4. Reload-phase content files (listed under `on_reload`)

### 4.1 The two content types and how they are discovered

`ReloadOrchestrator` is the only orchestrator in the reload domain (`ReloadOrchestrator.java:121`), and it knows no type name itself: the `typeName()` of every registered `ReloadHandler` is a legal top-level key, and the orchestrator walks `ReloadHandlerRegistry` to obtain the key set. The two handlers currently registered on the modelling side are `state_machines` and `animation_clips` (`FsmReloadHandler` / `FsmClipsReloadHandler`, see section 4.6).

| Content | Top-level key | Discovery |
|------|--------|----------|
| State machine definitions | `state_machines` | **directory glob** `data/<ns>/state_machines/*.json` **+** listed in `on_reload` |
| Animation clips | `animation_clips` | **only** listed in `on_reload` |

- `state_machines/` has a directory glob: `FsmReloadHandler.globDirectories()` (`FsmReloadHandler.java:69-71`) declares `StateMachineDefinitionLoader.PATH` (`StateMachineDefinitionLoader.java:39`), enumerated by `ReloadOrchestrator.collectGlob` (`:348-367`) with `ResourceManager.listResources`. A state machine file is read even when no index lists it.
- `animation_clips/` **has no directory glob**. A clip file that no index lists is **never read** (`AnimationClipLoader.java:28-29`; `FsmClipsReloadHandler.globDirectories` keeps the default empty set).
- `state_machines/` files listed by an index are skipped by the glob entry point, to avoid reading them twice (`ReloadOrchestrator.collectGlob`, `:348-367`).
- Application order: glob-discovered first, index-listed second; on the same id the latter wins (class docs `:77-91`; `register`, `:507-526`).

### 4.2 `state_machines` wrapper

Each file is a wrapper object **carrying** the `state_machines` top-level key, whose value must be an array of definitions (extra top-level keys no longer reject the file, see section 4.4):

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

- One file can hold several definitions, and **the array order is the in-file last-wins order** (`StateMachineDefinitionLoader.java:14-33`).
- The element schema is decided by `StateMachineDefinition.CODEC`; this page specifies only the wrapper, and element fields are in `../../reference/fsm.md` and the corresponding codec.

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

- The element carries its own `id` (the very id `states[].clip` references), and the loader does not extract an id additionally (`AnimationClipLoader.java:21-27`).
- Keys in the element that the codec does not name are ignored, and can serve as comments or editor metadata (`:26-27`).
- The file takes effect only in `on_reload` and registers into the reload bucket; a SCRIPT clip of the same name is not overridden by the file (`AnimationClipLoader.register`, `:113-121`).

### 4.4 File-level strictness, element-level tolerance

The two decoders isolate failures identically (`StateMachineDefinitionLoader.decodeFile` :67-100; `AnimationClipLoader.decodeFile` :69-102):

- **File level**: a non-object body, a missing top-level key, or a non-array value → **the whole file is rejected**, with one error + bucket entry. `{"state_machines": {}}` is rejected as a whole (`StateMachineDefinitionLoaderTest.nonArrayValueRejectsWholeFile`).
- **File level (genuinely unknown key)**: a top-level key claimed by no reload type → one `unsupported top-level field 'extra'` + bucket entry, but **known keys still contribute as usual** (partial apply, no longer a whole-file rejection). In `{"state_machines": [...], "extra": 1}`, `state_machines` registers as usual (`StateMachineDefinitionLoaderTest.extraTopLevelKeyDoesNotAffectThisType()`; `AnimationClipLoaderTest.ignoresAnExtraTopLevelKey()`).
- **Multiple top-level keys are legal**: one file can carry both `state_machines` and `animation_clips`, and the two decoders each read their own key and each contribute their own entries (`AnimationClipReloadTest.mixedTopLevelKeysAreConsumedByBothHandlers`).
- **Element level**: an array element that is not an object, or that cannot be decoded for a missing field → only that element is skipped, its siblings proceed as usual, and each one is reported. See `StateMachineDefinitionLoaderTest.badElementIsSkippedWhileSiblingsDecode()` and `AnimationClipLoaderTest.skipsAMalformedElementAndKeepsItsSiblings()` / `skipsAnElementWithoutAnId()`.
- An empty array is legal (`AnimationClipLoaderTest.emptyClipArrayIsValid()`).

### 4.5 Reload domain routing and the reverse D9 hint

- The domain is decided by the **index array**, and the content type by the **top-level key**.
- An unknown top-level key in a reload file → error + bucket entry, with a **reverse hint** attached: if these fields are registration content (such as `blocks`/`items`/`registry_groups`), list the file under `on_register` instead (`ReloadOrchestrator.java:439-446`).
- One file can carry both content types at once: the two handlers each claim their own top-level key and each contribute their own entries (`ReloadOrchestrator.dispatch`, `:429-470`). Unknown top-level keys are determined by `ContentStructure.unknownKeys`, and there is no longer a "single top-level key" check.
- The reload wrap-up re-checks the clips referenced by state machines; a dangling reference only records a WARN + bucket entry, and does not affect registration (`FsmReloadHandler.afterReload`, `:95-116`).

### 4.6 Extending to a new reload type

Reload types are not hardcoded: extend or implement `ReloadHandler<T>` and register it by calling `ReloadHandlerRegistry.register(handler)` from a `@Context` bean's `@PostConstruct`, with no need to change the orchestrator. `typeName()` is the top-level key this type claims, and doubles as the deduplication collision space. For detailed steps and a minimal example see [guide-extension.md](../../guides/data-driven/guide-extension.md) §6, and for interface signatures see [api-reload.md](api-reload.md) §13.

## 5. Error classification and outlets

### 5.1 Errors (error log + bucket entry)

Format errors in the registration and reload phases are basically all in this list:

| Case | Source |
|------|------|
| Unknown index field / field not an array / element not a string | `JsonTreeBuilder.java:226-274` |
| A single file declares neither domain; every file declares neither | `:240-244` (error) / `:323-326` (WARN + bucket) |
| The same path listed in both arrays | `:308-316` |
| Failure reading an index file or directory | `:188`, `:767-771` |
| Unknown top-level field in a content file, field not an array, element not an object | `:475-516` |
| Illegal content file path / missing file / parse exception | `:396-436` |
| handler `apply` throws | `:712` |
| Duplicate id conflict | `:667-670` (WARN log + bucket entry) |
| Reload side: manifest read failure, illegal path, missing file, wrapper shape violation, unknown top-level key | `ReloadOrchestrator.java:307,369,405-470` |
| Duplicate id on the reload side | `:545-556` (WARN log + bucket entry) |
| Dangling clip reference at reload wrap-up | `FsmReloadHandler.java:111-116` (WARN log + bucket entry) |

### 5.2 Warnings (warn, no bucket entry)

The following cases only log and do not write a bucket entry, because the entry is downgraded rather than malformed:

- Unknown factory `type` → the entry is skipped (`RegTypeHandler.java:70-74`).
- Unknown `properties` / `item_properties` key → that key is ignored (`JsonPropertyParser.java:50-52`; `JsonItemParser.java:63`).
- Unknown `map_color` / `sound_type` (`JsonPropertyParser.java:80-82,90-92`).
- BE host block not found / unknown BE factory (`BlockEntityTypeHandler.java:77,83`).
- A cycle in group references (`JsonTreeBuilder.java:823-825`).

### 5.3 Reading diagnostics

All errors are bucketed by **mod id**. The reading API:

- `JsonTreeBuilder.getLoadingErrors(modId)` / `getLoadingErrorsByMod()` / `getLoadingErrors()` (`JsonTreeBuilder.java:743-775`).
- The unified facade `Diagnostics`: `Diagnostics.errors(modId)`, `Diagnostics.errors(Domain, key)`, `Diagnostics.summarize()` (`Diagnostics.java:64-166`). The mod dimension delegates verbatim to `JsonTreeBuilder`'s bucket (registration phase); the domain × source dimension **already has writers**: the reload phase uses `Domain.RELOAD_DATA` with the key = namespace (`Diagnostics.java:21-31,96-105`).
- The contract the tests assert: `assertEquals(List.of(), JsonTreeBuilder.getLoadingErrors(modId))`.

### 5.4 D9 hint text (content listed under the wrong domain)

Conditional hints in both directions; the error message strings are a public contract:

- **Registration side**: `on_register` lists a reload-domain file (with `state_machines` or the like at the top level) → it goes down the unknown-field path, and the message ends with "list this file under `'on_reload'` instead". The key names in the example are enumerated from `ReloadHandlerRegistry` at runtime rather than written literally; when the registry is empty (pure JVM unit tests) the example is omitted. The constraint still holds that data-driven does not depend on modelling: both registries are owned by data-driven, and handlers are registered by the modelling side, see `JsonTreeBuilder.java:559-570` and section 4.6; test `JsonTreeBuilderReloadHintTest.reloadDomainContentUnderOnRegisterIsReportedWithOnReloadHint()`.
- **Reload side**: `on_reload` lists a registration-domain file → the message ends with "list the file under `'on_register'` instead", see `ReloadOrchestrator.java:441-446`.

Partial-apply guarantee: listing content under the wrong domain still leaves the other domain in the same file processed as usual (`JsonTreeBuilderReloadHintTest.halfApplyStillDispatchesSiblingRegistrationFields()`).

## 6. Rule-to-source table

| Rule | Source (class:line / test method) |
|------|--------------------------|
| 4-segment index path | `JsonTreeBuilder.indexDirectorySegments` `:745-747`; `JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout` |
| Only `on_register`/`on_reload` at the top level | `JsonTreeBuilder.java:40,42,226-230`; `JsonTreeBuilderIndexSchemaTest.legacySourcesFieldIsReportedAsUnknown` |
| D1 at least one domain declared | `:240-244`; `declaringNeitherDomainIsReportedAndConsumesNothing` / `emptyFileIsReportedInsteadOfSilentlyIgnored` |
| D2 an empty array is legal | `:237`; `emptyArrayIsValidAndCountsAsDeclared` |
| D3 siblings are not implicated | `:256-277`; `nonArrayFieldDoesNotAbortTheSibling` |
| D4 a pure reload mod is not falsely reported | `:323-326`; `pureReloadModDoesNotRaiseTheAggregateError` / `allBrokenSchemaStillRaisesTheAggregateError` |
| D5 `sources` is illegal | `:226-230`; `legacySourcesFieldIsReportedAsUnknown` |
| D6 listing in both arrays fails closed | `:308-316`; `crossListedPathIsConsumedByNeitherDomain` |
| Path entry rules | `validateSourcePath` `:383-392` |
| Top-level type table | `JsonTreeBuilder.java:461-470`; each `*TypeHandler.getTypeName` |
| `blocks` fields | `BlockTypeHandler.parse` `:25-34` |
| `items` fields | `ItemTypeHandler.parse` `:22-30` |
| `registry_groups` fields | `RegistryGroupHandler.parse` `:26-33` |
| Embedded `block_entity` | `BlockEntityTypeHandler` `:21-68`; `JsonTreeBuilder.java:528-535` |
| An embedded type written at the top level applies partially | `JsonTreeBuilder.java:475-490`; `JsonTreeBuilderEmbeddedTypeDispatchTest` |
| last-wins identity determination | `EffectiveId.of` `:39-52`; `RegTypeHandler.resolveIdentity` `:58-60`; `RegistryGroupHandler.resolveIdentity` `:50-52`; `BlockEntityTypeHandler.resolveIdentity` `:66-68` |
| last-wins order | `JsonTreeBuilder.java:105-110,655`; `DuplicateIdResolver.resolve` `:73-100`; `DuplicateIdResolverTest.lastMeansLastInSequenceNotLexicographicallyLastSource` |
| Conflict diagnostics | `DuplicateIdResolver.describe` `:109-115`; `JsonTreeBuilder.java:667-674` |
| The two reload wrappers | `StateMachineDefinitionLoader` `:36-102`; `AnimationClipLoader` `:42-121` |
| Directory glob vs index-only | `ReloadOrchestrator.collectGlob` `:348-367`; `AnimationClipLoader.java:29`; `FsmReloadHandler.globDirectories` `:69-71` |
| File-level strictness / element-level tolerance | all cases in `StateMachineDefinitionLoaderTest` / `AnimationClipLoaderTest` |
| D9 hints in both directions | `JsonTreeBuilder.java:559-570`; `ReloadOrchestrator.java:439-446` |
| Diagnostic buckets and the facade | `JsonTreeBuilder.java:806-866`; `Diagnostics.java:64-166` |

## 7. Boundaries with other documents

- This page (schema.md): the authoritative reference for the JSON format, covering fields, types, requiredness, path rules, and error classification.
- **`intro.md`**: an introductory explanation that answers "what is this, and why use it".
- **`guide-content.md`**: a task-oriented guide that answers "how do I build my first content file"; its property key semantics and troubleshooting checklist take this page's format as authoritative.
- **`guide-extension.md`**: Java-side extensions (custom `TypeHandler` / factories / property compilers).
- **`api-registration.md` / `api-extension.md` / `api-diagnostics.md` / `api-reload.md`**: Java API references; where their index chapters still say `sources`, this page is authoritative.
- **`../../reference/fsm.md`**: the codec fields **inside** state machine definition and animation clip elements.

## Appendix A: Not covered / to be confirmed

- The **complete field table and defaults** of `state_machines` / `animation_clips` elements: this page specifies only the wrapper. For the element schema, treat the corresponding codec (`StateMachineDefinition.CODEC` / `AnimationClip.CODEC`) as the final authority; this page does not check it field by field.
- The runtime behavior when `tab` points at a creative tab that does not exist: the code only parses the string into a `ResourceLocation` (`BlockTypeHandler.resolveCreativeTab`) and does not validate at the format level whether that tab exists.
- Data pack indexes (data packs carrying `on_register` data) are not done this round; the registration phase reads the mod jar only, and a data pack's `on_register` section is not consumed. Whether a "snapshot comparison WARN" is needed is not implemented yet.
