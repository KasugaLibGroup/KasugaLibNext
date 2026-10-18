# 多机位输出契约

## 最终主画面 WYSIWYG 输出

```java
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;

var output = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, frame -> {
    var texture = frame.resource();
    consumeTexture(texture); // 调用方实现，在渲染线程同步消费。
});
output.close(); // 在渲染线程释放。
```

`MIRROR` 同时交付 FBO 和显示窗口；`OFFSCREEN_ONLY` 成功交付后跳过本帧
屏幕 blit，游戏循环和窗口事件仍继续。关闭所有独占输出后恢复正常显示；
没有独占输出成功时回退到原生窗口输出。

默认 view `minecraft:main` 在 `Minecraft.runTick` 的最终
`RenderTarget.blitToScreen` 处截获，世界、后处理、HUD、GUI、加载界面和
调试面板均已绘制。它复用原生最终 blit，提供最终 WYSIWYG 画面，无需
为了捕获再渲染一遍世界。额外机位通过自己的 world/Iris composition 交付。

## 一次调用创建相机及生命周期

所有创建、更新、暂停、恢复、关闭操作都在渲染线程进行。其他线程使用
`Minecraft.getInstance().execute(...)` 调度；首次创建需要世界已加载。

```java
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

var settings = new CameraRenderSettings(
        12,
        CameraRenderSettings.Quality.FANCY,
        new CameraRenderSettings.Shader(
                Path.of("/absolute/path/to/shader-pack.zip"),
                Map.of("SHADOW_QUALITY", "1")), // 由所选光影包定义的选项名。
        List.of(Path.of("/absolute/path/to/resource-pack.zip")),
        2,    // mipmap 等级 0..4
        true  // ambient occlusion
);
var camera = MinecraftCameras.create("capture:wide", () ->
        new WorldCameraView(100, 80, 100, 45, 15, 0, 70, 1280, 720),
        settings, frame -> consumeTexture(frame.resource()));

camera.pause();
camera.updatePose(new WorldCameraView(110, 82, 100, 90, 10, 0, 50, 1920, 1080));
camera.resume();
camera.close(); // 同时关闭 producer 和它拥有的输出。
```

消费者是示例占位函数。无光影使用 `CameraRenderSettings.Shader.disabled()`。
省略 settings 的重载使用视距 8、Fancy、无光影、基础 vanilla/mod 材质、
mipmap 0、AO 开启。pose 可以传固定记录或每帧一次采样的 provider。

`WorldCameraView` 参数为 x/y/z 世界绝对坐标、yaw/pitch/roll、垂直 FOV、宽/高。
角度单位为度，yaw/pitch 遵循 MC。数值必须有限，FOV 为 `(0, 180)`，尺寸
必须为正且不超过 GL 最大纹理尺寸。新尺寸自动替换渲染和输出 FBO。
view ID 必须非空、唯一，不能使用 `minecraft:main`。

`CameraHandle.state()` 为 READY、PAUSED、FAILED、CLOSED；`failure()` 保存
故障。READY 表示注册可用，初次资源异步加载时可能还没有交付第一帧。
provider、资源加载、绘制或消费者失败会移除该机位并释放资源，保留异常。
`close()` 幂等；暂停停止输出并撤销服务端订阅，恢复后重新订阅。

资源配置在创建时固定，更换包、质量或视距时关闭后重新创建。位置、FOV、
尺寸通过 `updatePose` 动态修改。低层也可分别调用 `MinecraftWorldViews.register`
和 `MinecraftFrameOutputs.open(viewId, ...)`，或给同一 view 添加多个消费者；
低层 producer/消费者需分别关闭。无消费者或未启用的机位不重渲染、不采样。

## 同进程隔离与渲染时序

```mermaid
flowchart LR
    A[宿主帧 / 共享动画 token] --> B[机位 A world / resource / Iris scope]
    B --> C[A 世界与 Iris 最终合成]
    C --> D[A 消费者]
    D --> E[机位 B 独立 scope 与输出]
    E --> F[恢复主视角并渲染]
    F --> G[主后处理 / HUD / GUI]
    G --> H[最终主画面输出]
```

| 配置或资源 | 隔离方式 |
| --- | --- |
| 位置、朝向、FOV、尺寸 | 每机位 Camera、投影和颜色/深度 FBO |
| 区块与光照 | 每机位 ClientLevel、ClientChunkCache、light engine |
| 地形与实体绘制 | 每机位 LevelRenderer、RenderBuffers、可变实体/方块实体 dispatcher |
| 渲染距离 | 每机位 2..32 chunks，独立服务端订阅与客户端视距 |
| Iris 光影 | 每机位 ShaderPack、PipelineManager、材质设置、uniform 时间、捕获及 shadow 状态 |
| 材质与模型 | 每 pack stack 独立 resource manager、TextureManager、ModelManager、atlas |
| 质量 | 每机位 Fast/Fancy、叶片类型、AO、mipmap |
| 雾与云 | 每机位雾历史；Iris/Sodium 云格式标志按 CloudRenderer 实例保存 |

