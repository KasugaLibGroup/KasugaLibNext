# How Multi-Bogey Carriages Work

KasugaLibNext extends Create's station assembly path so a single carriage can use more than two bogeys. Create's methods are not replaced; the feature makes three minimal interventions. For usage and the config switch see the [guide](../../guides/create/multi-bogey-carriage.md).

## Vanilla constraints

Create models a carriage as "one leading bogey + one trailing bogey", which breaks a multi-bogey structure in three places:

1. `CarriageContraption#assemble` counts the captured bogeys and throws `train_assembly.too_many_bogeys` above two;
2. `CarriageContraption#capture` only writes `secondBogeyPos` when the counter equals 2, so with more bogeys the caller cannot tell where the structure ends;
3. `StationBlockEntity#assemble` pairs the carriage at index `i` with the station bogey at `i + 1`. For a multi-bogey group `i..j`, `j != i + 1` triggers `train_assembly.not_connected_in_order`; even if that check were bypassed, every interior bogey would be assembled again as its own carriage.

## Capture phase

`CarriageContraptionMixin` does two things:

- **Defuses the count guard instead of cancelling the method.** Before `assemble` reads `bogeys` for the `> 2` comparison, the real count is saved to `ksg$bogeys` and the field is set to `2`. The comparison therefore falls through and the method continues with the sideways-controls check, conductor/seat bookkeeping, and `return true`. Cancelling would discard those side effects, so the input is changed instead of returning early.
- **Widens `capture`'s "second bogey" check to `>= 2`.** `capture` compiles the test as `!= 2`; clamping only the compared read to `min(count, 2)` makes every bogey from the second onwards pass and overwrite `secondBogeyPos`. The read feeding `bogeys++` is untouched, so the counter stays exact.

Every `secondBogeyPos = pos` assignment records its position in the redundant list; the last one captured is the final trailing bogey. Capture order is BFS order, not necessarily track order, so three pieces of data are exposed:

- `isMoreThan2Bogeys()`: whether this assembly really exceeded two (always `false` while the config switch is off);
- `getRedundantBogeys()`: every position except the first and the last captured, in raw capture order;
- the last captured position, still held in `secondBogeyPos`.

`CarriageContraptionAccess` lives outside the mixin package because Mixin forbids direct references to classes in a package owned by its config; the mixin implements it and ordinary code casts the target instance.

## Station collapse

`StationBlockEntityMixin` aligns the captured data with the station's ordered bogey list and collapses it back into the leading/trailing pair vanilla expects:

1. Reconstruct the captured set: `redundantBogeys ∪ {secondBogeyPos}`.
2. For each station index `k > i`, compute the expected world position (`bogeyLocations`, `upsideDownBogeys`, the two offsets, and the assembly direction) and remove the matched position from the set.
3. Accept the group only when all of the following hold:
   - `trailing > i + 1` (at least one interior bogey);
   - the set is fully consumed (nothing outside the group was captured);
   - `matched == trailing - i` (no gaps in the range).
4. Before overwriting the pair slot, build the `CarriageBogey`s from the **original** interior slots. Slot `i + 1` may itself be an interior bogey; building after the copy would mislabel the trailing bogey as interior. Relative positions are `expectedPosition(k) - contraption.anchor`, the same frame as `Contraption#toLocalPos`.
5. Copy the trailing bogey `j`'s `bogeyLocations`, `bogeyTypes`, `upsideDownBogeys`, and two `TravellingPoint`s into slot `i + 1`, and make `getSecondBogeyPos()` return `j`'s expected position. Vanilla then recomputes `expected(i + 1)` from the copied fields, which equals the returned value, so the check passes; the constructed trailing bogey uses the trailing data and `bogeySpacing` becomes `location[j] - location[i]`, the full carriage length.
6. At the second `CarriageBogey` constructor call, set the loop local to `trailing - 1` so the two following increments land on `trailing` and `trailing + 1`; the next iteration starts after the group and interior indices are not assembled again.
7. When vanilla adds the new `Carriage` to its result list, a `WrapOperation` on `List.add` attaches the redundant bogeys; `instanceof Carriage` distinguishes it from the other lists sharing the erased signature.

Any failed validation returns the original `secondBogeyPos`, writes no state, and leaves assembly on the vanilla path.

## Persistence

`CarriageMixin` appends a list when `Carriage#write` returns:

```text
KasugaRedundantBogeys: [
  { BogeyData: { Type, Points, UpsideDown, BogeyData }, Position: [x, y, z] },
  ...
]
```

`BogeyData` stores the full `CarriageBogey#write` tag rather than the raw `bogeyData` field: rebuilding also needs the `AbstractBogeyBlock` type and two `TravellingPoint`s. `read` restores with the same `graph`/`dimensions` as `FirstBogey`/`SecondBogey`; a tuple with an unreadable `Position` is skipped as a whole so both lists stay index-aligned.

Only the server writes this NBT; client carriages come from `Train.STREAM_CODEC` and never read it.

## Client sync

`Train.STREAM_CODEC` only carries the two real bogeys and `bogeySpacing`, and `CarriageSyncData` only carries four wheel points, so interior bogeys need a dedicated channel. `CarriageExtraBogeySync` sends one `(trainId, carriageIndex, List<Entry>)` per carriage with extras; each Entry is `(carriage-local position, block registry name, upsideDown, bogeyData)`:

- the block type travels as a `ResourceLocation` rather than `AbstractBogeyBlock.STREAM_CODEC` because the RPC only has a `FriendlyByteBuf`; the receiver resolves it through `BuiltInRegistries.BLOCK`;
- assembly sends after Create broadcasts `AddTrainPacket`; login sends from the `TAIL` of `playerLogin` in `GlobalRailwayManagerMixin`. Play-phase payloads on one connection arrive in write order, so the client already knows the `Train`;
- the client handler **replaces** both lists, making repeated or late syncs idempotent; unknown block types are skipped together with their positions;
- client-side bogeys use the three-argument constructor, so their `TravellingPoint`s are empty.

## Angles and rendering

Client-side interior bogeys have no track points, so calling `CarriageBogey#updateAngles` directly would always hit the derailed fallback. `CarriageContraptionEntityMixin` runs after the second `updateAngles` call and computes:

- wheel angle: the same integration as Create, `new = (old - 360 * distanceTo / (2π * radius)) % 360`;
- yaw/pitch: interpolate between the leading and trailing bogeys with `t = along / bogeySpacing`. `along` is the relative position projected onto the assembly direction, exactly the bogey's distance from the leading bogey; exact on straight, level track and a first-order approximation on curves and slopes.

The renderer passes the same projection to `CarriageContraptionEntityRenderer#translateBogey` and reuses its lighting and `isHiddenInPortal` logic. The Flywheel visualization only knows `carriage.bogeys`, so the renderer mixin draws the extra bogeys directly.

## Switch

The `enable_multi_bogey_carriage` common config in `CreateConfig` is the master switch; every entry point returns to vanilla behaviour when it is off:

- capture no longer clamps the counter → vanilla `too_many_bogeys`;
- station collapse returns the original `secondBogeyPos`;
- NBT is neither written nor read;
- the client drops incoming payloads and skips rendering and angle updates.

The default is evaluated lazily via `ModConfigSpec.Builder#define(path, Supplier<Boolean>)`. Other mods can override it before configs are loaded with `KasugaLibCreateApi.setMultiBogeyCarriageDefault(boolean)`; an explicit value in the config file always wins.
