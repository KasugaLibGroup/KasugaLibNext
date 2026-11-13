# Minecraft structures and Create model previews

`lib.kasuga.rendering.models.mc.scene.MinecraftModelSource` is the common display contract for
Minecraft content and Kasuga models. Pass a source to `GuiModelPreview`, then use
`ModelPreviewWidget`, LDLib2's `ModelPreviewElement`, or a `UIBackend` world panel. Contents are
drawn to an independent transparent depth canvas and are never placed in the game world.

Construct, sample, render and close sources on the Minecraft render thread. `setView` and `setLayers`
publish immutable display parameters and may be called by UI threads.

```java
var mc = Minecraft.getInstance();
var source = MinecraftModelSources.structure(mc.level,
        ResourceLocation.parse("minecraft:end_city/base_floor"));
var preview = new GuiModelPreview(source);
var bounds = source.snapshot(LayerLayout.ASSEMBLED, 0).bounds();
mc.setScreen(new MinecraftModelPreviewScreen(Component.literal("End City"), preview,
        (int) Math.floor(bounds.minY()), (int) Math.ceil(bounds.maxY()) - 1));
```

The viewer provides left-drag rotation, middle-drag pan, wheel zoom, R to reset the view,
cumulative layers, isolated slices, showing all layers, and an explosion slider. View gestures
start inside the canvas and remain captured until release; gestures started on controls stay with
those controls. It closes its preview on removal. Other widget owners must call
`close()` themselves. Changing worlds also releases previews that have been rendered there.
A short left click inspects a block's name, registry ID and source coordinates; dragging rotates.

| Source | Factory |
|---|---|
| Bundled vanilla building / single-player data pack | `MinecraftModelSources.structure(level, ResourceLocation)` |
| Compressed structure-block NBT | `MinecraftModelSources.structure(level, Path)` |
| Loaded template | `MinecraftModelSources.structure(level, StructureTemplate)` |
| Litematica / Forgematica `.litematic` v5–v7 | `MinecraftModelSources.litematic(level, Path)` |
| Loaded client region | `MinecraftModelSources.capture(level, minimum, maximum)` |
| External projection data | `MinecraftModelSources.blocks(level, List<StructureBlockInfo>)` |
| Create machine region | `CreateModelSources.machine(level, minimum, maximum)` |
| Create contraption entity | `CreateModelSources.contraption(AbstractContraptionEntity)` |
| Attached Create contraption | `CreateModelSources.contraption(Contraption)` uses Create's own `entity` backlink |
| Create schematic item / template | `CreateModelSources.schematic(level, ItemStack or StructureTemplate)` |
| Local Create structure NBT | `CreateModelSources.schematic(level, Path)` |

Create factories live in the optional `modules/create`, in `lib.kasuga.create.client.model`.
The common modelling module does not link Create classes. Assembled structures reuse
`ContraptionRenderInfo` and `SchematicRenderer`; actors use `MovementBehaviour.renderInContraption`.
Layered geometry and block entities use native renderers, with actors following their block's layer.
Private virtual levels select ordinary renderers independently of the game world's Flywheel instances.

Litematic import retains multiple regions, signed sizes, continuously packed palette indices and
block-entity NBT. Old Minecraft data uses the structure DataFixer; newer data and missing mod blocks
are rejected explicitly. Import factories throw on failure. Draw failures return `false` and set
`preview.failure()`.

```java
preview.setView(-.55f, .25f, 1, .1f, -.05f); // Radians, zoom, right 10% / up 5% of the viewport
preview.setView(-.55f, .25f, 1);            // Three-argument overload resets pan
preview.setLayers(LayerLayout.ASSEMBLED.range(0, 5));
preview.setLayers(LayerLayout.ASSEMBLED.range(5, 5));
preview.setLayers(LayerLayout.ASSEMBLED.separation(1.25));
preview.setLayers(LayerLayout.ASSEMBLED.axis(LayerLayout.Axis.X).separation(.75));
preview.setLayers(LayerLayout.ASSEMBLED);
```

Coordinates are source-local Minecraft blocks, with Y as the default layer axis. Missing layers
retain their spacing. Hiding a layer changes neither remaining offsets nor the full framing bounds.
Limits are 131072 blocks, 4096 layers, 4096 blocks per axis, and 64 MiB of decoded NBT. Sources do not
tick block entities, play sounds, send packets or change live entity poses.
Model appearance/connected textures always use the full original neighbor context; isolated layers
filter cut-face visibility while retaining stored block states. Create's cached schematic renderer
has a separate 1048576-cell volume bound; the actual block count limit remains unchanged.

Static block/fluid geometry is cached per assembled model or axis/layer. View changes and explosion
spacing update matrices; block entities, actors, contraption rotation and animated textures remain live.
Transparent quads retain the shared sort. New layouts build once; reload rebuilds geometry and
close/world changes release it. Assembled Create schematics retain native live directional shading.
Custom `LayeredModelSource` subclasses can opt into cached planning with an immutable `geometryKey()`;
replace the key when geometry changes. The default `null` preserves live per-frame planning.

