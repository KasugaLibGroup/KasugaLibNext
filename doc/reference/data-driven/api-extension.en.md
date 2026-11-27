# Data-driven extension API (TypeHandler, BuildContext, and property parsers)

## 8. The TypeHandler system

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

| Method | Responsibility and constraints | Failure behavior |
|------|-----------|---------|
| `getTypeName()` | Top-level JSON field name; unique in `TypeHandlerRegistry` (v1), and duplicates are rejected by v2 `DataDrivenBuildPipeline` | In v1, a later registration with the same name silently overrides an earlier one; in v2 `STRICT` mode, emits `HANDLER_NOT_STRICT_READY` |
| `getPhase()` | Apply phase order: `PHASE_GROUPS(0)` → `PHASE_CONTENT(1)` → `PHASE_EMBEDDED(2)` | None |
| `parse(json)` | Turn a JSON object into a definition object | Recommended to be a pure function; v1 skips the entry into the error bucket; v2 emits `PARSE_FAILED` |
| `apply(definition, context)` | Register the definition into the registration tree or build context | In v1, exceptions are caught by `applyUnchecked` and bucketed; in v2, exceptions abort the build with `Status.FAILED` |
| `capabilities()` | v2 capability flags for strict preflight, registry claims, and multi-source retention (see 8.8) | Defaults to empty `Set.of()`; triggers `HANDLER_NOT_STRICT_READY` if used under `BuildOptions.STRICT` |
| `strictValidationImplemented()` | Explicit confirmation in v2 that strict `validate` logic is implemented (declaring `STRICT_PREFLIGHT` alone is insufficient) | Defaults to `false`; emits `HANDLER_NOT_STRICT_READY` under `STRICT` unless overridden to return `true` |
| `validate(definition, context)` | v2 `STRICT` preflight run only during `prepare`; must have no registration side effects | Reports `BuildDiagnostic` entries via `ValidationContext.error/warning` |
| `claims(definition, context)` | v2 declaration of all Minecraft registry `ResourceLocation` keys this definition will occupy (see 8.8) | Produces `CLAIM_CONFLICT` or `CLAIM_FAILED` under `BuildOptions.STRICT` |
| `parentDefinition(definition)` | v2 parent `DefinitionKey` used for `GROUP_CYCLE` detection and topological ordering in `stableApplyOrder` | Defaults to `null` (no explicit parent edge) |
| `getParentTypeName()` | Non-null indicates an embedded type; value is the parent top-level field name | `null` means a top-level type |
| `getEmbeddedKeyName()` | Key name of the embedded type inside a parent entry, for diagnostic hints | `null` omits that key hint from diagnostics |
| `extractEmbedded(parentJson)` | Extract embedded JSON objects from a parent entry | Returning `null` yields no embedded items; never participates in top-level dispatch |
| `resolveIdentity(modId, definition)` | Identity string used for deduplication / selection | Default `null` opts out of deduplication |
| `resolveIdentity(modId, definition, strict)` | Mode-aware identity resolution (`RegTypeHandler` uses `EffectiveId.of` when `strict=true` vs `ofLegacy`) | Defaults to calling the 2-arg overload |

### 8.2 TypeHandlerRegistry

```java
public static <T> void register(TypeHandler<T> handler);            // TypeHandlerRegistry.java:11
public static TypeHandler<?> get(String typeName);                  // :15
public static Collection<TypeHandler<?>> all();                     // :19
public static TypeHandler<?> findByParent(String parentType);       // :23
```

- Global static `LinkedHashMap` shared by all mods in v1 (`JsonTreeBuilder`), with no namespace isolation. `all()` returns an immutable registration-order view, and `findByParent` returns only the first match.
- v2 `DataDrivenBuildPipeline` **does not read** `TypeHandlerRegistry` implicitly; the host explicitly passes a `Collection<? extends TypeHandler<?>>` (for example after calling `JsonTreeBuilder.ensureHandlersRegistered()` and reading `TypeHandlerRegistry.all()`).

