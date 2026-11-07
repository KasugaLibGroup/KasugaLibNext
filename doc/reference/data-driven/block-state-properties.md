# 数据驱动方块状态属性

`blocks` 条目中的 `state_properties` 声明 Minecraft 真正的 `BlockState` 属性。它与控制硬度、摩擦力等的 `properties` 是两个独立字段。以下内容文件必须列入索引的 `on_register`：

```json
{
  "blocks": [
    {
      "id": "mymod:control_panel",
      "type": "data_driven_block",
      "properties": { "destroy_time": 2.0 },
      "state_properties": {
        "open": { "type": "vanilla", "property": "OPEN", "default": false },
        "active": { "type": "boolean", "default": false },
        "level": { "type": "integer", "min": 0, "max": 3, "default": 1 },
        "mode": { "type": "enum", "values": ["idle", "working", "paused"], "default": "idle" },
        "facing": { "type": "vanilla", "property": "HORIZONTAL_FACING", "default": "north" }
      }
    }
  ]
}
```

`data_driven_block` 是库提供的普通方块工厂，同时创建默认的方块物品。属性类型如下：

| `type` | 字段 | Minecraft 属性类型 | 省略 `default` 时 |
|---|---|---|---|
| `boolean` | `default`：JSON 布尔值 | 新的 `BooleanProperty` | `false` |
| `integer` | `min`、`max`：整数；`default`：范围内的整数 | 新的 `IntegerProperty` | `min` |
| `enum` | `values`：字符串数组；`default`：其中一个字符串 | `StringStateProperty`，值是字符串，不是 Java `enum` | `values` 第一项 |
| `vanilla` | `property`：`BlockStateProperties` 的大写字段名；`default`：属性值 | 原版的同一个 `Property` 实例 | 该属性可能值集合的第一项；建议显式填写 |

属性名及枚举值只能使用 `[a-z0-9_]+`；枚举值必须唯一。整数范围非负、`max > min` 且小于 `2147483647`。每个属性须有 2～64 个值，JSON 声明的属性组合数不得超过 4096。使用自定义方块工厂时，方块自身的属性也会增加总状态数，内容作者应控制整体规模。

`vanilla` 条目的键名必须等于该常量的实际属性名。例如 `HORIZONTAL_FACING` 的键应为 `facing`。要使用 `state.getValue(BlockStateProperties.OPEN)` 或 `setValue(BlockStateProperties.OPEN, ...)`，应像示例一样显式引用 `OPEN`；`boolean` 会新建属性，即使名称同为 `open`，也需要从对应方块的 `getStateDefinition().getProperty("open")` 取回实际实例。其他自定义属性同样按方块和名称查找，不应把不同方块上的实例混用。

JSON 格式无效时会进入注册期诊断并跳过该条方块；未声明 `state_properties` 的旧工厂保持原有行为。声明了状态属性的方块必须使用支持状态的工厂，否则会记录错误并跳过。`data_driven_block` 不添加门的开关、红石或其他交互：玩法代码应在服务端修改真实方块状态。状态模型、物品模型、掉落及本地化仍需单独提供。

这些属性在启动注册时建立，`/reload` 不能改变它们。客户端和服务端必须使用相同的方块及属性定义。属性名、取值和方块 ID 都会进入存档：已发布后变更它们可能使旧状态回退或丢失，框架不提供自动迁移，升级或降级前应备份世界。

## 让自定义方块工厂支持状态属性

Minecraft 会在 `Block` 的父构造函数中调用 `createBlockStateDefinition`，因此不能在构造完成后补属性。让工厂实现 `StatefulBlockFactory`，并将实际构造包在 `StatePropertyContext.construct` 内：

```java
FactoryRegistry.register("mymod:custom_block", (StatefulBlockFactory) (id, params, states) ->
    blockWithItem(id, properties -> StatePropertyContext.construct(states,
        () -> new MyBlock(properties))));
```

其中 `blockWithItem` 可沿用[工厂扩展指南](../../guides/data-driven/guide-extension.md)中的辅助函数。方块类同时在状态定义和默认状态两个位置接入：

```java
public class MyBlock extends Block {
    public MyBlock(Properties properties) {
        super(properties);
        registerDefaultState(StatePropertyContext.applyDefaults(defaultBlockState()));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        // 如果本类有原生属性，先 builder.add(原生属性)，再加入 JSON 属性。
        StatePropertyContext.addTo(builder);
    }
}
```

如果父类自行设置默认状态，应在 `super` 返回后保留这些值，再应用 JSON 默认值。JSON 属性名不得与方块类或其父类已有属性重名；Minecraft 会在构造状态表时拒绝冲突，这类工厂与方块类之间的冲突可能中止注册。仅实现工厂接口而不在方块中接入上述两处，不能使状态属性生效。完整示例见 `data-driven` 模块的 `StatefulTestBlock` 与 `DataDrivenTestFactories` 测试源集。

