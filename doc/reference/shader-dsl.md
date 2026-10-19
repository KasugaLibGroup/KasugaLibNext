# Java Shader DSL 参考

## Java Shader DSL

- DSL 不解析 Java AST，也不反编译 lambda。builder lambda 在 Java 中立即执行，生成类型化 shader IR。

- Java 的条件与循环只控制 IR 的构造，最终 shader 不含这些 Java 结构。需要保留为 GLSL 动态逻辑的条件与循环，请用 shader builder 表达。

- 后端在每一处使用位置插入来自 Java 变量的表达式；该表达式不会自动创建 GLSL 局部变量。

- `let*` 调用创建带初始值的 GLSL 局部变量。需要后续赋值时，用返回 `ShaderVariable` 的 `local*` 调用。

- fullscreen 程序只包含一个片元模块，使用内置的 fullscreen 顶点阶段。

- graphics 程序包含一个顶点模块和一个片元模块。`position(...)` 写入 `gl_Position`。

- 每个片元输入都必须有同名同类型的顶点输出，否则程序构造失败。

- 两个阶段中同名的 uniform 或 sampler 必须有完全一致的声明。完全一致的声明在 Minecraft shader JSON 中只出现一次。

- 程序 ID 必须使用小写的 `namespace:path` 资源 ID 格式。

- 当前 profile 支持 float、int、bool、vec2、vec3、vec4、mat2、mat3、mat4 和 sampler2D，还支持 float uniform 数组与具名 struct。profile 支持阶段输入和输出、局部变量、结构化控制流、常用数学运算以及纹理采样。

- 当前后端生成 GLSL 150 和 Minecraft core-shader JSON。

- 加载期间，运行时生成的 bundle 对它自己的 `.vsh`、`.fsh` 和 `.json` 文件有优先权。资源或 import 缺失时，加载使用当前客户端的 `ResourceProvider`。

- DSL 在 Java 构造期检查 graphics 程序的阶段接口。调用方必须让 `RenderShaderDescriptor` 的顶点格式与各属性对应。

## 用户可调 shader 参数

shader 不会自动暴露所有 uniform。每个用户可调参数都要逐个注册。参数有固定的 `name`、`description`、`ShaderParameterType`、闭区间的 `range` 和 `defaultValues`。参数名同时就是 GLSL uniform 名，必须是合法且非保留的 GLSL 标识符。

```java
public static final ShaderParameter EXPOSURE = ShaderParameter.floatParameter(
        "Exposure",
        "Controls the final output exposure",
        1.0f,  // 默认值
        0.0f,  // 最小值
        4.0f   // 最大值
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

在生成的 shader 里，用相应的 `expose*` 调用声明 uniform 并登记参数规格。`uniform*` 调用让 uniform 保持内部，不会把它加入公开 schema。

```java
ShaderProgram program = ShaderProgram.fullscreen("example:adjustable", shader -> {
    FloatExpr exposure = shader.exposeFloat(EXPOSURE);
    Vec3Expr tint = shader.exposeVec3(TINT);
    Vec3Expr color = shader.sampler2D("SceneSampler").sample(shader.texCoord()).rgb();
    shader.fragmentColor(shader.vec4(color.mul(tint).mul(exposure), shader.f32(1.0f)));
});
```

资源 shader 或自定义工厂不能用 DSL。请在 `RenderShaderDescriptor` 上登记同样的规格。资源里的 uniform 必须与参数的 `name` 和基本 GLSL 类型一致；uniform 缺失时首次绑定失败。

```java
RenderShaderDescriptor descriptor = RenderShaderDescriptor.builder(id, format)
        .resource()
        .expose(EXPOSURE)
        .expose(TINT)
        .build();
