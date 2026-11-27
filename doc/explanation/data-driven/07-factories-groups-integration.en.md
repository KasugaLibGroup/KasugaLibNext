# Factories, Registry Groups, and Host Integration

`TypeHandler` interprets JSON structure, while `FactoryRegistry` turns `type` and `params` into `Reg` nodes. Registry groups provide property inheritance, `RegistryGroup` handles runtime dispatch, and the host projects the same fixed `ResourceSnapshot` into both registration `ContentSource` views and MC Approved pack layers.

## `FactoryRegistry`

`FactoryRegistry` lives in `core` and is shared by `data-driven` and host mods:

```java
FactoryRegistry.register("panel_block", factory, true);
FactoryRegistry.registerItem("manual_item", itemFactory);
FactoryRegistry.registerBlockEntity("panel_be", blockEntityFactory);
FactoryRegistry.registerGeneric("custom_type", genericFactory);
```

Source: [FactoryRegistry.java](../../../modules/core/src/main/java/lib/kasuga/registration/factory/FactoryRegistry.java).

| Category | Registration | Query |
| --- | --- | --- |
| Block | `register(type, factory[, createsBlockItem])` | `get`, `contains`, `createsBlockItem` |
| Item | `registerItem` | `getItemFactory`, `containsItem` |
| Block entity | `registerBlockEntity` | `getBlockEntityFactory`, `containsBlockEntity`, `getBlockEntityTypes` |
| Generic | `registerGeneric` | `getGeneric`, `containsGeneric` |

Each table is a process-wide `ConcurrentHashMap`. Registering the same `type` again silently replaces the old factory, and there is no namespace isolation. Prefix custom type names with your mod id, and register factories during `@Context` bean initialization before the first `prepare`. Calls such as `register` and `registerBlockEntity` are independent rather than transactional; failing partway through leaves earlier types registered.

Factory `params` is an open JSON object:

```java
Reg<?, Block> create(String id, @Nullable JsonObject params);
Reg<?, Item> create(String id, @Nullable JsonObject params);
Reg<?, ?> create(
        String id,
        Supplier<Block[]> validBlocks,
        @Nullable JsonObject params
);
```

Each factory defines its own parameter keys. STRICT mode checks whether a factory exists and implements a given Java capability interface, but cannot validate private `params` schemas inside individual factories.

`FactoryRegistry` has no production unregister or reload API. Replacing a factory dynamically immediately affects subsequent `validate` and `apply` calls, so all factory registration should finish before startup builds and must never depend on resource pack state during `/reload`.

## Block Factories and Default Block Items

Data-driven blocks commonly create both a block and a block item:

```java
@SuppressWarnings("unchecked")
private static <T extends Block> Reg<?, Block> blockWithItem(
        String id,
        Function<BlockBehaviour.Properties, T> blockSupplier
) {
    return (Reg<?, Block>) (Reg<?, ?>)
            BlockReg.of(id, blockSupplier).withDefaultBlockItem(id);
}

FactoryRegistry.register(
        "panel_block",
        (id, params) -> blockWithItem(id, PanelBlock::new),
        true
);
```

The third boolean argument records a factory capability flag rather than reflecting over the returned `Reg`:

- `true`: built-in `BlockTypeHandler.claims` also claims `minecraft:item` with the same id as the block;
- `false`: claims the block only;
- Re-registering the same type with `false` clears the previously recorded item capability.

If a factory calls `withDefaultBlockItem` yet registers with `false` (or omits the flag), STRICT mode misses that item claim. Conversely, passing `true` when no block item is created causes claims to diverge from the actual registration tree.

The built-in `BlockTypeHandler` derivation covers only the convention where a block item shares the block's id. If a factory creates a different item id via `withBlockItem`, or creates multiple items, write a dedicated handler to declare exact claims—the boolean flag alone cannot express those relationships.

### Derived Claims

`BlockTypeHandler.claims` emits:

```text
minecraft:block / <effective block id>
plus when createsBlockItem is true:
minecraft:item / <effective block id>
```

`ItemTypeHandler` emits only:

```text
minecraft:item / <effective item id>
```

`BlockEntityTypeHandler` derives:

```text
minecraft:block_entity_type / <block namespace>:<block path>_be
```

If a factory also registers creative tabs, menus, entities, or fluids as child entries, built-in handlers cannot see those children. Implement a dedicated `TypeHandler` or provide host-level reserved/derived claim tracking when full coverage is required.

`RegTypeHandler.claims` reads the final id from the active `DefinitionKey`, keeping block and item claims aligned with the STRICT or LEGACY `apply` identity rules: explicit `minecraft:` stays `minecraft:` in STRICT and rewrites to the host namespace in LEGACY.

## Factories with State Properties

Blocks declaring `state_properties` require a factory implementing `StatefulBlockFactory`:

