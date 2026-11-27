# Core Mod Entry and Registration System (modules/core)

`lib.kasuga` and `lib.kasuga.registration` in `modules/core` provide the mod bootstrap entry point, hierarchical declarative registration trees (`Reg` / `RegistryGroup` / `Registry`), stage-based event dispatching, and fluent registrars for Minecraft and KasugaLib registries.

- Dependency injection bootstrap and `@BeanOnlyIn`: [Inject Dependency Injection and Environment Wiring](inject.md)
- Lifecycle phase boundaries and sidedness rules: [Lifecycle and Initialization Order](lifecycle.md), [Sidedness Model](sidedness.md)
- Unified resource platform and VFS: [Unified Resource Platform and Script Tickets](resource-platform.md), [Virtual file systems](vfs.md)
- Core runtime subsystems (RPC, Saved, Compat, Rendering, Content): [Core Runtime Infrastructure and Content Subsystems](core-runtime.md)

---

## 1. Mod Entry, Application Context, and Startup Event (`lib.kasuga`)

| Type / Method | Responsibility and Signature |
|---|---|
| `KasugaLib` | `@Mod("kasuga_lib")` main mod class; exposes `public static final String MODID = "kasuga_lib"` and `public static final ApplicationContext context = ModApplicationContext.create(KasugaLib.class)`. Its constructor calls `ModApplicationContext.init(context, modEventBus, modContainer)` and posts `KasugaLibStartupEvent` on `ModLoader` |
| `KasugaLib.getContext()` | Returns KasugaLib's shared Micronaut `ApplicationContext` |
| `KasugaLib.getBean(Class<T> beanType)` | Looks up a bean; throws `IllegalStateException` if `!context.isRunning()` |
| `KasugaLib.afterRunning(IEventBus eventBus, Runnable hook)` | Runs `hook` immediately if `isRunning()`, otherwise subscribes to `KasugaLibStartupEvent` on `eventBus` |
| `KasugaLib.isRunning()` | Returns `context.isRunning()` |
| `KasugaLibStartupEvent` | `extends Event implements IModBusEvent`, fired on the mod event bus after Micronaut context initialization |
| `KasugaLibApplication` | `@Context` singleton holding `public static final Registry REGISTRY = KasugaLibRegistry.getRegistryOf(KasugaLib.MODID)`; calls `REGISTRY.register(modEventBus)` in `@PostConstruct init()` |
| `KasugaLibClientApplication` | `@Context`, `@BeanOnlyIn.Client`; calls `KasugaLibApplication.REGISTRY.registerClient(modEventBus)` in `@PostConstruct init()` |
| `KasugaLibRegistry` | `public static Registry getRegistryOf(String modId)` returning `new Registry(modId)` |

External mods can create their own `Registry` via `KasugaLibRegistry.getRegistryOf("your_modid")` and invoke `registry.register(modEventBus)` (plus `registry.registerClient(modEventBus)` on the client).

---

## 2. Registration Tree Architecture (`lib.kasuga.registration`)

Registration entries form a tree rooted at a `Registry`, sharing namespaces, creative tabs, and conditional dispatch rules through **ancestor-first modifier transformation**.

### 2.1 `Reg<S extends Reg<?, ?>, T>`

`public abstract class Reg<S extends Reg<?, ?>, T> implements TransformerProvider, EntrySupplier<T>`

| Capability | Signatures | Behavior |
|---|---|---|
| Tree wiring | `setParent(Reg<?, ?> parent)`, `addChild(Reg<?, ?> child)`, `getParentReg()`, `getChildren()` | Maintains bidirectional parent/child links (`children` uses `ConcurrentHashMap.newKeySet()`) |
| Fluent helpers | `S self()`, `S consumeBy(Consumer<S> targetRegistration)` | Preserves concrete subtype `S` across chained calls |
| Modifier channel | `S configure(Modifier<?> modifier)`, `S configure(Consumer<? super S> consumer)` | Registers ordered modifiers or configuration callbacks |
| Transform evaluation | `<L> L transform(ModifierType<L> modifierType, L element)` | Walks `parent.transform(...)` first, then applies local modifiers; skips `this` when `origin == this && modifierType.skipSelf` (used for `SCOPE`) |
| Class-keyed properties | `<P> S withProperty(Class<P> type, Function<P, P> modifier)`, `<P> P applyProperties(Class<P> type, P value)` | Codegen-free property chain; also evaluates ancestor functions before local functions |
| Stage dispatch | `void register(RegisterContext<?> context)`, `void dispatchRegister(RegisterContext<?> context)` | Invokes `register(context)` on self, then recursively dispatches to `children` |

