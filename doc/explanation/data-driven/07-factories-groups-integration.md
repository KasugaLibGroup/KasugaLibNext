# 工厂、注册组与宿主接线

`TypeHandler` 解释 JSON 形状，`FactoryRegistry` 把 `type` 和 `params` 转成 `Reg`。注册组负责属性继承，`RegistryGroup` 负责运行时 dispatch；宿主把同一份固定 `ResourceSnapshot` 分别投影为注册 `ContentSource` 和 MC Approved pack 层。

## FactoryRegistry

`FactoryRegistry` 位于 core，data-driven 与宿主 mod 共用：

```java
FactoryRegistry.register("panel_block", factory, true);
FactoryRegistry.registerItem("manual_item", itemFactory);
FactoryRegistry.registerBlockEntity("panel_be", blockEntityFactory);
FactoryRegistry.registerGeneric("custom_type", genericFactory);
```

源码：[FactoryRegistry.java](../../../modules/core/src/main/java/lib/kasuga/registration/factory/FactoryRegistry.java)。

| 类别 | 注册 | 查询 |
| --- | --- | --- |
| Block | `register(type, factory[, createsBlockItem])` | `get`、`contains`、`createsBlockItem` |
| Item | `registerItem` | `getItemFactory`、`containsItem` |
| Block entity | `registerBlockEntity` | `getBlockEntityFactory`、`containsBlockEntity`、`getBlockEntityTypes` |
| Generic | `registerGeneric` | `getGeneric`、`containsGeneric` |

每个表是进程级 `ConcurrentHashMap`。重复 type 会覆盖旧 factory；没有 namespace 隔离。建议 type 名带 mod 前缀，并在 `@Context` bean 的初始化阶段、第一次 prepare 之前完成注册。`register` 与 `registerBlockEntity` 等调用彼此独立，不是事务；初始化中途失败会留下部分已注册类型。

factory 参数对象是开放 JSON：

```java
Reg<?, Block> create(String id, @Nullable JsonObject params);
Reg<?, Item> create(String id, @Nullable JsonObject params);
Reg<?, ?> create(
        String id,
        Supplier<Block[]> validBlocks,
        @Nullable JsonObject params
);
```

具体键由 factory 自己解释。STRICT 能检查 factory 是否存在和是否实现某个 Java capability，无法理解该 factory 私有的 `params` schema。

`FactoryRegistry` 没有 production unregister/reload API。factory 的动态替换会立即影响后续 `validate` 和 `apply`，因此应在启动构建前完成；不要在 `/reload` 中按 resource pack 状态替换 Java factory。

## 方块工厂与默认方块物品

数据驱动方块通常同时创建 block 和 block item：

```java
@SuppressWarnings("unchecked")
private static <T extends Block> Reg<?, Block> blockWithItem(
        String id,
        Function<BlockBehaviour.Properties, T> blockSupplier
) {
    return (Reg<?, Block>) (Reg<?, ?>)
            BlockReg.of(id, blockSupplier).withDefaultBlockItem(id);
}

FactoryRegistry.register(
        "panel_block",
        (id, params) -> blockWithItem(id, PanelBlock::new),
        true
);
```

第三个 boolean 是 factory capability 登记，不是工厂返回值的自动反射：

- `true`：内置 `BlockTypeHandler.claims` 额外声明 `minecraft:item`，id 与 block 相同；
- `false`：只声明 block；
- 重新注册同一 type 且 boolean 为 false 会移除之前的 item capability。

factory 实际调用 `withDefaultBlockItem`，但注册时没有传 `true`，STRICT 会漏报这个 item claim。反过来传 `true` 却没有创建 item，会让 claim 与实际注册树不一致。

内建 `BlockTypeHandler` 的推导只覆盖“方块物品与方块同 id”的约定。用 `withBlockItem` 创建不同 id，或一个 factory 创建多个物品时，应使用专用 handler 报告真实 claim；仅设置第三个 boolean 不足以描述这些关系。

### 派生 claim

`BlockTypeHandler.claims` 的结果：

```text
minecraft:block / <effective block id>
plus when factory capability is true:
minecraft:item / <effective block id>
```

`ItemTypeHandler` 只声明：

```text
minecraft:item / <effective item id>
```

`BlockEntityTypeHandler` 派生：

```text
minecraft:block_entity_type / <block namespace>:<block path>_be
```

factory 若额外创建 creative tab、menu、entity、fluid 等其他 deferred entry，内建 handler 不知道这些子节点。需要更完整 claim 时，应实现专用 TypeHandler，或由宿主提供额外的 reserved/derived claim 机制。

`RegTypeHandler.claims` 从当前 DefinitionKey 读取最终 ID，因此 block/item claim 与 STRICT/LEGACY apply 使用同一身份规则。显式 `minecraft:` 在 STRICT 中仍是 `minecraft:`，在 LEGACY 中按历史规则映射到宿主 namespace。

## 带状态属性的工厂

声明了 `state_properties` 的方块必须使用 `StatefulBlockFactory`：