```java
FactoryRegistry.register(
        "stateful_panel",
        (StatefulBlockFactory) (id, params, states) ->
                blockWithItem(id, properties ->
                        StatePropertyContext.construct(
                                states,
                                () -> new StatefulPanelBlock(properties)
                        )),
        true
);
```

The block class must:

1. Add JSON properties in `createBlockStateDefinition`;
2. Apply JSON default values via `StatePropertyContext.applyDefaults`;
3. Forward `StateProperty` hooks if change listeners or synchronization are needed.

See [Block-State Properties](../../reference/data-driven/block-state-properties.md) for the complete API.

## Built-in Factories

Built-in factories in the current source tree:

- `data_driven_block`: plain stateful block factory. `JsonTreeBuilder.ensureHandlersRegistered()` calls `DataDrivenBlockFactory.registerIfAbsent()` to register it; new pipelines that skip that initialization entry must register it explicitly.
- `fsm_block`: FSM block factory in `modelling`, registered with `createsBlockItem = true`.
- `fsm_be`: FSM block entity factory reading `state_machine`, `model`, and `model_name`.
- `contentTesting` fixtures such as `simple_block`, `simple_item`, and `test_be`, which are not shipped for downstream consumption.

`fsm_block` and `fsm_be` are registered by [`FsmBlockEntityFactories.registerBuiltin()`](../../../modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/dynamic/fsm/FsmBlockEntityFactories.java). Production code does not invoke that initializer automatically; tests and game tests call it explicitly. Any host using those two types in a real registration flow must invoke it before `prepare`. Third-party mods should register their own factories and never depend on `contentTesting` types as a stable API.

## `RegistryGroup` Property Inheritance

`Reg.applyProperties` executes parent-first:

```text
JSON root
  → parent registry_group
  → child registry_group
  → concrete block/item
```

Child groups therefore override parent groups, and concrete blocks or items override groups. The `parent` chain in `RegistryGroupHandler` governs property inheritance, independently of the runtime predicate on `RegistryGroup.dispatchWhen`.

Creative tab (`tab`) resolution follows a separate rule:

1. If the block or item declares its own `tab`, use that value;
2. Otherwise, check the tab stored directly on its referenced `registry_group`;
3. To inherit a tab, declare it explicitly on the group referenced by the content entry rather than expecting parent group tabs to cascade automatically.

## `RegistryGroup` Dispatch

```java
Registry root = new Registry("example");

RegistryGroup coreRuntime = new RegistryGroup().setParent(root);
RegistryGroup coreData = new RegistryGroup()
        .dispatchWhen(state::isCorePackEnabled)
        .setParent(root);

// runtime code registrations
new RuntimeOnlyReg().setParent(coreRuntime);

// JSON and optional code registrations owned by the pack
new PackOnlyReg().setParent(coreData);
```

`dispatchWhen` stores a `BooleanSupplier` evaluated on every `RegistryGroup.dispatchRegister`:

```java
if (predicate.getAsBoolean()) {
    super.dispatchRegister(context);
}
```

When the predicate returns `false`:

- The group's own `register` does not run;
- None of its child nodes execute;
- The subtree remains attached in the in-memory registration tree;
- The predicate may flip to `true` later, and subsequent dispatches traverse the subtree normally.

Calling `dispatchWhen` again replaces the previous predicate rather than composing them.

Dispatch predicates never delete nodes or alter parent/child links. Because `RegistryClaimSnapshot.collect` skips groups whose predicate returns `false` along with their subtrees, disabled code registrations do not claim reserved slots; once the predicate returns `true`, later snapshots include them again.

Runtime exceptions thrown by a predicate abort that dispatch pass, so predicates should remain simple and side-effect-free.

## `JsonRegistryGroup`

`DataDrivenBuildPipeline` creates:

```java
new JsonRegistryGroup(hostModId + ":json_root")
```

and installs the default namespace mapping on it:

```java
root.withProperty(
        ResourceLocation.class,
        location -> ResourceLocation.fromNamespaceAndPath(
                hostModId,
                location.getPath()
        )
);
```

In LEGACY mode, `RegTypeHandler` attaches a per-registration mapping only when the explicit namespace is not `minecraft`, so `minecraft:path` continues to resolve under the host namespace. In STRICT mode, any explicit namespace (including `minecraft`) installs its own mapping so the final id matches `EffectiveId.of`. This behavior is governed by `RegBuildContext.isStrict()`.

`JsonRegistryGroup` supports `setParent` and `dispatchWhen` just like any `RegistryGroup`; hosts adjusting it between `DataDrivenBuildPipeline.build` returning and attaching it to the mod root should keep their lifecycle and predicated-group policy explicit.

## Combining Built-in and Custom Handlers

The new pipeline takes an explicit handler list. To reuse the library's built-in types:

```java
JsonTreeBuilder.ensureHandlersRegistered();

List<TypeHandler<?>> handlers = new ArrayList<>();
handlers.addAll(TypeHandlerRegistry.all());
handlers.add(new PanelModeHandler());

DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
        ExampleMod.MOD_ID,
        handlers,
        finalizers
);
```

Because `TypeHandlerRegistry` is process-wide, multiple mods registering custom types under the same name would overwrite one another. Production extensions are safer passing custom handler instances directly into the host's pipeline list with source-prefixed type names.

## Coexistence of Code and Data Registrations

```java
Registry modRegistry = new Registry("example");
RegistryGroup runtime = new RegistryGroup().setParent(modRegistry);
RegistryGroup data = new RegistryGroup()
        .dispatchWhen(packState::dataEnabled)
        .setParent(modRegistry);

jsonRoot.setParent(data);
```

Recommended boundaries:

- Place Java classes, shared gameplay logic, and fixed infrastructure in `runtime`;
- Place pack-togglable default entries and pack-snapshot-dependent code registrations in `data`;
- Ensure registry groups, JSON handlers, and factories are registered before `build`;
- Include in reserved claims only code entries that could actually collide with JSON entries;
- Never rebuild Java registry groups during `/reload` asset refreshes.

## Resource Packs and `ResourceManager`

Fixed sources, registration views, `Reg`, and Minecraft resource packs form four distinct layers:

| Layer | Role |
| --- | --- |
| `ResourceSnapshot` | Fixes actual bytes, URIs, revisions, and root identity from startup sources |
| `ContentSource` | Reads manifests and JSON from a single concrete snapshot/root |
| `Reg` tree | Creates Java/Minecraft registry entries during registry events |
| Resource pack | Provides assets, data, models, textures, language files, and reload JSON during resource reloads |

A host typically coordinates all four:

1. Pin each pack/root as an independent snapshot with non-shared close lifetimes, then expose it to `DataDrivenBuildPipeline` via `ContentSourceFactory.snapshot(...)`;
2. Construct `MappedPackResources` / Approved pack layers from the same root set and ranks during pack discovery;
3. Ensure valid `pack.mcmeta`, `assets/`, and `data/` layouts;
4. Keep MC pack priority consistent with `ResourceSourceDescriptor.rank` override semantics;
5. Ship resource packs even for Java-only registered blocks/items so models, textures, and translations are not missing.

`ApprovedResourcePacks.register(id, sources, routing)` retains the provided `ResourceSource` list and calls `source.capture()` on each `AddPackFindersEvent` discovery. Production code should pass `FixedResourceSource` instances; passing live directory or archive sources causes discovery to re-read changing install directories. Each source/root yields a separate `MappedPackResources` layer opening files via snapshot leases rather than flattening multiple layers into one "last-winner directory".

`MappedPackResources` projects only `pack.mcmeta`, `pack.png`, `assets/`, and `data/`, synthesizing runtime metadata for the active `PackType` when `pack.mcmeta` is omitted. `ApprovedResourcePacks` orders pack generation by rank, while final per-file resolution still follows Minecraft's active `ResourceManager`.

`ResourceSystem` wraps client and server native `ResourceManager` instances in `ScopedResourceManager`. `ReloadOrchestrator` registers as a listener via `ScopedResourceManagerConsumer` and executes:

```java
reload(scopedResourceManager.getResourceManager());
```

Reload therefore reads from Minecraft's active pack stack:

- Higher-priority resource/data packs override lower-priority files at the same namespace and path;
- Disabled packs are absent from `ResourceManager`;
- `getResourceStack` resolves active pack-stack files (with `ManagedReloadAllowlist` filtering authorized layers for managed packs);
- `/reload` updates state machines and clip buckets, never block or item registries.

Both the registration view and MC pack projection should reference the same frozen snapshot/root. Because `MappedPackResources` bridges captured bytes directly, v2 needs no second authoritative materialized cache directory on disk; any optional persistent cache a host builds must act strictly as an acceleration copy for capture, never a second source of truth read separately by registration and resource discovery.

`rank` defines platform-layer order, but `ResourceSystem` does not decide which packs are enabled in business terms. The host must derive both the registration source list and the Approved pack list from the same frozen selection, and must not publish a user pack into the resource stack if registration rejects it.

## Startup Consistency

Consider persisting the following in reports or `SavedData`:

- Enabled pack IDs and versions;
- Concrete `(sourceId, rootId)` list and global ranks;
- Manifest logical paths;
- Prepared claims;
- Build status;
- Key diagnostic codes.

If a world restarts with a different enabled pack set, previously saved registry entries may be missing, causing old block or item data in the world to be lost or fall back. Hosts should compare historical snapshots against the new snapshot before server start and reject incompatible worlds. KasugaLib's data-driven layer supplies the build and claim data; the host defines world compatibility policy.
