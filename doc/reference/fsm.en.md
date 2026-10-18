# State Machine and Animation Clip JSON Reference

> Type: Reference (information-oriented, for lookup).
> Applies to: Minecraft 1.21.1, NeoForge 21.1.203, Parchment 2024.11.17, Java 21. The fields on this page only hold within this version range.
> Line numbers are locator hints only and may drift as the source changes; treat class names, method names, and test names as authoritative.
> Authoritative sources: element fields come from the codecs (`StateMachineDefinition.CODEC`, `AnimationClip.CODEC`, and their sub-codecs); wrappers and discovery rules come from `StateMachineDefinitionLoader` / `AnimationClipLoader` / `ReloadOrchestrator` / `FsmReloadHandler` / `FsmClipsReloadHandler`. Every rule cites its source; this page does not carry over the old conclusions from before the rewrite.
> Boundary: this page covers only **the element format, the file wrapper, and the reload orchestration of state machine definitions and animation clips**. For index files and registration-phase content files see [`data-driven/schema.md`](data-driven/schema.md); for Java API signatures see [`data-driven/README.md`](data-driven/README.md); for content-organization practice see [`data-driven/guide-content.md`](../guides/data-driven/guide-content.md).

## 0. Two Files, Three Kinds of Readers

The animation state machine (FSM, finite state machine) is a Mecanim-style layered state machine: multiple layers (state layers) run in parallel, and every tick composites the pose of each layer into the model. Both the FSM and the animation clips it plays (an animation clip is a segment of bone / morph / material-frame tracks sampled along a timeline) are defined in JSON.

The reload phase (the resource re-read triggered by `/reload`) has exactly **one orchestrator**: `ReloadOrchestrator` (data-driven module, package `lib.kasuga.registration.data_driven.reload`). It reads the **pack stack** (not the jar), so data packs can override these files. Types are not hard-coded into it: the `typeName()` of each registered `ReloadHandler` is a valid top-level key; the modelling side registers two handlers, corresponding to two top-level keys:

| Top-level key | handler (modelling) | Decoder it delegates to | Content |
|--------|-------------------|--------------|------|
| `state_machines` | `FsmReloadHandler` | `StateMachineDefinitionLoader.decodeFile` | State machine definitions |
| `animation_clips` | `FsmClipsReloadHandler` | `AnimationClipLoader.decodeFile` | Animation clip elements |

Both decoders (`StateMachineDefinitionLoader` / `AnimationClipLoader`) are **pure file-layer components** and stay in modelling; neither of them holds a reload listener or bucket-clearing logic of its own anymore. The two `ReloadHandler`s are one layer wrapped around them: they claim the top-level keys, declare the glob directories, write to buckets, and run the closing validation, and `FsmReloadHandlerRegistrar` registers them into `ReloadHandlerRegistry` at context startup in **fsm → clips** order. The whole reload cycle (clear buckets / discover / register / re-check) is owned by `ReloadOrchestrator`; the orchestrator itself does not know either name, `state_machines` or `animation_clips`.

## 1. File Locations and Discovery

| Content | Location | Discovery |
|------|------|----------|
| State machine | `data/<namespace>/state_machines/<path>.json`, **or** any path listed by `on_reload` | **Directory glob + index**, two entry points |
| Animation clip | Any path listed by `on_reload` (by convention placed under `animation_clips/`) | **Index only** |

- `state_machines/` has a directory glob: `FsmReloadHandler.globDirectories()` declares `StateMachineDefinitionLoader.PATH = "state_machines"` (`FsmReloadHandler.java:69-71`), and `ReloadOrchestrator.collectGlob` (`:348-367`) uses `ResourceManager.listResources` to list that namespace's `.json` files in resource-location order. **A file is read even if no index lists it**.
- `animation_clips/` has **no directory glob** (`AnimationClipLoader.java:29`; `FsmClipsReloadHandler` keeps `globDirectories()` at its default empty set). A clip file that no index lists is **never read**. Test: `AnimationClipReloadTest.animationClipsDirectoryIsNotGlobDiscovered`.
- A `state_machines/` file that the index lists is skipped by the glob entry point so it is read only once (`collectGlob`, `ReloadOrchestrator.java:348-367`; test `ReloadOrchestratorTest.globAndIndexListingTheSameFileReadItOnce`).

> A file path and a definition `id` **need not match**: the `id` is decided by the element's own `id` field (see §5.1, §6.1). A bare path (no colon) is given the default namespace `minecraft` by `Id.parse` (`Id.java:49-71`).

## 2. The `state_machines` File Wrapper

### 2.1 The Top-Level Key `state_machines`

Every `state_machines` file is a JSON object in which the value of the `state_machines` key must be an array:

```json
{ "state_machines": [ <definition>, ... ] }
```

Rules (`StateMachineDefinitionLoader.decodeFile`, `:67-100`):

- the body must be a JSON object;
- it must have the top-level key `state_machines` (constant `FIELD_STATE_MACHINES = "state_machines"`, `:42`);
- the value of that key must be an array.

