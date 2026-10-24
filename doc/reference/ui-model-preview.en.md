# UI model previews

`GuiModelPreview` displays a model published by the existing model loader. Resource locations and
optional MMD entry names follow the normal registration contract. Each preview owns a local
`ModelInstance`, material state, GPU buffers and transparent depth canvas. It does not mount its
instance in the world backend or change an existing world's model pose.

```java
var preview = new GuiModelPreview(
        ResourceLocation.parse("kasuga_lib:models/gltf/maribel.glb"), null);
preview.setView(-0.55f, 0.25f, 1f); // yaw / pitch in radians; positive zoom
```

`render` returns `false` while the asset is unavailable, allowing the host to draw a placeholder.
It resolves the current publication on each draw and releases the old private instance when the
asset changes. Default framing fits the model's bounds; zooming in can intentionally crop it.
An alternative constructor accepts a `Supplier<Model>` that lends an asset or returns `null`.

| Toolkit | Control | Ownership |
|---|---|---|
| Vanilla | `ModelPreviewWidget(x, y, width, height, preview)` | The owning Screen calls `close()` from `removed()` |
| LDLib2 | `ModelPreviewElement(preview)` | Removal closes the preview; supports normal ModularUI and world panels |
| LDLib2 Compose | `ModelPreview(modelLocation, modelName, ...)` | Removal or an asset-key change releases the preview; see the [Compose adapter](../../external/uip/ldlib-compose/README.md) |
| ModernUI | `ModelPreviewView(context, modelLocation, modelName)` | Uses the public MinecraftSurfaceView renderer; detach schedules cleanup on Minecraft's render thread |

Toolkit adapters live separately in `external/uip/ldlib` and `external/uip/modernui`. The shared
renderer depends on neither toolkit. Give each owning control its own preview.

For custom hosts use `preview.render(graphics, x, y, width, height, partialTick)`; an overload accepts
opacity in `[0,1]`. Rendering, instance access and closing belong to the Minecraft render thread;
`setView` may be called by UI input threads. `currentFrame()` lends a read-only color frame, valid
until the next draw, resize or close; never delete its texture or framebuffer. `instance()` allows
the caller to pose the local model. The preview does not drive world physics or global scheduling.

The native preview material path supports depth, skinning and model textures independently of Iris
world passes. Verified on macOS arm64, MC 1.21.1, NeoForge 21.1.203, LDLib2 2.2.1 and ModernUI 3.13.0.1.
Windows, Linux and Iris combinations have not been exercised.

## Development demos

```sh
./gradlew :external:uip:ldlib:runClientModelDemo -PkasugaModelToolkit=vanilla
./gradlew :external:uip:ldlib:runClientModelDemo -PkasugaModelToolkit=ldlib
# Place an isolated world in this module's run/model-demo/saves first.
./gradlew :external:uip:ldlib:runClientModelDemo -PkasugaModelToolkit=world -PkasugaQuickPlayWorld=YourTestWorld
./gradlew :external:uip:modernui:runClientModelDemo
```

Add `-PkasugaModelCapture=/absolute/path/model.png` to run the smoke, capture and exit. Inspect
`passed` in the adjacent `model-report.json`: a failed client assertion can still yield a successful
Gradle exit. Vanilla/LDLib2 check actual model pixels, rotation, GUI resizing and canvas retirement;
ModernUI checks its real render callback and detach cleanup. Demo classes stay out of release jars.
