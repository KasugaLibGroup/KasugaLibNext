# Aeronautics / Sable scene hosts

Aeronautics physics structures are backed by Sable `SubLevel` instances whose blocks live in a
separate plot coordinate space. `SableSceneHosts` adapts these structures to the existing
`SceneHost`, `SceneModels`, and world-UI APIs through the official Sable Companion API. The code
lives in the `modules/create` client adapter package and does not modify Sable or Aeronautics
source code.

## Block anchors

```java
// plotBlockPos is the block's actual storage position, e.g. BlockEntity.getBlockPos(), not its projected world position.
SableSceneHosts.block(minecraft.level, plotBlockPos).ifPresent(host -> {
    var scene = SceneModels.open(minecraft.level);
    scene.add(myDisplayScene, host);
    Constants.UI_BACKEND.addRenderable(myWorldPanel, host,
            new Matrix4f().translation(0, 2, 1).scale(.01f, -.01f, .01f));
    // Retain a reference to scene and call scene.close() when the content owner's lifecycle ends.
});
```

Local `(0,0,0)` corresponds to the lower corner of the anchor block. Model and UI offsets are in
blocks, and the UI matrix maps pixels into host-local space. Scene-attached panels automatically
share the model's sampled pose; standalone panels sample on the UI draw frame.

The adapter calls `ClientSubLevelAccess.renderPose(partialTick)`, transforming the plot anchor in
double precision through the rotation point, orientation, and scale into world coordinates before
constructing a nearby float basis. It never converts a distant plot origin to `Matrix4f` first, and
never draws with the uninterpolated logical physics pose.

Factories return `Optional.empty()` when Companion is absent, the target is outside a sublevel, or
the chunk/anchor is not ready. A target known to belong to Sable should retry once ready rather
than temporarily mounting at raw plot coordinates while waiting. `isAvailable()` only reports that
the optional API is on the classpath, not that a sublevel necessarily exists at a given position.

## Ordinary Create contraptions aboard a ship

```java
SableSceneHosts.contraption(entity).ifPresent(host -> {
    var source = CreateModelSources.contraptionLocal(entity);
    scene.add(new ModelDisplayTarget(source), host);
});
```

This entry composes the Sable parent pose followed by the Create entity's local pose, avoiding
double-applied rotation. Ordinary Create contraptions outside Sable continue using
`CreateSceneHosts.contraption(entity)`. Use `contraptionLocal` as the model source; the existing
independent preview source already includes rotation and must not be attached to the same moving
host again.

## Lifecycle and responsibility boundaries

The adapter continuously resolves the sublevel containing the original anchor or child entity and
checks original object identity and UUID. Breaking the original anchor, moving it out of its
original plot on a split, having the child entity leave its original sublevel, unloading, or switching
worlds permanently expires the old attachment. Reappearing identical UUIDs or storage slots never
revive old panels; content layers must create a new attachment in the new object's lifecycle.

Existing `SceneModels` / `UIBackend` owners clean up model and input resources. The adapter only
reads poses and creates no new physics authority, network protocol, or model JSON fields; gameplay
actions triggered by buttons still require validation through the existing server-side interfaces.

## Optional dependencies and verification environment

Production code only has a `compileOnly` dependency on Companion 1.6.0, and Sable supplies that
API at runtime. Ordinary Create environments without Companion installed can load the optional entry
points and receive empty results. Full Sable, Aeronautics, and Rapier libraries are not bundled into
KasugaLib artifacts.

The current integration target is official Sable 2.0.6, Aeronautics 1.3.2, and Companion 1.6.0.
These releases require at least NeoForge 21.1.228, Create 6.0.10, and Flywheel 1.0.6. Base project
versions remain unchanged; tests select the isolated run profile through explicit Gradle
properties and an init script.

Place the official Sable and Aeronautics bundled JARs into an isolated game directory's `mods`
folder and use a dedicated test world:

```sh
./gradlew :modules:create:runClientHeadless \
  -I scripts/sable-scene-smoke.init.gradle \
  -Pneo_version=21.1.228 -Pcreate_version=6.0.10-280 \
  -Pflywheel_version=1.0.6 -Pregistrate_version=MC1.21-1.3.0+67 \
  -PkasugaSableSmokeJar=/absolute/path/sable-neoforge-1.21.1-2.0.6.jar \
  -PkasugaClientDirectory=/absolute/path/to/isolated/game \
  -PkasugaQuickPlayWorld=sable-scene -PkasugaTestSableScene=true --console=plain
```

The full Sable API is only used by development acceptance sources compiled when
`kasugaSableSmokeJar` is set and does not enter the default source set. The test drives real
sublevels via the upstream server assembly and physics `teleport` APIs, including an Aeronautics
`levitite` block and an ordinary Create bearing aboard the ship. It checks main framebuffer RGBA,
clicks on scene/standalone/nested panels, structure picking, and unload cleanup. The bearing fixture
uses the native `ROTATE_NEVER_PLACE` setting so it does not place blocks back when unpowered. Results
are written to `debug/sable-scene/report.json` in the game directory; verify `passed` rather than
relying on the Gradle exit code alone.

Pure JVM regressions use Companion's actual `Pose3d` to verify distant coordinates, rotation
points, non-uniform scale, nested Create parent-child composition, identity invalidation, and
class-loading paths without Companion.

On 2026-11-11, client acceptance passed on macOS arm64 with official Sable 2.0.6 / Aeronautics
1.3.2: two phases of 30 frames each, 60 accumulated clicks on each of the three panel types, ship
translation/rotation, 25°/65° child bearing rotation, model picking, and host-removal cleanup all
passed. The native Rapier library used the unmodified aarch64 macOS version inside the official JAR.
Ordinary Create 6.0.6 and 6.0.10 windmill demos were regressed separately, and both actual curve
gauge bake paths were exercised and verified. Testing did not cover dedicated servers, multiple
clients, Valkyrien Skies, or all third-party shader pack combinations.
