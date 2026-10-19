# Offscreen Output and Multi-Camera Rendering

This page maintains the interface, startup and operation reference; for isolation, data flow and synchronization principles see [the explanation page](../explanation/offline-rendering-and-multi-cam.md).
The Player, Free and Fixed categories and their creation entry points are in [the camera API](../reference/cameras.md). The configuration of the standalone-world camera views below applies to Free/Fixed;
Player shares the vanilla final main frame and the player's current configuration.
The performance and client acceptance sections below keep the historical records from that time; they do not mean this documentation migration re-ran the corresponding tests.

This page corresponds to Minecraft 1.21.1 / NeoForge 21.1.203; the compatibility path actually verified Sodium
0.6.13 and Iris 1.8.12. All camera views render in order inside one client process and the main OpenGL context;
multi-window uses auxiliary contexts with a shared texture to display finished frames. Camera views each own world caches, a terrain renderer, camera, projection, color/depth targets
and an Iris pipeline, while the main view still takes the vanilla rendering path.

Offscreen output delivers a GPU FBO and the game tick keeps running. The headless startup entry points support a hidden desktop
context and an EGL pbuffer context on Linux without X11/Wayland. A full
Minecraft client and an OpenGL driver are still required; there is currently no fixed-timestep offline video exporter and no built-in
video encoder, so a caller can send frames to its own compositing, encoding and transport modules.

## Dual-Camera DEBUG Startup

```sh
./gradlew :modules:modelling:runClient -PkasugaDebugDualCamera=true

# Optional: enter an existing singleplayer world; omit it to enter normally from the menu.
./gradlew :modules:modelling:runClient \
  -PkasugaDebugDualCamera=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/test-client
```

The equivalent JVM switch is `-Dkasuga.debugDualCamera=true`. It is off by default and creates no DEBUG
camera view or preview target. On entering a world it creates `debug:first-person` and `debug:third-person`,
with first person on the left and third person on the right, and the window title states the left-right order. The two have separate
sessions, FOV follows the user setting, size follows the window, render distance 8 chunks, Fancy, shader pack off.
Third person sits 4 blocks back along the view direction and checks for wall occlusion; first person hides the player's own entity.

The DEBUG preview is the world image and contains no first-person hand or HUD. Opening a menu/inventory pauses the side-by-side
compositing and displays the original screen normally. Leaving the world releases the camera views and they are rebuilt automatically the next time you enter. The main view still
runs, and DEBUG compositing happens before the final screen output; while it is active, subscribing to the main view also captures the side-by-side image.

The automated check is for an isolated world copy only:

```sh
./gradlew :modules:modelling:runClient \
  -PkasugaDebugDualCamera=true \
  -PkasugaDebugDualCameraCaptureFrames=180 \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/test-client
```

After previewing 180 frames it writes `debug/dual-camera.png`, prints `DUAL_CAMERA_DEBUG_PASS`
and closes the client. The plain DEBUG switch keeps running and does not exit automatically.

## Multi-Window

```sh
./gradlew :modules:modelling:runClient -PkasugaDebugMultiWindow=true
```

After entering a world, first and third person each get their own window and the MC main window keeps displaying. It works with
`kasugaQuickPlayWorld` / `kasugaClientDirectory`; for testing,
`-PkasugaDebugDualCameraCaptureFrames=120` prints `MULTI_WINDOW_DEBUG_PASS` and exits after both paths have presented 120 frames. Off by default. DEBUG temporarily disables pause-on-lost-focus and
restores the previous setting afterwards; an ordinary API caller can set `mc.options.pauseOnLostFocus` itself.

```java
import lib.kasuga.rendering.output.gl.FramePreviewWindow;
import lib.kasuga.rendering.output.mc.MinecraftFrameWindows;

// Owns both the camera and the window: closing either this view's native window releases the camera.
var windowCamera = MinecraftFrameWindows.createCamera("monitor:wide", () ->
        new WorldCameraView(100, 80, 100, 0, 20, 0, 70, 640, 360), settings,
        new FramePreviewWindow.Options("Wide camera", 640, 360));
windowCamera.pause();
windowCamera.resume();
windowCamera.resize(1280, 720);
windowCamera.close();

// Owns only the window and a subscription: closing this window does not close the existing camera.
var mirror = MinecraftFrameWindows.open("capture:wide",
        new FramePreviewWindow.Options("Existing camera", 640, 360));
var mainMonitor = MinecraftFrameWindows.open(MinecraftFrameOutputs.MAIN_VIEW,
        new FramePreviewWindow.Options("Final MC + GUI", 640, 360));
mirror.close();
mainMonitor.close();
```

