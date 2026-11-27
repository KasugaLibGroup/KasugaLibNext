# 数据驱动扩展 API（TypeHandler、BuildContext 与属性解析器）

## 8. TypeHandler 体系

### 8.1 TypeHandler

```java
public interface TypeHandler<T> {                                                     // TypeHandler.java:13
    int PHASE_GROUPS = 0;                                                             // :16
    int PHASE_CONTENT = 1;                                                            // :19
    int PHASE_EMBEDDED = 2;                                                           // :22
    String getTypeName();                                                             // :25
    int getPhase();                                                                   // :32
    T parse(JsonObject json);                                                         // :34
    void apply(T definition, BuildContext context);                                   // :36
    default Set<HandlerCapability> capabilities() { return Set.of(); }                // :42
    default boolean strictValidationImplemented() { return false; }                   // :53
    default void validate(T definition, ValidationContext context) {}                 // :61
    default List<RegistryClaim> claims(T definition, ValidationContext context) { return List.of(); } // :69
    default DefinitionKey parentDefinition(T definition) { return null; }             // :77
    default String getParentTypeName() { return null; }                               // :86
    default String getEmbeddedKeyName() { return null; }                              // :96
    default List<JsonObject> extractEmbedded(JsonObject parentJson) { return null; }  // :98
    default String resolveIdentity(String modId, T definition) { return null; }       // :112
    default String resolveIdentity(String modId, T definition, boolean strict) {      // :118
        return resolveIdentity(modId, definition);
    }
}
```

| 方法 | 职责与约束 | 失败行为 |
|------|-----------|---------|
| `getTypeName()` | 声明的顶层 JSON 字段名；v1 在 `TypeHandlerRegistry` 中唯一，v2 `DataDrivenBuildPipeline` 额外拒绝重复 `typeName` | v1 同名后注册者静默覆盖先注册者；v2 `STRICT` 下报 `HANDLER_NOT_STRICT_READY` |
| `getPhase()` | 应用阶段：`PHASE_GROUPS(0)` → `PHASE_CONTENT(1)` → `PHASE_EMBEDDED(2)` | 无 |
| `parse(json)` | 把 JSON 对象转成定义对象 | 建议纯函数；v1 抛异常跳过单条并入桶；v2 记为 `PARSE_FAILED` |
| `apply(definition, context)` | 把定义注册进注册树或上下文 | v1 由 `applyUnchecked` 捕获入桶；v2 抛异常立即中止管线返回 `Status.FAILED` |
| `capabilities()` | v2 声明预检能力、注册表 claim 语义与多来源覆盖保留行为（见 8.8） | 默认空集；在 `BuildOptions.STRICT` 下若被文件实际用到则触发 `HANDLER_NOT_STRICT_READY` |
| `strictValidationImplemented()` | v2 显式确认已真正实现严格 `validate`（不能只挂 `STRICT_PREFLIGHT` 能力标记） | 默认 `false`；`STRICT` 下若未返回 `true` 报 `HANDLER_NOT_STRICT_READY` |
| `validate(definition, context)` | v2 `STRICT` 模式预检，只在 `prepare` 期调用、不得触发任何注册副作用 | 通过 `ValidationContext.error/warning` 写 `BuildDiagnostic` |
| `claims(definition, context)` | v2 声明该定义将在哪些 MC 注册表占用的 `ResourceLocation`（见 8.8） | `BuildOptions.STRICT` 下冲突或异常产 `CLAIM_CONFLICT` / `CLAIM_FAILED` |
| `parentDefinition(definition)` | v2 返回父级 `DefinitionKey`（用于 `GROUP_CYCLE` 环检测与 `stableApplyOrder` 父先子后拓扑序） | 默认 `null` 表示无显式父定义 |
| `getParentTypeName()` | 非 `null` 表示内嵌类型，值为父类型字段名 | `null` 表示顶层类型 |
| `getEmbeddedKeyName()` | 内嵌类型在父条目里的键名，仅诊断提示用 | `null` 时诊断少一段键名提示 |
| `extractEmbedded(parentJson)` | 从父条目抽取内嵌对象列表 | 返回 `null` 表示没有内嵌；不参与顶层分发 |
| `resolveIdentity(modId, definition)` | 去重 / 选择阶段使用的规范化 identity | 默认 `null` 表示退出去重 |
| `resolveIdentity(modId, definition, strict)` | 按模式区分 identity（`strict=true` 时 `RegTypeHandler` 保留 `minecraft:`，否则使用 `ofLegacy`） | 默认转调双参重载 |

### 8.2 TypeHandlerRegistry

```java
public static <T> void register(TypeHandler<T> handler);            // TypeHandlerRegistry.java:11
public static TypeHandler<?> get(String typeName);                  // :15
public static Collection<TypeHandler<?>> all();                     // :19
public static TypeHandler<?> findByParent(String parentType);       // :23
```

