# 扩展点目录

本页汇总 KasugaLibNext 各模块面向接入模组与引擎扩展方的公开 SPI、注册表、工厂、Handler 与管线挂接点，并标注注册时机、生命周期与注销契约。

---

## 1. DI、配置与基础注册（`modules/inject`、`modules/core`）

| 扩展点（全限定名） | 机制 / 注册入口 | 触发与生效时机 | 所有权与注销 |
| --- | --- | --- | --- |
| `lib.kasuga.inject.auto_configure.Configurable` | `@Context` / `@Singleton` Bean 实现接口并覆写 `configureClient` / `configureServer` / `configureCommon` / `configureStartup` | `ModApplicationContext.init` 内于 `context.start()` 后收集并注册非空 `ModConfigSpec` | 进程生命周期；受 `@BeanOnlyIn`、`@CreateModBeans` 等条件过滤 |
| `lib.kasuga.inject.class_loader.MinecraftDist.ModLoaded` | 继承抽象 `Condition` 类并配合 `@Requires(condition = ...)` 控制 Micronaut Bean 启用 | `ApplicationContext` 解析 Bean 定义时评估 | 无状态条件类 |
| `lib.kasuga.inject.auto_configure.Saved<T>` / `SavedFactory` | 构造器或字段注入 `Saved<MySavedData>`（目标类需提供无参构造器及 `(CompoundTag)` / `(CompoundTag, HolderLookup.Provider)` / `(HolderLookup.Provider, CompoundTag)` 构造器或静态加载方法） | 自动挂载到 `ServerStartedEvent` / `ServerStoppingEvent`，按维度懒加载 `SavedData` | 服务端世界生命周期 |
| `lib.kasuga.inject.mixins.ServiceMixinPlugin` | 继承该 `IMixinConfigPlugin` 基类，在 Mixin JSON 中配置 `"plugin"` | Mixin 载入阶段 | 类加载生命周期 |
| `lib.kasuga.registration.core.RegisterContextRegistry` | `register(Side, RegistryDispatchFunction)` 与 `registerBefore(Side, RegistrationStage<?>, RegistryStageDispatchFunction)` | `COMMON` / `CLIENT` 注册器配置及对应 `RegistrationStage` 派发前 | 随 `ApplicationContext` 存活；用于挂接模组自定义阶段前导逻辑 |
| `lib.kasuga.registration.stages.RegistrationStage<T>` | 声明新的静态令牌 `new RegistrationStage<>()`，通过 `RegisterContextRegistry` 派发并在 `Reg.register` 中调用 `context.onStage(stage, consumer)` | 自定义事件或阶段触发时 | 按对象身份（`equals`）匹配 |
| `lib.kasuga.registration.Registry`（附件） | `getOrCreateAttachment(Class<T>, Supplier)` / `setAttachment` / `getAttachment` / `removeAttachment` | 注册期共享每模组状态（如 `CreateRegistrationState`、`CreateClientBridge`） | 归属对应 `Registry` 实例 |
| `lib.kasuga.registration.beans.rendering.RenderingRegistry` | 实现接口并标注 `@Replaces(DefaultRenderingRegistry.class)`（例如客户端 `@BeanOnlyIn.Client`） | 注册表派发 `BAKING_COMPLETE` 时调用 | Micronaut 单例 |
| `lib.kasuga.core.rendering.SpecialRenderingRegistry` | `SpecialRenderingRegistry.register(Item, ISpecialItemRenderer)` | 客户端第一人称 / 手持物品渲染钩子（`MixinItemInHandRenderer`）触发 | 全局静态映射 |
| `lib.kasuga.content.device.DeviceRegistries` | 使用 `DEVICE_ATTRIBUTE_REG` / `DEVICE_REG` 注册设备属性与设备类型 | `NEW_REGISTRY` / `REGISTER_EVENT` | MC 自定义同步注册表 |
| `lib.kasuga.content.document.DocumentComponentRegistries` | 使用 `DOCUMENT_COMPONENT_REGISTRY_REG` 与 `DocumentComponentReg` | `NEW_REGISTRY` / `REGISTER_EVENT` / `BAKING_COMPLETE` | MC 自定义同步注册表 |

---

## 2. 统一资源平台、VFS 与客户端钩子（`modules/core`）

