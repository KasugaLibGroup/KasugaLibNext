# Integrating Content With the Proxy Kernel

This guide is for mod authors who need block entities, Create devices, or external hosts to drive
model animation. The target is one `ProxyDefinition`, one `ProxySession`, and one
`ManagedAnimationScope` per element instance, with typed ports separating host, logic, and visual
concerns.

Read [Proxy system principles](../explanation/proxy/README.en.md) first, especially the lifecycle,
tick/frame boundary, and port definitions.

## Choose the Host Integration

| Host condition | Recommended integration |
| --- | --- |
| A normal `BlockEntity` or `BlockEntityType` is available | `BlockEntityProxyDefinitions + ProxyTickers` |
| The block already has a ticker that must keep running | `ProxyTickers.forBlockEntity(type, existing)` |
| Client-only visuals with no authoritative machine state | `ProxyTickers.forClientBlockEntity(type)` |
| No Minecraft block-entity lifecycle, such as a Create train visual | Direct `ProxyRuntime.register` / `attach` plus external lifecycle forwarding |

Prefer the type catalog for normal blocks. Do not create a model, scope, or second tick from a
block-entity constructor.

## 1. Define Immutable Input, Output, Command, and Frame Types

These four types are the stable contract between the host adapter and the kernel. Records or
other immutable value objects are good choices.

```java
public record MyInput(float speed, boolean powered) {
}

public record MyOutput(int committedStep) {
}

public enum MyCommand {
    TOGGLE
}

public record MyFrame(float rootAngle) {
}
```

Do not put `StateMachine<?>`, `Level`, `ModelInstance`, or a renderer in these types. They belong
inside a composition or visual, not in a public port value.

## 2. Implement Ports and Assemble a Composition

Create a composition for each element instance. The skeleton below uses a no-op controller and no
targets; replace them with the real FSM and `TargetBinding`s when animation is required.

```java
public static ProxyComposition<MyBlockEntity, MyInput, MyOutput, MyCommand, MyFrame>
create(MyBlockEntity element) {
    ProxyInputPort<MyBlockEntity, MyInput> input =
            host -> new MyInput(host.speed(), host.isPowered());

    ProxyControllerPort<MyInput, MyOutput> controller =
            new ProxyControllerPort<>() {
                private MyOutput output;

                @Override
                public void prepare(MyInput input) {
                    // Prepare this step's input only. Do not advance an FSM or another scope.
                }

                @Override
                public RuntimeController<Void> controller() {
                    return RuntimeController.none();
                }

                @Override
                public MyOutput output() {
                    return output;
                }
            };

    return ProxyComposition.of(
            LogicalStepPolicy.fixed(1.0 / 20.0, 5),
            input,
            controller,
            (host, value) -> {
                // Write to the host only after the scope commits successfully.
            },
            ProxyCommandPort.none(),
            ProxyVisualPort.none(),
            List.of() // Add TargetBindings when the visual needs animation.
    );
}
```

When a visual needs to replace models, reuse `RigidPartVisualStage` or
`DeferredModelPoseTarget`; do not create another `ManagedAnimationScope`.

## 3. Make the Block Entity Implement ProxySessionHost

```java
public final class MyBlockEntity extends BlockEntity implements ProxySessionHost {

    private final ProxyAttachToken attachToken = ProxyAttachToken.random();
    @Nullable
    private ProxySession<?, ?, ?, ?, ?> proxySession;

    @Override
    public ProxyAttachToken proxyAttachToken() {
        return attachToken;
    }

    @Override
    public @Nullable ProxySession<?, ?, ?, ?, ?> proxySession() {
        return proxySession;
    }

    @Override
    public void proxySession(@Nullable ProxySession<?, ?, ?, ?, ?> session) {
        if (session != null && proxySession != null && proxySession != session) {
            throw new IllegalStateException("element already has a proxy session");
        }
        proxySession = session;
    }

    @Override
    public void setRemoved() {
        closeProxySession();
        super.setRemoved();
    }

    @Override
    public void onChunkUnloaded() {
        closeProxySession();
        super.onChunkUnloaded();
    }
}
```

The token must remain stable for the life of one element instance. A rebuilt element should get a
new token; never let callbacks from the old visual control the new session.

## 4. Declare the Definition During Content Registration

Associate the definition with the `BlockEntityType` in the block/block-entity registration
callback:

```java
BlockEntityProxyDefinitions.register(
        type,
        ResourceLocation.fromNamespaceAndPath("examplemod", "my_proxy"),
        MyProxyComposition::create
);
```

Use `BlockEntityProxyDefinitions.unregister(type)` when that type is removed. Normal content code
should not need to register directly with `ProxyRuntime`; the type catalog handles it.

## 5. Attach Automatically From the Block Ticker

When there is no other ticker:

```java
@Override
public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
        Level level,
        BlockState state,
        BlockEntityType<T> type
) {
    return ProxyTickers.forBlockEntity(type);
}
```

When the block already has a ticker, compose it and let it run first so the proxy observes the
current host state:

```java
return ProxyTickers.forBlockEntity(type, super.getTicker(level, state, type));
```

The first tick attaches the definition, constructs the composition, binds the visual, and enters
ACTIVE. Normal ticks do not call `activate()` manually. A block with authoritative server machine
state must not use `forClientBlockEntity()`.

## 6. Forward Frames, Commands, and Lifecycle Events

Pass render frames without advancing logical time:

```java
session.publishFrame(new MyFrame(frameRoot));
```

Queue host commands so they are applied at the next logical step:

```java
session.submitCommand(MyCommand.TOGGLE);
```

| Situation | Call |
| --- | --- |
| Element is hidden or frozen but may return | `session.suspend()` |
| A suspended element returns | `session.resume()` |
| Model/resource is not ready | `session.waitForResource()` |
| Resource recovered and visual must rebuild | `session.rebind()` |
| Element is destroyed or its chunk unloads | `session.close()` or `host.closeProxySession()` |

`close()` is idempotent and terminal. Do not use `suspend()` as final cleanup or reactivate a
closed session.

## 7. External Host Integration

A Create train has no normal block-entity proxy attachment point. An adapter such as
`CreateBogeyProxyAdapter` can:

1. Register a temporary definition with a stable external `ProxyKey`;
2. Create one attach token;
3. Call `ProxyRuntime.GLOBAL.attach(definition, element, token)`;
4. Forward destroy/hide/show events to `close`, `suspend`, and `resume`;
5. Call `onDetach` and unregister the definition on every failure path.

The external host also owns frame forwarding. Do not send `partialTick` through the logical tick,
and do not create a session per rendered frame.

## 8. What to Test

Use the existing boundary tests as templates:

- [ProxyKernelTest](../../modules/render-core/src/test/java/lib/kasuga/rendering/models/uml/dynamic/proxy/ProxyKernelTest.java): session ticks,
  frames, commands, revisions, and lifecycle;
- [ProxyKernelBoundaryTest](../../modules/render-core/src/test/java/lib/kasuga/rendering/models/uml/dynamic/proxy/ProxyKernelBoundaryTest.java): recovery and port
  boundaries;
- [ProxyTickersKernelTest](../../modules/modelling/src/test/java/lib/kasuga/rendering/models/mc/proxies/ProxyTickersKernelTest.java): automatic first-tick
  attachment;
- [CreateBogeyProxyAdapterTest](../../modules/create/src/test/java/lib/kasuga/create/client/rendering/CreateBogeyProxyAdapterTest.java): external host
  lifecycle.

At minimum, assert that:

- One tick advances the scope once;
- One render frame does not increase the revision;
- Targets in one batch share a revision;
- The first tick attaches exactly one session;
- Hide/suspend prevents drawing and logical advancement;
- A failed visual replacement preserves the old visual and recovery keeps the original scope;
- Repeated close calls are harmless.

## Troubleshooting

| Symptom | Check first |
| --- | --- |
| Model never appears | Definition registered before the first tick; type/id match; visual bound on the client |
| Animation advances twice per tick | A host, visual, or legacy `ProxyInstance` created another scope |
| Frames duplicate logical events | `publishFrame()` accidentally calls `tick()` or resamples an animation source |
| Model still draws after hide | `suspend()` hides mounted resources instead of only changing session state |
| Host state does not update | `ProxyControllerPort.output()` returns a non-null value; scope/writeback reports no failure |
| Animation restarts after resource reload | `rebind()` reuses the original session instead of rebuilding the scope |
| An old callback controls a new element | Attach tokens are not reused across element rebuilds |

See [Proxy principles](../explanation/proxy/README.en.md) for the system boundary and lifecycle diagrams.
