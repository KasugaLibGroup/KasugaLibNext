# Physics, IK, and ModelTickLoop API

Interface, configuration, and call reference. For the underlying principles see [physics.md](../explanation/physics.md).

Native physics in Kasuga modelling is handled by the official Box3D C17 engine. The Java layer only handles object mapping, fixed steps, Minecraft
collision data, animation targets, and bone writeback; there is no Java fallback integrator or contact solver.

The currently vendored version is pinned to the official Box3D `main` commit
`30c67b5e6d0a3a66f0f506c69ce9e9e0587e3b7c`. The source and the license are in
`modules/modelling/src/main/native/box3d` and `modules/modelling/THIRD_PARTY_NOTICES.md` respectively.

CMake and the Box3D native library are both optional capabilities. When the build machine cannot find CMake, Gradle emits a warning, skips the
Box3D JNI compile, and still produces a modelling package without native physics. At runtime the capability can be queried through
`NativeBox3D.available()`; when the native library is missing, the high-level `enablePhysics`/`attach` APIs return
`null`, the deployment and block-generation APIs return empty results, and the physics commands show the reason they are disabled, while the remaining model loading, rendering, and animation features are unaffected.

## 1. Per-Frame Evaluation Order

All procedural stages hang off the single ordered `ModelTickLoop` pipeline. The framework provides ordering through standard slots, and
modules such as physics and IK implement only their own internal logic:

```text
kasuga:apply -> [user pre-IK modules] -> kasuga:ik -> [user post-IK modules]
  -> kasuga:physics -> [user post-physics modules] -> kasuga:anchor
```

The complete data flow within one frame for a model with physics:

```text
PoseDriver.sample(partialTick)
  -> SkeletonApplyModule (flush pending transforms)
  -> pre-IK modules (write transient IK targets; modules that need pending transforms flushed go before apply)
  -> IkModule (hierarchy evaluation + generic Caliko IK; skipped while physics takes over the pose)
  -> post-IK modules (read the same-frame IK results, submit controller forces)
  -> RagdollModule (Box3D fixed steps + bone writeback)
  -> post-physics modules (observe the final pose)
  -> AnchorModule (recompute the world transform of the display child anchor)
  -> renderer upload
```

`MinecraftRagdollRuntime` samples the animation first and then advances physics in `RenderFrame.Pre`; the backend's later `sample` consumes the
mark already prepared for this frame and does not overwrite the physics results with the animation again. Animation/IK targets are evaluated and cached by input mutation epoch, so
when the input stays the same the fixed steps reuse the same animation target; the native force accumulation and fixed-step execution are still managed by the world.

In manual mode you can call:

```java
instance.animate(gameTickSeconds);
instance.sample(partialTick);
instance.simulatePhysics(frameSeconds);   // goes through tickLoop.tick(dt) internally
instance.tick(frameSeconds);              // advances the whole pipeline directly
```

Use this when animation sampling and physics advancement must happen in one call:

```java
instance.evaluatePhysicsFrame(partialTick, frameSeconds);
```

## 2. Generic Rigid Body API

Main package:

```text
lib.kasuga.rendering.models.uml.dynamic.physics.core
```

### 2.1 Shape and Body

`BodyShape` is a body-local sealed API:

- `BodyShape.Sphere(center, radius)`
- `BodyShape.Box(center, rotation, halfExtents)`
- `BodyShape.Capsule(centerA, centerB, radius)`

`SimBody.collisionShapes()` can return any number of child shapes, so one native body can be a compound body.
The compatibility fields `shape()` and `shapeSizeRef()` are still mapped automatically to one centered primitive.

`GenericRigidBody` is the default implementation for non-PMX content:

```java
GenericRigidBody sphere = GenericRigidBody.sphere(0.3f, 2f)
        .at(0f, 4f, 0f)
        .friction(0.7f)
        .restitution(0.1f)
        .damping(0.05f, 0.1f)
        .filter(2, 1 << 5);

GenericRigidBody compound = GenericRigidBody.compound(List.of(
        new BodyShape.Box(new Vector3f(-0.5f, 0f, 0f), new Vector3f(0.25f)),
        new BodyShape.Box(new Vector3f( 0.5f, 0f, 0f), new Vector3f(0.25f))
), 4f);

GenericRigidBody movingPlatform = GenericRigidBody.kinematic(List.of(
        new BodyShape.Box(new Vector3f(1f, 0.1f, 1f))
));
```

