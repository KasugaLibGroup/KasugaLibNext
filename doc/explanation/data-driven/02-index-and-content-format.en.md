# Index and JSON Format

This page describes the current registration handlers, reload handlers, and resource layout. Complete codec fields inside objects remain defined by each handler, while the three-layer grammar stays consistent: manifests select files, top-level keys in content files select handlers, and array elements are parsed by handlers. The v2 registration path also requires source bytes to be fixed up front rather than feeding a live, mutating installation directory directly into a build.

## Standard Directory Layout

Relative to one source root:

```text
data/
  example/
    kasuga_lib/
      data_driven/
        index.json
    kasuga_content/
      groups.json
      blocks.json
      state_machines/
        panel.json
assets/
  example/
    blockstates/
    models/
    textures/
    lang/
pack.mcmeta
```

The index directory is fixed at:

```text
data/<namespace>/kasuga_lib/data_driven/
```

The spellings `kasuga_lib` and `data_driven` are part of the protocol. `ContentSource.indexes()` recognizes only namespaces declared in the descriptor, and the directory hierarchy must be exact: `data/<ns>/kasuga_lib/data_driven/<file>.json`. Only direct child `.json` files in this directory are collected; deeper subdirectories are not traversed recursively.

Paths listed in a manifest are relative to `data/<namespace>/`:

```text
kasuga_content/blocks.json
state_machines/panel.json
```

Content files may originate from an ordinary mod JAR, external directory, or ZIP, though production paths should first capture and pin them into a `ResourceSnapshot` via `ResourceSources`. Whether a source root includes `pack.mcmeta` affects only MC resource projection, not whether `SnapshotContentSource` can read registration JSON.

## Roots and Scene Indexing

In platform identity, `rootId` identifies a concrete resource root. In these pages, "scene indexing" refers to the composition of which roots the current registration scene reads from and which entry manifests each root exposes—it is not a separate JSON schema or Java type, and is implemented by combining:

```java
ResourceSnapshot snapshot = fixedRoot.capture();
ContentSource source = ContentSourceFactory.snapshot(
        snapshot,
        descriptor,
        namespaces,
        label,
        declaredIndexes
);
```

- By default, `ContentSource.indexes()` discovers only direct `.json` children under `data/<declared-namespace>/kasuga_lib/data_driven/`.
- `ContentSourceFactory.snapshot(..., declaredIndexes)` restricts the entry set to the exact logical paths declared by the caller; pass `null` to restore default discovery.
- Passing an empty collection declares that the root has no manifests, which is valid for an assets/data-only root that still contributes resources through the platform's MC projection.
- `declaredIndexes` filters only manifests; it does not prune files from the snapshot. Content in the same root referenced by a manifest can still be opened via `open(path)`, and the host is responsible for verifying that declared indexes exist and match allowed layouts.
- Each index is resolved within its own `(sourceId, rootId)`. Distinct roots retain separate definition provenance and independent claims even if they share manifest names, namespaces, or logical paths.

Typical train-pack layouts map the pack root to `main` and each parcel listed in the manifest order to `parcel-*`. `main` can auto-discover pack-root indexes, whereas parcel roots should expose only the entry paths declared in `parcel.json.indexes`. Business IDs such as `<namespace>:<parcel>` cannot serve directly as platform `rootId` values because colons are disallowed in platform IDs; the host should maintain a stable mapping. These names are host conventions, not semantics enforced by KasugaLib.

Every active `(sourceId, rootId)` must have a unique, non-negative global `rank`. Ranks must be allocated sequentially—or at least distinctly—across all `main` and `parcel` roots of every pack, never restarting from 0 per pack. `DataDrivenBuildPipeline` rejects duplicate ranks; LEGACY may still continue to `apply`, but a result carrying errors should not be treated as healthy.

## Index Manifest

A manifest is a JSON object permitting only two top-level fields:

```json
{
  "on_register": [
    "kasuga_content/groups.json",
    "kasuga_content/blocks.json"
  ],
  "on_reload": [
    "animation_clips/wheels.json"
  ]
}
```

