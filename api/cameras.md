# Player / Free / Fixed Camera

通用数据位于 `lib.kasuga.rendering.output.camera`，Minecraft 接入位于
`lib.kasuga.rendering.output.mc`。原理见 [多机位与输出](../doc/offline_rendering_and_multi_cam.md)。
所有创建、控制和关闭操作在渲染线程执行。

## 类型与创建

| `CameraType` | 姿态与配置来源 | 控制方式 |
| --- | --- | --- |
| `PLAYER` | 原生玩家摄像机、第一/第三人称、当前 FOV/roll、资源和着色器 | 原生玩家控制；句柄管理输出生命周期 |
| `FREE` | 独立世界位置、旋转、FOV、尺寸和 `CameraRenderSettings` | 直接控制、供应器或动画轨道 |
| `FIXED` | 目标插值位置/朝向与偏移；独立投影和 `CameraRenderSettings` | 跟随目标、偏移、朝向模式和投影控制 |

`FIXED` 表示绑定目标的跟随相机。静止机位可用固定姿态的 `FREE`。
`type()` 与 `CameraState` 的 `READY / PAUSED / FAILED / CLOSED` 生命周期独立。

```java
CameraHandle player = MinecraftCameras.createPlayer(frame -> consume(frame));
CameraHandle free = MinecraftCameras.createFree("example:free",
        new WorldCameraView(0, 80, 0, 90, 20, 0, 60, 1280, 720),
        CameraRenderSettings.defaults(), frame -> consume(frame));
CameraHandle fixed = MinecraftCameras.createFixed("example:follow", entity,
        new CameraProjection(60, 1280, 720),
        CameraRenderSettings.defaults(), frame -> consume(frame));
```

`createFree` 接受 `WorldCameraView` 或 `Supplier<WorldCameraView>`，各有省略 settings 的默认重载。
原有 `MinecraftCameras.create(...)` 保留为 Free Camera 的兼容入口。

`createFixed` 的签名如下，均返回 `CameraHandle`：

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

Free/Fixed 的 view ID 必须非空、唯一，且不能为 `minecraft:main`。Player 使用 `minecraft:main`，
只允许一个拥有生命周期的 Player 句柄；多个消费者通过
[`MinecraftFrameOutputs.open(MIRROR, ...)`](frame-output.md) 共享主画面。
Player 输出包含原生世界、手部、后处理、HUD/GUI，不额外渲染世界。暂停/关闭仅取消这个句柄的订阅，
玩家主窗口和其他主画面消费者继续运行。Free/Fixed 输出世界画面。

## 跟随数据与实体接入

```java
CameraTarget target = MinecraftCameraTargets.entity(() -> Minecraft.getInstance().player);
fixed.follow(target); // 切换目标，保留投影和偏移
fixed.updateFollowSettings(new CameraFollowSettings(
        0, 2, 4,       // 世界坐标偏移，不随实体朝向旋转
        0, 10, 0,      // yaw / pitch / roll，单位为度
        true));        // 跟随朝向，角度字段作为附加偏移
fixed.setVerticalFov(55);
fixed.zoom(2);
fixed.setProjection(new CameraProjection(50, 1920, 1080));
```

`CameraTarget` 是通用函数接口 `CameraTarget.Pose sample(float partialTick)`；
`Pose(double x, double y, double z, float yaw, float pitch, float roll)` 不含 Entity 或模型格式类型。
自定义宿主可以绑定任意对象。Minecraft adapter 默认使用实体插值后的眼睛位置和视线朝向。
传入实体对象会持续绑定该对象；传入 supplier 可以在 respawn/维度切换后取得新对象。

默认 `CameraFollowSettings.defaults()` 所有偏移为 0，`followRotation=true`。
设为 `false` 时仍跟随位置，yaw/pitch/roll 作为世界中的绝对朝向。
FOV、zoom、projection 和渲染配置更新均保留目标绑定。此接口没有自动避墙或碰撞推离逻辑。

