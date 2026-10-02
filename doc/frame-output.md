# MC 最终画面离屏输出

`MinecraftFrameOutputs` 在 `Minecraft.runTick` 的最终
`RenderTarget.blitToScreen(width, height)` 调用处路由画面。世界、后处理、
HUD、GUI、加载界面及 FPS 调试面板已完成绘制；输出复用同一次原生最终
blit 的 shader 和缩放方式。不会替换 `getMainRenderTarget()`，也不会在世界
pass 中修改相机或截获其他内部 FBO。这是最终画面的 WYSIWYG 输出入口。

## 使用

在渲染线程注册，保存返回的 registration，并在不需要输出时关闭：

```java
import lib.kasuga.rendering.output.FrameOutputMode;
import lib.kasuga.rendering.output.mc.MinecraftFrameOutputs;

// 同时显示在 MC 窗口与输出 FBO。
var capture = MinecraftFrameOutputs.open(FrameOutputMode.MIRROR, frame -> {
    var texture = frame.resource();
    // texture.textureId(): 完整最终画面，可供材质、合成器、编码器使用。
    // texture.framebufferId(): 可在当前 GL context 中读取颜色附件。
    // frame.width()/height(): 窗口的实际 framebuffer 像素尺寸。
    // frame.viewId()/frameNumber(): 输出视角及该视角的交付序号。
    consumeTexture(texture);
});

// 不再展示到 MC 窗口，只交付到输出 FBO。
var exclusive = MinecraftFrameOutputs.open(FrameOutputMode.OFFSCREEN_ONLY, frame -> {
    consumeTexture(frame.resource());
});

// 在渲染线程取消订阅。最后一个订阅关闭后，才释放此 view 的 FBO 与颜色纹理。
capture.close();
exclusive.close();
```

代码中的 `consumeTexture` 是调用方的消费者。输出提供本进程、当前 GL
context 中的颜色纹理；传输到其他进程、机器或视频文件，由消费者执行
读回、编码或共享。无需为了输出再渲染一遍世界。

没有 registration 时直接执行原生屏幕 blit，不分配 FBO，也不查询 GL 状态。
同一 view 每帧只捕获一次；多个 registration 共享同一个只读 `OutputFrame`
和 FBO/颜色纹理句柄，不会因订阅数增加而重复捕获。任一正常交付且仍有效的
`OFFSCREEN_ONLY` registration 会取消本帧屏幕 blit。窗口事件、游戏循环和
display swap 继续执行。关闭所有 exclusive 输出后自动恢复正常展示。

## 生命周期与错误

- 所有注册、交付、关闭操作由渲染线程持有；MC 入口及 registration 修改
  操作检查线程。回调也在渲染线程同步执行，不能在其中阻塞等待编码/网络。
- `OutputFrame` 与 `FrameTexture` 是共享只读借用资源，不可写入、resize 或
  删除。纹理 ID 是 GPU 句柄，不是 CPU 指针；Java record 的不可变性不能
  阻止调用方错误地调用 GL 写操作。下一帧可能覆盖纹理，resize 或最后一个
  订阅关闭会删除旧 GL 对象。回调外保留画面必须复制到自己的 storage；
  不要将裸纹理 ID 交给无 GL context 的异步线程。
- FBO 按最终窗口 framebuffer 大小自动调整，输出颜色为 RGBA8，没有深度。
  原生屏幕 blit 只写 RGB，输出初始化为不透明 alpha。尺寸变化会先验证新
  FBO，再退役旧 FBO，不改 MC 的渲染尺寸、GUI scale 或投影。
- 纹理遵循 OpenGL 左下角原点。写 PNG 等左上角图像时按消费者需要翻转 Y。
- 输出目标恢复各自独立的 read/draw framebuffer 绑定与 viewport；分配
  临时修改的纹理绑定、clear color、color mask 和 scissor 在最终 blit 前
  恢复。消费者应恢复自己修改的其他 GPU 状态，并遵守 MC 的状态缓存 API。
- 消费者异常只关闭该 registration，其他消费者继续借用同一份完成帧。
  目标创建或绘制失败则关闭该 view 的全部订阅，只报告一次捕获错误；其他
  view 不受影响。如果没有有效 exclusive 输出成功，本帧仍执行原生屏幕
  blit，避免一次导出故障永久吞掉屏幕。registration 的 `isClosed()` 可
  用于发现被自动移除的输出；`latest()` 仅在 registration 有效时可借用。
- 回调中关闭自己/其他 registration 或关闭整个 router，实际资源释放延迟
  到本次 publication 结束；回调中的借用资源不会中途失效。
- Minecraft 关闭时，Mixin 在窗口及渲染资源销毁前释放所有输出。

## 多机位

默认 view 为 `minecraft:main`。`FrameOutputRouter<T>`、`FrameOutputTarget<T>`
和 `FrameBlitter` 不依赖 Minecraft。view ID 标识**已经渲染完成的视角**，
可以用于多个相机、不同输出窗口或编码通道。真正的相机生产者现由
`MinecraftWorldViews` 提供，使用方法及支持范围见 [多机位与离屏输出文档](../docs/offline_rendering_and_multi_cam.md)。

```java
var cameraOutput = MinecraftFrameOutputs.open("camera:rear", FrameOutputMode.MIRROR,
        frame -> consumeTexture(frame.resource()));

// 自定义 producer 在自己的最终画面完成后也可直接交付：
var delivery = MinecraftFrameOutputs.publish("camera:rear", width, height,
        (w, h) -> rearCameraFinalTarget.blitToScreen(w, h));
```

只注册输出消费者不会自动创建相机；还需要注册同 view ID 的
`MinecraftWorldViews` producer。相机拥有世界渲染的颜色/深度目标，输出
router 按 view 拥有完成画面的共享颜色目标；关闭消费者仅取消订阅，最后
一个订阅关闭才释放颜色目标。关闭相机 producer 与取消订阅仍是不同操作。

非 MC 宿主可以使用 `new FrameOutputRouter<>(checkThread, targetFactory)`，
然后调用 `register(viewId, mode, consumer)`。factory 每个 view 延迟调用一次。
保留的四参数 `register` 重载由该 view 的第一个注册选择 factory；之后的
注册共享既有目标，传入的 factory 不会执行，直到最后一个订阅关闭。

## 验证入口

```sh
./gradlew :modules:modelling:test :modules:modelling:renderGlTest
./gradlew :modules:modelling:runClient \
  -PkasugaTestFrameOutput=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client-directory
```

单测覆盖共享帧身份、单次捕获、最后订阅释放、回调内替换、view 隔离、
失败回退与延迟释放。独立 GL 回归增加三个消费者共享一个 FBO/一次最终
blit、消费者异常隔离和最后订阅释放，同时
使用 Minecraft 的最终 blit fragment shader 检查 RGB/方向、不透明 alpha、
resize、异常时 read/draw/viewport 恢复及关闭释放。

可选客户端测试在菜单画面运行，逐像素比较原生完整 GUI 颜色与输出 FBO，
切换 MIRROR/OFFSCREEN_ONLY，调整窗口大小并故意让一个消费者失败，最后
恢复正常帧并退出。结果和 PNG 写入隔离目录的 `debug/frame-output/`。