- v1 入口（`JsonTreeBuilder`）共享的全局静态表（`LinkedHashMap`），无命名空间隔离。`all()` 返回保持注册顺序的不可变视图；`findByParent` 线性查找并返回第一个匹配项，故一个父类型当前最多挂一个内嵌子类型。
- v2 `DataDrivenBuildPipeline` **不隐式读取**该静态表，而由调用方在构造管线时显式传入 `Collection<? extends TypeHandler<?>>`（通常先调用 `JsonTreeBuilder.ensureHandlersRegistered()` 后读取 `TypeHandlerRegistry.all()`）。

### 8.3 顶层与内嵌

顶层类型占一个顶层 JSON 字段（如 `blocks`、`items`、`registry_groups`）。内嵌类型不占顶层字段，只能经 `extractEmbedded` 从父条目进入。`BlockEntityTypeHandler` 是参考实现（`getParentTypeName` 返回 `"blocks"`，`getEmbeddedKeyName` 返回 `"block_entity"`，`BlockEntityTypeHandler.java:48-51`）：`extractEmbedded`（`:54-67`）把父条目的 `id` 拷入内嵌对象的 `_parent_block`，`apply` 时据此在 `context.getBlockReg(...)` 中找回父方块（`:137-170`）。若把 `block_entities` 误写为顶层字段，`dispatchContent`（v1）或 `DataDrivenBuildPipeline`（v2）会将其判为未知顶层字段并提示改写到 `blocks[].block_entity`，文件其余字段仍正常分发。

### 8.4 RegTypeHandler

`handler/RegTypeHandler.java` 是 `blocks` / `items` 共用的抽象基类，固化了 MC 注册表型条目的身份归一、严格预检、registry claim 与 apply 流程：

```java
protected abstract String resolveRawId(T definition);                                    // :26
protected abstract Reg<?, ?> createRegistration(T definition, String path);              // :28
protected String resolveType(T definition) { return getTypeName(); }                     // :35
protected void configureTypeSpecific(T definition, Reg<?, ?> reg) {}                     // :37
protected String resolveRegistryGroup(T definition) { return null; }                     // :39
protected JsonObject resolveItemProperties(T definition) { return null; }                // :41
protected ResourceLocation resolveCreativeTab(T definition) { return null; }             // :43
protected FactoryRegistry.FactoryType factoryType() { ... }                              // :45
protected String claimRegistry(T definition) { ... }                                       // :49
protected String resolveNamespace(String rawId)                                          // :57
protected String resolvePath(String rawId)                                               // :62
public String resolveIdentity(String modId, T definition)                                // :77
public String resolveIdentity(String modId, T definition, boolean strict)                // :82
public Set<HandlerCapability> capabilities()                                             // :89
public boolean strictValidationImplemented()                                             // :97
public void validate(T definition, ValidationContext context)                            // :102
public List<RegistryClaim> claims(T definition, ValidationContext context)               // :144
public void apply(T definition, BuildContext baseContext)                                // :155
```

- **能力声明**：默认返回 `{HandlerCapability.STRICT_PREFLIGHT, HandlerCapability.REGISTRY_CLAIMS}`（`:89-93`），且 `strictValidationImplemented()` 返回 `true`（`:97-99`）。
- **identity 分叉**：`resolveIdentity(modId, def)` 走 `EffectiveId.ofLegacy`（`:77-79`）；`resolveIdentity(modId, def, strict)` 在 `strict == true` 时走 `EffectiveId.of` 保留显式 `minecraft:`（`:82-86`）。
- **严格 `validate`（`:102-141`）**：校验 `rawId` 非空、包含命名空间前缀（`rawId.indexOf(':') > 0`，在 `STRICT` 下拒绝裸 ID）且满足 `ResourceLocation.tryParse`；通过 `context.factoryAvailable(factoryType(), resolveType(def))` 校验工厂存在；若声明了 `registry_group`，调用 `context.requireDefinition(new DefinitionKey("registry_groups", groupId), "/registry_group")` 验证分组可见性（缺失/未声明依赖/多依赖歧义会分别触发 `MISSING_REFERENCE` / `REFERENCE_NOT_DECLARED` / `REFERENCE_AMBIGUOUS`）。
- **严格 `claims`（`:144-151`）**：按 `rawId` 与 `context.hostModId()` 解析有效 `ResourceLocation` 并提交 `claimRegistry(definition)` 声明；子类可扩展额外 claim（如 `BlockTypeHandler` 的伴生 `minecraft:item`）。
- **`apply` 固定流程（`:155-202`）**：
  1. 拆分 namespace 与 path；无工厂时，若 `context.isStrict()` 抛出 `IllegalStateException`，否则打 WARN 并跳过（`:161-166`）。
  2. 当 `rawId.indexOf(':') >= 0 && (context.isStrict() || !namespace.equals("minecraft"))` 时给 `Reg` 挂 `ResourceLocation` 命名空间重写（`:168-174`）——v2 `STRICT` 下由此允许覆写或显式指向 `minecraft:` 命名空间，而 `LEGACY` / v1 沿用历史行为。
  3. 挂分组（找不到则回退根组）、绑定创造模式标签页（定义级优先，否则继承分组级）、执行 `configureTypeSpecific`、存入 `context.putReg(getTypeName(), ...)`（`:175-191`）。
  4. 异常处理：`context.isStrict()` 时重抛异常使管线进入 `Status.FAILED`，非严格模式打 WARN 返回（`:192-201`）。
