# LDLib2 Compose Integration

Compose is an optional extension published independently upstream. This project uses `ldlib2-compose-1.21.1:21.0.0.1:all` and LDLib2 `2.2.44`, while the adapter module develops and runs on NeoForge `21.1.238`. Both Kotlin and the Compose compiler are `2.1.20`, and the runtime is embedded in the upstream `all` JAR. Dependencies and third-party integration code live in `external/uip/ldlib-compose`; ordinary LDLib2 calls remain in `external/uip/ldlib`, and the shared library does not depend on Compose.

Compose handles declarative UI construction and state recomposition. The demo still uses `StylesheetManager.MODERN`, so controls look similar to ordinary LDLib2 pages; the title `LDLib2 Compose / 世界双端 UI` identifies this demo.

## Client-Local World Panels

Create world panels on the client render thread after entering a world. `transform` maps GUI pixels to absolute world coordinates, following the same rules as `LdElement`.

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
// Close on the client when the panel owner removes it; leaving the world also cleans it up.
panel.close()
```

`ModelPreview` can also be placed inside an ordinary `composeModularUI(...)`. The resource location and optional `modelName` follow `model_loader`. `yaw` and `pitch` are in radians, and `zoom` is positive; changing these parameters via Compose state rotates or zooms the preview. The node factory creates the preview only once so recomposition does not repeatedly allocate GPU resources; the old preview is released when the resource key changes or a conditional branch removes the node.

## Client-Server UI: Fixed Registration, Client Recomposition

You must follow the upstream [server-driven UI rules](https://low-drag-mc.github.io/LowDragMC-Doc/zh/ldlib2/ui/compose/state-and-sync.html#server-driven-ui). `UISyncManager` assigns numeric IDs in registration order, while composition runs only on the client. Calling registration APIs such as `bindDataSource`, `setOnServerClick`, or `addServerEventListener` inside a composable causes the IDs on the two sides to drift out of sync.

The counter below constructs one synchronized value and one message host in the same order on both sides. The client sends only user intent, and the server determines the result.

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

Dynamic lists likewise carry the entire model in a single synchronized value and build controls using `key(row.id)`. The demo uses `CompoundTag` to transfer each row ID, Chinese title, and acknowledgment count; removal validates argument types, and addition caps the row count. Do not register per-row bindings or RPCs. Message handlers are registered on the outer `ComposeElement` constructed in `createUI`; that host must be added on both sides.

Register the factory on both sides with `PlayerUIMenuType.register(id) { CounterMenu() }`, and open it on the server with `PlayerUIMenuType.openUI(player, id)`. Player ownership, validity, and server lifecycle of the menu object follow the normal LDLib2 path.

## Moving a Real Client-Server Menu into World Space

Once the standard open-menu packet completes and the client's `ModularUIContainerScreen` has initialized, call on the render thread:

```java
var host = LdMenuElement.attach(Constants.UI_BACKEND, menu, 400, 360, transform);
Constants.UI_BACKEND.openPointer();
// When finished, close the world panel and the menu still held by the current player.
host.close();
```

This transfers the existing widget tree while retaining `player.containerMenu` and the upstream synchronization channel, without creating a separate client-authoritative copy. After the original screen is removed, Compose recomposes when the world host initializes. The host supplies client synchronization ticking, while the server continues syncing through the normal menu flow. LDLib2's `UISurface` receives the actual canvas dimensions and borrowed FBO so clipping and visual layers do not mistakenly use the main window dimensions.

A player can have only one active synchronized menu at a time. Opening a new menu, closing the menu from the server, or leaving the world cleans up the old host; calling `close()` on an old host does not close a menu opened later. The application holder remains responsible for server-side permission checks and `isStillValid` validation. Multiple client-only panels may coexist; use `ComposeWorldUi.attach` for those instead of hijacking the menu channel to fake client-server synchronization.

See the [adapter module documentation](../../external/uip/ldlib-compose/README.md) for development demos, automated checks, two-client commands, and verification boundaries.
