# Core 模组入口与注册系统（modules/core）

`modules/core` 的 `lib.kasuga` 与 `lib.kasuga.registration` 提供模组引导入口、分层声明式注册树（`Reg` / `RegistryGroup` / `Registry`）、基于事件的阶段分发机制，以及面向 Minecraft 与 KasugaLib 自定义注册表的 DSL 注册器。

- 依赖注入上下文与 `@BeanOnlyIn` 见 [Inject 依赖注入与环境装配](inject.md)
- 生命周期阶段边界与侧边约束见 [生命周期与初始化顺序](lifecycle.md)、[侧边模型](sidedness.md)
- 统一资源平台与 VFS 见 [统一资源平台与 Script Ticket](resource-platform.md)、[虚拟文件系统（VFS）](vfs.md)
- 运行时子系统（RPC、Saved、Compat、Rendering、Content）见 [Core 运行时基础设施与内容子系统](core-runtime.md)

---

## 1. 模组入口与启动上下文（`lib.kasuga`）

| 类 | 职责与核心 API |
|---|---|
| `KasugaLib` | `@Mod("kasuga_lib")` 主入口；持静态 `public static final String MODID = "kasuga_lib"` 与 `public static final ApplicationContext context = ModApplicationContext.create(KasugaLib.class)`。构造器调用 `ModApplicationContext.init(context, modEventBus, modContainer)` 后在 mod 总线发布 `KasugaLibStartupEvent` |
| `KasugaLib.getContext()` | 返回 KasugaLib 共享 Micronaut `ApplicationContext` |
| `KasugaLib.getBean(Class<T> beanType)` | 返回指定类型的 Bean；若 `!context.isRunning()` 则抛出 `IllegalStateException` |
| `KasugaLib.afterRunning(IEventBus eventBus, Runnable hook)` | 若上下文已运行则立即执行 `hook`，否则在 `eventBus` 上监听 `KasugaLibStartupEvent` 后执行 |
| `KasugaLib.isRunning()` | 返回 `context.isRunning()` |
| `KasugaLibStartupEvent` | `extends Event implements IModBusEvent`，标志 KasugaLib 容器已启动完成 |
| `KasugaLibApplication` | `@Context` 单例；持有 `public static final Registry REGISTRY = KasugaLibRegistry.getRegistryOf(KasugaLib.MODID)`，在 `@PostConstruct init()` 中调用 `REGISTRY.register(modEventBus)` |
| `KasugaLibClientApplication` | `@Context`、`@BeanOnlyIn.Client`；在客户端 `@PostConstruct init()` 中调用 `KasugaLibApplication.REGISTRY.registerClient(modEventBus)` |
| `KasugaLibRegistry` | 提供工厂方法 `public static Registry getRegistryOf(String modId)`（返回 `new Registry(modId)`） |

外部模组可以创建自己的 `Registry`（通过 `KasugaLibRegistry.getRegistryOf("your_modid")`），然后在模组初始化时调用 `registry.register(modEventBus)`（并在客户端条件调用 `registry.registerClient(modEventBus)`）。

---

## 2. 注册树架构（`lib.kasuga.registration`）

所有注册条目均构成以 `Registry` 为根的树形结构，通过**祖先优先修饰符变换**共享命名空间、creative tab 与条件分发能力。

### 2.1 `Reg<S extends Reg<?, ?>, T>`

`public abstract class Reg<S extends Reg<?, ?>, T> implements TransformerProvider, EntrySupplier<T>`

| 能力分组 | 签名 | 说明 |
|---|---|---|
| 树连接 | `setParent(Reg<?, ?> parent)` / `addChild(Reg<?, ?> child)` / `getParentReg()` / `getChildren()` | 维护父子双向引用（`children` 使用 `ConcurrentHashMap.newKeySet()`） |
| 流畅辅助 | `S self()` / `S consumeBy(Consumer<S> targetRegistration)` | 返回自引用类型 `S` |
| Modifier 通道 | `S configure(Modifier<?> modifier)` / `S configure(Consumer<? super S> consumer)` | 追加修饰符或配置回调 |
| 变换求值 | `<L> L transform(ModifierType<L> modifierType, L element)` | 先递归调用 `parent.transform(...)`，再依次应用本节点修饰符；当 `origin == this && modifierType.skipSelf` 时跳过本节点（用于 `SCOPE`） |
| 类键属性通道 | `<P> S withProperty(Class<P> type, Function<P, P> modifier)` / `<P> P applyProperties(Class<P> type, P value)` | 与代码生成解耦的属性链；同样先经过 `parent.applyProperties(...)` |
| 阶段分发 | `void register(RegisterContext<?> context)` / `void dispatchRegister(RegisterContext<?> context)` | 先对自身执行 `register(context)`，再递归分派给所有 `children` |

