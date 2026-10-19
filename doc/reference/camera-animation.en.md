# Camera Control and Animation Tracks

Interfaces, configuration, and call reference. For the mechanics see [camera-animation.md](../explanation/camera-animation.md).

The camera handles returned by `MinecraftCameras.create(...)` and `MinecraftFrameWindows.createCamera(...)`
are Free Cameras, and they support position, rotation, optical zoom, and keyframe playback. For the three camera types and entity following see
[Player / Free / Fixed Camera](cameras.md). All control operations run on the render thread.

## Direct control

```java
camera.moveTo(x, y, z);                 // world coordinates, absolute position
camera.moveBy(dx, dy, dz);              // world coordinates, incremental move
camera.rotateTo(yaw, pitch, roll);       // degrees, follows the Minecraft camera convention
camera.rotateBy(dYaw, dPitch, dRoll);    // incremental rotation
camera.setVerticalFov(60);              // vertical FOV, must be within (0, 180) degrees
camera.zoom(2);                        // magnifies the projection 2x
WorldCameraView pose = camera.pose();   // pose for the current tick
```

Direct control starts from the pose of the current animation tick, keeps the components it does not modify and the output size, then replaces the pose with a fixed one and stops the original animation.
Use `MinecraftCameras.createFixed(...)` when you need to follow an entity; its FOV/projection control preserves the target binding.
Free can still use `updatePose(Supplier<WorldCameraView>)` to describe a custom pose provider; replacing the provider clears the animation.
`WorldCameraView` also provides the same immutable transform methods, so you can compose providers yourself.

Camera zoom changes the projection; it does not scale the world or the output resolution. The factor `m` satisfies
`tan(newFov / 2) = tan(baseFov / 2) / m`: greater than 1 magnifies, less than 1 shrinks.
At the numerical extremes the FOV stays inside the representable `(0, 180)` degree range.

## Animation tracks

```java
var start = camera.pose();
var clip = CameraAnimationClip.builder(Id.parse("example:camera_pan"), 3f)
        .move(0, start.x(), start.y(), start.z(), Easing.easeInOutCubic())
        .move(3, start.x() + 10, start.y() + 2, start.z(), null)
        .rotate(0, start.yaw(), start.pitch(), start.roll(), Easing.easeInOutSine())
        .rotate(3, start.yaw() + 90, 15, 0, null)
        .zoom(0, 1, Easing.easeInOutCubic())
        .zoom(3, 2, null)
        .build();
camera.animation().play(clip, false);
```

Each track independently drives one component of `x / y / z / yaw / pitch / roll / zoom`.
`move` and `rotate` are convenience methods that add keyframes for three tracks at once; to change only one
component use `builder.keyframe(Channel.YAW, time, value, easing)`.
Position and angle values are absolute; zoom is relative to the FOV the provider supplies for the frame, is
re-evaluated every frame, and does not accumulate magnification.
Missing tracks keep the provider's values, and a window camera still updates its output size with the framebuffer/DPI.

Time is in seconds. Tracks are sorted and copied automatically; duplicate times, duplicate components, times beyond the clip duration, empty tracks, and illegal values are rejected at construction time.
Outside the first and last keyframes the endpoint value is held. Easing reuses the model's `Easing` and applies to the segment after the keyframe.
Rotation interpolates unwrapped angle values, so `0 → 720` performs two full turns; to cross the 360 degree boundary, for example, use `350 → 370`.
When a back/elastic easing drives zoom to zero or below, it is clamped to a tiny positive value so the projection stays valid.

```java
camera.animation().pause();        // pauses the animation; the camera keeps outputting frames
camera.animation().seek(1.5f);     // jumps immediately and clears the old interpolation interval
camera.animation().setSpeed(2f);   // double speed; 0 freezes the clock
camera.animation().resume();
camera.animation().isPlaying();
camera.animation().currentTime();  // monotonic clock even while looping
camera.animation().stop();         // removes the tracks and restores the original pose provider
```

After a non-looping playback ends, the end pose is kept; replay with `play`, or `seek(0)` and then `resume()`.
During standalone playback, `camera.pause()` pauses both frame output and the camera's own clock advance, and `camera.resume()` restores them.
When following a shared timeline, `camera.pause()` pauses only that camera's output while other targets keep playing; to pause the whole scene use `timeline.pause()`.
A closed or failed camera rejects further control, including animation controller references saved earlier.

Minecraft advances the clock automatically once per game tick (20 Hz), and does not advance it while the world is paused; each frame interpolates by the game's partial tick.
`OwnedCamera` / `CameraAnimationPlayer` in `render-core` can be used standalone: the host calls `tick(dt)` and
`samplePose(partialTick)` / `sample(partialTick)`. Sampling for rendering does not itself advance the clock.

## Integration with existing model animation