同一 pack stack/mipmap 的机位共享载入的模型/atlas，以引用计数释放；可变
renderer/dispatcher 各自持有。资源包依列表顺序叠加，后面的优先。基础资源
保留 vanilla/mod resources，不继承主视角选中的用户/服务端包，需要的包
明确写入 `resourcePacks`。目录和 zip 都支持。连接下载的玩家 skin/cape/elytra，
以及共享 light texture、字体 glyph、particle atlas 和没有文件资源的模组生成纹理
为借用资源，不随机位删除。机位自有 atlas 优先，不会被主视角 atlas 替换。

Iris 入口在 scope 内路由到各自实例，部分 static 状态通过切换和恢复隔离。
区块构建任务创建时捕获机位资源、材质和质量，在 worker 执行时恢复上下文。
原版 rebuild task 还持有资源 lease，取消或 CPU 构建结束后才归还，避免关闭
机位时释放仍被异步任务读取的模型/材质。原版 ViewArea 按实际机位坐标定位。
这要求同一 context 顺序渲染，不支持多线程同时绘制机位。

宿主 tick 和 `RenderFrameEvent.Pre/Post` 每帧仍只执行一次，模型动画以共享
frame token 去重；可见标记每机位绘制前清理。scope 内 MC 世界、renderer、
buffers、目标、camera、资源 getter 指向当前机位，world stage 收到机位视锥
与矩阵。自定义时间历史可用 `WorldRenderPipelineContext.viewId()` 分区。

退出 scope 恢复主状态以及 GL read/draw FBO、viewport、投影和绘制/雾状态。
回调内关闭延迟释放至本次交付结束。世界切换销毁旧 session，下次按当前
世界重建；MC 关闭统一释放。vanilla 在途 builder pack 在归还时释放，Sodium
停止其 terrain workers 后释放原生缓冲、sky VBO、pipeline 和 FBO。

## 远处区块与实体

服务端也需安装包含该功能的 KasugaLib；单人集成服务器同样使用协议。
不支持 channel 的服务器会让机位明确失败，不假装加载了远处世界。

服务端按 camera UUID、epoch、维度和中心管理订阅，分批申请 FULL/non-ticking
区块 tickets，不在主线程阻塞等待生成。每订阅每 tick 最多申请 8 个 ticket、
发送 4 个已完成且 light-ready 的快照；方块/光照变化触发重发。客户端把
区块、heightmap、光照及方块实体送进各自 ClientLevel。移动丢弃范围外缓存
与过期 epoch 数据，并清理 light/Sodium tracker。暂停、关闭、登出、维度
变化和 heartbeat 超时撤销服务端 tickets。

实体每 5 ticks 同步 spawn、位置、头朝向、完整 metadata、装备、属性、乘客
和已有 leash 到机位世界，副本独立插值，不消费原版 `packDirty`，保持主玩家
实体追踪。该订阅不是新玩家连接：远处能加载/拍摄，但 non-ticking tickets
不扩展 AI/红石的服务端模拟范围。

## 输出规约与边界

`OutputFrame<FrameTexture>` 提供 view ID、每 view 的帧号、尺寸和颜色纹理。
颜色为 RGBA8、不透明 alpha，OpenGL 左下角原点，无深度附件。router 按
view 拥有颜色目标；同一帧的所有订阅收到同一个 `OutputFrame` 和资源对象，
只借用读取，不可写入、resize 或删除。下一帧覆盖，resize 或最后一个订阅
关闭删除旧对象；回调内取消订阅时，释放推迟到本次交付结束。回调外保留画面
必须当场复制到自己的 GPU/CPU storage，DEBUG 预览也是先复制后合成。
消费者在渲染线程，不应等待编码/网络；可以自行实现 PBO/fence 读回和有界
worker 队列，裸纹理 ID 不能交给没有当前 GL context 的异步线程。

上表内资源已隔离，但并非复制完整客户端：

- 额外机位输出 world stages 和 Iris 最终合成，不包含 HUD/GUI、手部、发光
  轮廓及主视角的 `GameRenderer.postEffect`。完整主画面走最终输出入口。