### 2.2 `RegistryGroup` 与 `Registry`

- **`RegistryGroup`**（`extends Reg<RegistryGroup, Void> implements Consumer<Reg<?, ?>>, IModifierConfigure<RegistryGroup>`）：
  - `accept(Reg<?, ?> reg)`：等价于 `reg.setParent(this)`。
  - `dispatchWhen(BooleanSupplier predicate)` / `isDispatchEnabled()`：重写 `dispatchRegister`，当 `predicate.getAsBoolean()` 为 `false` 时跳过自身及整棵子树的阶段注册分发。
  - `transform(Function<Reg<?, ?>, Reg<?, ?>> function)`：向 `transformation` 列表追加变换函数。
- **`Registry`**（`public final class Registry extends RegistryGroup`）：
  - 构造器自动配置 `ResourceLocationModifiers.withNamespace(modId)`。
  - `getModId()` 返回模组 ID。
  - `register(IEventBus modEventBus)` 与 `@OnlyIn(Dist.CLIENT) registerClient(IEventBus modEventBus)`：通过 `KasugaLib.afterRunning(modEventBus, ...)` 注册到 `RegisterContextRegistry` 的 `Side.COMMON` / `Side.CLIENT`。
  - **类型键附件存储**（基于 `ConcurrentHashMap`）：`getOrCreateAttachment(Class<T>, Supplier<? extends T>)`、`getAttachment(Class<T>)`、`setAttachment(Class<T>, T)`、`removeAttachment(Class<?>)`。
- **`CompositeReg<S extends CompositeReg<?>>`**：复合注册节点基类，其 `getEntry()` 返回 `Map<Class<? extends Registry>, Registry>`（需由代码生成子类覆写，未生成时抛出 `IllegalStateException`）。

### 2.3 核心工具类（`lib.kasuga.registration.core`）

| 类型 | 关键成员 | 说明 |
|---|---|---|
| `RegisterContext<L>` | `onStage(RegistrationStage<T> stage, Consumer<T> consumer)`、`getStage()` | 将事件载荷安全缩窄到目标 `RegistrationStage` |
| `RegisterContextRegistry` | `Side.{COMMON, CLIENT}`、`configure(...)`、`register(...)`、`registerBefore(Side, RegistrationStage<?>, RegistryStageDispatchFunction)`、`dispatch(...)` | `@Context` 单例，负责将 `Registry` 与事件分发函数交叉绑定 |
| `Modifier<T>` / `ModifierType<T>` | `ModifierType(boolean skipSelf)` | `skipSelf = true` 允许子级继承父级 `SCOPE` 而父级自身不消费 |
| `ScopeHelper` | `effect(ModifierType<T> effect, Supplier<T> value)`、`get(Reg<?, ?> self, ModifierType<T> effect)` | 将延迟获取的值绑定到可继承的 `SCOPE` 修饰符 |
| `ResourceLocationModifiers` | `ID`、`id(Function<ResourceLocation, ResourceLocation>)`、`withNamespace(String namespace)` | 将默认 `"minecraft"` 命名空间重写为模组命名空间 |
| `CreativeTabModifiers` | `TYPE`、`set(Supplier<ResourceLocation> tabSupplier)` | 在 `RegistryGroup` / `Registry` 级统一设置默认 creative tab |
| `ChildrenLookup` | `findParent(Reg<?, ?> start, Class<T> type, BiFunction<Reg<?, ?>, Class<T>, Object> projection)` | 沿父链向上查找特定类型祖先，避免在注册事件内过早解析跨注册表条目 |
| `ChildrenUtils` | `traverse(...)`、`traverseRI(...)` | 遍历 `Reg` 与 `IChildrenConfiguration` / `IAdaptedObject` 子树 |
| `IModifierConfigure<S>` / `ConfigureAdapter<S>` / `IChildrenConfiguration<S>` / `IAdaptedObject<T>` | `configure(...)` / `addChild(...)` / `getOriginal()` | 代码生成配置接口与适配器基类 |

