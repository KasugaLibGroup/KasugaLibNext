# Model render scheduling (Render Scheduling)

Interface, configuration, and call reference. For the principles see [render-scheduling.md](../explanation/render-scheduling.md).

For the public scheduling and buffer/backend contracts see [the UML rendering framework](uml-render-framework.md).
The `ModelRenderScheduler` in this document is an MC compatibility adapter; the policy and frame
marks are managed by `uml.framework.schedule`; `VANILLA_RENDERER` corresponds to the public
`HOST_RENDERER`.
Multi-camera visibility marks are cleared per view and animation sampling is deduplicated by the
shared frame token; see [the multi-camera and offscreen output document](multi-camera-output.md).

After a UML model is mounted to `mc_backend`, it is drawn by the phased world pipeline. This
document answers "when a model should render and when it should not", and how to integrate with
the scheduling mechanism of Minecraft's vanilla renderer.

## 0. Model handle: the split between the logic side and the render side

Upper-layer code operates on the same model in two halves, following MC's mental model:

```text
upper logic side ────► state machine (FSM / PoseDriver)    decides "what action to perform": state, transition, animation behavior
                     │ writes bone local transforms
                     ▼
              ModelInstance ◄───────────────────── the same instance
                     ▲ writes root transform / scheduling / anchors / physics
upper render side ───► McModelHandle                  decides "where it goes, whether it is visible, which schedule to use"
```

`McModelHandle` (`mc/api/McModelHandle`) is the only entry point on the render side:
resource-level outfit uses `ofAssembly` / `switchAssembly`, see [outfit resources and switching](minecraft-model-assembly.md).

```java
// Render side: the handle can be created before resources are published; the pose is stashed and takes effect on mount
McModelHandle handle = McModelHandle.of(modelLoc, null, instanceLoc, new Vec3(x, y, z));
handle.mount();                       // returns false while resources are unpublished; retry next tick
handle.setPos(entity.position());     // vanilla-style verb
handle.scheduleVanillaRenderer();     // visibility is decided by the vanilla renderer
handle.attachToAnchor("hand_r", (name, t) -> placeItem(t));  // held item
handle.enablePhysics(UpdateMode.RENDER_FRAME);
handle.hide() / show() / markRenderedThisFrame();
```

A state machine is attached through the handle in three forms:

```java
// ① Local data-driven: built from a definition id; the logic side ticks the bound object
var binding = handle.attachLocalStateMachine(Id.of("kasuga_lib", "door"));
if (binding != null) {
    binding.machine().mutableVars().set(OPEN, true);  // the logic side triggers the transition
    binding.tick();                                   // call from the BE/Entity game tick
}

// ② Local programmatic: take over an already built machine directly
var machine = StateMachine.builder(owner)...build();
handle.attachLocalStateMachine(machine);

// ③ Full host with server-authoritative sync (recommended for BE/Entity):
//    create the handle with ofFsm to align the instance identity; it hosts an FsmAnimatedModel
//    internally, the client-bound instance is adopted into the handle automatically,
//    and render scheduling still applies as usual.
McModelHandle handle = McModelHandle.ofFsm(modelLoc, null, pos.asLong(), null);
FsmAnimatedModel fsm = handle.attachSyncedStateMachine(this, pos.asLong(), fsmId);
fsm.tick(level, pos);        // forwarded from the BE ticker (server pushes sync / client drives the puppet)
```

If the instance was already created by an existing component (such as the `FsmAnimatedModel` inside
an `AnimationBlockEntity`), the render side adopts it with
`McModelHandle.ofExisting(modelLoc, modelName, instanceLoc)` instead of creating a second instance.

## 1. The three scheduling modes

`ModelRenderScheduler` maintains one policy per mounted instance, defaulting to `ALWAYS` (the
historical behavior: unconditional draw every frame). To switch modes:

```java
ModelRenderScheduler.setMode(instance, RenderScheduleMode.VANILLA_RENDERER);
```

| Mode | Decision maker | Typical host |
| --- | --- | --- |
| `ALWAYS` | The global pipeline draws every frame | Static decoration, effect-type content |
| `MANUAL` | Host code: `setVisible(instance, bool)` or `pipeline.setRendering(model, id, bool)` | Custom LOD/switch logic |
| `VANILLA_RENDERER` | Whether the vanilla renderer is called | Entities (EntityRenderer), block entities (BER) |

Beyond the modes there are two orthogonal gates:

- **Render distance**: `setMaxRenderDistance(instance, blocks)` culls by squared camera distance;
  0 means unlimited.
- **Frustum**: for each instance the pipeline runs a `Frustum.isVisible` test using "the vertex
  bounding sphere translated to the root position" (the bounding radius is computed and cached on
  first use).
  A culled instance **is not sampled for animation and does not upload vertices in that frame** — consistent with
  "the vanilla renderer is not called once a mob is beyond its distance". Physics stepping is
  independent of visibility
  (`MinecraftRagdollRuntime`) and does not freeze because of culling.

Unmounting an instance (`stopRendering`/`removeInstance`) automatically cleans up its scheduling state.

## 2. Integrating with the vanilla Entity renderer

The vanilla flow: `EntityRenderer.render` is called only after
`EntityRenderDispatcher.shouldRender` (tracking distance + frustum) passes. To make an entity model
inherit this scheduling:

1. Mount the model in `VANILLA_RENDERER` mode;
2. Provide a renderer extending `UmlModelEntityRenderer<T>` and register it for the entity type:

```java
public class MyEntityRenderer extends UmlModelEntityRenderer<MyEntity> {
    public MyEntityRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

    @Override protected ModelInstance resolveModel(MyEntity entity) {
        return entity.getKasugaModel();   // the instance held by the host
    }

    @Override protected ResourceLocation modelTexture(MyEntity entity) {
        return null; // queried only by the vanilla UI overlay; null → white fallback
    }
}
```

For entities culled by tracking range or outside the frustum, the vanilla code does not call their
renderer's `render()` → no mark → the pipeline skips sampling and drawing for that frame.

## 3. Integrating with the vanilla BlockEntity renderer

The vanilla flow: `BlockEntityRenderDispatcher.render` first checks
`BlockEntityRenderer.shouldRender` (view distance, 64 blocks by default), then the section frustum
outside it; `render()` is called only when both pass.

```java
BlockEntityRenderers.register(MY_BE_TYPE,
        context -> new UmlBlockEntityRenderer<>(context,
                be -> be.getKasugaRender().getModelInstance("main")));
```

Mount in `VANILLA_RENDERER` mode here as well. `getViewDistance` can be adjusted at construction time.

## 4. What about "blocks that go through the chunk dispatcher"

Modern vanilla bakes static block geometry straight into the mesh of a whole chunk section
(`SectionRenderDispatcher` builds in batches) and has no per-block renderer callback — a deliberate
batching optimization. The conclusion splits into two cases:

- **Purely static models**: they can still be baked into the chunk mesh (or use an ordinary BakedModel) and
  benefit from the vanilla section-level culling and batching; the price is that they can never move.
- **Dynamic/skeletal models**: they cannot be baked into a section mesh (vertices change every frame), so
  vanilla provides no dispatcher entry point for them. There are two proper paths:
  1. **Promote to a BlockEntity** + the BER adapter above — recommended, it fully reuses vanilla scheduling;
     this is also how vanilla itself handles "blocks that need to change every frame" (chest open/close
     animations, banners, etc. are all BEs).
  2. Keep the global pipeline mount and use `MANUAL` mode with your own visibility logic, or rely on the
     pipeline's built-in frustum/render-distance culling (`ALWAYS` mode gets these two gates as well).

In short: **the chunk baking path carries static content only; any dynamic model that needs
scheduling should land in an Entity / BlockEntity renderer or under explicit MANUAL control.**

## 5. Frame timing

```text
vanilla pass: entities + block entities
  └─ EntityRenderer.render / BER.render
       └─ ModelRenderScheduler.markRenderedThisFrame(instance)
kasuga pipelines:
  └─ AFTER_ENTITIES (priority 0 → 1)
       ├─ OPAQUE: no blend + depth write
       └─ MASK: alpha discard + depth write
  └─ AFTER_TRANSLUCENT_BLOCKS
       └─ BLEND: low alpha discard + no depth write + weighted-blended OIT
          (Iris/unsupported-target fallback: far-to-near sort)

  The first pass calls flipFrame() to lock in this frame's marks. The sampled-instance
  cache remains alive until BLEND, so a mixed model is sampled once even
  though its three material classes are submitted independently.
```

The mark buffer uses a double-buffer swap: marks written by the vanilla pass are locked in at the
start of the pipeline, next frame's marks go into the other buffer, and the two stages need no
synchronization barrier.
