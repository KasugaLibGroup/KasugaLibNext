# Skeleton Dynamics API Reference

This page covers the generic skeleton dynamics interfaces in `render-core`. Data conversion happens at the
model construction stage; at runtime, instances are controlled by IK chain name. Algorithms and ownership are documented in the [concept document](../explanation/skeleton-dynamics.md).
The underlying world, forces, collision, active ragdoll, and Minecraft integration are covered in [Physics API](physics.md);
format manifest/profile are covered in [MMD/glTF configuration](mmd-ragdoll.md).

## Types and Packages

| Type | Full package name | Purpose |
| --- | --- | --- |
| `Skeleton`, `SkeletonDynamics` | `lib.kasuga.rendering.models.uml.structure.skeleton` | Bones and shared dynamics definitions |
| `SkeletonDynamics.*` | same as above; nested types of `SkeletonDynamics` | Physics, IK, and closure data |
| `SkeletonDynamicsBuilder`, `SkeletonDynamicsReader<T>`, `SkeletonBuilder` | `lib.kasuga.rendering.models.uml.loaders` | Construction, validation, and external format extension |
| `SkeletonInstance`, `ModelInstance` | `lib.kasuga.rendering.models.uml.dynamic` | Per-instance control and pose evaluation |
| `CalikoIkSolver` | `lib.kasuga.rendering.models.uml.dynamic.ik` | Solver adaptation and diagnostic results |
| `IkTargetModule` | `lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler` | Per-tick external IK targets |
| `MmdSkeletonDynamicsReader` | `lib.kasuga.rendering.models.uml.typo.miku_miku_dance` | PMX/converted PMD data conversion |
| `GltfSkeletonDynamicsReader` | `lib.kasuga.rendering.models.uml.typo.gltf` | glTF node physics candidate conversion |

Unless declared standalone, the code blocks on this page use the following imports; `model` denotes an already loaded `Model`:

```java
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsBuilder;
import lib.kasuga.rendering.models.uml.loaders.SkeletonDynamicsReader;
import lib.kasuga.rendering.models.uml.structure.skeleton.Skeleton;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics;
import lib.kasuga.rendering.models.uml.structure.skeleton.SkeletonDynamics.*;
import org.joml.Vector3f;
import java.util.List;
```

## Lifecycle and Units

1. Load the Model; the `configureSkeleton(Skeleton)` hook of format metadata runs during Model construction.
2. Create a dynamics Builder from the Skeleton, append or override definitions, then `attach()`.
3. Create a ModelInstance; set targets, tick loop modules, and physics.
4. Evaluate through the instance's pose pipeline; on disposal, close the ModelInstance or detach physics.

Dynamics configuration should be completed before creating instances. The collections in shared definitions are immutable, and both vector constructor arguments and vector accessor results are copied,
but `Bone` is a structural reference, and its definition transform cannot be modified as an instance pose. Multiple instances each keep their own targets and solver corrections.

| Data | Unit/coordinates |
| --- | --- |
| `RigidBody.position`, `Joint.position`, shape sizes, and translation ranges | definition coordinates; the physics adaptation multiplies by `Physics.unitScale` |
| `RigidBody.rotation`, `Joint.rotation`, and rotation ranges | XYZ Euler, radians |
| IK angles, `maxRotationStep`, `Rotor`, `Hinge` | radians |
| IK `tolerance` | distance units in the current evaluation space; import/root scaling affects the actual corresponding scale |
| Targets of `setIkTarget`, `setFrameIkTarget`, `IkTargetModule` | world coordinates, `Vector3f`; with floating origin enabled, the double world anchor is subtracted at evaluation time |
| `SkeletonRagdoll.Body.position()` and ordinary physics queries | simulation-local coordinates; use the `worldPosition`/`*World` interfaces to obtain or supply world coordinates |

A float external target is already precision-limited by itself; floating origin does not restore world-coordinate fractions lost when the `Vector3f` was constructed.

## Skeleton and SkeletonDynamics

