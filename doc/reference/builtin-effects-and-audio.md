# 内置效果、水体仿真与空间音频参考

本文档说明 `modules/modelling` 与 `modules/render-core` 内置的黑洞后处理、浅水自由表面仿真与光学渲染、空间音频播放，以及 `/kasuga_effects` 诊断与参数面板接口。托管效果管线和后处理图基础机制见 [托管效果与后处理](effects-and-postfx.md)。

## 1. 黑洞引力透镜后处理（`BlackHoleEffect` / `BlackHoleEffects`）

包：`lib.kasuga.rendering.effect.builtin.blackhole`（客户端启动时由 `Constants.onRegisterRenderPipelines` 调用 `BlackHoleEffects.initialize(RenderPipelineRegistrar)` 初始化）。

- 管线注册：注册基于 Java Shader DSL 生成的 fullscreen shader `kasuga_lib:black_hole`（`BlackHoleShaderProvider`），以及优先级 `1000` 的后处理图 `kasuga_lib:black_hole_graph`（含全屏尺寸 `LINEAR` 过滤及深度的托管目标 `kasuga_lib:black_hole_scene`）。`prepare` 阶段用视锥与屏幕投影过滤当前可见黑洞，无可见黑洞时跳过拷贝与合成 pass。
- 容量与排序：`BlackHoleEffects.MAX_VISIBLE_BLACK_HOLES = 8`。所有通过投影裁剪的黑洞按屏幕投影半径降序（同半径按 ID 升序）截取前 8 个，每个黑洞打包 `16` 个 float 写入 `HoleData` uniform 数组。
- 公开参数（`BlackHoleShaderProvider`）：暴露 `LensingScale`（默认 `1.0`，范围 `[0, 3]`）、`DiskBrightness`（默认 `1.0`，范围 `[0, 4]`）与 `ChromaticScale`（默认 `1.0`，范围 `[0, 3]`），可在 `/kasuga_effects` 的 `Parameters` 界面或 `/kasuga_effects parameter ...` 中实时调节。

### `BlackHoleEffect` 参数与默认值

不可变 record，通过 `BlackHoleEffect.builder(ResourceLocation id, Vec3 position)` 或 `toBuilder()` 构造：

| 字段 | 默认值 | 约束 / 说明 |
| --- | --- | --- |
| `eventHorizonRadius` | `1.0f` | `> 0`，事件视界世界半径 |
| `influenceRadius` | `4.0f` | `>= 1.0f`，相对 `eventHorizonRadius` 的影响半径倍率 |
| `distortionStrength` | `0.7f` | `>= 0` |
| `accretionRadius` | `1.65f` | `>= 1.0f`，相对 `eventHorizonRadius` 的吸积盘中心半径 |
| `accretionWidth` | `0.28f` | `> 0`，相对 `eventHorizonRadius` 的吸积盘半宽 |
| `glowStrength` | `1.3f` | `>= 0` |
| `chromaticAberration` | `0.025f` | `>= 0`，相对 `eventHorizonRadius` 的 RGB 色散强度 |
| `rotationSpeed` | `1.0f` | 有限值，吸积盘角速度倍率 |
| `glowColor` | `(1.0f, 0.32f, 0.06f)` | `BlackHoleEffect.Color(red, green, blue)`，各分量 `>= 0` |
| `depthTest` | `true` | 是否依据场景深度缓冲遮挡 |
| `accretionDiskTilt` | `1.0f` | `(0, 1]`，屏幕空间短轴/长轴比（`1` 为正视，接近 `0` 为侧视） |

### `BlackHoleEffects` 生命周期与命令

- `BlackHoleEffects.put(effect)`：按 `effect.id()` 添加或替换黑洞。
- `BlackHoleEffects.register(effect)`：返回 `BlackHoleEffects.Registration`（`id()`、`effect()`、`update(BlackHoleEffect)`、`close()`）；旧句柄关闭时不会误删之后同 ID 的新注册。
- `BlackHoleEffects.remove(id)`、`clear()`、`size()`、`snapshot()`、`shader()`。切换或退出世界时在 `ClientTickEvent.Post` 自动清空。
- 开发客户端命令（`contentTesting` 的 `BlackHoleDemo`，默认 `AUTO_SPAWN = false`）：

| 命令 | 说明 |
| --- | --- |
| `/kasuga_black_hole demo` | 在玩家视线前方生成一个单体黑洞示例 |
| `/kasuga_black_hole orbit` | 启动三体互绕黑洞示例 |
| `/kasuga_black_hole massive` | 生成大质量倾斜吸积盘黑洞示例 |
| `/kasuga_black_hole clear` | 清除示例黑洞 |
| `/kasuga_black_hole status` | 查看 `BlackHoleEffects.shader()` 状态、generation、当前数量与演示模式 |

