# Managed Effects and Post-Processing

## Transformable Particle Instances and Group Control

Particles and managed effects use different lifecycle models:

- `ParticleInstance` is a passive, transformable render instance. A particle instance contains a complete `Transform`, velocity, color, and general per-instance attributes. `ParticleInstance` does not require a `tick()` implementation.

- `ParticleSource` is a continuous particle source. Application code can place and move the source. The emission rate uses particles per tick and supports fractional accumulation. Source settings control the particle factory, initial velocity, gravity, color, initial size, initial rotation, and lifetime. A source-setting change affects only new particles.

- `ParticleState` supplies one state value to `ParticleOperator`. The state contains a spatial `Transform`, velocity, color, attributes, and lifetime progress. `Transform` contains position, size, and rotation. Velocity is a separate physical value. `ParticleOperator` receives the current state and returns the next state.

- An external physics system can replace or atomically change the complete instance transform through `transform(...)`.

- `ParticleGroup` owns its instances and supplies two update methods. Both methods use two phases. The general method creates a stable snapshot for the complete group. The method then commits all changes. The packed-buffer method stores current and next states in direct buffers.

- `ParticleGroupBehavior` updates the complete group in one call. Use this behavior for smoke flow fields, rain, snow, explosions, and camera-relative weather volumes.

- `ParticleBufferGroupBehavior` updates the complete group in a reusable `ParticleInstanceBuffer`. A native-order direct `ByteBuffer` stores IDs, matrices, velocity, color, attributes, and flags. After the capacity becomes stable, tick and render operations do not create per-instance snapshot or update objects.

- `ParticleBehavior` is an optional behavior for one instance. The behavior reads the complete group snapshot from before the update. Use this behavior for fish, bird, and insect groups that need neighbor data.

- `ParticleGroupSnapshot.near(position, radius)` builds a spatial grid when the first query occurs. Queries with the same radius reuse the grid during one update. Therefore, Boids do not scan the complete group for each instance.

- If a group controller and instance behavior update one instance, the instance-behavior result has priority. A `null` result prevents an update of that instance.

- `ParticleBatchRenderer` receives a reusable `ParticleInstanceBuffer` for each call. Matrix, position, velocity, and color getters can write to a destination parameter. This parameter prevents temporary-object allocation. A callback renderer can expand instances into a Minecraft vertex buffer. `ParticleInstancedBatchRenderer` submits instances with hardware instancing.

- Kasuga clears groups and placed sources when the client changes or unloads a world. If an instance has no controller and no behavior, the runtime does not move the instance.

The following example registers a batch pipeline:

```java
ParticleRenderPipeline smoke = pipelines.particles(
        RenderPipelineDescriptor.builder(id("smoke"), RenderPhase.AFTER_PARTICLES)
                .draw(draw -> draw
                        .vertexFormat(DefaultVertexFormat.POSITION_COLOR)
                        .primitiveMode(VertexFormat.Mode.QUADS)
                        .blend(PipelineBlendMode.TRANSLUCENT))
                .build(),
        (instances, context) -> renderSmokeBatch(instances, context)
);

GasSmokePreset preset = new GasSmokePreset(GasSmokePreset.Settings.defaults());
smoke.bufferController(preset.bufferController());
preset.inject(source, 4.5f, new Vector3f(0, 0.18f, 0));
smoke.add(preset.createTracer(position, scale, color));
```

The following example combines physics and appearance operators in a placeable cube-smoke source:

```java
ParticleOperator cubeSmoke = ParticleOperators
        // Apply buoyancy, drag, and velocity integration.
        .physics(new Vector3f(0, 0.0035f, 0), 0.985f)
        // Control size, rotation, and opacity in the same state function.
        .then(ParticleOperators.scale(1.012f))
        .then(ParticleOperators.rotate(new Vector3f(0.006f, 0.018f, 0.004f)))
        .then(ParticleOperators.fade(0.975f));

ParticleSource source = smoke.source(ParticleSource.Settings.builder()
        .position(new Vector3f(12, 70, -4))
        .emissionRate(0.75f)
        .particleType(spawn -> ParticleInstance.builder(spawn.transform())
                // The renderer can use this attribute to select a cube, sprite, or custom mesh.
                .velocity(spawn.velocity())
                .color(spawn.color())
                .attributes(CUBE_PARTICLE_TYPE)
                .build())
        .initialVelocity(new Vector3f(0, 0.035f, 0))
        .affectedByGravity(false)
        .size(0.35f)
        .rotation(new Vector3f(0, 0.25f, 0))
        .color(new Vector4f(0.62f, 0.62f, 0.62f, 0.48f))
        .lifetimeTicks(100)
        .operator(cubeSmoke)
        .build());

// The source can follow a block, entity, or script anchor.
source.position(nextPosition);
```

`ParticleOperators.transform(...)` accepts a `UnaryOperator<Transform>`. The operator receives the current transform and returns the next transform. A gravity-affected particle uses the default `integrate()` and `.affectedByGravity(true)`. The source `particleType` is an instance factory. The renderer of the related `ParticleRenderPipeline` selects cube, billboard, or mesh drawing.

The default particle type uses the source settings directly. A custom factory can use `spawn.sequence()` for stable random values. The factory can change position, velocity, size, or color during construction.

Hardware instancing uses a backend-independent mesh and draw contract:

```java
RenderShaderHandle shader = pipelines.shader(RenderShaderDescriptor.generated(
        ParticleInstanceShaderPrograms.colored("example:liquid_instances"),
        DefaultVertexFormat.POSITION
)).handle();

ParticleInstanceMesh mesh = new ParticleInstanceMesh(
        ParticleInstanceMesh.Topology.TRIANGLES,
        trianglePositions
);
ParticleBatchRenderer renderer = new ParticleInstancedBatchRenderer(
        mesh,
        new OpenGlParticleInstanceBackend(shader)
);
```

- `ParticleInstanceMesh`, `ParticleInstanceBuffer`, `ParticleInstanceShaderPrograms`, and `ParticleInstanceRenderBackend` do not contain VAO, VBO, or LWJGL types.

- An alpha-blending renderer can reuse `ParticleDepthSorter`. The sorter submits instances from far to near. The sorter enables the depth test and disables depth writes. For per-particle face sorting, a quad pipeline can use `sortOnUpload(true)`. `pipeline.sortBackToFront(true)` reorders the packed instance buffer before rendering. Therefore, callback renderers and hardware-instanced renderers use the same transparent sorting.

- The current `OpenGlParticleInstanceBackend` owns all OpenGL resources, attributes, and `glDrawArraysInstanced` calls. The base mesh uses one static VBO. The packed instance buffer uses one stream VBO. The stream VBO expands when necessary and updates each frame. One instance group uses one draw submission.

- Kasuga Shader IR describes the matrix and color semantics of an instance. The particle API does not contain GLSL. A Vulkan backend can reuse the mesh, packed layout, simulation, and Shader IR. The Vulkan backend replaces the `ParticleInstanceRenderBackend` implementation and its shader compiler.

- The current standard layout uses 144 bytes for each instance. The layout exposes the four columns of a `mat4` and color to the GPU. Velocity and attributes stay in the packed buffer for simulation. The standard shader does not read velocity or attributes.

- GPU depth sorting for transparent instances is not currently available. For an effect that needs exact transparent order, use additive blending, weighted OIT, or an instance-sorting method before upload.

Built-in behavior presets:

| Preset | Update method | Use |
| --- | --- | --- |
| `SmokePlumePreset` | Group controller | Analytic rise, disturbance, expansion, fade, and recycling |
| `GasSmokePreset` | Packed-buffer controller | Density and velocity volume, buoyancy, diffusion, pressure projection, and tracer advection |
| `LiquidFlowPreset` | Packed-buffer controller | Low-viscosity incompressible velocity field, gravity, pressure projection, and tracer advection |
| `RainFieldPreset` | Group controller | Wind-driven fall, camera-relative or world-relative volume, and out-of-range reuse |
| `BoidsPreset` | Optional instance behavior | Separation, alignment, cohesion, and group bounds |