- **保留未用钩子**：`resolveItemProperties`（`:41`）目前仍无调用点，`BlockTypeHandler` 与 `ItemTypeHandler` 各自仍在 `configureTypeSpecific` 中解析物品属性。

### 8.5 内置 handler

| Handler | `getTypeName` | `getPhase` | 能力与严格支持 | `parse` 产物 | 出处 |
|---------|---------------|-----------|----------------|--------------|------|
| `RegistryGroupHandler` | `registry_groups` | `PHASE_GROUPS` | `STRICT_PREFLIGHT`, `NON_REGISTRATION`, `strictValidationImplemented=true` | `RegistryGroupDef(id, parent, properties, itemProperties)` | `RegistryGroupHandler.java:26-108` |
| `BlockTypeHandler` | `blocks` | `PHASE_CONTENT` | `STRICT_PREFLIGHT`, `REGISTRY_CLAIMS`, `strictValidationImplemented=true` | `BlockDef(id, type, registryGroup, properties, itemProperties, params, stateProperties)` | `BlockTypeHandler.java:25-158` |
| `ItemTypeHandler` | `items` | `PHASE_CONTENT` | `STRICT_PREFLIGHT`, `REGISTRY_CLAIMS`, `strictValidationImplemented=true` | `ItemDef(id, type, registryGroup, properties, params)` | `ItemTypeHandler.java:19-91` |
| `BlockEntityTypeHandler` | `block_entities` | `PHASE_EMBEDDED` | `STRICT_PREFLIGHT`, `REGISTRY_CLAIMS`, `strictValidationImplemented=true` | `BlockEntityDef(beType, parentBlockId, params)` | `BlockEntityTypeHandler.java:29-175` |

补充差异：

- **`RegistryGroupHandler`**：`resolveIdentity` 保持原串（`:66-68`），`parentDefinition` 返回 `registry_groups:<parent>` 供 `GROUP_CYCLE` 检测与父先子后排序（`:101-106`），`validate` 调用 `JsonPropertyParser.validateStrict`、`JsonItemParser.validateStrict` 及 `requireDefinition` 检查 `parent`（`:71-98`）。
- **`BlockTypeHandler`**：`validate`（`:70-105`）除基类校验外还运行 `JsonPropertyParser.validateStrict`、`JsonItemParser.validateStrict`，并在 `stateProperties != null` 时以 `ValidationContext.factorySupports(..., StatefulBlockFactory.class)` 要求工厂实现 `StatefulBlockFactory`；`claims`（`:108-123`）在 `FactoryRegistry.createsBlockItem(def.type())` 为 `true` 时同时声明 `minecraft:block` 与 `minecraft:item` 两条 `RegistryClaim`；`apply` 中当 `stateProperties != null` 且工厂未实现 `StatefulBlockFactory` 时抛异常（`:39-44`）。
- **`ItemTypeHandler`**：`validate`（`:59-71`）调用 `JsonItemParser.validateStrict`。
- **`BlockEntityTypeHandler`**：`validate`（`:99-121`）检查 `parentBlockId`、`factoryAvailable(BLOCK_ENTITY, def.beType())` 及 `requireDefinition(blocks:<parent>)`；`claims`（`:124-134`）在 `minecraft:block_entity_type` 下声明 `<ns>:<path>_be`；`apply` 在 `context.isStrict()` 下发现缺失父方块或工厂时直接抛异常（`:141-166`）。

### 8.6 新增一种类型（v1 与 v2）

1. 实现 `TypeHandler<T>`；若产物要进入 Minecraft 注册表，优先继承 `RegTypeHandler<T>`。
2. 如需接入 v2 `BuildOptions.STRICT`：
   - 在 `capabilities()` 中声明 `HandlerCapability.STRICT_PREFLIGHT`，并在 `REGISTRY_CLAIMS` 与 `NON_REGISTRATION` 间二选一（按需附加 `OVERLAY`）；
   - 覆写 `strictValidationImplemented()` 返回 `true`；
   - 实现无副作用的 `validate(def, ValidationContext)`；若产生注册表条目，同时实现 `claims(def, ValidationContext)`；
   - 实现 `resolveIdentity(modId, def, strict)`（有父引用链时另实现 `parentDefinition`）。
