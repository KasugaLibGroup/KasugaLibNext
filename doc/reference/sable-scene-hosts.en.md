# Aeronautics / Sable scene hosts

`SableSceneHosts` adapts Sable's plot-backed `SubLevel` structures to the shared `SceneHost`,
`SceneModels` and world-UI APIs through official Sable Companion. It lives in the Create module's
client adapter package and does not modify upstream code.

```java
// Storage/plot position, e.g. BlockEntity.getBlockPos(); not the projected world position.
SableSceneHosts.block(minecraft.level, plotBlockPos).ifPresent(host -> {
    var scene = SceneModels.open(minecraft.level);
    scene.add(myDisplayScene, host);
    Constants.UI_BACKEND.addRenderable(myWorldPanel, host,
            new Matrix4f().translation(0, 2, 1).scale(.01f, -.01f, .01f));
    // Retain scene and close it when its content owner is destroyed.
});
```

Local zero is the anchor block's lower corner. The adapter reads `renderPose(partialTick)` and
projects the distant anchor, rotation point, orientation and scale in doubles before constructing
a nearby float basis. Existing scene panels share the model's sampled frame.

Factories return empty when Companion is absent, the target is outside a loaded sublevel, or the
anchor is not ready. Retry a known Sable target when it becomes ready; do not temporarily mount it
at its raw plot coordinates. `isAvailable()` only reports API availability.

Use `SableSceneHosts.contraption(entity)` for an ordinary Create contraption inside a Sable plot.
It composes the Sable parent with the Create child exactly once. Pair it with
`CreateModelSources.contraptionLocal(entity)`; the independent preview source includes rotation
already. Ordinary Create contraptions outside Sable keep using `CreateSceneHosts`.

Each attachment checks the current resolution, original object identity and UUID. Breaking or
remapping an anchor, leaving the original sublevel, unloading or changing worlds expires the old
attachment. Reused UUIDs or plot slots never revive it. Content owners create a new attachment
when a split/reloaded object becomes available. Existing scene/UI owners clean canvases and input
captures. Gameplay callbacks still use their existing server authority and validation.

Only Companion 1.6.0 is a compile-only dependency. Sable supplies it at runtime; no full Sable,
Aeronautics or native library is bundled into KasugaLib. Optional entry points load safely without
Companion installed.

The integration target is official Sable 2.0.6 and Aeronautics 1.3.2, requiring NeoForge 21.1.228,
Create 6.0.10 and Flywheel 1.0.6. Base project versions remain unchanged. See the
[Chinese reference](sable-scene-hosts.md) for the isolated smoke command and exact properties.
The opt-in smoke compiles against the official Sable JAR, assembles real server sublevels including
an Aeronautics levitite block and an ordinary Create bearing aboard it, drives the original physics teleport API, and checks RGBA, input,
model picking and unload cleanup. Inspect `debug/sable-scene/report.json`, independently of the
Gradle exit code. JVM tests additionally cover far coordinates, rotation points, non-uniform scale,
nested Create transforms, identity changes and missing-Companion linkage.

On 2026-11-11, macOS arm64 client validation passed with official Sable 2.0.6 / Aeronautics 1.3.2:
two phases of 30 frames, 60 clicks on each scene/direct/nested panel, ship translation and rotation,
25°/65° child bearing rotation, model picking, and removal with held input. The bearing fixture uses
the native never-place-on-stop setting. Rapier uses the unmodified aarch64 macOS library in the official JAR.
Ordinary Create 6.0.6 and 6.0.10 windmill regressions also passed, including both actual curve gauge bake paths.
Dedicated servers, multiple clients, Valkyrien Skies, and every third-party shader combination were not covered.
