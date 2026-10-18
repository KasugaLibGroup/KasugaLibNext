# Kasuga Custom Rendering Specification

接口、配置和调用参考。原理说明见 [EFFECT_RENDERING.md](../doc/EFFECT_RENDERING.md)。

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

## Java Shader DSL

- The DSL does not parse a Java AST and does not decompile a lambda. A builder lambda runs immediately in Java and creates typed shader IR.

- Java conditions and loops control only IR construction. The final shader does not contain these Java structures. Use the shader builder for conditions and loops that must stay dynamic in GLSL.

- The backend inserts an expression from a Java variable at each use location. The expression does not automatically create a GLSL local variable.

- A `let*` call creates an initialized GLSL local variable. For later assignments, use a `local*` call that returns `ShaderVariable`.

- A fullscreen program contains only a fragment module and uses the built-in fullscreen vertex stage.

- A graphics program contains a vertex module and a fragment module. `position(...)` writes to `gl_Position`.

- Each fragment input must have a vertex output with the same name and type. Otherwise, program construction fails.

- A uniform or sampler with the same name in both stages must have an identical declaration. An identical declaration occurs only one time in the Minecraft shader JSON.

- A program ID must use the lowercase `namespace:path` resource-ID format.

- The current profile supports float, int, bool, vec2, vec3, vec4, mat2, mat3, mat4, and sampler2D. The profile also supports float uniform arrays and named structs. The profile supports stage inputs and outputs, local variables, structured control flow, common mathematical operations, and texture sampling.

- The current backend generates GLSL 150 and Minecraft core-shader JSON.

- During loading, a runtime-generated bundle has priority for its own `.vsh`, `.fsh`, and `.json` files. For a missing resource or import, loading uses the current client `ResourceProvider`.

- The DSL checks the stage interface of a graphics program during Java construction. The caller must match the `RenderShaderDescriptor` vertex format to the attributes.

## User-Adjustable Shader Parameters

A shader does not expose all uniforms automatically. Register each user-adjustable parameter. Each parameter has a fixed `name`, `description`, `ShaderParameterType`, inclusive `range`, and `defaultValues`. The parameter name is also the GLSL uniform name. The name must be a valid, non-reserved GLSL identifier.

```java
public static final ShaderParameter EXPOSURE = ShaderParameter.floatParameter(
        "Exposure",
        "Controls the final output exposure",
        1.0f,  // default
        0.0f,  // minimum
        4.0f   // maximum
);

public static final ShaderParameter TINT = ShaderParameter.builder(
                "Tint",
                "Multiplies the output color",
                ShaderParameterType.COLOR_RGB
        )
        .range(0.0, 1.0)
        .defaultValues(1.0f, 1.0f, 1.0f)
        .build();
```

In a generated shader, use the applicable `expose*` call to declare a uniform and register the parameter specification. A `uniform*` call keeps the uniform internal. The call does not add the uniform to the public schema.

```java
ShaderProgram program = ShaderProgram.fullscreen("example:adjustable", shader -> {
    FloatExpr exposure = shader.exposeFloat(EXPOSURE);
    Vec3Expr tint = shader.exposeVec3(TINT);
    Vec3Expr color = shader.sampler2D("SceneSampler").sample(shader.texCoord()).rgb();
    shader.fragmentColor(shader.vec4(color.mul(tint).mul(exposure), shader.f32(1.0f)));
});
```

A resource shader or custom factory cannot use the DSL. Register the same specification on `RenderShaderDescriptor`. The resource uniform must match the parameter `name` and basic GLSL type. The first bind fails if the uniform is missing.

```java
RenderShaderDescriptor descriptor = RenderShaderDescriptor.builder(id, format)
        .resource()
        .expose(EXPOSURE)
        .expose(TINT)
        .build();
```

`RenderShaderHandle.parameters()` supplies the default runtime parameter block for the registration. `ShaderParameterBlock` validates each change for component count, numeric type, finite values, and range. A parameter change does not regenerate or compile the shader. `reset(name)` uses the declared default. `resetAll()` restores all declared defaults.

```java
RenderShaderHandle handle = registration.handle();
handle.parameters().setFloat(EXPOSURE, 1.35f);
handle.parameters().set(TINT, 1.0, 0.55, 0.2);
```