| 扩展点（全限定名） | 注册 / 实现入口 | 时机与用途 | 所有权与注销 |
| --- | --- | --- | --- |
| `lib.kasuga.core.resource.ResourceSystem` | `registerConsumer(ScopedResourceManagerConsumer)`；`createServerResourceScope` / `createClientResourceScope` / `createProcessResourceScope`；`mountScoped` / `mountClientScoped` | 客户端设置、服务端启动、资源重载与停机通知 | 托管 `AutoCloseable` 与 `ResourceCoordinator` 作用域随对应服务端 / 客户端 / 进程关闭自动释放 |
| `lib.kasuga.core.resource.lifecycle.ResourceEntryPoint` | `coordinator.register(ResourceEntryPoint)`；声明 `EntryPointDescriptor`（`events`、`sides`、`inputTypes`、`dependencies`、`reloadable`）与 `prepare(...)` | `ResourceCoordinator.reload(...)` 按依赖拓扑序异步准备，随后在发布线程执行 `PreparedConsumerBatch.publish()` | 上一代随新 `ResourceGeneration` 发布调用 `retire()` / `close()` |
| `lib.kasuga.core.resource.source.ResourceSource` | 实现 `capture()` 返回不可变 `ResourceSnapshot` | `ResourceCoordinator.reload(...)` 准备阶段 | 快照由 `ResourceGeneration` 关闭 |
| `lib.kasuga.core.vfs.VirtualFileSystem` | 实现只读 / 可写 VFS 后端并通过 `MountTableVfs.mount` 或 `ResourceSystem.mountScoped` 挂载 | 任意时间（支持 `VfsWatchService` 轮询外部磁盘变更） | 返回 `VfsSubscription`，支持显式 `close()` 或交由作用域回收 |
| `lib.kasuga.client.ClientBlockUpdateHooks` | `ClientBlockUpdateHooks.addListener(Consumer<Update>)` | 客户端 `ClientLevel#sendBlockUpdated` 主线程同步回调 | 返回 `AutoCloseable` 移除句柄 |
| `lib.kasuga.core.compat.CompatCapability<T>` | 创建静态探测器 `new CompatCapability<>(name, probe)`，使用 `requireClass` / `requireMethod` 做 ABI 校验 | 首次调用 `isAvailable()` / `result()` 时懒执行并缓存结果 | 无状态不可变缓存 |

---

## 3. 数据驱动构建、属性解析与重载（`modules/data-driven`、`modules/core`）

| 扩展点（全限定名） | 注册 / 构造入口 | 阶段与作用域 | 所有权与注销 |
| --- | --- | --- | --- |
| `lib.kasuga.registration.data_driven.DataDrivenRegistrationProviders` | `install(hostModId, DataDrivenRegistrationProvider)` | 注册期替换默认 mod JAR 扫描（`JsonTreeIntegration` 先查此处，未安装再回退 `JsonTreeBuilder.buildForMod`） | 返回 `Attachment`（`AutoCloseable`），同一 `hostModId` 重复安装会抛异常 |
| `lib.kasuga.registration.data_driven.pipeline.DataDrivenBuildHooks` | `registerFinalizer(hostModId, BuildFinalizer)` | `DataDrivenBuildPipeline.build(...)` 在全部 `TypeHandler.apply` 完成后执行 `Stage.FINALIZE` | 进程级按 `hostModId` 列表追加 |
| `lib.kasuga.registration.data_driven.pipeline.DataDrivenBuildPipeline` | 传入自定义 `TypeHandler<?>`、`BuildFinalizer`、`RegistrationTarget`、`ContentSource`（通过 `ContentSourceFactory` 打开目录/ZIP/ModJar/快照）与 `BuildOptions`（`LEGACY` / `STRICT`） | 宿主编排多来源准备（`prepare`）与构建（`build`） | 会话级对象；`HandlerCapability` 含 `STRICT_PREFLIGHT`、`NON_REGISTRATION`、`REGISTRY_CLAIMS`、`OVERLAY` |
| `lib.kasuga.registration.data_driven.TypeHandler<T>` / `TypeHandlerRegistry` | `TypeHandlerRegistry.register(TypeHandler<T>)` | 注册期按 `getPhase()`（`PHASE_GROUPS = 0`、`PHASE_CONTENT = 1`、`PHASE_EMBEDDED = 2`）执行；支持内嵌类型（`getParentTypeName()` / `extractEmbedded`） | 进程级表（按 `typeName` 幂等覆盖） |
| `lib.kasuga.registration.data_driven.reload.ReloadHandler<T>` / `ReloadHandlerRegistry` | `ReloadHandlerRegistry.register(ReloadHandler<?>)` | reload 期由 `ReloadOrchestrator` 按注册顺序执行 `clearBucket() → decode() → DuplicateIdResolver → register() → afterReload()`；支持声明 `globDirectories()` | 进程级链表映射（同 `typeName` 原位替换；测试可用 `clearForTest()`） |
| `lib.kasuga.registration.factory.FactoryRegistry` & `StatefulBlockFactory` | `register` / `registerItem` / `registerBlockEntity` / `registerGeneric` | `BlockTypeHandler` / `ItemTypeHandler` / `BlockEntityTypeHandler` 解析 JSON `type` 键时调用；方块工厂实现 `StatefulBlockFactory` 可接收 `StatePropertySet` | 进程级并发映射（内置含 `proxy_model` / `proxy_model_be`） |
| `lib.kasuga.registration.data_driven.property.JsonPropertyParser` | `JsonPropertyParser.getInstance().registerCompiler(...)` / `registerStrictCompiler(...)` | 解析或严格预检方块 JSON `properties` 对象时匹配 | 进程级单例列表 / 映射 |
| `lib.kasuga.registration.data_driven.property.JsonItemParser` | `JsonItemParser.INSTANCE.registerParser(...)` / `registerStrictParser(...)` | 解析或严格预检物品 JSON `properties` 对象时匹配 | 进程级单例映射 |

