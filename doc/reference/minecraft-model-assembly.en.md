# Minecraft outfit resources and instance switching API

For generic geometry/skeleton assembly see [Model Assembly](model-assembly.md). This page only describes Minecraft's resource lookup,
publication and handle integration; Model and its generic subsystems have no model format allowlist.

## Resource pack definitions

Files live in `assets/<namespace>/model_assemblies/<path>.json`, and the definition ID is `<namespace>:<path>`.
For example `assets/example/model_assemblies/winter.json` corresponds to `example:winter`.
Source models still have to be preloaded through the existing `model_proxy` configuration, see [model loading](modelling/README.md).

```json
{
  "body": {
    "id": "body",
    "model": "example:models/character.bbmodel",
    "regions": { "torso": [0, 1, 2] },
    "hide_regions": ["torso"]
  },
  "parts": [
    {
      "id": "shirt",
      "model": "example:models/shirt.glb",
      "bone_mappings": { "shirt_root": "root" }
    },
    {
      "id": "coat",
      "model": "example:models/coat.mmd.zip",
      "model_name": "coat",
      "include_dynamics": true
    }
  ],
  "bind_tolerance": 0.0001
}
```

| Field | Default/Requirement |
| --- | --- |
| `body` | Required part object; `id` defaults to `body` |
| `parts` | Optional part array, order preserved; each item's `id` is required and must not repeat |
| `model` | Required resource location per part; routing reuses the registered model pipeline |
| `model_name` | Optional internal model name, for Readers that need an internal entry lookup |
| `bone_mappings` | Object from source bone name to output bone name, default `{}`; the body cannot have aliases |
| `regions` | Object from region name to source face index array, default `{}` |
| `hide_regions` | Array of hidden region names, default `[]`; an unknown name is an error |
| `hide_meshes` | Array of additional hidden face indices, default `[]`; indices must be integers |
| `match_body_bones` | boolean, default true |
| `include_dynamics` | boolean, default true |
| `bind_tolerance` | Finite positive number, default `1e-4` |

Definitions are read during the resource preparation phase; after textures are ready they are published on the game thread. Invalid JSON makes that preparation fail,
and a partial outfit definition is not published. When sources are missing the definition can still be registered, but `mount` returns false and it retries on the next tick.
Validations that depend on the actual model, such as bind matrices and hidden face ranges, run after the full sources are ready.
The body and the clothing must already be in the same bind space; this is not an automatic body-fitting interface.

## McModelHandle

Package: `lib.kasuga.rendering.models.mc.api`.

```java
McModelHandle handle = McModelHandle.ofAssembly(
        ResourceLocation.parse("example:winter"), instanceId, entity.position());
if (handle.mount()) {
    handle.scheduleVanillaRenderer();
    handle.markRenderedThisFrame();
}

boolean switched = handle.switchAssembly(ResourceLocation.parse("example:summer"));
```

| Method | Behavior |
| --- | --- |
| `static McModelHandle ofAssembly(ResourceLocation id, ResourceLocation instanceId, @Nullable Vec3 pos)` | Uses the global assembly service; may be created before the resource is published |
| `boolean switchAssembly(ResourceLocation id)` | Swaps using the global service; returns false when the service or the source is not ready |
| `boolean switchAssembly(McModelAssemblies service, ResourceLocation id)` | Explicit service, suited to custom pipelines |
| `McModelHandle onInstanceChanged(BiConsumer<ModelInstance, ModelInstance> callback)` | Called after the new instance has finished restoring its basic state; can install custom drivers/physics; null clears the callback |

A swap first resolves the full sources, gets the cached model and mounts the new instance, then releases the handle's old instance.
When a source is not ready, the current outfit is left unchanged; a bind/callback exception cleans up the new instance and is thrown to the caller, keeping the old instance.
The callback runs before the generic driver migration, and a new driver set there takes precedence; external side effects of the callback itself are the caller's responsibility.

Position, rotation, scale and the double floating origin, the visibility mode, the distance limit and the ambient multiplier are preserved.
Bone local inputs under compatible names, morph activation values, IK switches and persistent targets are copied to the new instance.
Temporary per-frame IK targets, physics simulation results, custom tick-loop modules and anchor callbacks are not copied; reconfigure them in the callback.
Manual material frames that need per-part remapping are handled in the callback as well.

