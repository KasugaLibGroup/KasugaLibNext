# AnimationTarget: Writing Animation Results into Business Targets

Playback determines what pose or motion exists at a given time. `AnimationTarget<O>` determines where that result is written. One state machine may drive both a character's bones and the position of a floating effect: the pose goes to the model's pose slot, while the position goes to a property or root slot. Both share one logical commit but have different output types and write rules.

The Target is a narrow boundary between business results and render objects. It knows nothing about clips, FSMs, or playback timing. It receives an immutable result that has already been evaluated. Adding a model, output slot, or third-party adapter therefore does not make animation controllers and renderers depend on each other.

## Why result-based targets matter

An older pattern makes every render writer hold an animation source and resample it on the render thread. That creates three common failures:

- Parts evaluated on the same tick may read different generations of state.
- A logical state machine and an animation source may advance time separately, causing doubled speed, reversal, or loop misalignment.
- Physics pre-sampling, backend sampling, and model update may write the same slot more than once.

Result-based targets divide logical processing from render sampling:

```text
Logical phase
  Scope commit
    -> TargetOutputEvaluator<O>.evaluate(...)
    -> TargetPublication<O>
    -> Target.publish(...)

Render phase
  ModelInstance.sample(RenderSampleContext)
    -> Target.sample(publication, context)
    -> interpolate O
    -> write the Target's exclusive model or property slot
```

The animation source is evaluated only during logical publication. A render frame chooses the display phase between previous and current committed results.

## Stable identity

```text
AnimationTargetRef
  channel: TargetChannel
  address: String
```

`channel` identifies the output contract, and `address` identifies an exclusive slot within the owning Scope. Built-in channels include:

| channel | Business output | Typical destination |
|---|---|---|
| `kasuga:model_pose` | Composed bones, morphs, material frames, IK, and related pose channels | Character, block model, robotic arm |
| `kasuga:part_root` | Root transform of one rigid part | Vehicle wheel group, bogie, separate car part |
| `kasuga:camera_pose` | Position, orientation, roll, and optical zoom for one camera | `CameraAnimationPlayer` |
| `kasuga:property` | Numeric value, switch, or platform property | Effect strength, extension field, third-party object |

Channels are extensible, but a third party must also supply an immutable output type, evaluator, continuity rule, and write adapter. One `(channel, address)` can be registered only once in a Scope. A configuration reload or duplicate initialization must not create two root owners for one part.

Stable identity does not mean a permanent Java object. After a resource reload, a Target can rebind to a fresh instance. The address stays stable, the destination changes, and the latest publication and playback progress survive.

## What a publication contains

```text
TargetPublication<O>
  ref
  revision
  Active(previousOutput, currentOutput, continuity)
  or Cleared(reason)
```

A publication is the result of one logical commit for one Target. It does not expose the complete playback tree. `Active` lets rendering interpolate between two committed results. `Cleared` explicitly removes an output so stale visuals do not remain.

Continuity is selected by the target publication factory:

| Situation | Continuity | Business behavior |
|---|---|---|
| Ordinary logical advancement | `INTERPOLATE` | Display smoothly between two committed results |
| Zero delta with a republished state | `HOLD_CURRENT` | Keep the current value without an artificial transition |
| First publication, seek, or a result that declares a discontinuity | `SNAP` | Adopt the new result directly without crossing unrelated time |
| Stop or inactive target | `Cleared` | Remove the output until a later activation |

`revision` identifies the batch, not time. Every publication in a Target batch has the same revision. A render sample captures one complete batch, so a newer logical step being published concurrently cannot enter that sample.

## Publication and sampling

`TargetRegistry` has two phases:

```text
publish:
  evaluate O in registration order
  create one publication for each Target
  finish the entire batch
  atomically replace the latest render batch

sample:
  capture one complete batch
  check the RenderFrameToken
  interpolate each result
  write its exclusive slot
```

Registration order gives deterministic publication and diagnostics. A failure while evaluating, publishing, or sampling one Target is isolated and logged; other Targets can still complete the step. A model write failure after commit does not roll back the logical revision.

