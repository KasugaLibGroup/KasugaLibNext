# Multi-Bogey Train Carriages

KasugaLibNext's Create integration lets a single carriage be assembled with more than the vanilla two bogeys: the first bogey in the structure becomes the leading bogey, the last one becomes the trailing bogey, and every bogey in between is an *interior bogey* that moves, renders, persists, and syncs with the carriage, following the real track geometry in curves. For the mechanism see [How Multi-Bogey Carriages Work](../../explanation/create/multi-bogey-carriage.md). The carriage body's vertical posture relative to the bogeys is a separate system; see [Carriage Vertical Posture](carriage-posture.md).

## Prerequisites

- Create is installed and the train is assembled through a Create station;
- the carriage structure captures **at least 3** bogeys during assembly;
- those bogeys are **contiguous and ordered** in the station's bogey list (front to back along the assembly direction), with no foreign structure or missing bogey in between;
- the `kasuga_lib` common config `enable_multi_bogey_carriage` is `true`.

## Config entries

Edit `config/kasuga_lib-common.toml`:

```toml
enable_multi_bogey_carriage = true
max_bogey_offset = 1.0
derailed_on_bogey_offset_overflow = false
```

| Key | Default | Effect |
| --- | --- | --- |
| `enable_multi_bogey_carriage` | `false` | Master switch: capture, assembly, persistence, sync, rendering, and angle computation. |
| `max_bogey_offset` | `1.0` | Maximum lateral curve offset (in blocks) an interior bogey may receive; larger values are clamped. |
| `derailed_on_bogey_offset_overflow` | `false` | When the curve would require more than `max_bogey_offset`, the server derails the train. |

- These are common configs: the client and the server each read their own local file. Single-player shares one file; a dedicated server needs the server-side file changed, and the client should match (the client owns rendering and interior-bogey angles).
- Defaults can be changed through the API (see "For other mods"); a value explicitly written in the config file always wins.
- Restart the game (or wait for the config to reload) after changing them.

Disabling `enable_multi_bogey_carriage` returns everything to vanilla: assembling more than two bogeys at a station throws `train_assembly.too_many_bogeys` again, and existing multi-bogey data is no longer rendered or synced and is dropped on the next save. Loading an old save with the feature disabled also treats it as vanilla.

## Building and assembling

1. Build one complete carriage structure along the track and place at least 3 bogey blocks inside it. Styles may be mixed and bogeys may be upside down.
2. Build the rest of the carriage as usual; each interior bogey occupies its own part of the structure.
3. Trigger station assembly.
4. Result: exactly **one** carriage, with the first bogey leading and the last bogey trailing, `bogeySpacing` covering the full length, and the interior bogeys following the carriage.

If the interior bogeys are not contiguous with the ends, are out of order, or the contraption captured a bogey outside the station list, the merge is rejected and vanilla behaviour applies, usually surfacing as `train_assembly.not_connected_in_order`.

## Curve tracking and derailing

Interior bogeys are positioned and oriented from the real track geometry (Bezier turns and nodes included), so they receive a lateral offset from the carriage's straight chord that keeps their wheels on the rails:

- the offset grows with curvature, and the part beyond `max_bogey_offset` is clamped;
- with `derailed_on_bogey_offset_overflow` on, a curve that would need more than the limit derails the train server-side, using Create's high-stress derailment procedure;
- right after joining, before the client has received the track graph, orientation temporarily falls back to interpolating the end bogeys; it switches to real track sampling once the graph arrives.

## For other mods: changing the defaults

A mod that depends on the feature can call these from its `@Mod` constructor or `FMLConstructModEvent`:

```java
KasugaLibCreateApi.setMultiBogeyCarriageDefault(true);
KasugaLibCreateApi.setDerailedOnBogeyOffsetDefault(true);
```

The defaults are evaluated lazily when configs are loaded (`ModConfigSpec.Builder#define(path, Supplier<Boolean>)`), and config loading happens after all mods have been constructed, so there is no ordering hazard. The calls only affect the *defaults*: a newly generated config file, or an existing file that lacks the keys. An explicit value in the file always wins. The API does not force the feature on against a user's explicit choice.

## Behaviour boundaries

- Interior bogeys are not coupling points; they participate in movement, rendering, and lighting only;
- their orientation is sampled from the real track (Bezier turns and nodes), so it stays continuous across S-bends;
- portal clipping and lighting use the same carriage-local frame as vanilla bogeys;
- the client receives interior bogeys over a dedicated RPC;
- the style comes from each bogey's own `bogeyData`, so mixed styles and upside-down placement are supported.

## Verification checklist

- With the feature on, assembling a structure with 3+ bogeys yields one carriage instead of several;
- interior bogeys roll with the carriage and follow the real track on curves and slopes;
- save and rejoin the world — the interior bogeys are still there;
- a player joining later sees the interior bogeys;
- lowering `max_bogey_offset` with `derailed_on_bogey_offset_overflow` enabled derails the train on a sharp curve;
- setting `enable_multi_bogey_carriage` back to `false` and assembling the same structure takes the vanilla error path.
