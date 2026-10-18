# Generic model posing and animation API

The runtime interface does not require the Model to come from any particular format, and `ModelData` / `BoneData` may be null.
For the principles see [model subsystem boundaries](../explanation/model-subsystems.md); for IK, inheritance, fixed axes and physics definitions see
[Skeleton Dynamics](skeleton-dynamics.md).

## Model and format integration

| Interface | Package | Behavior |
| --- | --- | --- |
| `AnimationLibrary Model.getAnimations()` | `lib.kasuga.rendering.models.uml.structure` | Named clip library shared by Model |
| `ModelPosing ModelInstance.getPosing()` | `lib.kasuga.rendering.models.uml.dynamic` | Lazily creates this instance's generic pose controller; obtaining it does not itself install a driver |
| `void ModelData.configureSkeleton(Skeleton)` | `lib.kasuga.rendering.models.uml.structure.data` | Converts skeleton capabilities when the Model is constructed; default is an empty implementation |
| `void ModelData.configureModel(Model)` | same as above | Integrates the generic subsystems after the Model's public fields are initialized; default is an empty implementation |

The glTF Converter registers the animations it loads into the clip library; tracks that were not loaded are not in the library.
glTF's `weights` track shares this entry point with TRS, and supports STEP, LINEAR and CUBIC_SPLINE.
`POSITION` / `NORMAL` morph targets are registered per node instance as `GltfAsset.MorphId(nodeIndex, targetIndex)`;
different nodes of the same mesh are controlled independently. The default weight is taken in order from `node.weights`, `mesh.weights`, or all zeros,
and is baked into the static geometry; the sampler outputs offsets relative to the default value, and the default shape is restored when playback stops.
Weights may be negative or greater than 1, and assembly and cross-fade preserve their linear meaning. Custom samplers can write finite signed weights with
`Pose.Builder.morphWeight(id, weight)`; `morph(id, value, factor)`
keeps its original normalized activation semantics.
The Blockbench Loader registers the animations inside the file into the same library. Unnamed clips in both formats use `animation_<file index>`.
VMD/VPD, generic AnimationClip or custom formats are integrated through sampler/data; there is no need to create a new model type.
Bone names, morph identities and material references must still match the target model; this interface does not automatically retarget skeletons with a different topology.

## AnimationLibrary

Package: `lib.kasuga.rendering.models.uml.dynamic.animation`.

```java
new AnimationLibrary();
<T> AnimationLibrary.Clip<T> register(String name, AnimationSampler<T> sampler, T data);
AnimationLibrary.Clip<?> get(String name);
boolean contains(String name);
Map<String, AnimationLibrary.Clip<?>> clips();

new AnimationLibrary.Clip<T>(String name, AnimationSampler<T> sampler, T data);
float AnimationLibrary.Clip.duration();
Pose AnimationLibrary.Clip.sample(float seconds);
Pose AnimationLibrary.Clip.sample(float seconds, Namespace namespace);
AnimationLibrary.Clip<Pose> AnimationLibrary.pose(Pose pose);
```

The name passed to `register` must be non-empty and must not be all whitespace; registering the same name replaces the existing binding; `get` returns null on a miss.
`clips()` returns an unmodifiable view. Register at build/load time, and create players only after registration is complete; registration is not a thread-safe operation.
sampler/data must be non-null; the integrating party guarantees the immutability of external data. The library does not copy the original animation, nor does it rewrite its interpolation mode.
Clip times and durations are in seconds, and the shared player checks whether the duration is finite when playback actually starts.
`AnimationLibrary.SAMPLER` is the shared clip adapter used by AnimationPlayer/FSM.
The FSM's formula Namespace is passed through to the original sampler; a named binding does not lose the formula context.
`pose` produces an unnamed, zero-duration constant clip.

```java
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.fsm.Pose;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdSampler;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vpd.VpdSampler;

// model is a Model from any source; motion / vpd are data already read by the corresponding Reader.
model.getAnimations().register("dance", new VmdSampler(), motion);
model.getAnimations().register("stance", VpdSampler.INSTANCE, vpd);
ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
instance.getPosing().play("dance", true);

// A static pose can also use the generic Pose directly.
instance.getPosing().pose(new Pose.Builder()
        .bone("head", new Transform(), lib.kasuga.rendering.models.uml.dynamic.fsm.ApplyMode.REPLACE)
        .ikEnabled("left-arm", false).build());
```

## ModelPosing

Package: `lib.kasuga.rendering.models.uml.dynamic`; implements `PoseDriver`.

