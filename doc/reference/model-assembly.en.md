# Model assembly and outfit cache API

Package: `lib.kasuga.rendering.models.uml.loaders.assembly`. This interface accepts a generic Model from any source and
composes geometry, materials, skeleton, morph, named animations and dynamics into a single Model. For the principles and ownership see
[model subsystems](../explanation/model-subsystems.md); for pose control see [ModelPosing](model-posing.md).
For Minecraft's resource definitions, automatic invalidation and the instance swap entry point see [outfit resources and switching](minecraft-model-assembly.md).

Here a Mesh is a face definition. The assembly result is one Model and one Skeleton, handed to the existing backend to form geometry buffers;
it does not pack the vertices of several faces into one Mesh, nor does it promise a single draw for different materials/passes.

## Minimal call

```java
import lib.kasuga.rendering.models.uml.loaders.assembly.*;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSetInstance;

// The cache is owned by the clothing/model management service; do not create a new one every frame.
ModelAssemblyCache cache = new ModelAssemblyCache(32, 1_000_000);
ModelAssembly outfit = new ModelAssemblyBuilder("body", bodyModel, bodyRevision,
        body -> body.region("torso", 0, 1, 2).hideRegions("torso"))
        .part("shirt", shirtModel, shirtRevision)
        .part("coat", coatModel, coatRevision,
                coat -> coat.mapBone("coat_root", "root"))
        .assemble(cache);

var model = outfit.model();
ModelInstance instance = new ModelInstance(model, null, null, null,
        new MaterialSetInstance(model.getMaterialSet()), null);
instance.getPosing().play("walk", true);       // body clip names are kept
instance.getPosing().play("shirt/fit", false); // clothing clip names get a part prefix
```

Source models must already be fully loaded and use the same bind space/units. For shared bones, the difference between the source and target absolute bind
matrices must not exceed `bindTolerance`; the default is `1e-4`, compared per matrix component. Assembly does not automatically scale clothing, fit the body
or retarget animations from a different skeleton. Shared bones use the body hierarchy; bones unique to the clothing keep their original absolute bind pose.
All default mappings and geometry clones run on a cache miss; each frame only updates the pose of the merged instance.

## ModelAssemblyBuilder

```java
new ModelAssemblyBuilder(String bodyId, Model body, long revision);
new ModelAssemblyBuilder(String bodyId, Model body, long revision,
                         Consumer<ModelAssemblyBuilder.PartBuilder> configure);
```

The first part is the body. revision is the resource version managed by the caller, and any long is allowed; after modifying a resource in place you must
update the revision or call the cache invalidation interface. Source Models are distinguished by object identity; the format type is not read, and no
content hash of the whole resource is computed.

| Method | Behavior |
| --- | --- |
| `ModelAssemblyBuilder part(String id, Model model, long revision)` | Appends a part in order; by default matches same-named body bones and keeps dynamics |
| `ModelAssemblyBuilder part(String id, Model model, long revision, Consumer<PartBuilder> configure)` | Appends after configuring mappings, hidden regions and dynamics integration |
| `ModelAssemblyBuilder bindTolerance(float tolerance)` | Finite positive number, default `1e-4` |
| `ModelAssembly.Request build()` | Copies and validates the request; creates no geometry or GPU resources |
| `ModelAssembly assemble()` | Assembles directly with the standard assembler, without the cache |
| `ModelAssembly assemble(ModelAssemblyCache cache)` | Builds the request and fetches the cache result |

A part id must be non-empty, must not be all whitespace and must not repeat. The body cannot have bone aliases set. The configure callback runs synchronously;
the request stores its result, not the callback. Part order affects material indices and render order, so it is part of the cache key.

### PartBuilder

| Method | Behavior |
| --- | --- |
| `PartBuilder mapBone(String source, String target)` | Source bone name to output bone name; the source bone is resolved immediately, the target is validated at assembly time |
| `PartBuilder mapBone(Bone source, String target)` | Uses a source Bone reference, which must belong to that source model |
| `PartBuilder matchBodyBones(boolean enabled)` | Default true; matches same-named body bones when there is no explicit mapping |
| `PartBuilder includeDynamics(boolean enabled)` | Default true; false excludes that part's inheritance, fixed axes, IK/closures and physics definitions |
| `PartBuilder hideMeshes(int... indices)` | Removes faces by index into the source Model's Mesh array |
| `PartBuilder region(String name, int... indices)` | Defines a named region inside this part; the same name replaces it |
| `PartBuilder hideRegions(String... names)` | Hides those regions; expanded into face indices when the Part is constructed |

Explicit mappings take precedence over automatic same-name matching; a target may be a body bone or a bone already introduced by an earlier part, and may
not reference a later part. Unmapped part bones are named `partId/sourceName`; bones without a name use `bone_<source index>`.
New bones keep the source absolute bind matrix, and their local bind is recomputed relative to the already-mapped parent bone; an independent root is attached under the body root.
Output bone name conflicts, invalid mappings, incompatible binds or a cycle in the source hierarchy throw `IllegalArgumentException`.