---

## 3. 注册阶段与事件映射（`lib.kasuga.registration.stages`）

`DefaultRegisterContextDispatchers`（通用端）与 `DefaultClientRegisterContextDispatchers`（`@BeanOnlyIn.Client`）在 Micronaut `@PostConstruct` 阶段向 `RegisterContextRegistry` 挂载如下监听器：

| `RegistrationStage` 常量 | 上下文载荷类型 | 对应 NeoForge Mod 总线事件 | 分发端 |
|---|---|---|---|
| `NEW_REGISTRY` | `NewRegistryEvent` | `NewRegistryEvent` | `COMMON` |
| `REGISTER_EVENT` | `RegisterStageContext` | `RegisterEvent`（`EventPriority.HIGHEST`） | `COMMON` |
| `PAYLOAD_REGISTRATION` | `PayloadRegistrationStage` | `RegisterPayloadHandlersEvent` | `COMMON` |
| `REGISTER_CAPABILITIES` | `CapabilityRegistration` | `RegisterCapabilitiesEvent` | `COMMON` |
| `CREATIVE_TAB_CONTENT_REGISTRATION` | `CreativeTabContentRegistration` | `BuildCreativeModeTabContentsEvent` | `COMMON` |
| `MENU_SCREEN_BINDING` | `MenuScreenBindingStage` | `RegisterMenuScreensEvent` | `CLIENT` |
| `REGISTER_CLIENT_EXTENSIONS` | `FluidClientExtensionsStage` | `RegisterClientExtensionsEvent` | `CLIENT`（直接调用 `registry.dispatchRegister`，不经过 `registerBefore`） |
| `BAKING_COMPLETE` | `BakingCompleteStage` | `ModelEvent.BakingCompleted` | `CLIENT` |
| `COMMON_SETUP` | `RegisterStageContext` | — | 当前未由默认分发器订阅 |

各阶段载荷关键方法：

- `RegisterStageContext`：`register(ResourceKey<? extends Registry<T>>, ResourceLocation, Supplier<T>)`、`getRegistryKey()`
- `PayloadRegistrationStage`：`PayloadRegistrar getRegistrar(String version)`（内部对 `registrarFunction` 做 `Util.memoize` 缓存）
- `CapabilityRegistration`：`RegisterCapabilitiesEvent getEvent()`
- `CreativeTabContentRegistration`：`getTabKey()`、`getTab()`、`getEvent()`
- `MenuScreenBindingStage`：`register(MenuType<T>, Supplier<MenuRendererBuilder<T, U>>)`
- `FluidClientExtensionsStage`：`getEvent()`、`register(FluidTypeReg<?>)`
- `BakingCompleteStage` / `FMLCommonSetupStageContext` / `RegistrationStageContext`：无载荷方法的阶段标记类。

---

## 4. Minecraft 注册器（`lib.kasuga.registration.minecraft`）

本节包下的大多数类由 `modules/core/src/codeTemplate` 经 `:modules:generator` 生成到 `lib.kasuga.registration.minecraft.*`，并配套生成 `*Configurations` 接口与 `*Modifiers` 类。

### 4.1 基类：`MinecraftDeferRegistryReg` 与 `DeferredFillHolder`

`MinecraftDeferRegistryReg<S extends Reg<S, T>, R, T extends R>` 是注册到原版/NeoForge `Registry<R>` 的统一基类：

- 在构造后、`REGISTER_EVENT` 触发前，`getHolder()` 返回 `DeferredFillHolder<R>` 占位 `Holder`。
- 收到匹配 `entryKey` 的 `REGISTER_EVENT` 时：调用 `resolveId()`（先经过 `ResourceLocationModifiers.ID`，再经过 `applyProperties(ResourceLocation.class, id)`），调用 `createObject(id)` 创建条目，填充 `DeferredFillHolder` 并替换为 `DeferredHolder.create(entryKey, id)`，注册到事件并触发 `onRegistered(Consumer<? super T>)` 回调。
- 常用读取器：`getEntry()`（注册前抛 `IllegalStateException`）、`getHolder()`、`getName()`、`getRegistryKey()`、`getResourceLocation()`。

