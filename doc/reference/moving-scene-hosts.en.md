# Scenes and UI on moving structures

`SceneHost` supplies an external host's interpolated pose to `SceneModels` and world UI.
The current adapter targets ordinary Create 6.0.6-98 and 6.0.10-280 `AbstractContraptionEntity` instances.
Call these APIs on the client render thread.

```java
var host = CreateSceneHosts.contraption(entity);
var scene = SceneModels.open(Minecraft.getInstance().level);
var model = new ModelDisplayTarget(CreateModelSources.contraptionLocal(entity));
model.panel(new Matrix4f().translation(2, 3, 1).scale(.01f, -.01f, .01f), 260, 240);
var root = new WorldDisplayScene();
root.add(model);
var entry = scene.add(root, host).position(new Vec3(0, 0, 2));
// Later: entry.close(), or scene.close().
```

Use `contraptionLocal` when following the entity. The existing independent-preview source,
`CreateModelSources.contraption(entity)`, already includes the host's local rotation.
Applying the moving host again would rotate it twice. Mounting creates another display instance;
it does not replace Create's native contraption renderer.

The scene owns its model sources and attached panels, and borrows the host. Removal, chunk
unloading, or replacement of the entity's contraption closes the attachment, including when hidden.
A world change closes the scene. Reappearing structures need new attachments; reused entity IDs
cannot revive old ones. `SceneModels.Entry.position` and `transform` are host-local when following.

Models and attached UI share one sampled main-view pose. Moving model picking uses that same
drawn frame, with source-local block positions and world-space hit locations. This is read-only
picking; callbacks retain responsibility for existing server interaction, authority and reach checks.

Standalone panels can follow the same kind of host:

```java
Constants.UI_BACKEND.addRenderable(panel, host,
        new Matrix4f().translation(2, 3, 1).scale(.01f, -.01f, .01f));
// Later: Constants.UI_BACKEND.removeRenderable(panel).
```

The matrix maps UI pixels into host-local blocks; UI +Y points down. The panel must implement
`GuiEventListener` and expose dimensions to support input. Host removal drops the registration,
canvas and input captures. Toolkit resources keep their existing lifecycle ownership.

Existing static APIs keep their behavior. `ScenePose` retains a double world origin and subtracts
the camera before converting to floats. Static remote panels may also use
`addRenderable(panel, new ScenePose(origin, localMatrix))`.

Aeronautics uses Sable `SubLevel` objects with a separate chunk space and render pose. It requires
a separate `SceneHost` adapter and explicit parent composition for Create contraptions inside a
sublevel. See [Aeronautics / Sable scene hosts](sable-scene-hosts.en.md) for its separate adapter.
Valkyrien Skies remains unadapted; validation of one host does not establish another's compatibility.

Custom hosts implement `isAlive()` and `pose(partialTick)` without advancing game or physics time.
False liveness or a null pose permanently expires an attachment; temporary asset loading is not
host destruction.

The development command `/kasuga_create_mount` creates a native windmill, a small live copy,
a blue scene panel and a green standalone panel in the disposable gallery dimension.
`/kasuga_create_mount stop` closes the demo and returns the player.

Run `:modules:create:runClientHeadless -PkasugaClientDirectory=/absolute/isolated/game
-PkasugaQuickPlayWorld=test-world -PkasugaTestCreateMounts=true` for automated acceptance.
Inspect `debug/create-moving-scene/report.json` for `passed: true`, independently of Gradle's
exit status. The smoke checks actual RGBA, clicking, picking, rotation, translation and unload
cleanup. Pure JVM tests additionally cover far-coordinate precision and main-view sampling.