**Multiple top-level keys are legal**: a file may carry other top-level keys as well (such as `animation_clips`); this decoder reads only its own key and ignores the rest, and no longer rejects the file for that reason. Among them, keys claimed by no reload type (genuinely unknown keys) get one `unsupported top-level field` report from the orchestrator, but this **does not affect** the contribution of `state_machines` (see §2.4).

The three violations (non-object body, missing key, non-array value) **reject the whole file**, return one error, and register no definitions. The per-case wording is in `decodeFile` (`:67-100`); the missing-key diagnostic quotes the expected shape `{"state_machines": [ <definition>, ... ]}` verbatim (`EXPECTED_SHAPE`, `:45`).

### 2.2 A Legal Example

Below is a complete file the current loader can parse (`id = example:lamp`, two states, one trigger transition + one guard transition):

```json
{
  "state_machines": [
    {
      "id": "example:lamp",
      "state_vars": [
        { "name": "lit", "type": "bool", "default": false, "ephemeral": true }
      ],
      "layers": [
        {
          "id": "main",
          "mode": "base",
          "initial_state": "off",
          "states": [
            { "id": "off" },
            { "id": "on", "pose": { "morphs": { "glow": 1.0 } } }
          ],
          "transitions": [
            { "id": "off_to_on", "from": "off", "to": "on", "trigger_on": "lit", "cross_fade_seconds": 0.2 },
            { "id": "on_to_off", "from": "on", "to": "off", "when": ["example:not_lit"], "cross_fade_seconds": 0.2 }
          ]
        }
      ]
    }
  ]
}
```

`trigger_on: "lit"` points to a boolean variable name declared in this machine's `state_vars`; `when: ["example:not_lit"]` points to a guard function registered on the code side. If either is not registered it only warns when the machine is built and does not affect file decoding (see §5.7).

### 2.3 The Old Wrapper-Less Shape Is Rejected (Breaking Change)

**The old format put the definition directly at the top level** (with no `state_machines` wrapper). This is the legacy shape for the current implementation, and the **whole file is rejected**:

```json
{
  "id": "example:lamp",
  "state_vars": [ { "name": "lit", "type": "bool" } ],
  "layers": [ { "id": "main", "initial_state": "off" } ]
}
```

Writing it this way yields `missing top-level key 'state_machines'` and **0 registrations**. The pre-rewrite `fsm.md` gave exactly this format; the two are directly opposite.

Executable evidence:

- `StateMachineDefinitionLoaderTest.legacyShapeIsRejectedAndReportsExpectedShape` (`:77-83`): asserts that the decode result is an empty definition list and that the error text contains `state_machines`;
- `ReloadOrchestratorTest.legacyShapeRegistersNothing` (`:244-247`): after a full reload cycle the id is `null`.

### 2.4 Extra Top-Level Keys No Longer Reject the File

```json
{
  "state_machines": [
    { "id": "example:lamp", "layers": [ { "id": "main", "initial_state": "off" } ] }
  ],
  "extra": 1
}
```

`extra` is claimed by no reload type, so the orchestrator reports one `unsupported top-level field 'extra'` for it (with a reverse D9 hint attached); but `state_machines` is still decoded and still registered as usual. The decision no longer depends on "how many top-level keys there are" but checks each unknown key one by one (`ReloadOrchestrator.dispatch`; `ContentStructure.unknownKeys`). Tests `StateMachineDefinitionLoaderTest.extraTopLevelKeyDoesNotAffectThisType` (`:96-102`), `ReloadOrchestratorTest.extraTopLevelKeyIsReportedButDoesNotRejectTheFile` (`:265-275`).

> Earlier versions checked `root.size() != 1` inside `decodeFile` and rejected the whole file for one extra key; that check has been removed.

## 3. The `animation_clips` File Wrapper

The shape is symmetric, and the claimed top-level key is `animation_clips` (constant `AnimationClipLoader.FIELD_ANIMATION_CLIPS = "animation_clips"`, `:45`):

```json
{ "animation_clips": [ <clip>, ... ] }
```

The exact same three file-level violations, with the same whole-file-rejection semantics (`AnimationClipLoader.decodeFile`, `:69-102`). Tests in `AnimationClipLoaderTest`: `rejectsANonObjectBody` / `rejectsAMissingTopLevelKey` / `ignoresAnExtraTopLevelKey` / `rejectsANonArrayValue`.

Multiple top-level keys are legal: one file **may declare both** `state_machines` and `animation_clips`, and each of the two handlers claims its own key and contributes its own entries, with no effect on the other (`ReloadOrchestrator.dispatch`, `:429-470`; test `AnimationClipReloadTest.mixedTopLevelKeysAreConsumedByBothHandlers`). Only a genuinely unknown key (claimed by no handler) reports `unsupported top-level field`, and it does not block the known keys.

## 4. File-Level Strictness vs Element-Level Tolerance

Both decoders share the same failure isolation, whose boundary is **file** and **element**:

| Level | Violation | Consequence |
|------|------|------|
| File level | Non-object body / missing top-level key / non-array value | **The whole file is rejected**, one error, 0 registrations |
| File level | A genuinely unknown top-level key (claimed by no handler) | **One `unsupported top-level field` report**; known keys still contribute as usual (half-applied) |
| Element level | An array element is not an object | **Only that element is skipped**, reporting `key[i] must be an object ...; element skipped` |
| Element level | The element is an object but codec decoding fails (missing required field / wrong type / unknown enum token) | **Only that element is skipped**, reporting `key[i] failed to decode ...; element skipped`; siblings carry on as usual |
| Element level | The element object has a key the codec **does not name** | **No error; ignored** |