### 8.3 Top-level vs embedded

A top-level type occupies one top-level JSON field (such as `blocks`, `items`, `registry_groups`). An embedded type occupies no top-level field and enters only via `extractEmbedded`. `BlockEntityTypeHandler` (`getParentTypeName() = "blocks"`, `getEmbeddedKeyName() = "block_entity"`, `BlockEntityTypeHandler.java:48-51`) copies the parent block's `id` into `_parent_block` (`:54-67`) and retrieves the parent `BlockReg` via `context.getBlockReg(...)` during `apply` (`:137-170`). Writing `block_entities` as a top-level field causes both v1 (`dispatchContent`) and v2 (`DataDrivenBuildPipeline`) to report it as an unknown top-level field and suggest moving it under `blocks[].block_entity`, while remaining valid fields continue dispatching.

### 8.4 RegTypeHandler

`handler/RegTypeHandler.java` is the abstract base class shared by `blocks` and `items`, encapsulating identity normalization, strict preflight, registry claims, and apply logic:

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

- **Capabilities**: returns `{HandlerCapability.STRICT_PREFLIGHT, HandlerCapability.REGISTRY_CLAIMS}` (`:89-93`) and `strictValidationImplemented() == true` (`:97-99`).
- **Identity split**: `resolveIdentity(modId, def)` uses `EffectiveId.ofLegacy` (`:77-79`), while `resolveIdentity(modId, def, strict)` uses `EffectiveId.of` when `strict == true` to preserve explicit `minecraft:` namespaces (`:82-86`).
- **Strict `validate` (`:102-141`)**: verifies non-blank `rawId`, presence of an explicit namespace (`rawId.indexOf(':') > 0`, rejecting bare IDs in `STRICT`), valid `ResourceLocation`, `context.factoryAvailable(factoryType(), resolveType(def))`, and, when `registry_group` is set, calls `context.requireDefinition(new DefinitionKey("registry_groups", groupId), "/registry_group")` (producing `MISSING_REFERENCE`, `REFERENCE_NOT_DECLARED`, or `REFERENCE_AMBIGUOUS`).
- **Strict `claims` (`:144-151`)**: emits a `RegistryClaim` for `claimRegistry(definition)` under the resolved `ResourceLocation` (subclasses may add auxiliary claims such as `minecraft:item`).
- **Fixed `apply` flow (`:155-202`)**:
  1. Splits `rawId` into namespace and path; if the factory is missing, throws `IllegalStateException` when `context.isStrict()` or logs WARN and skips otherwise (`:161-166`).
  2. Attaches a `ResourceLocation` namespace rewrite whenever `rawId.indexOf(':') >= 0 && (context.isStrict() || !namespace.equals("minecraft"))` (`:168-174`), enabling explicit `minecraft:` targeting under v2 `STRICT` while preserving v1/legacy behavior.
  3. Attaches the registry group (falling back to root if absent), resolves the creative tab (definition overrides group), invokes `configureTypeSpecific`, and stores the `Reg` into `context.putReg(getTypeName(), ...)` (`:175-191`).
  4. Re-throws exceptions when `context.isStrict()` (failing the pipeline with `Status.FAILED`), or logs WARN and returns in non-strict mode (`:192-201`).
- **Unused hook**: `resolveItemProperties` (`:41`) remains uncalled; `BlockTypeHandler` and `ItemTypeHandler` parse item properties inside their own `configureTypeSpecific`.

### 8.5 Built-in handlers