```java
SkeletonDynamics Skeleton.getDynamics();
void Skeleton.setDynamics(SkeletonDynamics dynamics);

new SkeletonDynamics(Physics physics, List<IkChain> ikChains,
                     List<DiamondConstraint> diamonds);
new SkeletonDynamics(Physics physics, List<IkChain> ikChains,
                     List<DiamondConstraint> diamonds, List<BonePoseConstraint> poseConstraints);
```

Skeleton uses `SkeletonDynamics.EMPTY` by default. It is usually mounted through a Builder after validation; direct construction and the setter
do not perform all bone ownership, chain hierarchy, joint reference, and closure validation.

| Accessor | Returns |
| --- | --- |
| `physics()` | the shared physics definition |
| `poseConstraints()` | unmodifiable map from target Bone to inheritance/fixed-axis constraints; empty by default with the three-argument constructor |
| `ikChains()` | IK chain list preserving definition order |
| `diamonds()` | diamond closure constraint list |
| `chainsByName()` | read-only `Map<String, IkChain>`; chain names must be unique |
| `ikChainsByBone()` | read-only `Map<Bone, List<IkChain>>`; reverse mapping from a driver bone to all chains that control it |

## SkeletonDynamicsBuilder

```java
new SkeletonDynamicsBuilder(Skeleton skeleton);
```

Construction copies the definitions already mounted on the Skeleton into the Builder; it does not automatically clear the original rig.

| Full method | Behavior |
| --- | --- |
| `Skeleton skeleton()` | current target skeleton |
| `Bone bone(String name)` | resolves a structural reference by bone name; unknown names throw `IllegalArgumentException` |
| `<T> SkeletonDynamicsBuilder read(T source, SkeletonDynamicsReader<? super T> reader)` | runs the Reader synchronously, returns this Builder |
| `SkeletonDynamicsBuilder physics(Physics definition)` | replaces the whole physics definition, preserving IK/closure |
| `SkeletonDynamicsBuilder physics(List<RigidBody> bodies, List<Joint> joints)` | replaces bodies/joints; unit scaling, no required profile, affine writeback, radius scale 1, no bind-pose followers |
| `SkeletonDynamicsBuilder ik(IkChain chain)` | adds a chain; the same chain name replaces the existing chain |
| `SkeletonDynamicsBuilder diamond(DiamondConstraint diamond)` | adds a constraint; the same constraint name replaces the existing constraint |
| `SkeletonDynamicsBuilder pose(BonePoseConstraint constraint)` | adds a bone pose constraint; the same target Bone replaces the existing constraint |
| `SkeletonDynamics build()` | validates and returns the definition; does not mount, create a solver, or create a native world |
| `SkeletonDynamics attach()` | calls `build()` first, then writes into the Skeleton and returns the mounted definition |

The structural validation performed by `build()`/`attach()` includes:

- Non-null body bones, bind-pose followers, and IK controller/effector/link all belong to the target Skeleton.
- A pose constraint's target and inheritance source both belong to this Skeleton; self-references and inheritance cycles are rejected.
- A joint's two rigid body indices are valid and different; the indices refer to `Physics.bodies()`, not `Bone.index`.
- The IK chain is non-empty, driver bones do not repeat, and each subsequent link/effector must be a descendant of the previous link; intermediate bones may be skipped.
- A diamond closure references two existing POSITION chains; the same chain cannot belong to multiple closures.
- Closure branches do not share driver bones, their endpoints differ, the bind positions of the branch bases are no farther apart than the closure tolerance,
  and one branch's driver bone cannot be an ancestor of the other base.

Illegal structures throw `IllegalArgumentException`. Null required arguments usually throw `NullPointerException`.

## Reader and Existing SkeletonBuilder

```java
@FunctionalInterface
public interface SkeletonDynamicsReader<T> {
    void read(T source, SkeletonDynamicsBuilder builder);
}
```

The Reader only fills generic definitions into the Builder; it does not `attach()` by itself. External formats may use any source type:

```java
Skeleton skeleton = model.getSkeleton();
SkeletonDynamicsReader<String> reader = (chainName, builder) -> builder.ik(
        new IkChain(chainName, builder.bone("hand-target"), builder.bone("hand"),
                List.of(new IkLink(builder.bone("upper-arm")),
                        new IkLink(builder.bone("forearm")))));

new SkeletonDynamicsBuilder(skeleton).read("left-arm", reader).attach();
```

The existing `SkeletonBuilder` provides:

```java
SkeletonBuilder dynamics(Consumer<SkeletonDynamicsBuilder> configure);
```

The callback runs after the skeleton's `build(...)` completes Bone/Anchor/Vertex binding, and automatically validates and mounts.
Repeated calls to `dynamics(...)` replace the configuration callback; `clear()` removes it as well. A custom `ModelData` may override
`void configureSkeleton(Skeleton skeleton)`, hooking up a Reader during Model construction.
The Model construction hook of format metadata runs after the SkeletonBuilder callback and may override the physics table or same-named chains;
configuration that needs to override format defaults should go after Model loading and before ModelInstance creation.

## Generic Bone Pose Constraints

```java
new TransformInheritance(Bone source, float weight, boolean translation, boolean rotation);
new BonePoseConstraint(Bone bone, TransformInheritance inheritance, Vector3f fixedAxis);
```

Both are public records under `SkeletonDynamics`; they do not require a BoneData type.
The inheritance weight must be finite and may be negative; at least one of translation or rotation must be enabled. The inheritance source provides the currently evaluated local pose delta,
translation is multiplied by weight, and rotation is slerped from the identity quaternion by weight; it does not inherit the source's bind pose or world position.
The target bone's original local pose applies inheritance first, then extracts the twist along fixedAxis, and then applies IK and physics overrides.
The fixed axis is in the target bone's local coordinates; the input vector is copied and normalized, and is rejected when its squared length is less than `1e-8`.
The physics override takes the final simulated pose as authoritative; this axis constraint does not replace physics joint limits.

`inheritance` or `fixedAxis` may be null, but not both.
`fixedAxis()` returns a copy; `hasFixedAxis()` queries whether an axis is defined, and `fixedAxis(Vector3f destination)`
copies into the caller's scratch when an axis is defined. The Builder validates skeleton ownership and inheritance cycles by bone reference.
Instances are evaluated in dependency order; cross-branch inheritance does not depend on hierarchy traversal order, and the update range is widened to cover affected vertices.

PMX/PMD bone inheritance and fixed axes are converted in `MmdSkeletonDynamicsReader.read`; when reading a PMX bone table alone,
you can also call `MmdSkeletonDynamicsReader.readBoneConstraints(builder)` and `readIk(builder)`.
Index references of format files exist only in the Reader layer; MC or external formats just fill these definitions through the same Builder.

## Physics Data

### Physics

```java
new Physics(List<RigidBody> bodies, List<Joint> joints, Vector3f unitScale,
            boolean profileRequired, boolean affineWriteback,
            float profileRadiusScale, Set<Bone> bindPoseFollowers);
```

| Parameter | Contract |
| --- | --- |
| `bodies`, `joints` | copied definition lists; profile/joints reference the list indices of bodies |
| `unitScale` | three finite positive components; used for rigid body position/size and joint translation |
| `profileRequired` | when true, this table cannot be enabled directly without a profile; used for candidate tables |
| `affineWriteback` | when true, computes the local physics transform inversely within the full affine parent hierarchy, preserving scale/shear |
| `profileRadiusScale` | finite positive number; radius scale of generated profile capsules |
| `bindPoseFollowers` | follower bones without a direct physics body; they can follow the bind-to-current affine delta of the nearest physics ancestor |

`Physics.EMPTY`: empty tables, `unitScale=(1,1,1)`, `profileRequired=false`,
`affineWriteback=true`, `profileRadiusScale=1`, empty follower set.

### RigidBody