### 2.2 `RegistryGroup` and `Registry`

- **`RegistryGroup`** (`extends Reg<RegistryGroup, Void> implements Consumer<Reg<?, ?>>, IModifierConfigure<RegistryGroup>`):
  - `accept(Reg<?, ?> reg)`: sets `reg.setParent(this)`.
  - `dispatchWhen(BooleanSupplier predicate)` / `isDispatchEnabled()`: overrides `dispatchRegister` to skip the group and its entire subtree whenever `predicate.getAsBoolean()` is `false`.
  - `transform(Function<Reg<?, ?>, Reg<?, ?>> function)`: appends to `transformation`.
- **`Registry`** (`public final class Registry extends RegistryGroup`):
  - Automatically configures `ResourceLocationModifiers.withNamespace(modId)` in its constructor.
  - `getModId()` returns the mod ID.
  - `register(IEventBus modEventBus)` and `@OnlyIn(Dist.CLIENT) registerClient(IEventBus modEventBus)` defer binding via `KasugaLib.afterRunning(modEventBus, ...)` into `RegisterContextRegistry.configure(Side.COMMON / Side.CLIENT, this, modEventBus)`.
  - **Per-registry attachments** (backed by `ConcurrentHashMap`): `getOrCreateAttachment(Class<T>, Supplier<? extends T>)`, `getAttachment(Class<T>)`, `setAttachment(Class<T>, T)`, `removeAttachment(Class<?>)`.
- **`CompositeReg<S extends CompositeReg<?>>`**: composite registration base whose `getEntry()` returns `Map<Class<? extends Registry>, Registry>` (must be overridden by codegen subclasses; otherwise throws `IllegalStateException`).

### 2.3 Core Helpers (`lib.kasuga.registration.core`)

| Type | Key Members | Description |
|---|---|---|
| `RegisterContext<L>` | `onStage(RegistrationStage<T> stage, Consumer<T> consumer)`, `getStage()` | Narrows an event context safely to the matching `RegistrationStage` |
| `RegisterContextRegistry` | `Side.{COMMON, CLIENT}`, `configure(...)`, `register(...)`, `registerBefore(Side, RegistrationStage<?>, RegistryStageDispatchFunction)`, `dispatch(...)` | `@Context` singleton wiring `Registry` instances to stage dispatch functions |
| `Modifier<T>` / `ModifierType<T>` | `ModifierType(boolean skipSelf)` | `skipSelf = true` lets children read a parent's `SCOPE` without the parent applying it to itself |
| `ScopeHelper` | `effect(ModifierType<T> effect, Supplier<T> value)`, `get(Reg<?, ?> self, ModifierType<T> effect)` | Binds a lazily supplied object onto a `SCOPE` modifier |
| `ResourceLocationModifiers` | `ID`, `id(Function<ResourceLocation, ResourceLocation>)`, `withNamespace(String namespace)` | Rewrites default `"minecraft"` namespaces to the mod's namespace |
| `CreativeTabModifiers` | `TYPE`, `set(Supplier<ResourceLocation> tabSupplier)` | Sets a shared creative tab across a `RegistryGroup` or `Registry` |
| `ChildrenLookup` | `findParent(Reg<?, ?> start, Class<T> type, BiFunction<Reg<?, ?>, Class<T>, Object> projection)` | Walks ancestors without resolving cross-registry entries during an active `RegisterEvent` |
| `ChildrenUtils` | `traverse(...)`, `traverseRI(...)` | Traverses `Reg` and `IChildrenConfiguration` / `IAdaptedObject` hierarchies |
| `IModifierConfigure<S>` / `ConfigureAdapter<S>` / `IChildrenConfiguration<S>` / `IAdaptedObject<T>` | `configure(...)`, `addChild(...)`, `getOriginal()` | Configuration interface and adapter contracts used by codegen |

