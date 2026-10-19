# 离屏输出与多机位渲染

本页维护接口、启动和操作参考；隔离、数据流与同步原理见 [原理文档](../explanation/offline-rendering-and-multi-cam.md)。
Player、Free、Fixed 分类与创建入口见 [相机 API](../reference/cameras.md)。下文独立世界机位的配置适用于 Free/Fixed；
Player 共享原生最终主画面和玩家当前配置。
下文性能和客户端验收段落保留当时的历史记录，不表示此次文档迁移重新进行了对应测试。

本文对应 Minecraft 1.21.1 / NeoForge 21.1.203；兼容路径实际验证了 Sodium
0.6.13 和 Iris 1.8.12。所有机位在同一个客户端进程、主 OpenGL context
内顺序渲染；多窗口使用共享纹理的辅助 context 展示已完成的画面。机位分别拥有世界缓存、地形渲染器、相机、投影、颜色/深度目标
和 Iris pipeline，主视角仍走原生渲染路径。

离屏输出交付 GPU FBO，游戏 tick 继续运行。无窗口启动入口支持桌面隐藏
context，以及 Linux 无 X11/Wayland 的 EGL pbuffer context。仍需运行完整
Minecraft 客户端及 OpenGL 驱动；目前没有固定时间步离线视频导出器或内置
视频编码器，调用方可以把画面送往自己的合成、编码和传输模块。

## 双机位 DEBUG 启动

```sh
./gradlew :modules:modelling:runClient -PkasugaDebugDualCamera=true

# 可选：进入指定的已有单人世界；省略则从菜单正常进入。
./gradlew :modules:modelling:runClient \
  -PkasugaDebugDualCamera=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/test-client
```

等价的 JVM 开关为 `-Dkasuga.debugDualCamera=true`。默认关闭，不创建 DEBUG
机位或预览目标。进入世界后创建 `debug:first-person` 和 `debug:third-person`，
左侧为第一人称，右侧为第三人称，窗口标题标明左右顺序。两者各有独立
session，FOV 跟随用户设置，尺寸跟随窗口，视距 8 chunks、Fancy、光影关闭。
第三人称沿视线向后 4 blocks，并检查墙体遮挡；第一人称隐藏玩家自身实体。

DEBUG 预览为世界画面，不含第一人称手部或 HUD。打开菜单/背包时暂停并排
合成，正常显示原有界面。退出世界释放机位，下次进入自动重建。主视角仍
执行，DEBUG 合成发生在最终屏幕输出前；此时订阅主视角捕获的也是并排画面。

自动检查只用于隔离的世界副本：

```sh
./gradlew :modules:modelling:runClient \
  -PkasugaDebugDualCamera=true \
  -PkasugaDebugDualCameraCaptureFrames=180 \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/test-client
```

预览 180 帧后写入 `debug/dual-camera.png`、打印 `DUAL_CAMERA_DEBUG_PASS`
并关闭客户端。普通 DEBUG 开关持续运行，不自动退出。

## 多窗口

```sh
./gradlew :modules:modelling:runClient -PkasugaDebugMultiWindow=true
```

进入世界后，第一和第三人称各有一个独立窗口，MC 主窗口继续显示。可配合
`kasugaQuickPlayWorld` / `kasugaClientDirectory`；测试用
`-PkasugaDebugDualCameraCaptureFrames=120` 在两路均呈现 120 帧后打印
`MULTI_WINDOW_DEBUG_PASS` 并退出。默认不开启。DEBUG 会临时禁用失焦暂停，
结束后恢复原设置；普通 API 调用方可自行设置 `mc.options.pauseOnLostFocus`。

```java
import lib.kasuga.rendering.output.gl.FramePreviewWindow;
import lib.kasuga.rendering.output.mc.MinecraftFrameWindows;

// 同时拥有相机和窗口：关闭任一该机位的原生窗口会释放这个 camera。
var windowCamera = MinecraftFrameWindows.createCamera("monitor:wide", () ->
        new WorldCameraView(100, 80, 100, 0, 20, 0, 70, 640, 360), settings,
        new FramePreviewWindow.Options("Wide camera", 640, 360));
windowCamera.pause();
windowCamera.resume();
windowCamera.resize(1280, 720);
windowCamera.close();

// 只拥有窗口和订阅：关闭这个窗口不会关闭现有相机。
var mirror = MinecraftFrameWindows.open("capture:wide",
        new FramePreviewWindow.Options("Existing camera", 640, 360));
var mainMonitor = MinecraftFrameWindows.open(MinecraftFrameOutputs.MAIN_VIEW,
        new FramePreviewWindow.Options("Final MC + GUI", 640, 360));
mirror.close();
mainMonitor.close();
```