详细参数与校验契约见 [数据驱动扩展 API](data-driven/api-extension.md) 与 [数据驱动重载 API](data-driven/api-reload.md)。

---

## 4. 模型、骨骼动画、物理与 Proxy（`modules/render-core`、`modules/render-opengl`、`modules/modelling`）

| 扩展点（全限定名） | 注册 / 实现入口 | 用途与合约 | 所有权与注销 |
| --- | --- | --- | --- |
| `lib.kasuga.rendering.models.mc.registry.PipelineRegistry` | `register(String id, ModelPipeLine)`、`registerRoute(KasugaPipeLineRouter, String ext, String id)` | 注册自定义模型格式管线（内置路由包含 `.geo.json`、`.obj`、`.mmd.zip`、`.glb`/`.gltf`、`.json`、`.bbmodel`、`.mtb`/`.fmtb`、`.bob`/`.beo`/`.fmf` 与 `assembly`） | 同一 `id` 注册不同管线实例将抛出异常 |
| `lib.kasuga.rendering.models.mc.source.model.KasugaPipeLineRouter` | `register(Predicate, Supplier)`、`registerByExtension`、`registerByPathPattern` | 将扫描到的 `ResourceLocation` 路由到对应 `ModelPipeLine` | 归属客户端 `KasugaModelManager` |
| `lib.kasuga.rendering.models.uml.framework.render.*` | 实现 `RenderBackend`、`ModelGeometryAdapter`、`RenderableFactory`、`RenderContext` | 接入独立图形后端、几何适配、可渲染对象构建与帧渲染上下文 | 参见 [渲染后端接入参考](rendering-backends.md) 与 [UML 渲染 framework 与后端接口](uml-render-framework.md) |
| `lib.kasuga.rendering.models.uml.bridge.Bridge`、`lib.kasuga.rendering.models.uml.loaders.ModelLoader`、`lib.kasuga.rendering.models.uml.loaders.sources.Source` / `SourceManager` | 通过 `ModelPipeLine.Builder`（`withBridge`、`withLoader`、`withModelSource` / `withSidedSource`）接入并配合 `SourceManager.registerSource` | 接入自定义模型格式加载器、资产源与 `Bridge` 几何/渲染适配 | 由所属 `ModelPipeLine` 实例持有 |
| `lib.kasuga.rendering.models.uml.framework.buffer.UploadDevice` | 实现设备操作并传入 `UploadRing` / `GpuUploadRing` | 自定义缓冲分配、Fence 同步或 orphan 上传策略（例如 macOS TBO 专用 `MacTboUploadDevice`） | 由所属 `UploadRing.close()` 回收缓冲区与 Fence |
| `lib.kasuga.rendering.models.uml.dynamic.tick_loop.ModelTickLoop` | `addPreIk(id, module)`、`addPostIk(id, module)`、`addPostPhysics(id, module)` 或直接操作 `getPipeline()`（`ModelTickLoopModule`） | 在标准槽位 `kasuga:apply` / `kasuga:ik` / `kasuga:physics` / `kasuga:anchor` 之间插入自定义程序化姿态或物理控制模块 | 归属单个 `ModelInstance`，随 `tickLoop.destroy()` 销毁 |
| `lib.kasuga.rendering.models.uml.dynamic.physics.ModelPhysicsClusterManager.EnvironmentFactory` | `clusterManager.setEnvironmentFactory(EnvironmentFactory)` | 为动态拆分 / 合并的 `ModelPhysicsScene` 生成局部 `CollisionEnvironment` | 归属对应 `Cluster` 生命周期 |
| `lib.kasuga.rendering.models.mc.api.pbr.PbrConversionRegistry` | `PbrConversionRegistry.register(ResourceLocation id, int priority, PbrConversionRule)` | 覆写 PMX → PBR 自动转换参数（`priority` 越小越先执行；`PbrUserConfig` 以 `1000` 最后应用） | 返回 `PbrConversionRegistry.Registration`（`AutoCloseable`） |
| `lib.kasuga.rendering.models.mc.api.ModelLightingDefaults` | `ModelLightingDefaults.register(ResourceLocation model, ModelLightingMode)` | 为模型设定默认光照模式（`PBR` 或 `BLOCK`，默认 `PBR`） | 跨资源重载保留 |
| `lib.kasuga.rendering.models.mc.api.item.ItemModelRenderers` | `register(Item, ResourceLocation model, modelName, guiScale)` + 在 `REGISTER_CLIENT_EXTENSIONS` 注册 `ItemModelRenderers.clientExtensions()` | 将 `minecraft:builtin/entity` 物品接入共享 UML `ItemModelRenderer` | 进程级映射；参见 [物品模型渲染](item-model-renderers.md) |
| `lib.kasuga.rendering.models.mc.multiplexer.MultiplexerRegistry` | `register(ResourceLocation blockId, Multiplexer)` / `resolve(...)` | 按方块类型共享无状态变体选择器 | 参见 [Multiplexer 参考](multiplexer.md) |
| `lib.kasuga.rendering.models.uml.dynamic.proxy.ProxyRuntime` & `BlockEntityProxyDefinitions` | `ProxyRuntime.GLOBAL.register(ProxyDefinition)` 或 `BlockEntityProxyDefinitions.register(BlockEntityType, ResourceLocation, ProxyTemplate)` | 注册元素到动画 / 渲染 Port 的 Proxy 模板并按 `ProxyAttachToken` 管理会话 | 显式注册/注销；元素级单会话约束 |
| `lib.kasuga.rendering.models.mc.api.McModelAssemblies` | `register(ResourceLocation id, ModelAssemblyDefinition)` / `unregister` | 注册程序化模型组装配方（优先级高于资源包定义） | 参见 [Minecraft 穿搭资源与实例切换 API](minecraft-model-assembly.md) |

