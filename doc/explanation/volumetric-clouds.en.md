# How multi-layer volumetric cloud rendering works

For cloud field configuration, instance control, preview, and the verification entry see the
[API reference](../reference/volumetric-clouds.md).

## Weather and density layers

The sky is made of independent low, middle, and high density layers, and a weather window controls the cloud
clumps, gaps, and thickness variation.
The continuous density can connect and overlap across weather grid boundaries, which avoids a discrete
arrangement of one cloud per cell. The low layer keeps cumulonimbus envelopes, the middle layer spreads out, and
the high layer uses stretched, anisotropic shapes; a cloud tower can also span several height layers.
Scene height and coverage presets are art configuration, not meteorological measurements.

## Rendering and cost

A cloud field is drawn with one sky-covering ray march and one compositing draw, and no fullscreen pass is created
per cloud. It first intersects the ray with each layer's height interval, sorts and merges the overlapping
intervals, and skips the gaps between layers outright.
The sampling budget is allotted by the square root of the interval length, so a thin high cloud is not starved of
budget by the lower layers and does not disappear;
the densities of several cloud layers at one point are added. The total number of samples per ray is bounded by
the budget, and the march ends early when transmission is very low.
Weather windows and GPU textures use a cache of at most 8 entries, so travelling does not grow memory
without limit.

A shared shape texture keeps the cumulonimbus tower and anvil envelopes; periodic
Perlin-Worley/Worley noise generated in the background provides the body, billowing, and erosion of the
continuous cloud layers. Each layer uses its own weather, noise phase, and shape scale, and thin clouds use a
stretched, anisotropic shape. Quadratically spaced light intervals integrate to layer exits for soft self-shadowing.
Beer–Lambert extinction accumulates premultiplied radiance and transmittance front to back, with empty-envelope skipping.
Dual Henyey–Greenstein lobes control forward/backward scattering. Three approximate scattering orders progressively
halve extinction and anisotropy, with decaying normalized energy; this is not a full radiative-transfer solver.
A single cumulonimbus uses a cached lighting voxel atlas, usually updated every 0.25 seconds.

For the cloud field, FAST/BALANCED/HIGH use width and height resolutions of 1/3, 1/2, and 1 of the original
viewport; the total budget per ray is 112/160/256 samples, with 2/4/6 light samples.
The corresponding resolutions for per-cloud quality are 1/3, 1/2, and 1, with at most 48/80/128 fine steps.
A low-resolution result is upscaled with conservative depth and depth-aware interpolation. Both paths truncate at
the opaque scene depth and write no depth; sky clouds are not limited by the terrain far plane, and the host GL
state is restored. Infinite perspective far planes are also supported.

RGBA16F stores radiance/opacity; RG32F stores extinction-weighted mean cloud distance and the scene-ray endpoint.
Stable view IDs enable independent ping-pong histories. Reprojection compensates camera movement and wind, rejects
incompatible depth/endpoints, boundaries, time, lighting and settings, and clamps history to the current 3×3 neighborhood.
Teleports, world changes, resize and lighting discontinuities reset history. At most eight views are cached. Anonymous
views or historyWeight=0 keep the independent spatial path. Mean depth approximates separated layers, so rejection is
conservative; empty current rays never resurrect old silhouettes.

Composition snapshots host color and evaluates `Lcloud + Tcloud * Lscene` in linear light. The default explicitly
decodes/encodes display sRGB; linear HDR mode does not tone-map or clamp radiance. The host owns final exposure.

Long-range views add host-sky atmospheric perspective and distance fade. Ground cloud shadows and complete
weather/spectral-atmosphere simulation remain extensions; translucent
objects such as water are composited with the clouds by render stage. The extra gbuffer channels of an Iris
shader pack are not verified yet, so acceptance with the shader pack disabled does not imply compatibility.

## References

- [User-provided volumetric cloudscape implementation from Horizon Zero Dawn](https://zhuanlan.zhihu.com/p/638440336)
- [Guerrilla: Nubis, original material from 2017](https://www.guerrilla-games.com/read/nubis-authoring-real-time-volumetric-cloudscapes-with-the-decima-engine)
- [SIGGRAPH 2015: The Real-time Volumetric Cloudscapes of Horizon: Zero Dawn](https://advances.realtimerendering.com/s2015/)
- [WMO: cumulus outline, billowing top, and flat base](https://cloudatlas.wmo.int/en/definition-cumulus-cu.html)
- [WMO: the tower and anvil of cumulonimbus](https://cloudatlas.wmo.int/en/cumulonimbus-cb.html)
- [WMO: low, middle, and high cloud layers and their distribution across layers](https://cloudatlas.wmo.int/en/clouds-definitions.html)
- [NOAA: the ten basic cloud types and the shapes of multi-layer clouds](https://www.noaa.gov/jetstream/clouds/ten-basic-clouds)

This implementation uses continuous density layers, procedural weather regions, and a small number of
cumulonimbus shape envelopes; it does not copy the spherical-shell cloud layer or the frame-split reprojection of
the original system.
The hardware timings in the cited material do not represent the performance of this repository's implementation.