All operations are called on the MC render thread. The camera size follows the window framebuffer/DPI; while minimized the
last valid size is kept. Close events are handled every frame, and a paused camera view can also be closed. A window that only subscribed keeps the last frame after the
source camera view stops publishing, and the caller is responsible for closing it. Exiting MC releases the windows together and
only then releases the main context.

An auxiliary window uses a GL context with a shared texture, its own read FBO and a bidirectional GPU fence.
Presenting only copies the finished frame and adds no world rendering; a camera view captures once per frame and multiple
subscribers share the read-only output FBO. Each auxiliary window still has its own context-local read FBO,
which is only the read entry point onto the shared texture and does not allocate a separate output color texture.
The main context may overwrite the next frame only after the texture read completes; no CPU `glFinish` is used. Every present
restores MC's current context / GL capabilities. Standalone windows can be stretched, and the image keeps its aspect ratio
with black bars. GLFW initialization, the global error callback and termination are still managed by MC.

This is not multiple clients: player input, ticks and the main window event loop still belong to one MC.
Linux Null/EGL mode provides no native window, and calling the multi-window API reports an explicit error.

## Headless MC Launcher

The repository provides `scripts/run-headless-client.sh` and the Gradle `runClientHeadless` entry point.
By default it uses the standalone `modules/modelling/run/headless-client` directory and does not reuse the everyday client.
A single-player Quick Play world must already exist under that directory's `saves/`; an existing directory can also be specified:

```sh
./scripts/run-headless-client.sh \
  -PkasugaClientDirectory=/absolute/path/to/headless-client \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaHeadlessFps=60

# Or connect straight to a server; the client session's authentication requirements still apply.
./scripts/run-headless-client.sh -PkasugaHeadlessServer=localhost:25565

# Gradle direct entry point for a desktop machine.
./gradlew :modules:modelling:runClientHeadless -PkasugaHeadlessBackend=hidden \
  -PkasugaQuickPlayWorld=world-view-test
```

This is the startup entry point for this repository's NeoForge development client; it keeps the Gradle mod/classpath configuration and
does not handle account login or video encoding. Without a world/server specified it starts into an offscreen menu for a caller's
client extension to control; it does not create a new world automatically.

| Mode | GL context | Display service |
| --- | --- | --- |
| `hidden`, default on macOS/Windows | a GLFW window hidden from creation | needs a desktop display environment |
| `egl`, default on Linux | GLFW Null platform + EGL pbuffer | needs no X11/Wayland/Xvfb |

Before startup, `prepareHeadlessClient` sets `earlyWindowControl = false` in the chosen client directory's
`config/fml.toml`, so that NeoForge does not display a splash before the game mixins take effect.
This setting is kept in that directory. MC does not go fullscreen, the context is not displayed, vsync is not enabled and
pause-on-lost-focus is disabled; the render frame rate is controlled separately by `kasugaHeadlessFps` (default 60). The first-time accessibility
introduction screen is skipped, while game ticks, world loading, resource packs and the regular loading screens still run.

Linux needs Java 21, a C compiler, CMake, `patch`, `curl` and an EGL driver that supports desktop OpenGL.
For example, a Debian/Ubuntu Mesa environment can install `libegl1`,
`libgl1-mesa-dri`, `build-essential`, `cmake`, `patch`, `curl`. On first launch the script
automatically downloads the pinned GLFW 3.4 source, verifies its SHA-256, applies a small in-repository pbuffer patch and
compiles a shared library with X11/Wayland disabled. The build output lands in
`modules/modelling/build/headless-glfw/cmake/src/libglfw.so` and is reused on later launches.

