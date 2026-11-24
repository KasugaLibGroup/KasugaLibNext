# 车厢垂直姿态

KasugaLibNext 的 Create 集成允许车厢车体相对转向架做垂直偏移：车体可以上下起伏，转向架始终留在轨道上。偏移由服务端权威计算，经实体同步数据下发，客户端平滑追上。这是一个独立于多转向架系统的能力——普通两转向架车厢同样可用。机制细节见[车厢垂直姿态原理](../../explanation/create/carriage-posture.md)。

## 前提

- 已安装 Create，列车通过 Create 车站装配；
- `kasuga_lib` 的 common 配置 `enable_carriage_y_offset` 为 `true`；
- 有代码监听 `ModifyCarriagePostureEvent` 提供姿态值（没有监听者时偏移恒为 0）。

## 配置项

编辑 `config/kasuga_lib-common.toml`：

```toml
enable_carriage_y_offset = true
max_carriage_y_offset = 0.5
```

| 键 | 默认值 | 作用 |
| --- | --- | --- |
| `enable_carriage_y_offset` | `false` | 姿态系统开关；关闭时不发事件、偏移恒为 0。 |
| `max_carriage_y_offset` | `0.5` | 偏移最大幅度（格，上下对称）；事件结果和存档恢复值都会夹紧到该范围。 |

- 这是 common 配置：客户端与服务端各自读取本地文件，建议保持一致。
- 默认值可通过 API 修改（见"其他模组"）；配置文件中显式写入的值优先级最高。
- 修改后重启游戏（或等待配置重载）生效。

## 提供姿态：`ModifyCarriagePostureEvent`

开关开启后，服务端每 tick 每节车厢发布一次 `lib.kasuga.create.content.train.event.ModifyCarriagePostureEvent`。监听并给出该 tick 的垂直偏移：

```java
@EventBusSubscriber(modid = "your_mod")
public final class CarriagePosture {
    @SubscribeEvent
    public static void onPosture(ModifyCarriagePostureEvent event) {
        if (event.getLevel().isClientSide())
            return;
        event.setYOffset(computeOffset(event.getCarriage(), event.getLevel()));
    }
}
```

- `getLevel()` / `getCarriage()` 提供上下文；`getYOffset()` 初始为 `0`，`setYOffset(float)` 以格为单位，正值向上。
- 事件返回后结果夹紧到 `±max_carriage_y_offset`，作为该 tick 的权威值。
- 客户端不发布事件；收到同步值后按 0.5 格/tick 缓动，渲染再按 partialTick 插值，因此网络间隔内的运动也是平滑的。

## 运行表现

- 车体（方块结构与方块实体）上下移动，转向架保持在轨道上；多转向架车厢的内部转向架同样不受影响。
- 权威值参与服务端判定盒与碰撞：`Entity#position()` 会追加偏移，因此判定盒随车体移动；`position() − getPrevPositionVec()` 的差值仍只反映真实位移。
- 轮速不受姿态偏移影响：车轮按剔除偏移后的真实轨道位移转动。
- 偏移随车厢写入存档、加载时恢复；同步给客户端后由缓动平滑呈现。

## 参考实现

contentTesting 中的 `test.kasuga.create.train.CarriagePostureBobDemo` 是一个完整示例：正弦 ±0.5 格、100 tick 周期。

## 其他模组：修改默认值

依赖该功能的模组可以在自己的 `@Mod` 构造函数或 `FMLConstructModEvent` 中调用：

```java
KasugaLibCreateApi.setCarriageYOffsetDefault(true);
```

默认值在配置加载时才求值，配置加载发生在所有 mod 构造完成之后，因此不存在时序问题；它只影响配置缺省场景，用户显式写入的值始终优先。

## 行为边界

- 只移动车体，不改变转向架位置、轮速与轨道几何；
- 偏移是车厢级状态：同一车厢的所有维度实体共享；
- 与多转向架系统相互独立，可单独启用，也可同时使用（见[多转向架列车车厢](multi-bogey-carriage.md)）；
- 客户端与服务端的 common 配置应一致。

## 验证清单

- 开启开关并由监听器设置值 → 车体上下起伏，转向架不动；
- 设置超过 `max_carriage_y_offset` 的值 → 被夹紧到上限；
- 保存并重进世界 → 偏移被恢复，随后由事件每 tick 重新给出；
- 后加入的玩家能看到车体姿态，数值平滑出现。