| Handler | `getTypeName` | `getPhase` | Capabilities & strict support | `parse` output | Source |
|---------|---------------|-----------|-------------------------------|--------------|--------|
| `RegistryGroupHandler` | `registry_groups` | `PHASE_GROUPS` | `STRICT_PREFLIGHT`, `NON_REGISTRATION`, `strictValidationImplemented=true` | `RegistryGroupDef(id, parent, properties, itemProperties)` | `RegistryGroupHandler.java:26-108` |
| `BlockTypeHandler` | `blocks` | `PHASE_CONTENT` | `STRICT_PREFLIGHT`, `REGISTRY_CLAIMS`, `strictValidationImplemented=true` | `BlockDef(id, type, registryGroup, properties, itemProperties, params, stateProperties)` | `BlockTypeHandler.java:25-158` |
| `ItemTypeHandler` | `items` | `PHASE_CONTENT` | `STRICT_PREFLIGHT`, `REGISTRY_CLAIMS`, `strictValidationImplemented=true` | `ItemDef(id, type, registryGroup, properties, params)` | `ItemTypeHandler.java:19-91` |
| `BlockEntityTypeHandler` | `block_entities` | `PHASE_EMBEDDED` | `STRICT_PREFLIGHT`, `REGISTRY_CLAIMS`, `strictValidationImplemented=true` | `BlockEntityDef(beType, parentBlockId, params)` | `BlockEntityTypeHandler.java:29-175` |

Per-handler specifics:

- **`RegistryGroupHandler`**: `resolveIdentity` returns the raw `id` unchanged (`:66-68`); `parentDefinition` returns `registry_groups:<parent>` for `GROUP_CYCLE` checks and topological apply ordering (`:101-106`); `validate` runs `JsonPropertyParser.validateStrict`, `JsonItemParser.validateStrict`, and `requireDefinition` on `parent` (`:71-98`).
- **`BlockTypeHandler`**: `validate` (`:70-105`) additionally validates `properties` and `item_properties` via `validateStrict` and checks `ValidationContext.factorySupports(..., StatefulBlockFactory.class)` when `state_properties` is declared; `claims` (`:108-123`) emits both `minecraft:block` and `minecraft:item` when `FactoryRegistry.createsBlockItem(def.type())` is `true`; `apply` throws if `stateProperties != null` and the resolved factory is not a `StatefulBlockFactory` (`:39-44`).
- **`ItemTypeHandler`**: `validate` (`:59-71`) runs `JsonItemParser.validateStrict`.
- **`BlockEntityTypeHandler`**: `validate` (`:99-121`) validates `parentBlockId`, `factoryAvailable(BLOCK_ENTITY, def.beType())`, and `requireDefinition(blocks:<parent>)`; `claims` (`:124-134`) claims `minecraft:block_entity_type` at `<ns>:<path>_be`; `apply` rethrows missing-parent or factory errors when `context.isStrict()` (`:141-166`).

### 8.6 Adding a new type (v1 and v2)

1. Implement `TypeHandler<T>` (or extend `RegTypeHandler<T>` when registering into Minecraft registries).
2. To participate in v2 `BuildOptions.STRICT`:
   - Declare `HandlerCapability.STRICT_PREFLIGHT` plus either `REGISTRY_CLAIMS` or `NON_REGISTRATION` (and optionally `OVERLAY`);
   - Override `strictValidationImplemented()` to return `true`;
   - Implement side-effect-free `validate(def, ValidationContext)` and, for registration handlers, `claims(def, ValidationContext)`;
   - Implement `resolveIdentity(modId, def, strict)` (and `parentDefinition` if hierarchical).
3. Register any backing factories with `FactoryRegistry` (see section 10).
4. Wire the handler:
   - **v1**: call `TypeHandlerRegistry.register(handler)` so `JsonTreeBuilder` discovers it.
   - **v2**: pass the handler collection explicitly to `DataDrivenBuildPipeline(...)` (optionally after registering into `TypeHandlerRegistry` and passing `TypeHandlerRegistry.all()`).

Reference example: `TestGenericFactory.java` shows a minimal custom handler paired with `FactoryRegistry.registerGeneric`.

### 8.7 ContentStructure (shared structure layer)

`structure/ContentStructure.java:30`. Pure structural validator shared by v1 `JsonTreeBuilder` and the reload domain (`ReloadOrchestrator` and decoders), depending only on Gson and `java.util`.

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

