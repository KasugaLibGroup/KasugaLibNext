# Data-driven extension API (TypeHandler, BuildContext, and property parsers)

## 8. The TypeHandler system

### 8.1 TypeHandler

```java
public interface TypeHandler<T> {                                  // TypeHandler.java:8
    int PHASE_GROUPS = 0;                                          // :11
    int PHASE_CONTENT = 1;                                         // :14
    int PHASE_EMBEDDED = 2;                                        // :17
    String getTypeName();                                          // :20
    int getPhase();                                                // :27
    T parse(JsonObject json);                                      // :29
    void apply(T definition, BuildContext context);                // :31
    default String getParentTypeName() { return null; }            // :38
    default String getEmbeddedKeyName() { return null; }           // :48
    default List<JsonObject> extractEmbedded(JsonObject parentJson) { return null; }  // :50
    default String resolveIdentity(String modId, T definition) { return null; }       // :64
}
```

| Method | Responsibility and constraints | Failure behavior |
|------|-----------|---------|
| `getTypeName()` | The declared top-level JSON field name, unique within `TypeHandlerRegistry` | With the same name a later registration overrides an earlier one, with no warning |
| `getPhase()` | The apply phase, see 2.4 | None |
| `parse` | Turn a JSON object into a definition object | Recommended to be a pure function; a thrown exception loses only the current entry while siblings parse as usual, and produces one `entry <i> in '<key>' failed to parse: <msg>; entry skipped` |
| `apply` | Register the definition into the registration tree | The exception is caught by `applyUnchecked` and turned into a bucket entry, without interrupting other entries |
| `getParentTypeName()` | Non-null means this is an embedded type, and the value is its parent type's field name | `null` means a top-level type |
| `getEmbeddedKeyName()` | The key name of the embedded type inside the parent entry, for diagnostics only | When an embedded type does not override it, `null` is returned and the diagnostic loses one hint |
| `extractEmbedded(parentJson)` | Extract the list of embedded objects from the parent entry | Returning `null` means there is no embedded content; it takes no part in top-level dispatch |
| `resolveIdentity(modId, definition)` | The identity used for deduplication | A default of `null` opts out of deduplication |

### 8.2 TypeHandlerRegistry

```java
public static <T> void register(TypeHandler<T> handler);            // :11
public static TypeHandler<?> get(String typeName);                  // :15
public static Collection<TypeHandler<?>> all();                     // :19
public static TypeHandler<?> findByParent(String parentType);       // :23
```

A global static table (`LinkedHashMap`) shared by all mods, with no namespace isolation. `all()` returns an immutable view preserving registration order. `findByParent` searches linearly and returns only the first match, so a parent type can currently carry at most one embedded subtype.

### 8.3 Top-level vs embedded

A top-level type occupies one top-level JSON field (such as `blocks`). An embedded type occupies no top-level field and can only enter from a parent entry via `extractEmbedded`. `BlockEntityTypeHandler` is the reference implementation (`getParentTypeName` returns `"blocks"`, `getEmbeddedKeyName` returns `"block_entity"`): it copies the parent entry's `id` into the embedded object's `_parent_block` and uses that at apply time to find the parent block back with `context.getBlockReg(...)` (`BlockEntityTypeHandler.java:33-44, 71-101`). When it is written as a top-level field by mistake, `dispatchContent` reports an unknown field and gives the correct key name, while the rest of the file's fields dispatch as usual.

### 8.4 RegTypeHandler

`handler/RegTypeHandler.java` is the abstract base class shared by `blocks` / `items`, pinning down the registration flow.

```java
protected abstract String resolveRawId(T definition);                       // :19
protected abstract Reg<?, ?> createRegistration(T definition, String path); // :21
protected String resolveType(T definition) { return getTypeName(); }        // :28
protected void configureTypeSpecific(T definition, Reg<?, ?> reg) {}        // :30
protected String resolveRegistryGroup(T definition) { return null; }        // :32
protected JsonObject resolveItemProperties(T definition) { return null; }   // :34
protected ResourceLocation resolveCreativeTab(T definition) { return null; }// :36
protected String resolveNamespace(String rawId)                              // :38
protected String resolvePath(String rawId)                                   // :43
public String resolveIdentity(String modId, T definition)                    // :58
public void apply(T definition, BuildContext baseContext)                    // :63
```

