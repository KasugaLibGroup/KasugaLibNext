# 构建与验证入口

从仓库根目录执行。以下列出已有任务和证据用途，不表示本次文档整理重新运行了客户端或 GPU 验收。
主题专用的模型选择、配置和输出路径见相应 API reference。

## JVM 与发布检查

```sh
./gradlew :modules:modelling:modelUnitTest
./gradlew :modules:formula:test
./gradlew renderingReleaseCheck
```

`modelUnitTest` 使用普通 JVM/JUnit 运行 modelling 测试，避开 NeoForge dedicated-server launcher。
配置了本平台 CMake 时包含 Box3D 原生测试；没有原生构建能力时会排除相应标签。
本地 PMX/glTF 资产测试还依赖本机 fixture，可选资产缺失时的 skipped 不能算作真实资产验证。

`renderingReleaseCheck` 包含 Gradle 插件测试、shader 测试/assemble、render-core/render-opengl assemble、
modelling JVM 测试、contentTesting 编译及 jar/source/javadoc 产物，以及 library assemble。
该任务不等于运行真实 Minecraft 世界或执行全部独立 GL 回归。

## 独立 OpenGL harness

```sh
./gradlew :modules:modelling:renderGlTest
./gradlew :modules:modelling:renderPreview
./gradlew :modules:modelling:renderBench
```

这些任务启动独立 OpenGL 上下文。shader、上传、framebuffer、GPU 读回与时间查询在当前平台执行，
不自动证明 Minecraft、Sodium/Iris 或其他平台的相同结果。云场场景选择见 [体积云 API](volumetric-clouds.md)。

## 开发客户端

```sh
./gradlew :modules:modelling:runClient -PkasugaTestModel=mmd
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client \
  -PkasugaQuickPlayWorld=world-view-test
```

Quick Play 的存档需预先存在于隔离目录 `saves/` 下，使用专用测试世界或副本。
`runClientHeadless` 仍是完整客户端：桌面平台默认 hidden，Linux 默认 EGL；驱动要求见
[headless API](offline_rendering_and_multi_cam.md#无窗口-mc-启动器)。

| 内容 | 参考 |
| --- | --- |
| MMD/glTF 模型、原生布娃娃 | [模型运行入口](modelling/README.zh-CN.md)、[配置/部署](mmd-ragdoll.md) |
| 完整 GUI 与最终输出逐像素检查 | [Frame Output](frame-output.md#验证入口) |
| 摄像机、共享时间轴与窗口 | [Camera Animation](camera-animation.md)、[多机位](offline_rendering_and_multi_cam.md) |
| Alpha/OIT 提交顺序与调试缓冲 | [Alpha/OIT](render-alpha-passes.md#可视化验收场景contenttesting) |
| 多层天空与单云 | [体积云](volumetric-clouds.md#示例与验证) |

## 记录验证范围

验证记录应分别说明编译/JVM、原生物理、独立 GL、真实客户端和平台/renderer 组合。
报告、截图和日志记录运行日期、硬件、配置、任务与成功标识；历史记录保留原日期，
不能把旧 JFR 采样、构建成功或独立场景 GPU 时间解释成当前游戏 FPS 或全平台兼容结论。
