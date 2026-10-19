# Java Shader DSL Reference

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


> Other pages of this topic: [Custom Rendering Specification](effect-rendering.md) and [Managed Effects and Post-Processing](effects-and-postfx.md).
