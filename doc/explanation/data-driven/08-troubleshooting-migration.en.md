# Troubleshooting and Migration

Data-driven failures typically fall into four layers: whether files reached the source, whether manifests are valid, whether handlers and schemas match, and whether final claims conflict. Narrowing by layer is far faster than debugging "nothing loaded".

## Identify Which Registration Entry Point Ran

| Symptom | Diagnosis |
| --- | --- |
| Logs contain host-recorded `PreparedContent`, `BuildResult`, or diagnostic codes | Host provider + multi-source pipeline |
| Logs contain `Loaded N JSON entries ... for mod` | Legacy `JsonTreeBuilder` |
| Neither appears | No index, provider not installed, mod did not dispatch registration, or zero content |

The pipeline itself does not enforce a fixed startup log line; confirm whether the new pipeline is active by checking provider registration and structured `BuildDiagnostic` outputs rather than relying on a single log message.

For the legacy entry point, check for:

```text
No data-driven index directory for mod '<id>'
Loaded ... JSON entries ... for mod '<id>'
Failed to load JSON registrations for mod '<id>'
```

For the new pipeline, inspect what the host recorded from:

```java
PreparedContent.sources()
PreparedContent.registerFiles()
PreparedContent.reloadFiles()
PreparedContent.definitions()
PreparedContent.claims()
PreparedContent.diagnostics()
```

`PreparedContent` is the authoritative snapshot of startup state. When "the file clearly exists on disk but did not load", inspect the sources and logical paths recorded there instead of assuming resource pack paths match.

If partial definitions from a source already parsed before enumeration or reading throws, `prepare` retains the earlier results and appends `SOURCE_READ_FAILED`. STRICT rejects the whole batch; LEGACY may proceed to `apply`, so the presence of some definitions does not mean the entire source loaded cleanly.

## Common Symptoms

### Zero Definitions Loaded

Check in order:

1. `SourceDescriptor.namespaces` includes the namespace in `data/<ns>`;
2. `SnapshotContentSource.declaredIndexes` includes the actual manifest paths, or a standard source places manifests directly under `data/<ns>/kasuga_lib/data_driven/`;
3. Manifests declare `on_register` rather than the removed `sources` field;
4. Content paths are relative to `data/<ns>/`;
5. Content files actually exist inside the same source/root snapshot;
6. Neither the `ContentSource`, `FixedResourceSource`, nor underlying `ResourceSnapshot` was closed/retired early;
7. The provider was installed before the first registration dispatch.

The new pipeline permits sources with zero manifests, so "0 definitions" is not inherently an error; confirm assets/data-only packs via `registerFiles().isEmpty()` and host expectations.

For `main`/`parcel` train packs, also verify:

- `main` and every parcel have distinct `rootId` values;
- `rootId` contains no colons;
- Ranks across all packs and roots are globally unique;
- Each parcel declares only its own indexes, and `main` does not accidentally sweep in undeclared parcel indexes;
- Registration and Approved pack projection read from the same frozen roots.

### `INVALID_SOURCE`

`INVALID_SOURCE` covers more than a duplicate `sourceId`. In v2, it checks:

```text
Duplicate (sourceId, rootId)
Duplicate global rank
```

Having `main`, `parcel-m1`, and `parcel-m2` under the same `sourceId` is valid as long as `rootId` distinguishes them. Every root must also carry a unique rank rather than restarting from 0 per pack.

### `MANIFEST_INVALID`

Check item by item:

- Only `on_register` and `on_reload` are allowed at top level;
- Values must be JSON arrays of strings;
- At least one domain must be declared (empty arrays are legal);
- Every path must be relative, slash-separated, and end with `.json`;
- No path may appear in both domains.

Manifest diagnostics are always `ERROR`. LEGACY builds may proceed anyway, but a result carrying `MANIFEST_INVALID` must never be treated as healthy.

STRICT also rejects duplicate JSON keys, comments, trailing commas, extra top-level documents, malformed UTF-8, nesting deeper than 64 levels, and documents exceeding 100,000 nodes. Regenerate invalid files as standard JSON rather than relying on Gson's lenient parser.

### `SOURCE_FILE_MISSING`

Manifest paths are anchored at `data/<ns>/`:

```text
manifest: "content/blocks.json"
physical: data/example/content/blocks.json
```

Writing `"data/example/content/blocks.json"` causes the loader to look up `data/example/data/example/content/blocks.json`.

Also check path casing and allowed characters. Registration `ContentSource` allows broader JVM/filesystem filenames, whereas runtime reload must construct a `ResourceLocation` (`[a-z0-9/._-]`); `DataDrivenBuildPipeline`'s reload preflight does not check `ResourceLocation` character rules for you.

