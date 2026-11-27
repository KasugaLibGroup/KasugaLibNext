# Gradle 插件、代码生成与资源编译参考

KasugaLibNext 通过 `settings.gradle` 中的 included build `./modules/gradle-plugin`（坐标 `kasuga.build:gradle-plugin:1.0.0`）提供四个 Gradle 插件，分别配合 `:modules:generator`、`:modules:resource-compiler` 与 `:modules:shader` 完成多模块代码合并、注册代码生成、资源编译与 GLSL shader 生成。

> 另见 [Java Shader DSL 参考](shader-dsl.md#gradle-shader-生成) 中关于 `ShaderProgramProvider` 与 DSL 编译规则的说明，以及 [库打包与多版本结构](library-packaging.md) 中各插件在 `:modules:library` 等发行包上的实际组合方式。

## Gradle 插件总览

| 插件 ID | 实现类 | 依赖插件 / 配置 | 核心产出 / 扩展 |
| --- | --- | --- | --- |
| `builder.kasuga.monorepo` | `builder.kasuga.KasugaMonorepoPlugin` | NeoForge ModDev（`neoForge.mods`）、`processResources`、可选 `shadowJar` | `useModProject(project, includeContentTesting)`、`processResources` 并行 JSON 深度合并 |
| `builder.kasuga.generator` | `builder.kasuga.KasugaGeneratorPlugin` | `:modules:generator`（或 `lib.kasuga:generator:1.0.0`） | `useGenerator()` 辅助闭包、`codeTemplate` 源集、`runGenerator` 任务 |
| `builder.kasuga.resource-compiler` | `builder.kasuga.KasugaResourceCompilerPlugin` | `:modules:resource-compiler`（或 `lib.kasuga:resource-compiler:1.0.0`） | `compileResources` 任务（`resources/` -> `src/compiled/resources`） |
| `builder.kasuga.shader` | `builder.kasuga.KasugaShaderPlugin` | `:modules:shader`（或 `lib.kasuga:shader:${mod_version}`） | `shader` 源集（`src/shader/{java,resources}`）、`generateKasugaShaders` 任务 |

此外，插件包还提供 Shadow 资源转换器 `builder.kasuga.MicronautServiceShadowTransformer`，配合 `com.gradleup.shadow`（`9.0.2`）重定向 `META-INF/micronaut/**` 路径。

## `builder.kasuga.monorepo` 与 Shadow 转换器

### `useModProject` 约定

应用 `builder.kasuga.monorepo` 后，构建脚本可调用：

```groovy
useModProject(project(':modules:core'))
useModProject(project(':modules:data-driven'), false)
```

`useModProject(Project targetProject, boolean includeContentTesting = true)` 执行以下接线：

1. 声明 `project.evaluationDependsOn(targetProject.path)` 并读取当前项目的 `mod_id` 属性（未设置时回退 `"main"`）。
2. 将 `targetProject.sourceSets.main` 注册进当前项目的 `neoForge.mods."${mod_id}"`。
3. 添加 `compileOnly targetProject`（若存在 `shadowOnly` 配置则同时加入 `shadowOnly`）。
4. 将 `targetProject.sourceSets.main.resources` 并入 `processResources`（排除 `META-INF/neoforge.mods.toml` 与 `META-INF/mods.toml`），并声明 `dependsOn(targetProject.tasks.named("processResources"))`。
5. 在 `afterEvaluate` 中：
   - 当 `includeContentTesting == true` 且目标存在 `contentTesting` 源集时，将其注册进 `neoForge.mods."${mod_id}"`；若当前项目也定义了 `contentTesting`，还会追加其 `output`、`compileClasspath` 与 `runtimeClasspath`。
   - 若当前项目存在 `shadowJar` 任务，则将 `targetProject.sourceSets.main.output` 加入 `shadowJar`（同样排除 `META-INF/neoforge.mods.toml` 与 `META-INF/mods.toml`）。

> 当目标子模块以 `FMLModType = "GAMELIBRARY"` 发布或无需继承其 `contentTesting` 时（如 `modules/modelling/build.gradle` 与 `modules/create/build.gradle` 对 `:modules:data-driven`），传入 `includeContentTesting = false` 可避免多余测试源集或重复模块层冲突。

### `processResources` 并行 JSON 深度合并

`KasugaMonorepoPlugin` 会在 `processResources` 中拦截全部 `.json` 文件（按输出相对路径分桶并排除出默认拷贝流程，同时设置 `duplicatesStrategy = DuplicatesStrategy.EXCLUDE`），在 `doLast` 阶段使用大小为 `Runtime.runtime.availableProcessors()` 的线程池并行输出：

- 桶内仅 1 个文件：调用 `Files.copy` 直接拷贝到目标路径（注意：源码未传入 `REPLACE_EXISTING`，若输出目录遗留同名只读/旧文件需先清理或 clean）。
- 桶内有多个同路径 `.json`：使用 Groovy `JsonSlurper` 依次解析并执行 `deepMerge`：
  - 双方均为 `Map`：按键递归合并。
  - 双方均为 `List`：拼接并调用 `.unique()` 去重。
  - 其他值：`b ?: a`（后写覆盖）。
  - 通过 `JsonOutput.toJson(merged)` 写出。

### `MicronautServiceShadowTransformer`

`builder.kasuga.MicronautServiceShadowTransformer` 实现 Shadow 的 `ResourceTransformer`，用于配合 `shadowJar` 中的 `relocate`（例如 `modules/library/build.gradle` 与 `modules/core/build.gradle` 将 `io.micronaut`、`jakarta.inject`、`jakarta.annotation` 与 `lib.kasuga.inject` 重定位至 `lib.kasuga.vendors.*`）：

- `canTransformResource` 匹配所有 `META-INF/micronaut` 前缀条目。
- `transform` 按 `/` 拆分路径段，对每个段依次尝试应用当前 `transformerContext.relocators`（`canRelocateClass` -> `relocateClass`），将重定位后的路径与原字节存入 `LinkedHashMap`。
- `modifyOutputStream` 将转换后的全部条目写入目标 JAR，确保 Micronaut DI 服务索引目录与重定位后的包名一致。

## `builder.kasuga.generator` 与代码生成器（`:modules:generator`）

### Gradle 配置与 `runGenerator` 任务

应用插件并调用 `useGenerator()` 后，`KasugaGenerator.useGenerator(Project)` 会创建：

- `codeGenDependency` 配置：优先指向 `:modules:generator` 或 `:KasugaLibNext:modules:generator`，外部工程回退到 `'lib.kasuga:generator:1.0.0'`。
- `codeTemplate` 源集：源码目录 `src/codeTemplate/java`、资源目录 `src/codeTemplate/resources`，继承 `main.output`、`main` 的编译/运行类路径以及 `codeGenDependency`。
- `runGenerator`（`JavaExec`，主类 `lib.kasuga.internal.generator.KasugaCodeGen`）：
  - 输入：`sourceSets.codeTemplate.allSource`
  - 输出：`$buildDir/generated/sources/codegen/java/main`
  - 参数：`<codeTemplateSrcDir>`、`<generatedDir>`、`<classpath>`（由非 `buildDir` 下的 `main` java 源目录、`main.runtimeClasspath` 以及 `:modules:utils/build/moddev/artifacts` 中非 `-sources.jar` 的 JAR 拼接而成）。
  - 依赖本工程及 `:modules:utils` 的 `createMinecraftArtifacts`（若存在），且 `compileJava.dependsOn(runGenerator)`。

需要把生成代码纳入编译的模块（如 `modules/core/build.gradle`）需显式添加生成目录到 `main` 源集：

```groovy
plugins {
    id 'builder.kasuga.generator'
}

sourceSets.main.java.srcDir("$buildDir/generated/sources/codegen/java/main")
useGenerator()
```

### 生成流程、包重映射与 `Inline<T>`

`KasugaCodeGen` 使用 JavaParser（语言级别 `JAVA_17`）与 `JavaSymbolSolver` 解析 `src/codeTemplate/java` 下的全部 `.java` 文件：

1. 检查主类型注解，读取 `@CodeTemplate(generator = "...")`，在 `AllKasugaCodeGen.GENERATORS` 中查找实现 `CodeGenerator` 的生成器：
   - `"Reg"` -> `lib.kasuga.internal.generator.generators.reg.RegCodeGen`
   - `"Rpc"` -> `lib.kasuga.internal.generator.generators.rpc.RpcCodeGen`
2. 若模板类 `implements Inline<T>`（`lib.kasuga.internal.generator.facades.Inline`），`KasugaCodeGen` 解析 `T` 的 AST，移除 `Inline<T>` 接口声明，并将 `T` 的非构造器成员及类注解克隆合并到当前类。
3. 执行生成器后，通过 `SourceCodeWriter.postProcess(CompilationUnit)` 清理代码：
   - 移除所有 `lib.kasuga.internal.generator.*` 包下的注解与 `import`。
   - 剥离 `package` 声明、文件相对路径及 `import` 中开头的 `template.` 前缀（因此 `template.lib.kasuga.registration.minecraft.block` 会生成为 `lib.kasuga.registration.minecraft.block`）。

> 说明：`modules/generator/src/main/resources/services/javax.annotation.processing.Processor` 中声明了 `lib.kasuga.annotation.AnnotationProcessor`，但仓库中并无该类的实现；实际生成完全通过 `runGenerator`（`JavaExec` 运行 `KasugaCodeGen`）在 `compileJava` 前执行，不经由 `javac` APT。

### 注解与 Facade 参考

| 类型 | 全限定名 | 属性 / 签名 | 行为与生成产物 |
| --- | --- | --- | --- |
| `@CodeTemplate` | `lib.kasuga.internal.generator.annotations.CodeTemplate` | `String generator()` | 标记代码模板入口类（`"Reg"` 或 `"Rpc"`） |
| `@Prototype`（注解） | `lib.kasuga.internal.generator.annotations.Prototype` | 无属性 | `RegCodeGen` 处理时从生成类中删除被标注的原型方法（如 `ItemReg.tabToByKey` 占位重载） |
| `Prototype<T>` / `Inline<T>` | `lib.kasuga.internal.generator.facades.{Prototype,Inline}` | 空接口 | `Inline<T>` 触发成员与注解内联展开；`Prototype<T>` 为门面标记接口 |
| `@RegGenerator` | `lib.kasuga.internal.generator.annotations.RegGenerator` | `Modifier[] modifiers() default {}` | 声明当前注册模板类要生成的一组修饰器类型 |
| `@RegGenerator.Modifier` | `RegGenerator.Modifier` | `type`、`target`、`extendedType = ""`、`enumeration = {}`、`prefix = ""` | 生成 `<Class>Modifiers` 中的 `TYPE_<UPPER_UNDERSCORE>` 静态 `ModifierType<target>` 与嵌套静态基类 `<type> extends Modifier<target>`（含 `of(String, Function<T, T>)`）。当 `enumeration` 非空时，枚举 `target`（或 `extendedType` 解析类型）上的对应方法并批量生成无参/有参修饰器常量与 `<Class>Configurations` 默认链式方法（设 `prefix` 时方法名为 `<prefix><UpperCamelMethod>`） |
| `@RegGenerator.ModifyFunction` | `RegGenerator.ModifyFunction` | `String type()`、`String[] parameterTypes() default {}` | 将模板方法（首参为原始值，其余最多 2 个参数）转为 `<Class>Modifiers` 中的静态 `Modifier` / `Util.memoize` `Function` / `BiFunction`（命名 `<METHOD>` 或 `<METHOD>_BY_<PARAM_TYPES>`）及 `<Class>Configurations<S>` 中的默认链式方法；`parameterTypes` 用于覆盖带泛型实参的类型描述 |
| `@RegGenerator.ConfigureMember` | `RegGenerator.ConfigureMember` | 无属性 | 将方法移至 `<Class>Configurations<S>`，返回类型改写为 `S`，并将体内的 `RegFacade.callConfigure(scope, "name", args...)` 重写为 `scope.name(args...)` |
| `@RegGenerator.ChildrenConfiguration` | `RegGenerator.ChildrenConfiguration` | `Class<?> target() default Object.class` | 将方法移至 `<Class>ChildrenConfigurations<S>`（继承 `IChildrenConfiguration<S>` 与 `<Class>Configurations<S>`），返回类型改写为 `S`，并将方法体顶层 `return expr;` 改写为 `return addChild(expr);` |
| `@RegGenerator.SelfReference` | `RegGenerator.SelfReference` | `@Target(ElementType.PARAMETER)` | 标注于构造器上的 `Function<Self, R>` 参数，使 `RegCodeGen` 自动生成 `public static of(...)` 工厂方法，将用户传入的 `R` 包装为 `ig -> arg` |
| `ChildrenFindConfiguration` / `Type` / `ModifierApplier` / `ModifierInstance` | `RegGenerator.*` | `@Target` / `ModifierApplier.type` / `ModifierInstance.type` | 在 `RegGenerator` 中声明了注解定义，但当前 `RegCodeGen.onAnnotation` 未对这 4 个子注解做转换分发 |
| `RegFacade` | `lib.kasuga.internal.generator.facades.RegFacade` | `transformObject(String, T)`、`callConfigure(Object, String, Object...)`、`modifier(Class<?>, String, Object...)` | 编译期占位调用；`RegCodeGen` 将 `transformObject("Foo", val)` 重写为 `this.transform(<Class>Modifiers.TYPE_FOO, val)`，将 `callConfigure` 重写为直接方法调用；未经过生成器处理而直接运行时抛出 `IllegalStateException("Not compiled?")` |

此外，`RegCodeGen` 在生成 `<Class>Configurations<S>` 时还会自动附带嵌套静态类 `Adapter extends ConfigureAdapter<Adapter> implements <Class>Configurations<Adapter>`，以及 `toAdapter(IModifierConfigure<?>)` 与 `adopt(Consumer<Adapter>)` 适配器方法。

### `RpcCodeGen` 生成规则

标注 `@CodeTemplate(generator = "Rpc")` 的类（如 `template.lib.kasuga.core.networking.rpc.Rpc`）会由 `RpcCodeGen` 扫描其中的 `TemplateV`（无返回值）与 `TemplateR<R>`（返回 `CompletableFuture<R>`）嵌套类，按 `RPC_MAX_PARAM = 10` 展开为 `RpcV0`–`RpcV10` 与 `RpcR0`–`RpcR10`：

- 为每个参数槽 `1..i` 生成泛型参数 `T1..Ti`、`Request` 字段 `p1..pi`、`StreamCodec<? super FriendlyByteBuf, T1..Ti>` 编解码串联以及 `call(...)` / `handleInstant(...)` 方法。
- 自动生成对应的静态 `Rpc.wrap(name, func[, responseCodec], c1..ci)` 重载方法。

## `builder.kasuga.resource-compiler` 与 `:modules:resource-compiler`

### Gradle 任务行为

应用 `builder.kasuga.resource-compiler` 后：

- 自动创建 `resourceCompilerDependency` 配置，解析 `:modules:resource-compiler`（外部消费回退为 `'lib.kasuga:resource-compiler:1.0.0'`）。
- 注册 `compileResources`（`JavaExec`，主类 `lib.kasuga.resource.ResourceCompilerMain`）：
  - 输入目录：`$projectDir/resources`
  - 输出目录：`$projectDir/src/compiled/resources`
  - 参数：`[inDir, outDir, project.findProperty("mod_id")]`
  - 在 `doFirst` 中先删除整个 `src/compiled/resources` 输出目录再运行编译器，确保移除旧资源；并挂载 `processResources.dependsOn(compileResources)`。

### `module.toml` 发现、目录类别与 `JsonMergeTransformer`

`ResourceCompilerMain` 递归查找 `<inDir>/**/module.toml`，并过滤只保留**顶层模块根目录**（即其任意祖先目录不再包含 `module.toml` 的目录）。`ResourceScanner` 会尝试读取 `module.toml`（NightConfig `CommentedFileConfig`；当前字段保留供后续扩展），跳过 `module.toml` 本身，并按模块根目录下的第一级子目录名匹配 `AllResourceCategory`：

| 顶层目录名 | 目标相对路径（`ns = mod_id`） | 默认 Transformer |
| --- | --- | --- |
| `blockstates` | `assets/<ns>/blockstates/<relPath>` | 无 |
| `lang` | `assets/<ns>/lang/<relPath>` | `JsonMergeTransformer` |
| `models` | `assets/<ns>/models/<relPath>` | 无 |
| `sounds` | `assets/<ns>/sounds/<relPath>` | 无 |
| `textures` | `assets/<ns>/textures/<relPath>` | 无 |
| `recipes` | `data/<ns>/recipes/<relPath>` | 无 |
| `tags` | `data/<ns>/tags/<relPath>` | `JsonMergeTransformer` |
| `loots` | `data/<ns>/loot_tables/<relPath>` | 无 |
| `data` | `data/<relPath>` | `JsonMergeTransformer` |
| `assets` | `assets/<relPath>` | `JsonMergeTransformer` |
| `scripts` | `scripts/<relPath>` | 无 |

第一级目录未命中 `AllResourceCategory` 时立即抛出 `RuntimeException("Unable to found category for \"...\"")`；位于模块根目录直接下层的孤立文件（`nameCount < 2`）会被跳过。

`ResourceProcessor` 在 `ForkJoinPool.commonPool()` 上并发处理每个目标路径分组：

- 优先级按 `Transformer.compareTo` 升序执行（优先级相同按类名字典序）。
- **`JsonMergeTransformer`**（`PRIORITY = 100`）：仅当同目标路径存在多个源文件（`resources.size() > 1`）且全部扩展名为 `.json` 时触发。按源文件名字典序依次解析并使用 Gson 深度合并：
  - 嵌套 `JsonObject`：递归合并。
  - `JsonArray`：顺序追加元素。
  - 原始类型冲突：打印 `[WARN] JSON primitive conflict: key "..." from "...", overwriting previous value` 并覆盖前值。
  - 最终以 `GsonBuilder().setPrettyPrinting()` 输出 UTF-8 JSON。
- 若多源文件映射到同一目标路径且无 `Transformer` 合并为单结果，则抛出包含源文件路径列表的冲突异常（`"Unable to resolve the conflict..."`）。

## `builder.kasuga.shader`

`builder.kasuga.shader` 插件（`KasugaShaderPlugin`）自动应用 `java` 插件并创建 `shader` 源集（`src/shader/java`、`src/shader/resources`），加入 `:modules:shader`（或 `lib.kasuga:shader:${mod_version}`）依赖，注册 `generateKasugaShaders` 任务调用 `lib.kasuga.shader.compiler.ShaderCompilerMain`，将 `shader` 源集中编译出的 `ShaderProgramProvider` 实现类输出到 `build/generated/kasugaShaders/resources`，并自动并入 `sourceSets.main.resources` 且挂载到 `processResources`。`ShaderProgramProvider` 的类可见性、命名校验与无参构造要求见 [Java Shader DSL 参考](shader-dsl.md#gradle-shader-生成)。

## 验证命令

```sh
# 验证 gradle-plugin 中的资源编译器与 shader 插件单测
./gradlew -p modules/gradle-plugin test

# 验证 resource-compiler 与 shader-dsl 生成
./gradlew :modules:resource-compiler:test :modules:shader:test

# 执行核心注册代码生成与编译
./gradlew :modules:core:runGenerator :modules:core:compileJava
```