3. 将工厂注册到 `FactoryRegistry`（见第 10 节）。
4. 集成路径：
   - **v1**：调用 `TypeHandlerRegistry.register(handler)`，由 `JsonTreeBuilder` 自动发现。
   - **v2**：在宿主构造 `DataDrivenBuildPipeline` 时显式把 handler 传入 `handlers` 集合（若调用 `TypeHandlerRegistry.register(handler)`，可统一通过 `TypeHandlerRegistry.all()` 收集）。

示例骨架：`TestGenericFactory.java` 展示了自定义 handler + `FactoryRegistry.registerGeneric` 的最小结构；真实构建链由 `JsonTreeBuilder`（v1）或 `DataDrivenBuildPipeline`（v2）负责分发与 `apply`。

### 8.7 ContentStructure（共享结构层）

`structure/ContentStructure.java:30`。v1 注册域（`JsonTreeBuilder` 分派器）与 reload 域（`ReloadOrchestrator` 及两个 decoder）共用的**结构判定层**——纯函数，只依赖 Gson 与 `java.util`，无 Minecraft / 加载器类型。

```java
public final class ContentStructure {
    public enum Kind { BODY_NOT_OBJECT, UNKNOWN_KEY, MISSING_KEY, VALUE_NOT_ARRAY, ELEMENT_NOT_OBJECT }  // :33

    public record Issue(Kind kind, String key, int index, JsonElement actual) {}   // :55
    public record Field(List<Element> elements, List<Issue> issues) {}             // :65
    public record Element(int fileIndex, JsonObject body) {}                       // :76

    public static Issue body(JsonElement raw);                                     // :84
    public static List<Issue> unknownKeys(JsonElement body, Set<String> knownKeys); // :98
    public static Field field(JsonElement body, String typeKey);                   // :123
    public static String joinWithOr(List<String> items);                           // :162
    public static String describe(JsonElement json);                               // :177
}
```

- `body`：文件 body 必须是 JSON 对象；`null` 视为非对象。返回 `null` 表示通过。
- `unknownKeys`：逐键检查不在 `knownKeys` 里的顶层字段，各产一个 `UNKNOWN_KEY`；非对象 body 返回空列表。
- `field`：拆一个顶层字段。缺键或值非数组各产**一个** issue 且无元素（调用方整字段拒绝）；非对象元素产 issue 而**兄弟保留**。
- `describe`：共享的 JSON 类型描述（如 `"an array"` / `"a primitive (5)"`）。

**判定唯一、渲染分侧**：`ContentStructure` 只判定结构、从不产出面向用户的文案，由调用方用自己的模板渲染（包括注册域与重载域各自反方向的 D9 跨域提示）。v2 `DataDrivenBuildPipeline` 使用 `StrictResourceJson` 配合结构化 `BuildDiagnostic` 走独立解码与字段检查路径。

### 8.8 HandlerCapability、DefinitionKey 与 RegistryClaim

这四个类型位于 `lib.kasuga.registration.data_driven.pipeline`，构成 v2 多来源管线的能力模型与跨来源占坑（claim）模型：

#### HandlerCapability（`pipeline/HandlerCapability.java:6-26`）

```java
public enum HandlerCapability {
    STRICT_PREFLIGHT,   // 支持 BuildOptions.STRICT 下的 validate(...) 预检
    NON_REGISTRATION,   // apply 仅修改构建元数据（如 registry_groups），不向 MC 注册表注册
    REGISTRY_CLAIMS,    // 通过 claims(...) 声明 MC 注册表占用键
    OVERLAY             // 选择阶段保留同一 DefinitionKey 下的全部来源候选，不按 last-wins 折叠
}
```

`DataDrivenBuildPipeline` 对能力声明有两项硬规则：

1. **严格完备性校验（`checkStrictCapabilities`，`DataDrivenBuildPipeline.java:1101-1147`）**：在 `BuildOptions.STRICT` 下，凡本轮被 `on_register` 文件实际用到的 handler（以及同一 `handlers` 列表中重复声明同一 `typeName` 的 handler），必须同时满足：
   - 包含 `STRICT_PREFLIGHT`；
   - `strictValidationImplemented()` 返回 `true`；
   - `NON_REGISTRATION` 与 `REGISTRY_CLAIMS` **互斥且必具其一**（即 `hasNonReg ^ hasClaims`）。
   否则发射 `Stage.PARSE` / `Code.HANDLER_NOT_STRICT_READY` 的阻断错误。
2. **多来源选择行为（`selectDefinitions`，`DataDrivenBuildPipeline.java:1149-1183`）**：当同一 `DefinitionKey` 在多个来源或文件中出现多个候选时，若 handler 满足：
   `capabilities.contains(HandlerCapability.OVERLAY) || (options.isStrict() && capabilities.contains(HandlerCapability.REGISTRY_CLAIMS) && !capabilities.contains(HandlerCapability.NON_REGISTRATION))`
   则**保留该 key 下的全部候选**；其余情况仅保留按 `(sourceOrder, declarationOrder)` 最大的 last-wins 胜者。因此，任何带 `REGISTRY_CLAIMS` 的类型在 `STRICT` 下若不同 sources 重复定义同一逻辑 ID，所有候选都会保留并在随后进入 `validate` / `claims` 校验以报出重名 / claim 冲突，而不是被 silently override 掩盖。

