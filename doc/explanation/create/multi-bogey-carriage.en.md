# How Multi-Bogey Carriages Work

KasugaLibNext extends Create's station assembly path so a single carriage can use more than two bogeys. Create's methods are not replaced; the assembly path makes three minimal interventions. The rest of the feature covers the interior bogeys' motion and orientation, curve tracking, and the derailment guard. For usage and the config switches see the [guide](../../guides/create/multi-bogey-carriage.md). The carriage body's vertical posture relative to the bogeys is a separate system; see [How Carriage Posture Works](carriage-posture.md).

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

## Interior bogeys: motion, orientation, and curve tracking

Client-side interior bogeys have no track points, so calling `CarriageBogey#updateAngles` directly would always hit the derailed fallback. `CarriageContraptionEntityMixin` runs after the second `updateAngles` call and computes:

**Wheel angle**: the same integration as Create, `new = (old - 360 · d / (2π · r)) % 360`, where `d` is the entity's real movement along the track this tick.

**Orientation (yaw/pitch)**: starting from the carriage's leading wheel point, walk along the graph towards the trailing bogey by `half leading wheel spacing + along ± half this bogey's wheel spacing`, sampling the bogey's two wheel contact points, then apply vanilla `updateAngles`' formula (`Δ = front contact − rear contact`, `yaw = −(deg(atan2(Δz, Δx)) + 90)`, `pitch = deg(atan2(Δy, horizontal length))`).

- the walk uses `TravellingPoint#travel`: curved edges map distance onto the Bezier parameter through `TrackEdge#incrementT → BezierConnection`, and at nodes `follow(trailing point)` picks the branch, so across an S-bend the sample is the real curve tangent at that spot;
- each stride is at most 5 blocks so the distance-to-Bezier-parameter mapping stays accurate;
- `along` is the relative position projected onto the assembly direction — the bogey's distance from the leading bogey;
- when the client has no track graph yet, the train is derailed, the walk is blocked, or the sampled direction degenerates, it falls back to interpolating the end bogeys' angles.

**Curve offset**: the body is the chord between the two real bogeys while the track is curved, so interior bogeys need a lateral shift towards the outside of the turn to keep their wheels on the rails. Using the end bogeys' yaw difference `θ`, the arc is:

```text
L = bogeySpacing
R = L / (2 · sin(|θ|/2))
u = (t − 0.5) · L
offset(t) = −sign(θ) · (sqrt(R² − u²) − R · cos(|θ|/2))
```

The height peaks at the chord midpoint and is 0 at the ends; positive is to the right of the leading-to-trailing chord, and the arc bulges to the outside of the turn. The result is clamped to `max_bogey_offset`, stored per bogey in a `LerpedFloat`, and interpolated with the partial tick when rendering.

**Rendering**: `CarriageContraptionEntityRendererMixin` passes the same `along` projection to `CarriageContraptionEntityRenderer#translateBogey` as the chord position, and applies the lateral offset as a world-space horizontal translation before `translateBogey` — the local +X axis of `translateBogey`'s carriage frame maps to world `(sin viewYRot, 0, cos viewYRot)`. The Flywheel visualization only knows `carriage.bogeys`, so the renderer mixin draws the extra bogeys directly.

## Derailment guard

`CarriageMixin` also runs a check at the tail of `Carriage.manageEntities` (server, once per carriage per tick):

- preconditions: multi-bogey on, `derailed_on_bogey_offset_overflow` on, the carriage has interior bogeys, the train is not derailed, and the graph plus the four track points of the end bogeys are valid;
- the end headings are derived from the real track points (the server does not maintain the client-side yaw `LerpedFloat`s), using the same formula as `updateAngles`;
- the arc height peaks at the chord midpoint, so the midpoint value `R · (1 − cos(|θ|/2))` is used; it is at least as large as what any interior bogey needs, covering every position;
- above `max_bogey_offset`, the train derails through Create's own high-stress procedure: `speed = 0`, cancel navigation, `runtime.tick`, `derailed = true`, `status.highStress()`. Already-derailed trains skip the check.

## Switches

The multi-bogey-related common configs in `CreateConfig`:

| Key | Effect |
| --- | --- |
| `enable_multi_bogey_carriage` | Master switch; when off, capture, assembly, persistence, sync, rendering, and angle computation all return to the vanilla path. |
| `max_bogey_offset` | Interior-bogey curve offset limit and the derailment threshold. |
| `derailed_on_bogey_offset_overflow` | Whether the required curve offset exceeding `max_bogey_offset` derails the train. |

With `enable_multi_bogey_carriage` off:

- capture no longer clamps the counter → vanilla `too_many_bogeys`;
- station collapse returns the original `secondBogeyPos`;
- NBT is neither written nor read;
- the client drops incoming payloads and skips rendering and angle updates.

Defaults are evaluated lazily via `ModConfigSpec.Builder#define(path, Supplier<Boolean>)`. Other mods can override them before configs are loaded with `KasugaLibCreateApi.setMultiBogeyCarriageDefault` and `setDerailedOnBogeyOffsetDefault`; an explicit value in the config file always wins.