- `body`: requires a JSON object (`null` is treated as non-object); returns `null` when valid.
- `unknownKeys`: produces one `UNKNOWN_KEY` per key not in `knownKeys`; returns empty list if `body` is not an object.
- `field`: splits a single array field; missing key / non-array value emits one issue and zero elements, while non-object array elements emit issues with surviving siblings kept.
- `describe`: shared JSON type formatter (e.g. `"an array"`, `"a primitive (5)"`).

`ContentStructure` makes structural decisions without user-facing templates so registration and reload can preserve separate message templates and opposite D9 hints. v2 `DataDrivenBuildPipeline` uses `StrictResourceJson` paired with structured `BuildDiagnostic` emission.

### 8.8 HandlerCapability, DefinitionKey, and RegistryClaim

Located in `lib.kasuga.registration.data_driven.pipeline`, these types define v2 capability checks, selection, and cross-source registry claims:

#### HandlerCapability (`pipeline/HandlerCapability.java:6-26`)

```java
public enum HandlerCapability {
    STRICT_PREFLIGHT,   // Implements side-effect-free validate(...) for BuildOptions.STRICT
    NON_REGISTRATION,   // Apply mutates build metadata only (e.g. registry_groups); emits no MC registry claims
    REGISTRY_CLAIMS,    // Emits explicit RegistryClaim keys from claims(...)
    OVERLAY             // Retains every source candidate sharing a DefinitionKey instead of collapsing by last-wins
}
```

`DataDrivenBuildPipeline` enforces two invariants on capabilities:

1. **Strict readiness (`checkStrictCapabilities`, `DataDrivenBuildPipeline.java:1101-1147`)**: in `BuildOptions.STRICT`, every used handler (and duplicate `typeName` entry in `handlers`) must declare `STRICT_PREFLIGHT`, return `strictValidationImplemented() == true`, and satisfy `NON_REGISTRATION ^ REGISTRY_CLAIMS` (exactly one of the two); failures emit `Stage.PARSE` / `Code.HANDLER_NOT_STRICT_READY`.
2. **Candidate retention (`selectDefinitions`, `DataDrivenBuildPipeline.java:1149-1183`)**: when multiple candidates share a `DefinitionKey`, all candidates are retained whenever `capabilities.contains(HandlerCapability.OVERLAY) || (options.isStrict() && capabilities.contains(HandlerCapability.REGISTRY_CLAIMS) && !capabilities.contains(HandlerCapability.NON_REGISTRATION))`; otherwise only the last-wins maximum by `(sourceOrder, declarationOrder)` survives. Consequently, strict registration handlers retain duplicate/conflicting entries into `validate` and `claims` so conflicts surface instead of disappearing silently.

#### DefinitionKey (`pipeline/DefinitionKey.java:11-30`)

```java
public record DefinitionKey(String typeDomain, String id) implements Comparable<DefinitionKey>
```

Pairs the handler's `getTypeName()` (`typeDomain`) with the resolved `id`.

#### RegistryClaim and RegistryKey (`pipeline/RegistryClaim.java:20-56`)

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

Every `RegistryClaim` links a `(registry, id)` key back to its originating source, logical path, and JSON Pointer; collisions against another candidate or against `reservedRegistryClaims` produce `Stage.CLAIMS` / `Code.CLAIM_CONFLICT`.

#### RegistryClaimSnapshot (`pipeline/RegistryClaimSnapshot.java:15-39`)

```java
public static Set<RegistryClaim.RegistryKey> collect(Reg<?, ?> root)   // :19
```

Runs BFS across an existing host `Reg` tree (skipping subtrees with `!group.isDispatchEnabled()`), collecting `MinecraftDeferRegistryReg` keys as `reservedRegistryClaims` so code-registered entries cannot be overwritten silently by JSON definitions.

---

## 9. BuildContext / RegBuildContext and ValidationContext

### 9.1 BuildContext and RegBuildContext

