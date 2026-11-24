# 多转向架车厢原理

KasugaLibNext 在 Create 的车站装配路径上扩展出"一节车厢使用两个以上转向架"的能力，不改写 Create 的方法：装配路径上只做三处最小干预，其余部分是内部转向架的运动、朝向、弯道贴轨与出轨判定。使用方式与开关见[使用指南](../../guides/create/multi-bogey-carriage.md)。车体相对转向架的垂直姿态是独立系统，见[车厢垂直姿态原理](carriage-posture.md)。

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

## 内部转向架：运动、朝向与贴轨

客户端内部转向架没有轨道点，直接调用 `CarriageBogey#updateAngles` 会一直命中"脱轨"分支。`CarriageContraptionEntityMixin` 在第二次 `updateAngles` 之后自行计算：

**车轮角**：与 Create 相同的积分 `new = (old - 360 · d / (2π · r)) % 360`，`d` 是实体本 tick 沿轨道的真实位移。

**朝向（yaw/pitch）**：从车体最前端轮点出发，沿轨道图朝后转向架方向行走 `前转向架半轮距 + along ± 该转向架半轮距`，得到该内部转向架的两个轮触点，再套用原版 `updateAngles` 的公式（`Δ = 前触点 − 后触点`，`yaw = −(deg(atan2(Δz, Δx)) + 90)`，`pitch = deg(atan2(Δy, 水平长度))`）。

- 行走使用 `TravellingPoint#travel`：弯道段由 `TrackEdge#incrementT → BezierConnection` 把距离换算成贝塞尔参数，节点处由 `follow(后转向架后轮点)` 选择分支，S 弯跨节点时取到的是该处真实的曲线切线；
- 每次步进不超过 5 格，保证距离到贝塞尔参数的映射准确；
- `along` 是相对位置在装配方向上的投影，即该转向架相对前转向架的距离；
- 客户端尚未拿到轨道图、列车已脱轨、行走被阻断或采样点退化时，回退到首尾转向架角度插值。

**弯道侧移**：车体是首尾转向架之间的弦，轨道是曲线，内部转向架需要相对弦线向弯道外侧移动才能让车轮留在轨道上。以首尾 yaw 差 `θ` 构造圆弧：

```text
L = bogeySpacing
R = L / (2 · sin(|θ|/2))
u = (t − 0.5) · L
offset(t) = −sign(θ) · (sqrt(R² − u²) − R · cos(|θ|/2))
```

拱高在弦中点最大、两端为 0；正方向为"沿 leading→trailing 弦的右手侧"，圆弧向弯道外侧凸出。结果夹紧到 `max_bogey_offset`，按格存入每架转向架的 `LerpedFloat`，渲染时按 partialTick 插值。

**渲染**：`CarriageContraptionEntityRendererMixin` 沿用同一 `along` 投影作为弦向位置传给 `CarriageContraptionEntityRenderer#translateBogey`，并把侧移作为世界水平位移加在 `translateBogey` 之前——`translateBogey` 弦向坐标系的局部 +X 轴映射到世界 `(sin viewYRot, 0, cos viewYRot)`。Flywheel 可视化只知道 `carriage.bogeys`，因此额外转向架由渲染 mixin 直接绘制。

## 出轨判定

`CarriageMixin` 在 `Carriage.manageEntities` 尾部（服务端、每车厢每 tick）做一道判定：

- 前置条件：多转向架开启、`derailed_on_bogey_offset_overflow` 开启、车厢有内部转向架、列车未出轨、轨道图与首尾转向架的四个轨道点有效；
- 端部航向从真实轨道点推算（服务端不维护客户端的 yaw `LerpedFloat`），公式与 `updateAngles` 相同；
- 拱高沿弦中间最大，因此直接取弦中点：`R · (1 − cos(|θ|/2))`，它不小于任何内部转向架实际需要的侧移，覆盖所有位置；
- 超过 `max_bogey_offset` 时按 Create 自己的高应力出轨流程处理：`speed = 0`、取消导航、`runtime.tick`、`derailed = true`、`status.highStress()`；已出轨的列车跳过后续判定。

## 开关

`CreateConfig` 的 common 配置中与多转向架相关的三项：

| 键 | 作用 |
| --- | --- |
| `enable_multi_bogey_carriage` | 多转向架总开关，关闭时装配、捕获、持久化、同步、渲染与角度计算全部回到原版路径。 |
| `max_bogey_offset` | 内部转向架弯道侧移上限，同时也是出轨判定的阈值。 |
| `derailed_on_bogey_offset_overflow` | 弯道所需侧移超过 `max_bogey_offset` 时是否出轨。 |

`enable_multi_bogey_carriage` 关闭时的回退点：

- 捕获阶段不钳制计数 → 原版 `too_many_bogeys`；
- 车站合并返回原始 `secondBogeyPos`；
- NBT 不写、不读；
- 客户端丢弃收到的数据包，渲染和角度计算跳过。

默认值通过 `ModConfigSpec.Builder#define(path, Supplier<Boolean>)` 延迟求值，其他模组可在配置加载前用 `KasugaLibCreateApi.setMultiBogeyCarriageDefault`、`setDerailedOnBogeyOffsetDefault` 覆盖；配置文件中显式写入的值优先级最高。