Runtime state methods include `position()`, `rotation()`, `teleport(...)`, `setLinearVelocity(...)`, and
`setAngularVelocity(...)`. The `*Ref()` methods on `SimBody` return internal mutable storage and are meant only for high-frequency adaptation and state synchronization.

### 2.2 World Lifecycle and Fixed Step

```java
try (RigidBodyWorld world = new RigidBodyWorld(
        List.of(sphere, compound), List.of(), RigidBodyWorld.DEFAULT_SUBSTEP_COUNT)) {
    world.setSimulationHertz(120f);
    world.setGravity(new Vector3f(0f, -9.80665f, 0f));
    world.addGroundPlane(0f, 0.8f, 0f);
    world.step(deltaSeconds, RigidBodyWorld.KinematicDriver.none());
}
```

Object and joint management:

- `add(SimBody)` / `remove(SimBody)`
- `add(BallJoint)` / `remove(BallJoint)` (the ball joints PMX/gltf ragdolls use)
- `add(PhysicsJoint)` / `remove(PhysicsJoint)` (weld/distance/revolute/prismatic, see 2.2.1)
- `bodies()` / `joints()` / `physicsJoints()`
- `excludePair(bodyA, bodyB)`
- `close()`

When a body is removed, both kinds of joint attached to it are destroyed automatically.

### 2.2.1 Generic Joint Types

The four common Box3D joints have been ported in full through JNI (all constraint solving happens inside native code):

```java
// Weld: rigidly bonds two props (0 hertz = hardest), softness adjustable
WeldJoint weld = WeldJoint.atCurrentPose(blockA, blockB).build();
world.add(weld);

// Distance: rope/spring/winch, supports spring, length limits, and motor
DistanceJoint rope = DistanceJoint.between(anchor, payload)
        .length(1f)
        .spring(true, 3f, 0.9f)
        .limits(true, 0.5f, 1.5f)
        .motor(true, 1f, 50f)      // winch reeling in the rope
        .build();

// Revolute: hinge (door, lid, windmill), can carry angle limits and a motor
RevoluteJoint hinge = RevoluteJoint.between(frame, door)
        .hingeAxis(new Vector3f(0, 1, 0))
        .limits(true, -(float) Math.PI * 0.25f, (float) Math.PI * 0.25f)
        .motor(true, -6f, 40f)
        .build();

// Prismatic: slide rail (piston, drawer), can carry travel limits and a motor
PrismaticJoint piston = PrismaticJoint.between(rail, slider)
        .slideAxis(new Vector3f(1, 0, 0))
        .limits(true, -1.2f, 1.2f)
        .motor(true, 2f, 200f)
        .build();
```

Runtime tuning goes through each joint's setters (`setLimits`, `setMotor`, `setSpring`, `setLength`);
calling a setter on a joint that is not bound to a world throws `IllegalStateException`.

`KinematicDriver.beginStep(world)` runs before every fixed step; `kinematicTarget(body)` returns the target
position and rotation of that same step. `lastFixedStepCount()`, `droppedSimulationTime()`, and `interpolationAlpha()` are used to diagnose the fixed step.

Main tuning parameters:

- `setSimulationHertz(10..1000)`
- `setSubstepCount(1..50)`
- `setMaxFixedStepsPerUpdate(1..1000)`
- `setConstraintTuning(hertz, dampingRatio)`
- `setSpeedLimits(linear, angular)`; the current Box3D only provides a world linear speed mapping
- `setRestitutionThreshold(value)`
- `setSleepingEnabled(boolean)` / `setSleepingThresholds(...)` / `wake()`
- `setContinuousCollisionEnabled(boolean)`
- `setCollisionsEnabled(boolean)`
- `setSelfCollisionsEnabled(boolean)`

`setSolverIterations` is a compatibility entry point for old configurations. Box3D does not expose an iteration count of the same name, so it only validates and stores the value.

### 2.3 Impulse, Force, and Body Control

All vectors are in world coordinates:

```java
world.applyImpulse(body, impulse);
world.applyImpulse(body, impulse, worldPoint);
world.applyAngularImpulse(body, angularImpulse);

world.applyForce(body, force);
world.applyForce(body, force, worldPoint);
world.applyTorque(body, torque);

world.setGravityScale(body, 0.25f);
float scale = world.gravityScale(body);
world.setAwake(body, true);
boolean awake = world.awake(body);
```

An impulse changes velocity immediately; force/torque is integrated by the next native Box3D step. The methods return `false` when the body does not belong to that world,
when the body is not dynamic, or when an input is not a finite number. A negative gravity scale inverts that body's gravity.

### 2.4 Collision, Contact, and Query

Static environment entry points:

- `addPlaneCollider(normal, offset, friction, restitution)`
- `addGroundPlane(y, friction, restitution)`
- `addStaticBoxCollider(minimum, maximum, friction, restitution)`
- `addEnvironmentMesh(friction, restitution)`
- `setCollisionEnvironment(CollisionEnvironment)`

`StaticEnvironmentMesh` incrementally synchronizes Minecraft terrain through `putCell/removeCell`; Java does not do the collision solving.

Queries:

```java
Optional<RayHit> hit = world.raycast(origin, direction, maximumDistance);
List<BodyContact> contacts = world.contacts(body);
```

`RayHit` returns the Java `SimBody`, the world hit point, and the distance. `BodyContact` fields:

| Field | Meaning |
| --- | --- |
| `body` | the body from the query's point of view |
| `other` | the other Java body; empty for native static terrain |
| `point` | world contact point |
| `normal` | unit normal pointing from `other` to `body` |
| `separation` | negative means penetration |
| `normalImpulse` | normal impulse of the current solver point |
| `totalNormalImpulse` | normal impulse including the accumulated warm start |
| `normalVelocity` | pre-solve normal velocity |
| `touching()` | the separation or the impulse shows a current contact |

Summary counts are available through `selfContactCount()` and `staticContactBodyCount()`.

### 2.5 Ray Drag

```java
world.setDragSettings(DragSettings.DEFAULT);
world.beginDrag(hit);                 // or beginDrag(body, worldPoint)
world.updateDragTarget(target, frameSeconds);
world.endDrag();
```

Drag creates a kinematic anchor and a spherical joint inside native Box3D; it does not teleport the body and does not run a Java stabilizer.
A world currently has only one drag target at a time.

## 3. PMX and glTF ragdoll API

```java
SkeletonRagdoll ragdoll = instance.enablePhysics();
// or generate only the body capsules registered in the profile
SkeletonRagdoll profiled = instance.enablePhysics(profile);
```

Queries:

- `bodies()` / `joints()`
- `body(definitionIndex)`: index into the generic bodies list; the MMD Reader keeps the original rigid body order, the glTF Reader keeps the node order
- `body(Bone)` / `body(boneName)`
- `animationTarget(body)`: the body target after this frame's animation + IK and before physics
- `raycast(...)`

`SkeletonRagdoll` forwards the generic impulse, force, torque, gravity, collision, drag, fixed-step, sleep, and
environment controls directly. `Body` additionally provides `position()`, `rotation()`, `shapeSize()`, velocity, `toWorldPoint(...)`,
`toLocalPoint(...)`, `teleport(...)`, and velocity setters.

Mode semantics:

- PMX mode 0: kinematic, follows the animated bones.
- PMX mode 1: dynamic, full physics writeback.
- PMX mode 2: dynamic, keeps the animated translation and writes back the physics rotation.
- profile ragdoll: both PMX and glTF can generate capsules and semantic masses from the shared UML bone segments; internal
  self-collision is off by default. PMX can keep the author-made skirt/hair rigid body chains through `include_secondary_bodies`; these
  secondary-motion rigid bodies keep the PMX-internal filter relationships in an independent collision group/mask namespace and attach to the main
  body one-way through a kinematic proxy, so they do not inherit the main body's negative group filters and do not drag the main body back. glTF must supply an explicit profile,
  and does not guess human topology from bone names.

For the configuration format, profile roles, and Minecraft deployment see [mmd-ragdoll.md](mmd-ragdoll.md).

MMD/glTF ragdolls use a per-instance double world anchor and origin-local float physics coordinates. The Minecraft block mesh,
rays, mouse drag, and entity attachment points all subtract that anchor first; rendering also uses a double camera-relative displacement. This avoids
magnifying small colliders, bone segments, and joint error to centimeters or even whole blocks anywhere from a hundred thousand blocks out to the
world border.

## 4. IK API

`SkeletonInstance` supports persistent external targets:

```java
skeleton.setIkTarget("右足ＩＫ", worldTarget, 1f);
skeleton.clearIkTarget("右足ＩＫ");
skeleton.clearIkTargets();

skeleton.setIkEnabled("右足ＩＫ", false);
skeleton.resetIkEnabled();
```

`weight` ranges over `[0,1]` and blends between the chain controller's position and the external world target. The name denotes a generic IK chain name, and
an undefined chain returns `false`. For the full construction, limits, closure, and diagnostics see [Skeleton Dynamics API](skeleton-dynamics.md).

