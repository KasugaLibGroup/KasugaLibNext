# Data-driven block-state properties

A `blocks` entry can declare real Minecraft `BlockState` properties in `state_properties`. This is separate from `properties`, which controls hardness, friction, and other block behaviour. List the content file in the index's `on_register` array.

```json
{
  "blocks": [{
    "id": "mymod:control_panel",
    "type": "data_driven_block",
    "state_properties": {
      "open": { "type": "vanilla", "property": "OPEN", "default": false },
      "active": { "type": "boolean", "default": false },
      "level": { "type": "integer", "min": 0, "max": 3, "default": 1 },
      "mode": { "type": "enum", "values": ["idle", "working", "paused"], "default": "idle" },
      "facing": { "type": "vanilla", "property": "HORIZONTAL_FACING", "default": "north" }
    }
  }]
}
```

`data_driven_block` is a built-in plain block factory with a default block item.

| `type` | Fields | Property | Default when omitted |
|---|---|---|---|
| `boolean` | `default`: JSON boolean | New `BooleanProperty` | `false` |
| `integer` | `min`, `max`, optional `default`: integers | New `IntegerProperty` | `min` |
| `enum` | `values`: strings, optional `default`: one of them | `StringStateProperty`; values are strings, not a Java enum | First value |
| `vanilla` | `property`: uppercase `BlockStateProperties` field; optional `default` | The original vanilla `Property` instance | First possible value; specify a default explicitly for stability |

Names and string values must match `[a-z0-9_]+`; enum values must be unique. Integer ranges are non-negative, with `max > min` and `max < 2147483647`. Each property has 2–64 values, and JSON-declared properties may produce at most 4096 combinations. Native properties on custom block classes multiply the total further.

For `vanilla`, the JSON key must equal the property's actual name: `HORIZONTAL_FACING` uses `facing`. Referencing `OPEN` this way allows `state.getValue(BlockStateProperties.OPEN)`. A newly created `boolean` named `open` is a different instance; retrieve it from that block's `getStateDefinition().getProperty("open")`. Do not assume properties from different blocks are interchangeable.

Invalid JSON declarations are reported in registration diagnostics and that block is skipped. Existing factories without `state_properties` retain their old behaviour; a block declaring them requires an opted-in factory. The plain block does not implement door, redstone, or other interaction logic. Change states on the server through Minecraft's world APIs, and provide blockstate models, item models, loot and localization separately.

Definitions are fixed during registration and cannot be changed by `/reload`. Client and server need identical definitions. Block IDs, property names and values are part of world saves; changing them can discard or reset existing values. No automatic migration is provided, so back up worlds before upgrades or downgrades.

## Opt a custom factory in

Minecraft builds the state definition inside the `Block` superclass constructor. Implement `StatefulBlockFactory` and scope construction with `StatePropertyContext.construct`:

```java
FactoryRegistry.register("mymod:custom_block", (StatefulBlockFactory) (id, params, states) ->
    blockWithItem(id, properties -> StatePropertyContext.construct(states,
        () -> new MyBlock(properties))));
```

`blockWithItem` is the helper from the [factory extension guide](../../guides/data-driven/guide-extension.en.md). The block must add properties during definition creation and apply the JSON defaults after `super` returns:

```java
public class MyBlock extends Block {
    public MyBlock(Properties properties) {
        super(properties);
        registerDefaultState(StatePropertyContext.applyDefaults(defaultBlockState()));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        // Add any native properties first.
        StatePropertyContext.addTo(builder);
    }
}
```

Preserve defaults set by the superclass before applying JSON defaults. A JSON property must not duplicate a property already added by the block or its superclass; Minecraft rejects duplicates during construction, and a conflict between a custom factory and its block class can abort registration. Both block hooks are required. See `StatefulTestBlock` and `DataDrivenTestFactories` in the data-driven `contentTesting` source set for a working example.

## Creating and accessing properties from Java

Java code can build a property set with the same validation as JSON. Pass it to `StatePropertyContext.construct` while the block is constructed during registration:

```java
StatePropertySet properties = StatePropertySet.builder()
    .booleanProperty("charging", false)
    .integerProperty("stage", 0, 3, 0)
    .stringProperty("mode", List.of("idle", "running"), "idle")
    .add(BlockStateProperties.OPEN, false)
    .build();

BlockReg.of("machine", blockProperties ->
    StatePropertyContext.construct(properties, () -> new DataDrivenBlock(blockProperties)));
```

`build()` returns an independent immutable snapshot. `add(Property<T>, T)` retains the supplied instance, also accepts Java enum properties, and checks that every serialized value can be read back. The normal registration flow still configures block items, namespaces and creative tabs as needed.

After registration, resolve a typed reference to the block's actual property:

```java
StateProperty<Boolean> charging = StateProperty.of(machineBlock, "charging", Boolean.class);
boolean current = charging.get(serverLevel, pos);
boolean changed = charging.set(serverLevel, pos, true);
BlockState other = charging.with(state, false); // no world mutation or event
Property<Boolean> minecraftProperty = charging.property();
```

`set` requires the server thread and defaults to Minecraft update flags `3`; an overload accepts explicit flags. It returns `false` for an unchanged value or rejected update. A `true` return follows `Level#setBlock` semantics: later neighbour reactions can still change the state. Wrong block, property name, type or value causes an exception.

## onChange

```java
StateProperty.Subscription subscription = charging.onChange(change -> {
    LOGGER.info("{}: {} -> {}", change.pos(), change.oldValue(), change.newValue());
});

// Close when the owner no longer needs the listener.
subscription.close();
```

Subscriptions are scoped to one block and property. The default is the logical server; `onChange(StateProperty.Side.CLIENT, callback)` and `BOTH` select other sides. The event provides `level()` as a `LevelReader`, an immutable `pos()`, the old and new states, and `oldValue()` / `newValue()`. Closing is idempotent. A listener's runtime exception is logged without preventing other listeners from running.

Only an actual value change on the same block triggers a callback. Placement, removal, unchanged values and changes to other properties do not. Java `set`, commands and ordinary `Level#setBlock` use the same hook. Uncommitted NeoForge block snapshots are not reported and rollback is suppressed; loading an entire chunk is not a sequence of block-change events.

The callback runs synchronously inside NeoForge's `onBlockStateChange`, where **world mutation is prohibited**. `StateProperty.set` rejects writes from inside the callback; do not bypass that restriction with `Level#setBlock`. Schedule world mutations for a later safe server tick. `with` is safe because it only returns an immutable state.

`DataDrivenBlock` forwards the hook. A custom block must forward it once as well; do not repeat the call if a superclass already does so:

```java
@Override
public void onBlockStateChange(LevelReader level, BlockPos pos,
                               BlockState oldState, BlockState newState) {
    super.onBlockStateChange(level, pos, oldState, newState);
    StateProperty.notifyChanges(level, pos, oldState, newState);
}
```

## Client synchronization smoke

An opt-in single-player client smoke places and updates a fixture near the player, so use an isolated client directory and test world:

```bash
./gradlew :modules:data-driven:runClient \
  -PkasugaStatePropertyClientDirectory=/absolute/path/to/isolated-client \
  -PkasugaStatePropertyQuickPlayWorld=test-world \
  -PkasugaStatePropertyClientSmoke=true
```

`test-world` must already exist under that directory's `saves/`. Success logs `STATE_PROPERTY_CLIENT_SMOKE_PASS server=1 client=1` and then exits; failure logs `STATE_PROPERTY_CLIENT_SMOKE_FAIL`. This covers one client with an integrated server, not a multiplayer dedicated server.