## 2. 浅水自由表面仿真与水体后处理（`WaterEffects` / `WaterSurface` / `ShallowWaterGrid` / `WaterOptics`）

### 纯 CPU 浅水网格与光学参数（`modules/render-core`）

包：`lib.kasuga.rendering.water`，无图形或 Minecraft 宿主依赖，单线程所有权：

- `ShallowWaterGrid(int size, float cellSize, float restDepth)`：在平坦河床上以守恒量 `(h, hu, hv)` 进行二维 Rusanov 有限体积求解（`size` 须在 `8..512`，`cellSize` 与 `restDepth` 须正有限数，`GRAVITY = 9.81f`，周期边界，自适应 CFL 子步，阻尼系数 `exp(-0.16 * dt)`）。
  - 核心方法：`displace(x, z, radius, amount)`（保持总体积守恒的中心下凹与外环隆起）、`impulse(x, z, radius, vx, vz)`、`step(float seconds)`（`[0, 1]` 秒）、`shift(cellsX, cellsZ)`（移动窗口同时保留世界坐标波浪，新边缘设为静水）、`height(x, z)`、`velocityX(x, z)`、`velocityZ(x, z)`、`cellDepth(x, z)`、`extent()`、`volume()`、`minimumDepth()`、`maximumDeviation()`、`isFinite()`、`reset()`。该深度平均模型不模拟翻卷浪或飞溅粒子。
- `WaterOptics(absorptionRed, absorptionGreen, absorptionBlue, scatterRed, scatterGreen, scatterBlue, shorelineFoam)`：各吸收系数范围 `[0, 20]`，散射与岸边泡沫范围 `[0, 1]`；内置预设 `WaterOptics.DEFAULT` 与 `WaterOptics.CLEAR_COAST`。

### 客户端水体后处理管线（`modules/modelling`）

包：`lib.kasuga.rendering.effect.builtin.water`（由 `WaterEffects.initialize` 注册资源 shader `kasuga_lib:water/clear_surface` 与优先级 `800` 的后处理图 `kasuga_lib:water/graph`）：

- `WaterEffects.open(ClientLevel level, double centerX, float bedY, double centerZ, int resolution, float cellSize, float depth)`：为指定 `ClientLevel` 创建唯一的 `WaterSurface`（每个 level 最多一个活跃水面；需 `WaterEffects.ready()`）。`ClientTickEvent.Post` 在游戏未暂停时以固定 `0.05f` 秒步进模拟，切换世界时自动关闭；最后一个水面移除后通过 `PostProcessTargetPool` 释放图托管目标。
- `WaterSurface`（`AutoCloseable`）支持链式配置：
  - `followPlayer(boolean)` / `playerInteraction(boolean)`（默认 `true`，开启时结合脚步与移动速度产生水波与轻微水阻力，并在玩家超出中心 `8` 个网格单元时滑动 `ShallowWaterGrid` 窗口）。
  - `renderEnabled(boolean)`（默认 `true`，可暂时隐藏光学渲染同时保留模拟状态）。
  - `optics(WaterOptics)` 与 `terrainHeight(DoubleBinaryOperator)`（提供世界坐标地面高度，用于判定涉水；默认平底 `bedY`）。
  - `interact(Vec3 position, Vec3 velocity, float radius, float displacement)`：在 `±0.45 * extent()` 范围内向模拟网格施加排开与水平动量冲量。
  - 将高度差 `(cellDepth - restDepth + 0.25) * 2` 编码为 16 位值写入 `DynamicTexture`（`HeightSampler`），经全屏折射、Beer-Lambert 吸收与 Fresnel 天空反射合成回主目标。
- `contentTesting` 提供 `/kasuga_cobalt_blue [stop]` 与 `/ksglib demo cobalt_blue [stop]`（需私有单人世界、已启用水体 shader、Box3D 原生物理及生成好的 `assets/kasuga_demo/cobalt_blue/track.json` 音频资源）。

## 3. 场景曲目与空间音频（`SceneTrack` / `SpatialAudio`）

包：`lib.kasuga.audio`（仅限客户端 `Dist.CLIENT`）：

