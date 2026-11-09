# LDLib2 Compose 接入

Compose 是上游独立发布的可选扩展。本项目使用 `ldlib2-compose-1.21.1:21.0.0.1:all`、
LDLib2 `2.2.44`，适配模块开发运行使用 NeoForge `21.1.238`。Kotlin 与 Compose 编译器均为
`2.1.20`，运行时由上游 all jar 内嵌提供。依赖和第三方调用位于 `external/uip/ldlib-compose`；
普通 LDLib2 调用仍在 `external/uip/ldlib`，共享 library 不依赖 Compose。

Compose 负责声明式构建和状态重组。演示仍使用 `StylesheetManager.MODERN`，因此控件外观与普通
LDLib2 页面相近；标题 `LDLib2 Compose / 世界双端 UI` 标识本演示。

## 客户端本地世界面板

在客户端渲染线程、进入世界后创建。`transform` 将 GUI 像素映射到绝对世界坐标，规则与 `LdElement` 相同。

```kotlin
val panel = ComposeWorldUi.attach(Constants.UI_BACKEND, 360, 300, transform) {
    Box(layout = { width(320.px); height(260.px) }) {
        Text("Compose / 模型预览")
        ModelPreview(
            ResourceLocation.parse("kasuga_lib:models/gltf/maribel.glb"),
            layout = { width(280.px); height(220.px) },
        )
    }
}
// 由面板拥有者在客户端移除时关闭；退出世界也会清理。
panel.close()
```

`ModelPreview` 也可放进普通 `composeModularUI(...)`。资源地址和可选 `modelName` 沿用
`model_loader`。`yaw`、`pitch` 为弧度，`zoom` 为正数；由 Compose 状态改变这些参数即可旋转或缩放。
节点工厂只创建一次预览，重组不反复分配 GPU 资源；资源 key 改变或条件分支移除节点时释放旧预览。

## 双端 UI：固定注册，客户端重组

必须遵守上游[服务端驱动 UI 规则](https://low-drag-mc.github.io/LowDragMC-Doc/zh/ldlib2/ui/compose/state-and-sync.html#server-driven-ui)。
`UISyncManager` 按注册顺序编号；组合仅在客户端执行。在 composable 内调用 `bindDataSource`、
`setOnServerClick`、`addServerEventListener` 等注册 API，会让两端编号错位。

下面的计数器在两端以相同顺序构造一个同步值和一个消息宿主。客户端只发送操作意图，服务端决定结果。

```kotlin
class CounterMenu : PlayerUIMenuType.PlayerUIHolder {
    private var count = 0
    private val mirror = mutableStateOf(0)

    override fun createUI(player: Player): ModularUI {
        val root = UIElement().layout { it.width(220f).height(100f) }
        val host = ComposeElement {
            val server = rememberServerMessenger()
            Box {
                Text("Server count: ${mirror.value}")
                Button(onClick = { server.send("increment") }, text = "+")
            }
        }
        root.addChild(BindableValue<Int>().bind(
            DataBindingBuilder.intValS2C { count }
                .onRemoteSyncReceived { mirror.value = it }
                .build(player.level().isClientSide)
        ))
        if (!player.level().isClientSide) {
            host.onMessage("increment") { count = (count + 1).coerceAtMost(100) }
        }
        root.addChild(host)
        return ModularUI.of(UI.of(root, StylesheetManager.MODERN), player)
    }
}
```

动态列表同样使用一个同步值承载整个模型，再用 `key(row.id)` 生成控件。演示使用 `CompoundTag`
传输行 ID、中文标题和确认计数；移除操作校验参数类型，添加操作限制行数。不要为每行注册绑定或 RPC。
消息处理器注册在 `createUI` 构建的外层 `ComposeElement` 上；该宿主必须在两端都添加。

通过 `PlayerUIMenuType.register(id) { CounterMenu() }` 在两端注册工厂，再在服务端调用
`PlayerUIMenuType.openUI(player, id)`。菜单对象的玩家、有效性和服务器生命周期沿用 LDLib2 的正常路径。

## 把真实双端菜单移到世界

等标准打开菜单包完成、客户端的 `ModularUIContainerScreen` 已初始化后，在渲染线程调用：

```java
var host = LdMenuElement.attach(Constants.UI_BACKEND, menu, 400, 360, transform);
Constants.UI_BACKEND.openPointer();
// 完成后，关闭世界面板及其仍被当前玩家持有的菜单。
host.close();
```

这个操作移交已有控件树，保留 `player.containerMenu` 和上游同步通道，不另外创建客户端权威副本。
原屏幕移除后，Compose 在世界宿主初始化时重建。宿主补齐客户端同步 tick，服务器仍按正常菜单流程同步。
新版 LDLib2 的 `UISurface` 得到实际画布尺寸和借用的 FBO，剪裁和视觉层不会误用主窗口尺寸。

一个玩家同时只有一个活跃同步菜单。打开新菜单、服务器关闭菜单、离开世界时旧宿主会清理；
旧宿主的 `close()` 不会关闭后来打开的菜单。业务 holder 仍负责服务器权限和 `isStillValid` 校验。
纯客户端面板可以有多个，应使用 `ComposeWorldUi.attach`，不要借用菜单通道冒充双端同步。

开发演示、自动检查、双客户端命令和验证边界见
[适配模块说明](../../external/uip/ldlib-compose/README.md)。