| Field | Type | Purpose | Empty Array |
| --- | --- | --- | --- |
| `on_register` | `string[]` | Parsed at startup to build `Reg` nodes | Valid; marks the domain as declared with no registration files |
| `on_reload` | `string[]` | Parsed from the pack stack on initial Manager attach and every `/reload` | Valid; marks the domain as declared with no reload files |

Both fields are individually optional, but they cannot both be omitted. A pack declaring only `on_reload` is a valid reload-only source.

Every path must be:

- Non-empty;
- Relative (must not start with `/`);
- Separated by `/`;
- Free of backslashes, Windows drive prefixes, empty segments, `.`, `..`, NUL, or other ISO control characters;
- Suffixed with `.json`;
- Resolved exclusively under `data/<namespace>/` by relative concatenation.

When the same path appears in both `on_register` and `on_reload`, manifest diagnostics record `MANIFEST_INVALID` and neither domain consumes the file. `DataDrivenBuildPipeline` silently deduplicates paths repeated within the same domain, and the legacy registration entry point uses `parsedSources` to avoid parsing the same registration file twice. Runtime reload manifest reading currently does not deduplicate repeated entries within `on_reload`, so each path should appear only once.

The legacy field `sources` has been removed. Using it yields `MANIFEST_INVALID` and it is not treated as an alias for `on_register`.

A single source may contain multiple manifests. Their declarations are aggregated per namespace; the new pipeline checks cross-domain conflicts first, then sorts `on_register` paths by full logical path before parsing.

Both the multi-source pipeline and runtime reload ultimately construct `ResourceLocation` values from `on_reload` paths. Path casing and characters must match `[a-z0-9/._-]`; however, `DataDrivenBuildPipeline`'s reload preflight checks only `LogicalPath` validity and source existence without constructing `ResourceLocation`, so passing strict preflight does not substitute for runtime `ResourceLocation` validation during reload.

Under STRICT mode, "JSON object" is enforced by `StrictResourceJson`:

- Input must be valid UTF-8 and the entire file must be a single JSON object;
- `reader.setLenient(false)` rejects comments, single-quoted fields, trailing commas, and non-standard JSON;
- Duplicate keys in any object fail immediately instead of silently keeping the last value;
- Default limits are depth 64, 100,000 nodes, and 8 MiB of text;
- Unknown manifest fields, omitting both domains, non-array fields, non-string entries, and cross-domain path conflicts are all blocking diagnostics.

LEGACY mode uses Gson's lenient parsing. Even if LEGACY reports manifest schema errors, it may still proceed to `apply`, so startup success does not prove schema health.

## Registration-Phase Top-Level Fields

A content file is a JSON object whose top-level keys name handler types and whose values are arrays of objects:

```json
{
  "registry_groups": [],
  "blocks": [],
  "items": []
}
```

Built-in top-level handlers:

| Field | Handler | Phase | Occupies Registry? |
| --- | --- | --- | --- |
| `registry_groups` | `RegistryGroupHandler` | `PHASE_GROUPS = 0` | No |
| `blocks` | `BlockTypeHandler` | `PHASE_CONTENT = 1` | Yes |
| `items` | `ItemTypeHandler` | `PHASE_CONTENT = 1` | Yes |
| Embedded `block_entity` | `BlockEntityTypeHandler` | `PHASE_EMBEDDED = 2` | Yes |

`block_entities` is `BlockEntityTypeHandler.getTypeName()`, but that handler's `getParentTypeName()` is `blocks` and `getEmbeddedKeyName()` is `block_entity`. It may appear only under the `block_entity` key of a block entry and cannot be written as a top-level field.

A content file may declare multiple top-level fields. Unknown top-level fields are warnings in LEGACY and errors in STRICT. When a known field is not an array, an array element is not an object, or `parse` throws, an error is always recorded: LEGACY continues applying parseable sibling entries, while STRICT rejects the entire batch before `apply`.

Whether unknown keys inside a handler's entry object fail depends on the handler. Built-in Gson definitions generally ignore undeclared fields; top-level file fields are checked uniformly by the pipeline.

## `registry_groups`

Registry groups provide parent-to-child property inheritance for blocks and items.