## Java 创建与读取属性

Java 代码也可以构造和 JSON 使用相同规则的属性集合，在方块注册、实际构造之前交给 `StatePropertyContext.construct`：

```java
StatePropertySet properties = StatePropertySet.builder()
    .booleanProperty("charging", false)
    .integerProperty("stage", 0, 3, 0)
    .stringProperty("mode", List.of("idle", "running"), "idle")
    .add(BlockStateProperties.OPEN, false)  // 也可以加入现有的 Property<T>
    .build();

BlockReg.of("machine", blockProperties ->
    StatePropertyContext.construct(properties, () -> new DataDrivenBlock(blockProperties)));
```

`build()` 返回独立的不可变快照。`add(Property<T>, T)` 保留传入的属性实例，也可用于 Java 枚举属性，并验证每个值能按名称读回；布尔、整数、字符串属性与 JSON 共用名称、值域和状态数量检查。该片段只建立 `BlockReg`；如需方块物品、命名空间或创造栏，还要按普通注册流程配置。

方块注册后，可按名称和 Java 值类型取得句柄。句柄绑定具体方块及其实际 `Property`，可用于 JSON 和 Java 创建的属性：

```java
StateProperty<Boolean> charging = StateProperty.of(machineBlock, "charging", Boolean.class);
boolean current = charging.get(serverLevel, pos);
boolean changed = charging.set(serverLevel, pos, true);  // 默认通知客户端及邻居

BlockState other = charging.with(state, false);           // 仅返回新的状态，不修改世界
Property<Boolean> minecraftProperty = charging.property();
```

`set` 必须在服务端线程调用，默认使用 Minecraft 更新标志 `3`；另有接受 `flags` 的重载。同值或世界拒绝更新时返回 `false`；成功返回值遵循 `Level#setBlock`，邻居后续反应仍可能再次改变状态。`get`、`with` 和 `set` 遇到错误的方块、属性名、值类型或非法值会抛异常，不会静默改错属性。

## onChange

```java
StateProperty.Subscription subscription = charging.onChange(change -> {
    LOGGER.info("{}: {} -> {}", change.pos(), change.oldValue(), change.newValue());
});

// 生命周期结束后，例如对应模块卸载时：
subscription.close();
```

监听按**方块和属性**绑定。默认只观察逻辑服务端；`onChange(StateProperty.Side.CLIENT, callback)` 和 `BOTH` 可显式选择其他侧。事件提供 `level()`（`LevelReader`）、不可变坐标 `pos()`、`oldState()`、`newState()`、`oldValue()` 与 `newValue()`。订阅由调用方持有，`close()` 可重复调用；监听器抛出的运行时异常会记录日志，其他监听器仍可执行。

只有同一方块的目标属性值实际变化才触发；初次放置、移除、相同值及仅修改其他属性不会触发。Java `set`、命令和普通 `Level#setBlock` 均走同一钩子。NeoForge 的方块快照捕获在提交前不通知，回滚也不产生属性变化事件；区块整体加载不是逐方块变化事件。

回调同步运行在 NeoForge 的 `onBlockStateChange` 中，此时**禁止修改世界**。`StateProperty.set` 会在回调中拒绝写入；不要绕过它直接调用 `Level#setBlock`，需要修改世界时应把工作交给后续安全的服务端 tick。纯 `with` 不修改世界，可以在回调中使用。

内置 `DataDrivenBlock` 已转发钩子。自定义方块除上面的状态定义接线外，还需转发一次；如果父类已经转发，不要重复调用：

```java
@Override
public void onBlockStateChange(LevelReader level, BlockPos pos,
                               BlockState oldState, BlockState newState) {
    super.onBlockStateChange(level, pos, oldState, newState);
    StateProperty.notifyChanges(level, pos, oldState, newState);
}
```

## 客户端同步验证

仓库内有一个仅在显式启用时运行的单人客户端 smoke。它会在玩家附近放置测试方块并修改状态，因此应使用隔离的客户端目录和测试世界：

```bash
./gradlew :modules:data-driven:runClient \
  -PkasugaStatePropertyClientDirectory=/absolute/path/to/isolated-client \
  -PkasugaStatePropertyQuickPlayWorld=test-world \
  -PkasugaStatePropertyClientSmoke=true
```

`test-world` 必须已存在于该目录的 `saves/` 下。成功时日志包含 `STATE_PROPERTY_CLIENT_SMOKE_PASS server=1 client=1`，客户端随后退出；失败时记录 `STATE_PROPERTY_CLIENT_SMOKE_FAIL`。这个测试覆盖单人客户端和内置服务端，不代表多人专用服务器验证。