#### DefinitionKey（`pipeline/DefinitionKey.java:11-30`）

```java
public record DefinitionKey(String typeDomain, String id) implements Comparable<DefinitionKey>
```

管线内标识单条逻辑定义的二元组，其中 `typeDomain` 为 `TypeHandler.getTypeName()`，`id` 为 `resolveIdentity(...)` 的返回值。

#### RegistryClaim 与 RegistryKey（`pipeline/RegistryClaim.java:20-56`）

```java
public record RegistryClaim(
        String registry,
        ResourceLocation id,
        SourceDescriptor source,
        int sourceOrder,
        DefinitionKey definitionKey,
        LogicalPath logicalPath,
        String jsonPointer
) {
    public RegistryKey registryKey() { return new RegistryKey(registry, id); }
    public record RegistryKey(String registry, ResourceLocation id) {}
}
```

每条 `RegistryClaim` 把「某个定义准备占据的 `(registry, id)`」精准定位到来源、逻辑路径和 JSON Pointer；不同来源、不同 `typeDomain`（如方块自带 item 占 `minecraft:item` 与同名独立 item 碰撞）或与 `reservedRegistryClaims` 发生 `(registry, id)` 碰撞时，均生成 `Stage.CLAIMS` / `Code.CLAIM_CONFLICT` 诊断。

#### RegistryClaimSnapshot（`pipeline/RegistryClaimSnapshot.java:15-39`）

```java
public static Set<RegistryClaim.RegistryKey> collect(Reg<?, ?> root)   // :19
```

从已有的宿主 `Reg` 树做广度优先遍历，收集所有已启用分派分组（`RegistryGroup.isDispatchEnabled() == true`）下注册条目的 `(registry, id)` 键，构造传入 `DataDrivenBuildPipeline` 的 `reservedRegistryClaims`，避免数据驱动 JSON 与宿主 Java 代码硬编码注册项发生静默 ID 碰撞。

---

## 9. BuildContext / RegBuildContext 与 ValidationContext

### 9.1 BuildContext 与 RegBuildContext

```java
// BuildContext（context/BuildContext.java:12-98）
String getModId();                                                                // :34
String getHostModId();                                                            // :36（默认同 getModId()）
JsonRegistryGroup getRootGroup();                                                 // :38
boolean isStrict();                                                               // :40（默认 false）
Optional<LocatedDefinition<?>> getCurrentDefinition();                            // :42
@Nullable String getSourceId();                                                   // :47
@Nullable String getOwnerId();                                                    // :52
Set<String> getNamespaces();                                                      // :56
@Nullable String getCurrentHandlerType();                                         // :61
@Nullable String getCurrentLogicalPath();                                         // :66
@Nullable String getCurrentJsonPointer();                                         // :71
<R> R withCurrentDefinition(@Nullable LocatedDefinition<?> def, Supplier<R> work);// :78
<T> void putMeta(String typeName, String id, T value);                            // :89
<T> T getMeta(String typeName, String id);                                        // :94

// RegBuildContext（context/RegBuildContext.java:10-57）
public RegBuildContext(String modId, JsonRegistryGroup rootGroup);                // :17（strict=false）
public RegBuildContext(String modId, JsonRegistryGroup rootGroup, boolean strict);// :21
void putRegistryGroup(String id, JsonRegistryGroup group);                        // :26
JsonRegistryGroup getRegistryGroup(String id);                                    // :30
void setRegistryGroupCreativeTab(String groupId, ResourceLocation tab);           // :34
ResourceLocation getRegistryGroupCreativeTab(String groupId);                     // :38
void putReg(String typeName, String id, Reg<?, ?> reg);                           // :44
Reg<?, ?> getReg(String typeName, String id);                                     // :48
Reg<?, Block> getBlockReg(String id);                                             // :54
```

- `getRegistryGroup` / `getReg` 找不到返回 `null`，调用方在非严格模式下回退根组或跳过。
- `putMeta` / `getMeta` 是自定义类型间建立引用关系的通用通道，不要求值是 `Reg`。
- `putReg` / `getReg` 的 `typeName` 维度即 `getTypeName()`；`getBlockReg(id)` 等价 `getReg("blocks", id)`。
- v2 管线在 `apply` 每条定义时外包 `withCurrentDefinition(def, ...)`（`:78-87`），使得任何 handler 在 `apply` 期间都可通过 `getSourceId()` / `getOwnerId()` / `getNamespaces()` / `getCurrentJsonPointer()` 获取当前条目的来源归属，并在 `isStrict()` 为 `true` 时切换严格异常行为。

