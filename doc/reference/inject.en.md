# Inject Dependency Injection and Environment Wiring (modules/inject)

`modules/inject` builds on Micronaut 4.9.10 (`micronaut-inject`, `micronaut-aop`) to provide compile-time dependency injection for NeoForge mods, physical-sided Bean isolation, automatic `ModConfigSpec` wiring, and per-dimension `SavedData` injection.

## 1. Module Coordinates, Packaging, and Annotation Processing

- **Gradle subproject**: `:modules:inject` (group: `lib.kasuga`, Java 21, `FMLModType: GAMELIBRARY`, depends on `:modules:utils`)
- **Annotation processor**: any module defining `@Singleton`, `@Context`, `@Inject`, or `@BeanOnlyIn` beans must configure:

```groovy
annotationProcessor 'io.micronaut:micronaut-inject-java:4.9.10'
```

- **Shade relocation note**: in the `:modules:core` and `:modules:library` `-all` shadow JARs, `io.micronaut`, `jakarta.inject`, `jakarta.annotation`, and `lib.kasuga.inject` are relocated under `lib.kasuga.vendors.*` and merged via `MicronautServiceShadowTransformer`.

---

## 2. Context Bootstrap: `ModApplicationContext`

`lib.kasuga.inject.ModApplicationContext` creates and boots the Micronaut `ApplicationContext` bound to a NeoForge mod.

| Method Signature | Behavior |
|---|---|
| `public static ApplicationContext create(Class<?> modMainClass)` | Delegates to `create(modMainClass, (String[]) null)` |
| `public static ApplicationContext create(Class<?> modMainClass, String... packages)` | Builds an unstarted `ApplicationContext` with `BeanResolutionTraceMode.STANDARD_OUT`. When `lib.kasuga.inject.class_loader.Envs.isDevEnvironment()` is `true`, wraps `modMainClass.getClassLoader()` and `ApplicationContext.class.getClassLoader()` in `CombinedClassLoader` |
| `public static void init(ApplicationContext context, IEventBus modEventBus, ModContainer modContainer)` | Registers 3 named singletons, calls `context.start()`, and runs `Configurable.configure(context, modEventBus, modContainer)` |

Named singletons registered by `init(...)`:

| Bean Type | `@Named` Qualifier | Value |
|---|---|---|
| `net.neoforged.bus.api.IEventBus` | `"modEventBus"` | Passed `modEventBus` |
| `net.neoforged.bus.api.IEventBus` | `"forgeEventBus"` | `NeoForge.EVENT_BUS` |
| `net.neoforged.fml.ModContainer` | `"modContainer"` | Passed `modContainer` |

```java
@Mod(KasugaLib.MODID)
public class KasugaLib {
    public static final ApplicationContext context = ModApplicationContext.create(KasugaLib.class);
    public static final String MODID = "kasuga_lib";

    public KasugaLib(IEventBus modEventBus, ModContainer modContainer) {
        ModApplicationContext.init(context, modEventBus, modContainer);
        ModLoader.postEvent(new KasugaLibStartupEvent());
    }
}
```

In production mode (`!Envs.isDevEnvironment()`) when no `packages` array is supplied, `create(...)` calls `.packages()` with an empty scan list; when non-empty `packages` are provided, it passes them to `.packages(packages)`.

---

## 3. Environment Detection and Sided Bean Isolation

### 3.1 `Envs` Utilities

Two `Envs` classes exist in `modules/inject`, both delegating to `FMLLoader.isProduction()` and `FMLLoader.getDist()`:

| Class | `isDevEnvironment()` | `isClient()` / `isDedicatedServer()` |
|---|---|---|
| `lib.kasuga.inject.class_loader.Envs` | `!FMLLoader.isProduction()` | Null-checks `FMLLoader.getDist() != null` before inspecting dist; safe in unit tests (recommended) |
| `lib.kasuga.inject.Envs` | `!FMLLoader.isProduction()` | Dereferences `FMLLoader.getDist()` directly without a null check |

### 3.2 `@BeanOnlyIn` and `MinecraftDist`

`lib.kasuga.inject.class_loader.BeanOnlyIn` defines 4 annotations (`@Retention(RUNTIME)`, `@Target({TYPE, METHOD})`), each meta-annotated with Micronaut's `@Requires(condition = ...)` against a nested `Condition` in `MinecraftDist`:

| Annotation | Condition Class | Matches When |
|---|---|---|
| `@BeanOnlyIn.Client` | `MinecraftDist.IsClient` | `Envs.isClient()` is `true` |
| `@BeanOnlyIn.DedicatedServer` | `MinecraftDist.IsDedicatedServer` | `Envs.isDedicatedServer()` is `true` |
| `@BeanOnlyIn.Development` | `MinecraftDist.IsDevelopment` | `Envs.isDevEnvironment()` is `true` |
| `@BeanOnlyIn.Never` | `MinecraftDist.Never` | Always `false` (disables bean loading) |

`MinecraftDist.ModLoaded` is an abstract `Condition` whose `matches(...)` returns `ModList.get().isLoaded(getModId())`.

A common pattern combines `@BeanOnlyIn.Client` with `@Replaces` to override a server-safe fallback bean only on the physical client (see [Sidedness Model](sidedness.md) and [Core Mod Entry and Registration System](core-registration.md)):

```java
@Singleton
@Replaces(DefaultRenderingRegistry.class)
@BeanOnlyIn.Client
public class ClientRenderingRegistry implements RenderingRegistry, Configurable {
    // Loaded and active only on physical clients; dedicated servers retain DefaultRenderingRegistry
}
```