### 4.2 Minecraft 注册器速查表

| 类 | 构造 / 工厂 | 目标注册表 / 阶段 | 主要流畅配置（`*Configurations` / 实例方法） |
|---|---|---|---|
| `BlockReg<T extends Block>` | `BlockReg.of(name, Function<Properties, T>)` / `new BlockReg<>(name, reg -> props -> ...)` | `Registries.BLOCK` | `BlockBehaviour.Properties` 常见链式方法（`strength`、`destroyTime`、`noOcclusion`、`lightLevel`、`sound` 等）；子项：`withDefaultBlockItem(name)`、`withBlockItem(name, supplier)`；`BlockReg.SCOPE` |
| `ItemReg<T extends Item>` | `ItemReg.of(name, Function<Properties, T>)` | `Registries.ITEM` + `CREATIVE_TAB_CONTENT_REGISTRATION` | `Item.Properties` 链式方法、`isFood(Boolean)`、`food*`、`component(...)`、`tabTo(CreativeTabReg \| Supplier<CreativeModeTab>)`、`tabToByKey(...)` |
| `BlockEntityReg<T extends BlockEntity>` | `BlockEntityReg.of(name, BlockEntitySupplier<T>)` | `Registries.BLOCK_ENTITY_TYPE` + `REGISTER_CAPABILITIES` | `validBlocks(BlockSupplier...)` / `validBlocks(Collection<Supplier<Block>>)` / `validBlocks(BiPredicate<ResourceLocation, Block>)`、`addCapability(BlockCapability<V, C>, ICapabilityProvider)`；`BlockEntityReg.SCOPE` |
| `BlockEntityRendererReg<B>` | `new BlockEntityRendererReg<>(Supplier<BlockEntityRendererBuilder<B>>)` | `BAKING_COMPLETE` -> `RenderingRegistry` | `withBlockEntity(Supplier<BlockEntityType<?>>)`、`withBlockEntities(BiPredicate<ResourceLocation, BlockEntityType<?>>)` |
| `EntityReg<T extends Entity>` | `EntityReg.of(name, BiFunction<EntityType<?>, Level, T>)` | `Registries.ENTITY_TYPE` | `size(w, h)`、`width(w)`、`height(h)`、`category(...)`、`fireImmune()`、`noSave()`、`noSummon()`、`clientTrackingRange(int)`、`updateInterval(int)`、`canSpawnFarFromPlayer()`、`shouldReceiveVelocityUpdates(boolean)`、`withAttributeSupplier(...)`、`withAttributeBuilder(...)` |
| `EntityRendererReg<E>` | `new EntityRendererReg<>(Supplier<EntityRendererBuilder<E>>)` | `BAKING_COMPLETE` -> `RenderingRegistry` | `withEntity(Supplier<EntityType<?>>)`、`withEntities(BiPredicate<ResourceLocation, EntityType<?>>)` |
| `EntityAttributeReg<T>` | `new EntityAttributeReg<>(name, Supplier<T>)` | `Registries.ATTRIBUTE` | 直接供应 `Attribute` 实例 |
| `CreativeTabReg` | `new CreativeTabReg(name)` | `Registries.CREATIVE_MODE_TAB` | `title`、`icon`、`displayItems`、`withSearchBar`、`alignedRight`、`hideTitle`、`noScrollBar`、`withTabsBefore`、`withTabsAfter` 等 |
| `MenuReg<T, U>` | `new MenuReg<>(name, IContainerFactory<T>, Supplier<MenuRendererBuilder<T, U>>)` | `Registries.MENU` + `MENU_SCREEN_BINDING` | 当 `screenSupplier != null` 时在客户端自动绑定菜单界面 |
| `PayloadReg<T>` | `new PayloadReg<>(name, codec)` / `(name, Supplier<StreamCodec>)` | `PAYLOAD_REGISTRATION` | `stage(PayloadStage.{COMMON, CONFIGURATION, PLAY})`、`version(String)`、`client(Supplier<IPayloadHandler<T>>)`、`server(Supplier<IPayloadHandler<T>>)` |
| `SoundReg` | `SoundReg.of(name)` / `of(name, range)` / `of(name, Function)` | `Registries.SOUND_EVENT` | 变距 / 定距 / 自定义 `SoundEvent`；`SoundReg.SCOPE` |
| `DataComponentReg<T>` | `new DataComponentReg<>(name, UnaryOperator<DataComponentType.Builder<T>>)` | `Registries.DATA_COMPONENT_TYPE` | 通过 builder 构造并注册组件类型 |
| `RegistryReg<T>` | `new RegistryReg<>(Registry<T>)` | `NEW_REGISTRY` | 注册模组自定义 `net.minecraft.core.Registry` |

