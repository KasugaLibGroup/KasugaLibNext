# Model Variant Selection (Multiplexer) Reference

Interfaces, configuration, and call reference. For the mechanics see [multiplexer.md](../explanation/multiplexer.md).

Multiplexer is a **stateless variant selector**: a set of `Variant` nodes plus guarded transition edges. It picks the current variant (such as a block's appearance/model) from the context. The definition itself holds no runtime state — **one block type shares one definition**, with one `MuxState` per instance.

> Multiplexer is a **code definition** (Java DSL), not JSON. FSM is the data-driven JSON one (see [fsm.md](fsm.md)).

## When to use Multiplexer and when to use FSM

| | Multiplexer | FSM |
|---|---|---|
| Driving mechanism | Context snapshot selects the variant (`ctx.property(...)`) | Layered state machine + tick advance + actions |
| State | None (definition); one `MuxState` per instance | One complete `StateMachine` per instance |
| Suited to | Switches, gears, redstone states, model variant switching | Animation timing, attack chains, locomotion |
| Definition location | Java code | `data/<ns>/state_machines/*.json` |

Use Multiplexer for a simple "powered / unpowered model swap"; use FSM when you need state duration, actions, and multi-layer blending.

## Core API (`lib.kasuga.rendering.models.uml.dynamic.multiplexer`)

```java
// 1. Definition (shared, one per type)
Multiplexer<MyContext, MyVariant> def = Multiplexer.define(MyVariant::new, mux -> {
    MyVariant off = mux.variant("off", v -> { /* variant config: model/material... */ });
    MyVariant on  = mux.variant("on",  v -> { });
    mux.transition(off, on, t -> t
            .when(ctx -> ctx.property("powered").equals("true"))
            .crossFade(0.2f));
    mux.transition(on, off, t -> t.when(ctx -> ctx.property("powered").equals("false")));
    mux.initial(off);
});

// 2. One state per instance
MuxState<MyVariant> state = def.newState();

// 3. Advance each tick (advance the cross-fade + evaluate guards; a guard hit switches)
def.advance(state, context, dt);

// The currently selected variant (during a transition it is the target variant)
MyVariant current = state.current();
```

### Builder methods

| Method | Description |
|------|------|
| `variant(id, config)` | Defines a variant and returns a typed handle |
| `transition(from, to, config)` | Adds a transition edge; `TransitionBuilder`: |
| ↳ `.when(Predicate<C>)` | Guard; empty = always true |
| ↳ `.crossFade(seconds)` | Cross-fade duration in seconds; 0 = switch immediately |
| ↳ `.onSwitch(Consumer<MuxState>)` | Callback on switch |
| `initial(variant)` | Initial variant |

### Runtime

| Method | Description |
|------|------|
| `newState()` | Creates a per-instance `MuxState` (starting at initial) |
| `advance(state, ctx, dt)` | Advances the cross-fade + evaluates guards, switching on a hit; call every tick |
| `select(ctx, state)` | Read-only: which variant would be selected now (does not change state) |
| `state.current()` | Current variant |
| `state.inTransition()` / `state.to()` | In transition / target variant (during the cross-fade) |

## Minecraft side

The MC implementation uses `McContext` (wrapping block states/redstone and so on into a context snapshot) + `McVariant` (model/material variant) + `McMultiplexer`; the core `Multiplexer` does not depend on Minecraft. A block type creates one `McMultiplexer` at registration, each BE calls `newState()`, and in `#onDataChanged` or tick it calls `advance(state, ctx, dt)` and feeds `state.current()` to rendering to select the variant.

## Composition with FSM

Multiplexer selects "which model variant to use", FSM drives "the animation timing of that model". The two compose: Mux selects the variant → the `ModelInstance` for that variant mounts an FSM `PoseDriver` to run animation.
