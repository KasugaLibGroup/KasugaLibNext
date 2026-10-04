# 摄像机控制与动画轨道

`MinecraftCameras.create(...)` 和 `MinecraftFrameWindows.createCamera(...)` 返回的摄像机句柄
均支持位置、旋转、光学缩放和关键帧播放。所有控制操作在渲染线程执行。

## 直接控制

```java
camera.moveTo(x, y, z);                 // 世界坐标，绝对位置
camera.moveBy(dx, dy, dz);              // 世界坐标，增量移动
camera.rotateTo(yaw, pitch, roll);       // 角度，沿用 Minecraft 摄像机约定
camera.rotateBy(dYaw, dPitch, dRoll);    // 增量旋转
camera.setVerticalFov(60);              // 垂直 FOV，必须在 (0, 180) 度内
camera.zoom(2);                        // 投影放大两倍
WorldCameraView pose = camera.pose();   // 当前 tick 的姿态
```

直接控制以当前动画 tick 的姿态为起点，保留未修改的分量和输出尺寸，然后替换为固定姿态，停止原动画。
需要跟随实体时，仍可用 `updatePose(Supplier<WorldCameraView>)`；替换供应器也会清除动画。
`WorldCameraView` 另提供相同的不可变变换方法，方便自行组合供应器。

摄像机缩放改变投影，不缩放世界或输出分辨率。倍率 `m` 满足
`tan(newFov / 2) = tan(baseFov / 2) / m`，大于 1 放大，小于 1 缩小。
数值极限处 FOV 保持在可表示的 `(0, 180)` 度范围内。

## 动画轨道

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

每条轨道独立驱动 `x / y / z / yaw / pitch / roll / zoom` 中的一个分量。
`move`、`rotate` 是同时添加三条轨道关键帧的便捷方法；只改变一个分量可使用
`builder.keyframe(Channel.YAW, time, value, easing)`。
位置和角度是绝对值；zoom 相对于供应器当帧提供的 FOV，每帧重新求值，不累积放大。
缺少的轨道保留供应器的值，窗口摄像机仍随 framebuffer/DPI 更新输出尺寸。

时间单位为秒。轨道自动排序并复制，重复时间、重复分量、超出片段时长、空轨道和非法数值在构造时拒绝。
首尾关键帧之外保持端点值。缓动沿用模型的 `Easing`，作用于关键帧的下一段。
旋转对未折返的角度数值插值，因此 `0 → 720` 可以完整转两圈；例如跨越 360 度边界时使用 `350 → 370`。
back/elastic 缓动造成 zoom 小于等于零时限制为极小正值，保证投影有效。

```java
camera.animation().pause();        // 暂停动画，摄像机继续输出画面
camera.animation().seek(1.5f);     // 立即跳转，清除旧的插值区间
camera.animation().setSpeed(2f);   // 两倍速；0 冻结时钟
camera.animation().resume();
camera.animation().isPlaying();
camera.animation().currentTime();  // 循环时也是单调时钟
camera.animation().stop();         // 移除轨道，恢复原姿态供应器
```

非循环播放结束后保留终点姿态；重播使用 `play`，或先 `seek(0)` 再 `resume()`。
独立播放时 `camera.pause()` 同时暂停画面输出和自身时钟推进，`camera.resume()` 恢复。
跟随共享时间轴时，`camera.pause()` 只暂停该摄像机输出，其他目标继续播放；暂停整个场景使用 `timeline.pause()`。
关闭或失败的摄像机拒绝进一步控制，包括之前保存的 animation 控制器引用。

Minecraft 自动每游戏 tick 推进一次时钟（20 Hz），世界暂停时不推进；每帧按游戏的 partial tick 插值。
`render-core` 中的 `OwnedCamera` / `CameraAnimationPlayer` 可独立使用，由宿主调用 `tick(dt)` 和
`samplePose(partialTick)` / `sample(partialTick)`。渲染采样本身不推进时钟。

## 与现有模型动画集成

模型的 `AnimationPlayer<T>` 和摄像机适配器现在都使用 `AnimationPlayback<T, R>`。
现有 `AnimationSampler<T>` 是输出模型 `Pose` 的 `AnimationSource<T, Pose>`，
摄像机的 `CameraClipSampler` 输出未应用到世界的 `CameraPose`，统一内核负责计时和采样。
原模型 `play(sampler, data, loop)`、glTF 播放入口及旧 JSON 保持兼容。

现有 `AnimationClip` 新增可选的 `cameras`，一个片段可以包含模型骨骼、形变、材质和多个命名镜头。
例如以下 JSON 会同步移动模型的 root 和场景镜头：

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

通过 `AnimationClip.CODEC` 读取为 `scene` 后，把目标绑定到同一个时间轴：

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

// 场景结束时释放驱动登记和目标绑定。
driver.close();
modelPlayer.stop();
camera.animation().stop();
timeline.stop();
```

`follow` 只绑定和采样，不重启时间轴，不自行推进。模型原有的 `animate` 调用和摄像机的自动 tick
都不会重复推进共享时钟。`MinecraftAnimationTimelines.drive` 每游戏 tick 统一推进一次，世界暂停时停止，
重复登记同一个时间轴会报错。纯 Java 宿主可以不用该登记，自行每 tick 调用一次 `timeline.tick(dt)`。
控制线程通过不可变的 volatile 快照把时钟交给渲染线程。

摄像机 `animation().pause/seek/setSpeed/resume` 和模型播放器的同名控制操作作用于其绑定的时间轴，
因此共享时也会同时控制其他目标。`stop()`、直接控制和关闭镜头只解除该目标，外部时间轴继续工作。
模型 reload/rebind 只更换输出目标，保留共享时钟进度。

逻辑镜头名（如 `main_shot`）与输出 view ID 独立，可以将同一个轨道绑定到多个镜头。
也可以把独立 `CameraAnimationClip` 与 glTF/VMD 等现有模型采样器绑定到同一时间轴。
不同长度的片段在共享周期内按各自长度保持终点，循环统一在时间轴的 duration 处发生。

已有模型播放器独立播放时也可作为时钟所有者：

```java
modelPlayer.play(ClipSampler.INSTANCE, scene, true);
camera.animation().follow(scene, "main_shot", modelPlayer.timeline());
```

此时模型宿主的 tick 驱动时钟，摄像机只跟随；该时钟不能再登记到 `MinecraftAnimationTimelines`
或直接调用 `timeline.tick`。模型的 `stop/play` 会停止旧的自有时钟；需要独立管理场景生命周期时使用外部共享时间轴。

`ClipSampler` / FSM 的模型姿态出口仍只应用模型通道；摄像机通道通过显式的目标绑定应用，
当前共享时间轴不接管 FSM 的分层权重或状态切换。

## JSON

通过 `CameraAnimationClip.CODEC` 读取和写出，与模型动画采用相同的 easing 名称。

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

单元测试使用 `./gradlew :modules:modelling:modelUnitTest --offline`。
使用复制的测试世界运行真实摄像机轨道烟测：

```bash
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=build/camera-animation-smoke \
  -PkasugaQuickPlayWorld=world-view-test -PkasugaTestCameraAnimation=true --offline
```

测试目录需预先包含 `saves/world-view-test`。成功时输出 `CAMERA_ANIMATION_SMOKE_PASS`，
并在该客户端目录的 `debug/camera-animation.json` 写出结果。