---

## 3. Registration Stages and Event Mapping (`lib.kasuga.registration.stages`)

`DefaultRegisterContextDispatchers` (common) and `DefaultClientRegisterContextDispatchers` (`@BeanOnlyIn.Client`) subscribe NeoForge mod bus listeners in `@PostConstruct`:

| `RegistrationStage` Constant | Payload Type | NeoForge Mod Bus Event | Side |
|---|---|---|---|
| `NEW_REGISTRY` | `NewRegistryEvent` | `NewRegistryEvent` | `COMMON` |
| `REGISTER_EVENT` | `RegisterStageContext` | `RegisterEvent` (`EventPriority.HIGHEST`) | `COMMON` |
| `PAYLOAD_REGISTRATION` | `PayloadRegistrationStage` | `RegisterPayloadHandlersEvent` | `COMMON` |
| `REGISTER_CAPABILITIES` | `CapabilityRegistration` | `RegisterCapabilitiesEvent` | `COMMON` |
| `CREATIVE_TAB_CONTENT_REGISTRATION` | `CreativeTabContentRegistration` | `BuildCreativeModeTabContentsEvent` | `COMMON` |
| `MENU_SCREEN_BINDING` | `MenuScreenBindingStage` | `RegisterMenuScreensEvent` | `CLIENT` |
| `REGISTER_CLIENT_EXTENSIONS` | `FluidClientExtensionsStage` | `RegisterClientExtensionsEvent` | `CLIENT` (calls `registry.dispatchRegister` directly, bypassing `registerBefore`) |
| `BAKING_COMPLETE` | `BakingCompleteStage` | `ModelEvent.BakingCompleted` | `CLIENT` |
| `COMMON_SETUP` | `RegisterStageContext` | — | Declared on `RegistrationStage`, currently unsubscribed |

Key stage payload methods:

- `RegisterStageContext`: `register(ResourceKey<? extends Registry<T>>, ResourceLocation, Supplier<T>)`, `getRegistryKey()`
- `PayloadRegistrationStage`: `PayloadRegistrar getRegistrar(String version)` (memoized with `Util.memoize`)
- `CapabilityRegistration`: `RegisterCapabilitiesEvent getEvent()`
- `CreativeTabContentRegistration`: `getTabKey()`, `getTab()`, `getEvent()`
- `MenuScreenBindingStage`: `register(MenuType<T>, Supplier<MenuRendererBuilder<T, U>>)`
- `FluidClientExtensionsStage`: `getEvent()`, `register(FluidTypeReg<?>)`
- `BakingCompleteStage`, `FMLCommonSetupStageContext`, `RegistrationStageContext`: marker context classes with no payload methods.

---

## 4. Minecraft Registrars (`lib.kasuga.registration.minecraft`)

Most classes in this package are generated from `modules/core/src/codeTemplate` by `:modules:generator` into `lib.kasuga.registration.minecraft.*`, along with `*Configurations` interfaces and `*Modifiers` classes.

### 4.1 Base Class: `MinecraftDeferRegistryReg` and `DeferredFillHolder`

`MinecraftDeferRegistryReg<S extends Reg<S, T>, R, T extends R>` is the base class for entries registered into vanilla or NeoForge `Registry<R>` instances:

- Before `REGISTER_EVENT` runs for `entryKey`, `getHolder()` returns a `DeferredFillHolder<R>`.
- On matching `REGISTER_EVENT`: calls `resolveId()` (`ResourceLocationModifiers.ID` followed by `applyProperties(ResourceLocation.class, id)`), calls `createObject(id)`, binds `DeferredFillHolder`, swaps `holder` to `DeferredHolder.create(entryKey, id)`, posts the entry to the event, and invokes `onRegistered(Consumer<? super T>)` callbacks.
- Accessors: `getEntry()` (throws `IllegalStateException` before registration), `getHolder()`, `getName()`, `getRegistryKey()`, `getResourceLocation()`.