`GasSmokePreset` and `LiquidFlowPreset` share `StableFluidGrid3D`. Semi-Lagrangian advection, Gauss-Seidel diffusion, and pressure projection use preallocated direct `FloatBuffer` values. This implementation is a Stable Fluids approximation for real-time effects. The primary computational complexity is `O(gridSize^3 * iterations)`. Gas uses the default closed solver boundary. Liquid uses an open boundary; after a tracer leaves the finite solver grid it keeps its world-space momentum and gravity instead of being clipped to the simulation volume. A `FluidTracerCollision3D` remains active in world coordinates outside that grid. The Minecraft block adapter implements this interface with swept collision-shape queries in loaded chunks, so unrestricted tracers continue to collide and slide against blocks without forcing chunk loads.

To increase simulation detail:

1. Increase the tracer count.
2. If more detail is necessary, increase the grid resolution.

`FluidEnvironment3D` combines fluid-environment constraints. Each simulation step applies the constraints before the pressure solver starts:

```java
liquid.environment(FluidEnvironment3D.builder()
        .add(FluidConstraints3D.solidBox(minimum, maximum))
        .add(FluidConstraints3D.solidSphere(center, radius))
        .add(FluidConstraints3D.directionalForce(regionMin, regionMax, acceleration))
        .add(FluidConstraints3D.source(regionMin, regionMax, densityPerSecond, velocity))
        .add(FluidConstraints3D.drain(regionMin, regionMax, densityRetention, acceleration))
        .build());
```

Application code can implement `FluidConstraint3D` for a custom dynamic constraint. The constraint context supplies normalized cell coordinates, density, velocity, and the solid mask. Application code can generate constraints from block collision shapes, pipe networks, or moving force fields. The solver and preset do not depend on the Minecraft world.

The following example connects Minecraft block collisions through a separate adapter:

```java
MinecraftBlockFluidConstraint blocks =
        new MinecraftBlockFluidConstraint(liquidHalfExtents, 4);
blocks.level(clientLevel);
blocks.center(simulationCenter);

liquid.environment(FluidEnvironment3D.builder()
        .add(blocks)
        .add(otherConstraints)
        .build());
```

- The adapter scans real block `VoxelShape` values in loaded chunks of the simulation volume. The adapter rasterizes stairs, slabs, fences, and other collision boxes into solid cells. The adapter does not treat all non-air blocks as full cubes.

- Collision boxes align with fluid-cell centers. This alignment prevents a wall from expanding outward by half a cell. A thinner collision box maps to the cell nearest the center of the collision box. Therefore, thin objects such as fences remain in the solid mask.

- The solver clears density and velocity in solid cells. In adjacent fluid cells, the solver clamps velocity that points into a solid cell. Pressure projection and advection then move around the block. If a coarse-grid step moves a liquid tracer into a solid cell, the tracer returns to its previous position.

- Scan results stay in a cache for the configured game-tick interval. The adapter rebuilds the results after volume movement, level changes, or an `invalidate()` call. The adapter skips unloaded chunks. Fluid simulation does not force chunk loading.

- Collision operates only from blocks to fluid. Simulated water does not push pistons or entities. The simulation does not wet blocks or change vanilla waterlogging. The simulation does not place or remove Minecraft fluid blocks.

In this API, liquid means an incompressible velocity and density volume. The solid mask is an approximate no-slip obstacle. The model excludes free surfaces, surface tension, drop merging, and conservative FLIP or PIC particles. The model also excludes two-way reactions between fluid and rigid bodies. The model supports visual effects such as water flow, magic liquid, and pipe flow. The model is not a complete water solver.

The following commands control particle presets in the development client:

| Command | Result |
| --- | --- |
| `/kasuga_particle_preset smoke` | Starts the smoke preset. |
| `/kasuga_particle_preset cube_smoke` | Starts the cube-smoke preset. |
| `/kasuga_particle_preset liquid` | Starts the liquid preset. |
| `/kasuga_particle_preset rain` | Starts the rain preset. |
| `/kasuga_particle_preset boids` | Starts the Boids preset. |
| `/kasuga_particle_preset all` | Starts all presets. |
| `/kasuga_particle_preset clear` | Stops and clears all presets. |
| `/kasuga_particle_preset status` | Shows the instance count for each group. |

