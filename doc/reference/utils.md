# Utils 工具模块（modules/utils）

`modules/utils` 提供不依赖 KasugaLib 其它模块的基础注解、数据结构、路径/glob 匹配器与 Gson 绑定策略。该模块声明 `FMLModType: GAMELIBRARY`，打包时会嵌入 `:modules:core` 与 `:modules:library` 的 shadow JAR 中。

## 1. 模块坐标与依赖

- **Gradle 子项目**：`:modules:utils`（Group：`lib.kasuga`，Java 21）
- **消费方引入**：`implementation project(":modules:utils")`
- **Gson 运行时契约**：`modules/utils/build.gradle` 对 `com.google.code.gson:gson:2.10.1` 仅声明 `compileOnlyApi`。Minecraft 运行时已自带 Gson，因此 `:modules:core` 与 `:modules:library` 的 shadow JAR 不会重复打包 Gson；脱离 Minecraft 运行时的纯 JVM 消费方（如 `:modules:render-core`、`:modules:resource-compiler`）需自行在 `runtimeClasspath` 提供 Gson。

| 包名 | 职责 |
|---|---|
| `lib.kasuga.annotations` | 条件 Mixin 与代码生成辅助注解 |
| `lib.kasuga.utils.json` | 共享 `GsonBindings` 与 `JsonFieldPolicy` 校验策略 |
| `lib.kasuga.structure` | 懒计算缓存 `LazyRecomputable`、`MapSet`、`Pair` |
| `lib.kasuga.utils.glob` | 分段通配符树匹配 `GlobMatcher` / `GlobTreeNode` |
| `lib.kasuga.utils.path` | 可扩展路径段过滤器与并行匹配 `PathMatcher` |
| `lib.kasuga.utils` | `ValueInspectionUtils`、`VectorUtils` |

---

## 2. 注解（`lib.kasuga.annotations`）

### 2.1 `@ConditionalMixin` 与 `MixinCondition`

```java
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface ConditionalMixin {
    Class<? extends MixinCondition> condition();
}

@FunctionalInterface
public interface MixinCondition {
    boolean shouldEnableMixin();
}
```

用于在 Mixin 类上声明运行时启用条件，由宿主 Mixin 插件通过 `condition()` 指定的 `MixinCondition` 实现类判断是否应用该 Mixin。

### 2.2 `RegistryGeneration`

```java
public interface RegistryGeneration {
    @interface Modifier {}
    @interface Configure {}
}
```

代码生成阶段使用的标记注解容器。两个嵌套注解均未显式声明 `@Retention` / `@Target`（默认 `CLASS` 保留期）。

---

## 3. JSON 绑定与字段策略（`lib.kasuga.utils.json`）

### 3.1 `GsonBindings`

`lib.kasuga.utils.json.GsonBindings` 为配置文件与模型/数据驱动 DTO 提供统一的 `snake_case` 字段映射（`FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES`）并自动挂载 `JsonFieldPolicy`。

| 成员 | 签名 / 类型 | 行为说明 |
|---|---|---|
| `builder()` | `public static GsonBuilder builder()` | 创建带 `LOWER_CASE_WITH_UNDERSCORES` 与 `new JsonFieldPolicy()` 的 `GsonBuilder` |
| `GSON` | `public static final Gson` | `builder().create()`，用于常规 DTO 反序列化 |
| `STRICT` | `public static final Gson` | 在 `builder()` 基础上注册 `StringAdapter`、`BooleanAdapter`（`boolean` / `Boolean`）与 `IntegerAdapter`（`int` / `Integer`） |

`STRICT` 中的三个嵌套 `TypeAdapter` 禁止 Gson 默认的隐式标量强转，不匹配时抛出 `JsonParseException`（附带 `reader.getPath()`）：

- `GsonBindings.StringAdapter`：要求 `JsonToken.STRING`（拒绝数字或布尔值自动转字符串）。
- `GsonBindings.BooleanAdapter`：要求 `JsonToken.BOOLEAN`（拒绝 `"false"` 等字符串）。
- `GsonBindings.IntegerAdapter`：要求 `JsonToken.NUMBER`，通过 `new BigDecimal(reader.nextString()).intValueExact()` 校验精确整数，拒绝小数（如 `1.5`）、字符串（如 `"2"`）与 32 位整数溢出。

