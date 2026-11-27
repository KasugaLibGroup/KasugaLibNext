# Core Runtime Infrastructure and Content Subsystems (modules/core)

This reference covers the runtime infrastructure and content subsystems in `modules/core` outside the resource platform, VFS, and registration tree:

- Networking RPC: `lib.kasuga.core.networking.rpc.*`
- Custom saved data and external item storage: `lib.kasuga.core.saved.*`
- Optional mod compatibility probing: `lib.kasuga.core.compat.*`
- Codecs and lifecycle holders: `lib.kasuga.core.codec.*`, `lib.kasuga.core.holder.*`
- Core rendering and GLSL import hooks: `lib.kasuga.core.rendering.*`
- Early loading and client update hooks: `lib.kasuga.early.*`, `lib.kasuga.client.*`
- Content subsystems: `lib.kasuga.content.{block,device,document,graph,qr}.*`

Related pages: [Unified Resource Platform and Script Tickets](resource-platform.md), [Virtual file systems](vfs.md), and [Core Mod Entry and Registration System](core-registration.md).

---

## 1. Networking RPC (`lib.kasuga.core.networking.rpc`)

`RpcApi` builds fire-and-forget and request-response (`CompletableFuture`) networking endpoints on top of `PayloadReg`. Code generation expands the `Rpc` template in `codeTemplate` into `lib.kasuga.core.networking.rpc.Rpc` supporting 0–10 typed arguments.

### 1.1 `RpcApi` and One-Way `WithOutResponse`

| Type / Method | Signature and Behavior |
|---|---|
| `new RpcApi<>(String name)` | Creates an underlying `PayloadReg<>("rpc/" + name + "/request", ...)` |
| `executeOnServer()` / `executeOnClient()` | Registers the receiving handler and marks that side executable |
| `runOnMainThread()` | Wraps handler execution in `context.enqueueWork(...)` before invoking `handle(...)` |
| `register(Reg<?, ?> parent)` | Attaches underlying payload registration(s) to a parent tree providing a non-`"minecraft"` namespace |
| `call(T request)` / `call(T request, Player player)` | Sends via `PacketDistributor.sendToPlayer(...)` when `player instanceof ServerPlayer` (requires `executeOnClient()`) or `PacketDistributor.sendToServer(...)` otherwise (requires `executeOnServer()`), throwing `IllegalStateException` on direction mismatch |
| `WithOutResponse.call(T request, Player p)` | Fire-and-forget wrapper; receivers override `handleInstant(T, IPayloadContext)` |

### 1.2 Request-Response `RpcApi.WithResponse<T, R, S>`

`WithResponse` registers three payloads: `rpc/<name>/request`, `rpc/<name>/response`, and `rpc/<name>/error` (`Error` carries a UTF-8 string message).

| API | Behavior and Constraints |
|---|---|
| `CompletableFuture<R> run(T request)` / `run(T request, Player player)` | Allocates a unique `VAR_LONG` session ID (bound to `accepts = player`), sends the request, and returns a `CompletableFuture<R>` |
| `handleInstant(T request, IPayloadContext context)` / `CompletableFuture<R> handle(T, IPayloadContext)` | Override one on the receiver; both result and error replies hop onto the main thread via `context.enqueueWork(() -> context.reply(...))` |
| `setTimeout(Duration)` / `setTimeoutSeconds(long)` / `setTimeoutMillis(long)` | Default timeout is **30 seconds**; `setTimeout(null)` resets to 30 seconds; `setTimeout(long)` is `@Deprecated` |
| `void tick()` | Drains `sessionManager.checkTimeout()`, failing expired futures with `RpcTimeoutException`. **Callers must invoke `tick()` periodically** on the client or server tick so unanswered sessions time out |

### 1.3 Session Management and Generated `Rpc` Facade

- **`RpcSessionManager<T>`**: manages `AtomicLong` session IDs, a deadline-ordered `PriorityQueue<Session<T>>`, and a `HashMap<Long, Session<T>>`. Uses `saturatedAdd` to clamp extreme timeouts at `Long.MAX_VALUE`, completes futures outside the internal lock to avoid callback re-entrancy deadlocks, and checks `accepts` player identity (`null` accepts any player).
- **`IdentifiedRpcPacketType<T>`**: pairs a `VAR_LONG` session ID with a `StreamCodec<? super FriendlyByteBuf, T>` into a `CustomPacketPayload`.
- **`RpcTimeoutException`**: extends `Exception`; supplied as the cause when a session expires.
- **`Rpc` (generated facade)**:
  - `Rpc.wrap(name, func, c1..cn)` -> returns `RpcV0` .. `RpcV10<T1..Tn>` (`WithOutResponse`, `call(Player, T1..Tn)`).
  - `Rpc.wrap(name, func, responseCodec, c1..cn)` -> returns `RpcR0<R>` .. `RpcR10<R, T1..Tn>` (`WithResponse`, `call(Player, T1..Tn)` returns `CompletableFuture<R>`).

