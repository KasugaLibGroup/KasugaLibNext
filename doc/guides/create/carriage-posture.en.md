# Carriage Vertical Posture

KasugaLibNext's Create integration lets a carriage body move vertically relative to its bogeys: the body can bob up and down while the bogeys stay on the track. The offset is computed authoritatively on the server, sent to clients through the entity's synched data, and eased smoothly on the client. This is a system independent of the multi-bogey feature — a plain two-bogey carriage can use it just as well. For the mechanism see [How Carriage Posture Works](../../explanation/create/carriage-posture.md).

## Prerequisites

- Create is installed and the train is assembled through a Create station;
- the `kasuga_lib` common config `enable_carriage_y_offset` is `true`;
- some code subscribes to `ModifyCarriagePostureEvent` to supply the posture (with no listener the offset stays 0).

## Config entries

Edit `config/kasuga_lib-common.toml`:

```toml
enable_carriage_y_offset = true
max_carriage_y_offset = 0.5
```

| Key | Default | Effect |
| --- | --- | --- |
| `enable_carriage_y_offset` | `false` | Posture switch; when off, no event is posted and the offset is 0. |
| `max_carriage_y_offset` | `0.5` | Maximum offset magnitude (in blocks, symmetric up and down); the event result and restored save values are clamped to it. |

- This is a common config: the client and the server each read their own local file; keep them consistent.
- Defaults can be changed through the API (see "For other mods"); a value explicitly written in the config file always wins.
- Restart the game (or wait for the config to reload) after changing it.

## Supplying the posture: `ModifyCarriagePostureEvent`

With the switch on, the server posts `lib.kasuga.create.content.train.event.ModifyCarriagePostureEvent` once per carriage per tick. Subscribe and return that tick's vertical offset:

```java
@EventBusSubscriber(modid = "your_mod")
public final class CarriagePosture {
    @SubscribeEvent
    public static void onPosture(ModifyCarriagePostureEvent event) {
        if (event.getLevel().isClientSide())
            return;
        event.setYOffset(computeOffset(event.getCarriage(), event.getLevel()));
    }
}
```

- `getLevel()` / `getCarriage()` provide the context; `getYOffset()` starts at `0`, and `setYOffset(float)` is in blocks, positive upwards.
- After the event returns, the result is clamped to `±max_carriage_y_offset` and becomes that tick's authoritative value.
- The client does not post the event; it eases towards the synced value at 0.5 blocks per tick, and rendering interpolates with the partial tick, so motion stays smooth between syncs.

## What it looks like in game

- The body (block structure and block entities) moves up and down while the bogeys stay on the rails; on a multi-bogey carriage the interior bogeys are likewise unaffected.
- The authoritative value feeds the server hitbox and collisions: `Entity#position()` adds the offset, so the hitbox moves with the body; `position() − getPrevPositionVec()` still reflects only real movement.
- Wheel speed is unaffected: wheels turn according to the real track movement with the offset removed.
- The offset is written to the save with the carriage and restored on load; clients receive it by sync and ease into it.

## Reference implementation

`test.kasuga.create.train.CarriagePostureBobDemo` in contentTesting is a complete example: a sine of ±0.5 blocks with a 100-tick period.

## For other mods: changing the default

A mod that depends on the feature can call this from its `@Mod` constructor or `FMLConstructModEvent`:

```java
KasugaLibCreateApi.setCarriageYOffsetDefault(true);
```

The default is evaluated lazily when configs are loaded, after all mods have been constructed, so there is no ordering hazard. It only affects the *default*: a newly generated config file, or an existing file that lacks the key. An explicit value in the file always wins.

## Behaviour boundaries

- only the body moves; bogey positions, wheel speed, and track geometry are unchanged;
- the offset is carriage-level state, shared by every per-dimension entity of that carriage;
- it is independent of the multi-bogey system; either can be enabled alone or both together (see [Multi-Bogey Train Carriages](multi-bogey-carriage.md));
- keep the common config identical on client and server.

## Verification checklist

- With the switch on and a listener setting a value, the body bobs while the bogeys stay put;
- a value beyond `max_carriage_y_offset` is clamped to the limit;
- save and rejoin the world — the offset is restored, then recomputed by the event every tick;
- a player joining later sees the posture, easing in smoothly.
