# Player / Free / Fixed Camera

The generic data lives in `lib.kasuga.rendering.output.camera`, and the Minecraft integration in
`lib.kasuga.rendering.output.mc`. For the mechanics see [Multi-camera and output](../explanation/offline-rendering-and-multi-cam.md).
All creation, control, and close operations run on the render thread.

## Types and creation

| `CameraType` | Pose and configuration source | Control |
| --- | --- | --- |
| `PLAYER` | Native player camera, first/third person, current FOV/roll, resources, and shaders | Native player controls; the handle manages the output lifecycle |
| `FREE` | Standalone world position, rotation, FOV, size, and `CameraRenderSettings` | Direct control, a provider, or animation tracks |
| `FIXED` | Interpolated target position/orientation and offsets; standalone projection and `CameraRenderSettings` | Target following, offsets, orientation mode, and projection control |

`FIXED` is a follow camera bound to a target. For a stationary camera view, use a `FREE` with a fixed pose.
`type()` is independent of the `READY / PAUSED / FAILED / CLOSED` lifecycle of `CameraState`.

```java
CameraHandle player = MinecraftCameras.createPlayer(frame -> consume(frame));
CameraHandle free = MinecraftCameras.createFree("example:free",
        new WorldCameraView(0, 80, 0, 90, 20, 0, 60, 1280, 720),
        CameraRenderSettings.defaults(), frame -> consume(frame));
CameraHandle fixed = MinecraftCameras.createFixed("example:follow", entity,
        new CameraProjection(60, 1280, 720),
        CameraRenderSettings.defaults(), frame -> consume(frame));
```

`createFree` accepts either a `WorldCameraView` or a `Supplier<WorldCameraView>`, each with a default overload that omits the settings.
The original `MinecraftCameras.create(...)` is kept as a compatibility entry point for Free Camera.

The `createFixed` signatures are as follows; all of them return `CameraHandle`:

```java
createFixed(String viewId, Entity entity, CameraProjection projection,
            CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer)
createFixed(String viewId, Supplier<? extends Entity> entity, CameraProjection projection,
            CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer)
createFixed(String viewId, CameraTarget target, CameraProjection projection,
            CameraRenderSettings settings, Consumer<OutputFrame<FrameTexture>> consumer)
createFixed(String viewId, CameraTarget target, CameraProjection projection,
            CameraFollowSettings follow, CameraRenderSettings settings,
            Consumer<OutputFrame<FrameTexture>> consumer)
```

A Free/Fixed view ID must be non-empty and unique, and must not be `minecraft:main`. Player uses `minecraft:main`,
and only one Player handle with a lifecycle is allowed; multiple consumers share the main frame through
[`MinecraftFrameOutputs.open(MIRROR, ...)`](frame-output.md).
Player output includes the native world, hands, post-processing, and HUD/GUI, and does not render the world again. Pausing/closing only cancels this handle's subscription;
the player's main window and other main-frame consumers keep running. Free/Fixed outputs the world frame.

## Follow data and entity integration

```java
CameraTarget target = MinecraftCameraTargets.entity(() -> Minecraft.getInstance().player);
fixed.follow(target); // switches the target, keeping the projection and offsets
fixed.updateFollowSettings(new CameraFollowSettings(
        0, 2, 4,       // world-coordinate offset, not rotated with the entity orientation
        0, 10, 0,      // yaw / pitch / roll, in degrees
        true));        // follow the orientation; the angle fields act as additional offsets
fixed.setVerticalFov(55);
fixed.zoom(2);
fixed.setProjection(new CameraProjection(50, 1920, 1080));
```

`CameraTarget` is the generic functional interface `CameraTarget.Pose sample(float partialTick)`;
`Pose(double x, double y, double z, float yaw, float pitch, float roll)` contains no Entity or model format type.
A custom host can bind any object. The Minecraft adapter uses the entity's interpolated eye position and look direction by default.
Passing an entity object keeps that object bound; passing a supplier can pick up a new object after a respawn or dimension change.