```

`RenderShaderHandle.parameters()` 提供该注册默认的运行时参数块。`ShaderParameterBlock` 对每次修改校验分量数、数值类型、有限性和范围。修改参数不会重新生成或重新编译 shader。`reset(name)` 使用声明的默认值，`resetAll()` 恢复所有声明的默认值。

```java
RenderShaderHandle handle = registration.handle();
handle.parameters().setFloat(EXPOSURE, 1.35f);
handle.parameters().set(TINT, 1.0, 0.55, 0.2);
```

fullscreen 方法收到句柄时，Kasuga 在绘制前上传默认参数块。相关方法是 `PostProcessContext.fullscreen(...)` 和 `PostProcessGraphContext.fullscreen(...)`。Kasuga 无法从一个直接的 `ShaderInstance` 推断出注册，需要用 `.parameters(block)` 绑定参数块。同一个 shader 的每套独立配置都用 `handle.createParameterBlock()` 各建一块。

当前 `ShaderParameterType` 的取值包括 float、integer、boolean、vec2、vec3、vec4、RGB color 和 RGBA color，也包括 mat2、mat3 与 mat4。range 作用于所有分量。布尔参数始终使用 `[0, 1]`。

打开参数面板：

1. 在客户端执行 `/kasuga_effects`。
2. 选择 `Parameters`。

参数面板由 schema 生成，不针对某一个效果。面板列出每个暴露了参数的 shader。布尔参数用开关，其他参数类型用分量滑块。

向量、颜色和矩阵使用各自的分量名。参数改动在下次绘制时生效，不会重新生成或重新编译 shader。

Kasuga 把默认参数块异步保存到 `config/kasuga_lib/shader-parameters.json`。客户端重启后，Kasuga 按 shader ID 与参数名恢复取值。`createParameterBlock()` 得到的独立参数块承载效果实例状态，Kasuga 不把它写入全局配置。

配置只保存覆盖 schema 默认值的那些取值，因此一次 reset 之后，后续的 schema 默认值可以重新生效。用 `isDefault(name)` 与 `hasOverrides()` 检查当前状态。

以下客户端命令用于查看、修改和重置暴露的 shader 参数。滑块无法精确设置大整数时用命令，输入完整的向量或矩阵也用命令：

```text
/kasuga_effects parameter list <shader-id>
/kasuga_effects parameter set <shader-id> <name> <value...>
/kasuga_effects parameter reset <shader-id> <name>
```

向量和矩阵的各分量用空格或逗号分隔。内置的黑洞示例暴露 `LensingScale`、`DiskBrightness` 与 `ChromaticScale`；屏幕、时间以及按效果打包的 uniform 保持内部。

下面的例子用 Java 表达式构建并注册一个 fullscreen shader：

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

## 从 GLSL 迁移到 Java Shader DSL

builder lambda 代表 GLSL `main()` 函数体。lambda 不计算像素结果，它按调用顺序记录声明、表达式和语句，后端随后生成 GLSL 150。

### 类型与表达式

Java DSL 用表达式包装类型替代 GLSL 运算符。表达式类型明确时，中间表达式可以用 `var`，确切类型由 Java 编译器检查。

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

只有相应 `Expr` 类型自身的方法才能生成代码。例如当前 `Vec2Expr` 提供 `normalize()`，其他向量包装类型并不一定提供同名方法。若某个表达式不接受 Java 数字，改用 `f32()`、`i32()` 或相应的基本类型重载。

### 全局量、阶段输入与阶段输出

- `uniformFloat`、`uniformInt`、`uniformBool`、`uniformVec*` 和 `uniformMat*` 声明 GLSL uniform。提供的取值会成为 Minecraft shader JSON 中的默认值。不带默认值的矩阵重载使用单位矩阵。

- `uniformFloatArray` 是当前唯一的 uniform 数组方法。长度必须大于 1。索引某个元素要用 `IntExpr`。

- `sampler2D` 声明 `uniform sampler2D`。

- `inputFloat`、`inputInt` 和 `inputVec*` 为当前阶段声明 `in` 值。

- 顶点阶段用 `outputFloat` 和 `outputVec*` 声明 `out` 值，`position(...)` 写入 `gl_Position`。

- 片元阶段用 `fragmentColor(...)` 写入生成的 `fragColor`。

- fullscreen 片元可以用 `texCoord()` 读取内置顶点阶段给出的纹理坐标。

- graphics 片元必须用 `inputVec2` 这类方法声明片元输入，调用 `texCoord()` 会失败。

- 顶点程序至少要调用一次 `position(...)`，片元程序至少要调用一次 `fragmentColor(...)`。校验只确认 IR 中存在写入；shader 写作者必须保证所有运行时路径都会写到必需输出。

graphics 程序里的两个阶段分别对应 `.vsh` 和 `.fsh` 文件：

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

`Position`、`UV0` 和 `Color` 的输入名与类型必须与所提供的 Minecraft `VertexFormat` 一致。DSL 检查顶点到片元的接口，但不检查 `VertexFormat`。

### 局部变量与控制流

一个 Java 变量持有一条 IR 表达式。下面这个例子里 `brightness` 不创建 GLSL 局部变量，后端在使用位置插入该表达式：

```java
var brightness = color.rgb().mul(intensity);
shader.fragmentColor(shader.vec4(brightness, color.a()));
```

`letFloat/letVec*`、`letMat*` 和 `letStruct` 创建在 DSL 中只读的 GLSL 局部变量。需要后续赋值时，用相应的 `local*` 方法。`get()` 读变量，`set()` 写变量。

这段 GLSL：

```glsl
float sum = 0.0;
for (int index = 0; index < Count; ++index) {
    if (index >= 8) break;
    if (Values[index] <= 0.0) continue;
    sum = sum + Values[index];
}
fragColor = vec4(sum, sum, sum, 1.0);
```

等价的 Java DSL 是：

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

- `ifThen` 与 `ifThenElse` 生成 shader 分支。Java 的 `if` 只在 IR 构造期执行一次。

- `forRange(name, start, end, body)` 生成整数循环。循环步长为 1，结束条件为 `< end`。

- `whileLoop(condition, body)` 生成 `while`，`doWhile(condition, body)` 生成 `do while`。

- `breakLoop()` 只能跳出最内层循环。循环里可以用 `continueLoop()`。

- Java 的 `for` 可以针对常量值展开表达式，它不会生成 GLSL 循环。

- 若某个 shader 取值要在 Java lambda 中跨分支或循环变化，请用 `ShaderVariable`，不要用重新绑定 Java 局部变量来表示 shader 赋值。

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

### struct

struct 是具名类型。创建某个字段的 `ShaderStructType.Builder` 拥有该字段键；你不能把这个字段键用到另一个 struct 上，即使那个 struct 有同名同类型的字段也一样。

有依赖关系时，先声明被依赖的 struct，再声明包含它的 struct。当前 DSL 支持 struct 用于类型化局部变量、构造、字段读取和字段赋值，不支持把 struct 用作 uniform、阶段输入或阶段输出。

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

`structValue` 的实参顺序必须与字段声明顺序一致。builder 在 Java 构造期检查实参个数与类型。

### switch 语句

`switchOn` 接受一个 `IntExpr`。`caseOf` 保持声明顺序，不插入 `break`，因此允许 GLSL 的贯穿（fallthrough）语义。用 `breakSwitch()` 结束一个 case。生成的输出把 `defaultCase` 放在所有 case 之后。

```java
shader.switchOn(mode, cases -> cases
        .caseOf(0, () -> {
            intensity.set(shader.f32(0.25f));
            shader.breakSwitch();
        })
        .caseOf(1, () -> intensity.set(shader.f32(0.75f))) // 贯穿到下一个 case
        .defaultCase(() -> intensity.set(shader.f32(1.0f)))
);
```

对 `breakLoop()` 与 `breakSwitch()` 而言，对应的结构必须是最内层的 break 目标。这条规则避免生成非法的裸 `break`。循环里的 case 可以调用 `breakSwitch()`，但不能直接调用 `breakLoop()`。要跳出外层循环，请设置一个布尔局部变量，并在 switch 之后检查它。

### 命名与链接规则

- shader 程序 ID 必须使用小写的 `namespace:path`。

- shader 标识符必须匹配 `[A-Za-z_][A-Za-z0-9_]*`，不能以 `gl_` 开头，也不能是 GLSL 保留字。

- 一个阶段内的全局量、局部变量和循环索引共用一个名字集合。同一个名字在整个阶段里只能出现一次。分离的代码块不会形成可复用的名字作用域。

- 片元输入必须有同名同类型的顶点输出。顶点可以有片元不读取的额外输出。

- 两个阶段若使用同名的 uniform 或 sampler，其存储、类型、数组长度和默认值必须完全一致。

- 名字区分大小写。最终 GLSL 与 Minecraft JSON 原样使用这些名字。

### 运行时 uniform 与 sampler

F3+T 会替换当前的 `ShaderInstance`。对长生命周期对象：

1. 只保存 `RenderShaderHandle`。
2. 每次渲染时通过该句柄或 pass context 访问当前 generation。

- 在渲染线程上，用 `currentShader.safeGetUniform(name).set(...)` 更新 uniform。

- fullscreen pass 里用 `colorSampler`、`depthSampler` 或 `textureSampler` 绑定 sampler。

上传 float 数组：

1. 把 `float[]` 的容量设为 shader 声明中的长度。
2. 上传完整的 `float[]`。

- DSL 里的 uniform 默认值会生成 JSON 默认值，但不会覆盖 Java 每帧上传的当前值。

```java
context.fullscreen(PostProcessGraphTarget.main(), shaderHandle)
        .colorSampler("SceneSampler", context.read(scene))
        .uniforms(active -> active.safeGetUniform("Tint").set(red, green, blue))
        .draw(false);