所有操作在 MC 渲染线程调用。相机尺寸随窗口 framebuffer/DPI 改变；最小化时
保留最近有效尺寸。关闭事件每帧处理，暂停机位也可关闭。单独订阅的窗口在
源机位停止发布后保留最后画面，调用方负责关闭它。退出 MC 会统一释放窗口，
之后才释放主 context。

辅助窗口使用共享纹理的 GL context、自己的 read FBO 和双向 GPU fence。
呈现只复制完成的画面，不增加世界渲染；同一机位每帧只捕获一次，多个
订阅共享只读输出 FBO。每个辅助窗口仍有自己的 context-local read FBO，
它只是挂接共享纹理的读取入口，不额外分配一份输出颜色纹理。
纹理读取完成后主 context 才能覆盖下一帧；不使用 CPU `glFinish`。每次呈现
恢复 MC 的 current context / GL capabilities。独立窗口可拉伸，画面保持比例
并留黑边。GLFW 初始化、全局 error callback 和终止仍由 MC 管理。

这不是多个客户端：玩家输入、tick、主窗口事件循环仍属于同一个 MC。
Linux Null/EGL 模式不提供原生窗口，调用多窗口 API 会明确报错。

## 无窗口 MC 启动器

仓库提供 `scripts/run-headless-client.sh` 和 Gradle `runClientHeadless`。
默认使用独立 `modules/modelling/run/headless-client` 目录，不复用日常客户端。
单人 Quick Play 的世界必须已存在于该目录的 `saves/`，也可指定已有目录：

```sh
./scripts/run-headless-client.sh \
  -PkasugaClientDirectory=/absolute/path/to/headless-client \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaHeadlessFps=60

# 或直接连接服务器，需符合该客户端会话的认证条件。
./scripts/run-headless-client.sh -PkasugaHeadlessServer=localhost:25565

# 桌面机器的 Gradle 直接入口。
./gradlew :modules:modelling:runClientHeadless -PkasugaHeadlessBackend=hidden \
  -PkasugaQuickPlayWorld=world-view-test
```

这是本仓库 NeoForge 开发客户端的启动入口，保留 Gradle 的 mod/classpath 配置，
不负责账号登录或视频编码。未指定世界/服务器时启动到离屏菜单，供调用方的
客户端扩展控制；不是自动创建新世界。

| 模式 | GL context | 显示服务 |
| --- | --- | --- |
| `hidden`，macOS/Windows 默认 | 从创建起隐藏的 GLFW 窗口 | 需要桌面显示环境 |
| `egl`，Linux 默认 | GLFW Null 平台 + EGL pbuffer | 不需要 X11/Wayland/Xvfb |

启动前 `prepareHeadlessClient` 在所选客户端目录的 `config/fml.toml` 设置
`earlyWindowControl = false`，避免 NeoForge 在 game mixin 生效前显示 splash。
该设置会保留在此目录。MC 不进入全屏、context 不显示、不启用 vsync，禁用
失焦暂停；渲染帧率单独由 `kasugaHeadlessFps`（默认 60）控制。首次辅助功能
介绍页被跳过，游戏 tick、世界加载、资源包和常规加载界面仍运行。

Linux 需要 Java 21、C 编译器、CMake、`patch`、`curl`，以及支持桌面 OpenGL
的 EGL 驱动。例如 Debian/Ubuntu 的 Mesa 环境可安装 `libegl1`、
`libgl1-mesa-dri`、`build-essential`、`cmake`、`patch`、`curl`。第一次启动脚本
自动下载固定 GLFW 3.4 源码、校验 SHA-256、应用仓库内的小型 pbuffer patch，
编译关闭 X11/Wayland 的共享库。构建产物位于
`modules/modelling/build/headless-glfw/cmake/src/libglfw.so`，后续启动复用。

