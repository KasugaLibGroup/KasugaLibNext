# Blueprints and Nestable World Displays

`WorldDisplayTarget` is the client-side owner of a display subtree, bundling models, panels, and proxy controllers.
`ModelDisplayTarget` accepts any `MinecraftModelSource`, including vanilla structures, Litematic, Create, and UML models.
`WorldDisplayScene` is itself a `WorldDisplayTarget`, so scenes can be nested arbitrarily.

```java
var source = BlueprintImporters.load(client.level, Path.of("building.litematic"));
var building = new ModelDisplayTarget(source).fit(3).autoPanel();
var group = new WorldDisplayScene();
group.add(building);
var root = new WorldDisplayScene();
root.add(group).transform(new Matrix4f().rotateY(.3f));

// World mount takes ownership of root, including all nested model/UI resources.
var world = SceneModels.open(client.level);
world.add(root, new Vec3(10, 80, 10));
building.stacking().secondsPerLayer(.35).play();
// Pause/resume preserve progress. play() restarts; showAll() stops and assembles.
// world.close() closes the whole subtree and removes its world panels.
```

`fit()` scales according to the model bounds; `autoPanel()` places a panel beside the model that moves with it.
Pass absolute world coordinates to `SceneModels.Entry.position(Vec3)` rather than putting large world coordinates into float local matrices.
Child target and parent scene matrices apply together to models, panels, and picking. Do not attach one target to two parents; cycles and duplicate ownership are rejected.
Hiding a target only suppresses display; its logical clock keeps advancing.

`ModelDisplayPanel` can be placed in any `Screen` or `UIBackend`. It controls the same target directly—supporting replay, pause/resume, speed, stepping through non-empty layers, stack/slice mode, axis switching, and explode separation—and lists block counts below by block registry ID.
Counts ignore air and aggregate different block states of the same block. Long lists are scrollable; non-block models display `"No block data / 无方块数据"`.
`statistics()` returns a read-only map keyed by source-local layer coordinate and does not let animated display offsets affect material counts.

```java
client.setScreen(new WorldDisplayScreen(Component.literal("Blueprint"),
    root, building, false, false, returnAction));
```

`WorldDisplayScreen`'s `world` parameter selects see-through world inspection or the standalone 3D viewer; `ownsTarget` controls whether closing the screen closes the target.
Use `ownsTarget=false` for targets already mounted in the world, and `true` for a standalone viewer.
The viewer supports orbit, pan, zoom, reset, and click-to-inspect block info, sizing the panel to the window.
The screen only ticks roots that are not yet mounted; world-mounted targets are ticked by `SceneModels`.
At a lower level, `GuiModelPreview(target.borrowedSource())` only samples the source, and closing it only releases its own canvas.

Playback reuses `ProxySession` and `ManagedAnimationScope`: there is a single advance entry point per logical tick, and rendering never advances the clock.
Before the first tick, you can attach additional controllers via `target.controller(RuntimeController<Void>)`, such as the existing `ProxyMachineController` and a model FSM; controllers must use Scope checkpoints for rollback-safe state.
A target exposes the existing proxy commands, lifecycle, and revision APIs via `proxySession()`.

The local import UI is `BlueprintImportScreen(WorldMount, returnAction)`.
Open it in the dev client with `/kasuga_model_preview import` or via `"Import blueprint / 导入蓝图"` in the preview selector.
After entering any local path, choose either the 3D viewer or world + panel; the input file is read-only, and on failure the reason is shown while keeping the previous world display intact.
The default world placement distance is within `UIBackend`'s 5-block reach, and panels can also be operated via world-UI pointer mode.
In the Create factory inspection entry, `"Animation + counts / 分层统计"` opens the same shared control panel.

Built-in importers detect vanilla/Create structures, Litematic v5–v7, and Sponge/WorldEdit `.schem` v2–v3 by NBT structure (supporting both gzip and uncompressed NBT) without relying on filename extensions. Unknown formats are explicitly rejected; call `BlueprintImporters.register(id, matches, decoder)` to add formats. A decoder returns vanilla `StructureBlockInfo` entries, and layering, statistics, panels, and the viewer work automatically. Legacy numeric-ID `.schematic` or other formats require their own importer.
For the Sponge schema, see the [official v2/v3 specification](https://github.com/SpongePowered/Schematic-Specification/tree/master/versions).
Existing bounds apply: 64 MiB input NBT, 131,072 blocks, and axis span 4,096; missing modded blocks and newer data versions are never silently replaced with air.

Verification entry points:

```sh
./gradlew :modules:modelling:test --tests '*WorldDisplayTest' --tests '*LayeredModelSourceTest'
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=run/disposable-display-test \
  -PkasugaQuickPlayWorld=display-test -PkasugaTestWorldDisplay=true \
  -PkasugaWorldDisplayOutput=build/world-display-client
```

The real-client smoke test runs in an isolated test save and verifies file import, per-layer statistics, real panel buttons, unchanged RGBA while paused, pixel parity between the completed stack and the assembled blueprint, nested world-panel input, and close cleanup, writing screenshots and `report.json`.
