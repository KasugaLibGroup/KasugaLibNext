# KasugaLib PMX → PBR Conversion

Interfaces, configuration, and call reference. For the mechanics see [pbr.md](../explanation/pbr.md).

This page covers KasugaLib's current PMX stylized PBR conversion pipeline, the implementation changes in this round, observability and troubleshooting, and how to customize it with the player configuration and the Java API.

## 1. How It Works

PMX source materials mainly provide a base texture, a diffuse color, a specular color, and a specular strength; they do not directly contain complete PBR textures. KasugaLib performs one pre-bake during the client resource load phase:

1. Read the numeric properties of the PMX material and generate automatic conversion parameters.
2. Apply the Java registration rules and the player JSON rules in order.
3. Generate a normal texture from the luminance gradient of the base texture.
4. Generate a specular texture that matches the current LabPBR/Iris path.
5. Pack the base color, normal, and specular into three corresponding atlases.
6. Write the result to the disk cache; later resource reloads reuse it first.

### Automatic parameters

- `smoothness`: mapped from the PMX `shininess`.
- `f0_code`: generated from the average of the PMX specular color; the automatic value is limited to 4–90, and no metal is guessed automatically.
- `subsurface`: default 0.
- `normal_strength`: default 0.08.
- `emission`: default 0.

### Output channels

Normal texture:

| Channel | Content |
| --- | --- |
| R | Encoded tangent-space X |
| G | Encoded tangent-space Y |
| B | AO, currently 255 |
| A | Height, currently 255, that is, parallax height is not enabled |

Specular texture:

| Channel | Content |
| --- | --- |
| R | Perceptual smoothness |
| G | Dielectric F0 or the LabPBR metal code |
| B | Porosity/SSS; non-zero SSS maps to 65–255 |
| A | Emission |

Normal sampling uses repeat/wrap at texture edges, matching the repeatable-UV behavior of PMX.

## 2. Changes in This Round

### Independent material variants

The atlas identity is decided jointly by "source texture + final PBR parameters":

- Same source texture, same parameters: reuse the same variant.
- Same source texture, different parameters: generate an independent variant and independent normal/specular sprites.
- If the same atlas identity is bound to a different source image or different parameters again: throw a conflict error immediately, instead of averaging silently.

### GPU, CPU, and caching

- Prefer generating both the normal and specular outputs in a single pass on the render thread through an offscreen framebuffer and MRT.
- When the GPU shader is unavailable, this session switches automatically to the deterministic CPU reference baker.
- The GLSL of the GPU baker lives in `assets/kasuga_lib/shaders/pbr/stylized_bake.vsh` and `stylized_bake.fsh`.
- The GPU baker explicitly saves, clears, and restores `GL_PIXEL_PACK_BUFFER`, `GL_PIXEL_UNPACK_BUFFER`, and the pack/unpack row-length/skip state.

### Load performance

- Fixed the linear `indexOf` lookup in PMX vertex deduplication, replacing the near-O(n²) path of large models with a hash index.
- Unused copies of base textures are no longer put into the PBR atlas repeatedly.
- The PBR disk cache is prefetched in parallel by the resource load threads; only misses go back to the render thread for GPU baking.
- The content hash of the same source image and profile is reused within a single load.
- `BufferedImage` is converted to `NativeImage` directly, instead of being encoded to an in-memory PNG and decoded again.
- The CPU fallback runs at most two bakes at a time, so it does not occupy all resource load threads.
- In-world resource reload uses generation-based publishing: new models are built in the background first, and are atomically swapped on the game thread only after the atlas and the sprite mappings are all ready; Kasuga model drawing is paused during the reload, and the old backend instances of the replaced models are cleaned up.

## 3. Observability

### Startup indicator

Minecraft's resource loading screen additionally shows KasugaLib's current stage and progress, including:

- scanning model resources;
- the current model resource;
- the current PMX name and the PMX count inside the ZIP;
- reading PBR cache entries;
- the number of GPU bakes for cache misses;
- PBR atlas packing.

### Batch log

After each PBR preparation finishes, one summary log line is emitted, for example:

```text
Prepared 91 stylized PBR variants from 74 source textures (59.4 MP, 17 shared-source variants): GPU 0, CPU 0, disk hits 91, failures 0, elapsed 880.3 ms
```

Field meanings:

| Field | Meaning |
| --- | --- |
| variants | Number of configured variants in the final atlas |
| source textures | Number of distinct source images |
| MP | Total processed pixels, counted per variant |
| shared-source variants | Number of variants added because the same source image uses different parameters |
| GPU | Number of items newly generated on the GPU in this batch |
| CPU | Number of items newly generated by the CPU fallback in this batch |
| disk hits | Number of disk cache hits in this batch |
| failures | Number of items that failed in this batch and used the default PBR map |
| elapsed | Total time of cache prefetch and baking of misses |

A first load usually shows a GPU count; a warm-cache load usually shows `GPU 0, CPU 0` and a high `disk hits`.

### Client commands

```text
/kasuga_pbr status
/kasuga_pbr reload_config
/kasuga_pbr rebake
```

`status` prints:

- `ready`, `failed`, `total`: status counts of cache keys within this session;
- `GPU`, `CPU`: number of items actually newly baked;
- `disk-cache`: number of disk cache hits;
- `memory-cache`: number of in-memory result hits within the same resource load;
- `requests`: total number of requests the coordinator received, including cache prefetch and atlas consumption;
- `key`: cumulative time of generating content cache keys;
- `read`: cumulative time of decoding disk PNGs;
- `GPU-time`, `CPU-time`: cumulative time of the corresponding baker;
- `write`: cumulative time of writing to the disk cache.

`reload_config` reloads the player configuration and triggers a resource reload. `rebake` deletes the whole Kasuga PBR disk cache, clears the session statistics, and triggers a full resource reload.

When either command runs inside a world, Kasuga models are temporarily hidden during the resource reload. New models are not published before the atlas is ready, so the render thread never observes a missing sprite; after the reload finishes, backend instances are recreated with the new configuration. On every platform, macOS included, cache misses uniformly prefer the GPU baker; only when the GPU shader or a GL call fails does it fall back to the CPU.

### Troubleshooting

1. No change after editing parameters: run `/kasuga_pbr reload_config`. Parameters take part in the cache key, so clearing the cache by hand is usually unnecessary.
2. Suspected corrupt old cache: run `/kasuga_pbr rebake`.
3. `GPU=0`: if `disk-cache` is high, this is a normal cache hit; if a GPU unavailable log appears, check for OpenGL/shader errors, as the system will use the CPU fallback.
4. `failures > 0`: search the log for `Failed to bake stylized PBR texture`; the log entry contains the cache key and the exception.
5. Atlas too large: compare `variants` with `source textures`. A large difference usually means many materials use different configurations.
6. Startup still slow: use the indicator to tell whether it is stuck on PMX, cache reading, or atlas, and compare the staged timings in the batch log and `/kasuga_pbr status`.

## 4. Player JSON Customization

The configuration file is located at:

```text
<game directory>/config/kasuga-pbr.json
```

The file is generated automatically when it does not exist. Base structure:

```json
{
  "enabled": true,
  "defaults": {},
  "rules": []
}
```

- `enabled: false`: disables the player configuration layer; PMX automatic conversion and other Java rules are still kept.
- `defaults`: conversion parameters that override all PMX materials.
- `rules`: applied top to bottom in array order; a later matching rule can override the earlier result.
- A rule only needs the fields it wants to match and override.

### Match fields

| Field | Type | Description |
| --- | --- | --- |
| `enabled` | boolean | Set to false to temporarily disable a single rule |
| `model` | string glob | Full model `ResourceLocation` |
| `texture` | string glob | Original texture `ResourceLocation`, not the hashed variant ID |
| `local_name` | string glob | PMX local material name |
| `english_name` | string glob | PMX English material name |
| `material_index` | integer | PMX material index, starting from 0 |
| `min_shininess` | number | Lower bound of PMX shininess, inclusive |
| `max_shininess` | number | Upper bound of PMX shininess, inclusive |

String matching is case-insensitive and supports `*` and `?`. Omitting a match field means that field is unconstrained.

Material names and texture names can serve as auxiliary filter conditions, but using them as the only basis for classifying materials is not recommended. For a specific model, prefer `model + material_index`; for cross-model rules, prefer PMX numeric ranges or the structured context in the Java API.

### Output fields

| Field | Range | Description |
| --- | --- | --- |
| `smoothness` | 0–1 | Perceptual smoothness |
| `f0_code` | 0–255 | LabPBR specular G channel code |
| `subsurface` | 0–1 | Subsurface scattering strength |
| `normal_strength` | 0–1 | Strength of the normal derived from the base color luminance |
| `emission` | 0–1 | Emission strength |

Out-of-range values are clamped automatically by `PbrConversionSettings`.

LabPBR predefined metal codes:

| Metal | `f0_code` |
| --- | ---: |
| Iron | 230 |
| Gold | 231 |
| Aluminum | 232 |
| Chrome | 233 |
| Copper | 234 |
| Lead | 235 |
| Platinum | 236 |
| Silver | 237 |
| Use albedo as the metal color | 255 |

0–229 is used for dielectric F0. Automatic conversion does not produce metal codes above 230.

### Configuration example

The example below applies stylization to the material indices of one specific model:

```json
{
  "enabled": true,
  "defaults": {
    "normal_strength": 0.06
  },
  "rules": [
    {
      "model": "kasuga_lib:models/pmx/test2.mmd.zip/unfading_flowers_miku_black.pmx",
      "material_index": 12,
      "smoothness": 0.72,
      "f0_code": 230,
      "normal_strength": 0.035
    },
    {
      "model": "kasuga_lib:models/pmx/test2.mmd.zip/unfading_flowers_miku_black.pmx",
      "material_index": 18,
      "smoothness": 0.38,
      "subsurface": 0.45,
      "normal_strength": 0.025
    },
    {
      "model": "kasuga_lib:models/pmx/*",
      "min_shininess": 96,
      "smoothness": 0.85
    }
  ]
}
```

After saving, run:

```text
/kasuga_pbr reload_config
```

## 5. Java API Customization

The public API lives in:

```text
lib.kasuga.rendering.models.mc.api.pbr
```

Main types:

- `PbrConversionSettings`: immutable final conversion parameters, clamped automatically at construction, with `with...` methods.
- `PbrMaterialContext`: read-only snapshot of the current model, texture, material index, PMX names, colors, shininess, flags, and texture dimensions.
- `PbrConversionRule`: conversion function.
- `PbrConversionRegistry`: rule registration, unregistration, and ordering.
- `PbrMetalPreset`: LabPBR metal code enum.

### Registering rules

```java
import lib.kasuga.rendering.models.mc.api.pbr.PbrConversionRegistry;
import lib.kasuga.rendering.models.mc.api.pbr.PbrMetalPreset;
import net.minecraft.resources.ResourceLocation;

PbrConversionRegistry.Registration registration = PbrConversionRegistry.register(
        ResourceLocation.fromNamespaceAndPath("example_mod", "character_materials"),
        500,
        (context, current) -> {
            if (!context.modelId().getNamespace().equals("example_mod")) {
                return current;
            }
            if (context.materialIndex() == 4) {
                return current
                        .withMetal(PbrMetalPreset.SILVER)
                        .withSmoothness(0.82f)
                        .withNormalStrength(0.04f);
            }
            if (context.shininess() < 8.0f && context.diffuseAlpha() < 1.0f) {
                return current
                        .withSmoothness(0.3f)
                        .withSubsurface(0.2f);
            }
            return current;
        }
);
```

When the rule is no longer needed, call:

```java
registration.close();
```

You can also register again with the same ID; the old rule is removed first.

### Priority and error behavior

- Rules run from the smallest `priority` to the largest.
- Rules with the same priority run in string order of the rule ID.
- The player JSON configuration is registered at priority `1000`.
- To provide a base value before the player configuration, use a priority smaller than 1000.
- To force an override of the player configuration, use a priority greater than 1000; this reduces the player's ability to customize and should be used with care.
- When a rule returns `null` or throws a runtime exception, KasugaLib logs a warning and keeps the result of the previous rule, then continues.

### `PbrMaterialContext` fields

Code rules can access:

- `modelId`, `textureId`, `materialIndex`;
- `localName`, `englishName`, `metadata`;
- diffuse RGBA;
- specular RGB;
- ambient RGB;
- `shininess`;
- `noCull`, `receivesShadow`;
- `textureWidth`, `textureHeight`.

Prefer rules that use the stable combination of model ID, material index, and PMX numeric values. If names must be used, constrain the model ID as well and allow for fallback behavior when the model version changes.

## 6. Compatibility and Caveats

- Baking currently happens during the client resource load/reload phase and does not run every frame.
- The PBR atlas is matched to the Iris shader path; without Iris or a shader pack the base-color model is still usable, but whether the PBR channels produce a visual effect depends on the actual rendering path.
- Using many different configurations on the same base texture increases the atlas area and VRAM usage. Reuse identical parameter combinations as much as possible.
- When changing the channel semantics or the algorithm of a baker, `PbrBakeProfile.VERSION` must be bumped so that old disk caches are invalidated.
- `rebake` deletes the whole Kasuga PBR cache, which makes the next load expensive; use it only for troubleshooting or an algorithm upgrade.