### 4.2 Minecraft Registrar Reference

| Class | Constructor / Factory | Target Registry / Stage | Key Configuration Methods |
|---|---|---|---|
| `BlockReg<T extends Block>` | `BlockReg.of(name, Function<Properties, T>)` / `new BlockReg<>(name, reg -> props -> ...)` | `Registries.BLOCK` | `BlockBehaviour.Properties` fluent methods (`strength`, `destroyTime`, `noOcclusion`, `lightLevel`, `sound`, etc.); children: `withDefaultBlockItem(name)`, `withBlockItem(name, supplier)`; `BlockReg.SCOPE` |
| `ItemReg<T extends Item>` | `ItemReg.of(name, Function<Properties, T>)` | `Registries.ITEM` + `CREATIVE_TAB_CONTENT_REGISTRATION` | `Item.Properties` fluent methods, `isFood(Boolean)`, `food*`, `component(...)`, `tabTo(CreativeTabReg \| Supplier<CreativeModeTab>)`, `tabToByKey(...)` |
| `BlockEntityReg<T extends BlockEntity>` | `BlockEntityReg.of(name, BlockEntitySupplier<T>)` | `Registries.BLOCK_ENTITY_TYPE` + `REGISTER_CAPABILITIES` | `validBlocks(BlockSupplier...)` / `validBlocks(Collection<Supplier<Block>>)` / `validBlocks(BiPredicate<ResourceLocation, Block>)`, `addCapability(BlockCapability<V, C>, ICapabilityProvider)`; `BlockEntityReg.SCOPE` |
| `BlockEntityRendererReg<B>` | `new BlockEntityRendererReg<>(Supplier<BlockEntityRendererBuilder<B>>)` | `BAKING_COMPLETE` -> `RenderingRegistry` | `withBlockEntity(Supplier<BlockEntityType<?>>)`, `withBlockEntities(BiPredicate<ResourceLocation, BlockEntityType<?>>)` |
| `EntityReg<T extends Entity>` | `EntityReg.of(name, BiFunction<EntityType<?>, Level, T>)` | `Registries.ENTITY_TYPE` | `size(w, h)`, `width(w)`, `height(h)`, `category(...)`, `fireImmune()`, `noSave()`, `noSummon()`, `clientTrackingRange(int)`, `updateInterval(int)`, `canSpawnFarFromPlayer()`, `shouldReceiveVelocityUpdates(boolean)`, `withAttributeSupplier(...)`, `withAttributeBuilder(...)` |
| `EntityRendererReg<E>` | `new EntityRendererReg<>(Supplier<EntityRendererBuilder<E>>)` | `BAKING_COMPLETE` -> `RenderingRegistry` | `withEntity(Supplier<EntityType<?>>)`, `withEntities(BiPredicate<ResourceLocation, EntityType<?>>)` |
| `EntityAttributeReg<T>` | `new EntityAttributeReg<>(name, Supplier<T>)` | `Registries.ATTRIBUTE` | Supplies an `Attribute` instance directly |
| `CreativeTabReg` | `new CreativeTabReg(name)` | `Registries.CREATIVE_MODE_TAB` | `title`, `icon`, `displayItems`, `withSearchBar`, `alignedRight`, `hideTitle`, `noScrollBar`, `withTabsBefore`, `withTabsAfter`, etc. |
| `MenuReg<T, U>` | `new MenuReg<>(name, IContainerFactory<T>, Supplier<MenuRendererBuilder<T, U>>)` | `Registries.MENU` + `MENU_SCREEN_BINDING` | Automatically binds the menu screen on clients when `screenSupplier != null` |
| `PayloadReg<T>` | `new PayloadReg<>(name, codec)` / `(name, Supplier<StreamCodec>)` | `PAYLOAD_REGISTRATION` | `stage(PayloadStage.{COMMON, CONFIGURATION, PLAY})`, `version(String)`, `client(Supplier<IPayloadHandler<T>>)`, `server(Supplier<IPayloadHandler<T>>)` |
| `SoundReg` | `SoundReg.of(name)` / `of(name, range)` / `of(name, Function)` | `Registries.SOUND_EVENT` | Variable-range, fixed-range, or custom `SoundEvent`; `SoundReg.SCOPE` |
| `DataComponentReg<T>` | `new DataComponentReg<>(name, UnaryOperator<DataComponentType.Builder<T>>)` | `Registries.DATA_COMPONENT_TYPE` | Builds and registers a `DataComponentType<T>` |
| `RegistryReg<T>` | `new RegistryReg<>(Registry<T>)` | `NEW_REGISTRY` | Registers a custom `net.minecraft.core.Registry` |