`setFrameIkTarget(...)` and `clearFrameIkTargets()` are the tick loop's transient entry points. `ModelTickLoop.tick`
clears them at the start of every evaluation; the pre-IK modules write them again, and IK and physics then consume them together within that same evaluation. Ordinary callers should usually use
`IkTargetModule` instead of managing the lifecycle directly.

## 5. ModelTickLoop Modules

Every `ModelInstance` carries its own `ModelTickLoop` pipeline (`instance.getTickLoop()`), with four built-in modules installed by default:

| Slot | Module | Responsibility |
| --- | --- | --- |
| `kasuga:apply` | `SkeletonApplyModule` | flushes pending transforms into the skeleton; the identity slot skips, and does not overwrite bones the animation already wrote |
| `kasuga:ik` | `IkModule` | hierarchy evaluation + generic Caliko IK; when physics is enabled physics takes over the pose and this slot skips automatically |
| `kasuga:physics` | `RagdollModule` | advances the SkeletonRagdoll (Box3D) and writes back; no-op when physics is not enabled |
| `kasuga:anchor` | `AnchorModule` | recomputes the anchor world transform and notifies the display children |

User modules attach between the standard slots with typed helpers, and ordering is provided entirely by the pipeline:

```java
ModelTickLoop loop = instance.getTickLoop();
loop.addPreIk("my-target", module);      // by default after apply, before IK
loop.addPostIk("my-controller", module); // after IK, before physics
loop.addPostPhysics("my-observer", module); // after physics, before anchor
loop.module(ModelTickLoop.SLOT_ANCHOR, AnchorModule.class); // fetch a built-in module instance
```

When you need to write pending transforms and have the default apply consume them, use
`loop.getPipeline().addBefore(ModelTickLoop.SLOT_APPLY, "my-pose", module)`.
You can also use `addFirst/addLast/addBefore/addAfter/remove` through `loop.getPipeline()` to insert anywhere.
`tick()` clears the transient IK targets at the start, so their lifetime is exactly one tick.

### 5.1 `IkTargetModule`

```java
IkTargetModule hand = new IkTargetModule("右手ＩＫ", new Vector3f(1f, 2f, 0f), 1f);
instance.getTickLoop().addPreIk("hand", hand);

hand.setTarget(new Vector3f(1.2f, 2f, 0f));
hand.setTarget(target, 0.6f);
hand.setEnabled(false);
```

It writes the transient world target only in the pre-IK stage, and does not modify authored animation data.

### 5.2 `ActiveRagdollModule`

This controller pulls dynamic bodies toward the same-frame animation/IK body targets in the post-IK stage: it first calls
`SkeletonRagdoll.evaluateAnimationTarget()` to refresh the kinematic targets, and then computes the PD spring force/torque;
integration, collision, joints, and sleep are still all executed by Box3D in the physics stage.

Control all dynamic bodies:

```java
ActiveRagdollModule active = new ActiveRagdollModule(
        5f,    // frequency Hz
        1f,    // damping ratio
        800f,  // maximum force
        250f   // maximum torque
);
instance.getTickLoop().addPostIk("active-ragdoll", active);
```

Control only the bodies of the given bones:

```java
var upperBody = new ActiveRagdollModule(
        List.of("上半身", "首", "頭"), 6f, 1f, 600f, 180f, 0.7f);
```

At runtime you can use `setWeight`, `setSettings`, and `setEnabled`. An empty bone set means all dynamic bodies.

`IkTargetModule` and `ActiveRagdollModule` can be used together: the former modifies the targets first, Caliko solves the generic IK chains, and the latter then
makes the Box3D bodies follow the post-IK pose. This is the recommended combination of animation, IK, and active physics.

### 5.3 `AnchorModule` and Display Children

`Anchor` in the model library (`uml.structure.skeleton.Anchor`) is a display-child anchor bound to a skinned bone,
for example the sword a player holds. `SkeletonInstance.anchorTransform(name)` blends the bound bone's bind→current delta with the same rules as BDEF
skinning to obtain the anchor world transform:

```java
// hand the hand anchor's world transform to the host rendering logic every frame
instance.attachToAnchor("hand_r", worldTransform -> {
    if (worldTransform == null) { /* anchor is invalid, hide the held item */ return; }
    placeHeldItem(worldTransform.getPosition(), worldTransform.getRotation());
});
instance.detachFromAnchor("hand_r", attachment);
```

`AnchorModule` pushes these transforms in the last stage of every pipeline (`kasuga:anchor`), so an anchor always follows the final pose
(including IK and physics). You can also call `instance.anchorTransform(name)` directly to query it immediately.

