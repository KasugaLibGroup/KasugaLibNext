# Data-driven getting started: from an empty directory to a moving block

> Type: Tutorial (learning-oriented, follow along once).
> Applies to: Minecraft 1.21.1 · NeoForge 21.1.203 · Parchment 2024.11.17 · Java 21. The paths and behavior on this page hold only within this set of versions.
> Code baseline: `KasugaLibNext` branch `model-loader` (2026-10-04, baseline commit `ec864a1`). Line numbers in this page refer to the current working tree (the uncommitted data-driven reimplementation).
> This page only gets you started. For the complete list of fields see [schema.md](../../reference/data-driven/schema.md); for Java class and method signatures see [the data-driven API reference](../../reference/data-driven/README.md); for specific tasks (adding a block, wiring a creative tab, inheritance) see [guide-content.md](guide-content.md); for writing a custom TypeHandler see [guide-extension.md](guide-extension.md).

## 1. What data-driven solves

Data-driven lets you declare in JSON which blocks, items, and groups to register, and the framework reads them into Minecraft's registries at startup, instead of you calling `register()` one by one in Java.

It does not do the Java half for you. JSON says "register a block with id `mymod:panel` of type `simple_block`", and `simple_block` must first be registered in Java as a factory; how the factory builds that block is still decided by code.

The division of labor therefore works out like this: someone with a Java background builds a factory once and registers a creative tab once; after that, team members who do not write Java add content with JSON only.

The scope of this round has to be drawn clearly first: data pack authors have no use for this registration pipeline. The registration phase reads files from the mod jar only, and data packs cannot reach it (the reason is in the next section).

## 2. Remember one thing first: the two phases

This is the most important concept on the page, and almost every rule that follows is derived from it.

| | registration phase (registration) | reload phase (reload) |
|---|---|---|
| When it runs | during mod startup and construction, once | when you run `/reload` or reload resources |
| Where objects go | MC registries (such as `BuiltInRegistries.BLOCK`) | the reload bucket (not into MC registries) |
| Where it reads from | the mod jar | the pack stack |
| Can it hot reload | No; restart the game after a change | Yes; `/reload` takes effect |
| Can a data pack override it | No | Yes |
| What the content looks like | `registry_groups` / `blocks` / `items` (embedded `block_entity`) | `state_machines` / `animation_clips` |
| Code entry point | `JsonTreeBuilder.buildForMod` | `ReloadOrchestrator.reload` |

A one-line mnemonic: **anything that goes into an MC registry belongs to the registration phase, and anything that does not belongs to the reload phase.**

Which phase a piece of content belongs to is decided by whether the index file lists it under `on_register` or `on_reload`, and has nothing to do with which directory it sits in.

## 3. The three kinds of files

All the input data-driven takes is three kinds of files.

The index file is the only entry point, and it answers only "which files to read"; it contains no objects of its own. Its location is fixed: `data/<namespace>/kasuga_lib/data_driven/<name>.json`.

Content files hold object definitions. Paths are relative to `data/<namespace>/`, and the top-level field name (`blocks`, `registry_groups`, ...) decides which handler receives them.

Reload-phase state machine and animation clip files are content files too, only listed under `on_reload`.

How a file is discovered differs between the two reload content types, and this is where you are most likely to get tripped up. The `state_machines/` directory has a glob, so files in `data/<namespace>/state_machines/*.json` are read even when no index lists them; the `animation_clips/` directory has no glob, so a clip file that no `on_reload` lists is never read.

There can be more than one index. Every `.json` under the same mod's `data_driven/` directory counts as an index, read in ascending filename order.

## 4. Hands on (1): register a block

### 4.1 Prerequisite: `type` needs a factory first

`type` in JSON is a **factory registration key**. Before copying the example below, confirm this key exists in your environment:

- The only block factory built into the library itself is `fsm_block` (modelling module);
- Keys such as `simple_block` and `simple_item` used in the examples here are registered by this repository's `contentTesting` source set, and are **test fixtures that are not published with the library**.

When `type` finds no factory, the entry gets a WARN and is skipped (it does not go into the error bucket). For how to write a factory for your own mod see [guide-extension.md](guide-extension.md).

### 4.2 Place three files

Assume your namespace is `mymod`:

```
src/main/resources/data/mymod/kasuga_lib/data_driven/content.json   ← 索引
src/main/resources/data/mymod/kasuga_lib_content/groups.json        ← 内容：分组
src/main/resources/data/mymod/kasuga_lib_content/blocks.json        ← 内容：方块
```

`kasuga_lib_content/` is only a conventional directory name inside the library and is not mandatory; content files at any relative path under `data/mymod/` are legal.

### 4.3 Write the index

`data/mymod/kasuga_lib/data_driven/content.json`:

```json
{
  "on_register": [
    "kasuga_lib_content/groups.json",
    "kasuga_lib_content/blocks.json"
  ]
}
```

Only `on_register`, because both files below are consumed in the registration phase. Entry paths are **relative to `data/mymod/`** and must include `.json` (write `kasuga_lib_content/blocks.json`, not `data/mymod/...`, and not `blocks`).

### 4.4 Write the group

`data/mymod/kasuga_lib_content/groups.json`:

```json
{
  "registry_groups": [
    {
      "id": "mymod:panels",
      "item_properties": {
        "tab": "mymod:main"
      }
    }
  ]
}
```

A group is a container for properties shared by a set of blocks. Which creative tab a block item goes into is attached here as well, through `item_properties.tab`; `tab` must be a creative tab id you have already registered. For the full table of `registry_groups` fields see section 2.2 `registry_groups` entries in [schema.md](../../reference/data-driven/schema.md).

### 4.5 Write the block

`data/mymod/kasuga_lib_content/blocks.json`:

```json
{
  "blocks": [
    {
      "id": "mymod:panel",
      "type": "simple_block",
      "registry_group": "mymod:panels",
      "properties": {
        "destroy_time": 2.0
      }
    }
  ]
}
```

`registry_group` points at the group from 4.4. Without it the block is still registered, it just does not inherit the group's properties and the item does not go into a creative tab. For the full table of `blocks` fields see section 2.3 `blocks` entries in [schema.md](../../reference/data-driven/schema.md).

The field names and shapes in the examples are copied from this repository's fixture `contentTesting/resources/data/kasuga_lib/kasuga_lib_content/`, with only the namespace changed; such shapes parse and load without errors, which `JsonTreeBuilderIndexPathTest.contentTestingIndexLoadsWithoutErrors` covers.

### 4.6 Path rules

Three rules. Violating any one produces one error and skips that entry (the remaining entries continue):

1. The index directory must be exactly `data/<namespace>/kasuga_lib/data_driven/`, **four segments**. `kasuga_lib` is a fixed spelling and `data_driven` uses an underscore; writing `kasuga-lib`, or dropping the `kasuga_lib` level, results in **silently loading 0 entries**. Source: `JsonTreeBuilder.indexDirectorySegments`, locked down by the test `JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout`.
2. Index entries are resolved relative to `data/<namespace>/`, must end with `.json`, must not start with `/`, and must not contain an empty segment or a `.`/`..` segment. Source: `JsonTreeBuilder.validateSourcePath`, test `JsonTreeBuilderSourcePathTest`.
3. An entry cannot cross namespaces. The implementation guarantees this: at load time the path is always joined under `data/<modId>/` (`JsonTreeBuilder.parseSource`).

## 5. Hands on (2): make the block move

The registration phase only makes the block exist. Making it move, that is wiring a state machine and an animation clip to it, means entering the reload phase. This section walks through the real fixture in this repository's modelling module, the only end-to-end working example in this document that also has test coverage.

The fixture is at `modules/modelling/src/contentTesting/resources/data/kasuga_lib/`, with namespace `kasuga_lib`.

### 5.1 Index (declaring both domains at once)

`data/kasuga_lib/kasuga_lib/data_driven/modelling.json`:

```json
{
  "on_register": [
    "kasuga_lib_content/fan_formula_data_driven.json",
    "kasuga_lib_content/fsm_blocks.json"
  ],
  "on_reload": [
    "animation_clips/fan_fsm_data_driven.json"
  ]
}
```

Note the state machine file is not here; it is discovered through the directory glob from 5.3. This is exactly an instance of the glob difference in section 3. `fsm_blocks.json` originally belonged to data-driven's contentTesting, and moved to the modelling side because the `fsm_block` / `fsm_be` factories live in modelling only.