```java
// From TestMyRpc (client calling server with an Integer result)
public static final Rpc.RpcR1<Integer, Integer> MY_RPC = Rpc.wrap(
        "test_rpc_with_response",
        (ctx, i1) -> 42,
        ByteBufCodecs.VAR_INT,
        ByteBufCodecs.VAR_INT
).executeOnServer().runOnMainThread().setTimeoutSeconds(10).register(registry);

// Caller side:
CompletableFuture<Integer> future = MY_RPC.call(null, 41);
// Periodic timeout drain:
MY_RPC.tick();
```

---

## 2. Custom Saved Data and External Save Items (`lib.kasuga.core.saved`)

> For per-dimension vanilla `SavedData` injection, see `Saved<T>` in [Inject Dependency Injection and Environment Wiring](inject.md). This package handles **world-root-scoped** custom files and UUID-linked item storage.

| Type | Scope and Key APIs |
|---|---|
| `SavedSystem` | `@Context` singleton registering persistent, network-synchronized data component `ITEM_SAVE_ID` (`kasuga_lib:item_external_id`, `UUID`) on `KasugaLibApplication.REGISTRY` |
| `CustomSavedData` | Base class with no-arg constructor and `save(Path path, HolderLookup.Provider provider)` |
| `CustomSavedDataManager` | `@Singleton` listening on `forgeEventBus` for `ServerStartingEvent` (`HIGHEST`), `LevelEvent.Save` (`LOWEST`), and `ServerStoppingEvent` (`LOWEST`). Resolves the world root directory via `MinecraftServerAccess` and caches one instance per subclass via `get(Class<T>, SimpleFactory<T>)` / `get(Class<T>, Factory<T>)` |
| `ScopedNbtSavedData<T extends SavedData>` | Extends `CustomSavedData`; manages keyed `.nbt` files under `<worldDir>/<basePath>/` through `DimensionDataStorage`. Provides `get(key)`, `computeIfAbsent(key)`, `remove(key)` (queues disk deletion in `pendingRemove` for the next `save`), and `getAllSavedNames()` |
| `ExternalSaveItem` | Extends `Item`: assigns a unique `ITEM_SAVE_ID` via `initialize(ItemStack, Consumer<UUID>)` or `initializeOutputs(ItemStack, LivingEntity, Consumer<UUID>)`, and reads it via `getSaveId(ItemStack)` |
| `NbtDataStorage<T extends SavedData>` | Skeleton class holding `cache`, `directory`, and `provider` fields |

---

## 3. Optional Mod Compatibility Probing (`lib.kasuga.core.compat`)

`CompatCapability<T>` provides a lazy, double-checked-locking probe for optional integrations (such as Iris/Sodium ABI probing and OIT fallback in `modules/iris-compat`):

| API | Description |
|---|---|
| `new CompatCapability<>(String name, Probe<T> probe)` | `Probe.run()` may throw `ReflectiveOperationException` |
| `boolean isAvailable()` / `Result<T> result()` | Runs the probe once on first access and caches `Result<T>(boolean available, T value, String reason, Throwable cause)` |
| `CompatCapability.requireClass(String className, ClassLoader loader)` | Calls `Class.forName(className, false, loader)` without running static initializers |
| `CompatCapability.requireMethod(Class<?> owner, String methodName, Class<?> returnType, Class<?>... parameterTypes)` | Looks up a public method and asserts `method.getReturnType() == returnType` to detect signature drift |

Failure boundary (verified by `CompatCapabilityTest`): `ReflectiveOperationException`, `InaccessibleObjectException`, `LinkageError`, `SecurityException`, and a `null` probe return produce `available == false`; application bugs such as `IllegalStateException` propagate rather than being swallowed.

```java
CompatCapability<Method> api = new CompatCapability<>("iris_state", () ->
        CompatCapability.requireMethod(CapturedRenderingState.class, "getCurrentRenderedEntity", int.class));
if (api.isAvailable()) {
    // Safe to use api.result().value()
}
```

---

## 4. Codecs and Lifecycle Holders (`lib.kasuga.core.codec`, `lib.kasuga.core.holder`)

### 4.1 `KasugaCodec`

`KasugaCodec.mapFunc` builds a `StreamCodec<B, M>` for maps where each key dynamically selects its value codec (used by `DocumentComponentRegistries.DOCUMENT_COMPONENT_BYTE_BUF`):

```java
public static <B extends ByteBuf, K, V, M extends Map<K, ? extends V>> StreamCodec<B, M> mapFunc(
        IntFunction<? extends M> factory,
        StreamCodec<? super B, K> keyCodec,
        Function<K, StreamCodec<? super B, ? extends V>> valueCodecFunction,
        int maxSize,
        boolean cacheValueCodecByKey
)
```