- `SceneTrack(String title, String artist, String album, float durationSeconds, ResourceLocation sound, ResourceLocation audioFile)`：
  - `SceneTrack.load(ResourceLocation metadata)` 从客户端 `ResourceManager` 读取 JSON 元数据（键名：`title`、`artist`、`album`、`duration_seconds`、`sound`、`audio_file`）。
  - `available()` 同时检查 `BuiltInRegistries.SOUND_EVENT.containsKey(sound)` 与 `audioFile` 资源是否存在。
  - `playAt(Vec3 position, float gain)` 以 `SoundSource.RECORDS` 在指定世界位置创建空间音源（资源缺失时抛 `IllegalStateException`）。
- `SpatialAudio`：
  - `SpatialAudio.play(SoundEvent sound, SoundSource category, Vec3 position, float gain)` 要求有限位置与 `gain > 0`，返回 `SpatialAudio.Voice`（继承 `AbstractTickableSoundInstance` 并实现 `AutoCloseable`，默认 `relative = false`、`looping = false`、`Attenuation.LINEAR`）。流式读取与单声道空间化特性由注册的 Ogg 资源决定。
  - `SpatialAudio.activeCount()` 返回活跃实例数；`ClientTickEvent.Post` 在切换世界或音频停止时自动清理。
  - `SpatialAudio.Voice` 提供 `seconds()`、`isPlaying()`、`failedToStart()`（`ticks > 60 && !observedPlaying`）、`position()`、`spatial()`（`!relative`）与 `close()`。

## 4. 效果诊断、Shader 参数界面与 `/kasuga_effects` CLI

包：`lib.kasuga.rendering.effect.debug`（命令在 `Constants.onRegisterClientCommands` 注册）：

- **客户端命令树 `/kasuga_effects`**：
  - `/kasuga_effects`：打开非暂停客户端诊断界面 `EffectDiagnosticsScreen`（Title 为 `"Kasuga Effect Inspector"`，每 `10` tick 自动刷新）。
  - `/kasuga_effects preload`：调用 `RenderShaderRegistry.preloadPending()` 将待编译 shader 入队预加载。
  - `/kasuga_effects workers <count>`：调用 `ShaderPreparationScheduler.configureWorkers(count)` 调整后台准备线程数（`0` 为自动）。
  - `ShaderParameterCommands` 子命令：
    - `/kasuga_effects parameter list <shader>`
    - `/kasuga_effects parameter set <shader> <name> <values...>`（布尔参数接受 `true`/`false`，向量/颜色/矩阵分量可用空格或逗号分隔）
    - `/kasuga_effects parameter reset <shader> <name>`
- **`EffectDiagnosticsScreen`**：包含 `Effects`（活跃与可见实例数、最近渲染耗时）、`Shaders`（源码类型、状态、策略、优先级、来源、generation、队列位置、准备与编译耗时、暴露参数数、错误信息及线程/帧预算摘要）与 `Pipelines`（阶段、优先级、图元模式、缓存变体数）三个页签，以及 `Parameters`（跳转到 `ShaderParameterScreen`）、`Preload pending`、`Done` 按钮。
- **`ShaderParameterScreen`**：非暂停通用参数编辑器，自动枚举带非空参数 schema 的已注册 shader（支持 `< Prev` / `Next >` 切换、`Reset` / `Reset all`、布尔开关按钮与按分量命名 `Value` / `XYZW` / `RGBA` / `Mij` 的滑块）。修改直接写入 `RenderShaderHandle.parameters()`，下次绘制立即生效而无需重编译，并由 `ShaderParameterPersistence` 异步保存到 `config/kasuga_lib/shader-parameters.json`。

## 相关源码

| 范围 | 源码 |
| --- | --- |
| 黑洞后处理 | `modules/modelling/src/main/java/lib/kasuga/rendering/effect/builtin/blackhole/{BlackHoleEffect,BlackHoleEffects,BlackHoleShaderProvider}.java` |
| 浅水网格与光学参数 | `modules/render-core/src/main/java/lib/kasuga/rendering/water/{ShallowWaterGrid,WaterOptics}.java` |
| 水体后处理与演示 | `modules/modelling/src/main/java/lib/kasuga/rendering/effect/builtin/water/{WaterEffects,WaterSurface}.java`、`modules/modelling/src/contentTesting/java/test/kasuga/modelling/demo/cobalt/CobaltBlueDemo.java` |
| 场景曲目与空间音频 | `modules/modelling/src/main/java/lib/kasuga/audio/{SceneTrack,SpatialAudio}.java` |
| 诊断与参数面板/命令 | `modules/modelling/src/main/java/lib/kasuga/rendering/effect/debug/{EffectDiagnosticsScreen,ShaderParameterScreen,ShaderParameterCommands}.java`、`modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/Constants.java` |