```sh
# Linux：脚本会 unset DISPLAY / WAYLAND_DISPLAY，并默认 EGL_PLATFORM=surfaceless。
./scripts/run-headless-client.sh -PkasugaQuickPlayWorld=world-view-test

# 预先单独构建，或部署预编译的同架构库。
./scripts/build-headless-glfw.sh
KASUGA_HEADLESS_GLFW_LIBRARY=/opt/kasuga/libglfw.so \
  ./scripts/run-headless-client.sh -PkasugaHeadlessServer=localhost:25565

# 显式使用软件渲染，仅用于无 GPU 环境的功能验证。
LIBGL_ALWAYS_SOFTWARE=1 ALSOFT_DRIVERS=null \
  ./scripts/run-headless-client.sh -PkasugaQuickPlayWorld=world-view-test
```

底层 GLFW 3.4 的普通 EGL 路径只选择 window surface；patch 仅在 Null 平台
选择 pbuffer config 并调用 `eglCreatePbufferSurface`。使用原版 GLFW 库代替
此库不等价。若直接启动 Gradle 的 `egl` 模式，须自行 unset 显示环境变量、
设置 `EGL_PLATFORM=surfaceless` 并传 `-PkasugaHeadlessGlfwLibrary=/absolute/libglfw.so`。
JVM 等价配置为 `-Dkasuga.headless=egl` 与 `-Dorg.lwjgl.glfw.libname=...`，
NeoForge 的早期窗口配置仍须在启动前关闭。

没有显示服务也仍需 GL 驱动。硬件 EGL 取决于驱动支持和 render node 权限；
多机位光影/大材质包仍消耗对应 CPU/GPU 内存。隐藏窗口或 pbuffer 都不会
消除世界渲染成本，软件渲染也不代表生产硬件性能。

## 验证

真实客户端用世界副本和隔离目录，检查 shader/shadow session、不同材质
atlas 像素、1024 blocks 外的区块与实体、metadata 默认值恢复、主 RGB 一致、
雾与状态恢复、resize、暂停/恢复、回调关闭及故障释放。

```sh
./gradlew :modules:modelling:modelUnitTest :modules:modelling:renderGlTest
./gradlew :modules:modelling:runClient \
  -PkasugaTestWorldViews=true -PkasugaTestCameraIris=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/test-client

# 不安装 Iris/Sodium 的路径。
./gradlew :modules:modelling:runClient \
  -PkasugaRendererMods=false -PkasugaTestWorldViews=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/vanilla-test-client
```

客户端产物为 `debug/world-views/report.json` 和 PNG，成功标志
`WORLD_VIEWS_SMOKE_PASS`。独立 GL 回归覆盖最终 blit RGB/方向/alpha、resize、
异常状态恢复和资源释放，无需启动完整 MC。

## JFR 测试与性能优化

```sh
./gradlew :modules:modelling:runClient \
  -PkasugaTestCameraPerformance=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/performance-test-client
python3 scripts/analyze-camera-jfr.py \
  /path/to/cameras-4.jfr --output /path/to/analysis.json
```

真实 MC 依次跑 0、1、2、4 个额外机位，最后复测 0；每组预热 8 秒，JFR
`profile` 记录 12 秒。额外机位 1280×720、4 chunks、Fancy、无光影，主视角
使用测试光影，关闭 vsync，空消费者统计交付。测试自动退出，结果为
`debug/camera-performance/` 中各组 JFR 和 `report.json`；不要同时开启 DEBUG
或其他基准。每次运行会覆盖该目录，比较前先复制记录。

2026-10-02，Apple M3 Max，主窗口 1708×960，真实客户端初轮对照：

| 4 个额外机位指标 | 优化前 | 优化后 |
| --- | ---: | ---: |
| 宿主帧 CPU 渲染均值 | 2.913 ms | 2.953 ms |
| 宿主帧 P95 | 3.305 ms | 3.278 ms |
| 宿主帧 P99 | 3.870 ms | 3.500 ms |
| camera pass 平均耗时之和 | 1.262 ms | 0.945 ms |
| 渲染线程分配率 | 207.31 MB/s | 156.70 MB/s |
| 云层路径 allocation samples | 131 | 12 |