#### `PayloadReg` 命名空间与端侧保护

- **命名空间守卫**：`PayloadReg` 在 `ensureEntry()` 时若解析结果为 `"minecraft"` 命名空间（例如忘记调用 `setParent(registry)`），将立即抛出 `IllegalStateException`，防止误占用原版命名空间（由 `PayloadRegNamespaceTest` 覆盖）。
- **专用服务端防护**：若配置了 `client(...)` 处理器且当前处于专用服务端（`lib.kasuga.inject.class_loader.Envs.isDedicatedServer()`），`PayloadReg` 会自动以空 lambda 占位替换客户端处理器而不求值 `clientHandlerSupplier`，避免加载客户端类。

#### 流体注册套件：`FluidReg`、`FluidTypeReg`、`FluidBlockReg`

面向 NeoForge 1.21.1 `BaseFlowingFluid` 设计的 2.0 流体注册链路：

- **`FluidReg<S extends BaseFlowingFluid.Source, F extends BaseFlowingFluid.Flowing>`**：
  - 构造：`new FluidReg<>(name)`（派生 `<name>_flowing`）或 `new FluidReg<>(typeName, sourceName, flowingName)`，内部自动创建并挂载子 `FluidTypeReg`。
  - 配置：`instances(sourceFactory, flowingFactory)`、`numericProperties(levelDecrease, slopeDistance, tickRate, explosionResistance)`（或分别调用单项 setter）、`type(t -> ...)`、`block(FluidBlockReg<?>)`、`bucket(String)` / `bucket(ItemReg<?>)`。
  - 访问：`getSource()`、`getFlowing()`、`getSourceSupplier()`、`getFlowingSupplier()`、`getSourceHolder()`、`getFlowingHolder()`、`getFluidType()`、`getFluidTypeHolder()`。
- **`FluidTypeReg<T extends FluidType>`**：
  - `FluidTypeReg.of(name)` / `of(name, factory)`；客户端视觉属性：`still(path)`、`flowing(path)`、`overlay(path)`、`tint(argb)` / `tint(r, g, b)`、`fogColor(r, g, b)`、`fog(start, end)`；流体物理属性：`density`、`viscosity`、`lightLevel`、`temperature`、`supportsBoating`、`sound`、`bucketSounds`、`defaultBucketSounds`、`vaporizeSound`。
  - 通过 `REGISTER_CLIENT_EXTENSIONS`（`RegisterClientExtensionsEvent`）注册 `IClientFluidTypeExtensions`，避免在专用服务端加载客户端纹理/雾效类。
- **`FluidBlockReg<T extends LiquidBlock>`**：
  - `FluidBlockReg.of(name, fluid -> props -> ...)`，支持 `BlockBehaviour.Properties` 常见链式配置（`FluidBlockRegConfigurations`）。
  - 依赖 NeoForge 在 `minecraft:block` 之前分派 `minecraft:fluid` 的顺序，通过 `ChildrenLookup.findParent` 从父 `FluidReg` 读取源流体。

### 4.3 典型注册代码示例

