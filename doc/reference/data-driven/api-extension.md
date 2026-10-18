# 数据驱动扩展 API（TypeHandler、BuildContext 与属性解析器）

## 8. TypeHandler 体系

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

| 方法 | 职责与约束 | 失败行为 |
|------|-----------|---------|
| `getTypeName()` | 声明的顶层 JSON 字段名，在 `TypeHandlerRegistry` 中唯一 | 同名后注册者覆盖先注册者，无告警 |
| `getPhase()` | 应用阶段，见 2.4 | 无 |
| `parse` | 把 JSON 对象转成定义对象 | 建议纯函数；抛异常只丢当前条目、兄弟照常解析，产一条 `entry <i> in '<key>' failed to parse: <msg>; entry skipped` |
| `apply` | 把定义注册进注册树 | 异常被 `applyUnchecked` 捕获并转入桶，不中断其他条目 |
| `getParentTypeName()` | 非 null 表示这是内嵌类型，值是其父类型字段名 | `null` 表示顶层类型 |
| `getEmbeddedKeyName()` | 内嵌类型在父条目里的键名，仅诊断用 | 内嵌类型未覆写时返回 `null`，诊断少一段提示 |
| `extractEmbedded(parentJson)` | 从父条目抽出内嵌对象列表 | 返回 `null` 表示没有内嵌；不参与顶层分发 |
| `resolveIdentity(modId, definition)` | 去重判定 identity | 默认 `null` 表示退出去重 |

### 8.2 TypeHandlerRegistry

```java
public static <T> void register(TypeHandler<T> handler);            // :11
public static TypeHandler<?> get(String typeName);                  // :15
public static Collection<TypeHandler<?>> all();                     // :19
public static TypeHandler<?> findByParent(String parentType);       // :23
```

全局静态表（`LinkedHashMap`），所有 mod 共享，无命名空间隔离。`all()` 返回不可变视图，保注册顺序。`findByParent` 线性查找，只返回第一个匹配，故一个父类型当前最多挂一个内嵌子类型。

### 8.3 顶层与内嵌

顶层类型占一个顶层 JSON 字段（如 `blocks`）。内嵌类型不占顶层字段，只能经 `extractEmbedded` 从父条目进入。`BlockEntityTypeHandler` 是参考实现（`getParentTypeName` 返回 `"blocks"`，`getEmbeddedKeyName` 返回 `"block_entity"`）：把父条目的 `id` 拷进内嵌对象的 `_parent_block`，apply 时据此用 `context.getBlockReg(...)` 找回父方块（`BlockEntityTypeHandler.java:33-44, 71-101`）。写错成顶层字段时，`dispatchContent` 按未知字段报错并给出正确键名，文件其余字段照常分发。

### 8.4 RegTypeHandler

`handler/RegTypeHandler.java` 是 `blocks` / `items` 共用的抽象基类，固化注册流程。

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

`apply` 的固定流程：拆 id 为 namespace 与 path（无 `:` 时 namespace 视为 `minecraft`）；`createRegistration` 返回 `null` 时打 `No factory for type '<type>' (id '<id>')` WARN 并跳过；namespace 非 `minecraft` 时给 Reg 挂 `ResourceLocation` 映射，这是命名空间重写的机制；挂分组（找不到则挂根组）；解析创造标签页（定义级优先，否则继承分组级）；执行类型特定配置；把 Reg 放进 `context.putReg(getTypeName(), ...)` 供跨 handler 查找。

`RegTypeHandler.apply` 内部捕获异常，打 WARN 后返回（`:107-111`）。`resolveItemProperties` 当前在实现里声明但**没有调用点**，实现类 `BlockTypeHandler` / `ItemTypeHandler` 分别在自己的 `configureTypeSpecific` 里解析属性（`BlockTypeHandler.java:68-88`、`ItemTypeHandler.java:64-72`）。这条 hook 是否会被接线待确认。

### 8.5 内置 handler

| Handler | getTypeName | getPhase | parse 产物 | 出处 |
|---------|-------------|----------|-----------|------|
| `RegistryGroupHandler` | `registry_groups` | `PHASE_GROUPS` | `RegistryGroupDef(id, parent, properties, item_properties)` | `RegistryGroupHandler.java:20-33` |
| `BlockTypeHandler` | `blocks` | `PHASE_CONTENT` | `BlockDef(id, type, registry_group, properties, item_properties, params)` | `BlockTypeHandler.java:19-34` |
| `ItemTypeHandler` | `items` | `PHASE_CONTENT` | `ItemDef(id, type, registry_group, properties, params)` | `ItemTypeHandler.java:16-30` |
| `BlockEntityTypeHandler` | `block_entities` | `PHASE_EMBEDDED` | `BlockEntityDef(type, parentBlockId, params)`，由 `extractEmbedded` 从 `block_entity` 键生成 | `BlockEntityTypeHandler.java:21-53` |

### 8.6 新增一种类型

1. 实现 `TypeHandler<T>`。若产物要进 MC 注册表，改继承 `RegTypeHandler<T>`，实现 `resolveRawId` 与 `createRegistration`。
2. 调 `TypeHandlerRegistry.register(handler)` 声明 typeName；顶层字段名即 `getTypeName()`。
3. 若走 `RegTypeHandler`，用 `FactoryRegistry` 注册对应工厂（见第 10 节），`handler` 按 JSON `type` 字段取值构造 Reg。
4. 需要去重时覆写 `resolveIdentity`；需要内嵌时覆写 `getParentTypeName` / `getEmbeddedKeyName` / `extractEmbedded`。