Hidden indices must be within the source Mesh array's range; an unknown region is an error. Only vertices used solely by hidden faces and their morphs are
removed; shared vertices are kept; hiding geometry does not automatically remove bones or physics. Regions are supplied by the integrating party;
occlusion is not guessed automatically. Pure triangles and quads can be combined into MIXED and handled by the existing backend; lines and surfaces must be assembled separately.

## ModelAssembly and reference remapping

```java
Model ModelAssembly.model();
ModelAssembly.Request ModelAssembly.request();
Map<String, ModelAssembly.Remap> ModelAssembly.parts();
ModelAssembly.Remap ModelAssembly.part(String id);

new ModelAssembly.Source(Model model, long revision);
new ModelAssembly.Part(String id, ModelAssembly.Source source, Map<Bone, String> boneMappings,
                       Set<Integer> hiddenMeshes, boolean matchBodyBones, boolean includeDynamics);
new ModelAssembly.Request(List<ModelAssembly.Part> parts, float bindTolerance);
new ModelAssembly.PartMorphId(String part, Object original);
```

The result mappings and the request's collections are unmodifiable, and an unknown part id is an error. The body keeps its original morph/clip/anchor names;
part morph identities are `PartMorphId`, avoiding identity collisions caused by string concatenation; clips and anchors use `partId/name`.

The complete set of `Remap` methods:

| Method | Return value/behavior |
| --- | --- |
| `String id()`, `Model source()` | Source part identity and the shared Model |
| `Bone bone(Bone source)` | Output bone reference; an unknown source is an error |
| `Vertex vertex(Vertex source)`, `Mesh mesh(Mesh source)` | Output elements; null when hidden or unused |
| `Material material(Material source)` | Output material; an unknown source is an error |
| `Object morphId(Object source)` | Body original identity or the part's PartMorphId |
| `String boneName(String source)` | Output name of a source bone name; unknown names are kept |
| `String ikName(String source)` | Output chain name; the same shared chain may reuse the body name |
| `String animationName(String source)`, `String anchorName(String source)` | Output names |
| `int rigidBodyIndex(int sourceIndex)` | Index from the source physics table to the output table; -1 when the part excludes dynamics |
| `Pose pose(Pose source)` | Converts bone name, morph, IK and material frame references |
| `<T> AnimationSampler<T> sampler(AnimationSampler<T> source)` | Wraps the sampler, preserving duration, original interpolation and formula Namespace |
| `MorphType<?, ?, ?> morph(MorphType<?, ?, ?> source)` | Copies a morph for the assembly extension; returns null for a hidden target |

```java
Object fit = outfit.part("shirt").morphId("fit");
instance.getMorph().activateMorph(fit, 0.8f);
int clothBody = outfit.part("coat").rigidBodyIndex(2);
```

Vertex, UV, normal, Mesh, Bone, Material and Sprite/SpriteSet definitions are copied separately. Textures and immutable
raw metadata borrow the source resources, and Textures are deduplicated by object identity. Material and frame state stay independent between parts.
Do not modify the shared definitions of a cache result; each character's transform, morph, material frames, animation clock and physics state live in the ModelInstance.

Numeric/numeric-string Pose material references are remapped from source material indices to output Materials; invalid numeric references are ignored.
`MaterialResolver.forInstance` supports Material references that belong directly to the current MaterialSetInstance.
Custom named material references are passed through as-is, and the host can handle them with a custom MaterialResolver.

## Dynamics merging

Inheritance and fixed axes are converted to output Bones; identical constraints on the same output bone are reused, conflicting constraints are an error.
A shared IK whose controller, effector, links and all solver settings match reuses the body chain; otherwise a part-prefixed chain is generated.
Diamond closures are preserved; fully identical closures are reused, and conflicts and overlapping chain relationships continue to be validated by the generic Builder.
When a full-skeleton garment uses the body's existing dynamics, you can also pass `includeDynamics(false)` explicitly.

Physics coordinates and sizes are converted by each source's `Physics.unitScale`, and the output has `unitScale = (1,1,1)`; angles are still in radians.
Rigid bodies bound to the same output bone with all properties identical are reused; a rigid body with no bound bone is kept independently. Joint indices are remapped,
and fully identical joints are reused; a self-connection after mapping is an error. Bind-following bones are merged.
If any source with physics bodies requires a profile, the result requires an explicit profile; when any source needs affine writeback,
the combined physics uses affine writeback. profileRadiusScale must agree across non-empty sources, and a conflict is an error.
The profile and drag interfaces use output physics indices, and source indices are converted through Remap.

## ModelAssemblyCache

