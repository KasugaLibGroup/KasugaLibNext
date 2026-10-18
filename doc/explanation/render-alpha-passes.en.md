# How model material alpha rendering works

Model materials are now split into three classes by the glTF/generic material alpha contract:

| Class | GPU state | Fragment behavior | Order |
| --- | --- | --- | --- |
| `OPAQUE` | No blending, depth write | Ignores the sampled alpha and outputs 1 | `AFTER_ENTITIES` priority 0 |
| `MASK` | No blending, depth write | Fragments below the material `alphaCutoff` are `discard`ed, the rest output 1 | `AFTER_ENTITIES` priority 1 |
| `BLEND` | OIT accumulation/revealage, no depth write | Discards only the near-invisible alpha `<= 1/255` and keeps the actual alpha | `AFTER_TRANSLUCENT_BLOCKS`, WBOIT needs no sorting |

`BackendInstance` creates a separate `FlatModelData` and vertex buffer for each non-empty pass,
so a model with several materials does not draw incompatible geometry repeatedly into the wrong RenderType.
OPAQUE and MASK can still go into the global batch, but the batch key includes the alpha pass; BLEND does not go
into the global batch, which keeps the two OIT geometry submissions apart from ordinary batching state.

The glTF `alphaMode` and `alphaCutoff` reach the backend through `GltfMaterialData`. An older loader with no
alpha contract defaults to `OPAQUE`; the PMX loader infers OPAQUE, MASK, or BLEND from the diffuse alpha and the
texture alpha.

The non-Iris path for BLEND is weighted-blended OIT (WBOIT) with two geometry sub-passes: the first adds
`color * alpha * weight` and `alpha * weight` into an `RGBA16F` accumulation, and the second multiplies
`1 - alpha` into an `R16F` revealage. Both passes use the scene depth copied from the main framebuffer and test
depth without writing it; a final fullscreen resolve computes
`color = accumulation.rgb / accumulation.a` and `alpha = 1 - revealage`, then composites back into the main
target with straight-alpha source-over. KasugaLib's BLEND geometry therefore does not depend on back-to-front
submission order on this path.

With an Iris shaderpack enabled the same accumulation/revealage/resolve targets are used, but Iris's
`entities_translucent` shader is kept to produce the straight-alpha color after materials, PBR, lighting, and
fog. Since Kasuga's depth weights cannot be injected into its fragment shader without changing the shaderpack,
the Iris path uses constant-weight WBOIT with a weight of 1: RGB accumulates with `SRC_ALPHA, ONE`, A accumulates
with `ONE, ONE`, and revealage stays `ZERO, ONE_MINUS_SRC_ALPHA`. The translucent entity shader of Iris 1.8.12
brings its own 0.1 alpha test, below the 0.5 cutoff MASK usually uses.

Iris binds the shaderpack gbuffer in every `ShaderInstance.apply()` and may override blend state,
so Kasuga rebinds the OIT attachment after apply and before draw, and explicitly restores the blend equation,
blend factors, and color/depth masks. The scene depth is copied from Iris's after-translucent default framebuffer,
and the resolve writes back into the same framebuffer; only the dedicated OIT composite shader may bypass the
Iris unknown-shader guard, and the compatibility policy for other mod shaders is not loosened.

WBOIT is an approximation, not exact arbitrary-layer source-over, and it does not fold vanilla water or
shaderpack translucent fragments into the same OIT equation; the stage is still `AFTER_TRANSLUCENT_BLOCKS`, so
its scope is "the KasugaLib BLEND layer on top of an already finished scene". When a floating-point target is
unavailable, the Iris pipeline has no bindable world framebuffer, or a runtime framebuffer operation fails, it
falls back safely to the old back-to-front sorting path. Iris OIT resolves only the main color, so a shaderpack
that expresses translucent materials through custom extra gbuffer channels may still need pack-specific
integration.

The material `alphaCutoff` of MASK and the near-zero `1/255` working cutoff of BLEND are two separate concepts:
the first decides whether depth is written, and the second only removes nearly invisible OIT work items.

For debug commands, keybinds, and the acceptance entry see the
[API reference](../reference/render-alpha-passes.md).