根据 JFR 实施两项优化：

1. Iris/Sodium `hadShadersOn` 原为全局标志，主视角开光影、机位关光影时反复
   切换，导致云几何缓存频繁失效。改为 CloudRenderer 每实例保留，在绘制中
   切换/恢复，真实客户端测试覆盖了实际 Mixin 路径。
2. 雾/天空色 Gaussian 采样每次循环产生临时 Vec3。机位采用相同 kernel、
   采样和加法顺序，以标量累积后创建最终 Vec3。200 个固定随机正负/小数
   坐标对比原版，三个分量 double bits 完全一致，216 次取样顺序也相同。
   主视角保持原版路径。

观察到分配率降低约 24%，camera pass 均值之和降低约 25%，整帧均值持平，
不能据此宣称 FPS 提升。初次 0 机位均值 2.900 ms、结束复测 1.601 ms，显示
预热/世界活动/环境波动明显。两轮不是锁定世界状态的统计 A/B，也未覆盖
复杂商用光影、大实体场景或其他 GPU。

宿主帧计时为 `RenderFrameEvent.Pre/Post`，未等待 GPU 完成，不代表完整
swap/present、编码或端到端延迟。`kasuga.CameraRenderPass` 记录 world + Iris
final composition，不含输出 blit/消费者；`kasuga.CameraChunkSnapshot` 记录
服务器快照构建/编码。分配率用 `jdk.ThreadAllocationStatistics` 差值/时间；
不要累计 `ObjectAllocationSample.weight`，首次样本可能带录制前分配。
allocation sample 数量是定位依据，不是精确字节数。

原始记录在 `../modules/modelling/build/camera-performance-baseline` 和
`modules/modelling/build/camera-performance-optimized/`，属于本地构建产物。
剩余热点主要是 biome/noise 读取、地形/实体视锥筛选和实体顶点构建。后续
应在复杂场景复测；实体每 5 ticks 的完整状态可进一步按值去重，但要保留
回到默认值时的同步语义。

## 玩家运动同步修正

机位订阅每 5 ticks 发送实体状态，而原版 teleport handler 用 3 ticks 插值，
会出现两 ticks 停顿。远处机位实体改为按 5 ticks 插值。对于主客户端已经
追踪的实体，机位在每次渲染前复制其位置、上一 tick 位置、旋转及 limb
animation 历史到独立副本；玩家的客户端预测移动不再绕服务端订阅一圈。
保留独立 Entity/ClientLevel，FOV/渲染资源隔离不受影响。

真实客户端回归让玩家连续移动/转向/改变步行动画，在 120 个输出帧的
partial tick 0、0.25、0.75、1 检查副本位置插值、旋转历史与动画相位一致：

```sh
./gradlew :modules:modelling:runClient -PkasugaTestCameraMotion=true \
  -PkasugaQuickPlayWorld=world-view-test \
  -PkasugaClientDirectory=/absolute/path/to/test-client
```

此测试主动修改隔离世界内的玩家位置，结束时恢复。成功标志
`CAMERA_MOTION_SMOKE_PASS`，报告为 `debug/camera-motion.json`。


## 最终代码复测

加入玩家运动修正、原版远处地形定位与 RGBA 读回接口后，再跑相同的 JFR
场景（空消费者，不包含同步 CPU 读回成本）：

| 额外机位数 | CPU 渲染均值 | P95 | P99 |
| --- | ---: | ---: | ---: |
| 0，初次 | 2.198 ms | 4.053 ms | 5.440 ms |
| 1 | 2.033 ms | 2.543 ms | 2.852 ms |
| 2 | 2.125 ms | 2.534 ms | 2.952 ms |
| 4 | 2.846 ms | 3.267 ms | 3.878 ms |
| 0，结束复测 | 1.253 ms | 1.479 ms | 1.657 ms |

4 机位渲染线程分配率为 **138.14 MB/s**，camera pass 均值之和为 **1.040 ms**，
云层路径 allocation samples 为 14。相对最初 207.31 MB/s 的记录，观察到约
33% 的分配率下降，整帧耗时仍属接近的水平；上述预热与非锁定世界限制依然
适用。不能把“1 机位比初次 0 机位快”解释为额外机位有加速作用。
记录及分析保留于 `../modules/modelling/build/camera-performance-final`。