### 9.2 ValidationContext（预检上下文）

`validation/ValidationContext.java:18-112`（v2 管线实现为 `pipeline/PipelineValidationContext.java:20-273`），在 `DataDrivenBuildPipeline.prepare` 的 `VALIDATION`（调用 `TypeHandler.validate`）与 `CLAIMS`（调用 `TypeHandler.claims`）阶段传入，不提供写注册表的入口：

```java
String hostModId();                                                                // :19
SourceDescriptor source();                                                         // :21
int sourceOrder();                                                                 // :23
LocatedDefinition<?> currentDefinition();                                          // :25
Set<String> directDependencies();                                                  // :27
Optional<LocatedDefinition<?>> find(DefinitionKey key);                            // :33
List<LocatedDefinition<?>> findAllVisible(DefinitionKey key);                      // :42
List<LocatedDefinition<?>> findAll(DefinitionKey key);                             // :51
default List<LocatedDefinition<?>> findAll(String typeDomain)                      // :60（PipelineValidationContext 实现）
boolean canReference(DefinitionKey key);                                           // :65
boolean requireDefinition(DefinitionKey key, String relPtr);                       // :67
boolean resourceExists(ResourceDomain domain, ResourceLocation id);                // :69
boolean factoryAvailable(FactoryType factoryType, String factoryId);               // :71
boolean factorySupports(FactoryType factoryType, String factoryId, Class<?> type); // :73
boolean isReserved(String symbol);                                                 // :75
Set<String> reservedSymbols();                                                     // :77
RegistryClaim claim(String registry, ResourceLocation id);                         // :79
default RegistryClaim claimAt(String registry, ResourceLocation id, String relPtr) // :81
void error(Code code, String relPtr, String msg, String sugg);                     // :97
void warning(Code code, String relPtr, String msg, String sugg);                   // :99

enum ResourceDomain { ASSETS, DATA }                                               // :101
enum FactoryType { BLOCK, ITEM, BLOCK_ENTITY, GENERIC }                            // :106
```

关键语义契约：

- **可见性与歧义规则**：
  - `findAllVisible(key)` 只返回属于当前定义同一 `ownerId` 或其 `directDependencies()`（直接依赖、不传递）的候选列表，并保持会话声明顺序（`PipelineValidationContext.java:88-95`）。
  - `find(key)` 只在 `findAllVisible(key).size() == 1` 时返回 `Optional.of(...)`；存在 0 个或 **2 个及以上**可见候选时均返回 `Optional.empty()`（`:82-85`）。
  - `requireDefinition(key, relPtr)`（`:115-155`）：存在唯一可见定义时返回 `true`；若无可见候选则返回 `false` 并记错（全局 `findAll(key)` 也为空时报 `MISSING_REFERENCE`，否则报 `REFERENCE_NOT_DECLARED`）；若可见候选数 `> 1` 则同样返回 `false` 并报 `REFERENCE_AMBIGUOUS`。
  - `findAll(String typeDomain)`（`ValidationContext.java:60-63`，`PipelineValidationContext.java:103-107`）：按会话声明顺序返回已解析定义中 `key.typeDomain().equals(typeDomain)` 的**全部**候选（跨所有 `id` 与来源），供消费侧做跨定义枚举校验；接口默认实现抛 `UnsupportedOperationException`。
- **资源可见域**：`resourceExists(domain, id)`（`:158-184`）在当前 `ownerId` 与 `directDependencies()` 内查找 `assets/<ns>/...` 或 `data/<ns>/...`；当 `domain == ResourceDomain.ASSETS` 时还会额外检索 `prepare(..., sharedAssetOwners)` 中声明的共享资产 owner。
- **构造 `RegistryClaim`**：`PipelineValidationContext.claim(registry, id)` 与 `claimAt(registry, id, relPtr)`（`:218-240`）纯构造并**返回**一条附带当前来源与 JSON Pointer（经 `JsonPointers.append` 拼接）的 `RegistryClaim`，供 `TypeHandler.claims(...)` 返回给管线收集；在 `validate(...)` 中直接调用它们**不会**向管线记录任何占位副作用。

---

## 10. FactoryRegistry 与内建模型工厂

### 10.1 FactoryRegistry

`modules/core/src/main/java/lib/kasuga/registration/factory/FactoryRegistry.java:15-121`。data-driven 的 handler 从这里按 JSON `type` 字段查找并创建注册对象。