The fixed flow of `apply`: split the id into namespace and path (with no `:`, the namespace is treated as `minecraft`); when `createRegistration` returns `null`, log the `No factory for type '<type>' (id '<id>')` WARN and skip; when the namespace is not `minecraft`, attach a `ResourceLocation` mapping to the Reg, which is the namespace-rewrite mechanism; attach the group (falling back to the root group when it cannot be found); resolve the creative tab (definition level first, otherwise inherit from the group level); run type-specific configuration; put the Reg into `context.putReg(getTypeName(), ...)` for cross-handler lookup.

`RegTypeHandler.apply` catches exceptions internally, logs a WARN, and returns (`:107-111`). `resolveItemProperties` is currently declared in the implementation but **has no call site**; the implementations `BlockTypeHandler` / `ItemTypeHandler` each parse properties in their own `configureTypeSpecific` (`BlockTypeHandler.java:68-88`, `ItemTypeHandler.java:64-72`). Whether this hook will be wired up is to be confirmed.

### 8.5 Built-in handlers

| Handler | getTypeName | getPhase | parse output | Source |
|---------|-------------|----------|-----------|------|
| `RegistryGroupHandler` | `registry_groups` | `PHASE_GROUPS` | `RegistryGroupDef(id, parent, properties, item_properties)` | `RegistryGroupHandler.java:20-33` |
| `BlockTypeHandler` | `blocks` | `PHASE_CONTENT` | `BlockDef(id, type, registry_group, properties, item_properties, params)` | `BlockTypeHandler.java:19-34` |
| `ItemTypeHandler` | `items` | `PHASE_CONTENT` | `ItemDef(id, type, registry_group, properties, params)` | `ItemTypeHandler.java:16-30` |
| `BlockEntityTypeHandler` | `block_entities` | `PHASE_EMBEDDED` | `BlockEntityDef(type, parentBlockId, params)`, produced by `extractEmbedded` from the `block_entity` key | `BlockEntityTypeHandler.java:21-53` |

### 8.6 Adding a new type

1. Implement `TypeHandler<T>`. If the output is to go into an MC registry, inherit `RegTypeHandler<T>` instead and implement `resolveRawId` and `createRegistration`.
2. Call `TypeHandlerRegistry.register(handler)` to declare the typeName; the top-level field name is `getTypeName()`.
3. If you go through `RegTypeHandler`, register the corresponding factory with `FactoryRegistry` (see section 10); the `handler` constructs the Reg from the value of the JSON `type` field.
4. Override `resolveIdentity` when deduplication is needed; override `getParentTypeName` / `getEmbeddedKeyName` / `extractEmbedded` when embedding is needed.

`TestGenericFactory.java` is the minimal example you can copy in a test: a custom handler plus `FactoryRegistry.registerGeneric`, with `parse` taking the factory from `FactoryRegistry.getGeneric`. Real dispatch is done by `JsonTreeBuilder`; that example walks `TypeHandlerRegistry.all()` by hand only to assert the parse result.

### 8.7 ContentStructure (shared structure layer)

`structure/ContentStructure.java:30`. The **structure-decision layer** shared by the registration domain (the `JsonTreeBuilder` dispatcher) and the reload domain (`ReloadOrchestrator` and the two decoders): a pure function depending only on Gson and `java.util`, with no Minecraft or loader types, whose every rule is pinned by pure JVM unit tests.

