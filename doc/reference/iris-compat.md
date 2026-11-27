# Iris 兼容层参考

`modules/iris-compat` 将对 Iris 与 Sodium 内部类、顶点格式、framebuffer 和 mixin 的直接依赖隔离在独立子项目中，使 `modules/modelling` 在未安装 Iris 或 Iris/Sodium ABI 发生变化时仍能安全加载并回退到原生渲染。

## 模块隔离与打包方式

- `modules/iris-compat/build.gradle` 仅以 `compileOnly` 依赖 `:modules:modelling`（只消费编译类输出）、`:modules:core`、`:modules:inject`、`:modules:utils`，以及 `iris-neoforge-mc1.21.1-1.8.12.jar` 与 `sodium-neoforge-mc1.21.1-0.6.13.jar`。
- `modules/modelling/build.gradle` 在构建期把 `:modules:iris-compat` 的 `main` 输出合并进自身 `jar`、`shadowJar` 与开发运行时的 `neoForge.mods`；`modules/library/build.gradle` 通过 `useModProject(project(':modules:iris-compat'))` 收纳该模块。因此 `modules/modelling` 的主编译类路径不直接引用 Iris 类型。

## 运行时桥接与能力探测

`IrisCompat`（位于 `modules/modelling`）在客户端资源重载监听器注册阶段调用 `IrisCompat.onStart()`：

1. 检查 `ModList.get().getModContainerById("iris")`。
2. 存在 Iris 时，通过 `CompatCapability<IrisIntegration>`（`lib.kasuga.core.compat.CompatCapability`）按类名反射实例化 `lib.kasuga.compat.iris.IrisRenderingIntegration`。
3. 探测失败（类缺失、构造失败、`LinkageError` 或权限/访问异常）时记录告警日志，保留 `adapter == null`，不中断客户端启动。

### `IrisRenderingIntegration`

`lib.kasuga.compat.iris.IrisRenderingIntegration` 实现 `IrisIntegration` 接口，是 `modules/iris-compat` 对外暴露的统一适配入口：

| 方法 | 职责与回退行为 |
| --- | --- |
| `registerPbrLoader()` | 通过 `CompatCapability<KasugaPBRLoader>` 探测 Iris PBR 内部类/方法并向 `PBRTextureLoaderRegistry` 注册 `KasugaTextureAtlas` loader（对接 `CombinedTextureManager` 的法线与高光图集）；失败时仅禁用 Iris PBR，打印告警。 |
| `isUsingShaderPack()` | 调用 `IrisApi.getInstance()::isShaderPackInUse`；若抛出 `LinkageError`，置位 `packQueryFailed` 并固定返回 `false` 回退到原生模型渲染。 |
| `supportsModelRendering()` | 通过 `CompatCapability<Boolean>` 缓存 `IrisModelFormats.probe()` 结果；失败时仅警告一次并返回 `false`。 |
| `compatibleFormat(format, force)` | 转发 `IrisModelFormats.compatibleFormat(format, force)`。 |
| `createVertexBuffer(...)` | 构造 `IrisVertexBuffer`。 |
| `framebufferUnavailable()` / `bindWorldFramebuffer()` | 转发 `IrisWorldFramebuffer`。 |
| `openCamera(shader)` | 先检查 `IrisCameraMixinPlugin.supportsCamera()`；ABI 满足时创建 `CameraIrisSession`，否则抛出 `IOException`。 |
| `closeCameraBuffers(source)` | 调用 `CameraIrisNativeBuffers.close(source)` 释放 Iris `MemoryTrackingBuffer`；遇 `LinkageError` 置位 `cameraBuffersUnavailable` 并告警。 |

## 顶点格式、顶点缓冲与 World Framebuffer

### `IrisModelFormats`

`lib.kasuga.compat.iris.IrisModelFormats` 负责探测并映射 Iris 扩展顶点格式：