- 质量支持 Fast/Fancy、AO、mipmap，没有全部 options（Fabulous、粒子预设、
  独立云开关等）。主 light texture、字体、particle engine 和原版 core shader
  loader 共用；修改字体、粒子或 core shaders 的包不能认定完全隔离，远处
  机位也没有独立的服务器粒子事件流。
- 未完整复刻 resource-pack overlays metadata、第三方 reload listener、模组
  实体 pairing payload/attachment、效果与音频协议。
- 第三方代码若直接读 static 状态、缓存主世界或在 world stage 推进逻辑，仍
  需适配；带时间历史的效果应按 view ID 管理。
- 多机位高视距会增加生成、网络、CPU/GPU/显存负载。公开服务器的权限、
  订阅数量和资源预算由上层策略约束。

## 不显示窗口画面的 FBO 数据流如何调用

无窗口启动后，同样使用下面的 FBO API。`MinecraftCameras.create` 自带离屏
消费者；`MinecraftFrameOutputs.open` 订阅最终主画面。启动器保留一个主画面
`OFFSCREEN_ONLY` 输出，包括加载界面，因此关闭调用方的输出也不会显示 MC
窗口。启动器和调用方的主画面订阅共享一个捕获目标，不额外复制一帧。
正常桌面启动时，主画面订阅可选 `OFFSCREEN_ONLY`，关闭后恢复显示。

### GPU 内直接传递

```java
var camera = MinecraftCameras.create("export:camera", () ->
        new WorldCameraView(100, 80, 100, 0, 20, 0, 70, 1280, 720), frame -> {
    FrameTexture texture = frame.resource();
    // 在这个回调和当前 GL context 中，使用 texture.textureId() 采样/合成，
    // 或从 texture.framebufferId() blit 到调用方持有的 FBO。
});
```

同步只读消费者直接共享上面的句柄，不需要先复制。比如一个机位同时
订阅两个预览窗口和一个 GPU 合成器，router 只创建一份输出颜色纹理、
每帧只执行一次最终捕获；窗口呈现到各自屏幕仍各需一次 blit。关闭其中
一个消费者不会释放其他消费者正在用的画面，消费者异常也只取消其订阅。
捕获本身失败则取消该 view 的所有订阅，其他机位继续工作。

纹理 ID 是 GL 对象句柄，不是可被 CPU 解引用的指针，仅在本进程相应 GL
context/share group 有意义。即使只读，CPU 编码器仍需回读。若后续帧或异步
消费者还需要它，必须在回调里复制到自有 FBO，并在 GL/render 线程管理复制目标和 fence，不能
把借用的 ID 直接放入后台队列。DEBUG 预览的 `OutputFramebuffer.capture`
加 `glBlitFramebuffer` 是一份实际的 GPU 复制示例，见 `modelling` 模块的 `DualCameraDebug`。

### CPU 字节流：最小可用录制示例

提供 `RgbaReadback.copy(FrameTexture)`：在回调中返回**调用方拥有的**紧密排列
RGBA8 `byte[]`，已翻转为左上角原点，无 stride padding。它恢复 read FBO、
read buffer、pixel-pack buffer 和 pack 参数；返回后 byte[] 可交给无 GL
context 的线程。该实现是同步 `glReadPixels`，会等待 GPU，适合功能验证，
高吞吐录制应替换为 PBO/fence 多槽读回。

以下片段固定 1280×720，把一个机位写为可编码的原始 RGBA 文件；创建/关闭
camera 在渲染线程，磁盘写入在单独线程。调用方应先创建输出目录：

```java
import lib.kasuga.rendering.output.WorldCameraView;
import lib.kasuga.rendering.output.gl.RgbaReadback;
import lib.kasuga.rendering.output.mc.MinecraftCameras;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

var queue = new ArrayBlockingQueue<byte[]>(2);
var running = new AtomicBoolean(true);
var writer = Executors.newSingleThreadExecutor();
// 保留 future；结束后检查 get()，使磁盘错误可见。
var writeResult = writer.submit(() -> {
    try (var stream = Files.newOutputStream(Path.of("capture.rgba"))) {
        while (running.get() || !queue.isEmpty()) {
            byte[] rgba = queue.poll(100, TimeUnit.MILLISECONDS);
            if (rgba != null) stream.write(rgba);
        }
    }
    return null;
});
var camera = MinecraftCameras.create("export:camera", () ->
        new WorldCameraView(100, 80, 100, 0, 20, 0, 70, 1280, 720), frame -> {
    if (writeResult.isDone()) {
        try { writeResult.get(); }
        catch (Exception failure) { throw new IllegalStateException("Output writer failed", failure); }
        throw new IllegalStateException("Output writer stopped");
    }
    if (queue.remainingCapacity() == 0) return; // 有界队列满时丢帧，不阻塞宿主。
    queue.offer(RgbaReadback.copy(frame.resource()));
});

// 录制结束：在渲染线程关闭 camera，然后通知 writer 排空剩余数据。
try { camera.close(); }
finally { running.set(false); writer.shutdown(); }
// 在后台控制线程等待 writeResult.get()，不要在渲染线程等待磁盘。
```

