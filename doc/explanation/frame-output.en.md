# How final frame output works

For registration, consumers, lifecycle, and readback see the [API reference](../reference/frame-output.md).

## Capture point

The capture sits at the host's final screen blit, after the world, post-processing, HUD, GUI, and loading screen
have finished. The output reuses the shader and scaling of the native final blit; it does not replace the main
render target, and it does not render the world again for the final frame.
A world camera view product and the final main frame that includes the full interface are different layers; see
[Multi-Camera and Offscreen](offline-rendering-and-multi-cam.md) for details.

## One capture shared per view

The router uses the view as the resource-ownership boundary. A frame of one view is captured once, and
synchronous consumers borrow the same completed frame and color texture.
The target is allocated lazily on the first valid output and released when the last consumer leaves. With no
subscribers, the native screen blit runs directly.

An exclusive output cancels only this frame's screen blit; it does not stop window events, the game loop, or the
display swap. When consumption or capture fails and no exclusive output succeeded, the screen presentation is
kept, so an export failure does not swallow the picture for good.

## Borrowing, synchronization, and release

The output is a synchronous read-only borrow on the render thread; a consumer may not resize it or delete the
texture. The next frame overwrites the storage, and a size change replaces the target. Keeping the picture beyond
the callback requires copying into your own GPU/CPU storage and handling synchronization.
A GPU texture number is only a handle: it is not a CPU pointer, and it cannot be handed to a thread that has no
current GL context.

An exception in one consumer removes only that consumer, while an exception during target creation or drawing
closes the whole view. When a subscription is closed or replaced during delivery, the actual release is deferred
to the end of that delivery, so other callbacks can still read the borrowed frame.

The output color is RGBA8 with opaque alpha and no depth attachment; the origin follows the OpenGL bottom-left
convention. The target is sized to the actual framebuffer pixel size. The capture restores the read/draw FBO, the
viewport, and any GPU state it changed temporarily; a consumer still has to restore its own extra changes, and on
host shutdown the output resources are released together before the main context is destroyed.
