# World UI and Free-Cursor Input

Package: `lib.kasuga.rendering.models.mc.backend.ui`. Call these APIs on the Minecraft client thread.
For coordinate transforms and input ownership, see the [design](../explanation/world-ui-input.en.md).
[中文](world-ui-input.md).

## Integrating an Existing Screen

```java
Minecraft mc = Minecraft.getInstance();
UIBackend backend = Constants.UI_BACKEND; // Available after client setup.
Screen panel = new MyPanelScreen();
panel.init(mc, 320, 180); // Panel-local dimensions, independent of the input viewport.

// Center the panel 2.5 blocks ahead of the camera, 1.92 blocks wide. Local +Y points down.
var camera = mc.gameRenderer.getMainCamera();
var center = camera.getPosition().add(
        camera.getLookVector().x * 2.5,
        camera.getLookVector().y * 2.5,
        camera.getLookVector().z * 2.5);
Matrix4f localToWorld = new Matrix4f()
        .translation((float) center.x, (float) center.y, (float) center.z)
        .rotate(camera.rotation())
        .scale(.006f, -.006f, 1)
        .translate(-160, -90, 0);
backend.addRenderable(panel, localToWorld);
backend.openPointer();
```

The built-in `world_ui` pipeline renders `Constants.UI_BACKEND` at `AFTER_LEVEL` in the main view.
Call `openPointer()` from gameplay with a player, a world, and no other Screen active, for example from a key binding.
A chat command must finish the chat Screen first, as the development demo does with `mc.setScreen(null)`.
The method returns the `WorldUiInputScreen` submitted to `Minecraft.setScreen`; other mods may still cancel screen opening.

The input Screen draws no background and does not pause the world. Minecraft's Screen lifecycle releases the mouse.
Escape closes the input layer and restores mouse-look. The panel remains registered and returns to crosshair interaction.
Call `removeRenderable(panel)` to remove it. Registrations belong to the backend and survive world changes;
callers must remove world-specific panels when leaving their world.

In normal mouse-look, clicking an `EditBox` or `MultiLineEditBox` with the crosshair temporarily captures
the keyboard. Characters reach the text field while gameplay movement and shortcuts are suppressed;
the mouse still rotates the view. Escape clears text focus and exits without opening the pause menu.
Repeats/releases from keys still held at exit are consumed; fresh presses resume gameplay. Buttons,
disabled/hidden fields, and read-only fields do not start capture. World changes, window focus loss,
another Screen, or target removal also end capture.

A panel with its own widget tree can implement `WorldUiTextInput` and report editable text focus
through `hasTextInputFocus()`. It must clear internal focus when `setFocused(false)` is called,
so Escape, target removal and panel changes can end editing. The shared backend remains independent
of third-party widget libraries. LDLib2's `LdElement.attach(backend, ui, width, height, transform)`
implements this contract and owns ticking, closing and world-boundary cleanup; see the
[LDLib2 world-UI adapter and demo](../../external/uip/ldlib/README.md).

A panel Screen can use its normal child-widget focus, keyboard, and text handling. Override the panel's own
`renderBackground` if it should be transparent; its appearance is separate from the input layer.
World rendering temporarily disables panel blur and then restores its previous value.

Widgets first render into a transparent RGBA canvas with the normal orthographic GUI depth range.
The canvas is then drawn once on the world plane, preserving the small depth separation between text
and shadows. Canvas density follows GUI scale, targets resize when needed, and local scissor rectangles
use canvas dimensions. Premultiplied-alpha composition preserves translucent edges and does not write
the panel rectangle into world depth.
`MIN_GUI_Z`, `MAX_GUI_Z`, and boundary text shadows affect only canvas ordering. Composite vertices
always use local Z=0, so GUI depth cannot push the panel towards the player. Canvas clipping reserves
one extra unit beyond each GUI depth endpoint.

## API and Contracts

| API | Behavior |
| --- | --- |
| `addRenderable(Renderable)` | Centers the default panel at the world origin, using GUI height as pixels per block; duplicate registration does not duplicate drawing |
| `addRenderable(Renderable, Matrix4fc)` | Registers the panel and copies its pixel-local to absolute-world transform |
| `setTransform(Renderable, Matrix4fc)` | Sets or updates a transform; rejects non-finite, non-affine, or singular matrices |
| `removeRenderable(Renderable)` | Stops rendering and releases that target's captured mouse buttons and keys |
| `openPointer()` | Opens the input Screen; throws `IllegalStateException` without a player/world or while another Screen is active |
| `getInputRouter().setMaxDistance(float)` | Maximum hit distance from the rendered near plane, default 5 blocks; requires a finite positive value |
| `worldChanged()` | Clears input and releases temporary GPU canvases while retaining panel registrations; the global backend uses the existing world-change hook |
| `close()` | Clears input, canvases, and registrations; custom backend owners must call it on destruction |
| `resetInput()` | Clears hover, keyboard focus, and captures, balancing held mouse buttons and keys with release events |
| `isKeyboardCaptured()` | Whether normal-view text keyboard capture is active |
| `WorldUiTextInput.hasTextInputFocus()` | A third-party panel reports editable text focus in its own widget tree and clears that state on focus loss |
| `renderAllUis(GuiGraphics, float)` | Custom world-render entry using the current world projection and effective model-view; do not call during the HUD orthographic pass |
| `renderWorld(WorldRenderPipelineContext)` | World-pipeline entry for a custom backend; saves/restores projection and model-view and accepts only the main view |

