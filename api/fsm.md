# FSM 数据驱动 JSON Schema

接口、配置和调用参考。原理说明见 [fsm.md](../doc/fsm.md)。

动画状态机(FSM)是 Mecanim 风格的**分层**状态机:多个 layer 并行运行,每 tick 合成各 layer 的 pose(BASE/ADDITIVE/OVERRIDE)写进模型。定义放在数据包里,由 `StateMachineDefinitionLoader` 在 reload 时加载进 `FsmRegistries.GLOBAL`。

## 文件位置

```
data/<namespace>/state_machines/<path>.json
```

文件解析出的 `id` 字段就是机器的标识(如 `kasuga_lib:beacon`),方块/BE 通过这个 id 绑定机器。文件路径与 id 不需要一致。

## 顶层结构

```json
{
  "id": "<namespace>:<path>",     // 必填,机器标识
  "state_vars": [ ... ],          // 可选,类型化变量声明
  "layers":  [ ... ]              // 必填,并行状态层
}
```

## state_vars(可选)

机器持有的类型化变量。条件(`when`)和动作里通过变量 id 读写。两种声明方式:

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `name` | string | — | 变量名,本机内唯一;在 `when`/动作里用它引用 |
| `type` | string | `float` | `bool` / `int` / `float` / `string` / `resource` / `vec3` |
| `default` | 同 type | — | 默认值(inline 声明时用) |
| `reference` | string(id) | — | `namespace:path` 引用已注册的变量;给了就忽略 `type`/`default` |
| `ephemeral` | bool | false | true = tick 末自动清除(做触发器用) |

```json
"state_vars": [
  { "name": "speed",  "type": "float", "default": 0.0 },
  { "name": "attack", "type": "bool",  "ephemeral": true }
]
```

## layers

每层是一个独立的状态图 + 混合属性。多个 layer 并行,按顺序合成。

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `id` | string | — | 层标识 |
| `mode` | string | `base` | `base`(底层)/ `additive`(累加)/ `override`(覆盖) |
| `weight` | float | `1.0` | 该层权重 |
| `bone_mask` | string | 全部 | 骨骼掩码(只影响指定骨骼组) |
| `initial_state` | string | — | 初始状态 id |
| `states` | array | `[]` | 状态节点 |
| `transitions` | array | `[]` | 转移边 |

## states

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `id` | string | — | 状态标识 |
| `duration_ticks` | int | — | 持续 tick 数;配 `when_complete` 自动结束 |
| `pose` | object | 空 | 该状态对模型施加的 pose(见下) |
| `on_enter` / `on_exit` / `on_update` | id[] | `[]` | 动作函数 id(在 `FsmFunctionLibrary` 里注册) |

## transitions

| 字段 | 类型 | 默认 | 说明 |
|------|------|------|------|
| `id` | string | — | 转移标识 |
| `from` / `to` | string | — | 起/止状态 id |
| `when` | id[] | `[]` | 守卫函数 id(返回 true 才允许转移) |
| `trigger_on` | string | — | 触发器变量名(布尔 ephemeral var 置位时触发) |
| `when_complete` | bool | false | `from` 状态到 `duration_ticks` 时触发 |
| `cross_fade_seconds` | float | `0` | 淡入淡出秒数;0 = 立即切换 |
| `on_fire` | id[] | `[]` | 转移触发时执行的动作函数 id |

> `when` / `on_*` 引用的函数必须在代码侧用 `FsmFunctionLibrary` 注册(注册一个 `Id` → `Predicate<StateContext>` / `Consumer<StateContext>`)。JSON 只存 id。

## pose(可选)

状态对模型施加的 pose:morph 权重、骨骼变换、材质帧、IK 链开关。

```json
"pose": {
  "morphs": { "blink": 0.5, "mouth_open": 0.8 },
  "bones": [
    { "name": "head", "transform": { "rotate": [0, 30, 0] }, "mode": "replace" },
    { "name": "arm",  "transform": { "translate": [0, 0.5, 0] }, "mode": "add" }
  ],
  "frames": [ { "material": "kasuga_lib:fan_blades", "frame": 2 } ],
  "ik_enabled": { "left-arm": false }
}
```

- `transform`:`translate` / `rotate`(度) / `scale`,均为 `[x, y, z]`,缺省 = 单位。
- `bones.mode`:`replace`(覆盖)/ `add`(位移累加)/ `multiply`。
- `ik_enabled`:可选的链名到布尔值映射，默认空；开关混合与重置规则见 [Pose IK 通道](model-posing.md#pose-的-ik-通道)。

## 混合模式

- `base`:底层 pose,作为基准。
- `additive`:在 base 上累加(适合上半身动作叠在 locomotion 上)。
- `override`:覆盖指定骨骼(`bone_mask` 圈定范围)。
- 转移期间自动在 `from`/`to` pose 之间按 `cross_fade_seconds` 做线性插值(渲染线程按 `partialTick` 帧率平滑)。

## 完整示例

一个风扇方块:有电就转(加速到满速),断电减速到停。

```json
{
  "id": "kasuga_lib:fan",
  "state_vars": [
    { "name": "powered", "type": "bool", "default": false },
    { "name": "speed",   "type": "float", "default": 0.0 }
  ],
  "layers": [{
    "id": "body", "mode": "base", "weight": 1.0, "initial_state": "off",
    "states": [
      { "id": "off",       "pose": { "morphs": { "spin": 0.0 } } },
      { "id": "spinning",  "pose": { "morphs": { "spin": 1.0 } },
        "on_update": ["kasuga_lib:fan_ramp_speed"] }
    ],
    "transitions": [
      { "id": "off_to_spin",  "from": "off",      "to": "spinning", "when": ["kasuga_lib:is_powered"], "cross_fade_seconds": 0.25 },
      { "id": "spin_to_off",  "from": "spinning", "to": "off",      "when": ["kasuga_lib:is_unpowered"], "cross_fade_seconds": 0.5 }
    ]
  }]
}
```

`kasuga_lib:is_powered` / `kasuga_lib:fan_ramp_speed` 等是代码侧注册的守卫/动作函数(读 `ctx.get(POWERED)`、写 `ctx.set(SPEED, ...)`)。方块侧把 `POWERED` 变量接到红石信号即可。

更小的例子见 `modules/modelling/src/gameTest/resources/data/kasuga_lib/state_machines/`(beacon、gametest_loader)。

## 绑定到方块

数据驱动注册的方块通过 `fsm_block` + `fsm_be` 工厂绑定一个机器 id，详见 [数据驱动注册 API](data-driven-registration.md)：
```json
{ "id": "kasuga_lib:fan_block", "type": "fsm_block", "state_machine": "kasuga_lib:fan",
  "block_entity": { "type": "fsm_be", "params": { "model": "kasuga_lib:models/fan.obj" } } }
```
