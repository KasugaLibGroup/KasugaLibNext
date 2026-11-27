# Model Depth Bias

`ModelInstance` defaults to `DepthBias.NONE`, preserving normal depth testing and occlusion. For models explicitly intended to overlay another surface, set an instance-level policy on the render thread:

```java
import lib.kasuga.rendering.models.uml.backend.DepthBias;

instance.setDepthBias(DepthBias.DECAL);
// Restore normal depth:
instance.setDepthBias(DepthBias.NONE);
```

`new DepthBias(factor, units)` controls the slope-scaled offset based on polygon depth slope and the constant offset measured in the backend's minimum resolvable depth units. Under conventional depth direction, negative values pull toward the camera and positive values push away from the camera; both parameters must be finite. `DECAL` uses `(-1, -1)` for intentionally coplanar overlays and may be tuned per scene as needed. This bias applies to filled polygons (triangles, quads) and does not alter standalone lines or points.

The OpenGL backend applies polygon offset after shader setup and restores the previous enable state, `factor`, and `units` after each draw. Ordinary draws, CPU/GPU skinning, global batching, and model translucent replay all use the same parameters. Instances with different bias policies are not merged into the same batch; prepared translucent draws capture their bias so subsequent sub-passes replay consistently.

This policy alters raster depth only; it does not change model coordinates, outline projection, skeleton, collision, or picking. Do not enable it unconditionally across all models, and do not use large offsets to patch broken geometry. It does not define draw order among overlapping faces within the same instance, nor does it solve sorting among multiple co-depth surfaces during depth peeling.

The setting belongs to the current instance's runtime display state and is not persisted to model assets or world saves. When resource reload creates new instances, callers must re-apply it on the new instances.

Verification entry:

```sh
./gradlew :modules:render-core:test :modules:modelling:modelUnitTest --offline
./gradlew :modules:modelling:renderGlTest --offline
./gradlew :modules:modelling:runClient -PkasugaTestModelDepthBias=true --offline
```

Client verification uses real Minecraft model shaders and an offscreen target, writing the report and screenshots to `debug/model-depth-bias/` in the run directory. Compatibility across platform drivers and with shader packs enabled must be verified separately.
