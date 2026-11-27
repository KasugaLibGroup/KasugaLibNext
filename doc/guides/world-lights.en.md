# World-Space Lights

`WorldLights` provides client-side dynamic point lights, rectangular area lights, and spherical volumetric lights. Each light belongs to a specific `ClientLevel` and uses the existing `EffectRenderPipeline` for visibility culling, ticking, removal, and cleanup on world switches.
No lights are created by default; when no lights are visible, the scene is neither copied nor shaded by the lighting pass.

```java
EffectHandle<WorldLight> lamp = WorldLights.spawn(level,
        LightSource.point(10, 65, 10)
                .temperature(2700)
                .intensity(30)
                .range(14)
                .build());

EffectHandle<WorldLight> panel = WorldLights.spawn(level,
        LightSource.area(10, 69, 10, 4, 2)
                .rotation(0, 90)
                .temperature(4500)
                .intensity(8)
                .range(16)
                .build());

EffectHandle<WorldLight> mist = WorldLights.spawn(level,
        LightSource.volumetric(13, 65, 10)
                .temperature(9000)
                .intensity(120)
                .range(4)
                .medium(0.12f, 0.2f)
                .build());
```

Import `lib.kasuga.rendering.lighting.LightSource`, `lib.kasuga.rendering.effect.EffectHandle`, and `WorldLights` / `WorldLight` from `lib.kasuga.rendering.effect.builtin.light`.
Spawn lights after client registration completes; `WorldLights.ready()` reports whether the resource shader is ready.
Rendering and updates are client-only; the server does not load these classes.

## Parameters and Updates

### Placing in a Scene and Moving

`WorldLightTarget` is a `WorldDisplayTarget` and can be added alongside models and UI into a nested `WorldDisplayScene`.
Mount the scene to the world with the existing `SceneModels`; node positions, area light orientations, and dimensions inherit the scene and mount transforms.

```java
WorldDisplayScene scene = new WorldDisplayScene();
WorldLightTarget light = scene.add(new WorldLightTarget(
        LightSource.point(0, 3, 0).temperature(2700).intensity(30).build()));

SceneModels models = SceneModels.open(level);
SceneModels.Entry mount = models.add(scene, new Vec3(10, 64, 10));

light.moveBy(2, 0, 0);                      // Move one light in parent scene coordinates
light.position(new Vec3(0, 4, 0));          // Set one light's local position
scene.transform(new Matrix4f().translation(3, 0, 0).rotateY(0.5f));
mount.position(new Vec3(20, 64, 10));       // Move the whole world mount
scene.visible(false);                       // Hide lights across the subtree
scene.visible(true);
scene.remove(light);                        // Close this node and remove its light
models.close();                             // Close the mount, scene, and all child lights
```

Call all of these APIs on the client render thread. `WorldDisplayScene` and `WorldLightTarget` live in `lib.kasuga.rendering.models.mc.scene.display`; `SceneModels` lives in `lib.kasuga.scene`.
`mount.follow(host)` can reuse a `SceneHost` to follow moving objects; each camera view only samples the current pose without advancing scene time.
When a scene closes, a node is removed, or a host disappears, the mount's lifecycle cleans up the lights. Hiding removes the rendered light instances and re-mounts them when made visible again.

Area lights preserve full rotation (including roll) and panel scale. Under non-uniform scale or shear, the falloff range and spherical medium use a bounding-sphere approximation while the emitting rectangle keeps the full affine transform. Standalone lights spawned via `WorldLights.spawn` also expose `moveTo(x, y, z)` / `moveBy(x, y, z)` on `WorldLight` (`handle.effect()`).

