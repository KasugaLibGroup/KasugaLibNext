# Inject 依赖注入与环境装配（modules/inject）

`modules/inject` 基于 Micronaut 4.9.10（`micronaut-inject`、`micronaut-aop`）为 NeoForge 模组提供编译期依赖注入、物理端侧 Bean 隔离、`ModConfigSpec` 自动装配与按维度 `SavedData` 注入。

## 1. 模块坐标、打包与注解处理器

- **Gradle 子项目**：`:modules:inject`（Group：`lib.kasuga`，Java 21，`FMLModType: GAMELIBRARY`，依赖 `:modules:utils`）
- **注解处理器**：使用 `@Singleton` / `@Context` / `@Inject` / `@BeanOnlyIn` 的模组需配置：

```groovy
annotationProcessor 'io.micronaut:micronaut-inject-java:4.9.10'
```

- **Shade 重定位提示**：在 `:modules:core` 与 `:modules:library` 的 `-all` shadow JAR 中，`io.micronaut`、`jakarta.inject`、`jakarta.annotation` 与 `lib.kasuga.inject` 会重定位到 `lib.kasuga.vendors.*`（并经 `MicronautServiceShadowTransformer` 合并服务描述文件）。

---

## 2. 上下文引导：`ModApplicationContext`

`lib.kasuga.inject.ModApplicationContext` 负责构建并启动与 NeoForge 模组绑定的 Micronaut `ApplicationContext`。

| 方法签名 | 说明 |
|---|---|
| `public static ApplicationContext create(Class<?> modMainClass)` | 等价于 `create(modMainClass, (String[]) null)` |
| `public static ApplicationContext create(Class<?> modMainClass, String... packages)` | 创建未启动的 `ApplicationContext`，启用 `BeanResolutionTraceMode.STANDARD_OUT`。当 `lib.kasuga.inject.class_loader.Envs.isDevEnvironment()` 为 `true` 时，使用 `CombinedClassLoader(modMainClass.getClassLoader(), ApplicationContext.class.getClassLoader())` |
| `public static void init(ApplicationContext context, IEventBus modEventBus, ModContainer modContainer)` | 注册 3 个命名单例、调用 `context.start()`，然后执行 `Configurable.configure(context, modEventBus, modContainer)` |

`init(...)` 自动注入上下文的命名 Bean：

| Bean 类型 | `@Named` 限定名 | 来源 |
|---|---|---|
| `net.neoforged.bus.api.IEventBus` | `"modEventBus"` | 传入的 `modEventBus` |
| `net.neoforged.bus.api.IEventBus` | `"forgeEventBus"` | `NeoForge.EVENT_BUS` |
| `net.neoforged.fml.ModContainer` | `"modContainer"` | 传入的 `modContainer` |

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

在未传 `packages` 参数且处于生产环境时，`create(...)` 显式调用 `.packages()`（空包扫描声明）；传入非空 `packages` 时则会扫描指定包集合。

---

## 3. 环境探测与端侧 Bean 隔离

### 3.1 `Envs` 工具类

模块中存在两个同名 `Envs` 工具类，均封装 `FMLLoader.isProduction()` 与 `FMLLoader.getDist()`：

| 类 | `isDevEnvironment()` | `isClient()` / `isDedicatedServer()` |
|---|---|---|
| `lib.kasuga.inject.class_loader.Envs` | `!FMLLoader.isProduction()` | 先检查 `FMLLoader.getDist() != null`，纯单元测试环境下不会空指针（推荐） |
| `lib.kasuga.inject.Envs` | `!FMLLoader.isProduction()` | 直接调用 `FMLLoader.getDist().isClient()` / `.isDedicatedServer()` |

### 3.2 `@BeanOnlyIn` 与 `MinecraftDist`

`lib.kasuga.inject.class_loader.BeanOnlyIn` 提供 4 个注解（`@Retention(RUNTIME)`、`@Target({TYPE, METHOD})`），底层均通过 Micronaut `@Requires(condition = ...)` 绑定到 `MinecraftDist` 的内部 `Condition`：

| 注解 | 绑定条件类 | 生效条件 |
|---|---|---|
| `@BeanOnlyIn.Client` | `MinecraftDist.IsClient` | `Envs.isClient()` 为 `true` |
| `@BeanOnlyIn.DedicatedServer` | `MinecraftDist.IsDedicatedServer` | `Envs.isDedicatedServer()` 为 `true` |
| `@BeanOnlyIn.Development` | `MinecraftDist.IsDevelopment` | `Envs.isDevEnvironment()` 为 `true` |
| `@BeanOnlyIn.Never` | `MinecraftDist.Never` | 恒为 `false`（测试用禁用） |

此外，`MinecraftDist.ModLoaded` 是一个抽象 `Condition` 基类，子类实现 `String getModId()` 后按 `ModList.get().isLoaded(getModId())` 判定。

典型用法是在客户端实现类上配合 `@Replaces` 替换通用服务端占位 Bean（见 [侧边模型](sidedness.md) 与 [Core 注册系统](core-registration.md)）：

```java
@Singleton
@Replaces(DefaultRenderingRegistry.class)
@BeanOnlyIn.Client
public class ClientRenderingRegistry implements RenderingRegistry, Configurable {
    // 仅在客户端加载与激活；服务端容器保留 DefaultRenderingRegistry
}
```