`Screen.width/height` or the position and size of a `LayoutElement` define the input rectangle.
Objects without `GuiEventListener` or known dimensions only render. Plane intersection reuses
`RayTracingHelper.intersectRayWithPlane`. The nearest rectangle receives input; ties follow registration order.
Picking does not test block occlusion or texture alpha.

The transparent layer consumes incoming mouse, scroll, and keyboard events, including misses, so they do not operate the world.
Keyboard input goes to the last clicked panel, whose normal child dispatch selects the widget. Escape always exits.
An accepted mouse press captures its target per button. Dragging outside the rectangle continues to project onto
that panel's plane, and release returns to the original target. Parallel rays or intersections behind the ray origin
retain the last valid capture coordinates. Window focus loss, screen replacement, target removal, dimension changes,
and disconnect cancel captures.

## Reusing an Existing Raycast

Use the projection component independently when the host already has a world-space hit solver:

```java
var projection = new ScreenPointerProjection(worldProjection, guiWidth, guiHeight);
Vector3f origin = new Vector3f(), direction = new Vector3f();
if (projection.ray(mouseGuiX, mouseGuiY, origin, direction)) {
    // If viewMatrix includes camera translation, inverseView restores absolute world coordinates.
    inverseView.transformPosition(origin);
    inverseView.transformDirection(direction).normalize();
    existingRaycast(origin, direction);
}
```

When the view matrix contains only camera rotation, apply its inverse and then add the camera world position to `origin`.
`ray` produces a unit ray starting on the near plane, supporting perspective and orthographic cameras.
Coordinates are GUI logical pixels with `(0,0)` at the top left. Convert raw GLFW/window cursor coordinates using
`guiWidth / screenWidth` and `guiHeight / screenHeight`; do not mix framebuffer dimensions with window coordinates.
The constructor copies and validates the projection and viewport, throwing `IllegalArgumentException` for invalid values.
Non-finite cursor coordinates make `ray` return `false`. Capture the rendered projection again after FOV, zoom, or viewport changes.

## Development Acceptance

```sh
./gradlew :modules:modelling:modelUnitTest --tests 'lib.kasuga.rendering.models.mc.backend.ui.*' --offline
./gradlew :modules:modelling:compileContentTestingJava --offline
./gradlew :modules:modelling:runClient
```

In a test world, run `/ksglib debug world_ui`. A panel is fixed in front of the camera at its opening pose,
and the free cursor opens immediately. Click both sides, type in the text field, scroll, drag outside the panel,
and release. Check the counters and local drag deltas. Press Escape, turn the camera, and check crosshair interaction.
Run the command again to rebuild the panel and reopen the cursor. `/ksglib debug world_ui stop` removes it.

After leaving the free cursor, crosshair-click the text field and type WASD/Chinese. Text should change
without moving the player, while the mouse still rotates the view. Hold W, press Escape, then release
and press W again: editing exits first, and the fresh press restores movement. Buttons must not capture
the keyboard. Run `/ksglib debug world_ui min_z` and `/ksglib debug world_ui max_z` to check complete
glyphs/shadows and unchanged panel distance; both presets use the same world transform as the default.

Check that text, shadows, button borders, and edit-box borders have no stripes or missing fragments.
Enter long ASCII and Chinese text to check clipping, then stop the demo and verify world, hand, and HUD rendering.

Window resizing, GUI scale, FOV, third-person views, and focus changes require real-client acceptance.
JVM tests cover projection math and routing state; they do not replace in-game rendering or GLFW verification.

With an existing isolated test world, run the real keyboard Mixin/character and GUI-depth pixel smoke:

```sh
./gradlew :modules:modelling:runClientHeadless --offline \
  -PkasugaClientDirectory=run/world-ui-smoke -PkasugaQuickPlayWorld=TestWorld \
  -PkasugaTestWorldUiInput=true
```

Place `TestWorld` under that run directory's `saves/`. The test uses a hidden window and exits on
completion; JSON and PNG output is in `debug/world-ui-input/` under the run directory. It does not
cover OS input-method candidate windows or the feel of player interaction.