In the default `CameraFollowSettings.defaults()`, all offsets are 0 and `followRotation=true`.
When set to `false`, the position is still followed, and yaw/pitch/roll act as an absolute orientation in the world.
FOV, zoom, projection, and render configuration updates all preserve the target binding. This interface has no automatic wall avoidance or collision push-out logic.

A target that returns null is temporarily unavailable. When the entity is null, has been removed, or does not belong to the current source world, the Minecraft adapter
skips that view, pauses its chunk subscriptions, and keeps the handle and output; it resumes automatically once the target becomes available.
While waiting, `CameraState` stays `READY` and that view's frame counter does not increase; `pose()` throws `IllegalStateException`
and does not fail the handle. FOV/projection can also be set before the target first appears. A runtime exception thrown by the target fails the camera and releases its resources.

`followTarget()` returns `Optional<CameraTarget>`; `followSettings()` returns the current immutable configuration.
Calling a follow write or `followSettings()` on Player/Free throws `UnsupportedOperationException`.
Fixed does not accept `updatePose / moveTo / moveBy / rotateTo / rotateBy / animation()`; control it through the follow configuration.

## Free control and render configuration

For the Free position, rotation, and animation interfaces see [Camera animation](camera-animation.md). Player does not accept standalone pose, projection,
follow, animation, and render configuration writes; the corresponding operations throw `UnsupportedOperationException`.

```java
free.setProjection(new CameraProjection(65, 1280, 720));
CameraRenderSettings settings = free.renderSettings().orElseThrow();
free.updateRenderSettings(new CameraRenderSettings(12,
        CameraRenderSettings.Quality.FANCY,
        CameraRenderSettings.Shader.pack(shaderPack)));
```

`renderSettings()` returns empty for Player and the current configuration for Minecraft Free/Fixed.
`updateRenderSettings` rebuilds that camera view's renderer/assets/Iris session on the next render frame where the target is available, preserving
pose/follow, animation, view ID, and output subscription. The current frame and its callback keep using the old session configuration, so requesting an update from a callback is safe.
The same configuration is not rebuilt; while rendering is paused the change takes effect later. A shader pack requires Iris, and an initialization failure follows the camera failure/release contract.
For the fields and resource-pack rules see [Multi-camera configuration](multi-camera-output.md).

`CameraProjection` requires a vertical FOV within `(0, 180)` degrees and positive integer width and height, and provides `from(view)`,
`withVerticalFov`, `zoom`, and `apply(view)`. Modifying the projection of a Free camera stops its own animation; updating the render configuration does not stop the animation.
A window-owned Free Camera uses the actual framebuffer/DPI size.

## Host extension and lifecycle

A pure Java host uses `CameraSource.Player(ViewProvider)`, `CameraSource.Free(Supplier<WorldCameraView>)`,
or `CameraSource.Fixed(CameraTarget, CameraProjection, CameraFollowSettings)` to create the generic data, and hands it to
`OwnedCamera(String viewId, CameraSource source, Producer producer, Output output, Runnable checkThread, Runnable onClosed)`.
The original constructor that takes a pose supplier still creates a Free Camera.
Player's `ViewProvider.sample(float partialTick)` provides the native camera snapshot; the host implements the actual output producer.

`sampleAvailablePose(partialTick)` returns an Optional, letting the host skip rendering when a fixed target is unavailable;
`samplePose(partialTick)` requires a pose for this frame. Only Free advances the animation tick; target sampling does not advance simulation time.
`Producer.renderSettings/updateRenderSettings` are the host configuration extension points. Pausing, failure, closing, and resource ownership
follow [Multi-camera lifecycle](multi-camera-output.md).

## Verification entry

```sh
./gradlew :modules:modelling:modelUnitTest
./gradlew :modules:modelling:runClientHeadless -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=camera-types -PkasugaTestCameraTypes=true
```

Prepare a copy of the test world in the isolated directory. The client success marker is `CAMERA_TYPES_SMOKE_PASS`, and the report is at
`debug/camera-types.json`; it covers native main-output sharing and pausing, Free projection, entity interpolation, target recovery,
session rebuild after a configuration change from a callback, real world textures, and GL error checks. The JVM tests cover type control boundaries,
double precision, offsets/orientation, projection preserving the target, and release on failure.