```java
// BuildContext (context/BuildContext.java:12-98)
String getModId();                                                                // :34
String getHostModId();                                                            // :36 (defaults to getModId())
JsonRegistryGroup getRootGroup();                                                 // :38
boolean isStrict();                                                               // :40 (defaults to false)
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

// RegBuildContext (context/RegBuildContext.java:10-57)
public RegBuildContext(String modId, JsonRegistryGroup rootGroup);                // :17 (strict=false)
public RegBuildContext(String modId, JsonRegistryGroup rootGroup, boolean strict);// :21
void putRegistryGroup(String id, JsonRegistryGroup group);                        // :26
JsonRegistryGroup getRegistryGroup(String id);                                    // :30
void setRegistryGroupCreativeTab(String groupId, ResourceLocation tab);           // :34
ResourceLocation getRegistryGroupCreativeTab(String groupId);                     // :38
void putReg(String typeName, String id, Reg<?, ?> reg);                           // :44
Reg<?, ?> getReg(String typeName, String id);                                     // :48
Reg<?, Block> getBlockReg(String id);                                             // :54
```

- `getRegistryGroup` / `getReg` return `null` when absent; non-strict callers fall back to the root group or skip.
- `putMeta` / `getMeta` provide a generic cross-type reference map that does not require `Reg` values.
- In v2 `DataDrivenBuildPipeline.build`, each `apply` executes inside `withCurrentDefinition(def, ...)` (`:78-87`) so handlers can inspect `getSourceId()`, `getOwnerId()`, `getCurrentJsonPointer()`, and `isStrict()`.

### 9.2 ValidationContext (Preflight Context)

`validation/ValidationContext.java:18-112` (implemented by `pipeline/PipelineValidationContext.java:20-273`), passed to `TypeHandler.validate` (`VALIDATION` stage) and `TypeHandler.claims` (`CLAIMS` stage) during `DataDrivenBuildPipeline.prepare`:

```java
String hostModId();                                                                // :19
SourceDescriptor source();                                                         // :21
int sourceOrder();                                                                 // :23
LocatedDefinition<?> currentDefinition();                                          // :25
Set<String> directDependencies();                                                  // :27
Optional<LocatedDefinition<?>> find(DefinitionKey key);                            // :33
List<LocatedDefinition<?>> findAllVisible(DefinitionKey key);                      // :42
List<LocatedDefinition<?>> findAll(DefinitionKey key);                             // :51
default List<LocatedDefinition<?>> findAll(String typeDomain)                      // :60
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

Key behavioral contracts:

- **Visibility and ambiguity**:
  - `findAllVisible(key)` returns definitions matching `key` whose `ownerId` is either the current definition's `ownerId` or in `directDependencies()` (non-transitive), in session declaration order (`PipelineValidationContext.java:88-95`).
  - `find(key)` returns `Optional.of(visible.getFirst())` **only when `findAllVisible(key).size() == 1`**; both 0 matches and `> 1` matches return `Optional.empty()` (`:82-85`).
  - `requireDefinition(key, relPtr)` (`:115-155`) returns `true` when there is a single visible match; otherwise it returns `false` and records `MISSING_REFERENCE` when absent globally (`findAll(key)` empty), `REFERENCE_NOT_DECLARED` when present only in an undeclared owner, or `REFERENCE_AMBIGUOUS` when `visible.size() > 1`.
  - `findAll(String typeDomain)` (`ValidationContext.java:60-63`, `PipelineValidationContext.java:103-107`) returns every parsed definition across all IDs and sources matching `typeDomain` in session declaration order; the default interface method throws `UnsupportedOperationException`.
- **Resource lookup**: `resourceExists(domain, id)` (`:158-184`) checks the current owner and `directDependencies()` under `assets/<ns>/...` or `data/<ns>/...`, plus `sharedAssetOwners` when `domain == ResourceDomain.ASSETS`.
- **Constructing `RegistryClaim` values**: `PipelineValidationContext.claim(registry, id)` and `claimAt(registry, id, relPtr)` (`:218-240`) construct and **return** a `RegistryClaim` (with JSON pointer resolved via `JsonPointers.append`) for `TypeHandler.claims(...)` to return to the pipeline; calling them inside `validate(...)` does not record claims as a side effect.

---

## 10. FactoryRegistry and Built-in Model Factories

### 10.1 FactoryRegistry

`modules/core/src/main/java/lib/kasuga/registration/factory/FactoryRegistry.java:15-121`. Data-driven handlers look up factories here by the JSON `type` field.

```java
public interface BlockFactory       { Reg<?, Block> create(String id, @Nullable JsonObject params); } // :18
public interface ItemFactory        { Reg<?, Item> create(String id, @Nullable JsonObject params); }  // :23
public interface BlockEntityFactory { Reg<?, ?> create(String id, Supplier<Block[]> validBlocks, @Nullable JsonObject params); } // :28
public interface GenericFactory     { Reg<?, ?> create(String id, JsonObject params); }              // :33

