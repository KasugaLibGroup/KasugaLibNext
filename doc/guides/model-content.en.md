# Content Integration: Block and Item Model Resources

This page answers one question: **when a block or item is drawn by this project's render pipeline, how should `blockstate` and the vanilla `models/item/*.json` be written?**

API signatures live in [ItemModelRenderers](../reference/item-model-renderers.md); binding a world block to a state machine/model is covered by [FSM](../reference/fsm.md).

## 1. Pick the path first

| Content | World rendering | Item rendering |
| --- | --- | --- |
| Block (project pipeline) | Block entity rendering (`RenderShape.ENTITYBLOCK_ANIMATED`) | `ItemModelRenderer` (BEWLR) |
| Item (project pipeline) | — | same as above |
| Vanilla static block / item | Vanilla baked model | Vanilla baked model |

Only the first case needs a `blockstate`; both project-pipeline cases need the item model line below.

## 2. What `blockstate` is for

```json
// assets/<ns>/blockstates/<block_path>.json
{
  "variants": {
    "running=false": {"model": "<ns>:block/<placeholder>"},
    "running=true":  {"model": "<ns>:block/<placeholder>"}
  }
}
```

Rules:

1. **The file name equals the block's registry path**, independent of Java class names.
2. **Variant keys must cover every property combination.** A block without properties uses `""`; `n` boolean properties need `2^n` keys. Missing keys log `Unable to load variant` and fall back to a missing model.
3. Under `ENTITYBLOCK_ANIMATED` it does **not** participate in world drawing, but it still decides:
   - the **break/hit particle texture** (taken from the referenced model's `particle`);
   - resource bake validation (a missing variant/model logs an error);
   - the world appearance if `getRenderShape` is ever switched back to `MODEL`.
4. The referenced model must be a **vanilla-bakery-compatible JSON** (`parent`/`elements`/`textures`). `.bbmodel`, `.geo.json`, `.glb` and `.mmd.zip` are read by this project's own loaders and **cannot** be referenced here.

Recommended: give the block a minimal dedicated placeholder (a plate or a cube) whose `particle` points at the real texture instead of borrowing an unrelated block's model.

## 3. Two ways to write the item model

| Form | Drawn by | Use |
| --- | --- | --- |
| `{"parent": "minecraft:item/generated"}` etc. | Vanilla baked model | a 2D icon |
| `{"parent": "minecraft:builtin/entity"}` | this project's BEWLR | GUI, hand and ground using the project model |

```json
// assets/<ns>/models/item/<item_path>.json
{"parent": "minecraft:builtin/entity"}
```

`builtin/entity` bakes into `BuiltInModel` (`isCustomRenderer() == true`), so `ItemRenderer` skips quads and calls `renderByItem`. **Without a registered BEWLR for that item, the item is completely invisible** — the most common "I wrote it correctly but see nothing" case.

## 4. Three steps for item rendering

```java
// 1) declare which model the item uses (any time before the client extension event)
ItemModelRenderers.register(item, MODEL_ID, null);   // third argument is an MMD entry name; null otherwise

// 2) write builtin/entity in the item model JSON (above)

// 3) register the client extension for that item
@EventBusSubscriber(modid = "your_mod", value = Dist.CLIENT)
final class ClientSetup {
    @SubscribeEvent
    static void onClientExtensions(RegisterClientExtensionsEvent event) {
        event.registerItem(ItemModelRenderers.clientExtensions(), item);
    }
}
```

The library registers no items automatically: item ids belong to the content.

## 5. Who owns the display transform

A vanilla block item's isometric look comes from the `display` section of `models/block/block.json`. `builtin/entity` receives `ItemTransforms.NO_TRANSFORMS`, so **those transforms do not apply automatically**; `ItemModelRenderer` ships the same table:

| context | rotation (X,Y,Z) | translation | scale |
| --- | --- | --- | --- |
| `GUI` | 30, 225, 0 | 0 | 0.625 |
| `GROUND` | 0 | 0, 3, 0 | 0.25 |
| `FIXED` | 0 | 0 | 0.5 |
| `THIRD_PERSON_*` | 75, 45, 0 | 0, 2.5, 0 | 0.375 |
| `FIRST_PERSON_*` | 0, 45, 0 | 0 | 0.4 |
| `HEAD` / `NONE` | no transform | | |

Two details:

- `ItemRenderer` already applied `translate(-0.5)` for the vanilla `[0,1]` model space. This project's models are **origin-centred**, so the BEWLR cancels it with `translate(0.5)` before applying the table.
- The GUI `PoseStack` is flipped with `scale(16, -16, 16)`, so screen-down is **-Y**. `ItemModelRenderer.GUI_DOWN_SHIFT` is the per-item GUI vertical nudge.

A custom BEWLR needs both steps, otherwise the icon renders unrotated and at full size.

## 6. World path and item path are separate

| | World | Item |
| --- | --- | --- |
| Driver | `ProxyInstance.tick` → `FsmAnimatedModel` / `McModelHandle` | `ItemModelRenderer.renderByItem` |
| Instance identity | `ProxyIdentity` (dimension+pos / UUID / external id) | `kasuga_lib:item/<ns>/<path>`, one per item |
| Scheduling | world pass (`ALWAYS` / `VANILLA_RENDERER`) | `VANILLA_RENDERER` only, widened for the draw |
| State machine | yes (server-authoritative + sync) | **no**: items have no tick channel, static preview only |

So an item icon shows the model's default pose; it does not follow the block's FSM state. Showing a specific state on the item requires applying fixed parameters to the `ModelInstance` at draw time (out of scope here).

## 7. Common failures

| Symptom | Cause |
| --- | --- |
| Checkerboard block/icon | the placeholder model's texture is missing (`textures/block/...`) |
| Item invisible | item model is `builtin/entity` but no `IClientItemExtensions` registered |
| Icon too large / facing the camera | a custom BEWLR skipped the display table or the `-0.5` cancellation |
| A model appears at the world origin | the item instance was not scheduled as `VANILLA_RENDERER` and the world pass drew it |
| Wrong break particles | the placeholder model's `particle` texture is wrong |
| Block turns checkerboard after adding a property | the blockstate does not cover the new property combination |

## 8. Reference implementation

The two fan blocks in `contentTesting` are the complete sample:

| File | Role |
| --- | --- |
| `FanBlock.java` | `RenderShape.ENTITYBLOCK_ANIMATED`, the `RUNNING` property, `ProxyTickers` |
| `FanBlockEntity.java` | `ProxyHost` + `Proxies.install`; machine/model come from constructor arguments |
| `FanItemClientExtensions.java` | `ItemModelRenderers.register` + `registerItem` for both items |
| `blockstates/example_fan*.json` | two `running` variants, placeholder model |
| `models/item/example_fan*.json` | `builtin/entity` |