The generic `ModelPosing`, `AnimationPlayer` and `FsmPoseDriver` implement `RebindablePoseDriver`.
A named ModelPosing refreshes its data from the new Model's clip library and keeps the original timeline, playback time, speed and paused state;
a missing clip restores an empty pose. An external shared clock is not restarted, and other targets following that player's own clock keep the same clock.
The rebind of a plain AnimationPlayer/FSM migrates into the target; when its external clip data needs updating, hook that in the callback.
A custom driver implements the same interface or is created in the callback.

Replacing or removing a source model, or reloading outfit resources, takes down the instances that borrowed the old resources. The handle keeps the basic state, and a later `mount()`
re-resolves and mounts automatically; outfit definitions in the registry are kept while a source is temporarily missing.
`destroy()` reclaims the current instance and its publication subscriptions, and releases the pending restoration state held by the handle.
The instanceId of a global Actor should be managed uniquely by the host; the same service should not let two handles manage the same outfit/instanceId at the same time.

## Programmatic registration and custom services

```java
import lib.kasuga.rendering.models.uml.loaders.assembly.ModelAssemblyDefinition;
import lib.kasuga.rendering.models.mc.api.McModelAssemblies;
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;

var definition = ModelAssemblyDefinition.builder("body",
        new McModelAssemblies.Reference(bodyLocation))
        .part("shirt", new McModelAssemblies.Reference(shirtLocation),
                part -> part.mapBone("shirt_root", "root"))
        .build();
var service = PipelineRegistry.assemblies(); // null before the client's built-in pipeline is initialized
if (service != null) service.register(outfitId, definition);
```

`PipelineRegistry.ASSEMBLY` has the value `assembly`; its pipeline has no format Reader and accepts derived generic Models.
A plain `of(modelLoc, ...)` still routes by file extension; outfit definitions use `ofAssembly` and do not need a faked file extension.

The complete `McModelAssemblies` interface:

```java
new McModelAssemblies(
    ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> output,
    Function<McModelAssemblies.Reference, McModelAssemblies.PublishedSource> sources,
    ModelAssemblyCache cache);

new McModelAssemblies.Reference(ResourceLocation model);
new McModelAssemblies.Reference(ResourceLocation model, @Nullable String modelName);
new McModelAssemblies.PublishedSource(
    ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline, ResourceLocation key);

void register(ResourceLocation id, ModelAssemblyDefinition<McModelAssemblies.Reference> definition);
boolean unregister(ResourceLocation id);
void replaceResourceDefinitions(Map<ResourceLocation, ModelAssemblyDefinition<McModelAssemblies.Reference>> next);
Map<ResourceLocation, ModelAssemblyDefinition<McModelAssemblies.Reference>> definitions();
@Nullable ModelAssembly resolve(ResourceLocation id);
@Nullable ModelInstance createAndBind(ResourceLocation id, ResourceLocation instanceId, @Nullable Transform root);
McModelHandle handle(ResourceLocation id, ResourceLocation instanceId);
ModelPipeLine<?, ?, ResourceLocation, ResourceLocation, ?> pipeline();
ModelAssemblyCache.Stats cacheStats();
void close();
```

A programmatic definition takes precedence over a resource-pack definition of the same name and is kept across resource reloads. `unregister` removes the programmatic override and reclaims that definition's
instances; if a resource-pack definition exists it is restored, and the return value says whether a programmatic definition was removed.
`replaceResourceDefinitions` applies a fully resolved resource snapshot and cleans up derived models and the assembly cache.
`close` is idempotent and reclaims derived instances, cache references and source publication subscriptions; after closing, calling the resolve/register interfaces is an error.
Service operations and model publication/render mounting must run serially on the game thread; the independent synchronization semantics of the base cache are still as described in the generic API.

The default source adapter reuses `KasugaModelPipelines.publishedSource(Reference)`; while a resource reload is in progress it does not resolve
resources of the old publication generation. A custom source function can integrate any published Model pipeline, without restricting its format.

## Generic resource definitions and publication lifecycle

The following interfaces are in render-core and do not depend on Minecraft:

```java
static <K> ModelAssemblyDefinition.Builder<K> ModelAssemblyDefinition.builder(String bodyId, K source);
static <K> ModelAssemblyDefinition.Builder<K> ModelAssemblyDefinition.builder(
        String bodyId, K source, Consumer<ModelAssemblyDefinition.PartBuilder> configure);
ModelAssemblyDefinition.Builder<K> part(String id, K source);
ModelAssemblyDefinition.Builder<K> part(String id, K source, Consumer<ModelAssemblyDefinition.PartBuilder> configure);
ModelAssemblyDefinition.Builder<K> bindTolerance(float tolerance);
ModelAssemblyDefinition<K> build();
@Nullable ModelAssembly.Request ModelAssemblyDefinition.resolve(Function<? super K, ModelAssembly.Source> sources);
```