public static void register(String type, BlockFactory factory);                                      // :45 (createsBlockItem=false)
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

To accept JSON `state_properties`, a block factory must implement `lib.kasuga.registration.data_driven.state.StatefulBlockFactory` (extending `BlockFactory` with `create(String id, @Nullable JsonObject params, StatePropertySet properties)`, `StatefulBlockFactory.java:10-19`).

- Backed by `ConcurrentHashMap`; duplicate registrations silently overwrite.
- `createsBlockItem`: the 2-arg `register(type, factory)` sets `false`; only `true` causes `BlockTypeHandler.claims` to emit a companion `minecraft:item` claim.
- `id` contains only the path; the namespace has already been stripped and attached as a Reg namespace rewrite.
- `BlockEntityFactory.validBlocks` is lazy (`Supplier<Block[]>`): store it rather than calling `.get()` during construction.

### 10.2 Built-in block and block-entity factories

| Class and module | Registered `type` keys | Contract |
|---|---|---|
| `DataDrivenBlockFactory` (`modules/data-driven`) | Block `"data_driven_block"` | `StatefulBlockFactory` with `createsBlockItem=true`, creating `DataDrivenBlock`; registered by `JsonTreeBuilder.ensureHandlersRegistered()` or `DataDrivenBlockFactory.registerIfAbsent()` |
| `FsmBlockEntityFactories` (`modules/modelling`) | Block `"fsm_block"` (`createsBlockItem=true`); BE `"fsm_be"` | `fsm_block` → `FsmBlock`; `fsm_be` → `AnimationBlockEntity` reading `state_machine`, `model`, `model_name` from `params`; registered on class load or via `registerBuiltin()` |
| `ProxyModelBlockFactories` (`modules/modelling`) | Block `"proxy_model"` (`BLOCK_TYPE`, `StatefulBlockFactory`, `createsBlockItem=true`); BE `"proxy_model_be"` (`BLOCK_ENTITY_TYPE`) | Micronaut `@Context` bean calling idempotent `registerBuiltin()` from `@PostConstruct init()` |

Key contracts of `ProxyModelBlockFactories` (`modules/modelling/.../proxies/ProxyModelBlockFactories.java:39-403`):

