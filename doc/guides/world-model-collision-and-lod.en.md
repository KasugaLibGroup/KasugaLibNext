# Model Collision, Chunk Lifecycle, and Distant Rendering

## Animated Entity Collision

On the server, call `collisionModel(id)` on `AnimationBlockEntity`. For example, with `example:models/gate.bbmodel`, place the matching geometry at `data/example/models/gate.bbmodel` while the client loads the visible model from resource packs. The data-pack entry point supports Blockbench and standalone glTF/GLB, or can be loaded directly via `ServerCollisionModels.find(level, id)`. For other formats, wrap a loaded UML `Model` in `ServerCollisionModels.Asset` and attach it to a custom block entity via `MinecraftModelCollisions.attach`; custom hosts call `Handle.tick(deltaSeconds)` (or `publish()`) on server ticks and `close()` on removal.

At load time, vertices associated with each bone are transformed from the bind pose into bone-local space to record their six extrema. Vertices with multiple positive skin weights contribute to each associated bone's bounding box. On each server tick, the server transforms those boxes by the final bone matrices and exposes Minecraft world-axis-aligned collision shapes for entity movement; rotated boxes wrap the bounds of the oriented AABB. Collisions update on game ticks, and the server syncs the shapes to players tracking the chunk for local movement prediction.

## Player Departure and Return

When a chunk unloads, the host releases its model instance, animation bindings, and collision handles. The client also verifies whether the original host object is still present in the live chunk cache to catch cache evictions caused by shrinking render distance. When approaching again, the client receives the chunk and block entity and mounts the model in `onLoad` or on the first host tick; if resources are still publishing, subsequent ticks keep retrying. By default, models are culled by the global render pipeline. Models scheduled with `VANILLA_RENDERER` are marked by the vanilla block entity renderer callback and drawn after the block entity pass of the same frame.

- JVM unit tests cover host instance release, stale callbacks, first visible frame, and cross-chunk collision sync: `./gradlew :modules:modelling:modelUnitTest`.
- Dedicated-server GameTests use an armor stand to verify bind-pose collision, animated displacement collision, and removal cleanup: `./gradlew :modules:modelling:runGameTestServer`.
- The client test mounts the model and verifies collision in a temporary integrated world, teleports 512 blocks away until both sides unload, then approaches to 112 blocks and 48 blocks to verify client re-creation and restored collision: `./gradlew :modules:modelling:runClientHeadless -PkasugaTestModelLifecycle=true -PkasugaClientDirectory=/tmp/kasuga-model-lifecycle`.

## Voxy and Create

Distant rendering shares the main view's projection and depth with terrain. When Voxy is active, models take an isolated transparency path so Voxy's terrain pass stays intact. In standalone scenes, `SceneModels.distantRange(blocks)` extends the distant range for statically placed models (passing `0` restores the default range); placements that follow a host still expire with their host's lifecycle.

Combined compatibility tests used Minecraft 1.21.1, NeoForge 21.1.228, Sodium 0.6.13, Voxy NeoForge 0.6.0, and Forgified Fabric API 0.116.7+2.2.0 (which requires NeoForge 21.1.217 or newer).

Every 4 game ticks, the server syncs simplified block meshes for active Create contraptions within 512 blocks of a player (up to 64 per player), following the server's rotation and translation. The client continues using Create's renderer whenever normal entity rendering is available. Distant meshes start downsampling at 2 blocks per cell and step up in powers of two to at most 64 blocks per cell for large structures (capped at 2,048 cells per mesh); block entity rendering, moving-part effects, and contraption interaction remain on the near path. Distant sync never force-loads server chunks, and removes the client's distant mesh once the contraption entity unloads or is removed on the server. This feature can be toggled via the common config `enable_contraption_lod` (default `true`).

The Create client regression assembles a real windmill at a 4-chunk render distance, verifying image and depth changes at 320 blocks away, restored entity tracking on approach, and distant mesh removal: `./gradlew :modules:create:runClientHeadless -PkasugaTestContraptionLod=true -PkasugaClientDirectory=/tmp/kasuga-contraption-lod`. In a graphics environment with Voxy support, place Voxy and its dependencies in that directory's `mods/` folder and add `-Pneo_version=21.1.228 -PkasugaTestContraptionLodVoxy=true` to assert that the Voxy terrain renderer is active.
