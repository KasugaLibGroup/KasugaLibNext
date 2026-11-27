# Core 运行时基础设施与内容子系统（modules/core）

本页记录 `modules/core` 中除资源平台、VFS 与注册树之外的运行时核心基础设施与内容子系统：

- 网络 RPC：`lib.kasuga.core.networking.rpc.*`
- 自定义存档与外部物品存储：`lib.kasuga.core.saved.*`
- 可选模组兼容探测：`lib.kasuga.core.compat.*`
- 编解码器与引用计数句柄：`lib.kasuga.core.codec.*`、`lib.kasuga.core.holder.*`
- 核心渲染与 GLSL 导入钩子：`lib.kasuga.core.rendering.*`
- 早期加载与客户端通知钩子：`lib.kasuga.early.*`、`lib.kasuga.client.*`
- 内容子系统：`lib.kasuga.content.{block,device,document,graph,qr}.*`

相关页面：[统一资源平台与 Script Ticket](resource-platform.md)、[虚拟文件系统（VFS）](vfs.md)、[Core 模组入口与注册系统](core-registration.md)。

---

## 1. 网络 RPC（`lib.kasuga.core.networking.rpc`）

`RpcApi` 在 `PayloadReg` 之上提供单向与请求-响应（`CompletableFuture`）网络调用封装；代码生成器从 `codeTemplate` 下的 `Rpc` 模板生成支持 0–10 个参数的 `lib.kasuga.core.networking.rpc.Rpc` 门面。

### 1.1 `RpcApi` 与单向调用 `WithOutResponse`

| 类 / 方法 | 签名与行为 |
|---|---|
| `new RpcApi<>(String name)` | 构造内部 `PayloadReg<>("rpc/" + name + "/request", ...)` |
| `executeOnServer()` / `executeOnClient()` | 绑定对应端侧处理器并标记可执行方向 |
| `runOnMainThread()` | 接收请求时通过 `context.enqueueWork(...)` 切换到主线程后再调用 `handle(...)` |
| `register(Reg<?, ?> parent)` | 将底层 `PayloadReg` 挂载到注册树（必须挂载到提供非 `"minecraft"` 命名空间的 `Registry`） |
| `call(T request)` / `call(T request, Player player)` | 当 `player instanceof ServerPlayer` 时走 `PacketDistributor.sendToPlayer(...)`（要求 `executeOnClient()`）；否则走 `PacketDistributor.sendToServer(...)`（要求 `executeOnServer()`），方向不匹配抛出 `IllegalStateException` |
| `WithOutResponse.call(T request, Player p)` | 单向调用包装，服务端/客户端覆写 `handleInstant(T, IPayloadContext)` |

### 1.2 请求-响应调用 `RpcApi.WithResponse<T, R, S>`

`WithResponse` 注册 3 个通道：`rpc/<name>/request`、`rpc/<name>/response` 与 `rpc/<name>/error`（`Error` 载体为 UTF-8 字符串消息）。

| API | 行为与约束 |
|---|---|
| `CompletableFuture<R> run(T request)` / `run(T request, Player player)` | 分配唯一 `VAR_LONG` 会话 ID（绑定 `accepts = player`），发送请求并返回异步 `CompletableFuture<R>` |
| `handleInstant(T request, IPayloadContext context)` / `CompletableFuture<R> handle(T, IPayloadContext)` | 接收方覆写其中一个；成功值或异常消息都会通过 `context.enqueueWork(() -> context.reply(...))` 回到主线程发送回包 |
| `setTimeout(Duration)` / `setTimeoutSeconds(long)` / `setTimeoutMillis(long)` | 默认超时 **30 秒**；`setTimeout(null)` 重置为 30 秒；`setTimeout(long)` 已 `@Deprecated` |
| `void tick()` | 驱动 `sessionManager.checkTimeout()` 清理超时会话并使 future 以 `RpcTimeoutException` 失败。**调用方必须在客户端/服务端 tick 中周期性调用 `tick()`**，否则未应答会话不会自动超时释放 |

### 1.3 会话管理与生成门面 `Rpc`

