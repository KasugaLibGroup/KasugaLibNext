# How PMX stylized PBR baking works

For configuration, rule registration, context, and diagnostic commands see the [API reference](../reference/pbr.md).

## 1. How it works

PMX source materials mainly provide a base texture, a diffuse color, a specular color, and a specular strength, and do not directly contain full PBR textures. KasugaLib performs one pre-bake during client resource loading:

1. Read the numeric properties of the PMX material and derive automatic conversion parameters.
2. Apply the Java registration rules and then the player JSON rules.
3. Generate a normal texture from the luminance gradient of the base texture.
4. Generate a specular texture that fits the current LabPBR/Iris path.
5. Splice base color, normal, and specular into their three corresponding atlases.
6. Write the result to the disk cache, so later resource reloads reuse it first.

### Automatic parameters

- `smoothness`: mapped from the PMX `shininess`.
- `f0_code`: derived from the average of the PMX specular color, with the automatic value clamped to 4–90; it never guesses metal automatically.
- `subsurface`: defaults to 0.
- `normal_strength`: defaults to 0.08.
- `emission`: defaults to 0.

### Output channels

Normal texture:

| Channel | Content |
| --- | --- |
| R | Encoded tangent-space X |
| G | Encoded tangent-space Y |
| B | AO, currently 255 |
| A | Height, currently 255, which means parallax height is disabled |

Specular texture:

| Channel | Content |
| --- | --- |
| R | Perceptual smoothness |
| G | Dielectric F0 or the LabPBR metal code |
| B | Porosity/SSS; a non-zero SSS maps to 65–255 |
| A | Emission |

Normal sampling uses repeat/wrap at texture edges, matching PMX's repeatable UV behavior.

## 2. Material variants, cache, and loading

### Separate material variants

The atlas identity is decided by the source texture plus the final PBR parameters together:

- Same source texture, same parameters: reuse one variant.
- Same source texture, different parameters: generate a separate variant with its own normal and specular sprites.
- If the same atlas identity is bound again to a different source image or parameters: throw a conflict error immediately instead of silently averaging.

### GPU, CPU, and cache

- The preferred path generates the normal and specular outputs together on the render thread through an offscreen framebuffer and MRT.
- When the GPU shader is unavailable, the session switches automatically to the deterministic CPU reference baker.
- The GLSL of the GPU baker lives in `assets/kasuga_lib/shaders/pbr/stylized_bake.vsh` and `stylized_bake.fsh`.
- The GPU baker explicitly saves, zeroes, and restores `GL_PIXEL_PACK_BUFFER`, `GL_PIXEL_UNPACK_BUFFER`, and the pack/unpack row-length and skip state.

### Loading performance

- The linear `indexOf` lookup in PMX vertex deduplication was fixed, turning the near-O(n²) path for large models into a hash index.
- Unused copies of the base texture are no longer added to the PBR atlas repeatedly.
- The PBR disk cache prefetches in parallel on resource loading threads, and only misses go back to the render thread for GPU baking.
- The content hash of the same source image and profile is reused within one load.
- `BufferedImage` is converted to `NativeImage` directly, without first encoding to an in-memory PNG and decoding it again.
- The CPU fallback runs at most two bakes at once, so it does not occupy every resource loading thread.
- An in-world resource reload uses generation-based publishing: the new model is built in the background first, and the atlas and sprite mappings are swapped atomically on the game thread once they are all ready; Kasuga model drawing is suspended during the reload, and the old backend instance of a replaced model is cleaned up.

Baking happens during loading and reloading, not every frame. The actual visual effect of the channels depends on the host and the shader pack; many parameter variants increase atlas area. A change to the algorithm or the channel semantics must invalidate the old cache.