When a condition evaluates to `false`, Micronaut does not load or initialize the bean class (verified by `AnnotationTest#shouldNotInjectBeanClassNotLoaded` and `TestMustNotInjectBean`).

---

## 4. Configuration Auto-Wiring: `Configurable`

`lib.kasuga.inject.auto_configure.Configurable` is a Java **interface** (not an annotation) that lets each subsystem declare its NeoForge `ModConfigSpec` options while registering one unified config spec per `ModConfig.Type`. See also [Configuration Options](configuration.md).

```java
public interface Configurable {
    static void configure(ApplicationContext context, IEventBus modEventBus, ModContainer modContainer);

    default void onConfigure(ModConfig.Type type, ModConfigSpec.Builder builder);
    default void configureClient(ModConfigSpec.Builder builder) {}
    default void configureServer(ModConfigSpec.Builder builder) {}
    default void configureCommon(ModConfigSpec.Builder builder) {}
    default void configureStartup(ModConfigSpec.Builder builder) {}
}
```

`Configurable.configure(...)` runs immediately after `context.start()`:

1. Creates one `ModConfigSpec.Builder` each for `CLIENT`, `SERVER`, `COMMON`, and `STARTUP`.
2. Calls `onConfigure(type, builder)` on every bean returned by `context.getBeansOfType(Configurable.class)`.
3. Builds each `IConfigSpec` and calls `modContainer.registerConfig(type, spec)` only when `!spec.isEmpty()`.

Because only active beans participate, `@BeanOnlyIn.Client` beans implementing `Configurable` will not emit client config entries on a dedicated server.

---

## 5. Per-Dimension `SavedData` Injection: `Saved<T>` and `SavedFactory`

`lib.kasuga.inject.auto_configure.Saved<T extends SavedData>` and `SavedFactory` (`@Factory`, `@Context`) allow any bean to `@Inject Saved<MySavedData>` with automatic NBT factory synthesis and server lifecycle binding. (For multi-file custom `.nbt` storage rooted in the save directory, see `CustomSavedDataManager` / `ScopedNbtSavedData` in [Core Runtime Infrastructure](core-runtime.md).)

### 5.1 `Saved<T>` Contract

| Method | Behavior |
|---|---|
| `T get()` | Returns the Overworld (`Level.OVERWORLD`) `SavedData` instance |
| `T get(ResourceKey<Level> levelResourceKey)` | Computes and caches the instance for the given dimension via `level.getDataStorage().computeIfAbsent(factory, name)` |
| `void onLoad(MinecraftServer server)` | Binds the active server and clears any cached entries |

Lifecycle and error behavior:

- `Saved.Impl` listens to `ServerStartedEvent` (`onLoad`) and `ServerStoppingEvent` (`onUnload`, clearing `server` and `dataMap`) on `forgeEventBus`.
- Calling `get(...)` while no server is active throws `IllegalStateException("The level is not loaded yet.")`.
- Requesting an unloaded dimension throws `IllegalArgumentException("The dimension ... is not loaded.")`.

### 5.2 `SavedFactory` Resolution Rules

`SavedFactory#createSaved(ArgumentInjectionPoint<?, ?> ip)` resolves the `T` type argument from the injection point:

1. **Default supplier**: looks for a public no-arg constructor on `T`; throws `RuntimeException` if missing.
2. **Loader function**: looks for the first public constructor or static method matching:
   - Constructor `(CompoundTag)`
   - Constructor `(CompoundTag, HolderLookup.Provider)`
   - Constructor `(HolderLookup.Provider, CompoundTag)`
   - Static method `(CompoundTag, HolderLookup.Provider) -> T`
   Throws `RuntimeException` if none is found.
3. **Storage name**: converts `clazz.getSimpleName()` via `CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, ...)` (for example, `MySavedData` becomes `"my_saved_data"`).

```java
public class MySavedData extends SavedData {
    public MySavedData() {}
    public MySavedData(CompoundTag tag, HolderLookup.Provider provider) {}

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
        return tag;
    }
}

@Context
public class SavedTestModule {
    @Inject
    Saved<MySavedData> myData;
}
```

---

## 6. ClassLoaders, Mixin Base Plugin, and Stubs

| Type | Package | Role and Current Status |
|---|---|---|
| `CombinedClassLoader` | `lib.kasuga.inject.class_loader` | Parent-`null` delegating loader that queries delegates sequentially for `findClass`/`getResource` and merges all URLs in `getResources` (bridges mod loader and Micronaut classpath in dev) |
| `IPathFinder` / `ForgePathFinder` | `lib.kasuga.inject.class_loader` | `@FunctionalInterface Path find(String path)`; `ForgePathFinder(ModContainer)` resolves resources via `modContainer.getModInfo().getOwningFile().getFile().findResource(path)` |
| `ModComplexClassLoader` | `lib.kasuga.inject.class_loader` | Redirects `getResources(name)` to `pathFinder` when `name` starts with `META-INF/micronaut` or `/META-INF/micronaut` |
| `ServiceMixinPlugin` | `lib.kasuga.inject.mixins` | Abstract `IMixinConfigPlugin` base with no-op lifecycle hooks (`shouldApplyMixin` returns `true`; `getMixins` and `getRefMapperConfig` return `null`), extended by `KasugaLibMixinPlugin` |
| `VanillaRegistryInjector` | `lib.kasuga.inject.vanilla` | Placeholder class with a single empty method `v()` |
| `InjectionPointDiscovery` / `InjectPointMatcher` / `InjectPointDiscoveryResult` | `lib.kasuga.inject.auto_configure` | Placeholder types whose `addMatcher()` / `discovery()` methods currently perform no scanning |