- The dividing line is implemented by two sections of logic in `decodeFile`: the shape check runs first and returns for the whole file; only after it passes are elements decoded one by one through `CODEC.parse(...).resultOrPartial(...)` (`StateMachineDefinitionLoader.java:88-98`; `AnimationClipLoader.java:90-100`).
- Tests for element-level tolerance: `StateMachineDefinitionLoaderTest.badElementIsSkippedWhileSiblingsDecode` (`:85-94`, an array with a number and an object missing `layers` sandwiched in it, where both good elements still decode), `AnimationClipLoaderTest.skipsAMalformedElementAndKeepsItsSiblings` (`:104-114`) and `skipsAnElementWithoutAnId` (`:117-126`).
- Unknown keys inside an element are ignored: `AnimationClipLoaderTest.ignoresKeysTheClipRecordDoesNotName` (`:132-144`). **This is the biggest difference between the element level and the file level**: a genuinely unknown key at the file's top level does get reported (though the file's known keys still take effect as usual), whereas one extra key inside an element object is silently ignored with no output at all. State machine elements are the same (`StateMachineDefinition.CODEC` is also a `RecordCodecBuilder`), but so far only the clip side has a dedicated test for this.
- An empty array is legal: `{"animation_clips": []}` passes cleanly (`AnimationClipLoaderTest.emptyClipArrayIsValid`, `:53-59`).

## 5. `state_machines` Element Fields

The element schema is decided by `StateMachineDefinition.CODEC` (`StateMachineDefinition.java:21-25`) and is **independent of the wrapper**: the wrapper exists only at the file layer, so inline / script registration, content hashing, and programmatic registration are all unaffected (`StateMachineDefinitionLoader` class Javadoc `:14-33`).

### 5.1 Definition Top Level

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string (`namespace:path`) | **Yes** | — | Machine identifier; blocks / BEs use it to bind; parsed by `Id.CODEC` (`Id.java:112`) |
| `state_vars` | array | No | `[]` | Typed variable declarations |
| `layers` | array | **Yes** | — | Parallel state layers |

Source: `StateMachineDefinition.CODEC` (`StateMachineDefinition.java:21-25`). A missing or illegal `id` → that element fails to decode and is skipped.