```java
public interface BlockFactory       { Reg<?, Block> create(String id, @Nullable JsonObject params); } // :18
public interface ItemFactory        { Reg<?, Item> create(String id, @Nullable JsonObject params); }  // :23
public interface BlockEntityFactory { Reg<?, ?> create(String id, Supplier<Block[]> validBlocks, @Nullable JsonObject params); } // :28
public interface GenericFactory     { Reg<?, ?> create(String id, JsonObject params); }              // :33

public static void register(String type, BlockFactory factory);                                      // :45（createsBlockItem=false）
public static void register(String type, BlockFactory factory, boolean createsBlockItem);            // :52
public static BlockFactory get(String type);                                                         // :65
public static boolean contains(String type);                                                         // :69
public static boolean createsBlockItem(String type);                                                 // :73
public static void registerItem(String type, ItemFactory factory);                                   // :79
public static ItemFactory getItemFactory(String type);                                               // :83
public static boolean containsItem(String type);                                                     // :87
public static void registerBlockEntity(String type, BlockEntityFactory factory);                     // :95
public static BlockEntityFactory getBlockEntityFactory(String type);                                 // :100
public static boolean containsBlockEntity(String type);                                              // :104
public static Set<String> getBlockEntityTypes();                                                     // :108
public static void registerGeneric(String type, GenericFactory factory);                             // :114
public static GenericFactory getGeneric(String type);                                                // :116
public static boolean containsGeneric(String type);                                                  // :118
```

若方块需要支持 JSON 里的 `state_properties`，工厂必须实现 `lib.kasuga.registration.data_driven.state.StatefulBlockFactory`（扩展 `BlockFactory`，提供 `create(String id, @Nullable JsonObject params, StatePropertySet properties)`，`StatefulBlockFactory.java:10-19`）。

- 内部使用 `ConcurrentHashMap`，同名注册静默覆盖。
- `createsBlockItem` 标志：双参 `register(type, factory)` 默认设为 `false`；仅当传 `true`（如调用了 `.withDefaultBlockItem(id)` 的工厂）时，`BlockTypeHandler.claims` 才会在 `REGISTRY_CLAIMS` 预检阶段同步认领 `minecraft:item` 注册位。
- `id` 参数只含 path，namespace 已由 handler 剥离并交由 `Reg` 重写；工厂内部不得再拼接 namespace。
- `BlockEntityFactory.validBlocks` 为惰性 `Supplier<Block[]>`，不得在工厂构造方法内立即 `.get()`。

### 10.2 内建方块与方块实体工厂

| 工厂类与模块 | 注册的 `type` | 能力与契约 |
|---|---|---|
| `DataDrivenBlockFactory`（`modules/data-driven`） | 方块 `"data_driven_block"` | 实现 `StatefulBlockFactory`（`createsBlockItem=true`），构建支持 `state_properties` 的 `DataDrivenBlock`；由 `JsonTreeBuilder.ensureHandlersRegistered()` 或显式调用 `DataDrivenBlockFactory.registerIfAbsent()` 注册 |
| `FsmBlockEntityFactories`（`modules/modelling`） | 方块 `"fsm_block"`（`createsBlockItem=true`）；BE `"fsm_be"` | `fsm_block` 绑定 `FsmBlock`（非 stateful）；`fsm_be` 绑定 `AnimationBlockEntity`，从 `params` 读取 `state_machine` / `model` / `model_name`。需显式触发类加载或调用 `FsmBlockEntityFactories.registerBuiltin()` |
| `ProxyModelBlockFactories`（`modules/modelling`） | 方块 `"proxy_model"`（`BLOCK_TYPE`，`StatefulBlockFactory`，`createsBlockItem=true`）；BE `"proxy_model_be"`（`BLOCK_ENTITY_TYPE`） | `@Context` bean，在 `@PostConstruct init()` 自动调用幂等的 `registerBuiltin()` |

`ProxyModelBlockFactories`（`modules/modelling/.../proxies/ProxyModelBlockFactories.java:39-403`）关键契约与扩展接口：