`TestGenericFactory.java` 是可在测试中照抄的最小例子：自定义 handler + `FactoryRegistry.registerGeneric`，`parse` 内从 `FactoryRegistry.getGeneric` 取工厂。真实链路的分派由 `JsonTreeBuilder` 完成，该例手工遍历 `TypeHandlerRegistry.all()` 只为断言解析结果。

### 8.7 ContentStructure（共享结构层）

`structure/ContentStructure.java:30`。注册域（`JsonTreeBuilder` 分派器）与 reload 域（`ReloadOrchestrator` 及两个 decoder）共用的**结构判定层**——纯函数，只依赖 Gson 与 `java.util`，无 Minecraft / 加载器类型，全部规则由纯 JVM 单测锁定。

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

- `body`：文件 body 必须是 JSON 对象；`null` 视为非对象。返回 `null` 表示通过。
- `unknownKeys`：逐键检查，**不在** `knownKeys` 里的各产一个 `UNKNOWN_KEY`；非对象 body 返回空列表（该情形归 `body` 管）。
- `field`：拆一个顶层字段。缺键 / 值非数组各产**一个** issue 且无元素（调用方据此整字段拒绝）；非对象元素产 issue 而**兄弟保留**（元素级隔离）。
- `describe`：共享的 JSON 类型描述（如 `"an array"` / `"a primitive (5)"`），取代此前三处逐字节相同的私有副本。

**判定唯一、渲染分侧**：`ContentStructure` 只判定结构、**从不产出面向用户的文案**。`Issue` 携带判定结果与肇事值，各调用方用**自己的模板**渲染。这样两侧措辞才能各自保持——尤其 `ReloadHandler.describeFile()` 仍是 reload 域自己的文案契约，且两侧的跨域提示方向相反（D9：注册域提示「改列到 `on_reload`」，reload 域提示「改列到 `on_register`」），而形状规则本身只有一处实现。

**分层位置**：它停在「结构」层，不越过「元素 → T」——把元素映射到定义类型仍是各 handler 自己的 `parse`（条目级）/ `decode`（文件级）的职责。所以加入此层后，六个 handler 的代码一行未改。`TypeHandler.parse` / `ReloadHandler.decode` 以 `field(...)` 返回的 `Field.elements()`（`List<Element>`）为输入，各自再做自己的类型转换与校验；`Element.fileIndex()` 保留元素在顶层数组里的下标，供诊断引用原文位置。

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

- `getRegistryGroup` / `getReg` 找不到返回 `null`，调用方应回退根组或跳过。
- `putMeta` / `getMeta` 是自定义类型间建立引用关系的通用通道，不要求值是 `Reg`。
- `putReg` / `getReg` 的 `typeName` 维度即 `getTypeName()`；`getBlockReg(id)` 等价 `getReg("blocks", id)`。
- 生命周期为一次 `buildForMod`，跨 mod 不共享。

## 10. FactoryRegistry

`modules/core/src/main/java/lib/kasuga/registration/factory/FactoryRegistry.java`。data-driven 的 handler 从这里按 JSON `type` 字段取工厂。

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

- 内部为 `ConcurrentHashMap`，重复注册同一 type 静默覆盖。
- `id` 参数只含 path，namespace 已由 handler 剥离并交给 Reg 的命名空间重写；工厂内不要再拼 namespace。
- `params` 是定义里 `params` 字段的原始对象，可为 `null`，语义由工厂定义。
- `BlockEntityFactory.validBlocks` 是 `Supplier<Block[]>`，惰性求值；工厂应存 Supplier，不要在构造时立即 `get()`。
- `get*` 未注册时返回 `null`，由调用方转成诊断。

## 11. 属性解析器

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

- `parseBlockProperties(null)` 返回空列表。
- compiler 按列表顺序尝试，首个 `valid(key, value)` 命中即停。内置键用精确 `ResourceLocation` 匹配（`RLCompiler`，`:189`）。
- 未匹配键打 `Unknown block property: <key>` WARN 并跳过，不入桶。
- 值不合法时打 `Failed to parse block property '<key>'` WARN 并跳过。
- `buildCompilers`（`:148`）注册内置键，含布尔开关、`friction`、`strength`、`map_color`、`sound_type`、`light_emission` 等。

### 11.2 JsonItemParser

```java
public static final JsonItemParser INSTANCE;        // :15
public List<Function<Item.Properties, Item.Properties>> parseItemProperties(JsonObject json);  // :38
public void registerParser(String key, ItemPropertyParser parser);  // :67
public interface ItemPropertyParser { Function<Item.Properties, Item.Properties> parse(String key, JsonElement value); }  // :72
```

- `parseItemProperties(null)` 返回空列表。
- `tab` 是保留键：parser 阶段直接跳过（`:52`），由 handler 层翻译成创造标签页绑定。
- 未知键打 `Unknown item property: <key>` WARN 并跳过。
- 内置键：`stacks_to`、`rarity`、`fire_resistant`、`durability`、`no_repair`（`:23-35`）。

### 11.3 属性继承与覆盖

属性生效顺序由 Reg 树的 property 链决定，父先子后，后应用者覆盖先应用者：根组属性、父分组、子分组、方块级，方块级最后应用因而优先级最高。物品属性同理，分组 `item_properties` 先于方块级 `item_properties`。这是靠应用顺序实现的覆盖，不做字段合并；同名键整键覆盖。


> 数据驱动专题其它页：[注册期 API](api-registration.md) · [扩展 API](api-extension.md) · [诊断 API](api-diagnostics.md) · [重载 API](api-reload.md) · [专题索引](README.md)