Normal smoke ray-marches a turbulent three-dimensional density field inside each instanced cube and integrates opacity along the view ray. The cube is only a volume bound; the fragment shader discards empty samples, so no billboard or solid cube surface is visible. Cube smoke enables back-face culling. Cube smoke also adds stable variation to the position, velocity, and color of each particle. These settings prevent repeated blending of transparent front and back faces.

## Managed Effects

- `EffectRenderPipeline` controls effect-instance ticks, lifetime checks, frustum culling, optional far-to-near sorting, and renderer calls.

- When the client is not paused and a world is present, Kasuga updates each active effect one time in each client tick.

- Kasuga removes an effect that is not alive before or after the effect tick. If the tick throws a runtime exception, Kasuga removes only the related instance. Kasuga also records the exception.

- Render traversal uses interpolated bounds for frustum culling. When sorting is enabled, Kasuga sorts effects from far to near. The sort uses the distance between the interpolated position and the camera. Kasuga calculates this distance one time for each visible effect in one frame. An effect can override `distanceToSqr(partialTick, observer)` to prevent allocation of a temporary position object.

- The renderer calls `begin` and `end` only if at least one instance is visible. The renderer calls each method one time for each pipeline in one frame.

- If one instance throws a render exception, Kasuga records the exception and continues with the other instances. Kasuga does not remove the failed instance automatically.

- `spawn()`, `clear()`, and `EffectHandle.remove()` accept calls from different threads. Kasuga applies list changes during the next client tick or render traversal.

- `EffectHandle.remove()` returns `true` only for the first successful removal.

- When the client changes or unloads a world, Kasuga clears all instances from all effect pipelines. Kasuga also clears the post-process target pool. Kasuga does not tick effects while the client is paused.

- After an effect pipeline closes, the pipeline rejects new instances. The closed pipeline does not participate in tick or render operations.

Instances of one effect type share a renderer and pipeline state. A separate handle controls each instance. The following example spawns and removes one effect:

```java
RenderPipelineDescriptor sparkDescriptor = RenderPipelineDescriptor.builder(
        ResourceLocation.fromNamespaceAndPath("example", "sparks"),
        RenderPhase.AFTER_PARTICLES
).priority(110).build();

EffectRenderPipeline<SparkEffect> sparks = pipelines.effects(
        sparkDescriptor,
        true,
        new SparkEffectRenderer()
);

EffectHandle<SparkEffect> spark = sparks.spawn(new SparkEffect(position));
spark.remove();
```

## Post-Process Passes

- A post-process pass must use the semantic `RenderPhase.POST_PROCESS`. Registration rejects all other placements.

- A pass always runs on the render thread. Kasuga binds the main render target after the pass ends or throws an exception.

- A target descriptor supports a size relative to the screen or a fixed size. The descriptor also supports an optional depth attachment, nearest or linear filtering, and a clear color. The resolved width and height are a minimum of one pixel.

- The target pool reuses a target that has the same physical ID. A descriptor or resolved-size change destroys the old target. The target pool then creates a new target.

- A color copy fails when the source and destination are the same target. A depth copy fails if either target has no depth attachment.

- A fullscreen draw accepts only a `BLIT_SCREEN` shader. The draw saves and restores the GL state.

- A fullscreen draw rejects color or depth sampling from the current output target. This restriction prevents framebuffer feedback. The caller must ensure that a directly bound external texture ID cannot cause feedback.

## Post-Process Graphs

- A graph ID must match the ID of the related pipeline descriptor.

- During graph construction, Kasuga rejects duplicate targets, duplicate passes, and undeclared targets. Kasuga rejects a managed-target read without a producer and multiple writers to a managed target. Kasuga also rejects missing explicit dependencies and dependency cycles.

- Each pass must declare at least one output. One pass must not read and write the same target.

- The main target is external. Multiple passes can write to the main target. All other targets are graph-managed targets.