### 5.2 `state_vars[]`

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `name` | string | **Yes** | — | Reference name within this machine; `trigger_on` resolves by it (this machine's declarations are checked first, then the registry) |
| `type` | string | No | `"float"` | Built-in type token, see below |
| `default` | any JSON | No | That type's zero value | Default value matching the codec of `type` |
| `reference` | string | No | — | `namespace:path`, referencing an already-registered variable; once given, `type` / `default` are ignored |
| `ephemeral` | bool | No | `false` | `true` = cleared automatically at the end of the tick, used as a trigger |
| `external_writable` | bool | No | `true` | `false` = a derived parameter, writable only inside the machine |
| `sync` | bool | No | `false` | `true` = goes through the FSM sync channel (server-authoritative) |

Source: `StateVarDefinition.CODEC` (`StateVarDefinition.java:51-59`); for field semantics see that class's Javadoc (`:12-27`).

The built-in `type` token catalog (`StateVarType`, `StateVarType.java:24-28`): `bool` / `int` / `float` / `string` / `vec3`. Three things to note:

- **`type` and `default` are resolved only at build time**: `StateVarDefinition.CODEC` stores them as a string / raw JSON, and an unknown `type` or a mis-decoded `default` **does not reject the file**. Resolution happens in `DefinitionStateMachineFactory.resolveInline` (`:124-138`): an unknown `type` makes `StateVarType.byToken` throw; a failed `default` decode only WARNs and falls back to that type's zero value (`decodeOrDefault`, `:140-146`).
- For `reference`-mode resolution see `resolveReference` (`:112-121`): a referenced id that does not exist → WARN, and that variable does not take effect.
- The pre-rewrite documentation also listed `resource` as a built-in type. The current `StateVarType` catalog has **no** `resource`, and no `StateVarType.register(...)` call can be found anywhere in the repository; today `resource` is called built-in only in scripting's Javadoc (`AnimatorBuilderApi.java:192`). See Appendix B, "To Be Confirmed".

### 5.3 `layers[]`

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | **Yes** | — | Layer identifier |
| `mode` | string | No | `"base"` | `base` (bottom layer) / `additive` (accumulate) / `override` (overwrite) |
| `weight` | float | No | `1.0` | Weight of this layer |
| `bone_mask` | string | No | all | Bone mask (affects only the specified bone group); resolution in `LayerDefinition.resolvedMask` |
| `states` | array | No | `[]` | State nodes |
| `transitions` | array | No | `[]` | Transition edges |
| `initial_state` | string | **Yes** | — | Initial state id |

Source: `LayerDefinition.CODEC` (`LayerDefinition.java:24-32`). `mode` is parsed by `BlendMode.CODEC` (`BlendMode.java:18-23`), and **an unknown value reports a decode error outright**, causing the whole definition element to be skipped.

Layer compositing semantics (`BlendMode` Javadoc, `BlendMode.java:10-17`): `base` is the bottom layer and its pose is the baseline; `additive` accumulates on top of the baseline; `override` overwrites the channels selected by the mask.

> The `BlendMode` here (layer compositing: base / additive / override) and `MorphInstance.BlendMode` (color multiply-add for a single morph) are two different axes (`BlendMode.java:6-9`).

### 5.4 `states[]`

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | **Yes** | — | State identifier |
| `duration_ticks` | int | No | none | Number of ticks the state lasts; with a `when_complete` transition it ends automatically |
| `pose` | object | No | empty pose | The static pose this state applies to the model (see §5.6) |
| `on_enter` / `on_exit` / `on_update` | id[] | No | `[]` | Action function ids (see §5.7) |
| `clip` | string or object | No | none | Animation clip reference (see §5.8) |

Source: `StateDefinition.CODEC` (`StateDefinition.java:25-33`).

### 5.5 `transitions[]`

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string | **Yes** | — | Transition identifier |
| `from` / `to` | string | **Yes** | — | From / to state ids |
| `trigger_on` | string | No | none | Trigger: points to a boolean variable name / id, fires when it is set |
| `when_complete` | bool | No | `false` | Fires when the `from` state reaches `duration_ticks` |
| `cross_fade_seconds` | float | No | `0` | Cross-fade seconds; 0 = switch immediately |
| `when` | id[] | No | `[]` | Guard function ids; the transition is allowed only if all of them are true |
| `on_fire` | id[] | No | `[]` | Action function ids executed when the transition fires |

Source: `TransitionDefinition.CODEC` (`TransitionDefinition.java:25-34`). `trigger_on` requires the variable to be of boolean type; a non-`ephemeral` variable only WARNs and is handled with tick semantics (`DefinitionStateMachineFactory.resolveTrigger`, `:231-248`). When `from` / `to` point to a state that does not exist, that transition is skipped at build time (`buildLayer`, `:175-180`).

### 5.6 `pose`

`pose` is the generic static pose a state applies to the model: morph weights, bone transforms, material frames, and IK switches.
The same Pose is also usable through [ModelPosing](model-posing.md), and the shared runtime does not require the target model to come from any particular file format.

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `morphs` | object (string→float) | No | `{}` | morph name → weight |
| `bones` | array | No | `[]` | List of bone transforms |
| `frames` | array | No | `[]` | List of material frames |
| `ik_enabled` | object (string→bool) | No | `{}` | Generic IK chain name to enabled state, see [Pose IK channel](model-posing.md#pose-的-ik-通道) |

Source: `PoseDefinition.CODEC`. The IK channel follows the same pose blending and write-ownership rules; after driving stops, or after switching to a pose that lacks that channel, the default enabled state is restored.

`bones[]` (`PoseDefinition.BoneDefinition.CODEC`, `:31-35`):

| Field | Type | Required | Default |
|------|------|------|------|
| `name` | string | **Yes** | — |
| `transform` | object | **Yes** | — |
| `mode` | string | No | `"replace"` (`replace` / `add` / `multiply`; an unknown value falls back to `replace`, `ApplyMode.byName`, `ApplyMode.java:28-38`) |

`transform` (`TransformDefinition.CODEC`, `TransformDefinition.java:26-30`): `translate` / `rotate` / `scale` are all arrays of **exactly 3 floats** (`VEC3F_CODEC`, `:19-22`), defaulting to `[0,0,0]` / `[0,0,0]` / `[1,1,1]` respectively; `rotate` is in **degrees**.

`frames[]` (`PoseDefinition.FrameDefinition.CODEC`, `:42-45`): `material` (string, required), `frame` (int, required).

### 5.7 Behavior Function References

The ids in `when` / `on_enter` / `on_exit` / `on_update` / `on_fire` are registered by the **code side** (Java or script) through `FsmFunctionLibrary`: guards with `registerCondition`, actions with `registerAction` (`FsmFunctionLibrary.java:19-25`). JSON stores only the ids.

Unregistered functions **do not affect file decoding**: they are aggregated into a single WARN when the machine is built, a missing condition degrades to `false`, and a missing action is a no-op (`DefinitionStateMachineFactory.validateDefinition` Javadoc, `:321-326`).

### 5.8 `clip`

`states[].clip` references an already-registered animation clip, written in two ways (`StateDefinition.ClipDefinition.CODEC`, `StateDefinition.java:47-50`):

- Object: `{ "id": "ns:clip", "loop": true }`, where `loop` defaults to `false` (`:40-45`);
- String shorthand: `"ns:clip"`, equivalent to `loop: false`.

Reference resolution and dangling handling are in §9. All four states of the real fixture `fan_machine_data_driven.json` use the object form to reference the same clip and set `loop: true`.

## 6. `animation_clips` Element Fields

The element schema is decided by `AnimationClip.CODEC` (`AnimationClip.java:47-54`). An element **carries its own `id`** (the very id `states[].clip` references); the loader does not extract an id separately (`AnimationClipLoader` class Javadoc `:20-26`).

### 6.1 Clip Top Level

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `id` | string (`namespace:path`) | **Yes** | — | Clip identifier |
| `duration_seconds` | float | No | `1.0` | Clip duration (seconds) |
| `bones` | array | No | `[]` | Bone keyframe tracks |
| `morphs` | array | No | `[]` | morph value tracks |
| `frames` | array | No | `[]` | Material frame tracks |
| `functions` | array | No | `[]` | Formula tracks (evaluated every frame) |

### 6.2 `bones[]`

| Field | Type | Required | Default |
|------|------|------|------|
| `bone` | string | **Yes** | — |
| `keyframes` | array | **Yes** | — |

`keyframes[]` (`Keyframe.CODEC`, `AnimationClip.java:71-75`): `time` (float, required), `transform` (`TransformDefinition`, required, fields as in §5.6), `easing` (string, optional, default `"linear"`).

### 6.3 `morphs[]`

`morph` (string, required) + `keyframes` (required); a keyframe is `{ "time": float, "value": float, "easing": string? }` (`MorphKeyframe.CODEC`, `:93-97`).

### 6.4 `frames[]`

`material` (string, required) + `keyframes` (required); a keyframe is `{ "time": float, "frame": int }` (`FrameKeyframe.CODEC`, `:111-114`). Material frame tracks round to the nearest keyframe and do not interpolate (`FrameTrack` Javadoc, `:100`).

### 6.5 `functions[]`

| Field | Type | Required | Default | Meaning |
|------|------|------|------|------|
| `bone` | string | **Yes** | — | Target bone |
| `channel` | string | **Yes** | — | `rotate` / `translate` / `scale`; an unknown value falls back to `rotate` (`CHANNEL_CODEC`, `:133-145`) |
| `x` / `y` / `z` | string | No | `""` | Per-axis formula expression; an empty string = that axis keeps its unit value (rotate 0 / translate 0 / scale 1) |

Source: `FunctionTrack.CODEC` (`AnimationClip.java:147-153`); the expressions are evaluated frame by frame at runtime in the `lib.kasuga.formula` namespace. The real fixture (`fan_fsm_data_driven.json`) has exactly these three formula tracks:

```json
{
  "animation_clips": [
    {
      "id": "example:fan",
      "duration_seconds": 12.0,
      "functions": [
        { "bone": "group", "channel": "rotate", "y": "query.angle" }
      ]
    }
  ]
}
```

> When encoding, fields whose value equals the default are omitted. In the real fixture `fan_fsm_data_driven.json`, the empty axes (`x` / `z`) of the three formula tracks are not written out, which is precisely the canonical encoded shape of `AnimationClip.CODEC`; `FanDataDrivenClipTest.clipFileIsInCanonicalEncodedShape` pins that the file is verbatim equal to the codec's encoding result.

### 6.6 easing Names

`easing` maps a string to a built-in easing (`EASING_CODEC`, `AnimationClip.java:39-45`). **An unknown name does not error; it falls back to `linear`**. The available names are `Easing.NAMED` (`Easing.java:232-246`):

`linear`, `ease_in_quad`, `ease_out_quad`, `ease_in_out_quad`, `ease_in_cubic`, `ease_out_cubic`, `ease_in_out_cubic`, `ease_in_sine`, `ease_out_sine`, `ease_in_out_sine`, `back`, `elastic`, `bounce`.

### 6.7 Unknown Keys Inside an Element Are Ignored

Keys the `AnimationClip` record does **not name** are ignored by the codec, so an author can add comment-like fields without breaking loading (`AnimationClipLoader` class Javadoc `:24-26`; test `AnimationClipLoaderTest.ignoresKeysTheClipRecordDoesNotName`, `:132-144`).

## 7. Differences Between the Two Discovery Paths

| | `state_machines` | `animation_clips` |
|--|------------------|-------------------|
| Directory glob | Yes (`data/<ns>/state_machines/*.json`) | **No** |
| Listed by the index `on_reload` | Supported | **The only entry point** |
| When not listed in the index | Still picked up by the glob | **Never read** |
| Both entry points listing the same file | The glob entry skips it; read once | Not applicable |

Source: `FsmReloadHandler.globDirectories()` (`FsmReloadHandler.java:69-71`) declares `StateMachineDefinitionLoader.PATH` (`StateMachineDefinitionLoader.java:39`); the glob listing is in `ReloadOrchestrator.collectGlob` (`:348-367`); `AnimationClipLoader` class Javadoc (`:29`); `ReloadOrchestrator` class Javadoc (`:77-91`). Tests `AnimationClipReloadTest.animationClipsDirectoryIsNotGlobDiscovered`, `ReloadOrchestratorTest.globAndIndexListingTheSameFileReadItOnce`.

> The glob only handles **discovery**; **routing** always follows the file's top-level key. A file that lands in the `state_machines/` directory but writes `animation_clips` at the top is still handled by the clip handler (`ReloadOrchestrator.collectGlob` Javadoc, `:340-347`).

## 8. The Orchestrator `ReloadOrchestrator` and the Two Handlers

### 8.1 The Stage Order of `reload()`

`reload(ResourceManager)` (`:186-193`) wraps the private `runCycle` (`:198-248`); the order within one cycle is fixed and type-agnostic (it walks the registry rather than hard-coding the two types):

1. **Build the cycle**: create one `HandlerCycle` per handler in `ReloadHandlerRegistry.all()` (`:199-201`).
2. **Clear the buckets**: call `runClear(entry)` → `clearBucket()` for each handler, **exactly once per cycle** (`runClear`, `:250-261`; call sites `:213-215`). An exception thrown by `clearBucket()` is caught in place by `runClear` and recorded as a `Domain.RELOAD_DATA` diagnostic, while the other handlers clear their buckets as usual (a per-handler guard, the same kind as in the closing phase). `FsmReloadHandler` clears only the RESOURCE half of `FsmDefinitions`, and `FsmClipsReloadHandler` clears only the RESOURCE half of `FsmAnimationClips` (script wins, see §10).
3. **Aggregate the glob directories**: collect each handler's `globDirectories()` (`:222-225`); `state_machines` contributes `state_machines/`, clips contributes nothing.
4. **List the namespaces** and sort them, with **dual-path discovery per namespace** (`collectNamespace`, `:266-288`): first `readReloadPaths` (`:307`) reads the index manifest's `on_reload`, then `collectGlob` (`:348-367`) scans the aggregated glob directories (skipping paths already listed), and finally `collectIndex` (`:369`) reads the files that `on_reload` points to.
5. **Register**: call `register(entry)` for each handler (`:507-526`); glob candidates come first and index candidates after (`HandlerCycle.candidates()`, `:595-599`) → deduplicate and write to the bucket.
6. **Closing re-check**: call `runAfterReload(entry)` for each handler (`:530-538`), only for entries registered this cycle, and after all the registers. `FsmReloadHandler.afterReload` runs the dangling clip check here.

The index side reuses the registration domain's `JsonTreeBuilder.parseIndexManifest` / `resolveIndexManifests`, so D6's "the same path listed in both → neither domain consumes it" also holds on the reload side (`readReloadPaths`, `:307`).

### 8.2 Types Are Registry-Driven, Not Hard-Coded

The orchestrator does not know any reload type name: the set of top-level keys is derived from `ReloadHandlerRegistry.all()` by `knownTypeNames()` (`:473-486`), and dispatch (`dispatch`, `:429-470`) routes by each handler's `typeName()`. Adding one reload type = implementing one `ReloadHandler` and calling `ReloadHandlerRegistry.register` in a `@Context` bean, **without touching the orchestrator**. This is what §4.6 means by "the reload side is symmetric with the registration phase"; the two FSM types are registered by `FsmReloadHandlerRegistrar` (`:23-31`) in **fsm → clips** order, and that order decides the dispatch/registration order and in turn the cross-type last-wins.

To understand how one handler's responsibilities are split, see §8.5.

### 8.3 last-wins and "No Side Effects for the Loser"

Across the two entry points, the last same-id candidate in the load sequence wins (glob first, index second; within a file, the array's writing order). The decision uses the shared `DuplicateIdResolver`, and **the loser never enters the handler's `register`** at all, so no override notification is triggered; each conflict logs a WARN plus one bucket entry (`resolveAndReport`, `:545-556`). Tests: `ReloadOrchestratorTest.indexEntryWinsOverTheGlobEntryAndTheLoserIsNotRegistered`, `duplicateIdWithinFileLastWins`; on the clip side `AnimationClipReloadTest.laterClipFileWinsForTheSameId`.

### 8.4 Four Layers of Exception Guards + a Fallback

`reload()` sits on the reload listener path; a thrown exception would abort the whole pack reload with the buckets half-cleared, so every failure must be turned in place into a "log + bucket" diagnostic:

| Layer | Location | Attribution target |
|----|------|----------|
| Per handler (bucket clearing) | `runClear` `:250-261` | The library mod (`KasugaLib.MODID`) |
| Per file (read / decode) | `readAndDispatch` `:405-415` | The namespace the file lives in |
| Per namespace (discovery mechanism) | `collectNamespace` `:266-288` | That namespace |
| Per entry (registration) | `register` `:507-526` | The namespace the entry lives in |
| Per handler (closing re-check) | `runAfterReload` `:530-538` | The library mod (when no finer attribution exists) |
| Fallback (no attribution available) | `reload()` `:186-193` | The library mod (`KasugaLib.MODID`) |

Tests `ReloadOrchestratorExceptionBoundaryTest`: `uppercaseIndexPathIsRejectedAndTheCycleContinues`, `throwingFileIsIsolatedAndTheNextFileStillLoads`, `wholeCycleFailureIsCaughtAndRecordedAgainstTheLibraryMod`. When the whole cycle fails, the buckets stay empty: the empty state is the truthful outcome and the errors stay in the bucket (class Javadoc `:106-114`).

### 8.5 How the Two Handlers Split Responsibilities

| | `FsmReloadHandler` | `FsmClipsReloadHandler` |
|--|--------------------|-------------------------|
| `typeName()` | `state_machines` | `animation_clips` |
| `globDirectories()` | `{state_machines}` | default empty = index-only |
| `describeFile()` | `"state machine file"` | `"animation clip file"` |
| `clearBucket()` | `definitions.clearResource()` | `clips.clearResource()` |
| `decode()` | delegates to `StateMachineDefinitionLoader.decodeFile` | delegates to `AnimationClipLoader.decodeFile` |
| `register()` | `definitions.registerResource(id, def)` | `AnimationClipLoader.register(clips, clip)` |
| `afterReload()` | runs the dangling clip check (see §9) | not overridden (default empty) |

### 8.6 Diagnostics Go Through the Domain/Source-Key Dimension (`Domain.RELOAD_DATA`)

`ReloadOrchestrator.reportError` (`:566-573`) and both handlers' `reportError` / `reportWarning` all call `Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, namespace, ...)`, where `namespace` is the namespace of the file that failed. Reload-phase diagnostics go through the **domain/source-key dimension** (`Domain.RELOAD_DATA`, key = namespace), separate from the registration-phase mod dimension; `runCycle` clears that dimension at the start of each cycle and does not accumulate (`Diagnostics.clear(Domain.RELOAD_DATA)`, `:208`). Read them with `/kasuga_data errors [mod]` (which lists both dimensions); details in sections 6 and 13 of [`data-driven/README.md`](data-driven/README.md).

> The reload phase also gives a reverse hint for an **unknown top-level key**: if those fields are in fact registration content (such as `blocks` / `items` / `registry_groups`), list the file under `on_register` instead (`dispatch`, `:439-446`). The registration-type examples in the hint are derived at runtime from `TypeHandlerRegistry`.

## 9. clip References and Dangling References

- **Build time**: `states[].clip` is resolved by id in `FsmAnimationClips`; if it does not exist → WARN, and the state degrades to a static pose (`configureState`, `:184`).
- **Reload closing**: `FsmReloadHandler.afterReload` (`:95-116`) runs `DefinitionStateMachineFactory.unknownClipReferences` (`:303-323`) over each definition that **did land** this cycle, aggregating the dangling references (layer id + state id + missing clip id) into one WARN + bucket entry.
- A dangling reference is **only recorded; it does not block registration**, nor does it change the build-time degradation behavior (`ReloadOrchestrator` class Javadoc `:100-104`; `FsmReloadHandler.afterReload`).
- A candidate already shadowed by a script definition with the same id skips the re-check; it is no longer a live entry in the bucket (`FsmReloadHandler.java:99-104`).
- Tests: `AnimationClipReloadTest.missingClipFileIsReportedAfterTheLoad`, `wrongClipIdInTheClipFileIsReported`; the same rule also feeds the build-time aggregate warning (`unknownClipReferences` Javadoc `:289-302`).

## 10. script-wins (Bucketing)

Both buckets split their entries in half by **source**:

| Bucket | SCRIPT entry | RESOURCE entry | Bucket clearing |
|----|-------------|---------------|------|
| `FsmAnimationClips` | `register` (`FsmAnimationClips.java:53-59`) | `registerResource` (`:66-74`) | `clearResource` (`:100-102`) drops RESOURCE only; `clear()` = `clearResource` (`:117-119`); `clearAll` (`:108-110`) clears everything |
| `FsmDefinitions` | `register` (`FsmDefinitions.java:59-67`) | `registerResource` (`:71-81`) | `clearResource` (`:84-92`) drops RESOURCE only |

- **Write-side** invariant: `register` (SCRIPT) overrides RESOURCE (with a WARN, "script wins"); `registerResource` (file) **does not override** SCRIPT entries.
- So `AnimationClipLoader.register` always goes through `registerResource` (`:113-121`): a file clip can never shadow a same-id clip registered by a script / content test / game test.
- Each reload cycle's `clips.clearResource()` drops only file-origin entries, so script-registered clips survive across reloads.
- Tests: `AnimationClipReloadTest.scriptClipWinsOverTheFileVersion`, `secondCycleReplaysClipFilesWithoutLeftovers`; bucket unit tests in `FsmAnimationClipsTest`.

## 11. Counterexample Roundup

These snippets are listed so a reader can recognize "a shape copied from the old documentation" at a glance; two of them are **no longer illegal** under the new contract:

1. **A top-level definition without a wrapper** (§2.3) → `missing top-level key 'state_machines'`, whole file rejected.
2. **A genuinely unknown top-level key** (§2.4) → one `unsupported top-level field '...'` report plus a reverse D9 hint, but **known keys still contribute as usual** (no longer a whole-file rejection).
3. **The top-level key's value written as an object** (such as `{"state_machines": {}}`) → `must be an array`, whole file rejected (`StateMachineDefinitionLoaderTest.nonArrayValueRejectsWholeFile`; `ReloadOrchestratorTest.NON_ARRAY_JSON`).
4. **One file writing both kinds of content** (§3) → now **legal**: each of the two handlers claims its own key and contributes its own entries, 0 diagnostics.
5. **An element missing `layers`** (§4) → only that element is skipped; siblings carry on as usual.

## 12. Binding to Blocks

A data-driven registered block binds a machine id through the `fsm_block` / `fsm_be` factories: `fsm_block` creates the block (with a default block item), and `fsm_be` creates an `AnimationBlockEntity`, reading `state_machine` / `model` from `params` (`FsmBlockEntityFactories.java:27-30,48-74`). The `state_machine` string at the top level of the block entry is forwarded into the embedded BE's `params.state_machine` (`BlockEntityTypeHandler.extractEmbedded`, see schema.md §2.5).

This part belongs to **registration-phase content files**; for the format see the `blocks` and embedded `block_entity` chapters of [`data-driven/schema.md`](data-driven/schema.md); for a working end-to-end example see [`data-driven/guide-content.md`](../guides/data-driven/guide-content.md).

## 13. Further Reading

| What you want | Where to go |
|--------|------|
| Index files, registration-phase content files, error classification | [`data-driven/schema.md`](data-driven/schema.md) |
| Java classes / method signatures, load ordering | [`data-driven/README.md`](data-driven/README.md) |
| Building your first content file from scratch | [`data-driven/guide-content.md`](../guides/data-driven/guide-content.md) |
| Data-driven overview | [`data-driven/intro.md`](../guides/data-driven/intro.md) |
| Custom `type` factories / TypeHandler | [`data-driven/guide-extension.md`](../guides/data-driven/guide-extension.md) |

## Appendix A: Rule-to-Source Map

| Rule | Source (class:line / method / test name) |
|------|-------------------------------|
| The `state_machines` top-level key, the three file-level violations | `StateMachineDefinitionLoader.decodeFile` `:67-100`; `StateMachineDefinitionLoaderTest` |
| The legacy wrapper-less shape being rejected | `StateMachineDefinitionLoaderTest.legacyShapeIsRejectedAndReportsExpectedShape`; `ReloadOrchestratorTest.legacyShapeRegistersNothing` |
| The `animation_clips` wrapper | `AnimationClipLoader.decodeFile` `:69-102`; `AnimationClipLoaderTest` |
| File-level strictness / element-level tolerance | `StateMachineDefinitionLoaderTest.badElementIsSkippedWhileSiblingsDecode`; `AnimationClipLoaderTest.skipsAMalformedElementAndKeepsItsSiblings` / `skipsAnElementWithoutAnId` |
| Unknown keys inside an element being ignored | `AnimationClipLoaderTest.ignoresKeysTheClipRecordDoesNotName` (clip side) |
| State machine element fields | `StateMachineDefinition.CODEC` `StateMachineDefinition.java:21-25`; sub-codecs `StateVarDefinition` / `LayerDefinition` / `StateDefinition` / `TransitionDefinition` / `PoseDefinition` / `TransformDefinition` |
| `state_vars` built-in types | `StateVarType.java:24-28`; build-time resolution in `DefinitionStateMachineFactory.resolveInline` `:124-146` |
| Clip element fields | `AnimationClip.CODEC` `AnimationClip.java:47-54` plus sub-codecs; easing `Easing.java:232-246` |
| Directory glob vs index-only | `ReloadOrchestrator.collectGlob` `:348-367`; `AnimationClipLoader.java:29`; `FsmReloadHandler.globDirectories` `:69-71` |
| reload stage order / dual entry points | `ReloadOrchestrator.reload` `:186-193`, `runCycle` `:198-248`, `collectNamespace` `:266-288` |
| last-wins | `ReloadOrchestrator.resolveAndReport` `:545-556`; `DuplicateIdResolver` |
| Four exception guards + fallback (+ bucket-clearing guard) | `ReloadOrchestrator` `:250-261,405-415,266-288,507-526,530-538,186-193`; `ReloadOrchestratorExceptionBoundaryTest` |
| Diagnostics going through the domain/source-key dimension | `ReloadOrchestrator.reportError` `:566-573`; `FsmReloadHandler.reportWarning` `:127-130`; `Diagnostics` || Dangling clip references | `DefinitionStateMachineFactory.unknownClipReferences` `:303-323`; `FsmReloadHandler.afterReload` `:95-116` |
| Types being registry-driven | `ReloadOrchestrator.knownTypeNames` `:473-486`, `dispatch` `:429-470`; `ReloadHandlerRegistry`; `FsmReloadHandlerRegistrar` `:23-31` |
| script-wins bucketing | `FsmAnimationClips.java:53-119`; `FsmDefinitions.java:59-92`; `AnimationClipLoader.register` `:113-121` |

## Appendix B: To Be Confirmed

- **The `resource` type token**: it is absent from the `StateVarType` catalog, and there is no `StateVarType.register(...)` call anywhere in the repository; yet scripting's `AnimatorBuilderApi.java:192` Javadoc lists it as one of the built-in tokens. The two contradict, so for now it is written as "not currently available". If a downstream registration exists, this needs to be filled in.
- **Whether unknown keys inside a state machine element are likewise silently ignored**: inferred from `StateMachineDefinition.CODEC` (a `RecordCodecBuilder`) that they should be ignored, but only the clip side has a dedicated test for that.
- The JSON examples on this page were only syntax-checked (`JSON.parse`) and cross-checked field by field against the codecs; the Java loader was not executed. Decoding of **build-time** fields such as `state_vars[].type` is outside the scope of file-layer verification.