```java
new RigidBody(String name, String alias, Bone bone, int collisionGroup,
              int nonCollisionMask, int shape, Vector3f size, Vector3f position,
              Vector3f rotation, float mass, float linearDamping,
              float angularDamping, float restitution, float friction, int mode);
```

| Parameter | Contract |
| --- | --- |
| `name`, `alias` | non-null display/auxiliary names; they are not required to be bone names; alias may be an empty string |
| `bone` | may be null, meaning a body not bound to a bone; when non-null it must belong to the Skeleton |
| `collisionGroup` | integer `0..63` |
| `nonCollisionMask` | integer filter mask; the physics adaptation preserves exclusion layer semantics |
| `shape` | `SPHERE=0`, `BOX=1`, `CAPSULE=2` |
| `size` | finite non-negative vector; spheres use the x radius, boxes use xyz half-extents, capsules use x radius / full height of the y cylinder segment |
| `position`, `rotation` | body position in definition coordinates and XYZ Euler orientation; both finite |
| `mass` | finite non-negative mass; 0 produces no dynamic inverse mass |
| `linearDamping`, `angularDamping` | finite non-negative damping |
| `restitution`, `friction` | finite non-negative material parameters |
| `mode` | the motion/writeback mode in the table below |

| Constant | Value | Runtime behavior |
| --- | ---: | --- |
| `KINEMATIC` | 0 | rigid body driven by animation targets |
| `DYNAMIC` | 1 | physics writes back the full pose |
| `DYNAMIC_ROTATION` | 2 | dynamic body; writes back only the bone rotation, preserving animation translation |

Definition construction allows non-negative sizes; when collision shapes are actually created, sphere/capsule radii and all three box half-extents must be positive.
Creating physics bodies may therefore reject degenerate shapes further.

### Joint

```java
new Joint(String name, String alias, int rigidBodyA, int rigidBodyB,
          Vector3f position, Vector3f rotation, Vector3f positionMin,
          Vector3f positionMax, Vector3f rotationMin, Vector3f rotationMax,
          Vector3f positionSpring, Vector3f rotationSpring);
```