```java
FactoryRegistry.register(
        "stateful_panel",
        (StatefulBlockFactory) (id, params, states) ->
                blockWithItem(id, properties ->
                        StatePropertyContext.construct(
                                states,
                                () -> new StatefulPanelBlock(properties)
                        )),
        true
);
```

方块类必须：

1. 在 `createBlockStateDefinition` 中加入 JSON 属性；
2. 用 `StatePropertyContext.applyDefaults` 设置默认状态；
3. 需要同步/监听时接入 `StateProperty` 钩子。

完整 API 见 [方块状态属性参考](../../reference/data-driven/block-state-properties.md)。

## 内建工厂

当前源码中存在：

- `data_driven_block`：普通状态方块工厂。`JsonTreeBuilder.ensureHandlersRegistered()` 会通过 `DataDrivenBlockFactory.registerIfAbsent()` 确保它存在；新 pipeline 若不调用该初始化入口，就必须自行注册。
- `fsm_block`：modelling 中的 FSM 方块工厂，声明默认方块物品 capability。
- `fsm_be`：读取 `state_machine`、`model`、`model_name` 的 FSM 方块实体工厂。
- contentTesting 中的 `simple_block`、`simple_item`、`test_be` 等 fixture，不随发布内容提供给下游。

`fsm_block` 和 `fsm_be` 由 [`FsmBlockEntityFactories.registerBuiltin()`](../../../modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/dynamic/fsm/FsmBlockEntityFactories.java) 注册；当前 production 源码没有自动调用该注册器，tests/game tests 会显式调用。宿主若要在正式注册流程中使用这两个类型，必须自己完成初始化。第三方 mod 也应注册自己的 factory，不要把 `contentTesting` 类型当成稳定 API。

## RegistryGroup 属性继承

`Reg.applyProperties` 按 parent-first 顺序执行：

```text
JSON root
  → parent registry_group
  → child registry_group
  → concrete block/item
```

因此子组覆盖父组，具体方块或物品覆盖组。`RegistryGroupHandler` 的 `parent` 关系决定该属性链；它和 `RegistryGroup.dispatchWhen` 的运行期谓词是两个独立功能。

创造栏 `tab` 的查找规则不同：

1. 方块/物品自身声明 `tab` 时使用自身值；
2. 否则查它直接使用的 `registry_group` 上保存的 tab；
3. 需要继承某个 tab 时，在实际引用的组上显式设置，不要假定 parent 组的 tab 自动展开。

## RegistryGroup dispatch

```java
Registry root = new Registry("example");

RegistryGroup coreRuntime = new RegistryGroup().setParent(root);
RegistryGroup coreData = new RegistryGroup()
        .dispatchWhen(state::isCorePackEnabled)
        .setParent(root);

// runtime code registrations
new RuntimeOnlyReg().setParent(coreRuntime);

// JSON and optional code registrations owned by the pack
new PackOnlyReg().setParent(coreData);
```

`dispatchWhen` 保存一个 `BooleanSupplier`，在每次 `RegistryGroup.dispatchRegister` 时读取：

```java
if (predicate.getAsBoolean()) {
    super.dispatchRegister(context);
}
```

谓词为 false 时：

- 组自己的 `register` 不执行；
- 所有子节点都不执行；
- 子树仍留在内存注册树中；
- predicate 随时可以变成 true，下一次 dispatch 会正常遍历。

再次调用 `dispatchWhen` 会替换旧 predicate，不会叠加。

dispatch 谓词不删除节点、不改变 parent/children。`RegistryClaimSnapshot.collect` 会跳过 predicate 为 false 的组及其子树，因此未启用的代码注册不会占 reserved claim；predicate 恢复为 true 后，下一次 snapshot 又会计入这些节点。

predicate 中抛出的运行时异常会中止该次 dispatch，应保持简单且无副作用。

## JsonRegistryGroup

`DataDrivenBuildPipeline` 创建：

```java
new JsonRegistryGroup(hostModId + ":json_root")
```

并在根上安装默认映射：

```java
root.withProperty(
        ResourceLocation.class,
        location -> ResourceLocation.fromNamespaceAndPath(
                hostModId,
                location.getPath()
        )
);
```

LEGACY 中，`RegTypeHandler` 只对显式非 `minecraft` namespace 追加自己的映射，因此 `minecraft:path` 继续落到 host namespace。STRICT 中，任意显式 namespace（包括 `minecraft`）都会追加自己的映射，最终 id 与 `EffectiveId.of` 一致。这个分支由 `RegBuildContext.isStrict()` 控制。

`JsonRegistryGroup` 可以像普通 `RegistryGroup` 一样设置 parent 或 `dispatchWhen`，但 `DataDrivenBuildPipeline.build` 返回对象后、附加到 mod root 前修改它，宿主应明确自己的生命周期与 predicated group 策略。

## 合并内建 handler 与自定义 handler

新管线使用显式 handler 列表。复用库内建类型：