验证汇总：625 个 JVM 用例，0 failures/errors，1 skipped；9 组独立 GL 检查
通过，包含 RGBA 方向、alpha、PBO/pack 状态恢复；原版和 Iris 客户端多机位
检查、双机位 DEBUG 实际输出、120 帧玩家运动回归以及最终 JFR 测试通过。
集成服务器的区块/实体订阅路径实际运行过，独立 dedicated server 与任意
第三方光影/材质组合不在这次验证覆盖范围内。

## 多窗口与无窗口启动的验证及补充优化

新增功能完成后，`renderingReleaseCheck` 通过；JVM 检查为 **627 用例，
0 failures/errors，1 skipped**。独立 GL 回归为 **10 组**，新增共享窗口纹理
呈现、双向 fence、current context/capabilities 恢复、resize、窗口独立关闭，
以及 Sodium 类型的 RGBA native blit 保持 RGB、强制输出 alpha=1。

| 实际检查 | 结果 |
| --- | --- |
| macOS MC + Iris/Sodium，多机位窗口及现有机位镜像 | 通过；暂停机位关闭、DPI resize、一个窗口关闭后其他机位继续 |
| 多窗口第一/第三人称 DEBUG 启动 | `MULTI_WINDOW_DEBUG_PASS` |
| macOS `run-headless-client.sh` 隐藏模式 | `HEADLESS_CLIENT_SMOKE_PASS`，主画面及两机位 PNG |
| Debian 12 aarch64，无 DISPLAY / WAYLAND_DISPLAY，GLFW Null + EGL | `HEADLESS_CLIENT_SMOKE_PASS`，主画面及两机位 PNG |

Linux 验证使用 Mesa 22.3.6 的 llvmpipe / OpenGL 4.5，**没有启动 Xvfb**。
检查原生窗口始终不可见、context 归位、没有 GL error、RGBA 非空且 alpha
不透明。主画面 PNG 包含手部/HUD，机位 PNG 为各自的世界视图；JFR 也记录了
两机位各 80 次 render pass。运行的是该 NeoForge 客户端配置导出的 JVM /
classpath，VM 内未重新构建整个 Gradle 项目。此项证明无显示服务的客户端
渲染路径可运行，不证明 NVIDIA/AMD 硬件 EGL、Windows 或所有第三方光影组合。

测试 VM 临时使用 12 GiB 内存、6.5 GiB Java 堆，载入了本机大型测试模型，
包含三张 16384×8192 atlas；这不是最小运行内存要求。产物保存在忽略的
`modules/modelling/build/headless-linux-smoke/debug` 和 `headless-linux-jfr`。

额外修正了两个启动/后台性能问题：

- GLFW Null 的 `glfwWaitEventsTimeout` 是空函数，MC 的限帧循环会忙等。
  EGL 模式改为 `LockSupport.parkNanos`，仍由原来的 frame deadline 循环
  处理提前唤醒。相同 60 FPS 离屏菜单各录制 4 秒 JFR：优化前 74 个渲染线程
  execution samples 中有 26 个在 `limitDisplayFPS`，优化后 50 个样本中为 0。
  这是短时间 CPU 采样证据，不能当成世界渲染 FPS 提升或精确 CPU 百分比。
- PBR 缓存 key 原来临时分配整图 `int[]` 和等大的 byte buffer，增加
  `8 × width × height` 字节 scratch。现在按 4096 像素分块，scratch 为
  32 KiB，SHA-256 的 descriptor、尺寸、大端 ARGB 顺序不变。测试覆盖 chunk
  边界、不同 raster 格式和 subimage，旧磁盘缓存保持有效。原始图像、烘焙
  结果、GPU atlas 的实际内存需求仍存在。

此前 0/1/2/4 机位 JFR 表不包含新增窗口呈现或同步 CPU 读回成本，不能将其
耗时直接套用到多窗口或软件 EGL 部署。



> 本文是离屏与多机位主题的实操篇。输出与相机的契约见 [多机位输出契约](../reference/multi-camera-output.md)，原理见 [离屏输出与多机位原理](../explanation/offline-rendering-and-multi-cam.md)。
