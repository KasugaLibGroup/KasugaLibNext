# Minecraft Model Components v1

The Blockbench editing and preview plugin is maintained in the standalone repository [KasugaLib-BlockBenchPlugin](https://github.com/KasugaLibGroup/KasugaLib-BlockBenchPlugin); the data format and game-side loading behavior described here remain maintained in KasugaLibNext.

Adds a `kasuga_lib` object to existing model JSON files. This object does not replace the base model structure: native geometry, textures, hierarchy, display transforms, animations, and related data are still emitted verbatim by the original codecs. Removing `kasuga_lib` leaves the exact same base model; ordinary readers do not need to understand these components to load static geometry. Specific tools using strict validators that reject unknown fields need to be verified separately for acceptance.

| Model | Field location | Bone source |
|---|---|---|
| `.bbmodel` | Root `kasuga_lib` | Native outliner groups |
| Java block/item JSON | Root `kasuga_lib` | Extension `bones`; base `elements` unchanged |
| Bedrock geometry JSON | Each `minecraft:geometry[i].kasuga_lib` | Native bones + empty control bones from the extension |

```json
{
  "elements": [],
  "textures": {},
  "kasuga_lib": {
    "version": 1,
    "bones": [],
    "bindings": [],
    "emitters": []
  }
}
```

The example above shows placement only; complete editable projects, Java/Bedrock examples, and the JSON Schema live in `examples/model-components/`. Unknown fields inside the extension are preserved by the plugin. When the extension is absent, the legacy model loading path is used; unknown versions are preserved verbatim and blocked from editing in the plugin, and the game loader raises a version error.

## Bones and References

Each bone entry contains `id` (a stable project-local identifier; Blockbench uses UUIDs), `name` (the native bone name for this export), `parent` (parent ID or `null`), `pivot` (absolute rest pivot), and `components`. References are resolved by ID; during game loading they map to the `Bone` instances created for that load and are not cached across models. Names and IDs must be unique, and parent chains must be acyclic. Renaming a bone keeps references pointing to the original UUID.

All three extension targets use **Blockbench coordinates**: lengths in pixels, angles in degrees, and +Y up. The game entry converts pixels to 1/16 blocks and angles to radians. Bedrock native pivots negate X in the existing Bedrock loader; extension data is already in Blockbench coordinates and is not negated again. Rigid-body and particle-emitter `position` values are local offsets relative to their bound bone, and `rotation` values are local ZYX Euler angles. Java groups provide translation pivots only; native cube rotations continue to be expressed via `elements[].rotation`.

Java `bindings` map **zero-based indices** of the finally exported `elements` to bone IDs: `{"element":0,"bone":"arm-id"}`; `null` or an omitted binding follows the synthetic root. The mapping is generated in the codec's actual outliner traversal order rather than guessing by name, position, or `Cube.all` order. When inheriting a parent model's elements, its extension can be inherited; if a child model defines its own elements, the parent extension's index mappings are not inherited.

## Inverse Kinematics (IK)

IK is placed under `components.ik` on the effector bone:

```json
{
  "target": "target-id",
  "links": [
    {"bone": "upper-id"},
    {"bone": "lower-id", "min": [-90, -45, -30], "max": [90, 45, 30]}
  ],
  "iterations": 32,
  "tolerance": 0.01,
  "max_step": 45
}
```

`links` are ordered from base to tip and must be contiguous ancestors of the effector bone, excluding the effector itself. The target must lie outside the driven chain and may be a group without geometry. Tolerance is in pixels; `min` and `max` are XYZ rotation delta limits in degrees relative to the animated pose. Evaluation follows the game's order: animation → IK → physics → attachments. The editor uses CCD while the game uses its existing solver, so exact pose-for-pose parity is not guaranteed near singular poses.

## Diamond Closure and Active Inputs

Optional top-level `diamonds` close the endpoints of two IK branches onto a shared pin:

```json
"diamonds": [
  {"id": "valve-gear", "first": "branch-a-tip-id", "second": "branch-b-tip-id",
   "iterations": 64, "tolerance": 0.01}
]
```

`first` and `second` are **effector bone IDs** that define `components.ik`. Each branch may belong to at most one closure, and the two branches must be distinct. `iterations` is `1–256`, and `tolerance` is `0.000001–16` pixels. In the plugin's IK sidebar, expand "Advanced Settings" ("高级设置") and click "Diamond Closure Constraints…" ("菱形闭环约束…") to select branches and modify or remove closures, with Undo/Redo support. The midpoint of the two branch targets serves as the initial closure point, then the two fixed-length branches are solved alternately.

Crank rotation angles, slider strokes, and combination-lever cutoff positions may first be written actively by animations or a logic-side `PoseDriver`. IK then adjusts the permitted rotational degrees of freedom while preserving those active translations; do not simultaneously write rotation tracks to passive connecting rods. Setting both `min` and `max` of a pivot link to `[0,0,0]` locks its animated pose so only subsequent links rotate. Combination-lever position can come from an acceleration controller as an independent input instead of being back-solved from the closure.

The private QJ demo uses `qj.running` numeric keyframes: a 2-second wheel cycle, a cutoff input varying independently over 8 seconds, two diamond closures, and main-rod IK. Formulas in the original project are sampled into numeric tracks in the editor before export; the current game `.bbmodel` loader does not evaluate Molang formulas. Endpoints and visible geometry of this model must be compared through external fixtures; generic CCD and the game solver still do not guarantee element-by-element parity for arbitrary linkages.

## Rigid Bodies and Joints

At most one `components.physics` per bone:

```json
{
  "shape": "capsule", "mode": "rotation",
  "size": [1, 4, 1], "position": [0, 4, 0], "rotation": [0, 0, 0],
  "mass": 1, "linear_damping": 0.2, "angular_damping": 0.2,
  "friction": 0.5, "restitution": 0,
  "collision_group": 0, "non_collision_mask": 0,
  "parent_body": "parent-bone-id",
  "joint": {"rotation_min": [-45, -45, -45], "rotation_max": [45, 45, 45], "rotation_spring": [0, 0, 0]},
  "stiffness": 80, "gravity": [0, -156.8, 0]
}
```

`shape` is `sphere`, `box`, or `capsule`: spheres take `size.x` as radius, boxes take half-extents along all three axes, and capsules take `size.x` as radius and `size.y` as cylinder length (total height `y + 2x`). `mode` is `kinematic` (follows animation), `dynamic` (writes back position and rotation), or `rotation` (writes back rotation only). The game uses the existing `SkeletonDynamics`/Box3D integration; `parent_body` specifies the connected parent rigid body, anchored at this bone's pivot. Joints use the spherical cone/twist approximation supported by the current backend and do not claim full 6-DOF limits.

`stiffness` and per-bone `gravity` apply only to Blockbench spring previews, with `gravity` in pixels/s². They are meant for inspecting secondary bone inertia and damping; exact collision and parent-joint response are governed by the game runtime, where gravity comes from the active physics scene settings.

## Lights

Blockbench's "Kasuga: Place Light" ("Kasuga：放置光源") action creates a native Locator. Lights are stored in an optional `lights` array at the same level as `emitters`:

```json
"lights": [{
  "id": "lamp-id", "name": "lamp", "bone": "tail-id",
  "position": [0, 16, 0], "rotation": [0, 0, 0],
  "type": "area", "enabled": true, "color": "#55aaff",
  "intensity": 12, "range": 256, "width": 32, "height": 16,
  "two_sided": false, "density": 0.08, "anisotropy": 0
}]
```

Types are `point`, `area`, and `volumetric`. Colors are sRGB HEX and are converted to linear RGB on load. Optional `temperature` accepts `1000–40000` K; when present it overrides `color`, and deleting that field restores the HEX color. `position` is relative to the bound bone, and `rotation` uses local ZYX degrees; an area light at zero rotation faces `+Z` with its rectangular surface in the XY plane. `range`, `width`, and `height` are in pixels (`16` = `1` block). Medium density is per block, and anisotropy ranges from `-0.9` to `0.9`.

Model instances automatically install the light module, sample bone transforms after the existing post-physics attachment update, and reuse `WorldLights`. Lights follow the model's root position and bone animation; unloading, resource reload, destruction, and world changes clean up their effect handles. Legacy models without `lights` keep their existing path. See [World-Space Lights](../guides/world-lights.md) for surface lighting and volumetric range, occlusion, and limitations.

## Particle Emitters

Particle emitters are zero-volume native Locators in the outliner that move with their parent bone and produce no triangles on export. Parameters are stored in the extension's `emitters` array:

```json
{
  "id": "emitter-id", "name": "exhaust", "bone": "tail-id",
  "position": [0, 8, 0], "rotation": [0, 0, 90],
  "enabled": true, "rate": 30, "max_particles": 256, "space": "world",
  "particle": {
    "texture": "minecraft:particle/flame",
    "lifetime": 1, "speed": 8, "spread": 15,
    "size": 1, "color": "#ff8844", "gravity": [0, -4, 0]
  }
}
```

`bone` may be `null` to indicate model root space. Emission points along the locator's local `+Y`, `spread` is the emission cone half-angle, speed and acceleration are in pixels/s and pixels/s², and `lifetime` is in seconds. `world` particles move independently after emission with acceleration in model world space; `local` particle coordinates and acceleration follow the emitter. Both the editor and the game use the specified color and texture ID; the game runtime updates on a fixed step and cleans up on model instance destruction, resource reload, and world changes.

Each emitter allows at most 4096 particles and an emission rate of at most 10000/s; fixed-step simulation caps catch-up when resuming from background stalls to prevent emitting a long backlog of particles at once. Saving, exporting, Undo, switching projects, and unloading all stop preview playback and release temporary resources; preview transforms are never written back into the model's authored bone rotations.
