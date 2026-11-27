# Utils Module (modules/utils)

`modules/utils` provides foundational annotations, lightweight data structures, path/glob matchers, and Gson binding policies with no dependencies on other KasugaLib modules. It sets `FMLModType: GAMELIBRARY` and is bundled into the `:modules:core` and `:modules:library` shadow JARs.

## 1. Module Coordinates and Dependencies

- **Gradle subproject**: `:modules:utils` (group: `lib.kasuga`, Java 21)
- **Consumer dependency**: `implementation project(":modules:utils")`
- **Gson runtime contract**: `modules/utils/build.gradle` declares `compileOnlyApi 'com.google.code.gson:gson:2.10.1'`. Because Minecraft supplies Gson at runtime, `:modules:core` and `:modules:library` do not bundle a duplicate copy in their shaded JARs. Pure JVM consumers outside Minecraft (such as `:modules:render-core` and `:modules:resource-compiler`) must provide Gson on their runtime classpath.

| Package | Responsibility |
|---|---|
| `lib.kasuga.annotations` | Conditional Mixin and code-generation marker annotations |
| `lib.kasuga.utils.json` | Shared `GsonBindings` and `JsonFieldPolicy` validation rules |
| `lib.kasuga.structure` | Lazy cache `LazyRecomputable`, `MapSet`, and `Pair` |
| `lib.kasuga.utils.glob` | Segment-tree glob matching (`GlobMatcher` / `GlobTreeNode`) |
| `lib.kasuga.utils.path` | Extensible segment-filter tree and parallel matcher (`PathMatcher`) |
| `lib.kasuga.utils` | `ValueInspectionUtils` and `VectorUtils` |

---

## 2. Annotations (`lib.kasuga.annotations`)

### 2.1 `@ConditionalMixin` and `MixinCondition`

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

Declares a runtime enablement predicate on a Mixin class so a host Mixin config plugin can evaluate `condition()` before applying the Mixin.

### 2.2 `RegistryGeneration`

```java
public interface RegistryGeneration {
    @interface Modifier {}
    @interface Configure {}
}
```

Container for marker annotations (`@RegistryGeneration.Modifier`, `@RegistryGeneration.Configure`) used by registration code generation. Neither nested annotation specifies `@Retention` or `@Target`, so they retain default `CLASS` retention.

---

## 3. JSON Bindings and Field Policies (`lib.kasuga.utils.json`)

### 3.1 `GsonBindings`

`lib.kasuga.utils.json.GsonBindings` standardizes `snake_case` field naming (`FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES`) and attaches `JsonFieldPolicy` across configuration and model/data-driven DTOs.

| Member | Signature / Type | Behavior |
|---|---|---|
| `builder()` | `public static GsonBuilder builder()` | Creates a `GsonBuilder` with `LOWER_CASE_WITH_UNDERSCORES` and `new JsonFieldPolicy()` |
| `GSON` | `public static final Gson` | `builder().create()` for standard DTO deserialization |
| `STRICT` | `public static final Gson` | Built from `builder()` with `StringAdapter`, `BooleanAdapter` (`boolean` / `Boolean`), and `IntegerAdapter` (`int` / `Integer`) |

The strict adapters on `GsonBindings.STRICT` reject Gson's implicit scalar coercions and throw `JsonParseException` (with `reader.getPath()`) on type mismatch:

- `GsonBindings.StringAdapter`: requires `JsonToken.STRING` (rejects numbers or booleans).
- `GsonBindings.BooleanAdapter`: requires `JsonToken.BOOLEAN` (rejects strings such as `"false"`).
- `GsonBindings.IntegerAdapter`: requires `JsonToken.NUMBER` and calls `new BigDecimal(reader.nextString()).intValueExact()`, rejecting fractional numbers (`1.5`), strings (`"2"`), and 32-bit overflow.

```java
private record Input(String name, boolean enabled, int[] meshIndices) {}

// GsonBindings.STRICT rejects {"name":3}, {"enabled":"false"}, {"mesh_indices":[1.5]} with JsonParseException
Input decoded = GsonBindings.STRICT.fromJson(
        "{\"name\":\"body\",\"enabled\":true,\"mesh_indices\":[0,2]}",
        Input.class
);
```

### 3.2 `JsonFieldPolicy`

`public final class JsonFieldPolicy implements TypeAdapterFactory` enforces two opt-in DTO field policies (scanning up the superclass chain before `Object.class`, skipping `static`, `transient`, and synthetic fields, and honoring `@SerializedName` primary names and `alternate()` aliases):

| Annotation | Target & Retention | Behavior |
|---|---|---|
| `@JsonFieldPolicy.NonNullFields` | `@Inherited`, `@Target(TYPE)`, `@Retention(RUNTIME)` | Throws `JsonParseException` if the root element is JSON `null` or any declared field receives an explicit `null` (including `null` inside arrays or nested arrays); omitted fields keep constructor defaults and unknown fields stay ignored |
| `@JsonFieldPolicy.DefaultOnMismatch` | `@Target(FIELD)`, `@Retention(RUNTIME)` | When a field receives a non-matching JSON kind (`String` requires string, `boolean`/`Boolean` requires boolean, others require number) or explicit `null`, removes that key from a shallow copy of the `JsonObject` before delegating to Gson so the constructor default survives |

`JsonFieldPolicy` never mutates caller-owned `JsonElement` trees and only allocates a shallow copy of the enclosing `JsonObject` when at least one mismatched field is stripped.