```java
public final class ContentStructure {
    public enum Kind { BODY_NOT_OBJECT, UNKNOWN_KEY, MISSING_KEY, VALUE_NOT_ARRAY, ELEMENT_NOT_OBJECT }  // :33

    public record Issue(Kind kind, String key, int index, JsonElement actual) {}   // :55
    public record Field(List<Element> elements, List<Issue> issues) {}             // :65
    public record Element(int fileIndex, JsonObject body) {}                       // :76

    public static Issue body(JsonElement raw);                        // :84 非对象 body → 一个 issue，否则 null
    public static List<Issue> unknownKeys(JsonElement body, Set<String> knownKeys);  // :98 每个未认领键一个 issue
    public static Field field(JsonElement body, String typeKey);      // :123 单字段结构拆分：有效元素 + issues
    public static String joinWithOr(List<String> items);              // :162 用 " or " 连接，供两处提示复用
    public static String describe(JsonElement json);                  // :177 JSON 类型的人类可读描述
}
```

- `body`: the file body must be a JSON object; `null` is treated as a non-object. Returning `null` means it passed.
- `unknownKeys`: checks key by key, each key **not** in `knownKeys` produces one `UNKNOWN_KEY`; a non-object body returns an empty list (that case is `body`'s job).
- `field`: splits one top-level field. A missing key or a non-array value each produce **one** issue and no element (the caller rejects the whole field on that basis); a non-object element produces an issue while its **siblings are kept** (element-level isolation).
- `describe`: the shared JSON type description (such as `"an array"` / `"a primitive (5)"`), replacing the three private copies that used to be byte-identical.

**One decision, per-side rendering**: `ContentStructure` only decides structure and **never produces user-facing wording**. An `Issue` carries the decision result and the offending value, and each caller renders it with **its own template**. That is what lets the two sides keep their respective wording (in particular, `ReloadHandler.describeFile()` is still the reload domain's own wording contract) and lets the two sides' cross-domain hints point in opposite directions (D9: the registration domain hints "list it under `on_reload`", the reload domain hints "list it under `on_register`"), while the shape rules themselves are implemented in exactly one place.

**Where it sits in the layering**: it stops at the "structure" layer and does not cross into "element → T"; mapping an element to a definition type remains each handler's own `parse` (entry level) / `decode` (file level) job. So after this layer was added, the six handlers' code did not change by a single line. `TypeHandler.parse` / `ReloadHandler.decode` take the `Field.elements()` (`List<Element>`) returned by `field(...)` as input and each do their own type conversion and validation; `Element.fileIndex()` keeps the element's index in the top-level array, so diagnostics can reference the original position.

## 9. BuildContext / RegBuildContext

```java
// BuildContext（基类）
String getModId();                                            // BuildContext.java:17
JsonRegistryGroup getRootGroup();                             // :19
<T> void putMeta(String typeName, String id, T value);        // :22
<T> T getMeta(String typeName, String id);                    // :27

// RegBuildContext（注册场景子类）
void putRegistryGroup(String id, JsonRegistryGroup group);    // RegBuildContext.java:22
JsonRegistryGroup getRegistryGroup(String id);                // :26
void setRegistryGroupCreativeTab(String groupId, ResourceLocation tab);  // :30
ResourceLocation getRegistryGroupCreativeTab(String groupId); // :34
void putReg(String typeName, String id, Reg<?, ?> reg);       // :40
Reg<?, ?> getReg(String typeName, String id);                 // :44
Reg<?, Block> getBlockReg(String id);                         // :50
```

- `getRegistryGroup` / `getReg` return `null` when not found; the caller should fall back to the root group or skip.
- `putMeta` / `getMeta` are the general channel for establishing references between custom types, and do not require the value to be a `Reg`.
- The `typeName` dimension of `putReg` / `getReg` is `getTypeName()`; `getBlockReg(id)` is equivalent to `getReg("blocks", id)`.
- The lifecycle is one `buildForMod`; it is not shared across mods.

## 10. FactoryRegistry

`modules/core/src/main/java/lib/kasuga/registration/factory/FactoryRegistry.java`. The data-driven handlers take their factory from here by the JSON `type` field.

```java
public interface BlockFactory        { Reg<?, Block> create(String id, @Nullable JsonObject params); }        // :18
public interface ItemFactory         { Reg<?, Item> create(String id, @Nullable JsonObject params); }         // :23
public interface BlockEntityFactory  { Reg<?, ?> create(String id, Supplier<Block[]> validBlocks, @Nullable JsonObject params); }  // :28
public interface GenericFactory      { Reg<?, ?> create(String id, JsonObject params); }                       // :33

public static void register(String type, BlockFactory factory);            // :44
public static BlockFactory get(String type);                              // :48
public static boolean contains(String type);                              // :52
public static void registerItem(String type, ItemFactory factory);         // :58
public static ItemFactory getItemFactory(String type);                    // :62
public static boolean containsItem(String type);                          // :66
public static void registerBlockEntity(String type, BlockEntityFactory factory);  // :74
public static BlockEntityFactory getBlockEntityFactory(String type);      // :79
public static boolean containsBlockEntity(String type);                   // :83
public static Set<String> getBlockEntityTypes();                          // :87
public static void registerGeneric(String type, GenericFactory factory);  // :93
public static GenericFactory getGeneric(String type);                     // :95
public static boolean containsGeneric(String type);                       // :97
```

- Internally a `ConcurrentHashMap`; registering the same type twice silently overrides.
- The `id` parameter contains only the path; the namespace has already been stripped by the handler and handed to the Reg's namespace rewrite, so do not prepend a namespace again inside the factory.
- `params` is the raw object of the `params` field in the definition, may be `null`, and its semantics are defined by the factory.
- `BlockEntityFactory.validBlocks` is a `Supplier<Block[]>` and is evaluated lazily; the factory should store the Supplier rather than calling `get()` immediately during construction.
- `get*` returns `null` when nothing is registered, and the caller turns that into a diagnostic.

## 11. Property parsers

### 11.1 JsonPropertyParser

```java
public static final JsonPropertyParser INSTANCE;    // :23
public static JsonPropertyParser getInstance();     // :217
public List<Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> parseBlockProperties(JsonObject json);  // :28
public void registerCompiler(PropertyCompiler compiler);   // :197
public void registerCompiler(String key, BiFunction<String, JsonElement, Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> supplier);  // :201
public void removeCompiler(PropertyCompiler compiler);     // :209
public int compilerSize();                                 // :213
```

- `parseBlockProperties(null)` returns an empty list.
- Compilers are tried in list order, stopping at the first `valid(key, value)` hit. Built-in keys are matched with an exact `ResourceLocation` (`RLCompiler`, `:189`).
- An unmatched key logs the `Unknown block property: <key>` WARN and is skipped, without a bucket entry.
- An illegal value logs the `Failed to parse block property '<key>'` WARN and is skipped.
- `buildCompilers` (`:148`) registers the built-in keys, including the boolean switches, `friction`, `strength`, `map_color`, `sound_type`, `light_emission`, and so on.

### 11.2 JsonItemParser

```java
public static final JsonItemParser INSTANCE;        // :15
public List<Function<Item.Properties, Item.Properties>> parseItemProperties(JsonObject json);  // :38
public void registerParser(String key, ItemPropertyParser parser);  // :67
public interface ItemPropertyParser { Function<Item.Properties, Item.Properties> parse(String key, JsonElement value); }  // :72
```

- `parseItemProperties(null)` returns an empty list.
- `tab` is a reserved key: it is skipped directly at the parser stage (`:52`), and the handler layer translates it into a creative tab binding.
- An unknown key logs the `Unknown item property: <key>` WARN and is skipped.
- Built-in keys: `stacks_to`, `rarity`, `fire_resistant`, `durability`, `no_repair` (`:23-35`).

### 11.3 Property inheritance and override

The order in which properties take effect is decided by the property chain of the Reg tree: parents before children, and whatever applies later overrides what applied earlier. The chain runs root-group properties, parent group, child group, block level; the block level applies last and therefore has the highest priority. Item properties work the same way: a group's `item_properties` comes before a block-level `item_properties`. Override here is achieved through apply order, with no field merging; a key of the same name is overridden whole.


> Other pages in the data-driven section: [Registration-phase API](api-registration.md) · [Extension API](api-extension.md) · [Diagnostics API](api-diagnostics.md) · [Reload API](api-reload.md) · [Section index](README.md)