- `probe()` 使用 `MethodHandles.lookup()` 与 `CompatCapability.requireMethod(...)` 校验 `ImmediateState.skipExtension`、`IrisVertexFormats`（`TERRAIN`、`ENTITY`、`GLYPH`、`MID_TEXTURE_ELEMENT`、`TANGENT_ELEMENT`、`ENTITY_ID_ELEMENT`）、`CapturedRenderingState.INSTANCE`（及 `getCurrentRenderedEntity`、`getCurrentRenderedBlockEntity`、`getCurrentRenderedItem`）与 `Iris.isPackInUseQuick()`。
- `compatibleFormat(format, force)` 在启用光影包且未跳过扩展（或 `force == true`）时将原版格式映射为 Iris 格式：
  - `DefaultVertexFormat.NEW_ENTITY` → `IrisVertexFormats.ENTITY`
  - `DefaultVertexFormat.BLOCK` → `IrisVertexFormats.TERRAIN`
  - `DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP` → `IrisVertexFormats.GLYPH`

### `IrisVertexBuffer`

`lib.kasuga.compat.iris.vertex.IrisVertexBuffer` 实现 `IVertexBuffer`，将 `FlatModelData` 打包进 Iris 扩展顶点格式并由 `FencedVertexBuffer`（upload ring）管理 GPU 缓冲：

- 填充期间将 `ImmediateState.skipExtension` 临时设为 `true`，使 `BufferBuilder` 直接写入已扩展顶点格式；当 `vertexCount >= multiThreadedThreshold`（`BackendInstance` 默认传 `10000`）时，通过传入的 `ExecutorService` 分块并行构建再复制到 `packedVertices`。
- 额外填充三项 Iris 顶点属性：`MID_TEXTURE_ELEMENT`（由 `midpointUv` 按图元顶点数计算均值 UV）、`TANGENT_ELEMENT`（将切线归一化为 4 个有符号字节，缺少切线时使用 `(1, 0, 0, 1)`）与 `ENTITY_ID_ELEMENT`（读取 `CapturedRenderingState.INSTANCE`）。
- `updateGpuBuffer(dirtyVertices, forceUploadAll)` 在脏顶点比例低于 75% 时按 `maxMergeGap` 合并脏区间做局部更新，达到或超过 75% 时走全量重新打包上传。

### `IrisWorldFramebuffer`

`lib.kasuga.compat.iris.IrisWorldFramebuffer` 为加权透明混合（WBOIT）提供 Iris world framebuffer 绑定能力：

