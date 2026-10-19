# How cameras and the shared animation timeline work

For the control interfaces, track fields, and clip examples see the [API reference](../reference/camera-animation.md).

## Pose sources and component tracks

A camera first takes position, orientation, FOV, and output size from a pose provider, and animation only overrides the components it defines.
Position uses world coordinates and rotation follows the host camera's angle convention; unwrapped angles interpolate directly, so continuous multi-turn rotation can be expressed.
Direct control starts from the current pose, replaces the provider, and ends the original animation, so the next frame is not overwritten by the old tracks.

Track keyframes are sorted and copied at construction, adjacent keyframes interpolate by the easing function, and outside the first and last the endpoint is held. Components with no track keep coming from the provider.
Optical zoom changes the projection; it does not scale the world or the output texture. The relationship is:

```text
tan(newFov / 2) = tan(baseFov / 2) / zoom
```

Zoom is evaluated against the base FOV of the current provider and is not multiplied across successive frames. Invalid zoom caused by interpolation is guarded by a positive-value bound.

## Clock ownership

A standalone player owns its own clock; the game tick advances time and render frames only sample by partial tick. Sampling must not advance the clock again.
A shared timeline is driven by one host, and models and cameras only follow, which prevents one scene from being advanced repeatedly by multiple targets.

During shared playback, pause, seek, and speed control act on the common clock; closing one camera or detaching one target does not stop the others.
Pausing the frame output of one camera and pausing the whole timeline are different operations. After playback finishes the end pose can be kept; clip looping is decided uniformly by
the timeline period, and shorter targets hold their own endpoint within the shared period.

## Model and camera channels

Model poses and camera poses share the timing/sampling kernel, but their output targets handle bone, morph, material, and named camera track channels separately.
The logical camera name is independent of the output view ID, and one camera track can be bound to multiple actual camera views. A model reload only swaps the output target and
does not reset the external shared clock. The layer weights and state transitions of a layered state machine are still managed by the state machine itself.

The control thread delivers the clock through an immutable snapshot and the render thread samples it. A closed or failed camera no longer accepts control, which keeps old controller references
from driving resources that have already been released. The output size of a window camera view is still determined by the framebuffer/DPI and is not frozen by animation tracks.