```java
var combined = MinecraftModelSources.compose(
        new MinecraftModelSources.Placement(structure, new Matrix4f()),
        new MinecraftModelSources.Placement(
                MinecraftModelSources.model(() -> alreadyLoadedKasugaModel),
                new Matrix4f().translate(2, 1, 0).scale(.5f)));
var preview = new GuiModelPreview(combined);
```

Compositions own their children; do not share one source between owners. UML assets remain borrowed,
while the preview owns a private instance and GPU state. Third-party adapters can extend
`LayeredModelSource` and implement `renderSlice` using their own renderer.

```java
preview.setFrameProcessor(input -> {
    runMyPostProcess(input.color(), input.depthTexture(), myTarget);
    return new FrameTexture(myTarget.frameBufferId, myTarget.getColorTextureId(),
            myTarget.width, myTarget.height);
});
```

The callback runs on the render thread and can use `FullscreenPassRenderer` or an existing shader.
It owns its output target, which must match input dimensions. Do not sample and write the same
attachment. `currentFrame()` is borrowed until the next render, resize or close. Legacy
resource/Supplier constructors retain renderbuffer depth compatibility; the source constructor
provides a sampleable depth texture.

```java
var scene = SceneModels.open(mc.level);
scene.add(source, new Vec3(10, 70, 10))
        .layers(LayerLayout.ASSEMBLED.separation(.5))
        .transform(new Matrix4f().rotateY(.4f).scale(.75f));
// scene.close() releases sources and registration; changing worlds also closes it.
```

`SceneModels` draws before shared world post-processing. Use a separate source for another owner.
To follow a Create contraption, use `scene.add(source, CreateSceneHosts.contraption(entity))`;
see [Scenes and UI on moving structures](moving-scene-hosts.en.md) for shared model/UI placement.
Positions are world coordinates: the model stays fixed as players walk around it. `Entry.position`
moves it, `transform` sets local rotation/scale, and `visible(false)` hides it. Use the render thread.
Development examples live in `test.kasuga.modelling.demo.preview` and `test.kasuga.create.demo.preview`.
`/kasuga_model_preview` opens the UI/world selector; `/kasuga_create_gallery` opens the floating
Create rig with creative flight, and `/kasuga_create_gallery stop` restores the original player state.
See the [gallery example](../../modules/create/src/contentTesting/java/test/kasuga/create/demo/preview/README.md).

`preview.pick(u, v)` takes top-left viewport fractions and inspects the latest displayed snapshot.
`scene.pick(from, to, partialTick)` takes a world-space ray. Hits contain the source `BlockState`,
original block position, displayed hit location and source face. Native outline shapes follow layer,
explosion, composition and scene transforms. UML sources without block metadata return no hit.
`ModelBlockInfo.draw(...)` draws the reusable inspection card. Aim and left click in the gallery
to display the block information for five seconds without breaking blocks.
Press I for transparent world inspection controls: cumulative layers, single slices, all layers,
explosion spacing, whole-model scale and reset. Close with Done or Esc to keep flying around the
model with its current layout retained.
The `Rig / Windmill` button switches to a real windmill assembled and ticked by Create's native
bearing. Its `ControlledContraptionEntity` keeps driving rotation in assembled, isolated-layer and
exploded modes; the display adapter reads the entity without ticking or changing it.
With `-PkasugaPreviewSchematic=...`, click **Inspect** beside the factory entry for the same world
inspection panel, or use `/kasuga_create_gallery factory` or the panel's **Factory** button.
The factory retains layers, explosion, scale, reset, I to reopen controls and block information.

```bash
./gradlew :modules:modelling:modelUnitTest --tests 'lib.kasuga.rendering.models.mc.scene.*'
./gradlew :modules:modelling:test --tests 'lib.kasuga.rendering.models.mc.scene.SchematicImportTest'
./gradlew :modules:create:compileJava :modules:create:compileContentTestingJava
./gradlew :modules:modelling:runClientHeadless -PkasugaTestMinecraftPreview=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=fixture
./gradlew :modules:create:runClientHeadless -PkasugaTestMinecraftPreview=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-create-client -PkasugaQuickPlayWorld=fixture
```

Client acceptance reads actual RGBA, drives layer/explosion controls and rotation/pan/zoom/reset
gestures, and checks transparency, sampleable depth, post-processing, resizing and immediate attachment retirement. Fixtures include
the bundled End City, negative-size litematic, section-boundary fluids, and optional Create
schematics/drill actors. Inspect `ksg_debug/minecraft-preview/report.json` in the isolated game
directory. Compilation and import tests do not establish pixel or interaction correctness.

For a visible client that stays open for manual testing:

```bash
./gradlew :modules:create:runClient -PkasugaMinecraftPreviewInteractive=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-create-client -PkasugaQuickPlayWorld=fixture
```

The source selector opens once after the world loads. Esc returns from a preview to the selector,
then back to the game. This development-only contentTesting mode has no automatic input, source
switching, timeout or exit.