| Method | Behavior |
| --- | --- |
| `new ModelPosing(ModelInstance instance)` | Usually use `instance.getPosing()`; does not check the model format |
| `boolean hasClip(String name)` | Queries the model clip library |
| `String currentClip()` | Current clip name; null for a static pose or after stopping |
| `boolean play(String name, boolean loop)` | Plays from the start and installs as the instance driver; returns false for a missing name and keeps the current driver |
| `boolean follow(String name, AnimationTimeline timeline)` | Follows an external timeline and installs a driver; does not advance the shared clock a second time |
| `void pose(Pose pose)` | Installs a driver and keeps sampling a constant pose; pose must not be null |
| `void stop()` | The next render sample restores the bone/morph/IK channels written by that driver to their defaults; material frames are kept |
| `void pause()`, `void resume()` | Pauses/resumes the playback clock |
| `void seek(float seconds)` | Seeks to a finite, non-negative number of seconds |
| `void setSpeed(float speed)` | Finite, non-negative multiplier; 0 freezes the clock |
| `boolean isPlaying()` | Whether the clock keeps advancing; a static pose is still sampled continuously |
| `float currentTime()` | Current clock seconds; not taken modulo when looping |
| `AnimationTimeline timeline()` | Current timeline, for other targets to follow |
| `void tick(float dt)` | Usually advanced by `ModelInstance.animate(dt)` |
| `void sample(float partialTick)` | Usually called by the instance's render sampling entry point |

Each instance holds its own player, timeline and PoseSink; a static pose and a named clip switch reuse the same sink,
so channels that disappear from the previous pose are cleared automatically. Playback control and tick run serially, and the render sample consumes the clock snapshot
published by the shared player. `stop`'s reset runs during the sampling phase and cannot replace sampling or instance updates.
The current PoseDriver is still a single slot; installing a ModelPosing replaces the current driver.
The shared drivers implement RebindablePoseDriver; for resource switching and clock preservation see [input migration](minecraft-model-assembly.md#驱动和输入迁移).
When animation layering is needed, use the FSM's `State.clip(AnimationLibrary.SAMPLER, clip, loop)` and `State.pose(Pose)`.

## Pose IK channels

Package: `lib.kasuga.rendering.models.uml.dynamic.fsm`.

```java
new Pose(Map<Object, Pose.Morph> morphs, Map<String, Pose.Bone> bones,
         Map<Object, Pose.Frame> frames, Map<String, Boolean> ikEnabled);
Map<String, Boolean> Pose.ikEnabled();
Pose.Builder Pose.Builder.ikEnabled(String chain, boolean enabled);
State<Owner> State.ikEnabled(String chain, boolean enabled);
boolean SkeletonInstance.clearIkEnabled(String chain);
```

The original three-parameter Pose constructor is kept, and the IK map defaults to empty. The keys of the map are IK chain names, not model format or file bone indices.
VmdSampler automatically writes the IK state of the property track into the Pose, so there is no need to write the skeleton separately after playback.
`pose.ik_enabled` in FSM JSON is an optional object from names to booleans, defaulting to `{}`.

A switch takes the earlier end while the cross-fade alpha is less than 0.5 and the later end otherwise; a one-sided channel keeps that side's value.
As with material frames, BASE/ADDITIVE use the last value and OVERRIDE takes precedence; BoneMask applies only to bone transforms and
does not mask IK chain switches. PoseSink ignores unknown chains; when a channel disappears it clears only the chains it wrote itself, and other manual switches are kept.
`clearIkEnabled` restores the chain's default enabled state and returns true when an explicit state was removed.

## Name migration

| Original interface | Current interface |
| --- | --- |
| `GltfAnimationPoseDriver` | `ModelInstance.getPosing()` / generic `ModelPosing` |
| `MmdRagdoll` | `lib.kasuga.rendering.models.uml.dynamic.physics.SkeletonRagdoll` |
| `MmdPhysicsScene` | `ModelPhysicsScene` in the same package |
| `MmdPhysicsClusterManager` | `ModelPhysicsClusterManager` in the same package |
| `SkeletonInstance.getPmxBones()` | Format Readers maintain their own file index mapping; the instance exposes only the generic Skeleton/Bone |

These format-specific runtime entry points have been removed. The parameters and capabilities of the physics interfaces are preserved; callers referencing the old types need to update imports
and recompile. glTF's STEP/LINEAR/CUBIC_SPLINE continues to be sampled exactly by GltfSampler.