```java
JsonTreeBuilder.ensureHandlersRegistered();

List<TypeHandler<?>> handlers = new ArrayList<>();
handlers.addAll(TypeHandlerRegistry.all());
handlers.add(new PanelModeHandler());

DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
        ExampleMod.MOD_ID,
        handlers,
        finalizers
);
```

`TypeHandlerRegistry` 是进程级表，若多个 mod 都直接注册同名自定义 type，会互相覆盖。生产扩展更稳妥的做法是把自定义 handler 实例直接放进当前宿主的 pipeline 列表，并在 type 名中加入来源前缀。

## 代码注册与数据注册共存

```java
Registry modRegistry = new Registry("example");
RegistryGroup runtime = new RegistryGroup().setParent(modRegistry);
RegistryGroup data = new RegistryGroup()
        .dispatchWhen(packState::dataEnabled)
        .setParent(modRegistry);

jsonRoot.setParent(data);
```

边界建议：

- Java 类、共通游戏逻辑和固定基础设施放 runtime group；
- pack 可以开关的默认内容以及由 pack snapshot 判定的代码注册放 data group；
- 属性组、JSON handler 和 factory 在构建前已经注册；
- reserved claims 只包含真正可能与 JSON 竞争的 code entries；
- `/reload` 资产刷新不要重建 Java registry group。

## 资源包与 ResourceManager

固定来源、注册视图、`Reg` 和 Minecraft resource pack 是四个层次：

| 层次 | 作用 |
| --- | --- |
| `ResourceSnapshot` | 固定启动来源的实际字节、URI、revision 和 root 身份 |
| `ContentSource` | 从一个具体 snapshot/root 读取 manifest 和 JSON |
| `Reg` tree | 在 registry event 创建 Java/Minecraft registry entry |
| resource pack | 在资源 reload 提供 assets、data、模型、纹理、语言和 reload JSON |

宿主通常需要同时完成：

1. 把每个 pack/root 固定为互不共享关闭寿命的 snapshot，再用 `ContentSourceFactory.snapshot(...)` 交给 `DataDrivenBuildPipeline`；
2. 在 pack discovery 阶段用同一 root 集合和 rank 建立 `MappedPackResources`/Approved pack 层；
3. 保证 `pack.mcmeta`、`assets/`、`data/` 布局有效；
4. 让 MC pack priority 与 `ResourceSourceDescriptor.rank` 表示同一覆盖关系；
5. 对纯 Java 注册包也提供资源包，否则方块/物品会缺模型、纹理和语言。

`ApprovedResourcePacks.register(id, sources, routing)` 会保存传入的 `ResourceSource` 列表，之后每次 `AddPackFindersEvent` discovery 再调用 `source.capture()`。生产路径应传 `FixedResourceSource`；传 live directory/archive source 会让 discovery 重新读取安装内容。每个 source/root 生成一个 `MappedPackResources` 层，文件通过 snapshot lease 打开；多个层不会被压成一个“最后赢家目录”。

`MappedPackResources` 只投影 `pack.mcmeta`、`pack.png`、`assets/` 和 `data/`，并在缺少 metadata 时按当前 `PackType` 生成运行时描述。`ApprovedResourcePacks` 按 rank 排序生成 pack，但最终单读取仍由真实 MC `ResourceManager` 决定。

`ResourceSystem` 把 client/server 的原生 `ResourceManager` 包装成 `ScopedResourceManager`。`ReloadOrchestrator` 作为 `ScopedResourceManagerConsumer` 注册 listener，收到 reload 后调用：

```java
reload(scopedResourceManager.getResourceManager());
```

因此 reload 读取的是 Minecraft 已经选好的包栈：

- 高层 resource/data pack 覆盖低层同 namespace/path 文件；
- pack 被禁用时，对应资源不进入 `ResourceManager`；
- `getResource` 返回当前包栈选中的那份文件；
- `/reload` 可以改状态机和 clip 桶，不能改 block/item registry。

注册视图与 MC 投影应引用同一个固定 snapshot/root。`MappedPackResources` 直接桥接捕获字节，因此 v2 不需要为了资源发现再物化一份权威缓存目录；若宿主另建可选持久缓存，它只能作为 capture 的加速副本，不能成为注册和资源栈各自读取的第二事实源。

`rank` 是平台层顺序，但 `ResourceSystem` 不会替宿主决定业务上哪些 pack 启用。宿主必须从同一个冻结选择结果生成注册 source 列表与 Approved pack 列表；若注册阶段拒绝某用户包，也不能提前把它发布到资源栈。

## 启动一致性

推荐在报告或 SavedData 中保存：

- 启用的 pack id 与版本；
- 实际 `(sourceId, rootId)` 列表及全局 rank；
- manifest 逻辑路径；
- prepared claims；
- build status；
- 关键 diagnostic code。

同一世界若在重启后得到不同 enabled pack set，旧 registry entry 可能不存在，旧 world block/item 数据便会丢失或回退。宿主应在 server start 前比较历史快照与新快照，并拒绝不兼容世界。KasugaLib 的数据驱动层提供构建与 claim 信息，历史兼容策略由宿主定义。