```

### raw GLSL 逃生口

类型化 API 表达不了的 GLSL 使用 raw 逃生口。所有 raw 入口都带 `@DelicateShaderApi`，该注解标识需要额外复核的代码，不保证 API 稳定。Java 编译器不校验 raw GLSL。

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

- `rawPreamble` 在 `#version 150` 之后插入文本，用于扩展与宏。

- `rawDeclaration` 在全局量之后、`main()` 之前插入文本，用于用户函数和后端相关的声明。

- `rawStatement` 把语句文本复制进当前代码块。该方法不解析也不改动该文本。

- `rawFloat`、`rawInt`、`rawBool`、`rawVec*`、`rawMat*` 和 `rawStruct` 把源码文本包装成调用方声明的表达式类型。

raw API 只拒绝空字符串。它不解析源码文本，也不跟踪类型、标识符、uniform、阶段接口或控制流。raw 表达式的声明类型由调用方负责，raw uniform 的 JSON 条目以及 GLSL 驱动支持同样由调用方负责。类型化 API 能支持所需功能时，请用类型化 API。

类型化 API 目前不提供用户函数、struct uniform、struct 阶段输入和 struct 阶段输出，也不包含非 float 的 uniform 数组、任意 swizzle、`discard`、导数和高级纹理采样。raw 逃生口可以提供这些功能，资源 shader 或自定义工厂也可以。需要跨后端支持、链接校验或长期公共 API 时，请扩展类型化 IR 与后端。不要把 raw 字符串当作长期接口。

## Gradle shader 生成

```groovy
plugins {
    id 'builder.kasuga.shader'
}
```

- `builder.kasuga.shader` 插件创建 `shader` 源集。插件读取 `src/shader/java` 和 `src/shader/resources`，并加入 shader DSL 依赖。

- `generateKasugaShaders` 在 `processResources` 之前运行。任务先清空生成输出目录，再运行各 shader provider。

- provider 必须是非抽象 public 类，实现 `ShaderProgramProvider`，并具备 public 无参构造器。不满足这些条件时生成任务失败。

- 任务在执行前按类名排序 provider，并把每个返回的程序转换成 `.vsh`、`.fsh` 和 `.json` 资源。

- 两个程序生成同一路径时任务失败。任务不会覆盖已生成的输出。

- 插件把生成目录加入 main 资源，因此最终 JAR 含有生成的内容。


> 本主题的其他页面：[自定义渲染规范](effect-rendering.md) 与 [托管效果与后处理](effects-and-postfx.md)。