---

## 5. 自定义渲染、着色器、托管效果与帧输出（`modules/modelling`、`modules/render-core`）

> 说明：源码中并不存在单一的 `EffectPipeline` 类；托管渲染扩展围绕 `RegisterRenderPipelinesEvent`、`RenderPipelineRegistrar`（`RenderPipelineScope`）、`EffectRenderPipeline`、后处理与着色器注册表组织。诊断视图中的快照类型为 `ClientEffectRuntime.EffectPipelineSnapshot`。

| 扩展点（全限定名） | 注册入口 | 管线类型 / 职责 | 所有权与注销 |
| --- | --- | --- | --- |
| `lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent` | 在模组事件总线监听事件，调用 `event.registrar(owner)` 获取 `RenderPipelineRegistrar`（即子 `RenderPipelineScope`） | 为客户端生命周期建立带 `owner` 归属的作用域注册器，统一创建下方各类注册项 | 随 `CLIENT_RENDER_PIPELINES` 生命周期按逆序关闭 |
| `lib.kasuga.rendering.effect.RenderPipelineScope` | `RenderPipelineScope.create(owner)` / `scope.child(childOwner)`；调用 `shader`、`world`、`postProcess`、`graph`、`effects`、`particles` | 面向动态内容（如关卡、脚本、屏幕预览）的可关闭作用域注册器 | 调用 `scope.close()` 逆序关闭名下全部注册句柄 |
| `lib.kasuga.rendering.effect.WorldRenderPipelineRegistry` | `register(owner, RenderPipelineDescriptor, DuplicatePolicy, WorldRenderPipeline)` | 在指定 `RenderPhase`（含 `POST_PROCESS`、`AFTER_LEVEL`）按优先级挂载世界渲染 pass | 返回 `PipelineRegistration`（`AutoCloseable`） |
| `lib.kasuga.rendering.effect.EffectRenderPipeline<T>` | `EffectRenderPipeline.register(owner, descriptor, duplicatePolicy, sortBackToFront, EffectRenderer<T>)` | 托管可每 tick 更新、可见性筛选、可选后向排序的 `RenderEffect` 集合；`spawn(T)` 返回 `EffectHandle<T>`，线程安全 | 返回 `EffectRenderPipeline<T>`（实现 `PipelineRegistration`） |
| `lib.kasuga.rendering.effect.particle.ParticleRenderPipeline` | `ParticleRenderPipeline.register(owner, descriptor, duplicatePolicy, ParticleBatchRenderer)` | 托管自定义粒子批次渲染器 | 返回 `ParticleRenderPipeline` |
| `lib.kasuga.rendering.effect.post.PostProcessPipelineRegistry` | `register(owner, descriptor, duplicatePolicy, PostProcessPass)` | 在 `RenderPhase.POST_PROCESS` 注册全屏后处理 pass（自动管理 `PostProcessContext` 与主目标恢复） | 返回 `PipelineRegistration` |
| `lib.kasuga.rendering.effect.post.graph.PostProcessGraphRegistry` | `register(owner, descriptor, duplicatePolicy, PostProcessGraph)` | 注册经静态校验的多 pass / 多目标后处理图，按引用计数管理 `PostProcessTargetPool` | 返回 `PostProcessGraphRegistration` |
| `lib.kasuga.rendering.effect.shader.RenderShaderRegistry` | `register(owner, RenderShaderDescriptor / ShaderProgram, DuplicatePolicy, ShaderLoadListener)` | 注册资源版、自定义工厂（`RenderShaderFactory`）或 Java Shader DSL（`ShaderProgram`）着色器，返回稳定跨重载 `RenderShaderHandle` | 返回 `ShaderRegistration`（`close()` 使句柄失效并在渲染线程释放自有 `ShaderInstance`） |
| `lib.kasuga.rendering.output.mc.MinecraftWorldViews` | `register(viewId, Supplier<WorldCameraView>, ...)` / `registerWhenAvailable(...)` | 在主视角之前按序离屏渲染独立机位 | 返回 `MinecraftWorldViews.Registration`（必须在渲染线程注册/关闭） |
| `lib.kasuga.rendering.output.mc.MinecraftFrameOutputs` & `FrameOutputRouter` | `MinecraftFrameOutputs.open(viewId, FrameOutputMode, Consumer<OutputFrame<FrameTexture>>)` | 订阅任意机位或 `minecraft:main`（`MIRROR` / `OFFSCREEN_ONLY`）的最终合成只读帧 | 返回 `FrameOutputRouter.Registration`（必须在渲染线程注册/关闭） |

