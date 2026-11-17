# 多转向架车厢原理

KasugaLibNext 在 Create 的车站装配路径上扩展出"一节车厢使用两个以上转向架"的能力，不改写 Create 的方法，只在三个位置做最小干预。使用方式与开关见[使用指南](../../guides/create/multi-bogey-carriage.md)。

## 原版限制

Create 按"一节车厢 = 前转向架 + 后转向架"建模，多转向架结构会在三处失效：

1. `CarriageContraption#assemble` 统计捕获到的转向架，超过两个时抛出
   `train_assembly.too_many_bogeys`；
2. `CarriageContraption#capture` 只在计数等于 2 时写入 `secondBogeyPos`，更多转向架时调用方无法知道结构在哪里结束；
3. `StationBlockEntity#assemble` 把索引 `i` 的车厢与索引 `i + 1` 的车站转向架配对。对 `i..j` 的多转向架组，`j != i + 1` 会触发 `train_assembly.not_connected_in_order`；即使绕过检查，中间的每个转向架也会被当作独立车厢再次装配。

## 捕获阶段

`CarriageContraptionMixin` 做两件事：

- **拆掉数量守卫，而不是取消整个方法。** 在 `assemble` 读取 `bogeys` 进行 `> 2` 比较之前，把真实计数保存到 `ksg$bogeys`，再把字段写成 `2`。比较因此走"通过"分支，方法继续执行控制方向检查、乘务员/座位登记和 `return true`。直接 cancel 会丢掉这些副作用，所以选择改输入而不是提前返回。
- **把 `capture` 的"第二个转向架"判断放宽为 `>= 2`。** `capture` 把测试编译成 `!= 2`；只把参与比较的那次读取钳制到 `min(count, 2)`，从第二个转向架开始都会通过判断并覆盖 `secondBogeyPos`。喂给 `bogeys++` 的那次读取不动，计数器仍然精确。

每次 `secondBogeyPos = pos` 赋值都会把位置记入冗余列表，最后捕获的位置就是最终的后转向架。捕获顺序是 BFS 顺序，不保证等于轨道顺序，因此对外暴露三组数据：

- `isMoreThan2Bogeys()`：本次装配是否真的超过两个（开关关闭时恒为 `false`）；
- `getRedundantBogeys()`：除首个和最后捕获者之外的全部位置，保持原始捕获顺序；
- 仍在 `secondBogeyPos` 里的最后捕获位置。

`CarriageContraptionAccess` 放在 mixin 包外，是因为 Mixin 禁止直接引用其配置包中的类；mixin 实现该接口，普通代码强转使用。

## 车站合并

`StationBlockEntityMixin` 把捕获结果与车站的有序转向架表对齐，再折叠成原版期望的前后两个：

1. 还原捕获集合：`redundantBogeys ∪ {secondBogeyPos}`；
2. 对车站索引 `k > i` 计算期望世界坐标（`bogeyLocations`、`upsideDownBogeys`、两个偏移量和装配方向），从集合中删除命中的位置；
3. 仅当以下条件全部成立时接受该组：
   - `trailing > i + 1`（至少一个内部转向架）；
   - 集合被完全消费（没有捕获到组外的转向架）；
   - `matched == trailing - i`（区间内没有空洞）。
4. 在覆盖配对槽位之前，先用**原始**内部槽位的数据构造 `CarriageBogey`。配对槽 `i + 1` 本身可能就是内部转向架，复制后再构造会把后转向架误标成内部转向架。相对位置取 `expectedPosition(k) - contraption.anchor`，与 `Contraption#toLocalPos` 同一坐标帧。
5. 把后转向架 `j` 的 `bogeyLocations`、`bogeyTypes`、`upsideDownBogeys` 和两个 `TravellingPoint` 复制到槽位 `i + 1`，并让 `getSecondBogeyPos()` 返回 `j` 的期望坐标。原版随后用复制后的字段重算 `expected(i + 1)`，恰好等于返回值，检查通过；构造出的后转向架使用后转向架数据，`bogeySpacing` 变为 `location[j] - location[i]`，即整车长度。
6. 在第二次 `CarriageBogey` 构造调用处把循环局部量设为 `trailing - 1`，让随后的两处自增落在 `trailing`、`trailing + 1`，下一轮从组后开始，内部索引不会被重复装配。
7. 原版把新 `Carriage` 加入结果列表时，通过 `List.add` 的 `WrapOperation` 挂上冗余转向架；`instanceof Carriage` 用于区分同一个擦除签名的其它列表。

