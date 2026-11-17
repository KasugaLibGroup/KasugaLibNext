# Multi-Bogey Train Carriages

KasugaLibNext's Create integration lets a single carriage be assembled with more than the vanilla two bogeys: the first bogey in the structure becomes the leading bogey, the last one becomes the trailing bogey, and every bogey in between is an *interior bogey* that moves, renders, persists, and syncs with the carriage. For the mechanism see [How Multi-Bogey Carriages Work](../../explanation/create/multi-bogey-carriage.md).

## Prerequisites

- Create is installed and the train is assembled through a Create station;
- the carriage structure captures **at least 3** bogeys during assembly;
- those bogeys are **contiguous and ordered** in the station's bogey list (front to back along the assembly direction), with no foreign structure or missing bogey in between;
- the `kasuga_lib` common config `enable_multi_bogey_carriage` is `true`.

## Enabling the feature

Edit `config/kasuga_lib-common.toml`:

```toml
enable_multi_bogey_carriage = true
```

- This is a common config: the client and the server each read their own local file. Single-player shares one file; a dedicated server needs the server-side file changed, and the client should match (the client owns rendering and angles).
- The current default is `false`. Mods that depend on the feature can change the default through the API (see "For other mods" below).
- A value explicitly written in the config file always wins.
- Restart the game (or wait for the config to reload) after changing it.

Disabling it returns everything to vanilla: assembling more than two bogeys at a station throws `train_assembly.too_many_bogeys` again, and existing multi-bogey data is no longer rendered or synced and is dropped on the next save. Loading an old save with the feature disabled also treats it as vanilla.

## Building and assembling

1. Build one complete carriage structure along the track and place at least 3 bogey blocks inside it. Styles may be mixed and bogeys may be upside down.
2. Build the rest of the carriage as usual; each interior bogey occupies its own part of the structure.
3. Trigger station assembly.
4. Result: exactly **one** carriage, with the first bogey leading and the last bogey trailing, `bogeySpacing` covering the full length, and the interior bogeys following the carriage.

If the interior bogeys are not contiguous with the ends, are out of order, or the contraption captured a bogey outside the station list, the merge is rejected and vanilla behaviour applies, usually surfacing as `train_assembly.not_connected_in_order`.

## For other mods: changing the default

A mod that depends on the feature can call this from its `@Mod` constructor or `FMLConstructModEvent`:

```java
KasugaLibCreateApi.setMultiBogeyCarriageDefault(true);
```

The default is evaluated lazily when configs are loaded (`ModConfigSpec.Builder#define(path, Supplier<Boolean>)`), and config loading happens after all mods have been constructed, so there is no ordering hazard. The call only affects the *default*: a newly generated config file, or an existing file that lacks the key. An explicit value in the file always wins. This API does not force the feature on against a user's explicit choice.

## Behaviour boundaries

- Interior bogeys are not coupling points; they participate in movement, rendering, and lighting only;
- their orientation is exact on straight, level track and a first-order approximation (interpolation between the end bogeys) on curves and slopes;
- portal clipping and lighting use the same carriage-local frame as vanilla bogeys;
- the client receives interior bogeys over a dedicated RPC and does not rely on Create's vanilla bogey packets;
- the style comes from each bogey's own `bogeyData`, so mixed styles and upside-down placement are supported.

## Verification checklist

- With the feature on, assembling a structure with 3+ bogeys yields one carriage instead of several;
- interior bogeys roll with the carriage and change orientation on curves;
- save and rejoin the world — the interior bogeys are still there;
- a player joining later sees the interior bogeys;
- setting the flag back to `false` and assembling the same structure takes the vanilla error path.