The model's `AnimationPlayer<T>` and the camera adapter now both use `AnimationPlayback<T, R>`.
The existing `AnimationSampler<T>` is an `AnimationSource<T, Pose>` that outputs the model `Pose`; the camera's `CameraClipSampler` outputs a `CameraPose` that has not been applied to the world, and the shared kernel handles timing and sampling.
The original model `play(sampler, data, loop)` and JSON are kept. Named clips and static poses now all use
[ModelPosing](model-posing.md); the glTF-specific driver has been migrated to that public entry point.

The existing `AnimationClip` gains an optional `cameras`, so one clip can contain model bones, morphs, materials, and multiple named camera tracks.
For example, the following JSON moves the model's root and the scene camera in sync:

```json
{
  "id": "example:scene",
  "duration_seconds": 2,
  "bones": [{"bone": "root", "keyframes": [
    {"time": 0, "transform": {"translate": [0, 0, 0]}},
    {"time": 2, "transform": {"translate": [2, 0, 0]}}
  ]}],
  "cameras": [{"camera": "main_shot", "tracks": [
    {"channel": "x", "keyframes": [
      {"time": 0, "value": 100}, {"time": 2, "value": 102}
    ]},
    {"channel": "zoom", "keyframes": [
      {"time": 0, "value": 1}, {"time": 2, "value": 1.5}
    ]}
  ]}]
}
```

After reading it as `scene` through `AnimationClip.CODEC`, bind the targets to the same timeline:

```java
var timeline = new AnimationTimeline();
var modelPlayer = new AnimationPlayer<AnimationClip>(modelInstance);
modelInstance.setPoseDriver(modelPlayer);
modelPlayer.follow(ClipSampler.INSTANCE, scene, timeline);
camera.animation().follow(scene, "main_shot", timeline);
timeline.play(scene.durationSeconds(), true);
var driver = MinecraftAnimationTimelines.drive(timeline);

timeline.pause();
timeline.seek(1f);
timeline.setSpeed(2f);
timeline.resume();

// Release the driver registration and the target bindings when the scene ends.
driver.close();
modelPlayer.stop();
camera.animation().stop();
timeline.stop();
```

`follow` only binds and samples; it does not restart the timeline and does not advance it on its own. Neither the model's existing `animate` calls nor the camera's automatic tick
advance the shared clock a second time. `MinecraftAnimationTimelines.drive` advances it once per game tick and stops while the world is paused;
registering the same timeline twice raises an error. A pure Java host can skip that registration and call `timeline.tick(dt)` once per tick itself.
The control thread hands the clock to the render thread through an immutable volatile snapshot.

The camera's `animation().pause/seek/setSpeed/resume` and the model player's controls of the same names act on the timeline they are bound to,
so when the clock is shared they also control the other targets. `stop()`, direct control, and closing a camera release only that target; the external timeline keeps working.
A model reload/rebind only swaps the output target and keeps the shared clock progress.

The logical camera name (such as `main_shot`) is independent of the output view ID, so the same track can be bound to multiple camera tracks.
You can also bind a standalone `CameraAnimationClip` and existing model samplers such as glTF/VMD to the same timeline.
Clips of different lengths hold their endpoint within the shared period according to their own length, and looping happens uniformly at the timeline's duration.

An existing model player can also act as the clock owner while playing standalone:

```java
modelPlayer.play(ClipSampler.INSTANCE, scene, true);
camera.animation().follow(scene, "main_shot", modelPlayer.timeline());
```

Here the model host's tick drives the clock and the camera only follows; that clock can no longer be registered with `MinecraftAnimationTimelines`
or have `timeline.tick` called on it directly. The model's `stop/play` stops the old owned clock; use an external shared timeline when you need to manage the scene lifecycle independently.

The model pose outlet of `ClipSampler` / FSM still applies only model channels; camera channels are applied through explicit target bindings.
The current shared timeline does not take over the FSM's layer weights or state switching.

## JSON

Read and written through `CameraAnimationClip.CODEC`; it uses the same easing names as model animation.

```json
{
  "id": "example:camera_pan",
  "duration_seconds": 3,
  "tracks": [
    {"channel": "yaw", "keyframes": [
      {"time": 0, "value": 0, "easing": "ease_in_out_cubic"},
      {"time": 3, "value": 90}
    ]},
    {"channel": "zoom", "keyframes": [
      {"time": 0, "value": 1},
      {"time": 3, "value": 2}
    ]}
  ]
}
```

Unit tests use `./gradlew :modules:modelling:modelUnitTest --offline`.
Run the real camera track smoke test with a copied test world:

```bash
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=build/camera-animation-smoke \
  -PkasugaQuickPlayWorld=world-view-test -PkasugaTestCameraAnimation=true --offline
```

The test directory must already contain `saves/world-view-test`. On success it prints `CAMERA_ANIMATION_SMOKE_PASS`
and writes the result to `debug/camera-animation.json` in that client directory.