- **`RpcSessionManager<T>`**：使用 `AtomicLong` 会话 ID、`PriorityQueue<Session<T>>`（按截止时间戳排序）与 `HashMap<Long, Session<T>>`。通过 `saturatedAdd` 防止超大超时值溢出（钳制到 `Long.MAX_VALUE`）；在内部锁外完成 `CompletableFuture` 回调以防止重入死锁；校验应答方 `player` 与会话 `accepts` 是否匹配（`accepts == null` 时接受任意端回复）。
- **`IdentifiedRpcPacketType<T>`**：将 `VAR_LONG` 会话 ID 与用户 `StreamCodec<? super FriendlyByteBuf, T>` 组合为 `CustomPacketPayload`。
- **`RpcTimeoutException`**：继承 `Exception`，会话超时后作为 `CompletableFuture` 异常原因。
- **`Rpc`（代码生成门面）**：
  - `Rpc.wrap(name, func, c1..cn)` -> 返回 `RpcV0` .. `RpcV10<T1..Tn>`（继承 `WithOutResponse`，`call(Player, T1..Tn)`）。
  - `Rpc.wrap(name, func, responseCodec, c1..cn)` -> 返回 `RpcR0<R>` .. `RpcR10<R, T1..Tn>`（继承 `WithResponse`，`call(Player, T1..Tn)` 返回 `CompletableFuture<R>`）。

```java
// 出自 TestMyRpc（客户端调用服务端并等待整数返回值）
public static final Rpc.RpcR1<Integer, Integer> MY_RPC = Rpc.wrap(
        "test_rpc_with_response",
        (ctx, i1) -> 42,
        ByteBufCodecs.VAR_INT,
        ByteBufCodecs.VAR_INT
).executeOnServer().runOnMainThread().setTimeoutSeconds(10).register(registry);

// 调用端：
CompletableFuture<Integer> future = MY_RPC.call(null, 41);
// 周期性驱动超时：
MY_RPC.tick();
```

---

## 2. 自定义存档与外部物品存储（`lib.kasuga.core.saved`）

> 按维度托管的原版 `SavedData` 注入见 [Inject 依赖注入与环境装配](inject.md) 中的 `Saved<T>`；本包面向**世界根目录**自定义文件与基于 UUID 的外部物品数据绑定。

| 类型 | 作用域与核心 API |
|---|---|
| `SavedSystem` | `@Context` 单例；向 `KasugaLibApplication.REGISTRY` 注册持久且网络同步的组件 `ITEM_SAVE_ID`（`kasuga_lib:item_external_id`，`UUID`） |
| `CustomSavedData` | 自定义存档基类，提供无参构造与 `save(Path path, HolderLookup.Provider provider)` |
| `CustomSavedDataManager` | `@Singleton`，监听 `forgeEventBus` 的 `ServerStartingEvent`（`HIGHEST`）、`LevelEvent.Save`（`LOWEST`）与 `ServerStoppingEvent`（`LOWEST`）。通过 `MinecraftServerAccess` 获取世界根目录 `Path`；提供 `get(Class<T>, SimpleFactory<T>)` 与 `get(Class<T>, Factory<T>)` 按类缓存并在世界保存时落盘 |
| `ScopedNbtSavedData<T extends SavedData>` | 继承 `CustomSavedData`，在 `<worldDir>/<basePath>/` 下委托 Minecraft `DimensionDataStorage` 按字符串键读写 `.nbt` 文件。提供 `get(key)`、`computeIfAbsent(key)`、`remove(key)`（记入 `pendingRemove` 并在下次 `save` 删除磁盘 `.nbt`）、`getAllSavedNames()` |
| `ExternalSaveItem` | 继承 `Item`：用 `initialize(ItemStack, Consumer<UUID>)` 或 `initializeOutputs(ItemStack, LivingEntity, Consumer<UUID>)` 为物品栈写入唯一 `ITEM_SAVE_ID`，用 `getSaveId(ItemStack)` 读取 |
| `NbtDataStorage<T extends SavedData>` | 当前仅含 `cache` / `directory` / `provider` 字段构造器的轻量骨架类 |

---

## 3. 可选模组兼容探测（`lib.kasuga.core.compat`）

`CompatCapability<T>` 为可选模组集成（如 `modules/iris-compat` 对 Iris/Sodium 的 ABI 校验与 OIT 回退）提供惰性、双重检查锁定缓存的探测原语：