### `UNKNOWN_TYPE`

The registration phase and reload phase use separate handler sets:

| Content | Correct Manifest Domain | Correct Top-Level Key |
| --- | --- | --- |
| Blocks / items / groups | `on_register` | `blocks`, `items`, `registry_groups` |
| Block entity | `on_register` | Embedded `blocks[].block_entity` |
| State machine | `on_reload` or `state_machines/` glob | `state_machines` |
| Animation clip | `on_reload` | `animation_clips` |

Common mistakes:

- Writing `block_entities` as a top-level field;
- Running `prepare` before reload handlers are installed;
- Listing registration files under `on_reload`;
- Listing reload files under `on_register`.

### `UNKNOWN_FACTORY`

The `type` value is not registered in `FactoryRegistry`:

```java
FactoryRegistry.contains(type)
FactoryRegistry.containsItem(type)
FactoryRegistry.containsBlockEntity(type)
```

Ensure factory beans initialize before the first `prepare`. Never rely on `contentTesting` factories as release APIs in production.

`data_driven_block` is registered on demand only when `JsonTreeBuilder.ensureHandlersRegistered()` runs; `fsm_block` and `fsm_be` require the host to call `FsmBlockEntityFactories.registerBuiltin()`. Handler lists on the new pipeline and `FactoryRegistry` are initialized independently.

### `UNKNOWN_REGISTRY_GROUP`

Group references match verbatim by raw string:

```json
{"registry_group": "example:panels"}
```

must match:

```json
{"id": "example:panels"}
```

`panels` and `example:panels` do not match. If the group belongs to another owner, an explicit owner dependency is also required.

### `REFERENCE_NOT_DECLARED`

The referenced definition exists in the session, but the current owner cannot see it:

```java
Map.of("addon_owner", Set.of("core_owner"))
```

Dependencies are direct only. If A references C while A depends only on B and B depends on C, validation still fails; A must declare C directly.

### `MISSING_REFERENCE`

No definition with that `DefinitionKey` exists anywhere in the session. Check:

- Whether the handler type domain matches;
- Whether the identity carries a namespace or underwent `EffectiveId`;
- Whether the referenced definition failed during `parse` and never entered the directory;
- Whether a registry id was passed where a configuration identity was expected.

### `INVALID_IDENTITY`

Built-in blocks and items require an explicit namespace in STRICT mode:

```text
"id": "example:panel"
```

Do not write:

```text
"id": "panel"
```

The legacy `JsonTreeBuilder` rewrites bare IDs into the host namespace, whereas strict external packs require full IDs for preflight, reporting, and cross-source diagnostics. Note that the two identity paths treat `minecraft:` differently:

```text
JsonTreeBuilder: minecraft:panel -> <hostModId>:panel
STRICT:          minecraft:panel -> minecraft:panel
```

A STRICT pack writing `minecraft:` genuinely claims the vanilla namespace. For bare `panel`, `resolveIdentity` temporarily produces `<hostModId>:panel`, and `validate` then rejects it with `INVALID_IDENTITY`. `DataDrivenBuildPipeline` selects the identity rule via `BuildOptions`; new packs should use STRICT and trace anomalies along `resolveIdentity` -> `validate` -> `apply` -> `claims`.

Built-in block and item `claims` read the final ID from `DefinitionKey`. Explicit `minecraft:panel` keeps `minecraft:panel` across `DefinitionKey`, `claims`, and `apply` in STRICT, whereas LEGACY maps all three to the host namespace.

Embedded block entities have no `id` of their own. Their validation inspects `_parent_block` injected by `extractEmbedded`, so the host block's `id` must likewise be an explicit `namespace:path`.

### `CLAIM_CONFLICT`

Two definitions—or a definition and a host code registration—compete for the same final registry key:

```text
(minecraft:block, example:panel)
(minecraft:item, example:panel)
```

Check:

- Whether two sources wrote different JSON IDs that resolved to the same key after namespace mapping;
- Whether a block factory declared `createsBlockItem = true`;
- Whether a standalone item shares an id with an auto-generated block item;
- Whether the host's reserved snapshot includes all existing code registrations;
- Whether an external source should have been excluded via disabled packs.

LEGACY may record only a warning and continue into unsafe Minecraft registry behavior. Production multi-source setups should always use STRICT.

### `HANDLER_NOT_STRICT_READY`

A handler is missing:

```java
HandlerCapability.STRICT_PREFLIGHT
```

or `strictValidationImplemented()` returning `true`, or does not declare exactly one of:

```java
HandlerCapability.NON_REGISTRATION
HandlerCapability.REGISTRY_CLAIMS
```

It can also indicate duplicate `typeName()` values in the pipeline's handler list.

### `GROUP_CYCLE`

Two or more `registry_groups.parent` edges form a cycle. Break one parent link or extract an acyclic shared parent group.

### `VALIDATION_FAILED`

A handler's `validate` method threw an unexpected `RuntimeException`. Expected content problems should be reported through `ValidationContext.error`/`warning`; only programming or invariant errors belong under `VALIDATION_FAILED`. Check whether `validate` attempted to access an uninitialized global service.

### `APPLY_FAILED`

Even after `prepare` passes, a factory or `apply` can still fail. Common causes:

- A factory depends on an unregistered deferred holder;
- A group id is missing from apply metadata;
- Type casting failure;
- Factory parameters fail object construction invariants;
- Host services are unavailable during context creation.

Failures may occur after some definitions have already applied. Hosts should not mount a `FAILED` root unless partial side effects are explicitly proven safe.

If context creation fails, no definition has applied; if sorting, definition `apply`, or a finalizer fails, partial side effects may already exist. `BuildResult.Status` and `finalizerResults` describe execution progress only and cannot roll back process-wide caches.

### `FINALIZER_FAILED`

All selected definitions applied, and a finalizer failed afterward. Fix the finalizer or make it diagnosable and idempotent. `BuildResult.finalizerResults` contains only the values returned before the failure.

### Provider Appears Inactive

Check:

- `install` uses the exact `modRegistry.getModId()` string;
- `install` runs before the registration callback executes;
- No test or earlier instance left an active `Attachment` on the same `hostModId`;
- The provider's source build did not return `null`;
- The provider did not throw an exception;
- The returned root is mounted only when `result.successful()`.

If a provider returns `null`, `JsonTreeIntegration` does **not** fall back to the legacy JAR scan; the provider has already taken ownership of that host.

### Reload Content Does Not Update

Check `on_reload`:

- Whether the pack is actually enabled in the active pack stack during `/reload`;
- Whether the handler's `typeName()` is registered in `ReloadHandlerRegistry`;
- Whether the pack provides that exact logical path;
- Whether `Decoded.errors()` is non-empty;
- Whether a later-loaded file overrode the identity via last-wins;
- Whether a script-bucket entry is shadowing the resource entry.

`state_machines` files omitted from manifests can still be discovered via directory glob (for unmanaged packs), whereas `animation_clips` must be listed under `on_reload`.

### Java Content Registered, but Models or Translations Missing

`Reg` handles Java registries only. Provide:

```text
assets/<ns>/blockstates/<block>.json
assets/<ns>/models/block/<block>.json
assets/<ns>/models/item/<item>.json
assets/<ns>/textures/...
assets/<ns>/lang/en_us.json
```

and ensure the resource pack reaches the client's `ResourceManager`.

### Data Pack Override Order Diverges from Source Order

Platform `rank` governs data-driven `prepare`/`build` order and `ApprovedResourcePacks` layer generation, while Minecraft's `ResourceManager` still determines runtime resource selection. Derive all of the following from a single frozen selection:

- `main`/`parcel`/`shared` `ContentSource` ordering;
- `PackResources` registration and ranking;
- Recorded active packs;
- Client and server consistency.

If registration and resource projection independently re-scan the install directory, re-assign ranks, or use different disabled sets, you can end up with "Java registration succeeded but models are missing" or "assets from a rejected pack remain visible". Compare the `(sourceId, rootId, rank)` lists on both sides first.

### Existing World Missing Blocks or Items After Restart

Once the registered set changes, IDs saved inside existing worlds may no longer exist. KasugaLib provides prepared claims and build results, while world compatibility policy belongs to the host. Compare the historical pack set and claims before loading a world and refuse startup when entries are missing or replaced.

## Diagnostic Outlets

### Registration

- `DataDrivenBuildPipeline`: structured `BuildDiagnostic`s residing strictly on `PreparedContent` and `BuildResult`;
- Legacy entry point: `JsonTreeBuilder.getLoadingErrors(modId)` / `Diagnostics.errors(modId)`;
- In-game command: `/kasuga_data errors [mod]`.

### Reload

- Logs carrying handler, file, and namespace context;
- `Diagnostics.Domain.RELOAD_DATA`;
- `/kasuga_data errors [namespace]` displays both registration errors and reload entries for that namespace.

Build diagnostics from the new pipeline are not forwarded automatically into `Diagnostics`. Providers should emit them to host reports, logs, or custom outlets.

