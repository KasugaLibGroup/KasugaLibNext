# Model Material Alpha Rendering Reference

Interfaces, configuration, and call reference. For the mechanics see [render-alpha-passes.md](../explanation/render-alpha-passes.md).

Model materials are now split into three classes by the alpha contract of glTF/generic materials:

| Class | GPU state | Fragment behavior | Order |
| --- | --- | --- | --- |
| `OPAQUE` | No blending, depth write | Ignores the sampled alpha, outputs 1 | `AFTER_ENTITIES` priority 0 |
| `MASK` | No blending, depth write | Fragments below the material `alphaCutoff` are `discard`ed, the rest output 1 | `AFTER_ENTITIES` priority 1 |
| `BLEND` | OIT accumulation/revealage, no depth write | Drops only the near-invisible alpha `<= 1/255`, keeps the actual alpha | `AFTER_TRANSLUCENT_BLOCKS`, WBOIT needs no sorting |

`BackendInstance` creates a separate `FlatModelData` and vertex buffer for each non-empty pass,
so a model with several materials does not redraw incompatible geometry into the wrong RenderType.
OPAQUE/MASK can still go into the global batch, but the batch key includes the alpha pass; BLEND does not go into the
global batch, so that the two OIT geometry submissions and the ordinary batching state do not get mixed together.

glTF's `alphaMode` and `alphaCutoff` are passed to the backend by `GltfMaterialData`. Older loaders without a transparency
contract default to `OPAQUE`; the PMX loader infers OPAQUE/MASK/BLEND from the diffuse alpha and the texture alpha.

The non-Iris path of BLEND is a weighted-blended OIT (WBOIT) over two geometry sub-passes: the first pass adds
`color * alpha * weight` and `alpha * weight` into an `RGBA16F` accumulation, the second multiplies
`1 - alpha` into an `R16F` revealage. Both passes use the scene depth copied from the main framebuffer, testing depth
but not writing it; finally the fullscreen resolve computes
`color = accumulation.rgb / accumulation.a` and `alpha = 1 - revealage`, then composites back to the main target with
straight-alpha source-over. Therefore KasugaLib's BLEND geometry on this path does
not depend on the far-to-near submit order.

With an Iris shader pack enabled, the same accumulation/revealage/resolve targets are used, but Iris's
`entities_translucent` shader is kept to produce the straight-alpha color after material, PBR, lighting, and fog. Since
Kasuga's depth weight cannot be injected into its fragment shader without modifying the shader pack, the Iris path
uses a constant-weight WBOIT with weight 1: RGB accumulates with `SRC_ALPHA, ONE`, A accumulates with `ONE, ONE`,
and revealage stays `ZERO, ONE_MINUS_SRC_ALPHA`. The translucent entity shader of Iris 1.8.12
carries its own 0.1 alpha test, lower than the 0.5 cutoff usually used by MASK.

Iris binds a shader pack gbuffer and can override the blend state on every `ShaderInstance.apply()`,
so Kasuga rebinds the OIT attachment after apply and before draw, and explicitly restores the blend equation,
blend factors, and color/depth mask. The scene depth is copied from Iris's after-translucent default framebuffer,
and the resolve writes back to the same framebuffer; only the dedicated OIT composite shader is allowed to bypass the Iris
unknown-shader guard, and the compatibility policy for other mod shaders is not relaxed.

WBOIT is an approximation, not an exact source-over for an arbitrary number of layers, and it does not bring vanilla water or the
transparent fragments of a shader pack into the same OIT equation; the current stage is still `AFTER_TRANSLUCENT_BLOCKS`, so its
scope is "the KasugaLib BLEND layer composited onto an already finished scene". When a floating-point target is unavailable, the Iris pipeline
has no bindable world framebuffer, or a runtime framebuffer operation fails, it falls back safely to the old
far-to-near sorting path. Iris OIT resolves the main color only; a shader pack that relies on custom extra gbuffer channels to express transparent
materials may still need pack-specific integration.

A material's `alphaCutoff` for MASK and the near-zero `1/255` working cutoff of BLEND are two independent concepts: the former
decides whether depth is written, and the latter only removes OIT work items that are almost invisible.

## Visual acceptance scene (contentTesting)

In the development client, `/ksglib debug oit` enables `OITVisualTestScene`, fixed relative to the
camera. The scene splits the screen into six regions A-F:

| Region | What it verifies |
| --- | --- |
| A | OPAQUE geometry and scene depth; the red BLEND behind it must not pass through the solid blocks |
| B | Artificial alpha ramp; `alpha < 0.5` must discard, and the retained part must write depth |
| C | Red/blue crossing faces whose depth relation is reversed left to right; ordinary object sorting cannot be correct on both sides at once |
| D | Two transparent layers in front of and behind an irregular opaque wall, checking depth copy, depth test, and depth write=OFF |
| E | 12 colored transparent faces, whose submit order changes by a deterministic permutation |
| F | Stage/framebuffer regression for a transparent model composited onto the current scene (you can stand on vanilla water to observe) |

Keybinds: `F6` toggles between WBOIT and the sorted fallback, `F7` toggles the normal/reverse submit order,
and `F8` cycles `FINAL → ACCUM_RGB → ACCUM_WEIGHT → REVEALAGE → COPIED_DEPTH`.
The HUD shows the actual path; so with Iris, with insufficient capabilities, or when OIT fails, the sorted fallback is not
mistaken for WBOIT.

Available commands:

```text
/ksglib debug oit
/ksglib debug oit mode oit|sorted
/ksglib debug oit order auto|normal|reverse|rotate|shuffle
/ksglib debug oit buffer final|accum_rgb|accum_weight|revealage|depth
```

`/ksglib debug oit capture` saves `oit_normal.png`, `oit_reverse.png`, `sorted_normal.png`, and `sorted_reverse.png`
in that order to the development client's `screenshots/kasuga_oit_visual/`, and computes

```text
E_oit = mean(abs(oit_normal - oit_reverse))
E_sorted = mean(abs(sorted_normal - sorted_reverse))
```

The acceptance condition is `E_oit < 1/255` and `E_sorted` greater than at least `2/255` and significantly higher than
`E_oit`. This does not require WBOIT to equal the exact source-over ground truth; it checks its
independence from submit order, and the tiny non-associativity error of floating-point addition should not be treated as a failure.