```json
{
  "registry_groups": [
    {
      "id": "example:base",
      "properties": {
        "strength": [2.0, 6.0]
      }
    },
    {
      "id": "example:panels",
      "parent": "example:base",
      "item_properties": {
        "tab": "example:main",
        "stacks_to": 16
      }
    }
  ]
}
```

| Field | Type | Required | Meaning |
| --- | --- | --- | --- |
| `id` | string | Yes | Group lookup key, matched by exact raw string |
| `parent` | string | No | Parent group id; attaches to the JSON root group when omitted |
| `properties` | object | No | Block property modifiers |
| `item_properties` | object | No | Item property modifiers; `tab` is also stored as the group's default creative tab |

Group IDs do not undergo `EffectiveId` normalization: `panels` and `example:panels` are distinct groups. A block or item's `registry_group` must match the group's raw `id` string verbatim.

Parent groups are applied before child groups. STRICT verifies that the parent group exists and is visible to the current owner, and rejects parent cycles.

## `blocks`

```json
{
  "blocks": [
    {
      "id": "example:control_panel",
      "type": "data_driven_block",
      "registry_group": "example:panels",
      "properties": {
        "destroy_time": 2.0,
        "requires_correct_tool": true
      },
      "state_properties": {
        "active": {
          "type": "boolean",
          "default": false
        }
      },
      "item_properties": {
        "tab": "example:main",
        "stacks_to": 64
      },
      "params": {
        "factory_specific": true
      },
      "block_entity": {
        "type": "example_be",
        "params": {
          "model": "example:models/block/control_panel"
        }
      }
    }
  ]
}
```

| Field | Type | Required | Purpose |
| --- | --- | --- | --- |
| `id` | string | Yes | Block id |
| `type` | string | Yes | Block factory type in `FactoryRegistry` |
| `registry_group` | string | No | Registry group id |
| `properties` | object | No | `BlockBehaviour.Properties` modifiers |
| `item_properties` | object | No | Property modifiers and creative tab for the same-id block item |
| `state_properties` | object | No | Real `BlockState` property set |
| `params` | object | No | Open parameters passed verbatim to the factory |
| `state_machine` | JSON primitive | No | Forwarded to `params.state_machine` on the embedded BE when `block_entity` is present |
| `block_entity` | object | No | Embedded block entity definition |

STRICT mode requires `id` to be an explicit valid `namespace:path`, whereas the legacy `JsonTreeBuilder` entry point permits bare IDs. The two `EffectiveId` rules handle explicit `minecraft:` differently:

| Identity Rule | `panel` | `minecraft:panel` | `example:panel` |
| --- | --- | --- | --- |
| `EffectiveId.ofLegacy` | `<hostModId>:panel` | `<hostModId>:panel` | `example:panel` |
| `EffectiveId.of` | `<hostModId>:panel` (rejected later by STRICT `validate`) | `minecraft:panel` | `example:panel` |

As a result, STRICT genuinely claims an explicit `minecraft:` key; the host must explicitly permit such overrides and include vanilla entries in reserved checks if desired. The legacy entry point's rewriting is compatibility behavior, not the v2 pack protocol.

`DataDrivenBuildPipeline` selects the strict branch of the three-argument `resolveIdentity` via `BuildOptions`: LEGACY uses the historical table above, while STRICT preserves explicit namespaces. The legacy JAR entry point remains `JsonTreeBuilder.buildForMod`. Train packs and external packs should use `BuildOptions.STRICT`.

`type` must match a registered factory. When `state_properties` is declared, the factory must also implement `StatefulBlockFactory`.

Supported keys in `properties` and `item_properties` are listed below. During `apply`, the built-in parsers (`parseBlockProperties` / `parseItemProperties`) log a warning and skip unknown keys or malformed values. In STRICT mode, however, the built-in handlers invoke `JsonPropertyParser.validateStrict` and `JsonItemParser.validateStrict`, turning unknown keys and invalid built-in property values into blocking `CONTENT_INVALID` errors (custom keys can be registered for strict validation via `registerStrictCompiler` / `registerStrictParser`).

### Block Properties (`properties`)