`PartBuilder` provides `mapBone(String,String)`, `matchBodyBones(boolean)`, `includeDynamics(boolean)`,
`hideMeshes(int...)`, `region(String,int...)` and `hideRegions(String...)`.
This resource definition's `hideRegions` is resolved at call time, so define a region before hiding it.
A definition can be constructed directly as `ModelAssemblyDefinition(List<Part<K>>, float)`; the parameters of a nested `Part<K>` are, in order,
`id, source, Map<String,String> boneMappings, Set<Integer> hiddenMeshes, matchBodyBones, includeDynamics`.
Any K can be supplied by the host; resolving with a missing source returns null and produces no partial geometry.

```java
new ModelAssemblyRegistry<K, I>(Function<? super K, ModelAssembly.Source> sources, ModelAssemblyCache cache);
void register(I id, ModelAssemblyDefinition<K> definition);
ModelAssemblyDefinition<K> definition(I id);
Map<I, ModelAssemblyDefinition<K>> definitions();
boolean remove(I id);
@Nullable ModelAssembly resolve(I id);
Set<I> invalidate(Model source);
void clearResolved();
ModelAssemblyCache.Stats cacheStats();
```

The registry keeps resource definitions and the latest complete results, and returned collection views are read-only; call `invalidate` when a source is updated, and it returns
the definition IDs that depend on that old Model, which the host should use to take down the derived instances. A temporarily missing source produces no partial result.

`ModelPipeLine` adds:

| Method | Behavior |
| --- | --- |
| `Model getModel(StorageIdentifierType key)` | null when not published |
| `long modelRevision(StorageIdentifierType key)` | Current published version; 0 when absent |
| `StorageIdentifierType modelKeyOf(ModelInstance instance)` | Looks up the registered key, null when unregistered; the key is kept after binding for constant-time checks |
| `AutoCloseable onModelChanged(Consumer<ModelChange<StorageIdentifierType>> listener)` | game thread notification, running before the old instance is closed; closing the subscription unregisters |
| `void publishModels(Map<StorageIdentifierType,Model>)` | Merged publication; identical objects are skipped; replacing reclaims that key's instances |
| `void replaceModels(Map<StorageIdentifierType,Model>)` | Full replacement, deleting models missing from the snapshot |
| `boolean removeModel(StorageIdentifierType key)` | Removes that key and its instances; returns false when absent |
| `Builder.buildForPublishedModels()` | A derived pipeline without a SourceManager/Loader; still needs a bridge and backend |

`ModelChange<K>` is `key, previous, current, revision`, and current is null on deletion.
A listener exception does not skip other listeners or the old-instance reclamation; the errors are aggregated and thrown after cleanup completes.
Instances are registered by resource key, and different keys sharing the same Model do not reclaim each other; createInstance with the same key/instanceId is idempotent.
Calling prepare/load on a publication-only pipeline is an error; the old build methods of SourceManager/Loader pipelines still require a Reader.

`ModelPublicationBatch<K>.publish(prepared)` publishes one complete generation of a resource manager: the keys of prepared are
`ModelPipeLine<?,?,K,?,?>`, and the values are `Map<K,Model>`. Old managed keys missing from the next generation are deleted, and other publishers' keys are kept.
Every managed pipeline gets a chance to clean up, even if an earlier pipeline's cleanup fails.

## Driver and input migration

```java
interface RebindablePoseDriver extends PoseDriver { void rebind(ModelInstance fresh); }
void ModelPosing.rebind(ModelInstance fresh);
ModelInstance ModelPosing.model();
void AnimationPlayer<T>.rebind(ModelInstance fresh, AnimationSampler<T> sampler, T data);
void AnimationPlayback<T,R>.retarget(AnimationSource<T,R> source, T data);
void SkeletonInstance.copyPoseInputsTo(SkeletonInstance target);
void MorphInstance<Id>.copyInputsTo(MorphInstance<Id> target);
```

Skeleton migration copies only named local transforms, IK switches and persistent targets; the root transform and floating origin are migrated separately by the host.
Morph migration copies the activation value/factor corresponding to the identity, and does not copy result caches or source structure objects. Names that do not exist on the target are ignored.
`AnimationPlayback.retarget` keeps the clock object/ownership; an own clock is updated to the new clip's duration, and an external clock is not modified.
These control operations must run serially with tick, and the render sample consumes the existing snapshot mechanism.