Runtime reload manifest parsing reuses the legacy manifest parser, so manifest schema errors land in the legacy per-mod compatibility error bucket, whereas file decode, unknown top-level keys, and register failures land in `RELOAD_DATA`. `RELOAD_DATA` clears on every cycle, while the per-mod compatibility bucket is not cleared by the reload cycle. Command permission level is 2.

Suggested triage order by code:

| Code | Check First |
| --- | --- |
| `INVALID_SOURCE` | Duplicate `(sourceId, rootId)` or duplicate global `rank` (also recorded as `ERROR` in LEGACY) |
| `UNKNOWN_DEPENDENCY` | Misspelled dependency owner, or missing source for that owner |
| `MANIFEST_INVALID` / `MANIFEST_EMPTY` | Top-level fields, array types, dual-domain listing, at least one domain declared |
| `INVALID_PATH` / `SOURCE_FILE_MISSING` | Relative to `data/<ns>/`, `.json` suffix, traversal segments, actual file presence |
| `SOURCE_READ_FAILED` | Missing file, mid-capture mutation or limit exceeded, closed ZIP/directory handle |
| `UNKNOWN_TYPE` | Registration vs. reload file in the wrong manifest domain, or handler not installed |
| `PARSE_FAILED` / `CONTENT_INVALID` | Element codec, required fields, array shape, strict property validation |
| `MISSING_IDENTITY` / `INVALID_IDENTITY` | `resolveIdentity` output and explicit namespace on built-in handlers |
| `HANDLER_NOT_STRICT_READY` | Duplicate type names, or missing `STRICT_PREFLIGHT`, `strictValidationImplemented()`, or unique role |
| `CLAIM_FAILED` / `CLAIM_CONFLICT` | Unreported handler claims, final `(registry, id)` collision, reserved snapshot |
| `APPLY_FAILED` / `FINALIZER_FAILED` | Factory initialization, context services, partial side effects already executed |

## Migration Steps

### 1. Standardize Indexes

Migrate from the legacy flat layout to:

```text
data/<ns>/kasuga_lib/data_driven/
```

Rename `sources` to `on_register` and move reload content to `on_reload`, keeping all paths relative to `data/<ns>/`.

Typical migration:

```text
Old: data/example/kasugalib/blocks.json
New: data/example/kasuga_lib/data_driven/main.json
     data/example/kasuga_content/blocks.json
```

```json
{
  "on_register": ["kasuga_content/blocks.json"],
  "on_reload": ["animation_clips/wheels.json"]
}
```

`state_machines/` can still rely on directory glob discovery (in unmanaged packs), while `animation_clips/` must be listed explicitly under `on_reload`. The legacy `kasugalib/` directory is no longer read.

### 2. Choose an Entry Point

| Requirement | Entry Point |
| --- | --- |
| Single mod JAR, no multi-source overrides, no strict claims | Legacy `JsonTreeBuilder` |
| Multiple built-in or external packs, dependencies, strict conflict checks, finalizers | Provider + `DataDrivenBuildPipeline` |

### 3. Design Sources and Owners

Assign stable `sourceId`, `ownerId`, `namespaces`, and globally unique `rank` per pack, and stable `rootId`s for each `main`, `parcel`, or `shared` root without passing colon-containing business parcel IDs as `rootId`. Keep "which ZIP a file came from" (`sourceId`) distinct from "which dependency unit owns it" (`ownerId`).

### 4. Declare Dependencies

Convert actual cross-owner references into the `dependencies` map. Dependencies are never inferred from content paths, namespaces, or source order.

### 5. Make Handlers STRICT-Ready

- Registration types: declare `STRICT_PREFLIGHT` + `REGISTRY_CLAIMS`;
- Configuration types: declare `STRICT_PREFLIGHT` + `NON_REGISTRATION`;
- Override `strictValidationImplemented()` to return `true`;
- Implement side-effect-free `validate`;
- Declare every final registry entry in `claims`;
- Implement deterministic `parentDefinition`.

Install handlers and factories explicitly before the first `prepare`. Call `JsonTreeBuilder.ensureHandlersRegistered()` to reuse built-in registration handlers and `data_driven_block`, call `FsmBlockEntityFactories.registerBuiltin()` if using FSM factories, and register custom handlers and factories directly in your pipeline and `FactoryRegistry`.

### 6. Collect Reserved Claims

Collect host code registrations before `build`:

```java
Set<RegistryClaim.RegistryKey> reserved =
        RegistryClaimSnapshot.collect(hostRoot);
```

or supply an equivalent complete set of reserved registry keys.

### 7. Wire the Provider

Install the provider before registration dispatch. Inside the provider:

- Open fixed sources;
- Run STRICT `prepare`;
- Record diagnostics;
- Run `build` only when no blocking errors exist;
- Mount only `APPLIED` roots;
- Persist source order, claims, and status.

### 8. Wire Resource Packs

Build Approved pack layers from the same fixed root set with matching source/root ranks. Publish resources only after strict preflight, rejected-pack closure, and `build` succeed—never add all packs to the resource stack before the provider finishes its checks.

### 9. Verify

Recommended verification layers:

- `JsonTreeBuilder.parseContentBody` / `parseIndexManifest`: pure schema rules;
- `DataDrivenBuildPipelineTest`-style fixtures: multi-source, strict mode, claims;
- JUnit suites for `data-driven` and `core`;
- FML / game tests: real registry events;
- Dedicated server: startup with no client dependencies;
- Client: models, textures, language files, and `/reload`;
- Existing world: registry-set changes and history guard.

Common commands:

```bash
./gradlew :modules:data-driven:test :modules:core:test --console=plain
./gradlew :modules:modelling:modelUnitTest --console=plain
```

## Current Documentation Boundaries and Implementation Gaps

The following behaviors are verified in source code, without framework-level automated enforcement:

1. `DataDrivenBuildPipeline`'s reload preflight does not run `globDirectories()` discovery, so state machine files omitted from manifests are discovered only by the runtime orchestrator.
2. `FactoryRegistry`, legacy `TypeHandlerRegistry`, and `ReloadHandlerRegistry` are process-wide global tables with last-registration-wins overwrite and no production unregister API.
3. `DataDrivenRegistrationProviders` allows one provider per host with no layered composition.
4. `DataDrivenBuildHooks` has no unregister API; repeated initialization can register duplicate finalizers.
5. Claim registry names are plain strings with no centralized registry-key validation.
6. `build` has no transactional rollback; `FAILED` may leave context or finalizer side effects behind.
7. `BuildDiagnostic.Stage.SELECTION` is declared on the enum, while the current pipeline does not emit diagnostics directly under that stage.
8. Legacy Directory/ZIP `ContentSource` adapters fix file lists or hold open handles rather than process-wide frozen bytes; use `FixedResourceSource` + `ResourceSnapshot` whenever registration, discovery, and reload must share frozen input.
9. The library does not register resource or data packs automatically; pack discovery, physical path support, and override ordering are host-implemented.
10. The built-in block handler derives only the same-id block item claim; other child registry entries created by custom factories require dedicated handlers or host claim tracking.
11. World save compatibility policies, pack history, and missing-pack rejection logic belong to the host.
12. `ValidationContext.find(key)` delegates to `findAllVisible(key)` and returns a definition only when exactly one visible candidate exists, while `canReference(key)` checks whether at least one visible candidate exists; handlers needing custom selection across multiple visible candidates should inspect `findAllVisible(key)` directly.
13. The new pipeline does not auto-install `TypeHandler`s or factories. Reusing built-ins requires calling `JsonTreeBuilder.ensureHandlersRegistered()`, and FSM factories require the host to call `FsmBlockEntityFactories.registerBuiltin()`.
14. Source preparation exceptions are isolated per source rather than transactional; paths, candidates, and definitions already parsed before the failure remain in that `prepare` session.
15. Pipeline reload preflight does not construct `ResourceLocation` instances, so runtime reload can still reject a preflighted `on_reload` path for uppercase letters or illegal characters.
16. Runtime reload manifest parsing reuses the legacy per-mod error bucket; `RELOAD_DATA` covers only diagnostics raised directly by `ReloadOrchestrator` and reload handlers.
17. Unused keys in the `dependencies` map that match no source owner do not emit a standalone diagnostic.
18. `SnapshotContentSource.declaredIndexes` filters only `indexes()`, neither pruning readable files nor validating index paths; the host must validate declared entry paths and parcel mappings itself.
19. `DataDrivenBuildPipeline`'s `BuildDiagnostic` has no `parcelId` field; hosts must enrich train-pack reports from their frozen `(sourceId, rootId)` mapping.
20. If a registration provider does not share a commit boundary with Approved pack discovery, rejected sources can enter the MC resource stack prematurely; publication must happen after final registration selection.
21. `DataDrivenBuildPipeline` and the built-in handlers' `DefinitionKey`, `claims`, and `apply` share either LEGACY or STRICT identity rules selected via `BuildOptions`.

These boundaries do not prevent single-source compatibility or controlled host integrations, but before cross-repository release it should be clear which host layer owns each item—especially items 13–16, which directly explain cases where configuration looks valid yet handlers/factories are missing at runtime or diagnostics land in a different outlet.
