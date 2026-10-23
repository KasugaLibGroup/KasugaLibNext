# LDLib2 world-space UI

`LdElement` hosts an LDLib2 `ModularUI` on KasugaLib's world-space canvas. The same
LDLib2 controls, styles and local state are rendered into the panel and receive
input through the existing world-plane picking and capture path.

Create the UI after client resources have loaded, then attach it on the client thread:

```java
var root = new UIElement().addClass("panel_bg")
        .layout(layout -> layout.width(320).height(180));
root.addChild(new Button().setText("World button", false));
var ui = ModularUI.of(UI.of(root, StylesheetManager.MODERN));
var transform = new Matrix4f().translation(10, 65, 10).scale(.006f, -.006f, 1);
var panel = LdElement.attach(Constants.UI_BACKEND, ui, 320, 180, transform);

// Move the existing panel; the backend copies this matrix.
Constants.UI_BACKEND.setTransform(panel, nextTransform);

// Call on the client thread when the owner removes its panel.
panel.close();
```

Dimensions and LDLib2 element positions are host GUI pixels. The matrix maps them
to absolute world coordinates; the backend handles camera-relative rendering.
Local +X points right and local +Y points down. A negative world Y scale produces
an upright panel. Each attached `ModularUI` must have its own widget tree and no
existing screen host.

The adapter ticks each panel once per client tick and temporarily disables
LDLib2's optional render-driven ticking. Closing is idempotent, restores that
policy and clears the UI's screen reference and internal focus. Removing the
backend registration, leaving its world or disconnecting also closes the panel.

LDLib2-specific rendering compatibility lives in this module's `compat` package.
While `LdElement` renders, a client-only hook uses premultiplied blending for
LDLib2's SDF rectangles and preserves destination alpha. Transparent hover/focus
outlines therefore retain the input's background. The hook restores blend factors
after each draw; ordinary 2D LDLib2 screens keep their upstream rendering behavior.
The shared world-UI backend contains no LDLib2 hooks or dependencies.

With no active Minecraft screen, the crosshair can select the panel. Editable
LDLib2 `TextField` and `TextArea` focus temporarily captures keyboard input;
Escape clears it and returns to gameplay. For a free cursor, call
`Constants.UI_BACKEND.openPointer()` from gameplay. Escape leaves cursor mode
while the world panel remains in place. Application actions that change server
state still need the application's own networking and validation.

## Interactive demo

```sh
./gradlew :external:uip:ldlib:runClientWorldDemo
```

Enter a development world; the demo appears in front of the camera and opens the
free cursor. The isolated game directory is `run/world-ui-demo` under this module.
Add `-PkasugaQuickPlayWorld=YourTestWorld` to open a world in that directory directly.

The module's development client also provides:

- `/ksglib debug ldlib_ui world`: create the world panel and open its free cursor.
- `/ksglib debug ldlib_ui cursor`: reopen the cursor for the existing panel.
- `/ksglib debug ldlib_ui stop`: remove the world panel.
- `/ksglib debug ldlib_ui`: open the same controls as a regular 2D screen.

The demo uses LDLib2 2.2.1's built-in MODERN stylesheet and can switch to MC/GDP
without replacing the controls. It includes a button counter, text field, switch,
draggable scroller, linked progress bar, selector and Chinese text. After a resource
reload, reopen the demo to obtain updated stylesheets.

## Real-client verification

Use an isolated development world; the smoke edits its local UI and exits:

```sh
./gradlew :external:uip:ldlib:runClientWorldDemo \
  -PkasugaQuickPlayWorld=YourTestWorld \
  -PkasugaLdlibWorldSmoke=true \
  -PkasugaLdlibCapture=/tmp/ldlib-world-ui.png
```

This checks projected button/switch/drag interactions, state-preserving theme
switching, pointer and crosshair Unicode input through the real keyboard entry
points, gameplay-key suppression, Escape and idempotent cleanup. It writes a
framebuffer screenshot and checks that the unchanged title retains its glyph
pixels after theme switching. It also reads the panel's actual RGBA attachment in
default, hover and focused text-field states, and checks SDF alpha accumulation,
premultiplied color, blend restoration and the unmodified ordinary-screen path.
Capturing restores the previous GL texture binding.
It writes `world-report.json`; check `passed`, since stopping the
client after a failed assertion can still produce a successful Gradle exit.

For a 2D comparison, run `:external:uip:ldlib:runClientDemo`; add
`-PkasugaLdlibCapture=/tmp/ldlib-ui.png` to capture it and exit automatically.
Demo/smoke classes live in `contentTesting` and are excluded from the published
mod JAR. LDLib2 supplies its own resources and nested libraries as a dependency.
