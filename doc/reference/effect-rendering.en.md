# Kasuga Custom Rendering Specification

接口、配置和调用参考。原理说明见 [effect-rendering.md](../explanation/effect-rendering.md)。

`RenderPipelineRegistrar` is the common registrar for Kasuga rendering resources. The registrar registers shaders, world pipelines, managed effects, and post-processing resources.

> **Language note**
>
> This document uses the writing principles in ASD-STE100, Issue 9. API names and product terms keep their official spelling.

## Terms

| Term | Definition |
| --- | --- |
| Registrar | An object that registers rendering resources for one owner. |
| Owner | A non-null resource ID that identifies the code or module that owns a registration. |
| Registration | An object that identifies and controls one registered entry. |
| Scope | A registrar for dynamic content. A scope closes the resources that the scope owns. |
| Descriptor | An immutable value that defines the configuration of a resource. |
| Handle | A stable object that supplies access to one exact shader registration. |
| Managed target | A render target that a post-process graph creates and releases. |

## Registration, Ownership, and Lifecycle

- Use `RenderPipelineRegistrar` as the common registration point for shaders, world pipelines, managed effects, post-process passes, and post-process graphs.

- `RegisterRenderPipelinesEvent` occurs one time on the client mod bus. A registrar from this event stays active for the lifetime of the client.

- Use `RenderPipelineScope` for dynamic content. A scope supplies the same registration methods as an event registrar.


- Each registrar has one non-null owner. Each registration and diagnostic snapshot keeps this owner.

The client registration event supplies a registrar for static content:

```java
@SubscribeEvent
public static void registerRendering(RegisterRenderPipelinesEvent event) {
    RenderPipelineRegistrar pipelines = event.registrar(
            ResourceLocation.fromNamespaceAndPath("example", "rendering")
    );
    installRendering(pipelines);
}
```

A dynamic module owns a scope. The module closes the scope during module unload:

```java
try (RenderPipelineScope scope = RenderPipelineScope.create(
        ResourceLocation.fromNamespaceAndPath("example", "script/session_42")
)) {
    installRendering(scope);
}
```

- `RenderPipelineScope.child(owner)` creates a child scope with an independent owner. When the parent scope closes, it also closes the child scope.

- A scope closes resources in the reverse order of registration. You can close a scope more than one time. A closed scope does not accept new resources.

- Each registration supplies `id()`, `owner()`, `isActive()`, and `close()`. A registration refers only to the entry that registration created. The registration does not follow a replacement that has the same ID.

- The shader registry and the world-pipeline registry apply ID uniqueness separately. World callbacks, managed effects, post-process passes, and graphs use world-pipeline IDs.

- `DuplicatePolicy.FAIL` rejects a registration if the ID is in use. The error identifies the current owner.

- `DuplicatePolicy.REPLACE` replaces the current entry. The replacement deactivates the old registration. Closing the old registration does not close the replacement.

## Pipeline Description and Execution

- `RenderPipelineDescriptor` is an immutable value. It contains an ID, a placement, a priority, and a `RenderDrawState`. The build fails if the placement is missing.

- A placement can be a Kasuga semantic `RenderPhase` or a native NeoForge `RenderLevelStageEvent.Stage`. A native placement does not create a semantic phase.

- `RenderPhase.POST_PROCESS` maps to the native `AFTER_LEVEL` stage. In this native stage, Kasuga uses this fixed order: `POST_PROCESS`, native `AFTER_LEVEL` placement, and semantic `AFTER_LEVEL`.

- At one execution position, a pipeline with a lower priority value runs first. If priority values are equal, Kasuga sorts the pipelines by ID string.

- The registry caches an immutable execution snapshot for each native stage. Registering, replacing, or closing an entry invalidates the applicable snapshot.

- A world-pipeline callback runs on the client render thread. Kasuga calls the callback only when a client world is present.

- Kasuga pushes a separate pose stack for each callback. Kasuga pops the pose stack after the callback returns or throws an exception.

- If one callback throws a runtime exception, Kasuga records the exception. Other pipelines in the same stage continue to run.

- If a callback starts a buffer batch, it must end the batch before it returns.

A world pipeline can use the compiled `RenderType` from its descriptor:

```java
RenderPipelineDescriptor descriptor = RenderPipelineDescriptor.builder(
                ResourceLocation.fromNamespaceAndPath("example", "energy_field"),
                RenderPhase.AFTER_PARTICLES
        )
        .priority(100)
        .draw(draw -> draw
                .vertexFormat(DefaultVertexFormat.POSITION_COLOR)
                .primitiveMode(VertexFormat.Mode.QUADS)
                .blend(PipelineBlendMode.ADDITIVE)
                .depthTest(PipelineDepthTest.LEQUAL)
                .cull(PipelineCullMode.DISABLED)
                .writeMask(PipelineWriteMask.COLOR))
        .build();

PipelineRegistration registration = pipelines.world(
        descriptor,
        context -> renderEnergyField(context, context.pipeline().renderType())
);
```

## Draw State and `RenderType`

- `RenderDrawState` is an immutable Minecraft draw-state description. You can reuse the draw state or derive a new value with `toBuilder()`.

- The default state uses `POSITION`, `QUADS`, a 1536-byte buffer, and the position shader. It has no texture and no blending. It also uses the `LEQUAL` depth test, culling, the main framebuffer, and color and depth writes.

