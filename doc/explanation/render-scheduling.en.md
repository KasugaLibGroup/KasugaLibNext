# How model visibility and render scheduling work

For model handles, scheduling methods, and Entity/BlockEntity integration examples see the
[API reference](../reference/render-scheduling.md).

## Division between logic and drawing

The state machine and the pose driver decide how a model moves, while the host render entry decides where it is
placed and whether it is visible. Both act on the same model instance; they must not create separate instances
to manage animation and drawing apart. A resource mount belongs only to the render-resource lifecycle.

## Visibility strategies and orthogonal gates

Continuous drawing, explicit host control, and vanilla renderer marks are the three strategies. Distance and
frustum are extra gates that do not disappear when the mode changes. A vanilla renderer emits a visibility mark
only after entity tracking/frustum or block-entity view distance/section culling; the global model pipeline
consumes that mark.

Instances are distinguished by object identity. A culled instance skips animation sampling and upload for the
frame, while an independent runtime keeps advancing its physics. Static block geometry suits section-mesh
batching; a skinned model that changes its vertices every frame has to be managed by a dynamic host or an
explicit strategy, and cannot rely on a static chunk dispatcher to give per-block dynamic callbacks.

## Frame and view boundaries

The host renderer writes the visibility mark, the first model material stage freezes it, and later stages reuse
it. The double-buffered marks are swapped at the frame boundary, and an instance that was not marked again
expires. Models with mixed materials share pose sampling, so the passes do not advance the animation repeatedly.

Each camera view clears the visibility marks on its own but shares the frame's animation token. An instance
visible only in a later view is still sampled once, and an instance already sampled in an earlier view is not
advanced again. The scheduling state is cleaned up when the last mount is removed, so unmounted instances do not
linger.

The world stage submits opaque and mask materials first, then submits blend models after translucent terrain.
For the alpha algorithm and depth behavior see [Alpha Rendering](render-alpha-passes.md), and for multi-camera
state isolation see [Multi-Camera and Offscreen](offline-rendering-and-multi-cam.md).