| Key | Value |
| --- | --- |
| `no_occlusion` | boolean |
| `no_collision` / `no_collission` | boolean (both spellings accepted) |
| `requires_correct_tool` | boolean |
| `replaceable` | boolean |
| `dynamic_shape` | boolean |
| `random_ticks` | boolean |
| `no_loot_table` | boolean |
| `ignited_by_lava` | boolean |
| `liquid` | boolean |
| `force_solid_on` | boolean |
| `air` | boolean |
| `no_terrain_particles` | boolean |
| `light_emission` | integer (0–15 in STRICT) |
| `friction` | number |
| `speed_factor` | number |
| `jump_factor` | number |
| `destroy_time` | number |
| `explosion_resistance` | number |
| `strength` | number or `[destroyTime, explosionResistance]` |
| `map_color` | `DyeColor` name |
| `sound_type` | Supported `SoundType` name |

### Item Properties

Blocks use `item_properties`; standalone items use `properties`.

| Key | Value | Notes |
| --- | --- | --- |
| `stacks_to` | integer | Maximum stack size (1–99 in STRICT) |
| `rarity` | string | `common`, `uncommon`, `rare`, `epic` (case-insensitive) |
| `fire_resistant` | boolean | Fire resistance |
| `durability` | integer | Maximum durability (positive integer in STRICT) |
| `no_repair` | boolean | Disables repairing |
| `tab` | string | Creative tab `ResourceLocation`, consumed separately by the loader |

Properties are applied parent-first along the `registry_group` parent chain. Child definitions or more specific block/item properties override matching settings from parent groups.

## `items`

```json
{
  "items": [
    {
      "id": "example:manual",
      "type": "simple_item",
      "registry_group": "example:common_items",
      "properties": {
        "tab": "example:main",
        "stacks_to": 1
      },
      "params": {
        "factory_specific": "value"
      }
    }
  ]
}
```

| Field | Type | Required | Purpose |
| --- | --- | --- | --- |
| `id` | string | Yes | Item id |
| `type` | string | Yes | Item factory type |
| `registry_group` | string | No | Registry group id |
| `properties` | object | No | Item properties and creative tab |
| `params` | object | No | Passed to the item factory |

Standalone items claim only `minecraft:item`. Block items are derived from the block factory's `createsBlockItem` capability; see [Factories, Registry Groups, and Host Integration](07-factories-groups-integration.md).

## Embedded `block_entity`

Block entities have no independent JSON `id`. The loader uses the host block's effective id as the definition identity and derives a block entity type id, typically `<blockPath>_be`.

```json
{
  "blocks": [
    {
      "id": "example:machine",
      "type": "example_block",
      "state_machine": "example:machine_fsm",
      "block_entity": {
        "type": "example_be",
        "params": {
          "model": "example:models/block/machine"
        }
      }
    }
  ]
}
```

`BlockEntityTypeHandler.extractEmbedded` performs the following steps:

- Deep-copies `block_entity`;
- Injects the host block's `id` as `_parent_block`;
- If the host block carries a JSON primitive `state_machine`, writes it into `params.state_machine`;
- Hands the resulting object to `BlockEntityTypeHandler.parse`.

The public fields of the embedded object are `type` and `params`. `_parent_block` is injected internally and should not be written by content authors.

If `block_entities` is written as a top-level field, STRICT rejects the entire batch as an unknown top-level field; LEGACY records an error and continues processing valid `blocks` in the same file, including a diagnostic hint naming the parent field and embedded key.

## Block-State Properties

`state_properties` creates Java `Property` instances and `BlockState` combinations, taking effect only during the registration phase.

| `type` | Required / Optional Fields | Notes |
| --- | --- | --- |
| `boolean` | Optional `default` | Creates a new `BooleanProperty` |
| `integer` | `min`, `max`; optional `default` | Bounded non-negative integer range |
| `enum` | `values`; optional `default` | String value set backed by `StringStateProperty` |
| `vanilla` | `property`; optional `default` | References a vanilla `BlockStateProperties` field |