- **`proxy_model` block host**: `ProxyModelBlock` implements `EntityBlock`, installs `state_properties` via `StatePropertyContext`, forwards `StateProperty.notifyChanges(level, pos, oldState, newState)` in `onBlockStateChange`, wires horizontal `facing` and `hinge` placement/rotation/mirroring, and uses `RenderShape.ENTITYBLOCK_ANIMATED` so missing baked JSON block models never leak purple/black fallback cubes. Passing non-empty `params` on the block throws `IllegalArgumentException` in `validateBlockParams`.
- **Public `proxy_model_be` parser**:
  ```java
  public record ModelSettings(
          ProxyModelSelection selection,
          float yawOffset,
          Map<String, Float> facingYaw,
          @Nullable ModelLightingMode lightingMode
  )
  public static ModelSettings parseModelSettings(@Nullable JsonObject params)  // :131
  ```
  Consuming mods can call `parseModelSettings` to reuse the exact `model`, `model_name`, `default_model`, `yaw_offset`, `facing_yaw`, `lighting_mode`, `model_groups`, and `model_by_state` parser (passing `params.animation` throws `IllegalArgumentException`). See [schema.md](schema.md#29-built-in-proxy_model-proxy_model_be-schema) for the JSON schema.
- **Java extension seam for animation and interaction**: content-specific controllers belong in Java rather than JSON — subclass `ProxyModelBlock` and override:
  ```java
  public ProxyComposition<ProxyModelBlockEntity, ProxyModelInput, Void, Void, Void>
  createProxyComposition(ProxyModelBlockEntity element)
  ```
  and register a custom `StatefulBlockFactory` using `ProxyModelBlockEntity` / `ProxyModelProxy.builder(element)`.

---

## 11. Property parsers

### 11.1 JsonPropertyParser

`property/JsonPropertyParser.java:22-328`:

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

- **Legacy parsing (`parseBlockProperties`)**: `parseBlockProperties(null)` returns an empty list; compilers run in order until the first `valid(key, value)` hit, while unknown keys or bad values log WARN and are skipped (`:152-159, 181, 191`). Built-in keys and sound types live in `buildCompilers` (`:248`) and `buildSoundTypeMap` (`:197`).
- **Strict validation (`validateStrict`, `:45-65`)**: used by v2 `RegistryGroupHandler.validate` and `BlockTypeHandler.validate`. Checks `strictExtensions` first (`registerStrictCompiler`, case-insensitive keys), then validates built-in keys via `StrictPropertyValues` (exact boolean/number/string/array checks, e.g. `light_emission` in `0..15`, valid `sound_type`/`map_color`, 2-element `strength`), returning `List<StrictPropertyIssue(relativeJsonPointer, message)>` (`StrictPropertyIssue.java:9-11`) instead of downgrading errors to WARN.

### 11.2 JsonItemParser

`property/JsonItemParser.java:15-166`:

```java
public static final JsonItemParser INSTANCE;                                                    // :17
public List<Function<Item.Properties, Item.Properties>> parseItemProperties(JsonObject json);   // :42
public List<StrictPropertyIssue> validateStrict(JsonObject json);                               // :57
public void registerParser(String key, ItemPropertyParser parser);                              // :151
public void registerStrictParser(String key, Predicate<JsonElement> validator);                 // :155
public interface ItemPropertyParser { Function<Item.Properties, Item.Properties> parse(String key, JsonElement value); } // :163
```

- **Legacy parsing (`parseItemProperties`)**: skips reserved `tab` (`:136`), logs WARN for unknown keys (`:147`), and provides built-ins `stacks_to`, `rarity`, `fire_resistant`, `durability`, and `no_repair` (`:27-39`).
- **Strict validation (`validateStrict`, `:57-123`)**: requires `tab` to be a valid namespace-qualified `ResourceLocation` (`:64-74`), checks `strictExtensions` (`registerStrictParser`), enforces exact bounds (`stacks_to` in `1..99`, `durability >= 1`, known `Rarity`), and reports unknown keys as `StrictPropertyIssue`.

### 11.3 Property inheritance and override

Properties apply in Reg-tree parent-to-child order so later stages override earlier ones: root-group properties, parent group, child group, then block level (highest priority). Item properties follow the same order (`item_properties` on groups before `item_properties` on blocks). Overrides replace whole keys rather than deep-merging objects.

> Other pages in the data-driven section: [Registration API](api-registration.md) · [Extension API](api-extension.md) · [Diagnostics API](api-diagnostics.md) · [Reload API](api-reload.md) · [Schema Reference](schema.md) · [Block State Properties](block-state-properties.md) · [Section index](README.md)
