# Scenes and UI on moving structures

`SceneHost` adapts an external moving structure's pose to `SceneModels` and world UI. The current
adapter targets vanilla Create 6.0.6-98 and 6.0.10-280 `AbstractContraptionEntity` instances,
supporting entity translation as well as the rotation and pivot offsets from `applyLocalTransforms`.
Call all entry points on the client render thread.

## Scene attachments

```java
var host = CreateSceneHosts.contraption(entity);
var scene = SceneModels.open(Minecraft.getInstance().level);
var model = new ModelDisplayTarget(CreateModelSources.contraptionLocal(entity));
model.panel(new Matrix4f().translation(2, 3, 1).scale(.01f, -.01f, .01f), 260, 240);
var root = new WorldDisplayScene();
root.add(model);
var entry = scene.add(root, host);

// Optional: both position and matrix are in the host's local coordinate frame.
entry.position(new Vec3(0, 0, 2));
// Actively close one attachment or the entire scene.
entry.close();
scene.close();
```

`contraptionLocal` outputs the model in the structure's original local coordinates. Independent
previews continue using `CreateModelSources.contraption(entity)`, which includes the preview
rotation and must not apply the same host rotation again. To mount an existing UML model or
composite scene, pass your `MinecraftModelSource` or `WorldDisplayTarget` to
`scene.add(source, host)`. This adds a display instance and does not replace Create's native
contraption renderer.

The scene owns the source and its child panels and borrows the host. Entity removal, chunk unloading,
or replacement of the entity's contraption automatically closes the attachment; hidden attachments
still check the host. Switching worlds closes the entire scene. Reappearing structures need new
attachments; a reused entity ID cannot reattach old panels to another entity.

Models and attached UI share the same frame's host pose. `scene.pick(worldFrom, worldTo, partialTick)`
uses the last actually sampled main-view frame for moving attachments; hit block positions remain
source-local coordinates while hit locations are world coordinates. This interface only provides
picking and does not replace Create's server-side interaction, authority, or reach checks; panel
callbacks continue using their existing gameplay network interfaces.

## Standalone world panels

```java
// panel is an existing Renderable; interaction also requires GuiEventListener and queryable dimensions.
Constants.UI_BACKEND.addRenderable(panel, host,
        new Matrix4f().translation(2, 3, 1).scale(.01f, -.01f, .01f));

// Actively unbind:
Constants.UI_BACKEND.removeRenderable(panel);
```

The matrix maps UI pixels into host-local block coordinates; local `+X` points right and `+Y` points
down, so negative Y scaling is commonly used. World panels created through adapters such as LDLib2
can also be rebound to a moving attachment through this entry point. When the host expires, the
backend registration is removed, canvases are released, and mouse/keyboard captures are balanced;
other UI toolkit resources remain managed by their existing lifecycles.

Static `add(source, Vec3)` and `addRenderable(panel, Matrix4fc)` retain their original semantics.
When a static panel needs distant-coordinate precision, use
`addRenderable(panel, new ScenePose(origin, localMatrix))`. `ScenePose` preserves a double-precision
world origin and subtracts the camera position before generating a float matrix.

## Other moving systems

Aeronautics uses Sable `SubLevel` instances rather than ordinary Create `AbstractContraptionEntity`
instances. Its separate chunk space, `ClientSubLevelAccess.renderPose(partialTick)`, rotation point,
and split lifecycle require a separate `SceneHost` implementation; if ordinary Create contraptions
are also mounted aboard a ship, parent and child poses must be composed in their actual reference
frames. Sable / Aeronautics is adapted through `SableSceneHosts`; see
[Aeronautics / Sable scene hosts](sable-scene-hosts.md). Valkyrien Skies is not adapted yet;
validation scope for each adapter is recorded separately.

Custom hosts implement `isAlive()` and `pose(partialTick)`. Sampling must not advance physics or
game ticks. Returning a `ScenePose` supplies the current interpolated pose; if the host disappears
(`isAlive()` returns `false` or `pose` returns `null`), the attachment expires permanently. Do not
treat temporary resource unavailability as host destruction.

## Development demo and verification

Running `/kasuga_create_mount` in a development client enters the isolated demo dimension, assembles
a native Create windmill, and attaches a blue scene panel, a green standalone panel, and a scaled-down
structure copy. Both panels count clicks. `/kasuga_create_mount stop` closes the demo and returns to
the previous location.

```sh
./gradlew :modules:create:runClientHeadless \
  -PkasugaClientDirectory=/absolute/path/to/isolated/game \
  -PkasugaQuickPlayWorld=your-test-world \
  -PkasugaTestCreateMounts=true --offline --console=plain
```

Automated acceptance checks final framebuffer RGBA during rotation and translation, clicks on both
panel types, structure picking, and cleanup of canvas registrations, focus, and mouse capture after
host removal; reports and screenshots live in `debug/create-moving-scene/` inside the game
directory. A zero Gradle exit code does not substitute for checking `"passed": true` in the report.
Numerical tests additionally cover sub-pixel round-trips near 30 million blocks, multi-camera
sampling isolation, and hidden host cleanup.