- Pass order first satisfies explicit `after` dependencies. Pass order also satisfies dependencies from read and write relations. Of all available passes, a pass with a lower priority value runs first. If priority values are equal, Kasuga sorts the passes by pass ID.

- `prepare` runs before managed-target allocation and pass execution. If `prepare` returns `false`, Kasuga does not allocate targets or run passes in that frame.

- Each graph execution creates a new `PostProcessGraphFrame`. The current `prepare` call and subsequent passes share the frame data. A read uses the declared type. The read throws an exception if data is missing or has a different type.

- A pass context can read or write only the targets that the pass declares. Access to an undeclared target fails at runtime.

- Kasuga converts each logical graph target to a physical ID that contains the graph ID. Therefore, equal logical names in different graphs do not conflict. Kasuga calculates physical IDs and substituted target descriptors during graph construction. Graph execution reuses these values.

- Managed targets use reference counts for each graph registration. When the last graph registration closes, Kasuga releases the target on the render thread.

- If `prepare` throws an exception, Kasuga adds the graph ID and throws the exception again. For a pass exception, Kasuga adds the pass ID. The world-pipeline dispatcher records the exception. Later pipelines continue to run.

- `raw()` bypasses graph resource-access limits. After a `raw()` call, the caller is responsible for resource declarations and feedback safety.

This graph first saves the main image. Then, a fullscreen shader composites the image back to the main target:

```java
ResourceLocation graphId = ResourceLocation.fromNamespaceAndPath("example", "screen_tint");
PostProcessGraphTarget scene = PostProcessGraphTarget.managed(
        ResourceLocation.fromNamespaceAndPath("example", "scene")
);

PostProcessGraph graph = PostProcessGraph.builder(graphId)
        .target(PostProcessTargetDescriptor.builder(scene.id())
                .screenScale(1.0f)
                .filter(PostProcessTargetDescriptor.TextureFilter.LINEAR)
                .build())
        .pass(PostProcessGraphPassDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath("example", "capture"),
                        context -> context.copyColor(PostProcessGraphTarget.main(), scene, true)
                )
                .readsMain()
                .writes(scene)
                .build())
        .pass(PostProcessGraphPassDescriptor.builder(
                        ResourceLocation.fromNamespaceAndPath("example", "composite"),
                        context -> context.fullscreen(PostProcessGraphTarget.main(), shader.handle())
                                .colorSampler("SceneSampler", context.read(scene))
                                .draw(false)
                )
                .reads(scene)
                .writesMain()
                .build())
        .build();

PostProcessGraphRegistration graphRegistration = pipelines.graph(graph, 100);
```

## Ready-to-Run PostFX Presets

`PostFxPresetDemo` uses one generated shader registration that stays active across reloads. Parameter blocks select different color settings. Preset selection does not register or compile the shader again.

| Preset | Development-client command |
| --- | --- |
| Cinematic color | `/kasuga_postfx cinematic` |
| High-contrast monochrome | `/kasuga_postfx noir` |
| High-saturation vivid color | `/kasuga_postfx vivid` |
| Off | `/kasuga_postfx off` |

`/kasuga_effects` shows the graph, shader state, and adjustable parameters.

## Diagnostics and Parameter Interface

- `/kasuga_effects` opens the client diagnostics and parameter interface. The interface does not pause the game.

- The Effects page shows the owner, pipeline ID, effect type, active count, visible count, and most recent render time.

- The Shaders page shows the owner, source, state, policy, priority, origin, generation, queue wait, preparation time, compile time, cache result, exposed-parameter count, and failure information.

- The shader summary shows the requested and actual worker counts for the preparation executor. The summary shows the available CPU count. The summary also shows the queue state of the preload scheduler on the render thread.

- `Parameters` opens the general schema parameter editor. The editor has no special logic for built-in effects or the black-hole example.

- The Pipelines page shows the owner, placement, priority, draw mode, buffer size, and number of cached `RenderType` variants.

> Other pages of this topic: [Custom Rendering Specification](effect-rendering.en.md) and [Java Shader DSL Reference](shader-dsl.en.md).