When a fullscreen method receives a handle, Kasuga uploads the default parameter block before the draw. The applicable methods are `PostProcessContext.fullscreen(...)` and `PostProcessGraphContext.fullscreen(...)`. Kasuga cannot infer a registration from a direct `ShaderInstance`. Use `.parameters(block)` to bind a parameter block. Use `handle.createParameterBlock()` for each independent configuration of one shader.

The current `ShaderParameterType` values include float, integer, boolean, vec2, vec3, vec4, RGB color, and RGBA color. `ShaderParameterType` also includes mat2, mat3, and mat4. The range applies to all components. Boolean parameters always use `[0, 1]`.

To open the parameter panel:

1. Enter `/kasuga_effects` in the client.
2. Select `Parameters`.

The schema generates the parameter panel. The panel is not specific to one effect. The panel shows each shader that exposes parameters. A boolean parameter uses a switch. Other parameter types use component sliders.

Vectors, colors, and matrices use the applicable component names. A parameter change applies during the next draw. The change does not regenerate or recompile the shader.

Kasuga saves the default parameter blocks asynchronously in `config/kasuga_lib/shader-parameters.json`. After a client restart, Kasuga restores values by shader ID and parameter name. An independent block from `createParameterBlock()` contains effect-instance state. Kasuga does not store this block in the global configuration.

The configuration stores only values that override schema defaults. A reset permits a later schema default to apply. Use `isDefault(name)` and `hasOverrides()` to examine the current state.

The following client commands examine, change, and reset exposed shader parameters. Use a command when a slider cannot set a large integer accurately. Also use a command to enter a complete vector or matrix:

```text
/kasuga_effects parameter list <shader-id>
/kasuga_effects parameter set <shader-id> <name> <value...>
/kasuga_effects parameter reset <shader-id> <name>
```

Spaces or commas separate vector and matrix components. The built-in black-hole example exposes `LensingScale`, `DiskBrightness`, and `ChromaticScale`. The screen, time, and per-effect packed uniforms stay internal.

The following example builds and registers a fullscreen shader from Java expressions:

```java
ShaderProgram program = ShaderProgram.fullscreen("example:screen_tint", shader -> {
    var scene = shader.sampler2D("SceneSampler");
    var tint = shader.uniformVec3("Tint", 1.0f, 1.0f, 1.0f);
    var source = scene.sample(shader.texCoord());
    shader.fragmentColor(shader.vec4(source.rgb().mul(tint), source.a()));
});

ShaderRegistration shader = pipelines.shader(
        RenderShaderDescriptor.generated(program)
                .withPreload(ShaderPreloadPolicy.DEFERRED, 50)
);

shader.whenReady().thenAccept(status -> rebuildDependentResources(shader.handle()));
```

## Migration from GLSL to the Java Shader DSL

A builder lambda represents the body of the GLSL `main()` function. The lambda does not calculate pixel results. The lambda records declarations, expressions, and statements in call order. The backend then generates GLSL 150.

### Types and Expressions

The Java DSL uses expression-wrapper types in place of GLSL operators. Use `var` for an intermediate expression when the expression type is clear. The Java compiler checks the exact type.

| GLSL | Java DSL |
| --- | --- |
| `float`, `int`, `bool` | `FloatExpr`, `IntExpr`, `BoolExpr` |
| `vec2`, `vec3`, `vec4` | `Vec2Expr`, `Vec3Expr`, `Vec4Expr` |
| `mat2`, `mat3`, `mat4` | `Mat2Expr`, `Mat3Expr`, `Mat4Expr` |
| `sampler2D` | `Sampler2DExpr` |
| `struct Material` | `ShaderStructType`, `StructExpr` |
| `1.0`, `1`, `true` | `shader.f32(1)`, `shader.i32(1)`, `shader.bool(true)` |
| `vec2(x, y)` | `shader.vec2(x, y)` |
| `vec4(rgb, alpha)` | `shader.vec4(rgb, alpha)` |
| `a + b`, `a - b` | `a.add(b)`, `a.sub(b)` |
| `a * b`, `a / b` | `a.mul(b)`, `a.div(b)` |
| `a < b`, `a >= b`, `a == b` | `a.lt(b)`, `a.gte(b)`, `a.equalTo(b)` |
| `a && b`, `a || b`, `!a` | `a.and(b)`, `a.or(b)`, `a.not()` |
| `texture(image, uv)` | `image.sample(uv)` |
| `normalize(v)`, `length(v)` | `v.normalize()`, `v.length()` |
| `mix(x, y, t)` | `x.mix(y, t)` |
| `atan(y, x)` | `y.atan2(x)` |
| `matrix * vector` | `matrix.transform(vector)` |
| `left * right` (matrix) | `left.mul(right)` |
| `value.rgb`, `value.a` | `value.rgb()`, `value.a()` |
| `Values[index]` | `values.get(index)` |
| `float(integer)` | `integer.toFloat()` |