```java
new ModelAssemblyCache(); // 32 entries, 1_000_000 vertices
new ModelAssemblyCache(int maximumEntries, long maximumVertices);
new ModelAssemblyCache(int maximumEntries, long maximumVertices, ModelAssembler assembler);

ModelAssembly getOrAssemble(ModelAssembly.Request request);
int invalidate(Model source);
int invalidate(String partId);
void clear();
ModelAssemblyCache.Stats stats();
```

Both capacities must be positive. The LRU is limited by both the entry count and the vertex count of merged Models; the statistics are not byte sizes or GPU
memory usage. A single result that exceeds the vertex budget can still be used, but is not cached. All operations are synchronous, and concurrent requests assemble only once;
different misses also run serially, so the assembly service should be used on a loading/worker thread.

The cache key covers part order, the object identity and revision of source Models, bone mappings, the final hidden face set, the dynamics
switch, the same-name matching switch and the bind tolerance. Invalid resources/assembly exceptions are not stored in the cache.
`invalidate` returns the number of removed entries; `clear` drops references but keeps the cumulative statistics.
`Stats` provides `hits()`, `misses()`, `builds()`, `entries()`, `vertices()`; builds counts only successful assemblies.

The cache owns CPU shared definitions, has no GPU resources and needs no close. Invalidation/eviction does not modify already returned results; a host still using an
old ModelInstance is responsible for its render resource lifecycle. On a resource reload, stop the old instances and invalidate the corresponding cache entries first,
then release the borrowed Texture/metadata.

## Extension interfaces

```java
@FunctionalInterface
interface ModelAssembler {
    ModelAssembly assemble(ModelAssembly.Request request);
    static ModelAssembler standard();
    static ModelAssembler standard(MorphRemapper morphs);
}

@FunctionalInterface
interface MorphRemapper {
    MorphType<?, ?, ?> remap(MorphType<?, ?, ?> source, ModelAssembly.Remap mapping);
    static MorphRemapper standard();
}
```

The standard implementation supports the existing vertex position/normal/UV/tangent, bone transforms, material color/specular/ambient/rim, material frames/
sprite frames, Flip and nested Group morphs. Unknown morph types are an error; custom types copy their
references and parameters through a remapper, then fall back to the standard implementation. There is no model format allowlist.

```java
MorphRemapper defaults = MorphRemapper.standard();
ModelAssembler assembler = ModelAssembler.standard((morph, mapping) -> {
    // You can handle your own morph types here, remapping targets, identities and parameters.
    return defaults.remap(morph, mapping);
});
ModelAssemblyCache cache = new ModelAssemblyCache(32, 1_000_000, assembler);
```

A custom assembler must return a request equal to the input request, otherwise the cache refuses to store it. Resources should be read-only during assembly;
the remapper is used only in the assembly/extension copy phase, and playback uses the result's immutable mappings.

## Local real-asset probe and cache benchmark

```sh
./gradlew :modules:modelling:modelAssemblyProbe -PkasugaAssemblyFixtures=/absolute/path/to/extracted-fixtures
./gradlew :modules:modelling:renderGlTest -PkasugaAssemblyFixtures=/absolute/path/to/extracted-fixtures
```

This optional probe covers the current nine-pack test assets: the extracted directories are `pack_1` to `pack_9`, `pack_6/7` are base bodies and
`pack_3` is compatible dress clothing; it only calls the production PMX conversion and the generic assembly, and does not decode or display the asset textures.
It checks all PMX loading and base body/part bind compatibility, then verifies the compatible dress, the assembly of 1/10/100 real small parts,
neutral skinning positions, cache sharing and source invalidation. The stress case shares only bind-compatible bones, and the remaining bones keep the part namespace;
this cannot be read as 100 different garments having been fitted to the base body.
When RibbonDress short sleeves/long sleeves are present it also measures their assembly with a separate bind skeleton alongside the base body; when `pack_8/**/13.pmx` is present, hair is included.
For actually swapping textures and clothes, and rendering hair and an MC weapon see [real-asset example](../guides/wardrobe-example.md).

The default report is `build/reports/modelAssemblyProbe/report.json`; `kasugaAssemblyReport` can specify the output, and
`kasugaAssemblyOrder=cached-first` or the default `uncached-first` controls the measurement order, making it easy to repeat in an alternating order on a fresh JVM.
It records the first assembly, a rebuild after warm-up, a cache lookup for an existing request, building a request plus a cache lookup, and allocations on the current thread.
Timings exclude file loading and texture decoding; the p50/p95 of batch means differ from a per-call latency distribution. Allocations do not include GPU/native memory.
These numbers only verify CPU assembly and caching, and do not represent game FPS or a per-frame skinning/draw speedup.

The optional GL path compares CPU and production GPU skinning over all merged vertices of the compatible base body + dress, including real bind types and bone rotations;
it uses transform feedback and disables rasterization, and outputs no base body image. The success marker is `REAL_OVERLAY_SKINNING_PASS`.
