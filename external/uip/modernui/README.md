# ModernUI model preview adapter

This optional module requires the official ModernUI NeoForge 1.21.1 distribution (3.13.0.1).
`ModelPreviewView` hosts the shared model-loader preview using ModernUI's public
[`MinecraftSurfaceView.Renderer`](https://github.com/BloCamLimb/ModernUI-MC/blob/1.21.1/common/src/main/java/icyllis/modernui/mc/MinecraftSurfaceView.java)
callback. GPU work and disposal run on Minecraft's render thread, including when the view is detached
from ModernUI's UI thread. Each view owns its own preview and can be detached and reattached.

```java
var view = new ModelPreviewView(context,
        ResourceLocation.parse("kasuga_lib:models/gltf/maribel.glb"), null);
view.setModelView(-0.55f, 0.25f, 1f);
container.addView(view, new LinearLayout.LayoutParams(360, 260));
```

See [UI model previews](../../../doc/reference/ui-model-preview.en.md) for ownership, resource reloads,
the vanilla/LDLib2 equivalents and the real-client demo. ModernUI classes and dependencies are confined
to this module; the shared library does not package them.