| API | 说明 |
|---|---|
| `new CompatCapability<>(String name, Probe<T> probe)` | `Probe.run()` 允许抛出 `ReflectiveOperationException` |
| `boolean isAvailable()` / `Result<T> result()` | 首次调用时执行一次探测并缓存不可变 `Result<T>(boolean available, T value, String reason, Throwable cause)` |
| `CompatCapability.requireClass(String className, ClassLoader loader)` | 使用 `Class.forName(className, false, loader)` 检查类是否存在而不触发静态初始化 |
| `CompatCapability.requireMethod(Class<?> owner, String methodName, Class<?> returnType, Class<?>... parameterTypes)` | 查找 public 方法并严格校验 `method.getReturnType() == returnType`，可捕获方法签名漂移 |

异常处理边界（由 `CompatCapabilityTest` 验证）：`ReflectiveOperationException`、`InaccessibleObjectException`、`LinkageError`、`SecurityException` 以及探测返回 `null` 均会记录为 `available == false`；而 `IllegalStateException` 等探测逻辑本身的应用程序异常会直接向外传播，不会被吞掉。

```java
CompatCapability<Method> api = new CompatCapability<>("iris_state", () ->
        CompatCapability.requireMethod(CapturedRenderingState.class, "getCurrentRenderedEntity", int.class));
if (api.isAvailable()) {
    // 安全使用 api.result().value()
}
```

---

## 4. 编解码器与生命周期句柄（`lib.kasuga.core.codec` / `lib.kasuga.core.holder`）

### 4.1 `KasugaCodec`

`KasugaCodec.mapFunc` 构造键依赖的动态值编解码 `StreamCodec<B, M>`（在 `DocumentComponentRegistries.DOCUMENT_COMPONENT_BYTE_BUF` 中用于按组件类型分发网络编解码）：

```java
public static <B extends ByteBuf, K, V, M extends Map<K, ? extends V>> StreamCodec<B, M> mapFunc(
        IntFunction<? extends M> factory,
        StreamCodec<? super B, K> keyCodec,
        Function<K, StreamCodec<? super B, ? extends V>> valueCodecFunction,
        int maxSize,
        boolean cacheValueCodecByKey
)
```

### 4.2 `Holder<T>` 与 `Provider<T>`

轻量级引用计数资源绑定接口（`Simple` 实现**非线程安全**）：

- `Provider.simple(Supplier<T> creator, Consumer<T> disposer)`：首个 `Holder` 挂载（`hold`）时创建实例；最后一个 `Holder` 释放（`release`）或调用 `dispose()` 时触发 `disposer` 并清空实例；提供 `getValue()` 与 `ifPresent(Consumer<T>)`。
- `Holder.simple(Consumer<T> applier, Consumer<T> disposer)`：`attach(Provider<T>)` 先从旧 `Provider` 分离，再持有并回调 `applier`；`detach()` 回调 `disposer` 并释放引用。

---

## 5. 核心渲染与 GLSL 导入钩子（`lib.kasuga.core.rendering`）

| 类型 | Mixin / 调用机制 | 公开 API 与行为 |
|---|---|---|
| `ShaderFunctionImport` | `MixinGlslPreprocessor` 重定向 `GlslPreprocessor#processImports` | 对 `kasuga_lib:<path>` 导入映射至 `assets/kasuga_lib/shaders/<path>`，`ShaderFunctionImport.read(Reader)` 跳过前导 `in` / `out` / `uniform` 声明并在 `void main(` 处截断，从而把独立 transform shader 函数复用为 `#moj_import` 库函数。设置 `-Dkasuga.debugShaderImports=true` 可将结果导出至 `ksg_debug/` |
| `SpecialRenderingRegistry`、`ISpecialRenderingItem`、`ISpecialItemRenderer` | `MixinItemInHandRenderer` 拦截 `renderArmWithItem` 与 `evaluateWhichHandsToRender` | 物品实现 `ISpecialRenderingItem` 后可覆写 `getRenderPriority(...)` 控制主副手绘制优先级，并通过 `SpecialRenderingRegistry.register(item, renderer)` 接管第一人称手持渲染（内部自动 `pushPose`/`popPose`） |
| `IBlurControl` | `MixinScreen` 实现于原版 `Screen` | `ksgB$isBlurEnabled()` / `ksg$SetBlurEnabled(boolean)`，用于按界面开关原版背景模糊 |
| `BufferBuilderSupplier` | 函数式接口 | `@Nullable BufferBuilder init(ByteBufferBuilder buffer, VertexFormat.Mode mode, VertexFormat format)` |