Repeated sampling with the same model and `RenderFrameToken` is a no-op. This prevents physics pre-sampling and backend rendering from applying the same pose twice. Targets run in stable registration order; when one batch contains both root and pose, the composition root registers root first. The two slots do not clear each other.

## Current production targets

| Target | Logical result | Render write | Typical business use |
|---|---|---|---|
| `ModelPoseTarget` | `PoseOutput` | `ModelInstancePoseSink.applyPose(...)` | FSM and explicit model-pose targets |
| `ScopedPlayerPoseTarget` | `TimedPose` history | `ModelInstancePoseSink.applyPose(...)` | `AnimationPlayer` standalone/follow |
| `ScopedCameraPoseTarget` | `TimedPose` history | Produces `CameraPose` for the camera layer | `CameraAnimationPlayer` standalone/follow |
| `RigidPartBinding` | `RigidPartOutput` | Root of one `ModelInstance` | Multi-part vehicles, trains, machinery |

`ModelPoseTarget` uses `PoseOutputInterpolator` for bones, morphs, discrete material frames, and clearing. `RigidPartBinding` captures root, local motion, and local placement during the logical phase, then composes `root × local × motion` for a render frame and writes only root.
FSM formula tracks use the frozen `query.*` snapshot during each logical step, and render frames interpolate only the resulting Poses. Intermediate values of nonlinear formulas therefore use logical-step sampling as an approximation. An FSM step that crosses a clip loop boundary is marked discontinuous so the clip end is never interpolated back into its start.
`ScopedPlayerPoseTarget` and `ScopedCameraPoseTarget` retain the committed samples for the current
host interval and resolve them by unwrapped logical time. Both are private composition endpoints;
the public entries remain `AnimationPlayer` and `CameraAnimationPlayer`. A loop boundary publishes
both the end and start results.

Parent/child playback or frame-domain motion may contribute to a final value, but the Target receives only the final result. The playback graph expresses timing and sources; the Target expresses destination and write behavior.
A model group also uses `ModelGroupRenderPort` to inject its current frame input into the committed Target batch's `RenderSampleContext` under one lock, so root, tick motion, and frame motion cannot come from different publication generations.

## Lifecycle and rebinding

- A Target establishes its address at registration. Duplicate registration of the same address fails.
- A model rebuild prepares a fresh Target before switching the sink; failure must leave the old owner valid.
- `rebind` may replace the model instance or sink, but it does not reset publication, revision, or playback progress.
- `close()` is idempotent and releases model, sink, frame token, and other runtime references.
- Closing a Scope closes only Targets it owns and has registered. Playback instances do not own Target lifecycle.

These rules turn resource reloads, chunk unloads, and visual rebuilds into endpoint replacement instead of rebuilding the whole animation state.

## When to add a Target

Add a Target when:

- A result needs its own interpolation, such as position, color, weight, or camera parameter.
- Multiple playback instances may contribute to one final value.
- The destination is not an existing pose or root slot.
- The destination must rebind to another platform object while preserving logical playback state.

Do not put these responsibilities in a Target:

- Evaluating business conditions.
- Advancing a state machine, RootTimeline, or playback instance.
- Scanning time events and creating the next command.
- Reading another Target and recursively driving it.
- Resampling a source animation during render sampling.

Controllers and Scope own conditions and time. Playback owns timing and commands. Host/Proxy code owns resource lifecycle. Keeping those boundaries makes each Target stable, testable, and independently isolatable.

Implementation:

- [`AnimationTarget.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/scope/AnimationTarget.java)
- [`TargetRegistry.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/scope/TargetRegistry.java)
- [`TargetPublication.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/scope/TargetPublication.java)
- [`ModelPoseTarget.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/target/ModelPoseTarget.java)
- [`ScopedPlayerPoseTarget.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/models/uml/dynamic/animation/ScopedPlayerPoseTarget.java)
- [`ScopedCameraPoseTarget.java`](../../../modules/render-core/src/main/java/lib/kasuga/rendering/output/camera/ScopedCameraPoseTarget.java)
- [`RigidPartBinding.java`](../../../modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/proxies/RigidPartBinding.java)