### 5.2 Registration phase: a block plus an embedded block entity

`kasuga_lib_content/fan_formula_data_driven.json`:

```json
{
  "blocks": [
    {
      "id": "kasuga_lib:test_fan_formula_data_driven",
      "type": "fan_block",
      "block_entity": {
        "type": "fan_be",
        "params": {
          "model": "kasuga_lib:models/be/test_fan_be.bbmodel",
          "state_machine": "kasuga_lib:fan_machine_data_driven"
        }
      }
    }
  ]
}
```

The block binds a block entity through the embedded `block_entity`, and `params` hands the model path and the state machine id to the BE factory. `fan_block` / `fan_be` are factories registered by `FanDataDrivenFactories` on the Java side. An embedded type can only be written as a key of the parent object; written as a top-level field it produces an error with a hint.

This block has no `registry_group`, so the item does not automatically go into a creative tab; you can obtain it with `/give kasuga_lib:test_fan_formula_data_driven`.

### 5.3 Reload phase: the state machine (discovered by directory glob)

`state_machines/fan_machine_data_driven.json`:

```json
{
  "state_machines": [
    {
      "id": "kasuga_lib:fan_machine_data_driven",
      "state_vars": [
        { "name": "cycle", "type": "bool", "default": false, "ephemeral": true, "reference": "kasuga_lib:fan/cycle" },
        { "name": "kasuga_lib:fan/current_speed", "reference": "kasuga_lib:fan/current_speed" },
        { "name": "kasuga_lib:fan/angle", "reference": "kasuga_lib:fan/angle" }
      ],
      "layers": [
        {
          "id": "gear",
          "mode": "base",
          "initial_state": "off",
          "states": [
            { "id": "off", "clip": { "id": "kasuga_lib:fan_fsm_data_driven", "loop": true } },
            { "id": "g1", "clip": { "id": "kasuga_lib:fan_fsm_data_driven", "loop": true } },
            { "id": "g2", "clip": { "id": "kasuga_lib:fan_fsm_data_driven", "loop": true } },
            { "id": "g3", "clip": { "id": "kasuga_lib:fan_fsm_data_driven", "loop": true } }
          ],
          "transitions": [
            { "id": "off_to_g1", "from": "off", "to": "g1", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
            { "id": "g1_to_g2", "from": "g1", "to": "g2", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
            { "id": "g2_to_g3", "from": "g2", "to": "g3", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
            { "id": "g3_to_off", "from": "g3", "to": "off", "trigger_on": "cycle", "cross_fade_seconds": 0.25 }
          ]
        }
      ]
    }
  ]
}
```

The top level is the `state_machines` key, and its value is an array of definitions (extra top-level keys no longer cause the file to be rejected, see [fsm.md](../../reference/fsm.md) section 2.4). What goes inside a definition (`state_vars`, `layers`, ...) belongs to the state machine specification, see [fsm.md](../../reference/fsm.md) and section 4.2 `state_machines` wrapper in [schema.md](../../reference/data-driven/schema.md).

### 5.4 Reload phase: the animation clip (index only)

`animation_clips/fan_fsm_data_driven.json`:

```json
{
  "animation_clips": [
    {
      "id": "kasuga_lib:fan_fsm_data_driven",
      "duration_seconds": 12.0,
      "functions": [
        { "bone": "group", "channel": "rotate", "y": "query.angle" },
        { "bone": "fan", "channel": "rotate", "y": "11 * query.angle" },
        { "bone": "cover", "channel": "rotate", "z": "sin(rad(2 * query.angle)) * 30" }
      ]
    }
  ]
}
```

This file **must** appear in `on_reload` (5.1 already lists it), because `animation_clips/` has no glob. The `kasuga_lib:fan_fsm_data_driven` referenced by `states[].clip` in the state machine is the id from here. For the element format see section 4.3 `animation_clips` wrapper in [schema.md](../../reference/data-driven/schema.md).

### 5.5 Hot reload

Change a file from 5.3 or 5.4, run `/reload`, and the new content takes effect immediately. Changing the block from 5.2 (registration phase) instead requires restarting the game. That is what the table in section 2 looks like in practice.

## 6. How to tell it worked

### 6.1 Read the log