```sh
# Linux: the script unsets DISPLAY / WAYLAND_DISPLAY and defaults EGL_PLATFORM=surfaceless.
./scripts/run-headless-client.sh -PkasugaQuickPlayWorld=world-view-test

# Pre-build separately, or deploy a precompiled library for the same architecture.
./scripts/build-headless-glfw.sh
KASUGA_HEADLESS_GLFW_LIBRARY=/opt/kasuga/libglfw.so \
  ./scripts/run-headless-client.sh -PkasugaHeadlessServer=localhost:25565

# Force software rendering; only for functional verification on a machine with no GPU.
LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null \
  ./scripts/run-headless-client.sh -PkasugaQuickPlayWorld=world-view-test
```

The upstream GLFW 3.4 plain EGL path only selects a window surface; the patch selects a pbuffer config and calls `eglCreatePbufferSurface` on the Null platform only. Substituting the upstream GLFW library for
this one is not equivalent. If you start Gradle's `egl` mode directly, you must unset the display environment variables yourself,
set `EGL_PLATFORM=surfaceless` and pass `-PkasugaHeadlessGlfwLibrary=/absolute/libglfw.so`.
The JVM equivalents are `-Dkasuga.headless=egl` and `-Dorg.lwjgl.glfw.libname=...`, and
NeoForge's early window configuration must still be turned off before startup.

No display service still means a GL driver is required. Hardware EGL depends on driver support and render node permissions;
multi-camera shader packs and large resource packs still consume the corresponding CPU/GPU memory. A hidden window or a pbuffer does not
eliminate world rendering cost, and software rendering does not stand for production hardware performance.

## Verification

The real client uses a world copy and an isolated directory to check shader/shadow sessions, pixel content of
different material atlases, chunks and entities beyond 1024 blocks, restoration of metadata defaults, main RGB consistency,
fog and state restoration, resize, pause/resume, callback closure and failure release.

```sh
./gradlew :modules:modelling:modelUnitTest :modules:modelling:renderGlTest
./gradlew :modules:modelling:runClient \
  -PkasugaTestWorldViews=true -PkasugaTestCameraIris=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/test-client

# The path with Iris/Sodium not installed.
./gradlew :modules:modelling:runClient \
  -PkasugaRendererMods=false -PkasugaTestWorldViews=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/vanilla-test-client
```

The client artifacts are `debug/world-views/report.json` and PNGs, with the success marker
`WORLD_VIEWS_SMOKE_PASS`. The standalone GL regression covers final blit RGB/orientation/alpha, resize,
abnormal state recovery and resource release, and needs no full MC launch.

## JFR Testing and Performance Optimization

```sh
./gradlew :modules:modelling:runClient \
  -PkasugaTestCameraPerformance=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/performance-test-client
python3 scripts/analyze-camera-jfr.py \
  /path/to/cameras-4.jfr --output /path/to/analysis.json
```

The real MC runs 0, 1, 2 and 4 additional camera views in turn, then re-measures 0 at the end; each group warms up for 8 seconds and JFR
`profile` records for 12 seconds. The additional camera views are 1280×720, 4 chunks, Fancy, no shader pack, and the main view
uses the test shader pack, vsync is off and an empty consumer measures delivery. The test exits by itself; the results are
the per-group JFR files and `report.json` in `debug/camera-performance/`; do not enable DEBUG
or another benchmark at the same time. Each run overwrites that directory, so copy the records before comparing.

2026-10-02, Apple M3 Max, main window 1708×960, first round of comparison in the real client:

| Metric for 4 additional camera views | Before optimization | After optimization |
| --- | ---: | ---: |
| Host frame mean CPU render | 2.913 ms | 2.953 ms |
| Host frame P95 | 3.305 ms | 3.278 ms |
| Host frame P99 | 3.870 ms | 3.500 ms |
| Sum of mean camera pass durations | 1.262 ms | 0.945 ms |
| Render thread allocation rate | 207.31 MB/s | 156.70 MB/s |
| Cloud path allocation samples | 131 | 12 |

Two optimizations were implemented from the JFR data:

1. The Iris/Sodium `hadShadersOn` flag used to be global, so with the main view's shader pack on and the camera views' off it flipped back and forth,
   which repeatedly invalidated the cloud geometry cache. It is now kept per CloudRenderer instance and switched/restored during drawing;
   real-client tests covered the actual Mixin path.
2. Gaussian sampling for fog/sky color produced a temporary Vec3 on every loop iteration. The camera views use the same kernel,
   sampling and addition order, accumulate in scalars and create the final Vec3 at the end. Comparing 200 fixed random positive/negative and fractional
   coordinates against vanilla, the double bits of all three components are identical, and the sampling order of 216 samples matches too.
   The main view keeps the vanilla path.

The observed allocation rate dropped by about 24% and the sum of mean camera pass durations by about 25%, while the whole-frame mean stayed flat,
so there are no grounds for claiming an FPS increase. The first 0-camera-view mean was 2.900 ms and the closing re-measurement 1.601 ms, which shows
clear warm-up/world activity/environment variance. The two rounds are not a statistical A/B with the world state locked, and they cover neither
complex commercial shader packs, large entity scenes nor other GPUs.

Host frame timing uses `RenderFrameEvent.Pre/Post` and does not wait for the GPU to finish, so it does not represent full
swap/present, encoding or end-to-end latency. `kasuga.CameraRenderPass` records world + Iris
final composition and excludes the output blit/consumers; `kasuga.CameraChunkSnapshot` records
server snapshot build/encoding. The allocation rate uses the `jdk.ThreadAllocationStatistics` delta divided by time;
do not accumulate `ObjectAllocationSample.weight`, because the first sample may carry allocations from before recording started.
The number of allocation samples is a pointer for locating hotspots, not an exact byte count.

The raw records are in `../modules/modelling/build/camera-performance-baseline` and
`modules/modelling/build/camera-performance-optimized/`, and are local build artifacts.
The remaining hotspots are mostly biome/noise reads, terrain/entity frustum filtering and entity vertex building. Later work
should re-measure in complex scenes; the full entity state sent every 5 ticks could be deduplicated further by value, but the sync
semantics for returning to default values must be preserved.

## Player Motion Synchronization Fix

A camera view subscription sends entity state every 5 ticks, whereas the vanilla teleport handler interpolates over 3 ticks,
which produces a two-tick stutter. Distant camera view entities were changed to interpolate over 5 ticks. For entities the main client already
tracks, the camera view copies their position, previous-tick position, rotation and limb
animation history into their own copy before each render; a player's client-side predicted movement no longer takes a round trip through the server subscription.
The separate Entity/ClientLevel is kept, and FOV/render resource isolation is unaffected.

A real-client regression has the player move/turn/change walking animation continuously, and at partial tick 0, 0.25, 0.75 and 1 of 120 output frames
checks that the copy's position interpolation, rotation history and animation phase agree:

```sh
./gradlew :modules:modelling:runClient -PkasugaTestCameraMotion=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/test-client
```

This test deliberately modifies the player position inside the isolated world and restores it at the end. The success marker is
`CAMERA_MOTION_SMOKE_PASS`, and the report is `debug/camera-motion.json`.


## Final Code Re-Measurement

After the player motion fix, vanilla distant terrain positioning and the RGBA readback interface were added, the same JFR
scenario was run again (empty consumer, excluding the synchronous CPU readback cost):

| Additional camera views | Mean CPU render | P95 | P99 |
| --- | ---: | ---: | ---: |
| 0, first | 2.198 ms | 4.053 ms | 5.440 ms |
| 1 | 2.033 ms | 2.543 ms | 2.852 ms |
| 2 | 2.125 ms | 2.534 ms | 2.952 ms |
| 4 | 2.846 ms | 3.267 ms | 3.878 ms |
| 0, closing re-measurement | 1.253 ms | 1.479 ms | 1.657 ms |

With 4 camera views the render thread allocation rate is **138.14 MB/s**, the sum of mean camera pass durations is **1.040 ms**
and the cloud path allocation samples are 14. Against the original 207.31 MB/s record, the observed
allocation rate dropped by about 33%, while whole-frame time is still at a similar level; the warm-up and unlocked-world limitations above still
apply. "1 camera view being faster than the first 0-camera-view run" must not be read as additional camera views speeding anything up.
The records and analysis are kept in `../modules/modelling/build/camera-performance-final`.