| Parameter | Semantics |
| --- | --- |
| World coordinates | Double-precision block coordinates. The current camera position is subtracted before GPU upload to preserve local precision far from origin |
| `temperature(kelvin)` | Optional approximate color temperature in 1000–40000 K; white when omitted |
| `color(new LightColor(r, g, b))` | Linear RGB tint directly; overrides or is overridden by `temperature` (last setter wins) |
| `intensity(value)` | Non-negative relative intensity; 0 disables emission. Not calibrated in lumens or candelas |
| `range(blocks)` | Positive finite radius; smoothly attenuates to 0 at the boundary. Also acts as the medium radius for volumetric lights |
| Area size | Rectangle width and height in blocks; intensity represents radiance per unit area, so enlarging the panel increases total output |
| `rotation(yaw, pitch)` | Degrees; zero faces `+Z` and pitch `+90` points downward. The rectangle's width axis rotates with yaw |
| `twoSided(true)` | Emits from both faces of the rectangle (single-sided by default); creates no visible fixture geometry |
| `medium(density, anisotropy)` | Non-negative scattering/extinction coefficient; anisotropy in `[-0.9, 0.9]`. Used only by volumetric lights |

`LightSource` is immutable; replace it atomically on update so ticking and rendering never observe partially mutated state:

```java
LightSource current = lamp.effect().source();
lamp.effect().source(current.toBuilder().position(12, 65, 10).intensity(40).build());
lamp.remove(); // or lamp.effect().close()
panel.remove();
mist.remove();
```

Color temperature uses [Tanner Helland's approximation fit](https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html) and converts the result to linear RGB; it is an artistic tint rather than a spectral simulation.

## Rendering Path and Boundaries

Reuses the existing shader registration, post-process graph, scene color/depth copy, fullscreen draw, and `TBOBuffer`.
Collection and shading run at `POST_PROCESS` priorities 700 and 701, before the built-in water pass.
All three light types are evaluated in a single fullscreen pass, uploading only the actually visible lights with no fixed count cap.
Each camera view uses its own matrices, world, position, and output dimensions; rendering never advances light time.

The shader files are at `assets/kasuga_lib/shaders/core/lighting/world_lights.{json,vsh,fsh}`, loaded from resources and compatible with the standard resource reload flow. No new GLSL is embedded in Java code.

This is screen-depth dynamic lighting: it illuminates vanilla blocks and Kasuga models that write to scene depth, reconstructing surface normals from neighboring depth samples.
Scene color approximates surface albedo; there are no separate PBR material channels, shadow maps, offscreen occlusion queries, or indirect light bounces.
Consequently, walls do not cast shadows from these lights onto surfaces behind them, and translucent materials without separate depth/normals are not shaded independently.
These lights do not change vanilla block lighting values and do not affect mob spawning or game logic. When a shader pack is enabled, its depth and color conventions must be verified separately.

Volumetric lights integrate across 48 steps through a spherical medium with smooth radial density, extinction, and the Henyey–Greenstein phase function; the integration range is clamped to the visible scene depth along each ray and supports the camera being inside the medium. It is not a billboard.
Multiple volumes add their scattered light and optical depths without solving multi-scattering between overlapping media.
Cost scales with visible light count: area lights take 16 samples per pixel, and each intersected volumetric light takes 48 steps.

## Demo and Verification

```sh
./gradlew :modules:modelling:modelUnitTest :modules:modelling:renderGlTest --offline
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaTestWorldLights=true \
  -PkasugaClientDirectory=run/world-lights-demo --offline
```

Each client run automatically creates a disposable `world-light-demo-<timestamp>` world, builds a test room near spawn, and captures 30 images across two camera views (`960×600` and `480×300`) covering baseline, warm/cool point lights, intensity, area light, volumetric light, combined lights, single-light movement, parent scene movement, hide, restore, close, and Blockbench exported lights before and after bone rotation and model close.
Images and `report.json` are written to `debug/world-lights/` inside the specified client directory, and the client exits automatically upon completion.
Use a dedicated run directory; this demo modifies its test world.

The standalone GL regression uses the production shaders and production buffer layout to verify color temperature, attenuation, intensity, panel orientation, two-sided emission, volumetric depth occlusion, insertion order, and removal restoration, saving low-resolution pixel verification images.