## 6. glTF Animation Compatibility

`.glb` and `.gltf` are registered as built-in resource pipelines. The parser API is in
`lib.kasuga.rendering.models.uml.typo.gltf`:

```java
GltfAsset asset = GltfLoader.load(path);                    // does not read animation tracks
GltfAsset animated = GltfLoader.load(path, Set.of("walk"));
GltfAsset all = GltfLoader.loadAllAnimations(path);
Model model = GltfModelConverter.convert(all);
```

`GltfModelConverter` merges all glTF nodes into one shared UML Skeleton and remaps every skin's `JOINTS_0` to
global Bones, so the existing CPU/GPU skinning and tick loop modules can be used directly. The Minecraft resource pipeline places embedded
base-color images into the existing texture atlas.

```java
ModelInstance instance = ...;
var animation = instance.getPosing();
animation.play("walk", true);
```

For the generic entry point see [ModelPosing](model-posing.md); the glTF sampler supports translation/rotation/scale/weights, STEP, LINEAR, and CUBIC_SPLINE. Resource-pack streaming of `.gltf` supports only
embedded/data URIs; when you need external `.bin` or image URIs use the `Path` API, or package the asset as `.glb`.

In Minecraft you can place a `<model>.gltf.json` manifest next to the model:

```json
{
  "model_scale": 1.3,
  "ragdoll": "example:ragdolls/character.json"
}
```

`model_scale` applies to the vertices, the bones, and the generated ragdoll at the same time; `ragdoll` points to a profile/simulation configuration. The deployment logic reads only the
manifest and does not choose a profile by file name or model name. The generic command is:

```text
/kasuga_ragdoll deploy example:models/character.glb
```

## 7. Minecraft Physics Block

`MinecraftBlockRigidBody` converts every voxel AABB of `BlockState#getCollisionShape` into one
body-local `BodyShape.Box`, instead of always using the full unit cube. Slabs, fences, pillars, and combined shapes therefore keep their real
collision gaps. The visual is still drawn by the vanilla block renderer.

`MinecraftBlockPhysics` uses one shared Box3D world:

- physics blocks collide with each other in real native collision;
- terrain is incrementally synchronized into static Box3D boxes by `MinecraftBlockRagdollEnvironment`;
- collision groups: props sit in group 1; the local player AABB is a group 15 dynamic proxy and, through a
  non-collision mask, excludes group 0 (terrain and other default bodies), so the solver noise between the player and the terrain is not
  passed on to a ground-hugging prop through the proxy. Spectators and dead players get no proxy;
- ground feel is synthesized from the contact writeback: the correction takes only the deepest contact's MTV (split by orientation — a top-face contact only lifts
  vertically and produces no horizontal drift; a side-face contact blocks walking just like a real wall), and the proxy's own solver displacement is not fed back to the player,
  so walking is not pushed back into "sliding" by the solver; while support holds, onGround is reset every frame and fallDistance is zeroed
  (vanilla clears that state every tick because no real block is underfoot), which makes ground friction, jumping, and fall-damage checks
  match real blocks;
- the default cap is `MAX_PROPS = 256`, and a prop is released automatically when it falls out of the level or the dimension changes;
- a block with an empty collision shape is not generated.

Debug commands:

```text
/kasuga_physics block [1-32]
/kasuga_physics status
/kasuga_physics clear
```

This is a client-side playground. To the local player it shows as a pushable, standable collision prop, but it is not a server-registered
Block/Entity and takes no part in server-authoritative collision, saving, chunk serialization, or multiplayer synchronization. If you need "like a
normal block" semantics that include those network and save behaviors, add a server-side entity and a synchronization protocol above it; do not treat the current local collision bridge as an anti-cheat security boundary.

## 8. Current Limitations

- Box3D has no built-in Minecraft fluid volume/buoyancy API; the old Java buoyancy model has been deleted.
- A PMX six-axis joint is currently mapped to a spherical joint; the rotation box is approximated as cone/twist, and the three translation axes are tightened to a fixed anchor.
- A world currently has only one drag constraint.
- glTF morph targets already convert POSITION / NORMAL and their weight animations; TANGENT, TEXCOORD / COLOR targets, a second set of skin weights, non-triangle primitives, and material extensions are not converted yet.
- The glTF parser stores the metallic/roughness factor, but the current Minecraft adapter only uploads base-color directly; Kasuga's
  stylized PBR conversion still uses its own rules and atlas baker from [pbr.md](pbr.md).

For the render pipeline, shader, and effect APIs see [effect-rendering.md](effect-rendering.md).
