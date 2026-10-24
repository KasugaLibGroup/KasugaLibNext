# LDLib2 Compose adapter

This optional module uses the official `ldlib2-compose-1.21.1:21.0.0.1:all` distribution,
LDLib2 2.2.44, Kotlin/Compose compiler 2.1.20 and Compose runtime 1.8.2. Its development
clients run NeoForge 21.1.238. The shared Kasuga library and the ordinary LDLib2 adapter
do not depend on Compose. Install this adapter together with the LDLib2 adapter, LDLib2
and the official Compose **all** jar, which embeds its runtime dependencies.

Compose controls declarative construction and recomposition. The demo still uses
`StylesheetManager.MODERN`, so its controls share the ordinary LDLib2 appearance.

The upstream APIs (`ComposeElement`, `composeModularUI`, `rememberServerMessenger`,
`UIElementNode`, etc.) remain available. This adapter adds:

- `ComposeWorldUi.attach(...)`: a client-local composition on the existing world canvas.
- `ModelPreview(...)`: a composition-owned preview of a model published by `model_loader`.
- The LDLib2 adapter's `LdMenuElement.attach(...)`: move an already-open, server-backed
  menu into world space while retaining its normal synchronization channel.

See the [Chinese integration guide](../../../doc/reference/ldlib-compose.md) for complete
examples and lifecycle rules. Follow the upstream [state and server-driven UI rules](https://low-drag-mc.github.io/LowDragMC-Doc/zh/ldlib2/ui/compose/state-and-sync.html#server-driven-ui):
register synchronization values and RPCs in `createUI` on both sides, outside the composition;
send named messages from composed controls. Dynamic rows must not register additional slots.

## Real-client demos

Place an isolated development world named `compose-demo` under
`run/compose-host/saves` in this module, then run:

```sh
# Real server menu, rendered as a regular 2D screen
./gradlew :external:uip:ldlib-compose:runClientComposeDemo \
  -PkasugaQuickPlayWorld=compose-demo -PkasugaComposeWorld=false

# The same synchronized menu, rendered and picked in world space
./gradlew :external:uip:ldlib-compose:runClientComposeDemo \
  -PkasugaQuickPlayWorld=compose-demo
```

The development-only `/kasuga_compose` server command opens the demo menu. Without capture,
the UI remains interactive. Add `-PkasugaComposeCapture=/absolute/path/host.png` to run the
automatic smoke and exit. Read `passed` in the neighboring `<peer>-report.json`; a failed
client assertion may still produce a successful Gradle exit.

The smoke exercises initial synchronization, server-side add/remove/clear, keyed row retention,
malformed payload rejection, a second message host after dynamic widgets, model rotation,
conditional model disposal/recreation, Chinese text and menu close/reopen/server close.
One additional fixed C2S probe binding verifies that menu synchronization keeps ticking in world space.
For a negative probe use `-PkasugaComposeDropAdd=true -PkasugaComposeTimeoutSeconds=40`:
the same smoke must fail waiting for the server's fourth row. All probes/demos are excluded
from release jars.

For a two-client test, start the host with `-PkasugaComposeLanPort=25577` and a capture path,
wait for `server-ready.json`, then start another Gradle invocation with
`-PkasugaComposePeer=guest -PkasugaComposePaired=true -PkasugaComposeServer=127.0.0.1:25577`
and a guest capture path **in the same output directory**. Each client uses its own run
directory. The host waits for the guest, verifies that its fourth row leaves the guest at
three, then both exercise their own menus. Use a fresh output directory for each run.
The guest uses Minecraft's normal LAN connection path for development profiles.

Runtime validation is platform-specific; macOS arm64 game results do not imply Windows/Linux
or Iris game results. A LAN test is distinct from a physical dedicated-server test.