A successful registration phase prints one INFO line (once per mod), in this format:

```
Loaded N JSON entries across M types for mod '<mod id>'
```

The real output of this repository's contentTesting fixture (source: the `system-out` of `modules/data-driven/build/test-results/test/TEST-lib.kasuga.test.registration.data_driven.JsonTreeBuilderIndexPathTest.xml`):

```
Loaded 23 JSON entries across 4 types for mod 'kasuga_lib'
```

Each top-level type that gets applied has one additional DEBUG-level `Applying ...` line. A real instance from the downstream KuaYue runtime:

```
Loaded 174 JSON entries across 2 types for mod 'kuayue'
Applying 8 entries for type 'registry_groups'
Applying 166 entries for type 'blocks'
```

If `N` is 0, or the line is missing entirely, the index was not found or it declared no entries.

The reload phase prints one INFO per entry on every `/reload`. Real output (source: the `system-out` of `modules/modelling/build/test-results/test/TEST-lib.kasuga.rendering.models.mc.dynamic.fsm.FanDataDrivenReloadTest.xml`):

```
Loaded state machine definition 'kasuga_lib:fan_machine_data_driven' from 'data/kasuga_lib/state_machines/fan_machine_data_driven.json' (reload domain, mod 'kasuga_lib')
Loaded animation clip 'kasuga_lib:fan_fsm_data_driven' from 'data/kasuga_lib/animation_clips/fan_fsm_data_driven.json' (reload domain, mod 'kasuga_lib')
```

### 6.2 Query with a command

In game (permission level 2 required):

- `/kasuga_data errors`: one line per bucket that has errors. The mod dimension looks like `<mod>: N error(s)`, the reload dimension looks like `RELOAD_DATA[<namespace>]: N error(s)`; when everything is clean the output is `Kasuga data: no loading errors recorded`.
- `/kasuga_data errors <mod>`: the errors for that mod, entry by entry, in both dimensions. The registration phase looks like `[<mod>] <error>`, the reload phase looks like `[RELOAD_DATA[<namespace>]] <error>`.

This is faster than reading logs, and it covers the buckets on both the registration and reload sides. Source: `DataDiagnosticsCommands`, test `DataDiagnosticsCommandsTest`.

## 7. The three mistakes beginners make most often

### 7.1 You wrote `sources`

`sources` is the old field, and it is now an **illegal field**. Writing:

```json
{ "sources": ["kasuga_lib_content/blocks.json"] }
```

produces two errors at once: `contains unsupported field 'sources'`, plus `declares neither 'on_register' nor 'on_reload'`, so nothing gets loaded at all. The fix is to replace `sources` with `on_register` (or with `on_reload` for reload-domain content).

### 7.2 The index lists it, but the content file path is wrong

Entries are relative to `data/<namespace>/`, not to the index directory and not to the resource root. Two common mistakes: adding a `data/mymod/` prefix, or omitting the `.json` suffix. The first reports `Source file not found for mod '<mod>': data/<mod>/<path>`; the second reports an illegal path. Both skip only that entry, and the remaining entries proceed as usual.

### 7.3 You listed reload-domain content under `on_register`

Listing a state machine file (with `state_machines` at the top level) under `on_register`: the registration phase does not recognize this top-level field, reports `unsupported top-level field 'state_machines'`, and appends `list this file under 'on_reload' instead` to the end of the message. Other registration fields in the same file are still processed normally. The opposite direction (listing `blocks` under `on_reload`) has a symmetric hint pointing back to `on_register`.

## 8. What to read next

- [schema.md](../../reference/data-driven/schema.md): the complete specification of JSON fields, types, requiredness, defaults, and rule sources. Use it as a dictionary while writing content.
- [the data-driven API reference](../../reference/data-driven/README.md): signatures and behavior contracts for `JsonTreeBuilder`, `Diagnostics`, `ReloadOrchestrator`, `ReloadHandler`, and similar classes.
- [guide-content.md](guide-content.md): step-by-step procedures for specific tasks (adding a block, wiring a creative tab, property inheritance, ...).
- [guide-extension.md](guide-extension.md): extensions on the Java side. Register your own factories and TypeHandlers to bring a team's private content into data-driven.
- [fsm.md](../../reference/fsm.md): the format of state machine definitions and animation clip elements.