```java
private record Input(String name, boolean enabled, int[] meshIndices) {}

// GsonBindings.STRICT 将 {"name":3}、{"enabled":"false"}、{"mesh_indices":[1.5]} 均判为 JsonParseException
Input decoded = GsonBindings.STRICT.fromJson(
        "{\"name\":\"body\",\"enabled\":true,\"mesh_indices\":[0,2]}",
        Input.class
);
```

### 3.2 `JsonFieldPolicy`

`public final class JsonFieldPolicy implements TypeAdapterFactory` 提供两项声明式 DTO 字段策略（沿继承链向上扫描至 `Object.class`，忽略 `static`、`transient` 与 synthetic 字段，支持 `@SerializedName` 的主名与 `alternate()` 别名）：

| 注解 | 目标与保留期 | 行为 |
|---|---|---|
| `@JsonFieldPolicy.NonNullFields` | `@Inherited`, `@Target(TYPE)`, `@Retention(RUNTIME)` | 根对象为 JSON `null` 或任意非静态字段出现显式 `null`（包括数组及嵌套数组内的 `null` 元素）时抛出 `JsonParseException`；省略的字段保留构造默认值，未知字段仍被忽略 |
| `@JsonFieldPolicy.DefaultOnMismatch` | `@Target(FIELD)`, `@Retention(RUNTIME)` | 当该字段的 JSON 值不是匹配的基本类型（`String` 对应字符串、`boolean`/`Boolean` 对应布尔、其余按数值匹配）或为显式 `null` 时，在反序列化前从浅拷贝后的 `JsonObject` 移除该键，从而保留 DTO 构造默认值 |

`JsonFieldPolicy` 对未知子对象和未修改子树保持只读语义，仅在需要剔除不匹配字段时才创建顶层 `JsonObject` 的浅拷贝，不修改调用方持有的原始 `JsonElement` 树。

```java
@JsonFieldPolicy.NonNullFields
public class ExampleDto {
    public String id = "default";

    @JsonFieldPolicy.DefaultOnMismatch
    public float scale = 1.0f;
}
```

---

## 4. 数据结构（`lib.kasuga.structure`）

### 4.1 `LazyRecomputable<T>`

懒计算与可失效缓存包装器（**非线程安全**；通过独立的 `isCached` 布尔标记跟踪状态，因此也会缓存 `null` 结果）：

| API | 说明 |
|---|---|
| `new LazyRecomputable<>(Supplier<T> supplier)` / `LazyRecomputable.of(supplier)` | 创建惰性缓存 |
| `T get()` | 首次调用或 `clear()` 后求值并缓存 |
| `void clear()` | 清空缓存并在下次 `get()` 时重算 |
| `LazyRecomputable.predictable(Supplier<T> supplier, Supplier<Boolean> predict)` | 返回 `PredictableLazyRecomputable<T>`；每次 `get()` 前先检查 `predict.get()`，返回 `false` 时自动调用 `clear()` |

### 4.2 `MapSet<K, V>`

`public class MapSet<K, V> implements Iterable<V>` 基于 `HashMap<K, Set<V>>`（值集合为 `HashSet`）的多值映射：

- `void put(K key, V value)`：向键对应的 `Set` 插入元素。
- `Set<V> get(K key)`：返回对应集合，缺省返回 `Collections.emptySet()`。
- `Iterator<V> iterator()`：扁平遍历全部键下的所有值。

### 4.3 `Pair<K, V>` 与 `Pair.Mutable<K, V>`

不可变二元组及可变子类（含 Lombok `@Getter`）：

| API | 说明 |
|---|---|
| `Pair.of(K first, V second)` | 创建 `Pair<K, V>` |
| `getFirst()` / `getSecond()` / `Object get(boolean isFirst)` | 读取成员；`get(true)` 返回 `first` |
| `new Pair.Mutable<>(K first, V second)` | 创建可变二元组，提供 `setFirst(K)` / `setSecond(V)` |
| `Pair.Mutable.create(boolean first, boolean second)` | 快捷创建 `Pair.Mutable<Boolean, Boolean>` |

---

## 5. 路径与 Glob 匹配（`lib.kasuga.utils.glob` / `lib.kasuga.utils.path`）

