# Sky Volumetric Clouds and Cumulonimbus Instances

Interfaces, configuration, and call reference. For the mechanics see [volumetric-clouds.md](../explanation/volumetric-clouds.md).

Volumetric clouds are a separate optional feature, off by default. `CloudEffects.setEnabled(true)` automatically draws a sky cloud field spread
around the observer position, and hides the vanilla block clouds in the main view and in standalone camera views at the same time.
Turning it off restores the previous cloud settings; the toggle neither modifies nor saves the user's vanilla cloud options. Leaving the world turns it off automatically.
Dimensions without a vanilla cloud layer do not draw the sky cloud field.

## Enabling the sky cloud field

```java
import lib.kasuga.rendering.cloud.SkyCloudSettings;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;

CloudEffects.sky().settings(SkyCloudSettings.DEFAULT);
CloudEffects.setSkyEnabled(true);
CloudEffects.setEnabled(true);

// Coverage, seed, and quality are adjustable at runtime; a change of wind speed does not teleport existing cloud clusters.
CloudEffects.sky().settings(CloudEffects.sky().settings()
        .withQuality(SkyCloudSettings.Quality.FAST));
CloudEffects.setEnabled(false);
```

The cloud field stacks 1–4 independent continuous density layers. Low cumulus layers have rolling tops, dark cloud bases, and varying
development heights; mid-level cloud decks spread into sheets, and high thin clouds stretch into bands. Weather regions control cloud clusters, cloud
gaps, and thickness variation; cloud bodies can cross weather grid boundaries, connect, or overlap, and are no longer arranged one per cell.
A small number of cumulonimbus clouds that use independent shape envelopes are kept in the low layers.

The default three cloud layers have cloud base Y values of 900, 2700, and 5400, with thicknesses of 2100, 900, and 600.
These values are adjustable scene scales; cumulus and cumulonimbus can also extend to the heights of other
layers. The `SCATTERED`, `CLOUDY`, and `OVERCAST` presets use coverage controls of .38, .80, and .98 respectively:
scattered cloud keeps large areas of clear sky, cloudy weather connects the cloud clusters, and overcast forms a continuous sheet.

`SkyCloudSettings` parameters are the seed, weather cell size, raw cloud base, maximum cumulonimbus thickness, coverage control,
maximum display distance, X/Z wind speed, quality, a layer list, and rendering controls. The nine-parameter constructor generates three default layers from the cloud base and thickness.
The actual height of each layer is controlled by `SkyCloudLayer`, and the list can contain several layers of the same type; changing the quality,
coverage, or seed keeps the layer list. Wind speed uses world units per second.
`coverage=0` hides the clouds completely in clear weather, and rain increases the coverage; the sun direction, day/night, and weather colors come from the world.

Each layer separately controls the type, cloud base, thickness, horizontal shape scale, coverage offset, and optical density.
Coverage and density are set separately, so a single cloud still keeps a sense of solidity even under scattered cover. A density of zero hides that layer,
including the cumulonimbus embedded in it. The configuration can be replaced at runtime:

```java
import java.util.List;
import lib.kasuga.rendering.cloud.SkyCloudLayer;

CloudEffects.sky().settings(SkyCloudSettings.CLOUDY.withLayers(List.of(
        new SkyCloudLayer(SkyCloudLayer.Type.CUMULUS, 900, 2100, 5200, 0, 2),
        new SkyCloudLayer(SkyCloudLayer.Type.STRATIFORM, 2700, 900, 3400, -.08f, 1.5f),
        new SkyCloudLayer(SkyCloudLayer.Type.CIRRUS, 5400, 600, 8000, -.12f, .55f))));
```

Each observer position uses a 32×32 weather window. The window relocates with the observer position, and clouds in the overlapping part stay
unchanged; a standalone camera view can observe its own local window at a distant position. Near the world border, the camera position is first subtracted in double,
then converted to GPU coordinates. `Frame.weatherCellCount()` only counts the candidate occupancy of weather control
cells; a continuous cloud body has no per-cloud numbering, and that statistic cannot represent the on-screen coverage either. Each layer generates its weather independently.

`SkyCloudRendering` controls extinction, erosion, detail frequency, forward/backward scattering, approximate multiple
scattering, ambient light, sample counts, resolution, debug views, color space, and temporal accumulation. Extinction
uses inverse world units; zero sampling or resolution values select the quality preset. Settings can change at runtime:

```java
import lib.kasuga.rendering.cloud.SkyCloudRendering;
import lib.kasuga.rendering.cloud.SkyCloudTemporal;

var rendering = SkyCloudRendering.DEFAULT
        .withDensityDetail(.009f, .12f, 4)
        .withScattering(.55f, -.25f, .15f, .5f, 1)
        .withSampling(160, 4, 2, true)
        .withTemporal(SkyCloudTemporal.STABLE);
CloudEffects.sky().settings(CloudEffects.sky().settings()
        .withWind(8, 3).withRendering(rendering));
```

`SkyCloudTemporal.STABLE` defaults to weight .65, a maximum time gap of .2 seconds, camera displacement of 16 blocks,
and relative depth tolerance .05; `OFF` disables history. `withDebug(...)` selects `DENSITY`, `TRANSMITTANCE`,
`RADIANCE`, `MARCH_STEPS`, or `HISTORY_VALIDITY`; `NONE` restores the normal image. `withColorSpace(...)` selects
`DISPLAY_SRGB` or `LINEAR_HDR` and should match the host target's color space.

## Creating a single cumulonimbus

The single-cloud API is kept; it can be used together with the sky cloud field, or with the field turned off to observe a single cloud:

```java
import lib.kasuga.rendering.cloud.CloudPose;
import lib.kasuga.rendering.cloud.CloudSettings;
import lib.kasuga.rendering.effect.builtin.cloud.CumulonimbusCloud;

var cloud = new CumulonimbusCloud(CloudPose.at(100, 900, 1800).withYaw(20),
        CloudSettings.CUMULONIMBUS);
var handle = CloudEffects.spawn(cloud);
CloudEffects.setSkyEnabled(false);  // Observe only explicitly created instances
CloudEffects.setEnabled(true);
cloud.wind(.6, 0, .2);
cloud.moveBy(20, 0, 0);
cloud.rotateBy(30);                // Around the world Y axis, in degrees
cloud.scale(1.2f, 1, 1.2f);
cloud.settings(CloudSettings.CUMULONIMBUS.withCoverage(.75f));
handle.remove();
cloud.close();
CloudEffects.setEnabled(false);
```

A single cloud's position is the center of its base; the default size is 1600 wide, 1200 high, and 1200 deep.
`CloudSettings` controls density, extinction, erosion, coverage, the anvil, seed, and quality.
Direct transforms take effect immediately; wind and internal noise advance only with the client tick and stop when paused;
the main view and several camera views sample the same moment and do not advance animation by render count.

## Rendering and cost

The cloud field uses one ray-marching pass covering the sky and one compositing draw, and does not create a fullscreen pass per cloud.
It first intersects the ray with the height interval of each layer, sorts and merges overlapping intervals, and skips the gaps between layers directly.
The sample budget is allocated by the square root of the interval length, so high thin clouds are not starved of budget by low layers and disappear;
the densities of several layers at the same point are summed. The total number of samples per ray is limited by the budget, and the march ends early when the transmittance is very low.
The weather window and the GPU textures use a cache of at most 8 entries, so travelling does not make memory grow continuously.

A shared shape texture preserves the cloud tower and anvil envelopes of the cumulonimbus; periodically
generated Perlin-Worley/Worley noise provides the body, rolling, and erosion of the continuous layers. Each layer uses its own weather,
noise phase, and shape scale, and thin clouds use a stretched anisotropic shape. Quadratically spaced light intervals
integrate to layer exits for soft self-shadowing. Beer–Lambert extinction accumulates premultiplied radiance and
transmittance; dual Henyey–Greenstein lobes control forward/backward scattering. Three approximate scattering orders
progressively halve extinction and anisotropy with normalized energy.
a single cumulonimbus uses a cached lighting voxel atlas, usually updated every 0.25 seconds.

For the cloud field, FAST/BALANCED/HIGH use width and height resolutions of 1/3, 1/2, and 1 of the original viewport, respectively;
the total budget per ray is 112/160/256 samples, with 2/4/6 light samples.
For single-cloud quality, the corresponding resolutions are 1/3, 1/2, and 1, with at most 48/80/128 fine steps.
Low-resolution results are upscaled with conservative depth and depth-aware interpolation. Both paths clip at the opaque scene
depth, write no depth, do not limit the sky clouds by the terrain far plane, and restore the host GL state.

