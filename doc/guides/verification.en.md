# Build and Verification Entries

Run from the repository root. The tasks below are listed with the evidence they provide; this does not mean the current documentation pass re-ran client or GPU acceptance.
Model selection, configuration and output paths for a specific topic are in the matching API reference.

## JVM And Release Checks

```sh
./gradlew :modules:modelling:modelUnitTest
./gradlew :modules:formula:test
./gradlew renderingReleaseCheck
```

`modelUnitTest` runs the modelling tests on a plain JVM/JUnit setup, avoiding the NeoForge dedicated-server launcher.
When CMake is configured for the current platform, the Box3D native tests are included; without native build capability the matching tags are excluded.
Local PMX/glTF asset tests also depend on a local fixture, so a skipped result for a missing optional asset cannot count as real-asset verification.

`renderingReleaseCheck` covers Gradle plugin tests, shader tests/assemble, render-core/render-opengl assemble, the modelling JVM tests, contentTesting compilation and the jar/source/javadoc artifacts, plus library assemble.
That task is not the same as running a real Minecraft world or running every standalone GL regression.

## Standalone OpenGL Harness

```sh
./gradlew :modules:modelling:renderGlTest
./gradlew :modules:modelling:renderPreview
./gradlew :modules:modelling:renderBench
```

These tasks start a standalone OpenGL context. Shaders, upload, framebuffer, GPU readback and timer queries run on the current platform; they do not by themselves prove the same results on Minecraft, Sodium/Iris or another platform. For cloud-field scene selection see [Volumetric clouds API](../reference/volumetric-clouds.md).

## Development Client

```sh
./gradlew :modules:modelling:runClient -PkasugaTestModel=mmd
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client \
  -PkasugaQuickPlayWorld=world-view-test
```

A Quick Play save must already exist under `saves/` in the isolated directory; use a dedicated test world or a copy.
`runClientHeadless` is still a full client: hidden by default on desktop platforms, EGL by default on Linux; for driver requirements see
[headless API](offline-rendering.md#无窗口-mc-启动器).

| Content | Reference |
| --- | --- |
| MMD/glTF models, native ragdoll | [model runtime entry points](../reference/modelling/README.md), [configuration/deployment](../reference/mmd-ragdoll.md) |
| Full GUI and pixel-by-pixel checks of the final output | [Frame Output](../reference/frame-output.md#验证入口) |
| Cameras, shared timeline and windows | [Camera Animation](../reference/camera-animation.md), [multi-camera](../reference/multi-camera-output.md) |
| Alpha/OIT submit order and debug buffers | [Alpha/OIT](../reference/render-alpha-passes.md#可视化验收场景contenttesting) |
| Multi-layer sky and a single cloud | [Volumetric clouds](../reference/volumetric-clouds.md#示例与验证) |

## Recording The Verification Scope

A verification record should state the compile/JVM, native physics, standalone GL, real-client and platform/renderer combinations separately.
Reports, screenshots and logs record the run date, hardware, configuration, task and success marker; historical records keep their original dates, and old JFR samples, a successful build or standalone-scene GPU time must not be read as the current game FPS or as an all-platform compatibility conclusion.