- The buffer size must be greater than zero. Each state shard must be non-null.

- Use stable enumerations for blend, depth, cull, layering, target, and write-mask selections. A caller can also supply a native `RenderStateShard`.

- Each pipeline registration creates one `CompiledRenderPipeline`. Kasuga builds and caches the default `RenderType` when code first accesses the `RenderType`.

- Kasuga caches texture variants by the complete texture, blur, and mipmap combination. Kasuga caches a custom texture-state variant by the non-null name that the caller supplies.

- A `CompiledRenderPipeline` cache belongs only to its registration. A replacement registration receives a new cache.

## Shader Sources and Registrations

- `RenderShaderDescriptor` supports three shader sources: resource shaders, generated shaders, and custom factories.

- A generated fullscreen program uses `DefaultVertexFormat.BLIT_SCREEN` by default. A generated graphics program must specify a Minecraft vertex format.

- A descriptor uses `EAGER`, priority `0`, and `DISABLE_PIPELINE` by default.

- `ShaderRegistration` and `RenderShaderHandle` bind to one exact registration.

- A handle stays stable across F3+T reloads. A reload invalidates the internal `ShaderInstance`. The handle then installs a new generation.

- A handle also stores the default `ShaderParameterBlock` for exposed parameters. F3+T reloads do not change the parameter values. The values do not depend on the current `ShaderInstance`.

- `generation()` increases only after Kasuga installs a new compiled instance successfully.

- `get()` returns `null` if the shader is not available. `shader()` returns an empty optional. `require()` throws an exception.

- `status()` reports the current state, load origin, queue position, wait time, generation, prepare time, compile time, translation-cache result, and most recent error.

- The observable states are `REGISTERED`, `PREPARING`, `QUEUED`, `COMPILING`, `READY`, `FAILED`, and `CLOSED`.

- A registry lookup or preload by ID uses the current active registration for that ID. A registration or handle cannot access a replacement with the same ID.

## Shader Preparation, Compilation, and Reload

- After client setup, an `EAGER` shader starts generated-source preparation immediately. The shader finishes compilation and linking during the initial Minecraft `RegisterShadersEvent`. If registration occurs after resource-system startup, Kasuga puts the shader in the per-frame compile queue.

- A `DEFERRED` shader does not block a resource reload. Kasuga puts the shader in the per-frame compile queue after resources become available.

- A `MANUAL` shader does not enter a queue automatically. The shader enters a queue only after an explicit preload through its registration, handle, or registry.

- Translation from generated shader IR to GLSL and JSON is CPU preparation. OpenGL compilation and linking occur only on the render thread.

- Preparation uses a separate bounded executor with a queue capacity of 128. The executor does not use the JVM common pool. By default, the worker count is half the available CPU count, with a minimum of 1 and a maximum of 4. Client setup starts these workers.

- `ShaderPreparationScheduler.configureWorkers(0)` selects the automatic count. A positive value is a user request, and Kasuga limits it to the CPU count that the JVM can see. The startup option `-Dkasuga.shaderPreparationWorkers=N` supplies the same setting, and `0` selects automatic mode. In the client, `/kasuga_effects workers N` changes the setting. The change applies immediately to queued and future tasks.

- Preparation results enter an LRU translation cache with a maximum of 128 entries. Equal `ShaderProgram` values reuse the generated bundle and encoded Minecraft resources. Concurrent requests translate one program one time. Multiple workers can translate different programs in parallel.

- If the preparation queue is full, the registration enters the failed state. Kasuga does not run preparation synchronously on the calling thread.

- When a registration closes or is replaced, Kasuga cancels preparation that did not start. Kasuga also removes the task from the executor queue.

- By default, the per-frame compile queue checks a 2 ms budget before the next item starts. The queue starts a maximum of four items in one frame. An OpenGL compile cannot stop after compilation starts. Therefore, the actual frame time can be more than the budget.

- The compile queue selects items first by preload priority and then by queue order. A lower priority value runs first.

- In each frame, one owner can use the first two compile slots. Other waiting owners receive the remaining slots. The first owner receives remaining slots only when no other owner can run.

- `whenReady()` waits only for the exact registration. A `READY` state completes the future with `ShaderStatus`. A `FAILED` or `CLOSED` state completes the future exceptionally. Replacement also completes the future exceptionally. If the caller cancels the future, the registry removes the related waiter.

- If the shader is ready, `whenReady()` returns a completed future. Other asynchronous completions occur on the render thread.

- The ready, failure, and invalidated callbacks of `ShaderLoadListener` run on the render thread. If a listener throws an exception, Kasuga records the exception. The exception does not change the shader state.

- `DISABLE_PIPELINE` limits a load failure to the current shader. The shader stays unavailable, and other resources continue to load.

- `FAIL_RELOAD` stops an active resource reload if compilation fails. A late preload has no active reload to stop. The shader state becomes `FAILED`.

- Minecraft manages shaders that a resource reload creates. The registry manages shaders that a late preload creates. On the render thread, the registry releases a shader when the registration closes or is replaced. The registry also releases the old shader after successful installation of a later generation.


> Other pages of this topic: [Java Shader DSL Reference](shader-dsl.en.md) and [Managed Effects and Post-Processing](effects-and-postfx.en.md).
