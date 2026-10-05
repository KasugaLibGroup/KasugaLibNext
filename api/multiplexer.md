# Multiplexer

接口、配置和调用参考。原理说明见 [multiplexer.md](../doc/multiplexer.md)。

Multiplexer 是**无状态变体选择器**:一组 `Variant` 节点 + 带守卫的转移边。根据上下文(Context)选当前变体(如方块外观/模型)。定义本身不持有运行时状态 —— **一种方块类型共用一份 definition**,每个实例一个 `MuxState`。

> Multiplexer 是**代码定义**(Java DSL),不是 JSON。 FSM 才是数据驱动 JSON(见 [fsm.md](fsm.md))。

## 何时用 Multiplexer,何时用 FSM

| | Multiplexer | FSM |
|---|---|---|
| 驱动方式 | 上下文快照选变体(`ctx.property(...)`) | 分层状态机 + tick 推进 + 动作 |
| 状态 | 无(definition);每实例一个 `MuxState` | 每实例一个完整 `StateMachine` |
| 适合 | 开关、挡位、红石态、模型变体切换 | 动画时序、attack chain、locomotion |
| 定义位置 | Java 代码 | `data/<ns>/state_machines/*.json` |

简单"有电/没电换模型"用 Multiplexer;需要状态时长、动作、多层混合用 FSM。

## 核心 API(`lib.kasuga.rendering.models.uml.dynamic.multiplexer`)

```java
// 1. 定义(共享,每类型一份)
Multiplexer<MyContext, MyVariant> def = Multiplexer.define(MyVariant::new, mux -> {
    MyVariant off = mux.variant("off", v -> { /* 变体配置:模型/材质... */ });
    MyVariant on  = mux.variant("on",  v -> { });
    mux.transition(off, on, t -> t
            .when(ctx -> ctx.property("powered").equals("true"))
            .crossFade(0.2f));
    mux.transition(on, off, t -> t.when(ctx -> ctx.property("powered").equals("false")));
    mux.initial(off);
});

// 2. 每实例一个状态
MuxState<MyVariant> state = def.newState();

// 3. 每 tick 推进(推进 cross-fade + 评估守卫,守卫命中则切换)
def.advance(state, context, dt);

// 当前选中的变体(转移期间是目标变体)
MyVariant current = state.current();
```

### Builder 方法

| 方法 | 说明 |
|------|------|
| `variant(id, config)` | 定义一个变体,返回类型化句柄 |
| `transition(from, to, config)` | 加一条转移边;`TransitionBuilder`: |
| ↳ `.when(Predicate<C>)` | 守卫;为空 = 恒真 |
| ↳ `.crossFade(seconds)` | 淡入淡出秒数;0 = 立即切换 |
| ↳ `.onSwitch(Consumer<MuxState>)` | 切换时回调 |
| `initial(variant)` | 初始变体 |

### 运行时

| 方法 | 说明 |
|------|------|
| `newState()` | 建一个 per-instance `MuxState`(起始 = initial) |
| `advance(state, ctx, dt)` | 推进 cross-fade + 评估守卫,命中则切;每 tick 调 |
| `select(ctx, state)` | 只读:现在会选哪个变体(不改变状态) |
| `state.current()` | 当前变体 |
| `state.inTransition()` / `state.to()` | 转移中 / 目标变体(cross-fade 期间) |

## Minecraft 侧

MC 实现用 `McContext`(把方块态/红石等包成上下文快照)+ `McVariant`(模型/材质变体)+ `McMultiplexer`,核心 `Multiplexer` 不依赖 Minecraft。一个方块类型在注册时建一份 `McMultiplexer`,每个 BE `newState()`,`#onDataChanged` 或 tick 里 `advance(state, ctx, dt)` 并把 `state.current()` 喂给渲染选变体。

## 与 FSM 组合

Multiplexer 选"用哪个模型变体",FSM 驱动"那个模型的动画时序"。两者可叠加:Mux 选变体 → 该变体对应的 `ModelInstance` 再挂一个 FSM `PoseDriver` 跑动画。