`name`/`alias` are non-null, and all vectors are finite and copied. `rigidBodyA/B` must be valid and different during Builder validation.
`position/rotation` is the shared joint frame, `*Min/*Max` are the author limits, and `*Spring` are the author spring parameters.
The current model adapter maps this set of six-axis author parameters to a Box3D spherical cone/twist; translation is tightened to a fixed anchor.
Other native joints are created through the [Physics API](physics.md#221-通用关节类型) and are not selected by this record's type field.

### Physics Example Without Format Metadata

```java
var builder = new SkeletonDynamicsBuilder(model.getSkeleton());
var body = new RigidBody("torso-body", "", builder.bone("torso"),
        0, 0, RigidBody.BOX, new Vector3f(0.2f, 0.3f, 0.1f),
        new Vector3f(0, 1, 0), new Vector3f(),
        2f, 0.05f, 0.1f, 0f, 0.6f, RigidBody.DYNAMIC);
builder.physics(List.of(body), List.of()).attach();

var instance = new ModelInstance(model, null, null, null, null, null);
var ragdoll = instance.enablePhysics();
if (ragdoll != null) {
    ragdoll.setGravity(new Vector3f(0, -9.80665f, 0));
    // Called once per frame by the host; instances already wired into the Minecraft runtime are advanced by that runtime.
    instance.evaluatePhysicsFrame(0f, 1f / 60f);
}
// On lifecycle end: instance.detachPhysics() or instance.close().
```

The high-level `enablePhysics` returns null when the native library is unavailable; it throws `IllegalArgumentException` when the library is available but bodies are empty,
or when `profileRequired=true` and no profile is passed.
`Body.source()` returns this generic `RigidBody`, and `Joint.source()` returns this generic `Joint`.

## IK Data and Limits

### IkLink, RotationLimit, and IkJoint

```java
new IkLink(Bone bone);
new IkLink(Bone bone, RotationLimit limit);
new IkLink(Bone bone, RotationLimit limit, IkJoint joint);
new RotationLimit(Vector3f minimum, Vector3f maximum);
new Rotor(float angle);
new Hinge(Vector3f axis, Vector3f reference, float minimum, float maximum);
```

| Type | Parameters and behavior |
| --- | --- |
| `IkLink` | `bone` required; `limit`/`joint` may be null; the omitted fields of the two simplified constructors are null |
| `RotationLimit` | finite XYZ Euler lower/upper bounds, per-axis minimum ≤ maximum; applies to the IK local correction |
| `IkJoint` | sealed interface, permits `Rotor` and `Hinge` |
| `Rotor` | finite angle `0..π`; the first segment revolves around the uncorrected pose direction, later segments are relative to the previous segment's direction |
| `Hinge` | axis/reference are in the driver's uncorrected local frame; non-zero, automatically normalized, orthogonality error ≤ `1e-4`; angle in `[-π,π]` and minimum ≤ maximum |

Rotor/Hinge constrain position solving in Caliko; RotationLimit projects onto the local correction. When both are given, both restrictions
participate, but Euler box projection does not guarantee global convergence for complex constraints. Chains that have already reached the target do not choose another solution.

### IkChain

```java
new IkChain(String name, Bone controller, Bone effector, List<IkLink> links);
new IkChain(String name, Bone controller, Bone effector, List<IkLink> links,
            int iterations, float tolerance, float maxRotationStep,
            boolean replaceAuthoredRotation, TargetMode targetMode);
```

| Parameter | Simplified-construct default | Contract |
| --- | --- | --- |
| `name` | required | non-blank chain name; control and diagnostics address it by this name, and it is not required to equal the controller bone name |
| `controller` | required | default target source bone |
| `effector` | required | final effector bone |
| `links` | required | non-empty, from base to tip; the final link points to the effector |
| `iterations` | 32 | `1..256`; caps the adaptation iterations and the library's attempt count |
| `tolerance` | `1e-4f` | finite positive distance |
| `maxRotationStep` | π | finite non-negative value, the maximum angle of each direction-alignment increment; 0 limits that increment to zero, and subsequent Euler projection can still adjust the correction |
| `replaceAuthoredRotation` | false | true takes over the chain bones' authored local pose; the current implementation starts from the identity local transform and skips even authored translation/scale, while morph/grant/fixed-axis still participate afterwards |
| `targetMode` | `POSITION` | non-null; modes below |

`POSITION` uses the controller's current absolute position as the default target.
`DIRECTION` determines orientation from the bind bone-segment direction and the controller's rotation difference when there is no external target, ignoring its pure translation.
When a persistent or single-tick external target exists, it switches to position blending; this mode does not interpret the external Vector3f as a direction vector.

```java
var builder = new SkeletonDynamicsBuilder(model.getSkeleton());
var elbow = new Hinge(new Vector3f(0, 0, 1), new Vector3f(1, 0, 0),
        -(float) Math.PI / 2f, 0f);
builder.ik(new IkChain("left-arm", builder.bone("hand-target"), builder.bone("hand"),
        List.of(new IkLink(builder.bone("upper-arm")),
                new IkLink(builder.bone("forearm"), null, elbow)))).attach();
```

This hinge example assumes the forearm's bind direction is local +X and the bend axis is +Z; real assets need to fill in their own bone frame.

## Diamond Closure

```java
new DiamondConstraint(String name, String firstChain, String secondChain);
new DiamondConstraint(String name, String firstChain, String secondChain,
                      int iterations, float tolerance);
```

The simplified constructor uses `iterations=32` and `tolerance=1e-4f`. The name is non-blank, the two chain names are required and different,
iterations is `1..256`, and tolerance is a finite positive number. Branch requirements are validated by the Builder, see its structural validation list.

```java
var builder = new SkeletonDynamicsBuilder(model.getSkeleton());
var left = new IkChain("left-linkage", builder.bone("closure-target"), builder.bone("left-tip"),
        List.of(new IkLink(builder.bone("left-base")), new IkLink(builder.bone("left-mid"))));
var right = new IkChain("right-linkage", builder.bone("closure-target"), builder.bone("right-tip"),
        List.of(new IkLink(builder.bone("right-base")), new IkLink(builder.bone("right-mid"))));
builder.ik(left).ik(right)
        .diamond(new DiamondConstraint("linkage", left.name(), right.name()))
        .attach();
```

The two bases should coincide at the bind pose position and drive separate hierarchies; the two endpoints represent the same closure point.
The two controllers may be the same bone or have separate targets; solving starts from the midpoint of the two targets.
Disabling either chain pauses the closure; when branches belong to a closure they are not first solved as independent chains.
The constraint only guarantees position closure; it does not guarantee equal endpoint orientation, equal side lengths, or native mechanical feedback.

## SkeletonInstance Runtime Control

```java
var skeleton = instance.getSkeletonInstance();
```

In the table below, `name` always denotes an IK chain name. The existing parameter names `boneName`/`controllerBone` are historical.

| Method signature | Returns/Behavior |
| --- | --- |
| `boolean setIkEnabled(String name, boolean enabled)` | returns false for an undefined chain; on success requires re-evaluation |
| `boolean isIkEnabled(String name)` | reads the toggle, default true; not an existence check, unknown chains also return true |
| `void resetIkEnabled()` | clears overrides, restoring the default true |
| `boolean clearIkEnabled(String name)` | clears an individual chain's explicit toggle; returns true when state was removed |
| `boolean setIkTarget(String name, Vector3f worldTarget, float weight)` | persistent target; false for an undefined chain; copies the target and requires an update |
| `boolean clearIkTarget(String name)` | clears the persistent target; returns false when there is no target |
| `void clearIkTargets()` | clears all persistent targets |
| `boolean setFrameIkTarget(String name, Vector3f worldTarget, float weight)` | transient target for the current pose pipeline cycle; false for an undefined chain |
| `void clearFrameIkTargets()` | clears transient targets; ModelTickLoop calls it automatically at the start of a cycle |
| `void updateTransform()` | evaluates hierarchy, IK, and final hierarchy; manual hosts call it according to their own evaluation order |
| `void updateTransformAfterPhysics()` | rebuilds the hierarchy after writeback, preserving solved IK corrections without solving IK again |
| `Map<String, CalikoIkSolver.SolveResult> ikSolveResults()` | read-only diagnostic view of the most recent IK solve |

Targets must be non-null and finite, and weight must be finite and within `[0,1]`; otherwise the corresponding argument exception is thrown.
Blending runs from the controller's current position to the external position; weight is not "the strength of enabling IK".
Transient targets take precedence over persistent targets; unknown chains return false before the target value is validated.
Setting a target only marks the input as changed; it does not solve immediately. Afterwards run the instance's normal evaluation entry point.

```java
var skeleton = instance.getSkeletonInstance();
boolean accepted = skeleton.setIkTarget("left-arm", new Vector3f(1, 2, 0), 1f);
instance.updateImmediate();
var result = skeleton.ikSolveResults().get("left-arm");
```

The accessors of `SolveResult(float residual, boolean satisfied)` are `residual()` and `satisfied()`.
For an ordinary chain, residual is the distance from the actual endpoint to this target; for a closure, it is the larger of the two endpoint distance and the two base distance.
Closure branch results correspond to the most recent shared target and do not mean that the branch's original controller target was necessarily reached.
satisfied only expresses that the corresponding position residual is within tolerance; it is not a complete angular/dynamics acceptance.
Disabled or unrun chains have no result for that solve; the Map is a live read-only view that is cleared and updated on the next solve; copy it yourself if you need to keep a snapshot.

### Per-Tick Targets: IkTargetModule

```java
new IkTargetModule(String name, Vector3f worldTarget);
new IkTargetModule(String name, Vector3f worldTarget, float weight);
```

The two-argument constructor uses weight 1 and initial enabled true.
Accessors: `controllerBone()` (chain name), `target()` (copied vector), `weight()`, `enabled()`.
Control methods: `void setTarget(Vector3f)`, `void setTarget(Vector3f,float)`, `void setEnabled(boolean)`.
Setting the target preserves the enabled state; the one-argument setter preserves the current weight.

```java
var target = new lib.kasuga.rendering.models.uml.dynamic.tick_loop.handler.IkTargetModule(
        "left-arm", new Vector3f(1, 2, 0));
instance.getTickLoop().addPreIk("hand-target-input", target);
target.setTarget(new Vector3f(1.2f, 2, 0), 0.6f);
target.setEnabled(false);
```

The constructor only validates that the name is non-blank and the target values; it does not verify that the chain exists. Chain names are resolved only when the module is published to an instance at runtime.
`addPreIk` places the module before IK and after the default apply; transient targets are consumed by both IK and physics within the same cycle.
When you need to write pending transforms for the default apply to consume, use the tick pipeline's `addBefore(SLOT_APPLY, ...)`.

## Actual Default Behavior of Format Readers

| Reader | Read interface | Default mapping |
| --- | --- | --- |
| `MmdSkeletonDynamicsReader` | `void read(MmdModelData, SkeletonDynamicsBuilder)` | authored rigid body order, direct Bone references, valid joints, imported unitScale; no required profile, rigid writeback, profile radius scale 1 |
| same as above | `static void readIk(SkeletonDynamicsBuilder)` | converts only PMX IK already attached to bones; usable for skeletons without a full tail |
| same as above | `static void readBoneConstraints(SkeletonDynamicsBuilder)` | converts PMX inheritance/fixed axes into generic constraints; the full read calls it automatically |
| `GltfSkeletonDynamicsReader` | `void read(GltfModelData, SkeletonDynamicsBuilder)` | generates candidates in node order; requires an explicit profile, affine writeback, skin bone bind followers; does not guess/create IK automatically |

MMD uses the controller bone name as the chain name, and reverses chains from the file's tip-to-base to base-to-tip; positive iteration counts
are capped at 256, tolerance is `1e-4f`, the step angle takes the absolute value of the original limit, and authored local pose takeover is enabled.
Expressible single-axis Euler limits are converted to Hinge; other box limits keep RotationLimit.
DIRECTION is used when there is a single link, the controller/effector bind positions coincide, and the controller has a vector tail.
Chains without a valid effector/link or with a non-positive iteration count do not enter the definition. Invalid or self-connected PMX joints do not enter the simulatable table.

The external indices of glTF profiles continue to correspond to node order; MMD profiles correspond to the original rigid body order.
Both sources complete conversion at the Reader boundary, so at runtime `Body.source().bone()` directly yields a generic bone reference.

## Solver Adaptation for Custom Hosts

`CalikoIkSolver(Skeleton skeleton, PoseAccess pose)` provides `void solve()` and
`Map<String, SolveResult> results()`. It is usually managed by SkeletonInstance; when used directly, the host implements:

| PoseAccess method | Host responsibility |
| --- | --- |
| `Transform absolute(Bone bone)` | the evaluated absolute pose |
| `Transform correction(Bone bone)` | mutable per-instance local IK correction |
| `Vector3f target(IkChain chain)` | returns a new vector for this target; closures modify the returned value to solve for the shared target |
| `boolean enabled(String chain)` | chain toggle |
| `void refresh(Bone driver, Bone endpoint)` | after correction, synchronously rebuild the affected hierarchy; subsequent reads must see the latest pose |

The host is responsible for animation/physics evaluation order, correction cleanup, and thread ownership. The solver has mutable state and cannot solve the same instance concurrently.
The current library is the official Caliko 1.3.8 core; dependency notes and licenses are included under `modules/render-core/libs`.

## Source Entry Points

The classes for this page are all in the `render-core` module; see the "Types and Packages" table for package paths:

- `SkeletonDynamics`
- `SkeletonDynamicsBuilder`
- `SkeletonDynamicsReader`
- `SkeletonInstance`
- `CalikoIkSolver`