Only a method from the applicable `Expr` type can generate code. For example, the current `Vec2Expr` supplies `normalize()`. Other vector wrappers do not always supply the same method. If an expression does not accept a Java number, use `f32()`, `i32()`, or an applicable primitive overload.

### Globals, Stage Inputs, and Stage Outputs

- `uniformFloat`, `uniformInt`, `uniformBool`, `uniformVec*`, and `uniformMat*` declare GLSL uniforms. Supplied values become defaults in the Minecraft shader JSON. A matrix overload without a default uses an identity matrix.

- `uniformFloatArray` is the only current method for uniform arrays. The length must be greater than 1. Use an `IntExpr` to index an element.

- `sampler2D` declares `uniform sampler2D`.

- `inputFloat`, `inputInt`, and `inputVec*` declare `in` values for the current stage.

- In a vertex stage, `outputFloat` and `outputVec*` declare `out` values. `position(...)` writes to `gl_Position`.

- In a fragment stage, `fragmentColor(...)` writes to the generated `fragColor`.

- A fullscreen fragment can use `texCoord()` to read the texture coordinates from the built-in vertex stage.

- A graphics fragment must use a method such as `inputVec2` to declare the fragment inputs. A call to `texCoord()` fails.

- A vertex program must call `position(...)` at least one time. A fragment program must call `fragmentColor(...)` at least one time. Validation only confirms that the IR contains a write. The shader author must write to the required output on all runtime paths.

The two stages in a graphics program represent the `.vsh` and `.fsh` files:

```java
ShaderProgram particle = ShaderProgram.graphics(
        "example:particle",
        vertex -> {
            var position = vertex.inputVec3("Position");
            var uv = vertex.inputVec2("UV0");
            var color = vertex.inputVec4("Color");
            var modelView = vertex.uniformMat4("ModelViewMat");
            var projection = vertex.uniformMat4("ProjMat");

            vertex.outputVec2("particleUv", uv);
            vertex.outputVec4("particleColor", color);
            vertex.position(projection.transform(modelView.transform(
                    vertex.vec4(position, vertex.f32(1))
            )));
        },
        fragment -> {
            var uv = fragment.inputVec2("particleUv");
            var color = fragment.inputVec4("particleColor");
            var radial = fragment.f32(1).sub(
                    uv.sub(fragment.vec2(0.5f, 0.5f)).length().mul(2).min(1)
            );
            fragment.fragmentColor(fragment.vec4(
                    color.rgb().mul(radial), color.a().mul(radial)
            ));
        }
);

RenderShaderDescriptor descriptor = RenderShaderDescriptor.generated(
        particle,
        DefaultVertexFormat.POSITION_TEX_COLOR
);
```

The `Position`, `UV0`, and `Color` input names and types must match the supplied Minecraft `VertexFormat`. The DSL checks the vertex-to-fragment interface, but it does not check the `VertexFormat`.

### Local Variables and Control Flow

A Java variable holds one IR expression. In this example, `brightness` does not create a GLSL local variable. The backend inserts the `brightness` expression at the use location:

```java
var brightness = color.rgb().mul(intensity);
shader.fragmentColor(shader.vec4(brightness, color.a()));
```

`letFloat/letVec*`, `letMat*`, and `letStruct` create GLSL local variables that are read-only in the DSL. For later assignments, use the applicable `local*` method. `get()` reads the variable. `set()` writes the variable.