> VFS 逻辑路径规范化与挂载表路由见 [虚拟文件系统（VFS）](vfs.md)；本节两个包面向按路径段树进行的模式过滤。

### 5.1 `GlobMatcher` 与 `GlobTreeNode`

`GlobMatcher` 将一组路径段模式编译为前缀树（`GlobTreeNode`），支持精确段名、单段通配 `*` 与跨任意层通配 `**`：

- `new GlobMatcher(List<List<String>> patterns)`：构建模式树，并在每个模式末尾标记终止节点（`setTerminator()`）。
- `List<List<String>> match(Function<List<String>, List<String>> listProvider, Predicate<List<String>> willNext)`：按目录枚举函数懒遍历并收集匹配路径。
- `List<List<String>> collect(Stream<List<String>> fileEntries)` / `boolean validate(List<String> entry, GlobTreeNode node)`：对已有路径列表做过滤校验。

```java
GlobMatcher matcher = new GlobMatcher(List.of(
        List.of("a", "b", "d"),
        List.of("a", "**", "d")
));
List<List<String>> matched = matcher.collect(Stream.of(
        List.of("a", "b", "c", "d"),
        List.of("a", "b", "e", "f")
));
```

### 5.2 `PathMatcher`、`PathEntry` 与 `PathFilter`

`lib.kasuga.utils.path` 提供面向可插拔段过滤器（`PathFilter`）的前缀树匹配器，支持懒加载目录探索（`match`）与批量并行分支过滤（`matchParallel`）。

| 类型 | 关键成员 | 说明 |
|---|---|---|
| `PathEntry` | `parse(path)` / `parse(path, sep)` / `parseAny(path)`、`extend(subPath)`、`getParent()` | 继承 `ArrayList<String>`；`parseAny` 按 `[\\/]` 分段，`parse(path)` 按 `File.separator` 分段 |
| `PathFilter` | `Result test(String pathPart)`、`boolean willMatchSelf()`、`PathFilter.INIT` | `Result` 枚举：`INITIAL`、`REJECT`、`RECURSIVE_ACCEPT`、`ACCEPT`、`RETURN` |
| `PathFilter.GlobPathFilter` | `new GlobPathFilter(String glob)` | 支持 `**`（`RECURSIVE_ACCEPT` 且 `willMatchSelf() == true`）、`*`（`.*`）、`?`（`.`），并自动转义正则元字符 |
| `PathEntryFilter` | `ArrayList<PathFilter>` | 表示单条多段过滤规则 |
| `PathEntryFilterTreeNode` | `add(PathEntryFilter)`、`markTerminal()` / `unsetTerminal()`、`addTerminal(PathFilter)`、`replaceTerminal(Function)` | 过滤规则树节点 |
| `PathMatcher` | `new PathMatcher(PathEntryFilterTreeNode root)`、`setNoExplore(boolean)`、`match(listFunction, initialPath)`、`matchParallel(entries, node)` | 发现式匹配与集合并行匹配；`noExplore = true` 时在当前节点命中终端后停止继续展开子路径 |

```java
PathEntryFilterTreeNode root = new PathEntryFilterTreeNode(PathFilter.INIT);
PathEntryFilter rule = new PathEntryFilter();
rule.add(new PathFilter.GlobPathFilter("**"));
rule.add(new PathFilter.GlobPathFilter("*.txt"));
root.add(rule);

PathMatcher matcher = new PathMatcher(root);
Collection<PathEntry> matched = matcher.matchParallel(entries, root);
```

---

## 6. 调试与坐标辅助（`lib.kasuga.utils`）

### 6.1 `ValueInspectionUtils`

`ValueInspectionUtils.INSTANCE` 提供按变化触发的调试日志节流：

- `public void print(String key, Object value, Runnable debugger)`：计算 `long hashCode = value.hashCode()`，仅当与 `lastLoggedHashcode.getOrDefault(key, 0L)` 不同时执行 `debugger.run()` 并更新记录。注意 `value` 不能为 `null`；若首次传入对象的 `hashCode()` 恰好为 `0`，该次调用会被跳过。

### 6.2 `VectorUtils`

- `public static String blockPosAsUnderlineFormat(BlockPos localPos)`：将 Minecraft `BlockPos` 格式化为 `"<x>_<y>_<z>"`（需运行环境存在 `net.minecraft.core.BlockPos`）。
