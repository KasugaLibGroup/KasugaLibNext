# 车厢垂直姿态原理

KasugaLibNext 在 `Carriage` 上维护一个服务端权威的垂直偏移，让车体相对转向架上下移动，同时保证转向架、轮速和轨道几何不受影响。这是一个独立于多转向架系统的能力，普通两转向架车厢同样适用。使用方式与开关见[使用指南](../../guides/create/carriage-posture.md)；多转向架部分见[多转向架车厢原理](multi-bogey-carriage.md)。

## 状态模型

- 偏移是 `Carriage` 上的普通 `float`，由服务端写入，是唯一权威值；
- 放在 `Carriage` 而不是实体上，是因为同一车厢的每个维度实体共享同一状态，并且它随列车存档、与实体生命周期解耦；
- 值由事件 `ModifyCarriagePostureEvent` 每 tick 给出；`enable_carriage_y_offset` 关闭时恒为 0；
- 客户端维护自己的平滑副本（`LerpedFloat`），从服务端同步值缓动追赶。

## 每 tick 更新

`CarriageMixin` 在 `Carriage.manageEntities` 的尾部执行（服务端、每车厢每 tick）：

1. 开关关闭：把权威值置 0，不发布事件；
2. 否则构造 `ModifyCarriagePostureEvent(level, carriage)` 并在 `NeoForge.EVENT_BUS` 上发布，监听者通过 `setYOffset(float)` 给出本 tick 的偏移；
3. 事件返回后夹紧到 `±max_carriage_y_offset`，写回权威值。

`manageEntities` 只在服务端列车 tick 中执行，因此事件天然是服务端语义，客户端不会收到也不应发布该事件。

## 持久化

`CarriageMixin` 在 `Carriage#write` 返回时写入一个浮点键：

```text
KasugaYOffset: <float>
```

- 只有 `enable_carriage_y_offset` 开启且当前值非 0 时才写入；
- `read` 在开关开启时恢复，并按 `max_carriage_y_offset` 夹紧；
- 只有服务端读写这组 NBT；客户端车厢来自 `Train.STREAM_CODEC`，姿态值走下面的同步通道。

## 客户端同步

姿态值搭在实体同步数据上，不另开网络通道：

- `CarriageContraptionEntity` 在 `defineSynchedData` 中注册一个 `SynchedEntityData` 的 float 访问器；
- 服务端在 `tickContraption` 头部把 `Carriage` 上的权威值写入该访问器；值变化时由原版实体数据同步发给跟踪该实体的客户端；
- 客户端在 `onSyncedDataUpdated` 里把值交给客户端 `Carriage`，后者用 `LerpedFloat` 以 0.5 格/tick 的线性速度向目标追赶；
- 车厢本身只在服务端 tick，因此追赶在客户端实体的 `tickContraption` 头部推进；
- 渲染时按 partialTick 在前后两 tick 的值之间插值，网络间隔内的运动也是平滑的。

## 消费路径

权威值（客户端为平滑后的值）沿以下路径生效：

- **位置与判定盒**：`Entity#position()` 对实现了 `CarriageContraptionEntityAccess` 的实体追加当前偏移。`AbstractContraptionEntity#setPos` 用 `getAnchorVec()`（即 `position()`）重建判定盒，因此服务端判定盒随车体上下移动；
- **上一 tick 位置**：`getPrevPositionVec()` 追加同一偏移，使 `position() − getPrevPositionVec()` 的差值仍只反映真实位移；`setServerSidePrevPosition()` 在捕获 `serverPrevPos` 时扣除当前偏移，缓存的是原始位置，`toLocalVector(..., prevAnchor)` 等依赖上一位置的计算保持一致；
- **轮速**：车轮角积分使用的位移是剔除姿态偏移后的真实轨道位移——`tickContraption` 用 `position()` 与 `xo/yo/zo` 求差，`position()` 带有偏移而 `xo/yo/zo` 是原始字段，这一处把偏移从差值中剔除；
- **车体渲染**：`OrientedContraptionEntityMixin` 在 `applyLocalTransforms` 的旋转链里，用插值后的偏移平移姿态栈，方块结构与方块实体随之上浮/下沉；
- **原版转向架**：由 Flywheel 渲染，其视觉位置来自 `lerp(partialTick, yOld, position())`，只包含 `partialTick × 当前偏移` 的那部分，`CarriageContraptionVisualMixin` 扣除该部分，使转向架保持在轨道上；
- **内部转向架**：由实体渲染器直接绘制，外层姿态栈不含偏移，天然贴轨。

## 与多转向架系统的关系

- 两者相互独立：多转向架系统负责内部转向架的存在、同步与弯道贴轨；姿态系统负责车体相对转向架的垂直偏移；
- 同时启用时：车体（连同车厢结构）上下起伏，所有转向架保持在轨道上，轮速仍按真实位移；
- 两个系统各有一组 common 配置，互不依赖。

## 开关与默认值

| 键 | 作用 |
| --- | --- |
| `enable_carriage_y_offset` | 姿态系统开关；关闭时不发事件、偏移恒为 0，所有消费点为无操作。 |
| `max_carriage_y_offset` | 偏移上限；事件结果与存档恢复值都会夹紧到该幅度。 |

默认值通过 `ModConfigSpec.Builder#define(path, Supplier<Boolean>)` 延迟求值，其他模组可在配置加载前用 `KasugaLibCreateApi.setCarriageYOffsetDefault(boolean)` 覆盖；配置文件中显式写入的值优先级最高。