- **`proxy_model` 方块主机**：`ProxyModelBlock` 实现 `EntityBlock`，通过 `StatePropertyContext` 自动挂载 `state_properties`，在 `onBlockStateChange` 转发 `StateProperty.notifyChanges(level, pos, oldState, newState)`，为水平 `facing` 与 `hinge` 提供原版放置/旋转/镜像行为，并以 `RenderShape.ENTITYBLOCK_ANIMATED`（实体渲染形态）避免烘培模型缺失时泄漏紫黑格贴图。若 JSON 方块条目带有非空 `params`，`validateBlockParams(params)` 会立即抛 `IllegalArgumentException`。
- **`proxy_model_be` 模型解析公开入口**：
  ```java
  public record ModelSettings(
          ProxyModelSelection selection,
          float yawOffset,
          Map<String, Float> facingYaw,
          @Nullable ModelLightingMode lightingMode
  )
  public static ModelSettings parseModelSettings(@Nullable JsonObject params)  // :131
  ```
  `parseModelSettings` 支持消费方在自定义代理宿主中复用与 `proxy_model_be` 完全一致的 `params` 解析逻辑（`model`、`model_name`、`default_model`、`yaw_offset`、`facing_yaw`、`lighting_mode`、`model_groups`、`model_by_state`；若出现已移除的 `params.animation` 则直接抛 `IllegalArgumentException`）。JSON 字段细节见 [schema.md](schema.md#29-内建-proxy_model-proxy_model_be-参数)。
- **Java 动画与状态控制扩展点**：消费 mod 不应在 JSON `proxy_model` 中声明交互或播放逻辑，而应继承 `ProxyModelBlock` 并覆写：
  ```java
  public ProxyComposition<ProxyModelBlockEntity, ProxyModelInput, Void, Void, Void>
  createProxyComposition(ProxyModelBlockEntity element)
  ```
  同时将自定义 `StatefulBlockFactory` 注册为自有 `type`（可借助 `ProxyModelBlockEntity` 与 `ProxyModelProxy.builder(element)` 定制状态机、播控与局部动作）。

---

## 11. 属性解析器

### 11.1 JsonPropertyParser

`property/JsonPropertyParser.java:22-328`：

```java
public static final JsonPropertyParser INSTANCE;                                                // :24
public static JsonPropertyParser getInstance();                                                 // :325
public List<Function<BlockBehaviour.Properties, BlockBehaviour.Properties>>
        parseBlockProperties(JsonObject json);                                                  // :30
public List<StrictPropertyIssue> validateStrict(JsonObject json);                               // :45
public void registerCompiler(PropertyCompiler compiler);                                        // :298
public void registerCompiler(String key,
        BiFunction<String, JsonElement, Function<BlockBehaviour.Properties, BlockBehaviour.Properties>> supplier); // :302
public void registerStrictCompiler(String key, Predicate<JsonElement> validator);               // :306
public void removeCompiler(PropertyCompiler compiler);                                          // :317
public int compilerSize();                                                                      // :321
```

- **非严格解析（`parseBlockProperties`）**：`parseBlockProperties(null)` 返回空列表；按编译器列表顺序匹配，未知键或非法值仅打 WARN 并跳过（`:152-159, 181, 191`）。`buildCompilers`（`:248`）与 `buildSoundTypeMap`（`:197`）注册全部内置键。
- **严格校验（`validateStrict`，`:45-65`）**：仅供 v2 `RegistryGroupHandler.validate` 与 `BlockTypeHandler.validate` 调用。依次先查 `strictExtensions`（经 `registerStrictCompiler(key, validator)` 登记，键名自动转小写），再通过 `StrictPropertyValues` 对所有内置键做精确 JSON 类型与范围校验（如拒绝数字写成字符串、`light_emission` 超出 `[0, 15]`、无效 `strength` 数组长度或非法 `sound_type`/`map_color` 等），发现问题返回 `List<StrictPropertyIssue(relativeJsonPointer, message)>`（`StrictPropertyIssue.java:9-11`），不会静默降级 WARN。

### 11.2 JsonItemParser

`property/JsonItemParser.java:15-166`：

```java
public static final JsonItemParser INSTANCE;                                                    // :17
public List<Function<Item.Properties, Item.Properties>> parseItemProperties(JsonObject json);   // :42
public List<StrictPropertyIssue> validateStrict(JsonObject json);                               // :57
public void registerParser(String key, ItemPropertyParser parser);                              // :151
public void registerStrictParser(String key, Predicate<JsonElement> validator);                 // :155
public interface ItemPropertyParser { Function<Item.Properties, Item.Properties> parse(String key, JsonElement value); } // :163
```

- **非严格解析（`parseItemProperties`）**：`tab` 为保留键直接跳过（`:136`），由 handler 翻译为创造标签页；未知键打 WARN 并跳过（`:147`）；内置键为 `stacks_to`、`rarity`、`fire_resistant`、`durability`、`no_repair`（`:27-39`）。
- **严格校验（`validateStrict`，`:57-123`）**：校验 `tab` 必须是合法的带命名空间 `ResourceLocation`（`:64-74`），再优先查 `strictExtensions`（`registerStrictParser`），随后精确检验内置键（如 `stacks_to` 为 `1..99` 整数、`durability >= 1`、`rarity` 为已知枚举等），未识别的键一律产出 `StrictPropertyIssue`。

### 11.3 属性继承与覆盖

属性生效顺序由 Reg 树的 property 链决定，父先子后，后应用者覆盖先应用者：根组属性、父分组、子分组、方块级，方块级最后应用因而优先级最高。物品属性同理，分组 `item_properties` 先于方块级 `item_properties`。这是靠应用顺序实现的覆盖，不做字段合并；同名键整键覆盖。

> 数据驱动专题其它页：[注册期 API](api-registration.md) · [扩展 API](api-extension.md) · [诊断 API](api-diagnostics.md) · [重载 API](api-reload.md) · [Schema 规范](schema.md) · [方块状态属性](block-state-properties.md) · [专题索引](README.md)