#### `PayloadReg` Namespace and Sidedness Guards

- **Namespace guard**: in `ensureEntry()`, if the resolved `ResourceLocation` still has namespace `"minecraft"` (for instance, when `setParent(registry)` was omitted), `PayloadReg` fails fast with `IllegalStateException` instead of claiming the vanilla namespace (verified by `PayloadRegNamespaceTest`).
- **Dedicated server protection**: if `client(...)` is supplied and `lib.kasuga.inject.class_loader.Envs.isDedicatedServer()` is `true`, `PayloadReg` installs a no-op lambda instead of evaluating `clientHandlerSupplier`, preventing client class loading on dedicated servers.

#### Fluid Registration Suite: `FluidReg`, `FluidTypeReg`, `FluidBlockReg`

The 2.0 fluid pipeline targets NeoForge 1.21.1 `BaseFlowingFluid`:

- **`FluidReg<S extends BaseFlowingFluid.Source, F extends BaseFlowingFluid.Flowing>`**:
  - Constructors: `new FluidReg<>(name)` (derives `<name>_flowing`) or `new FluidReg<>(typeName, sourceName, flowingName)`; automatically creates and attaches a child `FluidTypeReg`.
  - Configuration: `instances(sourceFactory, flowingFactory)`, `numericProperties(levelDecrease, slopeDistance, tickRate, explosionResistance)`, `type(t -> ...)`, `block(FluidBlockReg<?>)`, `bucket(String)` / `bucket(ItemReg<?>)`.
  - Accessors: `getSource()`, `getFlowing()`, `getSourceSupplier()`, `getFlowingSupplier()`, `getSourceHolder()`, `getFlowingHolder()`, `getFluidType()`, `getFluidTypeHolder()`.
- **`FluidTypeReg<T extends FluidType>`**:
  - Factories: `FluidTypeReg.of(name)` / `of(name, factory)`; client visuals: `still(path)`, `flowing(path)`, `overlay(path)`, `tint(argb)` / `tint(r, g, b)`, `fogColor(r, g, b)`, `fog(start, end)`; fluid properties: `density`, `viscosity`, `lightLevel`, `temperature`, `supportsBoating`, `sound`, `bucketSounds`, `defaultBucketSounds`, `vaporizeSound`.
  - Registers `IClientFluidTypeExtensions` through `REGISTER_CLIENT_EXTENSIONS` (`RegisterClientExtensionsEvent`) so dedicated servers never touch client texture/fog classes.
- **`FluidBlockReg<T extends LiquidBlock>`**:
  - `FluidBlockReg.of(name, fluid -> props -> ...)`, supporting `BlockBehaviour.Properties` fluent modifiers (`FluidBlockRegConfigurations`).
  - Relies on NeoForge dispatching `minecraft:fluid` before `minecraft:block` and resolves the parent `FluidReg` via `ChildrenLookup.findParent`.

### 4.3 Verified Registration Examples