This GLSL:

```glsl
float sum = 0.0;
for (int index = 0; index < Count; ++index) {
    if (index >= 8) break;
    if (Values[index] <= 0.0) continue;
    sum = sum + Values[index];
}
fragColor = vec4(sum, sum, sum, 1.0);
```

The equivalent Java DSL is:

```java
var count = shader.uniformInt("Count", 0);
var values = shader.uniformFloatArray("Values", 8, 0.0f);
var sum = shader.localFloat("sum", shader.f32(0));

shader.forRange("index", shader.i32(0), count, index -> {
    shader.ifThen(index.gte(shader.i32(8)), shader::breakLoop);
    shader.ifThen(values.get(index).lte(shader.f32(0)), shader::continueLoop);
    sum.set(sum.get().add(values.get(index)));
});

shader.fragmentColor(shader.vec4(sum.get(), sum.get(), sum.get(), shader.f32(1)));
```

- `ifThen` and `ifThenElse` generate shader branches. A Java `if` runs one time during IR construction.

- `forRange(name, start, end, body)` generates an integer loop. The loop increments by 1 and uses `< end` as the end condition.

- `whileLoop(condition, body)` generates `while`. `doWhile(condition, body)` generates `do while`.

- `breakLoop()` can exit only the innermost loop. You can use `continueLoop()` in a loop.

- A Java `for` can unroll an expression for constant values. The Java loop does not generate a GLSL loop.

- If a shader value changes across branches or loops in a Java lambda, use `ShaderVariable`. Do not rebind a Java local variable to represent a shader assignment.

```java
var cursor = shader.localInt("cursor", shader.i32(0));

shader.whileLoop(cursor.get().lt(count), () -> {
    cursor.set(cursor.get().add(1));
    shader.ifThen(cursor.get().gte(shader.i32(64)), shader::breakLoop);
});

shader.doWhile(cursor.get().gt(shader.i32(0)), () ->
        cursor.set(cursor.get().sub(shader.i32(1)))
);
```

### Structs

A struct is a named type. The `ShaderStructType.Builder` that creates a field owns the field key. You cannot use the field key with a different struct. This restriction applies when the other struct has a field with the same name and type.

Declare a dependency struct before you declare the struct that contains the dependency. The current DSL supports structs for typed locals, construction, field reads, and field assignments. The DSL does not support structs for uniforms, stage inputs, or stage outputs.

```java
var materialBuilder = ShaderStructType.builder("Material");
var tint = materialBuilder.vec3Field("tint");
var roughness = materialBuilder.floatField("roughness");
var materialType = materialBuilder.build();

shader.declareStruct(materialType);
var material = shader.localStruct("material", shader.structValue(
        materialType,
        shader.vec3(1.0f, 0.8f, 0.6f),
        shader.f32(0.5f)
));

shader.setStructField(material, roughness, shader.f32(0.25f));
var finalTint = material.get().field(tint);
```

`structValue` arguments must follow the order of field declarations. The builder checks the argument count and types during Java construction.

### Switch Statements

`switchOn` accepts an `IntExpr`. `caseOf` keeps declaration order and does not insert `break`. Therefore, `caseOf` permits GLSL fallthrough. Call `breakSwitch()` to end a case. The generated output puts `defaultCase` after all cases.

```java
shader.switchOn(mode, cases -> cases
        .caseOf(0, () -> {
            intensity.set(shader.f32(0.25f));
            shader.breakSwitch();
        })
        .caseOf(1, () -> intensity.set(shader.f32(0.75f))) // fallthrough
        .defaultCase(() -> intensity.set(shader.f32(1.0f)))
);
```

For `breakLoop()` and `breakSwitch()`, the applicable structure must be the innermost break target. This rule prevents an invalid bare `break`. A case in a loop can call `breakSwitch()`. The case cannot call `breakLoop()` directly. To exit the outer loop, set a boolean local variable. Test the variable after the switch.

### Naming and Link Rules

- A shader program ID must use lowercase `namespace:path`.

- A shader identifier must match `[A-Za-z_][A-Za-z0-9_]*`. It must not start with `gl_`, and it must not be a reserved GLSL word.