---

## 6. 脚本运行时与安全沙箱（`modules/scripting`）

| 扩展点（全限定名） | 注册 / 实现入口 | 用途与合约 | 所有权与注销 |
| --- | --- | --- | --- |
| `lib.kasuga.scripting.ScriptEngineRegistry` | `register(ResourceLocation, List<String> types, ScriptEngineType<?>, int priority)` | 注册脚本语言引擎实现（如 `kasuga_lib_javet:javet` 注册 `"javascript"`，优先级 `0`）；按优先级解析同名语言请求 | Micronaut `@Singleton` |
| `lib.kasuga.scripting.ScriptEngineType.Builder` | `scriptType(...)`、`resolver(ModuleResolver)`、`addFeature(EngineFeatureType<?>)`、`addGlobalApi(name, ...)` | 声明新引擎类型的模块解析器、特性（如 `SecurityEngineFeatureType`）与每次创建引擎时注入的全局 API（如 `FsmApiRegistration.install` 注册 `"Animator"` / `"AnimatorBuilder"`） | 不可变引擎描述符 |
| `lib.kasuga.scripting.module.BuiltinModuleRegistry` | `register(String moduleName, Supplier<Object>)` 或 `registerFactory(ScriptModuleFactory<M>)` | 向脚本 `require(...)` 暴露全局共享或每引擎实例隔离的内置模块（如 `TimerModule.FACTORY`、`FsmAutoTickModule.FACTORY`） | `@Context` 单例 |
| `lib.kasuga.scripting.security.SecurityConditionRegistry` & `SecurityCheckCondition` | 在引擎的 `SecurityEngineFeature.getConditionRegistry()` 注册条件实例，配合 `@Api(conditions = ...)` | 控制导出到脚本引擎的 Java 类 / 方法访问权限 | 随引擎实例 `SecurityEngineFeature` 生命周期 |
| `lib.kasuga.scripting.resource.ManagedScriptEntryPoint` | 向 `ResourceCoordinator` 注册 `new ManagedScriptEntryPoint(engines, trusted, authorized, accessFactory)` | 以隔离的 `ResourceGeneration`、独立 `PackageRegistry`、受信任策略与 `ScriptBlockRegistry` 托管脚本包并暴露异步 `ScriptTicket` | 随 `PreparedConsumerBatch` 退役停止对应 `ScriptThreadGroup` |

