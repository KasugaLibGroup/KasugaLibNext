# MC final frame offscreen output

Interface, configuration, and call reference. For the principles see [frame-output.md](../explanation/frame-output.md).

`MinecraftFrameOutputs` routes the frame at the final
`RenderTarget.blitToScreen(width, height)` call in `Minecraft.runTick`. The world, post-processing,
HUD, GUI, loading screen, and FPS debug overlay have already been drawn; the output reuses the
shader and scaling of that same native final blit. It does not replace `getMainRenderTarget()` and
does not modify the camera or intercept other internal FBOs during the world pass. This is the
WYSIWYG output entry point for the final frame.

## Usage

Register on the render thread, keep the returned registration, and close it when the output is no
longer needed:

```java
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;

// Shown in both the MC window and the output FBO.
var capture = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, frame -> {
    var texture = frame.resource();
    // texture.textureId(): the complete final frame, usable by materials, compositors, and encoders.
    // texture.framebufferId(): the color attachment can be read in the current GL context.
    // frame.width()/height(): the window's actual framebuffer pixel size.
    // frame.viewId()/frameNumber(): the output view and the delivery sequence number of that view.
    consumeTexture(texture);
});

// No longer shown in the MC window; delivered to the output FBO only.
var exclusive = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, frame -> {
    consumeTexture(frame.resource());
});

// Unsubscribe on the render thread. The view's FBO and color texture are released only after the last subscription closes.
capture.close();
exclusive.close();
```

The `consumeTexture` in the code is the caller's consumer. The output provides a color texture in
this process and the current GL context; transferring it to another process, machine, or video file
is up to the consumer (readback, encoding, or sharing). There is no need to render the world a
second time just for the output.

With no registration, the native screen blit runs directly, no FBO is allocated, and no GL state is
queried. The same view is captured only once per frame; multiple registrations share the same
read-only `OutputFrame` and FBO/color texture handle, and capture does not repeat as subscriptions
grow. Any `OFFSCREEN_ONLY` registration that delivered normally and is still valid cancels the
screen blit for that frame. Window events, the game loop, and the display swap keep running. After
all exclusive outputs are closed, normal display resumes automatically.

## Lifecycle and errors

- All registration, delivery, and closing operations are held by the render thread; the MC entry
  point and registration modification operations check the thread. Callbacks also run synchronously
  on the render thread; do not block inside them waiting on encoding/network.
- `OutputFrame` and `FrameTexture` are shared read-only borrows; they must not be written, resized,
  or deleted. A texture ID is a GPU handle, not a CPU pointer; the immutability of the Java record
  cannot stop a caller from wrongly calling GL write operations. The next frame may overwrite the
  texture, and a resize or the closing of the last subscription deletes the old GL objects. Keeping
  the frame outside the callback requires copying it into your own storage;
  do not hand a bare texture ID to an async thread with no GL context.
- The FBO resizes automatically to the final window framebuffer size; the output color is RGBA8
  with no depth. The native screen blit writes RGB only, and the output is initialized to opaque
  alpha. On a size change the new
  FBO is validated first, then the old FBO is retired, without changing MC's render size, GUI scale, or projection.
- Textures follow the OpenGL bottom-left origin. When writing top-left images such as PNG, flip Y as the consumer requires.
- Output targets restore their own independent read/draw framebuffer bindings and viewport; texture
  bindings, clear color, color mask, and scissor modified temporarily during allocation are restored
  before the final blit. Consumers should restore any other GPU state they modify and follow MC's
  state caching API.
- A consumer exception closes only that registration; other consumers keep borrowing the same
  completed frame. If target creation or drawing fails, all subscriptions of that view are closed
  and the capture error is reported once; other
  views are unaffected. If no valid exclusive output succeeds, the native screen
  blit still runs for that frame, so a single export failure does not permanently swallow the screen.
  A registration's `isClosed()` reveals outputs that were removed
  automatically; `latest()` can be borrowed only while the registration is valid.
- Closing your own/another registration or the whole router inside a callback defers the actual
  resource release until the current publication ends; borrowed resources do not become invalid
  mid-callback.
- On Minecraft shutdown, the Mixin releases all outputs before the window and render resources are destroyed.

## Multi-camera

The default view is `minecraft:main`. `FrameOutputRouter<T>`, `FrameOutputTarget<T>`,
and `FrameBlitter` do not depend on Minecraft. A view ID identifies **a view that has already
finished rendering** and can be used for multiple cameras, different output windows, or encoding
channels. The actual camera producer is now provided by
`MinecraftWorldViews`; for usage and supported scope see [the multi-camera and offscreen output document](multi-camera-output.md).

```java
var cameraOutput = MinecraftFrameOutputs.open("camera:rear", FrameOutputMode.MIRROR,
        frame -> consumeTexture(frame.resource()));

// A custom producer can also deliver directly once its own final frame is complete:
var delivery = MinecraftFrameOutputs.publish("camera:rear", width, height,
        (w, h) -> rearCameraFinalTarget.blitToScreen(w, h));
```

Registering only an output consumer does not create a camera; you also need to register a
`MinecraftWorldViews` producer with the same view ID. The camera owns the world rendering
color/depth targets, and the output
router owns the shared color target of the completed frame per view; closing a consumer only
unsubscribes, and the color target is released only when the last
subscription closes. Closing the camera producer and unsubscribing remain different operations.

A non-MC host can use `new FrameOutputRouter<>(checkThread, targetFactory)`
and then call `register(viewId, mode, consumer)`. The factory is called lazily once per view.
The retained four-argument `register` overload lets the first registration of that view choose the
factory; later
registrations share the existing target and the factory passed in is not executed until the last
subscription closes.

## Verification entry

```sh
./gradlew :modules:modelling:test :modules:modelling:renderGlTest
./gradlew :modules:modelling:runClient \
  -PkasugaTestFrameOutput=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client-directory
```

The unit tests cover shared frame identity, single capture, release on the last subscription,
replacement inside a callback, view isolation, failure fallback, and deferred release. The
standalone GL regression adds three consumers sharing one FBO/one final
blit, consumer exception isolation, and release on the last subscription, while
using Minecraft's final blit fragment shader to check RGB/orientation, opaque alpha,
resize, read/draw/viewport restoration on exception, and release on close.

The optional client test runs on the menu screen, compares the native complete GUI colors with the
output FBO pixel by pixel, toggles MIRROR/OFFSCREEN_ONLY, resizes the window and deliberately fails
one consumer, then restores normal frames and exits. Results and PNGs are written to
`debug/frame-output/` in the isolated directory.