---

## 6. 早期加载与客户端钩子（`lib.kasuga.early` / `lib.kasuga.client`）

### 6.1 `ModLoadingManager` 与 `ModLoadingProgress`

`ModLoadingManager.createTask(String label, int steps)` 按优先级倒序选择可用的 `ModLoadingProgressProvider`：

1. `ModLoadingProgressProvider.FML`（优先级 `255`）：反射探测 NeoForge `StartupNotificationManager.prependProgressBar` 与 `ProgressMeter`，返回 `NeoForgeFmlEarlyDisplayProgress`；在无 NeoForge 早期显示窗口的环境下自动不可用。
2. `ModLoadingProgressProvider.DEFAULT`（优先级 `-1000`）：回退为 `DefaultModLoadingProgress`，通过 SLF4J 输出 `"<label> [<n>/<totalSteps>]"` 进度日志。

`ModLoadingProgress` 接口方法：`increment()`、`complete()`、`set(int)`、`steps()`、`label(String)`。

### 6.2 `LoadingIndicator`（`lib.kasuga.client.loading`）

线程安全的 `AtomicReference<Snapshot>` 状态持有器，由 `MixinLoadingOverlay` 在 Minecraft `LoadingOverlay#render` 尾部绘制实时进度文本与细进度条（如 `KasugaModelManager` / `CombinedTextureManager` 在模型与 PBR 图集重载期间使用）：

- `LoadingIndicator.begin(String label, int total)`
- `LoadingIndicator.update(String label, int current, int total)`
- `LoadingIndicator.label(String label)`
- `LoadingIndicator.complete()`
- `LoadingIndicator.snapshot()` -> 返回 `Snapshot(boolean active, String label, int current, int total)` 及 `progress()`（钳制到 `[0.0f, 1.0f]`）。

### 6.3 `ClientBlockUpdateHooks`（`lib.kasuga.client`）

由 `MixinClientLevel` 注入 `ClientLevel#sendBlockUpdated` 尾部，在客户端主线程广播世界可见的单方块变更（网络同步、本地编辑或刻更新）：

- `AutoCloseable addListener(Consumer<Update> listener)`：返回取消订阅句柄；监听器必须保持轻量。
- `Update` 记录：`record Update(ClientLevel level, long packedPos, BlockState oldState, BlockState newState)`。

---

## 7. 内容子系统（`lib.kasuga.content`）

### 7.1 `UnModeledBlockProperty`（`lib.kasuga.content.block`）

`UnModeledBlockProperty<J extends Comparable<J>, T extends Property<J>> extends Property<J>`（工厂 `UnModeledBlockProperty.create(T prop)`）用于向 `BlockState` 附加逻辑状态但**不参与原版 blockstate JSON 变体映射与 NBT 序列化**：

- `MixinBlockModelShaper` 覆写 `statePropertiesToString` 跳过 `UnModeledBlockProperty` 实例。
- `MixinNbtUtils` 在 `NbtUtils#readBlockState` 查表时对 `UnModeledBlockProperty` 返回 `null`。

### 7.2 全局设备管理与属性索引（`lib.kasuga.content.device`）