目标返回 null 表示暂时不可用。实体为 null、已移除或不属于当前源世界时，Minecraft adapter
跳过该视图、暂停其区块订阅并保留句柄和输出，目标可用后自动恢复。
等待期间 `CameraState` 仍为 `READY`，该视图帧号不增加；`pose()` 抛出 `IllegalStateException`，
不会使句柄失败。首次目标出现前也能设置 FOV/投影。目标抛出的运行时异常则使相机失败并释放资源。

`followTarget()` 返回 `Optional<CameraTarget>`；`followSettings()` 返回当前不可变配置。
对 Player/Free 调用跟随写入或 `followSettings()` 抛出 `UnsupportedOperationException`。
Fixed 不接受 `updatePose / moveTo / moveBy / rotateTo / rotateBy / animation()`；通过跟随配置控制它。

## Free 控制与渲染配置

Free 位置、旋转和动画接口见 [摄像机动画](camera-animation.md)。Player 不接受独立姿态、投影、
跟随、动画和渲染配置写入，对应操作抛出 `UnsupportedOperationException`。

```java
free.setProjection(new CameraProjection(65, 1280, 720));
CameraRenderSettings settings = free.renderSettings().orElseThrow();
free.updateRenderSettings(new CameraRenderSettings(12,
        CameraRenderSettings.Quality.FANCY,
        CameraRenderSettings.Shader.pack(shaderPack)));
```

`renderSettings()` 对 Player 返回 empty，对 Minecraft Free/Fixed 返回当前配置。
`updateRenderSettings` 在下一次目标可用的渲染帧重建该机位的 renderer/assets/Iris session，保留
pose/follow、动画、view ID 和输出订阅。当前帧和回调继续使用原 session 配置，回调中可安全请求更新。
相同配置不重建；渲染暂停时延迟生效。shader pack 需要 Iris，初始化失败遵循相机失败/释放契约。
字段与资源包规则见 [多机位配置](offline_rendering_and_multi_cam.md)。

`CameraProjection` 要求垂直 FOV 在 `(0, 180)` 度内，宽高为正整数，提供 `from(view)`、
`withVerticalFov`、`zoom` 和 `apply(view)`。Free 修改投影会停止自身动画；更新渲染配置不会停止动画。
窗口拥有的 Free Camera 以实际 framebuffer/DPI 尺寸为准。

## 宿主扩展与生命周期

纯 Java 宿主使用 `CameraSource.Player(ViewProvider)`、`CameraSource.Free(Supplier<WorldCameraView>)`
或 `CameraSource.Fixed(CameraTarget, CameraProjection, CameraFollowSettings)` 创建通用数据，交给
`OwnedCamera(String viewId, CameraSource source, Producer producer, Output output, Runnable checkThread, Runnable onClosed)`。
原有接受姿态 supplier 的构造器仍创建 Free Camera。
Player `ViewProvider.sample(float partialTick)` 提供原生摄像机快照；实际输出 producer 由宿主实现。

`sampleAvailablePose(partialTick)` 返回 Optional，供宿主在固定目标不可用时跳过渲染；
`samplePose(partialTick)` 要求本帧有姿态。只对 Free 推进动画 tick，目标采样不推进模拟时间。
`Producer.renderSettings/updateRenderSettings` 是宿主配置扩展点。暂停、故障、关闭与资源所有权
遵循 [多机位生命周期](offline_rendering_and_multi_cam.md)。

## 验证入口

```sh
./gradlew :modules:modelling:modelUnitTest
./gradlew :modules:modelling:runClientHeadless -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=camera-types -PkasugaTestCameraTypes=true
```

在隔离目录预备测试世界副本。客户端成功标识为 `CAMERA_TYPES_SMOKE_PASS`，报告在
`debug/camera-types.json`，覆盖原生主输出共享和暂停、Free 投影、实体插值、目标恢复、
回调修改配置后的 session 重建、实际世界纹理和 GL 错误检查。JVM 测试覆盖类型控制边界、
double 精度、偏移/朝向、投影保留目标以及失败释放。
