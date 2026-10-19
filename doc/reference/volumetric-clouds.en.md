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
maximum display distance, X/Z wind speed, quality, and a layer list. The nine-parameter constructor generates three default layers from the cloud base and thickness.
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
noise phase, and shape scale, and thin clouds use a stretched anisotropic shape. The cloud field samples nearby
density along the sun direction to approximate self-shadowing, and uses density scattering and highlight compression to preserve the relief of the cloud body;
a single cumulonimbus uses a cached lighting voxel atlas, usually updated every 0.25 seconds.

For the cloud field, FAST/BALANCED/HIGH use width and height resolutions of 1/3, 1/2, and 1 of the original viewport, respectively;
the total budget per ray is 112/160/256 samples, with 1/2/3 local lighting samples; high thin clouds use simplified lighting.
For single-cloud quality, the corresponding resolutions are 1/3, 1/2, and 1, with at most 48/80/128 fine steps.
Low-resolution results are upscaled with conservative depth and depth-aware interpolation. Both paths clip at the opaque scene
depth, write no depth, do not limit the sky clouds by the terrain far plane, and restore the host GL state.

Distant views add atmospheric perspective and distance fade. There is currently no long-range cross-cloud shadowing, no ground cloud
shadows, no temporal reprojection, and no complete weather simulation; compositing with transparent objects such as water follows render
stages. The extra gbuffer channels of an Iris shader pack are not yet verified, and acceptance with the shader pack
disabled cannot be used to infer its compatibility.

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
  -PkasugaRenderWidth=1920 -PkasugaRenderHeight=1080 \
  -PkasugaRenderWarmup=8 -PkasugaRenderFrames=24 --offline --no-daemon
```

The benchmark uses `GL_TIME_ELAPSED` and covers the depth copy, the complete cloud field, and compositing, across ground, scattered cloud, overcast,
inside the cloud layer, rain, and a distant observer position. This is GPU time for a standalone scene and cannot be converted into a full Minecraft frame FPS.

On 2026-10-04, on an Apple M3 Max, 1920×1080, BALANCED, with 16 warm-up frames and 64 sampled frames:

| View / state | GPU P50 | GPU P95 |
| --- | ---: | ---: |
| Ground, default multi-layer | 4.72 ms | 5.99 ms |
| Scattered | 4.77 ms | 5.84 ms |
| Overcast | 1.65 ms | 1.97 ms |
| Inside the cloud layer | 7.21 ms | 8.56 ms |
| Rain | 2.01 ms | 3.18 ms |
| Distant observer position | 3.78 ms | 5.19 ms |

Overcast is faster because occlusion makes the ray marching end earlier. The values come from a standalone run of `renderBench`;
the report is at `build/reports/renderHarness/bench/report.json`, relative to the module directory.

The pure-JVM regression checks per-layer weather independence, configuration immutability, weather continuity, spatial overlap,
wind and interpolation, world-border precision, and cache boundaries. The GPU regression of the production shader checks the on-screen coverage
difference between scattered and overcast, the separate visibility of each layer, the budget error relative to double sampling, several
directions, distant/inside/above viewpoints, occlusion, relocation continuity, state restoration, and caching, and keeps the single-cloud regression.

Client acceptance in a temporary world uses `-PkasugaTestSkyCloud=true`, verifying a real camera near and 40000 units away, the
main frame, the shared clock, and restoration of the vanilla cloud toggle. The success log is `SKY_CLOUD_SMOKE_PASS`, and the
reports and screenshots are in `debug/sky-cloud/` under the game directory. `-PkasugaTestCloud=true` keeps the single-cloud acceptance.
These switches are only for purpose-built temporary test worlds.