---

## 7. Create 集成扩展（`modules/create`）

| 扩展点（全限定名） | 注册 / 实现入口 | 用途与阶段 | 所有权与注销 |
| --- | --- | --- | --- |
| `lib.kasuga.create.registration.CreateRegistrationStages.PREPARE` | 将 `TrackMaterialReg`、`BogeySizeReg`、`BogeyBlockReg`、`BogeyGroupReg`、`TrackReg` 挂入模组 `Registry` | `CreateRegistrationDispatcher` 触发后，前四类通过 `CreateRegistrationEvent.submit(...)` 按 `CreateRegistrationEvent.Phase`（`TRACK_MATERIAL`、`BOGEY_SIZE`、`BOGEY_BLOCK`、`BOGEY_STYLE`）提交并统一执行；`TrackReg` 仅在此阶段捕获 `event.clientBridge()`（不调用 `submit`） | 归属模组 `Registry`（见 [生命周期与初始化顺序](lifecycle.md)） |
| `lib.kasuga.create.registration.edge_data.EdgeDataReg` & `lib.kasuga.create.registration.edge_point.TrackEdgePointReg` | 挂入模组 `Registry`，在核心 `RegistrationStage.REGISTER_EVENT` 直接注册 | 分别向 `EdgeExtraPayloadRegistry.register(...)` 与 `EdgePointType.register(...)` 注册自定义轨道边负载与边界点类型（不走 `PREPARE` / `CreateRegistrationEvent`） | 归属模组 `Registry` |
| `lib.kasuga.create.content.train.device.TrainDeviceRegistry` | `register(ResourceLocation, TrainDeviceSystemType<?>, boolean contextDevice)` | 注册列车车载系统类型（可选作为每辆列车的上下文设备自动实例化） | 静态全局映射（禁止重复 ID/Type） |
| `lib.kasuga.create.content.train.graph.EdgeExtraPayloadRegistry` | `register(ResourceLocation, EdgeExtraPayloadType<?>)` | 注册轨道图边（`TrackEdge`）自定义附加同步数据负载 | 静态全局映射 |
| `lib.kasuga.create.content.train.signal.BoundarySegmentRegistry` | `register(featureLocation, EdgePointType, Function<UUID, CustomTrackSegment>)`、`registerUpdateListener(...)` | 注册自定义轨道信号分区段边界与更新监听器 | 静态全局映射 |
| `lib.kasuga.create.client.registration.ClientBogeyRendererRegistry` | `@BeanOnlyIn.Client` 单例，`register(BogeyRendererHandle, Supplier<BogeyRenderer>, BogeyVisualizer)` | 注册客户端转向架渲染器与 Flywheel 可视化器 | 仅物理客户端生效 |
| `lib.kasuga.create.client.rendering.edge_point.EdgePointOverlayRendererRegistry` | 普通未注解实例辅助类；`register(EdgePointType<?>, EdgePointOverlayRenderer)` / `getRendererFor(...)` | 基于同步 `WeakHashMap` 存储轨道边界点叠加层渲染器；目前在 `modules/` 中尚无全局 / DI 单例及活动调用方接线（`EdgePointBlockEntityRenderer` 未调用） | 由调用方自行持有实例（当前无 DI 单例） |
| `lib.kasuga.create.interaction.ContraptionShapeProvider` | 在机械动力方块上实现接口 | 为 Create 机械动力交互射线检测提供自定义碰撞轮廓 | 随方块实例 |

## 相关页面

- [生命周期与初始化顺序](lifecycle.md)
- [配置项](configuration.md)
- [侧边模型](sidedness.md)
- [Kasuga 自定义渲染规范](effect-rendering.md)
- [数据驱动扩展 API](data-driven/api-extension.md)
- [渲染后端接入参考](rendering-backends.md)
