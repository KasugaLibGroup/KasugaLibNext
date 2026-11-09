# 把内容接入 Proxy Kernel

本指南面向需要让方块实体、Create 设备或其他宿主驱动模型动画的模组作者。目标是建立一个
`ProxyDefinition`，让每个元素实例拥有一个 `ProxySession` 和一个
`ManagedAnimationScope`，并用明确的 Port 隔离宿主、逻辑和视觉。

先阅读 [Proxy 系统原理](../explanation/proxy/README.md)，尤其是 Session 生命周期、tick/frame
边界和 Port 的定义。

## 先选择宿主接法

| 宿主条件 | 推荐接法 |
| --- | --- |
| 普通 `BlockEntity` 或 `BlockEntityType` 可获得 | `BlockEntityProxyDefinitions + ProxyTickers` |
| 方块已有自己的 ticker，仍需要继续运行 | 使用 `ProxyTickers.forBlockEntity(type, existing)` |
| 仅客户端显示，没有服务端机器状态 | `ProxyTickers.forClientBlockEntity(type)` |
| 没有 Minecraft BlockEntity 生命周期，如 Create 列车视觉 | 直接用 `ProxyRuntime.register` / `attach`，由外部宿主转发生命周期 |

普通方块优先使用类型目录。不要在每个方块实体的构造函数里创建模型、Scope 或 second tick。

## 1. 定义不可变输入、输出、命令和帧

这四个类型是 Host Adapter 与 Kernel 之间的稳定契约。record 或不可变 value object 最合适。

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

避免把 `StateMachine<?>`、`Level`、`ModelInstance` 或 Renderer 对象放进这些类型。它们属于
Composition 或 Visual 的内部实现，不应成为 Port 的公共输入。

## 2. 实现 Port 并组装 Composition

Composition 只在元素实例上创建一次。下面是带空 controller 和空 target 的最小骨架；接入
FSM 或模型时，把 controller 和 `TargetBinding` 替换成真实实现。

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
                    // 只准备本步输入；不要在这里推进 FSM 或第二个 Scope。
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
                // 只在 Scope 提交成功后把 value 写回宿主。
            },
            ProxyCommandPort.none(),
            ProxyVisualPort.none(),
            List.of() // 需要动画时加入 TargetBinding。
    );
}
```

Visual 如果需要替换模型，通常复用 `RigidPartVisualStage` 或
`DeferredModelPoseTarget`，不要自行创建新的 `ManagedAnimationScope`。

## 3. 让方块实体实现 ProxySessionHost

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

Token 必须在元素实例的整个生命周期中稳定。元素重建时应创建新的 token；不要复用旧 token
让旧视觉回调操作新 Session。

## 4. 在内容注册期声明 Definition

Definition 与 `BlockEntityType` 绑定，通常放在方块/BlockEntity 注册回调中：

```java
BlockEntityProxyDefinitions.register(
        type,
        ResourceLocation.fromNamespaceAndPath("examplemod", "my_proxy"),
        MyProxyComposition::create
);
```

需要删除该类型时调用 `BlockEntityProxyDefinitions.unregister(type)`。正常内容生命周期通常
不需要手动注册进 `ProxyRuntime`；类型目录会处理。

## 5. 在方块 ticker 中自动挂载

没有其他 ticker 时：

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

已有 ticker 时必须组合，并让旧 ticker 先运行，使 proxy 观察到本 tick 的最新宿主状态：

```java
return ProxyTickers.forBlockEntity(type, super.getTicker(level, state, type));
```

元素第一次 tick 会自动 `attach`、创建 Composition、绑定 Visual 并进入 ACTIVE。正常路径
不需要手动 `activate()`。带服务端机器状态的方块不要使用 `forClientBlockEntity()`。

## 6. 转发 frame、command 和生命周期

宿主把 render frame 交给 Session，不要直接推进逻辑：

```java
session.publishFrame(new MyFrame(frameRoot));
```

玩家操作或宿主命令进入 Session 队列，在下一个逻辑步开始时应用：

```java
session.submitCommand(MyCommand.TOGGLE);
```

生命周期语义：

| 场景 | 调用 |
| --- | --- |
| 元素隐藏或冻结，之后仍恢复 | `session.suspend()` |
| 恢复暂存的元素 | `session.resume()` |
| 模型/资源尚不可用 | `session.waitForResource()` |
| 资源恢复后重建视觉 | `session.rebind()` |
| 元素销毁或 chunk 卸载 | `session.close()` 或 `host.closeProxySession()` |

`close()` 是幂等终态。不要用 `suspend()` 代替最终清理，也不要在旧 Session 已关闭后再次
激活它。

## 7. 外部宿主接法

Create 列车没有普通 BlockEntity proxy 的自动挂载点。它可以像
`CreateBogeyProxyAdapter` 一样：

1. 用稳定的外部 `ProxyKey` 注册临时 Definition；
2. 创建唯一的 attach token；
3. `ProxyRuntime.GLOBAL.attach(definition, element, token)`；
4. 在宿主 destroy/hide/show 事件中转发 `close/suspend/resume`；
5. 确保失败路径执行 `onDetach` 并撤销 Definition。

外部宿主还必须负责 frame 路径。不要把 `partialTick` 转交给逻辑 tick，也不要为每帧创建
新 Session。

## 8. 应该验证什么

优先复用现有边界测试作为模板：

- [ProxyKernelTest](../../modules/render-core/src/test/java/lib/kasuga/rendering/models/uml/dynamic/proxy/ProxyKernelTest.java)：Session tick、frame、
  command、revision 和生命周期；
- [ProxyKernelBoundaryTest](../../modules/render-core/src/test/java/lib/kasuga/rendering/models/uml/dynamic/proxy/ProxyKernelBoundaryTest.java)：失败恢复和 Port
  边界；
- [ProxyTickersKernelTest](../../modules/modelling/src/test/java/lib/kasuga/rendering/models/mc/proxies/ProxyTickersKernelTest.java)：方块实体首次 tick
  自动挂载；
- [CreateBogeyProxyAdapterTest](../../modules/create/src/test/java/lib/kasuga/create/client/rendering/CreateBogeyProxyAdapterTest.java)：外部宿主生命周期。

最小行为测试应断言：

- 同一个 tick 只推进一次 Scope；
- 一个 render frame 不增加 revision；
- 同批 Target 的 revision 相同；
- 首次 tick 只 attach 一个 Session；
- suspend/hide 后不会继续绘制或推进逻辑；
- 资源替换失败保留旧视觉，恢复后继续原 Scope；
- close 重复调用无副作用。

## 故障排查

| 现象 | 优先检查 |
| --- | --- |
| 模型完全不出现 | Definition 是否在元素首次 tick 前注册；type/id 是否一致；Visual 是否在客户端 bind |
| 动画每次 tick 前进两倍 | 宿主、Visual 或旧 `ProxyInstance` 是否又创建了一个 Scope |
| frame 改变后逻辑事件重复 | 是否在 `publishFrame()` 路径调用了 `tick()` 或重新采样动画源 |
| hide 后模型仍绘制 | `suspend()` 是否真的隐藏已挂载模型资源，而不只是改变 Session state |
| 宿主状态没有更新 | `ProxyControllerPort.output()` 是否返回非 null；Scope 与 writeback 是否发生异常 |
| 资源重载后动画从头开始 | `rebind()` 是否复用了原 Session，还是重建了新 Scope |
| 旧回调控制新元素 | attach token 是否随实例重建；是否错误复用了 token |

系统边界和生命周期图见 [Proxy 原理](../explanation/proxy/README.md)。