| 类 | 职责与主要 API |
|---|---|
| `DeviceRegistries` | 注册两个同步原版注册表：`kasuga_lib:device_attribute_type`（`DEVICE_ATTRIBUTE`）与 `kasuga_lib:device_type`（`DEVICE`） |
| `Device` / `DeviceType<T>` | 设备逻辑实体基类，支持 `setDirty()` / `isDirty()` / `cleanDirty()`、`save(CompoundTag)`、`load(CompoundTag)`、`acceptMerge(Device)` |
| `DeviceAttribute` / `DeviceAttributeType<T>` | 属性实例与类型记录 `record DeviceAttributeType<T>(factory, isUnique, canIndex, persistent)`，通过 `DeviceAttributeType.Builder` 构建 |
| `AttributeIndex` | 为设备属性维护唯一主键索引 `primaryIndex`（`isUnique`）与多值倒排索引 `indexable`（`canIndex`），提供 `reindex(...)`、`notifyAttributeAdded`、`notifyAttributeRemoved`、`queryAttribute(DeviceAttribute)` |
| `GlobalDeviceManager` | `@Context` 单例；基于 `CustomSavedDataManager` 与 `GlobalDeviceSavedData.Customized` 将设备及属性存放在 `<worldDir>/devices/<uuid>.nbt`，提供 `addAttribute`、`removeAttribute`、`getAttributes`、`remove`、`merge`、`saveToData` |

### 7.3 文档物品与组件系统（`lib.kasuga.content.document`）

| 类 | 职责与主要 API |
|---|---|
| `DocumentComponentRegistries` | `@Context` 单例；注册 `kasuga_lib:document_component_type` 注册表（`DOCUMENT_COMPONENT_REGISTRY`）与物品数据组件 `kasuga_lib:document_components`（`DOCUMENT_COMPONENT`，持久化用 `Codec.dispatchedMap`，网络同步用 `KasugaCodec.mapFunc`） |
| `DocumentComponentType<T>` | 抽象组件类型，声明 `codec()`、`networkCodec()`、`convert(Object)`、`convertBack(T)`、`readable()` |
| `DocumentItem` | 继承 `Item` 并实现 `ISpecialRenderingItem`（`getRenderPriority` 返回 `2`）；当 `DOCUMENT_COMPONENT` map 非空时堆叠上限自动降为 `1` |
| `DocumentItemRenderer` / `DocumentComponentRenderer<T>` | 实现 `ISpecialItemRenderer`（单手/双手持握渲染）；通过 `registerComponentRenderer(DocumentComponentType<T>, DocumentComponentRenderer<T>)`（通常经 `DocumentComponentRendererReg` + `RenderingRegistry`）注册各组件绘制器 |
| `DocumentComponentNode` | 空占位类 |

### 7.4 动态连通图与环路检测（`lib.kasuga.content.graph`）

- **`GraphManager<K, V, E>`**（依赖 `org.jgrapht:jgrapht-core:1.5.2`）：维护按连通分量划分的动态图集合（`HashMap<K, Graph<V, E>>`），子类实现 `createGraph()` 与 `createKey()`。
  - `createNode` / `removeNode`、`addConnection` / `removeConnection`、`synchronizeNode(vertex, connectionsSupplier)`、`getGraphIdByNode`、`getGraphByNode`。
  - 跨分量连边会自动合并两张图；删边或删点时调用 `GraphAlgo.split(...)` 按 `ConnectivityInspector` 自动拆分出新的连通子图（注意 `propagateSplit` 假设拆分后至少保留一个分量，删除单点图最后一个顶点时 `newGraphs.pop()` 会抛出 `NoSuchElementException`）。
- **`GraphCycleDetector<T>`**（纯 JDK 集合实现，零 Minecraft 依赖）：
  - `GraphCycleDetector.dfs(Set<T> nodes, GraphAdapter<T> adapter)`：三色 DFS 检测有向图全部环路，返回 `CycleResult<T>`（`getCycles()`、`getNodesInCycles()`、`hasCycle()`）。
  - `GraphCycleDetector.topologicalSort(Set<T> nodes, GraphAdapter<T> adapter)`：Kahn 算法拓扑排序（内部对 `adapter` 边反向，使父级排在子级之前），返回 `TopoResult<T>`（`getSorted()`、`getNodesNotSorted()`、`hasCycle()`）。

### 7.5 二维码生成（`lib.kasuga.content.qr`）

- `QrCodeCache.encodeBitMatrix(String content)`：使用 `com.google.zxing:core:3.5.3`（`MultiFormatWriter`）编码生成 `64 × 64` 的 `BitMatrix`（编码失败抛出 `WriterException`）。
- `QrCodeTexture extends DynamicTexture`：由 `NativeImage` 构建动态 QR 纹理。