```java
// 1. 方块与物品（出自 BlockRegistryTest / ItemRegistryTest）
public static final Registry REGISTRY = KasugaLibRegistry.getRegistryOf(KasugaLib.MODID);

public static final BlockReg<TestBlock> TEST_BLOCK = BlockReg.of("test_block", TestBlock::new)
        .configure(BlockRegModifiers.BlockProperties.of("destroyTime", p -> { p.destroyTime(10); return p; }))
        .configure(BlockRegModifiers.BlockProperties.of("noOcclusion", p -> { p.noOcclusion(); return p; }))
        .withDefaultBlockItem("test_block")
        .setParent(REGISTRY);

// 2. 流体、流体方块与桶（出自 AllFluidTestEntries）
public static final FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing> TEST_FLUID =
        new FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing>("test_fluid")
                .instances(BaseFlowingFluid.Source::new, BaseFlowingFluid.Flowing::new)
                .numericProperties(2, 4, 10, 100.0F)
                .type(t -> t.viscosity(1200).density(1400).defaultBucketSounds()
                        .tint(0xFF3FA7D6).still("block/test_fluid_still").flowing("block/test_fluid_flow"))
                .block(FluidBlockReg.of("test_fluid_block", fluid -> props -> new LiquidBlock(fluid, props)))
                .bucket("test_fluid_bucket")
                .setParent(REGISTRY);

// 3. 网络 Payload（出自 PayloadRegistryTest）
public static final PayloadReg<TestMyClass> TEST_PAYLOAD = new PayloadReg<>("test_payload", TestMyClass.CODEC)
        .stage(PayloadStage.COMMON)
        .client(() -> (msg, ctx) -> {})
        .server(() -> (msg, ctx) -> {})
        .setParent(REGISTRY);
```

---

## 5. 端侧渲染注册、Kasuga 自定义注册器与工厂表

### 5.1 `RenderingRegistry`（`lib.kasuga.registration.beans.rendering`）

`RenderingRegistry` Bean 为渲染器注册提供端侧多态实现：

- `DefaultRenderingRegistry`（`@Singleton`）：服务端默认实现，调用 `registerBlockEntityRenderer` / `registerEntityRenderer` / `registerDocumentComponentRenderer` 时仅打 DEBUG 日志并安全忽略。
- `ClientRenderingRegistry`（`@Singleton`、`@Replaces(DefaultRenderingRegistry.class)`、`@BeanOnlyIn.Client`、`implements RenderingRegistry, Configurable`）：在客户端注册到 `BlockEntityRenderers`、`EntityRenderers` 与 `DocumentItemRenderer`，并暴露客户端配置项 `ignore_block_entity_renderer`（默认 `false`）。

### 5.2 Kasuga 自定义注册器（`lib.kasuga.registration.kasuga`）

| 类 | 说明 |
|---|---|
| `DeviceAttributeReg<T, U>` | 注册到 `DeviceRegistries.DEVICE_ATTRIBUTE_KEY`；构造器为 `protected`，提供 `DeviceAttributeConfigurations`（`indexable()`、`unique()`、`notPersistent()`） |
| `GlobalDeviceReg` | 当前为空占位类 |
| `DocumentComponentReg<T>` | 注册自定义 `DocumentComponentType<?>` 到 `DocumentComponentRegistries.DOCUMENT_COMPONENT_KEY` |
| `DocumentComponentRendererReg<T>` | 在 `BAKING_COMPLETE` 阶段向 `RenderingRegistry` 绑定 `DocumentComponentRendererSupplier<T>`，通过 `withComponent(DocumentComponentReg<?>)` 关联组件类型 |
| `DocumentComponentTypes` | 内置组件类型：`DocumentString`、`DocumentBoolean`、`DocumentInt` |

### 5.3 数据驱动工厂表：`FactoryRegistry`（`lib.kasuga.registration.factory`）

`FactoryRegistry` 维护由 `ConcurrentHashMap` 支持的静态工厂注册表，供 data-driven 内容装配查找 `type` 键（详见 [数据驱动扩展 API](data-driven/api-extension.md)）：

- **方块工厂**：`register(String type, BlockFactory factory)`、`register(String type, BlockFactory factory, boolean createsBlockItem)`、`get(String)`、`contains(String)`、`createsBlockItem(String)`
- **物品工厂**：`registerItem(String type, ItemFactory factory)`、`getItemFactory(String)`、`containsItem(String)`
- **方块实体工厂**：`registerBlockEntity(String type, BlockEntityFactory factory)`、`getBlockEntityFactory(String)`、`containsBlockEntity(String)`、`getBlockEntityTypes()`
- **通用工厂**：`registerGeneric(String type, GenericFactory factory)`、`getGeneric(String)`、`containsGeneric(String)`