当条件不满足时，Micronaut 不会触发对应 Bean 类的初始化（由 `AnnotationTest#shouldNotInjectBeanClassNotLoaded` 与 `TestMustNotInjectBean` 验证）。

---

## 4. 配置项自动装配：`Configurable`

`lib.kasuga.inject.auto_configure.Configurable` 是**接口**（非注解），让 Bean 可以把 NeoForge `ModConfigSpec` 声明拆分到各个子系统内，再在启动期统一合并注册。详见 [配置项](configuration.md)。

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

`Configurable.configure(...)` 的执行规则：

1. 为 `CLIENT`、`SERVER`、`COMMON`、`STARTUP` 各自创建一个 `ModConfigSpec.Builder`。
2. 遍历 `context.getBeansOfType(Configurable.class)`，依次调用 `onConfigure(type, builder)`。
3. 构建各 `IConfigSpec`，仅当 `!spec.isEmpty()` 时调用 `modContainer.registerConfig(type, spec)`。

因此，如果某个配置实现同时标注了 `@BeanOnlyIn.Client`，则在专用服务端上不会创建客户端配置，也不会注册空的客户端配置文件。

---

## 5. 按维度 `SavedData` 注入：`Saved<T>` 与 `SavedFactory`

`lib.kasuga.inject.auto_configure.Saved<T extends SavedData>` 与工厂类 `SavedFactory`（`@Factory`、`@Context`）允许在任意 Bean 中直接 `@Inject Saved<MySavedData>`，由框架接管构造与服务器生命周期绑定（若需世界根目录下的多文件独立 `.nbt` 存储，见 [Core 运行时基础设施](core-runtime.md) 的 `CustomSavedDataManager` / `ScopedNbtSavedData`）。

### 5.1 `Saved<T>` 接口契约

| 方法 | 说明 |
|---|---|
| `T get()` | 获取主世界（`Level.OVERWORLD`）的 `SavedData` 实例 |
| `T get(ResourceKey<Level> levelResourceKey)` | 获取指定维度的 `SavedData` 实例（按需通过 `level.getDataStorage().computeIfAbsent(factory, name)` 加载并缓存） |
| `void onLoad(MinecraftServer server)` | 绑定当前服务器实例并清空旧缓存 |

错误与生命周期边界：

- `Saved.Impl` 监听 `forgeEventBus` 的 `ServerStartedEvent`（调用 `onLoad`）与 `ServerStoppingEvent`（清空 `server` 引用与 `dataMap` 缓存）。
- 在服务器未启动或已停止时调用 `get(...)` 会抛出 `IllegalStateException("The level is not loaded yet.")`。
- 传入未加载维度键时抛出 `IllegalArgumentException("The dimension ... is not loaded.")`。

### 5.2 `SavedFactory` 的反射与命名规则

`SavedFactory#createSaved(ArgumentInjectionPoint<?, ?> ip)` 从注入点提取泛型参数 `T`：

1. **新建实例构造器**：要求 `T` 具有无参 public 构造器，否则抛出 `RuntimeException`。
2. **反序列化构造器 / 工厂方法**：按如下顺序查找任一 public 签名，否则抛出 `RuntimeException`：
   - 构造器 `(CompoundTag)`
   - 构造器 `(CompoundTag, HolderLookup.Provider)`
   - 构造器 `(HolderLookup.Provider, CompoundTag)`
   - 静态方法 `(CompoundTag, HolderLookup.Provider) -> T`
3. **存储文件名**：`CaseFormat.UPPER_CAMEL.to(CaseFormat.LOWER_UNDERSCORE, clazz.getSimpleName())`（例如 `MySavedData` 映射为 `"my_saved_data"`）。

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

## 6. 类加载、Mixin 插件与占位类

| 类型 | 包名 | 作用与当前状态 |
|---|---|---|
| `CombinedClassLoader` | `lib.kasuga.inject.class_loader` | 父加载器为 `null`，按序委派多个 `ClassLoader` 查找类与合并资源 URL（开发期用于连接模组加载器与 Micronaut 类路径） |
| `IPathFinder` / `ForgePathFinder` | `lib.kasuga.inject.class_loader` | `@FunctionalInterface Path find(String path)`；`ForgePathFinder(ModContainer)` 通过 `modContainer.getModInfo().getOwningFile().getFile().findResource(path)` 定位资源 |
| `ModComplexClassLoader` | `lib.kasuga.inject.class_loader` | 对以 `META-INF/micronaut` 或 `/META-INF/micronaut` 开头的 `getResources` 请求重定向到 `pathFinder` |
| `ServiceMixinPlugin` | `lib.kasuga.inject.mixins` | 实现 `IMixinConfigPlugin` 的抽象基类，提供默认空实现（`shouldApplyMixin` 返回 `true`，`getMixins` 与 `getRefMapperConfig` 返回 `null`），由 `KasugaLibMixinPlugin` 等子类继承 |
| `VanillaRegistryInjector` | `lib.kasuga.inject.vanilla` | 当前仅含空方法 `v()` 的占位类 |
| `InjectionPointDiscovery` / `InjectPointMatcher` / `InjectPointDiscoveryResult` | `lib.kasuga.inject.auto_configure` | 当前仅含骨架签名（`addMatcher()` / `discovery()` 返回空结果）的占位类 |