Verification summary: 625 JVM cases, 0 failures/errors, 1 skipped; 9 standalone GL check groups
passed, including RGBA orientation, alpha and PBO/pack state restoration; the vanilla and Iris client multi-camera
checks, the dual-camera DEBUG actual output, the 120-frame player motion regression and the final JFR test passed.
The integrated server's chunk/entity subscription path was actually run; a standalone dedicated server and arbitrary
third-party shader pack/resource pack combinations are outside this verification's coverage.

## Verification of Multi-Window and Headless Startup, and Additional Optimizations

After the new features were completed, `renderingReleaseCheck` passed; the JVM checks are **627 cases,
0 failures/errors, 1 skipped**. The standalone GL regression is **10 groups**, adding shared window texture
presenting, bidirectional fences, current context/capabilities restoration, resize and independent window closing,
plus Sodium-type RGBA native blit keeping RGB and forcing output alpha=1.

| Actual check | Result |
| --- | --- |
| macOS MC + Iris/Sodium, multi-camera windows and mirroring an existing camera view | passed; closing a paused camera view, DPI resize, other camera views continuing after one window closes |
| Multi-window first/third person DEBUG startup | `MULTI_WINDOW_DEBUG_PASS` |
| macOS `run-headless-client.sh` hidden mode | `HEADLESS_CLIENT_SMOKE_PASS`, main frame plus two camera view PNGs |
| Debian 12 aarch64, no DISPLAY / WAYLAND_DISPLAY, GLFW Null + EGL | `HEADLESS_CLIENT_SMOKE_PASS`, main frame plus two camera view PNGs |

The Linux verification used Mesa 22.3.6's llvmpipe / OpenGL 4.5 and **did not start Xvfb**.
It checked that the native window was never visible, that the context came back to its place, that there was no GL error, and that RGBA was non-empty with an opaque
alpha. The main frame PNG includes the hand/HUD, and the camera view PNGs are their respective world views; JFR also recorded
80 render passes per camera view. What ran was the JVM / classpath exported from that NeoForge client configuration;
the whole Gradle project was not rebuilt inside the VM. This proves that the client rendering path can run without a display
service; it does not prove NVIDIA/AMD hardware EGL, Windows or all third-party shader pack combinations.

The test VM temporarily used 12 GiB of memory and a 6.5 GiB Java heap and loaded this machine's large test models,
including three 16384×8192 atlases; this is not a minimum runtime memory requirement. The artifacts are kept in the ignored
`modules/modelling/build/headless-linux-smoke/debug` and `headless-linux-jfr`.

Two startup/background performance issues were fixed as well:

- GLFW Null's `glfwWaitEventsTimeout` is an empty function, so MC's frame limiting loop busy-waits.
  EGL mode was changed to `LockSupport.parkNanos`, and early wake-ups are still handled by the original frame deadline
  loop. For the same 60 FPS offscreen menu, 4 seconds of JFR each: before the optimization, 26 of 74 render thread
  execution samples were in `limitDisplayFPS`; after, 0 of 50. This is short-duration CPU sampling evidence and cannot
  be taken as a world rendering FPS gain or an exact CPU percentage.
- The PBR cache key used to allocate a whole-image `int[]` and an equally large byte buffer for scratch, adding
  `8 × width × height` bytes of scratch. It now chunks by 4096 pixels with 32 KiB of scratch, and the SHA-256
  descriptor, size and big-endian ARGB order are unchanged. Tests cover chunk
  boundaries, different raster formats and subimages, and old on-disk caches stay valid. The actual memory demand of the source image, the bake
  result and the GPU atlas is still there.

The earlier 0/1/2/4 camera view JFR tables do not include the new window presenting cost or the synchronous CPU readback cost, so their
durations cannot be applied directly to multi-window or software EGL deployments.



> This is the hands-on page for the offscreen and multi-camera topic. For the output and camera contracts see [multi-camera output contracts](../reference/multi-camera-output.md), and for the principles see [offscreen output and multi-camera principles](../explanation/offline-rendering-and-multi-cam.md).