- Globals, locals, and loop indexes in one stage share one name set. A name cannot occur more than one time in the complete stage. Separate code blocks do not create reusable name scopes.

- A fragment input must have a vertex output with the same name and type. A vertex can have additional outputs that the fragment does not read.

- If both stages use a uniform or sampler with the same name, its storage, type, array length, and default value must be identical.

- Names are case-sensitive. The final GLSL and Minecraft JSON use these names without changes.

### Runtime Uniforms and Samplers

F3+T replaces the current `ShaderInstance`. For a long-life object:

1. Store only a `RenderShaderHandle`.
2. During each render, access the current generation through the handle or pass context.

- On the render thread, update a uniform through `currentShader.safeGetUniform(name).set(...)`.

- In a fullscreen pass, use `colorSampler`, `depthSampler`, or `textureSampler` to bind a sampler.

To upload a float array:

1. Set the `float[]` capacity to the length in the shader declaration.
2. Upload the complete `float[]`.

- A uniform default in the DSL generates a JSON default. It does not overwrite a current value that Java uploads each frame.

```java
context.fullscreen(PostProcessGraphTarget.main(), shaderHandle)
        .colorSampler("SceneSampler", context.read(scene))
        .uniforms(active -> active.safeGetUniform("Tint").set(red, green, blue))
        .draw(false);
```

### Raw GLSL Escape

Use a raw escape for GLSL that the typed API cannot represent. All raw entry points have `@DelicateShaderApi`. This annotation identifies code for additional review. The annotation does not guarantee API stability. The Java compiler does not validate raw GLSL.

```java
@DelicateShaderApi
static ShaderProgram derivativeShader() {
    return ShaderProgram.fullscreen("example:derivative", shader -> {
        shader.rawPreamble("#extension GL_ARB_gpu_shader5 : enable");
        shader.rawDeclaration("""
                float saturate(float value) {
                    return clamp(value, 0.0, 1.0);
                }
                """);

        var edge = shader.rawFloat("dFdx(texCoord.x)");
        shader.rawStatement("if (edge < 0.0) discard;");
        shader.fragmentColor(shader.vec4(edge, edge, edge, shader.f32(1.0f)));
    });
}
```

- `rawPreamble` inserts text after `#version 150`. Use it for extensions and macros.

- `rawDeclaration` inserts text after globals and before `main()`. Use it for user functions and backend-specific declarations.

- `rawStatement` copies the statement text into the current block. The method does not parse or change the text.

- `rawFloat`, `rawInt`, `rawBool`, `rawVec*`, `rawMat*`, and `rawStruct` wrap source text in caller-declared expression types.

The raw API rejects only empty strings. The raw API does not parse source text. The raw API does not track types, identifiers, uniforms, stage interfaces, or control flow. The caller is responsible for the declared type of a raw expression. The caller is also responsible for raw-uniform JSON entries and GLSL driver support. Use the typed API when the typed API supports the required feature.

The typed API does not currently supply user functions, struct uniforms, struct stage inputs, or struct stage outputs. The typed API also excludes non-float uniform arrays, arbitrary swizzles, `discard`, derivatives, and advanced texture sampling. A raw escape can supply these features. A resource shader or custom factory can also supply these features. Extend the typed IR and backend for cross-backend support, link validation, or a long-life public API. Do not use a raw string as a permanent interface.

## Gradle Shader Generation

```groovy
plugins {
    id 'builder.kasuga.shader'
}
```

- The `builder.kasuga.shader` plugin creates the `shader` source set. The plugin reads `src/shader/java` and `src/shader/resources`. The plugin also adds the shader DSL dependency.

- `generateKasugaShaders` runs before `processResources`. The task first clears the generated-output directory. The task then runs the shader providers.

- A provider must be a non-abstract public class that implements `ShaderProgramProvider`. The provider must have a public no-argument constructor. The generation task fails if the provider does not meet these conditions.

- The task sorts providers by class name before execution. The task converts each returned program to `.vsh`, `.fsh`, and `.json` resources.

- If two programs generate the same path, the task fails. The task does not overwrite generated output.

- The plugin adds the generated directory to the main resources. Therefore, the final JAR contains the generated content.

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