任一校验失败都原样返回 `secondBogeyPos`，不写任何状态，装配回到原版路径。

## 持久化

`CarriageMixin` 在 `Carriage#write` 返回时追加一个列表：

```text
KasugaRedundantBogeys: [
  { BogeyData: { Type, Points, UpsideDown, BogeyData }, Position: [x, y, z] },
  ...
]
```

`BogeyData` 存的是完整的 `CarriageBogey#write` tag，而不是原始的 `bogeyData` 字段：重建还需要 `AbstractBogeyBlock` 类型和两个 `TravellingPoint`。`read` 使用与 `FirstBogey`/`SecondBogey` 相同的 `graph`/`dimensions` 还原；`Position` 不可读的条目整条跳过，保证两个列表索引对齐。

只有服务端写这组 NBT；客户端车厢来自 `Train.STREAM_CODEC`，从不读它。

## 客户端同步

`Train.STREAM_CODEC` 只携带前后两个转向架和 `bogeySpacing`，`CarriageSyncData` 也只有四个轮点，所以内部转向架必须走独立通道。`CarriageExtraBogeySync` 为每节有额外转向架的车厢发送一条 `(trainId, carriageIndex, List<Entry>)`，每个 Entry 是 `(车厢局部坐标, 方块注册名, 是否倒置, bogeyData)`：

- 方块类型用 `ResourceLocation` 而不是 `AbstractBogeyBlock.STREAM_CODEC`，因为 RPC 只有 `FriendlyByteBuf`；接收端通过 `BuiltInRegistries.BLOCK` 解析；
- 装配时在 Create 广播 `AddTrainPacket` 之后发送，登录时在 `GlobalRailwayManagerMixin` 的 `playerLogin` `TAIL` 发送；同一连接上的 play 阶段 payload 按写入顺序投递，客户端此时已知晓该 `Train`；
- 客户端处理器整体**替换**两个列表，重复或迟到的同步都是幂等的；未知方块类型连同位置一起跳过；
- 客户端构造的转向架使用三参构造器，`TravellingPoint` 为空。

## 角度与渲染

客户端内部转向架没有轨道点，直接调用 `CarriageBogey#updateAngles` 会一直命中"脱轨"分支。`CarriageContraptionEntityMixin` 在第二次 `updateAngles` 之后自行计算：

- 车轮角：与 Create 相同的积分 `new = (old - 360 * distanceTo / (2π * radius)) % 360`；
- yaw/pitch：按 `t = along / bogeySpacing` 在前、后转向架角度之间插值。`along` 是相对位置在装配方向上的投影，恰好等于该转向架相对前转向架的距离；直线水平轨道完全准确，曲线/坡道为一阶近似。

渲染端把同一投影值传给 `CarriageContraptionEntityRenderer#translateBogey`，并沿用它的光照与 `isHiddenInPortal` 逻辑。Flywheel 可视化只知道 `carriage.bogeys`，因此额外转向架由渲染 mixin 直接绘制。

## 开关

`CreateConfig` 的 common 配置 `enable_multi_bogey_carriage` 是总开关，关闭时所有入口直接返回原版行为：

- 捕获阶段不钳制计数 → 原版 `too_many_bogeys`；
- 车站合并返回原始 `secondBogeyPos`；
- NBT 不写、不读；
- 客户端丢弃收到的数据包，渲染和角度计算跳过。

默认值通过 `ModConfigSpec.Builder#define(path, Supplier<Boolean>)` 延迟求值，其他模组可在配置加载前用 `KasugaLibCreateApi.setMultiBogeyCarriageDefault(boolean)` 覆盖；配置文件中显式写入的值优先级最高。