RGBA16F stores radiance/opacity and RG32F stores extinction-weighted mean cloud distance plus the scene-ray endpoint.
Stable view IDs enable independent ping-pong histories. Reprojection compensates camera movement and wind, rejects incompatible
depth/endpoints, boundaries, time, lighting and settings, and clamps history to the current 3×3 neighborhood. Teleports,
world changes, resize and lighting discontinuities reset history; at most eight views are cached. Anonymous views or
historyWeight=0 keep the independent spatial path, and empty current rays never resurrect old cloud silhouettes.
Resize replaces and releases old history targets; cache eviction, world changes, and renderer shutdown also release history textures.

Composition snapshots host color and evaluates `Lcloud + Tcloud * Lscene` in linear light. The default explicitly decodes and
encodes display sRGB; linear HDR mode does not tone-map or clamp radiance. The host owns final exposure.

Distant views add atmospheric perspective and distance fade. There is currently no long-range cross-cloud shadowing, no ground cloud
shadows, and no complete weather simulation; compositing with transparent objects such as water follows render stages. The extra
gbuffer channels of an Iris shader pack are not yet verified, and acceptance with the shader pack disabled cannot be used to infer
its compatibility.

## Examples and verification

Development client:

- `/ksglib debug cloud` enables the whole sky cloud field.
- `/ksglib debug cloud sparse` switches to a sparse, multi-layer scattered-cloud state.
- `/ksglib debug cloud dense` switches to a cloudy state with connected cloud clusters.
- `/ksglib debug cloud overcast` switches to a continuous sheet.
- `/ksglib debug cloud single` places a cumulonimbus in front and turns the sky cloud field off.
- `/ksglib debug cloud stop` turns volumetric clouds off and restores the vanilla clouds.

Standalone preview, without starting Minecraft:

```bash
./gradlew :modules:modelling:renderPreview -PkasugaRenderScene=sky \
  -PkasugaRenderWidth=1920 -PkasugaRenderHeight=1080 --offline --no-daemon
```

The arrow keys change the view direction, W/S fly, and Escape exits. `-PkasugaRenderFrames=60` ends automatically and
saves the preview image; `-PkasugaRenderScene=cloud` keeps the original orbit preview of a single cumulonimbus.
Images and reports are in `modules/modelling/build/reports/renderHarness/`.

```bash
./gradlew renderingReleaseCheck :modules:modelling:renderGlTest --offline --no-daemon
./gradlew :modules:modelling:renderBench -PkasugaRenderScene=sky \
  -PkasugaRenderWidth=1280 -PkasugaRenderHeight=720 \
  -PkasugaRenderWarmup=20 -PkasugaRenderFrames=60 --offline --no-daemon
```

The benchmark uses `GL_TIME_ELAPSED` and covers the depth copy, the complete cloud field, and compositing, across ground, scattered cloud, overcast,
inside the cloud layer, rain, and a distant observer position. This is GPU time for a standalone scene and cannot be converted into a full Minecraft frame FPS.

On 2026-11-09, on an Apple M3 Max using OpenGL 4.1 Metal, 1280×720, with 20 warm-up frames and 60 sampled frames:

| View / state | GPU P50 | GPU P95 |
| --- | ---: | ---: |
| FAST ground | 2.05 ms | 2.35 ms |
| BALANCED ground | 5.86 ms | 6.43 ms |
| BALANCED sparse | 3.67 ms | 4.22 ms |
| BALANCED inside | 6.80 ms | 9.01 ms |
| BALANCED temporal | 5.89 ms | 6.39 ms |
| HIGH ground | 25.91 ms | 29.38 ms |
| HIGH inside | 44.66 ms | 50.57 ms |

The HIGH setting is substantially more expensive in the dense and inside views, so BALANCED remains the practical default. The values come from a standalone run of `renderBench`;
the report is at `build/reports/renderHarness/bench/report.json`, relative to the module directory.

The pure-JVM regression checks per-layer weather independence, configuration immutability, weather continuity, spatial overlap,
wind and interpolation, world-border precision, and cache boundaries. The GPU regression of the production shader checks the on-screen coverage
difference between scattered and overcast, the separate visibility of each layer, the budget error relative to double sampling, several
directions, distant/inside/above viewpoints, occlusion, relocation continuity, state restoration, and caching, and keeps the single-cloud regression.

Client acceptance in a temporary world uses `-PkasugaTestSkyCloud=true`, verifying a real camera near and 40000 units away, the
main frame, the shared clock, and restoration of the vanilla cloud toggle. The success log is `SKY_CLOUD_SMOKE_PASS`, and the
reports and screenshots are in `debug/sky-cloud/` under the game directory. `-PkasugaTestCloud=true` keeps the single-cloud acceptance.
These switches are only for purpose-built temporary test worlds.
