# ItemModelRenderers / ItemModelRenderer

The item-side entry of the model system: maps an `Item` to a pipeline model and provides a `BlockEntityWithoutLevelRenderer` (BEWLR) so the GUI, hand and dropped items use this project's renderer.

Package: `lib.kasuga.rendering.models.mc.api.item`
Source: `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/api/item/`

Integration steps and JSON forms: [Content Integration: Block and Item Model Resources](../guides/model-content.md).

## ItemModelRenderers

A static registry plus a shared extension. Writes happen during client setup, reads on the render thread.

| Method | Signature | Notes |
| --- | --- | --- |
| `register` | `static void register(Item item, ResourceLocation model, @Nullable String modelName)` | Binds an item to a model. `modelName` is MMD-only; pass `null` otherwise. Last write wins. |
| `binding` | `static @Nullable Binding binding(Item item)` | The binding, or `null` when the item is not registered. |
| `items` | `static Set<Item> items()` | Currently registered items (read-only, for diagnostics). |
| `clientExtensions` | `static IClientItemExtensions clientExtensions()` | The shared extension to pass to `RegisterClientExtensionsEvent#registerItem`. |
| `renderer` | `static ItemModelRenderer renderer()` | The shared BEWLR, created on first use. |

```java
public record Binding(ResourceLocation model, @Nullable String modelName) {}
```

## ItemModelRenderer

`extends BlockEntityWithoutLevelRenderer`. `renderByItem` behaviour:

| Condition | Behaviour |
| --- | --- |
| the item is not registered in `ItemModelRenderers` | returns immediately, draws nothing |
| the model is not published yet | returns for this frame, retries next frame, no log spam |
| `PipelineRegistry.backend()` is `null` (not a client runtime) | returns |
| otherwise | resolves the item's `ModelInstance`, marks it schedule-visible, draws three passes via `MCBackend.renderInstance` |

Details:

- **Instance identity**: `kasuga_lib:item/<namespace>/<path>`, one per `Item`, not per `ItemStack`.
- **Scheduling**: the instance stays `VANILLA_RENDERER`; it is widened to `ALWAYS` for the draw and restored afterwards. This keeps the world pass from drawing it at the origin.
- **Sampling**: the sample mark is released on every draw, so GUI + hand + ground draws in the same frame each sample with the current `partialTick`.
- **Transform**: `ItemRenderer` already applied `translate(-0.5)` (vanilla `[0,1]` model space); the BEWLR cancels it with `translate(0.5)` and then applies the `block/block.json` display table (see guide section 5).
- **GUI nudge**: `private static final float GUI_DOWN_SHIFT = -0.5f`; negative is down (GUI Y is flipped), in the model space before the 0.625 scale, roughly 5 px at GUI scale 1.
- **Lifecycle**: instances live for the client's lifetime; there is no per-item destruction path. `McModelHandle.mount()` self-heals them after a resource reload.

## Interface with MCBackend

| Method | Notes |
| --- | --- |
| `boolean MCBackend#renderInstance(ModelInstance model, MCBackendContext context)` | Draws a single instance outside the world pass using the caller's `PoseStack`; returns whether the instance was found. |

The caller builds the `MCBackendContext`: with `camera` and `frustum` null the distance/frustum gates are skipped, the `poseStack` belongs to the caller, and `modelViewMatrix`/`projectionMatrix` come from `RenderSystem`.

## Failures and diagnostics

| Symptom | Where | Meaning |
| --- | --- | --- |
| Item invisible | no log | the item model is not `builtin/entity`, or `registerItem` was never called |
| Model appears late | `[KasugaModelPipelines] bound ...` | the resource just got published; normal lazy binding |
| Item permanently invisible | no log | check whether `ItemModelRenderers.binding(item)` is `null` |