输出文件顺序为逐帧、逐行 RGBA，单帧大小 `1280 * 720 * 4` bytes。可用 FFmpeg
转换为视频：

```sh
ffmpeg -f rawvideo -pixel_format rgba -video_size 1280x720 -framerate 60 \
  -i capture.rgba -c:v libx264 -pix_fmt yuv420p capture.mp4
```

这里的 60 是对文件帧序列的播放解释，并非保证 MC 按 60 FPS 采样；队列满
会丢帧，游戏也按真实时间 tick。需要准确时长/固定时间步的离线渲染时，还
需要调度时间、按时间戳补帧或离线推进，以及编码器背压策略。连续流期间
分辨率必须固定，resize 后需重新配置编码器/开始新片段。

只想导出最终主画面时把上述 create 换为：

```java
var output = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, frame -> {
    byte[] rgba = RgbaReadback.copy(frame.resource());
    // 按相同有界队列规约交付；尺寸使用 frame.width()/frame.height()。
});
// 结束时 output.close()。
```

流向为 `world/Iris → 每 view 一次最终 blit → 共享输出 FBO → 同步只读回调 → 自有 GPU 副本或
RGBA 字节 → 有界后台队列 → 编码/磁盘/网络`。额外机位不含 HUD，主视角含
完整最终画面。公开 API 的纹理输出和读回已实现；示例视频编码属于调用方。

## 只读消费者共享完成帧

输出 router 已改为按 view 捕获和持有 storage。同一帧所有订阅收到同一个
`OutputFrame<FrameTexture>`，最后一个订阅关闭后才释放共享目标；回调中关闭
或替换订阅不会提前删除借用纹理。首次注册选择目标 factory，首次有效输出
才分配 GPU storage。不同 view 仍各自拥有输出目标。

| 同一 view 的 N 个同步只读订阅 | 修改前 | 修改后 |
| --- | --- | --- |
| 输出颜色纹理 / FBO | N 份 | 1 份 |
| 每帧最终捕获 | N 次 | 1 次 |
| 每帧捕获的 alpha 初始化操作 | 2N 次 clear | 2 次 clear |
| 消费者调用 | N 次 | N 次 |

RGBA8 颜色 storage 的名义大小是 `4 × width × height` 字节；1080p 约
7.91 MiB、4K 约 31.64 MiB。上表不计 driver metadata、世界渲染目标、窗口
呈现或消费者自己的复制/编码 storage。`MIRROR` 主画面仍需要正常屏幕 blit，
每个辅助窗口也仍需要呈现；共享捕获并不省掉这些消费者的工作。

此修改没有加入 CPU 零拷贝或异步读回。GPU 纹理 ID 不是 CPU 地址，
`RgbaReadback.copy` 仍执行同步 GPU 回读、Java 数组复制和 Y 翻转。只读 GPU
消费者直接借用句柄即可；跨帧、异步消费需自有 storage 和同步机制。

本轮 JVM 检查 **633 用例，0 failures/errors，1 skipped**，独立 GL **10 组**
通过，`renderingReleaseCheck` 通过。新增 GL 断言实测三个订阅只分配一个
输出目标、每帧只捕获一次，消费者失败后仍可读取正确 RGB/alpha，resize
和最后订阅关闭释放旧 GL 对象。真实 MC 菜单检查通过共享帧身份、完整 GUI
逐像素一致、resize、回调内替换订阅、最后订阅释放和消费者故障回退。
这些结果验证捕获次数和资源所有权，没有测量独立 GPU 毫秒耗时；前文 JFR
数据为修改前的整帧结果，不能据此声称本次共享捕获的具体 FPS 提升。

共享捕获版本还重新通过 macOS MC + Iris/Sodium 的多机位窗口回归
（`FRAME_WINDOWS_SMOKE_PASS`，独立关闭与 resize）和隐藏窗口启动回归
（`HEADLESS_CLIENT_SMOKE_PASS`，主画面加两机位输出）。本轮没有重跑 Linux
无显示服务测试；前文 Linux 记录属于此前的启动器验证。

> 本文是离屏与多机位主题的契约篇。启动、验证与性能实操见 [离屏渲染实操](../guides/offline-rendering.md)，原理见 [离屏输出与多机位原理](../explanation/offline-rendering-and-multi-cam.md)。