```java
// 1. Block and BlockItem (from BlockRegistryTest)
public static final Registry REGISTRY = KasugaLibRegistry.getRegistryOf(KasugaLib.MODID);

public static final BlockReg<TestBlock> TEST_BLOCK = BlockReg.of("test_block", TestBlock::new)
        .configure(BlockRegModifiers.BlockProperties.of("destroyTime", p -> { p.destroyTime(10); return p; }))
        .configure(BlockRegModifiers.BlockProperties.of("noOcclusion", p -> { p.noOcclusion(); return p; }))
        .withDefaultBlockItem("test_block")
        .setParent(REGISTRY);

// 2. Fluid, LiquidBlock, and Bucket (from AllFluidTestEntries)
public static final FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing> TEST_FLUID =
        new FluidReg<BaseFlowingFluid.Source, BaseFlowingFluid.Flowing>("test_fluid")
                .instances(BaseFlowingFluid.Source::new, BaseFlowingFluid.Flowing::new)
                .numericProperties(2, 4, 10, 100.0F)
                .type(t -> t.viscosity(1200).density(1400).defaultBucketSounds()
                        .tint(0xFF3FA7D6).still("block/test_fluid_still").flowing("block/test_fluid_flow"))
                .block(FluidBlockReg.of("test_fluid_block", fluid -> props -> new LiquidBlock(fluid, props)))
                .bucket("test_fluid_bucket")
                .setParent(REGISTRY);

// 3. Network Payload (from PayloadRegistryTest)
public static final PayloadReg<TestMyClass> TEST_PAYLOAD = new PayloadReg<>("test_payload", TestMyClass.CODEC)
        .stage(PayloadStage.COMMON)
        .client(() -> (msg, ctx) -> {})
        .server(() -> (msg, ctx) -> {})
        .setParent(REGISTRY);
```

---

## 5. Sided Rendering Registry, Kasuga Registrars, and `FactoryRegistry`

### 5.1 `RenderingRegistry` (`lib.kasuga.registration.beans.rendering`)

`RenderingRegistry` provides side-polymorphic renderer binding:

- `DefaultRenderingRegistry` (`@Singleton`): server fallback that logs a debug message and ignores registration calls.
- `ClientRenderingRegistry` (`@Singleton`, `@Replaces(DefaultRenderingRegistry.class)`, `@BeanOnlyIn.Client`, `implements RenderingRegistry, Configurable`): registers with `BlockEntityRenderers`, `EntityRenderers`, and `DocumentItemRenderer`, and exposes the client config option `ignore_block_entity_renderer` (default `false`).

### 5.2 Kasuga Custom Registrars (`lib.kasuga.registration.kasuga`)

| Class | Description |
|---|---|
| `DeviceAttributeReg<T, U>` | Registers into `DeviceRegistries.DEVICE_ATTRIBUTE_KEY`; has a `protected` constructor and exposes `DeviceAttributeConfigurations` (`indexable()`, `unique()`, `notPersistent()`) |
| `GlobalDeviceReg` | Empty placeholder class |
| `DocumentComponentReg<T>` | Registers custom `DocumentComponentType<?>` instances into `DocumentComponentRegistries.DOCUMENT_COMPONENT_KEY` |
| `DocumentComponentRendererReg<T>` | Binds `DocumentComponentRendererSupplier<T>` through `RenderingRegistry` at `BAKING_COMPLETE`, associated via `withComponent(DocumentComponentReg<?>)` |
| `DocumentComponentTypes` | Built-in component types: `DocumentString`, `DocumentBoolean`, and `DocumentInt` |

### 5.3 Data-Driven Factory Table: `FactoryRegistry` (`lib.kasuga.registration.factory`)

`FactoryRegistry` maintains `ConcurrentHashMap`-backed static factory tables queried by data-driven content handlers for JSON `type` keys (see [Data-Driven Extension API](data-driven/api-extension.md)):

- **Block factories**: `register(String type, BlockFactory factory)`, `register(String type, BlockFactory factory, boolean createsBlockItem)`, `get(String)`, `contains(String)`, `createsBlockItem(String)`
- **Item factories**: `registerItem(String type, ItemFactory factory)`, `getItemFactory(String)`, `containsItem(String)`
- **Block-entity factories**: `registerBlockEntity(String type, BlockEntityFactory factory)`, `getBlockEntityFactory(String)`, `containsBlockEntity(String)`, `getBlockEntityTypes()`
- **Generic factories**: `registerGeneric(String type, GenericFactory factory)`, `getGeneric(String)`, `containsGeneric(String)`