Property names and enum values must match `[a-z0-9_]+`. For `integer`, `min` and `max` are required; the range must be non-negative, have `max > min`, contain 2 to 64 values, and have `max < Integer.MAX_VALUE` (`2147483647`). `boolean` defaults to `false`. `vanilla.property` must be an uppercase public static field name on `BlockStateProperties`, and the vanilla property's own name must match the JSON key. A single block definition may declare at most 4096 state combinations. A vanilla property key does not automatically substitute for a newly created `boolean` property of the same name.

Modifying block-state properties changes the state encoding persisted in worlds. Adding, removing, or reordering properties after release can cause saved data to fall back or reset; the framework provides no automatic migration. See [Block-State Properties](../../reference/data-driven/block-state-properties.md) for full rules and the Java API.

## Reload Content

Top-level keys in reload content must be claimed by a registered `ReloadHandler`.

### `state_machines`

```json
{
  "state_machines": [
    {
      "id": "example:panel",
      "layers": [
        {
          "id": "base",
          "initial_state": "idle",
          "states": [
            {
              "id": "idle",
              "duration_ticks": 20
            }
          ]
        }
      ]
    }
  ]
}
```

`state_machines/` is currently the only reload type with a directory glob. `.json` files placed under `data/<ns>/state_machines/` can be discovered even without a manifest (for unmanaged legacy packs); files already listed in `on_reload` are not read a second time via the glob. The directory governs discovery only—files are still routed by their top-level keys.

### `animation_clips`

```json
{
  "animation_clips": [
    {
      "id": "example:wheel_spin",
      "duration_seconds": 2.5,
      "bones": []
    }
  ]
}
```

Animation clips are currently discovered only through `on_reload`. There is no `animation_clips/` directory glob, so files omitted from manifests are not read.

A single reload file may include multiple known top-level types, with each handler contributing entries independently; unknown top-level keys emit diagnostics while known types still contribute.

An empty registration content file `{}` is structurally valid and yields zero definitions. Whether an empty reload content file is valid depends on the decoder; the current FSM decoders report an error because their target top-level key is missing.

## File Ordering

The deterministic order of the v2 build pipeline is:

1. Shared platform sources sorted ascending by explicit `rank`; only legacy `SourceDescriptor`s receive zero-based order by input position;
2. Within each source, manifests are aggregated and repeated paths within the same domain are deduplicated;
3. `on_register` content paths are sorted lexicographically by full logical path;
4. Within a single content file, top-level handlers run in the order passed to the pipeline constructor;
5. Array elements for a single handler preserve file order;
6. Embedded types follow their parent array element order.

For non-registration definitions sharing an identity, later sources, later files, and later array elements win. The legacy `JsonTreeBuilder` scans in index-filename order and manifest-array order; when migrating to the multi-source pipeline, do not assume both entry points visit duplicated paths in identical file order. STRICT registration types retain all candidates, and final applicability is governed by `(registry, id)` claims rather than simple last-wins.

`on_register` paths are deduplicated at the manifest layer and then sorted by full logical path by the new pipeline; the order of top-level keys inside a JSON file does not drive sorting. Array elements retain file order, while final `apply` ordering remains constrained by phase, handler order, source order, and `parentDefinition`.

## Minimal Registration Pack

```text
data/example/kasuga_lib/data_driven/index.json
data/example/kasuga_content/groups.json
data/example/kasuga_content/items.json
assets/example/lang/en_us.json
pack.mcmeta
```

`index.json`:

```json
{
  "on_register": [
    "kasuga_content/groups.json",
    "kasuga_content/items.json"
  ]
}
```

`groups.json`:

```json
{
  "registry_groups": [
    {
      "id": "example:items",
      "item_properties": {
        "tab": "example:main"
      }
    }
  ]
}
```

`items.json`:

```json
{
  "items": [
    {
      "id": "example:token",
      "type": "simple_item",
      "registry_group": "example:items",
      "properties": {
        "stacks_to": 16
      }
    }
  ]
}
```

This handles Java registration only. You still need `assets/example/models/item/token.json`, textures, and language entries; to participate in resource/data pack overrides, also provide a valid `pack.mcmeta`.

The example assumes the host has registered a `simple_item` factory. Released packs should replace example types with their own stably registered factories rather than `contentTesting` types.