```java
@JsonFieldPolicy.NonNullFields
public class ExampleDto {
    public String id = "default";

    @JsonFieldPolicy.DefaultOnMismatch
    public float scale = 1.0f;
}
```

---

## 4. Data Structures (`lib.kasuga.structure`)

### 4.1 `LazyRecomputable<T>`

Lazy evaluation and invalidatable cache (**not thread-safe**; tracks initialization with a dedicated `isCached` flag so `null` values are cached as well):

| API | Description |
|---|---|
| `new LazyRecomputable<>(Supplier<T> supplier)` / `LazyRecomputable.of(supplier)` | Creates a lazy cache |
| `T get()` | Evaluates on first access or after `clear()` |
| `void clear()` | Resets cached value so the next `get()` recomputes |
| `LazyRecomputable.predictable(Supplier<T> supplier, Supplier<Boolean> predict)` | Returns `PredictableLazyRecomputable<T>`; checks `predict.get()` on every `get()` and clears the cache if `false` |

### 4.2 `MapSet<K, V>`

`public class MapSet<K, V> implements Iterable<V>` wraps `HashMap<K, Set<V>>` (backed by `HashSet`):

- `void put(K key, V value)`: inserts `value` into the key's set.
- `Set<V> get(K key)`: returns the mapped set or `Collections.emptySet()`.
- `Iterator<V> iterator()`: flattens values across all keys.

### 4.3 `Pair<K, V>` and `Pair.Mutable<K, V>`

Immutable pair and mutable subclass (with Lombok `@Getter`):

| API | Description |
|---|---|
| `Pair.of(K first, V second)` | Creates an immutable `Pair<K, V>` |
| `getFirst()` / `getSecond()` / `Object get(boolean isFirst)` | Reads components; `get(true)` returns `first` |
| `new Pair.Mutable<>(K first, V second)` | Creates a mutable pair with `setFirst(K)` and `setSecond(V)` |
| `Pair.Mutable.create(boolean first, boolean second)` | Shorthand for `Pair.Mutable<Boolean, Boolean>` |

---

## 5. Path and Glob Matching (`lib.kasuga.utils.glob`, `lib.kasuga.utils.path`)

> For VFS logical path normalization and mount-table routing, see [Virtual file systems](vfs.md). These two utility packages match path-segment lists against tree-structured pattern rules.

### 5.1 `GlobMatcher` and `GlobTreeNode`

`GlobMatcher` compiles segmented path patterns into a prefix tree (`GlobTreeNode`) supporting literal segments, single-segment `"*"`, and recursive `"**"` wildcards:

- `new GlobMatcher(List<List<String>> patterns)`: builds the pattern tree and marks terminal nodes (`setTerminator()`).
- `List<List<String>> match(Function<List<String>, List<String>> listProvider, Predicate<List<String>> willNext)`: traverses lazily using a directory-listing callback.
- `List<List<String>> collect(Stream<List<String>> fileEntries)` / `boolean validate(List<String> entry, GlobTreeNode node)`: filters an existing stream of path-segment lists.

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

### 5.2 `PathMatcher`, `PathEntry`, and `PathFilter`

`lib.kasuga.utils.path` provides a pluggable segment-filter tree supporting discovery-style traversal (`match`) and batch parallel matching (`matchParallel`).

| Type | Key Members | Description |
|---|---|---|
| `PathEntry` | `parse(path)` / `parse(path, sep)` / `parseAny(path)`, `extend(subPath)`, `getParent()` | Extends `ArrayList<String>`; `parseAny` splits on `[\\/]`, while `parse(path)` splits on `File.separator` |
| `PathFilter` | `Result test(String pathPart)`, `boolean willMatchSelf()`, `PathFilter.INIT` | `Result` values: `INITIAL`, `REJECT`, `RECURSIVE_ACCEPT`, `ACCEPT`, `RETURN` |
| `PathFilter.GlobPathFilter` | `new GlobPathFilter(String glob)` | Supports `**` (`RECURSIVE_ACCEPT`, `willMatchSelf() == true`), `*` (`.*`), `?` (`.`), escaping regex metacharacters |
| `PathEntryFilter` | `ArrayList<PathFilter>` | Represents a single multi-segment filter rule |
| `PathEntryFilterTreeNode` | `add(PathEntryFilter)`, `markTerminal()` / `unsetTerminal()`, `addTerminal(PathFilter)`, `replaceTerminal(Function)` | Tree node for compiled filter rules |
| `PathMatcher` | `new PathMatcher(PathEntryFilterTreeNode root)`, `setNoExplore(boolean)`, `match(listFunction, initialPath)`, `matchParallel(entries, node)` | Discovery and batch matching; `noExplore = true` stops descending after matching a terminal node |

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

## 6. Inspection and Vector Helpers (`lib.kasuga.utils`)

### 6.1 `ValueInspectionUtils`

`ValueInspectionUtils.INSTANCE` throttles debug callbacks by `hashCode()` changes:

- `public void print(String key, Object value, Runnable debugger)`: computes `long hashCode = value.hashCode()` and runs `debugger.run()` only when it differs from `lastLoggedHashcode.getOrDefault(key, 0L)`. `value` must be non-null; if the first value for `key` happens to have `hashCode() == 0`, that first invocation is skipped.

### 6.2 `VectorUtils`

- `public static String blockPosAsUnderlineFormat(BlockPos localPos)`: formats a Minecraft `BlockPos` as `"<x>_<y>_<z>"` (requires `net.minecraft.core.BlockPos` on the classpath).