### 4.2 `Holder<T>` and `Provider<T>`

Lightweight ref-counted attachment primitives (`Simple` implementations are **not thread-safe**):

- `Provider.simple(Supplier<T> creator, Consumer<T> disposer)`: creates the instance on the first `hold`, invokes `disposer` when the last `Holder` calls `release` or on `dispose()`, and exposes `getValue()` and `ifPresent(Consumer<T>)`.
- `Holder.simple(Consumer<T> applier, Consumer<T> disposer)`: `attach(Provider<T>)` detaches any previous provider, holds the value, and runs `applier`; `detach()` runs `disposer` and releases the hold.

---

## 5. Core Rendering and GLSL Import Hooks (`lib.kasuga.core.rendering`)

| Type | Mixin / Hook Mechanism | Public API and Behavior |
|---|---|---|
| `ShaderFunctionImport` | `MixinGlslPreprocessor` redirects `GlslPreprocessor#processImports` | Maps `kasuga_lib:<path>` imports to `assets/kasuga_lib/shaders/<path>`; `ShaderFunctionImport.read(Reader)` strips leading `in`/`out`/`uniform` declarations and stops before `void main(`, allowing standalone transform shaders to be `#moj_import`ed as helpers. Setting `-Dkasuga.debugShaderImports=true` dumps output to `ksg_debug/` |
| `SpecialRenderingRegistry`, `ISpecialRenderingItem`, `ISpecialItemRenderer` | `MixinItemInHandRenderer` hooks `renderArmWithItem` and `evaluateWhichHandsToRender` | Items implementing `ISpecialRenderingItem` can override `getRenderPriority(...)` for hand selection and register first-person renderers via `SpecialRenderingRegistry.register(item, renderer)` (wrapped in `pushPose`/`popPose`) |
| `IBlurControl` | `MixinScreen` applied to vanilla `Screen` | `ksgB$isBlurEnabled()` / `ksg$SetBlurEnabled(boolean)` toggles vanilla screen background blur per screen |
| `BufferBuilderSupplier` | Functional interface | `@Nullable BufferBuilder init(ByteBufferBuilder buffer, VertexFormat.Mode mode, VertexFormat format)` |

---

## 6. Early Loading and Client Hooks (`lib.kasuga.early`, `lib.kasuga.client`)

### 6.1 `ModLoadingManager` and `ModLoadingProgress`

`ModLoadingManager.createTask(String label, int steps)` picks the highest-priority available `ModLoadingProgressProvider`:

1. `ModLoadingProgressProvider.FML` (priority `255`): reflectively binds NeoForge `StartupNotificationManager.prependProgressBar` and `ProgressMeter`, returning `NeoForgeFmlEarlyDisplayProgress`.
2. `ModLoadingProgressProvider.DEFAULT` (priority `-1000`): falls back to `DefaultModLoadingProgress`, logging `"<label> [<n>/<totalSteps>]"` via SLF4J.

`ModLoadingProgress` methods: `increment()`, `complete()`, `set(int)`, `steps()`, and `label(String)`.

### 6.2 `LoadingIndicator` (`lib.kasuga.client.loading`)

Thread-safe `AtomicReference<Snapshot>` progress holder rendered onto Minecraft's `LoadingOverlay#render` by `MixinLoadingOverlay` (used by `KasugaModelManager` and `CombinedTextureManager` during model and PBR atlas reloads):

- `LoadingIndicator.begin(String label, int total)`
- `LoadingIndicator.update(String label, int current, int total)`
- `LoadingIndicator.label(String label)`
- `LoadingIndicator.complete()`
- `LoadingIndicator.snapshot()` -> returns `Snapshot(boolean active, String label, int current, int total)` with `progress()` clamped to `[0.0f, 1.0f]`.

### 6.3 `ClientBlockUpdateHooks` (`lib.kasuga.client`)

Hooked into the tail of `ClientLevel#sendBlockUpdated` by `MixinClientLevel`, broadcasting world-visible single-block updates on the client main thread:

- `AutoCloseable addListener(Consumer<Update> listener)`: returns a removal handle; listeners must stay lightweight.
- `Update` record: `record Update(ClientLevel level, long packedPos, BlockState oldState, BlockState newState)`.

---

## 7. Content Subsystems (`lib.kasuga.content`)

### 7.1 `UnModeledBlockProperty` (`lib.kasuga.content.block`)

`UnModeledBlockProperty<J extends Comparable<J>, T extends Property<J>> extends Property<J>` (factory `UnModeledBlockProperty.create(T prop)`) attaches logical blockstate properties that are **excluded from vanilla blockstate JSON model lookups and NBT deserialization**:

- `MixinBlockModelShaper` overwrites `statePropertiesToString` to skip `UnModeledBlockProperty` instances.
- `MixinNbtUtils` returns `null` during `NbtUtils#readBlockState` when looking up an `UnModeledBlockProperty`.

### 7.2 Global Device Management and Attribute Indexing (`lib.kasuga.content.device`)

| Type | Responsibility and Key APIs |
|---|---|
| `DeviceRegistries` | Creates two synced registries: `kasuga_lib:device_attribute_type` (`DEVICE_ATTRIBUTE`) and `kasuga_lib:device_type` (`DEVICE`) |
| `Device` / `DeviceType<T>` | Base logical device with dirty tracking (`setDirty`, `isDirty`, `cleanDirty`), `save(CompoundTag)`, `load(CompoundTag)`, and `acceptMerge(Device)` |
| `DeviceAttribute` / `DeviceAttributeType<T>` | Attribute base and `record DeviceAttributeType<T>(factory, isUnique, canIndex, persistent)` built via `DeviceAttributeType.Builder` |
| `AttributeIndex` | Maintains unique primary index `primaryIndex` (`isUnique`) and multi-value `indexable` map (`canIndex`), exposing `reindex`, `notifyAttributeAdded`, `notifyAttributeRemoved`, and `queryAttribute(DeviceAttribute)` |
| `GlobalDeviceManager` | `@Context` singleton persisting devices and attributes to `<worldDir>/devices/<uuid>.nbt` via `CustomSavedDataManager` and `GlobalDeviceSavedData.Customized`; provides `addAttribute`, `removeAttribute`, `getAttributes`, `remove`, `merge`, and `saveToData` |

### 7.3 Document Items and Components (`lib.kasuga.content.document`)

| Type | Responsibility and Key APIs |
|---|---|
| `DocumentComponentRegistries` | `@Context` singleton registering `kasuga_lib:document_component_type` (`DOCUMENT_COMPONENT_REGISTRY`) and item data component `kasuga_lib:document_components` (`DOCUMENT_COMPONENT`, using `Codec.dispatchedMap` and `KasugaCodec.mapFunc`) |
| `DocumentComponentType<T>` | Abstract component type defining `codec()`, `networkCodec()`, `convert(Object)`, `convertBack(T)`, and `readable()` |
| `DocumentItem` | Extends `Item` and implements `ISpecialRenderingItem` (priority `2`); drops max stack size to `1` whenever `DOCUMENT_COMPONENT` is non-empty |
| `DocumentItemRenderer` / `DocumentComponentRenderer<T>` | Implements `ISpecialItemRenderer` (one-hand and two-hand rendering); binds component renderers via `registerComponentRenderer(DocumentComponentType<T>, DocumentComponentRenderer<T>)` (usually via `DocumentComponentRendererReg` and `RenderingRegistry`) |
| `DocumentComponentNode` | Empty placeholder class |

### 7.4 Dynamic Connected Graphs and Cycle Detection (`lib.kasuga.content.graph`)

- **`GraphManager<K, V, E>`** (backed by `org.jgrapht:jgrapht-core:1.5.2`): maintains connected-component graphs (`HashMap<K, Graph<V, E>>`) where subclasses implement `createGraph()` and `createKey()`.
  - Provides `createNode`, `removeNode`, `addConnection`, `removeConnection`, `synchronizeNode(vertex, connectionsSupplier)`, `getGraphIdByNode`, and `getGraphByNode`.
  - Cross-component `addConnection` merges graphs; `removeNode` and `removeConnection` invoke `GraphAlgo.split(...)` (`ConnectivityInspector`) to split disconnected components (note that `propagateSplit` calls `newGraphs.pop()`, assuming at least one component remains—removing the sole vertex of a 1-node graph will throw `NoSuchElementException`).
- **`GraphCycleDetector<T>`** (pure JDK collections, zero Minecraft dependencies):
  - `GraphCycleDetector.dfs(Set<T> nodes, GraphAdapter<T> adapter)`: three-color DFS detecting all directed cycles, returning `CycleResult<T>` (`getCycles()`, `getNodesInCycles()`, `hasCycle()`).
  - `GraphCycleDetector.topologicalSort(Set<T> nodes, GraphAdapter<T> adapter)`: Kahn's BFS topological sort (reversing `adapter` edges so parents appear before children), returning `TopoResult<T>` (`getSorted()`, `getNodesNotSorted()`, `hasCycle()`).

### 7.5 QR Code Utilities (`lib.kasuga.content.qr`)

- `QrCodeCache.encodeBitMatrix(String content)`: encodes a `64 × 64` QR `BitMatrix` via `com.google.zxing:core:3.5.3` (`MultiFormatWriter`), throwing `WriterException` on failure.
- `QrCodeTexture extends DynamicTexture`: wraps a `NativeImage` into a dynamic Minecraft texture.
