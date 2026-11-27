# Gradle Plugins, Codegen, and Resource Compiler Reference

KasugaLibNext ships four Gradle plugins from the included build `./modules/gradle-plugin` (`kasuga.build:gradle-plugin:1.0.0`, wired in `settings.gradle`) that work alongside `:modules:generator`, `:modules:resource-compiler`, and `:modules:shader` to handle monorepo module merging, registration code generation, resource compilation, and GLSL shader emission.

> Also see [Java Shader DSL Reference](shader-dsl.md#gradle-shader-generation) for `ShaderProgramProvider` rules and DSL semantics, and [Library Packaging and Multiversion Structure](library-packaging.md) for how these plugins compose across distribution artifacts such as `:modules:library`.

## Gradle Plugin Summary

| Plugin ID | Implementation Class | Backing Dependency | Main Tasks / Extensions |
| --- | --- | --- | --- |
| `builder.kasuga.monorepo` | `builder.kasuga.KasugaMonorepoPlugin` | NeoForge ModDev (`neoForge.mods`), `processResources`, optional `shadowJar` | `useModProject(project, includeContentTesting)`, parallel JSON deep-merge in `processResources` |
| `builder.kasuga.generator` | `builder.kasuga.KasugaGeneratorPlugin` | `:modules:generator` (or `lib.kasuga:generator:1.0.0`) | `useGenerator()` helper, `codeTemplate` source set, `runGenerator` task |
| `builder.kasuga.resource-compiler` | `builder.kasuga.KasugaResourceCompilerPlugin` | `:modules:resource-compiler` (or `lib.kasuga:resource-compiler:1.0.0`) | `compileResources` task (`resources/` -> `src/compiled/resources`) |
| `builder.kasuga.shader` | `builder.kasuga.KasugaShaderPlugin` | `:modules:shader` (or `lib.kasuga:shader:${mod_version}`) | `shader` source set (`src/shader/{java,resources}`), `generateKasugaShaders` task |

The plugin module also ships `builder.kasuga.MicronautServiceShadowTransformer` for Shadow (`com.gradleup.shadow` `9.0.2`) relocation of `META-INF/micronaut/**` descriptors.

## `builder.kasuga.monorepo` and Shadow Transformer

### `useModProject` Contract

With `builder.kasuga.monorepo` applied, consumer modules call:

```groovy
useModProject(project(':modules:core'))
useModProject(project(':modules:data-driven'), false)
```

`useModProject(Project targetProject, boolean includeContentTesting = true)` performs the following wiring:

1. Declares `project.evaluationDependsOn(targetProject.path)` and resolves `mod_id` (falling back to `"main"`).
2. Registers `targetProject.sourceSets.main` inside `neoForge.mods."${mod_id}"`.
3. Adds `compileOnly targetProject` (plus `shadowOnly targetProject` if a `shadowOnly` configuration exists).
4. Adds `targetProject.sourceSets.main.resources` to `processResources` (excluding `META-INF/neoforge.mods.toml` and `META-INF/mods.toml`) and sets `dependsOn(targetProject.tasks.named("processResources"))`.
5. In `afterEvaluate`:
   - If `includeContentTesting` is `true` and `targetProject` defines `contentTesting`, adds `targetProject.sourceSets.contentTesting` to `neoForge.mods."${mod_id}"`, and extends the consumer's `contentTesting` classpaths when present.
   - If the consumer defines a `shadowJar` task, adds `targetProject.sourceSets.main.output` (excluding `META-INF/neoforge.mods.toml` and `META-INF/mods.toml`).

> Pass `includeContentTesting = false` when mirroring a `GAMELIBRARY` subproject or when its test fixtures should not enter the consumer layer (for instance, `:modules:modelling` and `:modules:create` mirror `:modules:data-driven` with `false`).

### Parallel JSON Deep-Merge in `processResources`

`KasugaMonorepoPlugin` intercepts every `.json` file in `processResources` (`eachFile`), buckets files by target relative path, sets `duplicatesStrategy = DuplicatesStrategy.EXCLUDE`, and writes outputs in `doLast` using a thread pool of size `Runtime.runtime.availableProcessors()`:

- Single file in bucket: copied via `Files.copy(files[0].toPath(), out.toPath())` (note: no `REPLACE_EXISTING` flag is passed, so stale pre-existing outputs should be cleaned).
- Multiple `.json` files at the same path: parsed with Groovy `JsonSlurper` and combined with `deepMerge`:
  - `Map` + `Map`: merged recursively by key.
  - `List` + `List`: concatenated and deduplicated via `.unique()`.
  - Primitives/mixed: `b ?: a` (last non-falsy value wins).
  - Serialized via `JsonOutput.toJson(merged)`.

### `MicronautServiceShadowTransformer`

`builder.kasuga.MicronautServiceShadowTransformer` implements Shadow's `ResourceTransformer` for modules that relocate Micronaut packages in `shadowJar` (such as `modules/library/build.gradle` and `modules/core/build.gradle`, which relocate `io.micronaut`, `jakarta.inject`, `jakarta.annotation`, and `lib.kasuga.inject` under `lib.kasuga.vendors.*`):

- `canTransformResource` matches any path starting with `META-INF/micronaut`.
- `transform` splits the resource path on `/`, applies every active `Relocator` (`canRelocateClass` -> `relocateClass`) to each segment, and stores the rewritten path and bytes in a `LinkedHashMap`.
- `modifyOutputStream` writes the transformed entries into the shaded JAR so Micronaut bean/introspection metadata paths match the relocated package names.

## `builder.kasuga.generator` and Codegen (`:modules:generator`)

### Gradle Wiring and `runGenerator`

Applying `builder.kasuga.generator` and calling `useGenerator()` configures:

- Configuration `codeGenDependency`: resolves `:modules:generator` (or `:KasugaLibNext:modules:generator`), falling back to `'lib.kasuga:generator:1.0.0'`.
- Source set `codeTemplate`: `src/codeTemplate/java` and `src/codeTemplate/resources`, inheriting `main.output`, `main`'s compile/runtime classpaths, and `codeGenDependency`.
- Task `runGenerator` (`JavaExec`, main class `lib.kasuga.internal.generator.KasugaCodeGen`):
  - Inputs: `sourceSets.codeTemplate.allSource`
  - Output directory: `$buildDir/generated/sources/codegen/java/main`
  - Arguments: `<codeTemplateSrcDir>`, `<generatedDir>`, and a classpath string combining non-`buildDir` `main` Java source dirs, `main.runtimeClasspath`, and non-`-sources.jar` JARs in `:modules:utils/build/moddev/artifacts`.
  - Depends on `createMinecraftArtifacts` (local and `:modules:utils` when present), and attaches `compileJava.dependsOn(runGenerator)`.

Modules emitting generated code into `main` add the output directory explicitly (as in `modules/core/build.gradle`):

```groovy
plugins {
    id 'builder.kasuga.generator'
}

sourceSets.main.java.srcDir("$buildDir/generated/sources/codegen/java/main")
useGenerator()
```

### Generation Pipeline, Package Remapping, and `Inline<T>`

`KasugaCodeGen` parses all `.java` sources under `src/codeTemplate/java` with JavaParser (`LanguageLevel.JAVA_17`) and `JavaSymbolSolver`:

1. Scans primary-type annotations for `@CodeTemplate(generator = "...")` and dispatches via `AllKasugaCodeGen.GENERATORS`:
   - `"Reg"` -> `lib.kasuga.internal.generator.generators.reg.RegCodeGen`
   - `"Rpc"` -> `lib.kasuga.internal.generator.generators.rpc.RpcCodeGen`
2. If the template class `implements Inline<T>` (`lib.kasuga.internal.generator.facades.Inline`), `KasugaCodeGen` resolves `T`'s AST, removes `Inline<T>`, and clones `T`'s non-constructor members and class annotations onto the template class.
3. After generator execution, `SourceCodeWriter.postProcess(CompilationUnit)`:
   - Removes all annotations and imports under `lib.kasuga.internal.generator.*`.
   - Strips leading `template.` prefixes from `package` declarations, imports, and relative output paths (e.g., `template.lib.kasuga.registration.minecraft.block` outputs under `lib.kasuga.registration.minecraft.block`).

> Note: `modules/generator/src/main/resources/services/javax.annotation.processing.Processor` lists `lib.kasuga.annotation.AnnotationProcessor`, which has no implementation in the repository; all generation runs via the `runGenerator` `JavaExec` task prior to `compileJava`, not via `javac` annotation processing.

### Annotations and Facades Reference

| Symbol | Qualified Name | Attributes / Signatures | Behavior and Generated Output |
| --- | --- | --- | --- |
| `@CodeTemplate` | `lib.kasuga.internal.generator.annotations.CodeTemplate` | `String generator()` | Marks a top-level code template (`"Reg"` or `"Rpc"`) |
| `@Prototype` (annotation) | `lib.kasuga.internal.generator.annotations.Prototype` | none | Causes `RegCodeGen` to strip the annotated stub method from generated output (e.g., `ItemReg.tabToByKey` placeholder) |
| `Prototype<T>` / `Inline<T>` | `lib.kasuga.internal.generator.facades.{Prototype,Inline}` | marker interfaces | `Inline<T>` triggers member/annotation inlining in `KasugaCodeGen`; `Prototype<T>` is a facade marker |
| `@RegGenerator` | `lib.kasuga.internal.generator.annotations.RegGenerator` | `Modifier[] modifiers() default {}` | Declares modifier channels generated for a `Reg` template |
| `@RegGenerator.Modifier` | `RegGenerator.Modifier` | `type`, `target`, `extendedType = ""`, `enumeration = {}`, `prefix = ""` | Emits `TYPE_<UPPER_UNDERSCORE>` `ModifierType<target>` and nested abstract `<type> extends Modifier<target>` (with `of(String, Function<T, T>)`) inside `<Class>Modifiers`. When `enumeration` is non-empty, generates modifier constants and fluent methods in `<Class>Configurations` for matching methods on `target` (or `extendedType`), optionally prefixed with `prefix` |
| `@RegGenerator.ModifyFunction` | `RegGenerator.ModifyFunction` | `String type()`, `String[] parameterTypes() default {}` | Converts a template method (1st parameter = original value, up to 2 additional parameters) into a static `Modifier` / `Util.memoize` `Function` / `BiFunction` constant in `<Class>Modifiers` (`<METHOD>` or `<METHOD>_BY_<PARAM_TYPES>`) and a fluent default method returning `S` in `<Class>Configurations`; `parameterTypes` overrides resolved parameter type arguments |
| `@RegGenerator.ConfigureMember` | `RegGenerator.ConfigureMember` | none | Moves method to `<Class>Configurations<S>`, changes return type to `S`, and rewrites `RegFacade.callConfigure(scope, "name", args...)` into `scope.name(args...)` |
| `@RegGenerator.ChildrenConfiguration` | `RegGenerator.ChildrenConfiguration` | `Class<?> target() default Object.class` | Moves method to `<Class>ChildrenConfigurations<S>` (extending `IChildrenConfiguration<S>` and `<Class>Configurations<S>`), sets return type `S`, and rewrites top-level `return expr;` into `return addChild(expr);` |
| `@RegGenerator.SelfReference` | `RegGenerator.SelfReference` | `@Target(ElementType.PARAMETER)` | Placed on a `Function<Self, R>` constructor parameter so `RegCodeGen` generates `public static of(...)` wrapping `R` as `ig -> arg` |
| `ChildrenFindConfiguration` / `Type` / `ModifierApplier` / `ModifierInstance` | `RegGenerator.*` | `@Target` / `type` | Declared inside `@RegGenerator`, currently not dispatched by `RegCodeGen.onAnnotation` |
| `RegFacade` | `lib.kasuga.internal.generator.facades.RegFacade` | `transformObject(String, T)`, `callConfigure(Object, String, Object...)`, `modifier(Class<?>, String, Object...)` | Compile-time facade calls rewritten by `RegCodeGen` (`transformObject("Foo", v)` -> `this.transform(<Class>Modifiers.TYPE_FOO, v)`; `callConfigure` -> direct call); uncompiled invocations throw `IllegalStateException("Not compiled?")` |

`RegCodeGen` also generates a nested `Adapter extends ConfigureAdapter<Adapter> implements <Class>Configurations<Adapter>` alongside `toAdapter(IModifierConfigure<?>)` and `adopt(Consumer<Adapter>)` on `<Class>Configurations`.

### `RpcCodeGen` Expansion

Annotated classes (`@CodeTemplate(generator = "Rpc")`, such as `template.lib.kasuga.core.networking.rpc.Rpc`) have nested `TemplateV` (void) and `TemplateR<R>` (`CompletableFuture<R>`) classes expanded for `0..RPC_MAX_PARAM` (`10`) into `RpcV0`–`RpcV10` and `RpcR0`–`RpcR10`, generating typed `Request` payloads, chained `StreamCodec<? super FriendlyByteBuf, T1..Ti>` codecs, `call(...)`/`handleInstant(...)`, and static `wrap(...)` factory overloads.

## `builder.kasuga.resource-compiler` and `:modules:resource-compiler`

### Gradle Task Contract

Applying `builder.kasuga.resource-compiler`:

- Adds configuration `resourceCompilerDependency` resolving `:modules:resource-compiler` (or `lib.kasuga:resource-compiler:1.0.0`).
- Registers `compileResources` (`JavaExec`, main class `lib.kasuga.resource.ResourceCompilerMain`):
  - Input: `$projectDir/resources`
  - Output: `$projectDir/src/compiled/resources`
  - Arguments: `[inDir, outDir, project.findProperty("mod_id")]`
  - Deletes `src/compiled/resources` in `doFirst` before running, and wires `processResources.dependsOn(compileResources)`.

### `module.toml`, Categories, and `JsonMergeTransformer`

`ResourceCompilerMain` walks `<inDir>` for `**/module.toml` and keeps only top-level module roots (directories with `module.toml` whose ancestors have no `module.toml`). `ResourceScanner` loads `module.toml` via NightConfig `CommentedFileConfig` (currently reserved for future configuration fields), skips `module.toml`, and routes files under `<moduleRoot>/<category>/<relPath>` using `AllResourceCategory`:

| Category Folder | Target Path (`ns = mod_id`) | Default Transformers |
| --- | --- | --- |
| `blockstates` | `assets/<ns>/blockstates/<relPath>` | none |
| `lang` | `assets/<ns>/lang/<relPath>` | `JsonMergeTransformer` |
| `models` | `assets/<ns>/models/<relPath>` | none |
| `sounds` | `assets/<ns>/sounds/<relPath>` | none |
| `textures` | `assets/<ns>/textures/<relPath>` | none |
| `recipes` | `data/<ns>/recipes/<relPath>` | none |
| `tags` | `data/<ns>/tags/<relPath>` | `JsonMergeTransformer` |
| `loots` | `data/<ns>/loot_tables/<relPath>` | none |
| `data` | `data/<relPath>` | `JsonMergeTransformer` |
| `assets` | `assets/<relPath>` | `JsonMergeTransformer` |
| `scripts` | `scripts/<relPath>` | none |

Unknown category directories fail immediately (`RuntimeException`); files placed directly inside a module root (`nameCount < 2`) are ignored.

`ResourceProcessor` processes each target group in parallel on `ForkJoinPool.commonPool()`:

- Transformers run in `Transformer.compareTo` ascending priority order.
- **`JsonMergeTransformer`** (`PRIORITY = 100`): activates when multiple sources map to the same path and all filenames end with `.json`. Sorts inputs lexicographically by filename and deep-merges via Gson (objects merged recursively, arrays concatenated, primitive collisions logged with `[WARN] JSON primitive conflict...` and overwritten), emitting pretty-printed UTF-8 JSON.
- Multi-source collisions with no transformer producing a single `LoadedResource` fail with `"Unable to resolve the conflict..."`.

## `builder.kasuga.shader`

`KasugaShaderPlugin` applies `java`, creates the `shader` source set (`src/shader/java`, `src/shader/resources`) depending on `:modules:shader` (or `lib.kasuga:shader:${mod_version}`), and registers `generateKasugaShaders` (`lib.kasuga.shader.compiler.ShaderCompilerMain`). It emits `.vsh`, `.fsh`, and `.json` files from `ShaderProgramProvider` implementations into `build/generated/kasugaShaders/resources`, adds that directory to `sourceSets.main.resources`, and makes `processResources` depend on `generateKasugaShaders`. See [Java Shader DSL Reference](shader-dsl.md#gradle-shader-generation) for `ShaderProgramProvider` requirements.

## Verification Commands

```sh
# Run gradle-plugin tests (resource compiler & shader plugin)
./gradlew -p modules/gradle-plugin test

# Run resource-compiler and shader unit tests
./gradlew :modules:resource-compiler:test :modules:shader:test

# Generate registration sources and compile core
./gradlew :modules:core:runGenerator :modules:core:compileJava
```