- 通过 `CompatCapability<BooleanSupplier>` 在链接期绑定 `Iris::getPipelineManager`、`PipelineManager::getPipelineNullable`、`IrisRenderingPipeline::bindDefault` 与 `IrisApi.getInstance()::isRenderingShadowPass`。
- 处于 shadow pass（`isRenderingShadowPass() == true`）或无活跃 `WorldRenderingPipeline` 时返回 `false`。
- 若探测失败、运行时出现 `LinkageError`，或活跃管线不是 `IrisRenderingPipeline`，会置位 `framebufferUnavailable = true` 并调用 `IrisOitCompat.reportFallback(...)` 降级到排序半透明路径。OIT 整体回退规则见 [模型材质 Alpha 渲染参考](render-alpha-passes.md#irisoitcompat-与-compatcapability-回退路径)。

## 多机位隔离会话与 Mixin ABI 门控

### `CameraIrisSession`

`lib.kasuga.rendering.output.mc.iris.CameraIrisSession` 实现 `CameraShaderSession`，为多机位渲染的每个机位提供独立的光影状态（详见 [多机位输出契约](multi-camera-output.md) 与 [离屏、多机位与共享输出原理](../explanation/offline-rendering-and-multi-cam.md)）：

- 若 `CameraRenderSettings.Shader.pack()` 为空，为该机位创建使用 `VanillaRenderingPipeline` 的 `PipelineManager`；否则从目录或 ZIP（通过 `FileSystems.newFileSystem` 按路径打开独立文件系统）解析 `shaders/` 并构建独立的 `ShaderPack` 与 `IrisRenderingPipeline`。
- 每个会话持有独立的 `WorldRenderingSettings`、`CapturedRenderingState`、`SystemTimeUniforms.Timer`、`SystemTimeUniforms.FrameCounter` 与 `CameraIrisShadowState`（保存/恢复 `ShadowRenderer` 与 `ImmediateState` 的全局静态字段）。
- 在渲染线程调用 `enter()` 返回 `Scope`，切换线程本地状态并清理 `ProgramUniforms.clearActiveUniforms()`；`Scope.beginFrame()` 推进本机位时间/帧计数；`finish()` 调 `finalizeGameRendering()`；`close()` 在所属会话作用域内执行 `pipelines.destroyPipeline()` 并关闭归档文件系统。
- `CameraIrisSession.useMaterials(WorldRenderingSettings)` 用于 Sodium 异步区块构建工作线程，在不修改进程级单例的前提下切换当前线程的材质表。

### `kasuga_lib_iris.mixins.json` 与 `IrisCameraMixinPlugin`

`modules/iris-compat/src/main/resources/kasuga_lib_iris.mixins.json` 将 `"required"` 设为 `false`，指定插件 `lib.kasuga.compat.iris.IrisCameraMixinPlugin` 与包 `lib.kasuga.compat.iris.mixin`，`"mixins"` 列表留空，由插件在启动期按字节码探测结果动态决定注入哪些 mixin：

- 仅在 `MixinEnvironment.Side.CLIENT` 下运行；首先通过 `IrisCameraMixinPlugin.supportsCamera()` 读取类字节码（`ClassReader.SKIP_CODE | SKIP_DEBUG | SKIP_FRAMES`）逐项核对 Iris 与 Sodium 目标类/字段/方法描述符：
  - Iris：`Iris`、`CapturedRenderingState`、`SystemTimeUniforms`、`SystemTimeUniforms$FrameCounter`、`ShadowRenderer`、`ImmediateState`、`WorldRenderingSettings`、`PipelineManager`、`WorldRenderingPipeline`、`IrisRenderingPipeline`、`VanillaRenderingPipeline`、`ShaderPack`、`StandardMacros`、`ProgramUniforms`。
  - Sodium：`ChunkJobTyped.execute(ChunkBuildContext)`。
- `supportsCamera()` 全部匹配时才返回 6 个核心 mixin：`WorldViewIrisMixin`、`WorldViewIrisStateAccessor`、`WorldViewIrisTimeAccessor`、`WorldViewIrisCounterAccessor`、`WorldViewIrisMaterialsMixin`、`WorldViewIrisChunkJobMixin`。
- 另以独立的 `hasCloudCameraAbi()` 检测 Iris 1.8.12 的 `MixinCloudRenderer.hadShadersOn` 与 Sodium `CloudRenderer.render` 签名；匹配时追加 `WorldViewIrisCloudMixin`（使该云 hook 不阻塞基础相机 ABI 门控）。
- 若任意 ABI 检查不通过，`getMixins()` 返回空列表，保证游戏正常启动；实际尝试打开带光影包的独立相机时由 `IrisRenderingIntegration.openCamera` 抛出 `IOException` 明确报错。

## 相关源码

| 组件 | 源码 |
| --- | --- |
| 兼容适配入口与接口 | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/compat/iris/{IrisCompat,IrisIntegration,IrisOitCompat}.java` |
| 通用能力探测工具 | `modules/core/src/main/java/lib/kasuga/core/compat/CompatCapability.java` |
| Iris 实现与顶点缓冲 | `modules/iris-compat/src/main/java/lib/kasuga/compat/iris/{IrisRenderingIntegration,IrisModelFormats,IrisWorldFramebuffer,KasugaPBRLoader,CameraIrisNativeBuffers}.java`、`vertex/IrisVertexBuffer.java` |
| 机位光影会话与 Mixin | `modules/iris-compat/src/main/java/lib/kasuga/rendering/output/mc/iris/{CameraIrisSession,CameraIrisShadowState}.java`、`modules/iris-compat/src/main/java/lib/kasuga/compat/iris/IrisCameraMixinPlugin.java`、`modules/iris-compat/src/main/resources/kasuga_lib_iris.mixins.json` |
