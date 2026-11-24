# How Carriage Posture Works

KasugaLibNext keeps a server-authoritative vertical offset on `Carriage`, letting the body move up and down relative to the bogeys while bogey positions, wheel speed, and track geometry stay untouched. This is a system independent of the multi-bogey feature and works for a plain two-bogey carriage as well. For usage and the switches see the [guide](../../guides/create/carriage-posture.md); for the multi-bogey side see [How Multi-Bogey Carriages Work](multi-bogey-carriage.md).

## State model

- The offset is a plain `float` on `Carriage`, written by the server and the single source of truth;
- it lives on `Carriage` rather than the entity because every per-dimension entity of a carriage shares the same state, and because it is saved with the train and decoupled from entity lifetimes;
- the value is supplied by `ModifyCarriagePostureEvent` every tick; while `enable_carriage_y_offset` is off it is forced to 0;
- the client keeps its own smoothed copy (`LerpedFloat`) that eases towards the synced server value.

## Per-tick update

`CarriageMixin` runs at the tail of `Carriage.manageEntities` (server side, once per carriage per tick):

1. switch off: force the authoritative value to 0 and do not post the event;
2. otherwise build `ModifyCarriagePostureEvent(level, carriage)` and post it on `NeoForge.EVENT_BUS`; listeners set the tick's offset through `setYOffset(float)`;
3. after the event returns, clamp to `±max_carriage_y_offset` and store it as the authoritative value.

`manageEntities` only runs in the server-side train tick, so the event is server-side by construction; the client never posts it.

## Persistence

`CarriageMixin` writes one float when `Carriage#write` returns:

```text
KasugaYOffset: <float>
```

- written only while `enable_carriage_y_offset` is on and the value is non-zero;
- `read` restores it while the switch is on, clamped to `max_carriage_y_offset`;
- only the server reads and writes this NBT; client carriages come from `Train.STREAM_CODEC` and receive the posture through the sync channel below.

## Client sync

The posture value rides on the entity's synched data instead of a dedicated channel:

- `CarriageContraptionEntity` registers a `SynchedEntityData` float accessor in `defineSynchedData`;
- the server writes the `Carriage`'s authoritative value into that accessor at the head of `tickContraption`; vanilla's entity data sync sends it to tracking clients whenever it changes;
- on the client, `onSyncedDataUpdated` hands the value to the client `Carriage`, which eases its own `LerpedFloat` towards it at 0.5 blocks per tick;
- carriages themselves tick only on the server, so the easing is advanced from the client entity's `tickContraption` head;
- rendering interpolates between the previous and current tick values with the partial tick, so motion stays smooth between syncs.

## Consumers

The authoritative value (the smoothed value on the client) flows through:

- **position and hitbox**: `Entity#position()` adds the current offset for entities implementing `CarriageContraptionEntityAccess`. `AbstractContraptionEntity#setPos` rebuilds the hitbox from `getAnchorVec()` (which is `position()`), so the server hitbox moves with the body;
- **previous position**: `getPrevPositionVec()` adds the same offset, so `position() − getPrevPositionVec()` still reflects only real movement; `setServerSidePrevPosition()` subtracts the current offset when capturing `serverPrevPos` and caches the raw position, keeping `toLocalVector(..., prevAnchor)` and similar consumers consistent;
- **wheel speed**: the wheel-angle integration uses the real track movement with the posture offset removed — `tickContraption` differences `position()` against `xo/yo/zo`, and since `position()` carries the offset while `xo/yo/zo` are raw fields, the offset is removed from the difference there;
- **body rendering**: `OrientedContraptionEntityMixin` translates the pose stack inside `applyLocalTransforms`' rotation chain by the interpolated offset, so the block structure and block entities move with the body;
- **vanilla bogeys**: rendered by Flywheel; their visual position comes from `lerp(partialTick, yOld, position())` and only contains `partialTick × current offset` of it, which `CarriageContraptionVisualMixin` removes so the bogeys stay on the rails;
- **interior bogeys**: drawn directly by the entity renderer, whose outer pose never carries the offset.

## Relationship to the multi-bogey system

- The two systems are independent: the multi-bogey system owns the existence, sync, and curve tracking of interior bogeys; the posture system owns the body's vertical offset relative to the bogeys;
- with both enabled, the body (and its structure) bobs while every bogey stays on the rails and wheel speed still follows real movement;
- each system has its own common config entries and does not depend on the other.

## Switches and defaults

| Key | Effect |
| --- | --- |
| `enable_carriage_y_offset` | Posture switch; when off, no event is posted, the offset is 0, and every consumer is a no-op. |
| `max_carriage_y_offset` | Offset limit; both event results and restored save values are clamped to it. |

Defaults are evaluated lazily via `ModConfigSpec.Builder#define(path, Supplier<Boolean>)`. Other mods can override them before configs are loaded with `KasugaLibCreateApi.setCarriageYOffsetDefault(boolean)`; an explicit value in the config file always wins.
