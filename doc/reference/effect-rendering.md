# Kasuga 自定义渲染规范

接口、配置和调用参考。原理说明见 [effect-rendering.md](../explanation/effect-rendering.md)。

`RenderPipelineRegistrar` 是 Kasuga 渲染资源的公共注册器。它注册 shader、world pipeline、托管效果和后处理资源。

> **语言说明**
>
> 本文遵循 ASD-STE100（第 9 版）的写作原则。API 名称与产品术语保留官方拼写。

## 术语

| 术语 | 定义 |
| --- | --- |
| 注册器（Registrar） | 为一个所有者注册渲染资源的对象。 |
| 所有者（Owner） | 一个非空资源 ID，标识拥有某条注册的代码或模块。 |
| 注册（Registration） | 标识并控制一条已注册条目的对象。 |
| 作用域（Scope） | 面向动态内容的注册器。作用域会关闭自己拥有的资源。 |
| 描述（Descriptor） | 定义某项资源配置的不可变值。 |
| 句柄（Handle） | 为一条确切的 shader 注册提供访问入口的稳定对象。 |
| 托管目标（Managed target） | 由后处理图创建并释放的渲染目标。 |

## 注册、所有权与生命周期

- `RenderPipelineRegistrar` 是 shader、world pipeline、托管效果、后处理 pass 与后处理图的公共注册入口。

- `RegisterRenderPipelinesEvent` 在客户端 mod bus 上只发生一次。该事件给出的注册器在客户端整个生命周期内保持有效。

- 动态内容使用 `RenderPipelineScope`。作用域提供与事件注册器相同的注册方法。


- 每个注册器有一个非空所有者。每条注册与诊断快照都保留该所有者。

客户端注册事件为静态内容提供注册器：

```java
@SubscribeEvent
public static void registerRendering(RegisterRenderPipelinesEvent event) {
    RenderPipelineRegistrar pipelines = event.registrar(
            ResourceLocation.fromNamespaceAndPath("example", "rendering")
    );
    installRendering(pipelines);
}
```

动态模块拥有一个作用域，并在模块卸载时关闭它：

```java
try (RenderPipelineScope scope = RenderPipelineScope.create(
        ResourceLocation.fromNamespaceAndPath("example", "script/session_42")
)) {
    installRendering(scope);
}
```

- `RenderPipelineScope.child(owner)` 创建带独立所有者的子作用域。父作用域关闭时，子作用域也被关闭。

- 作用域按注册的逆序释放资源。同一个作用域可以关闭多次。已关闭的作用域不接受新资源。

- 每条注册提供 `id()`、`owner()`、`isActive()` 和 `close()`。一条注册只引用该注册自己创建的那条条目，不会跟随同 ID 的替换。

- shader 注册表与 world pipeline 注册表各自独立地执行 ID 唯一性。world 回调、托管效果、后处理 pass 与后处理图使用 world pipeline 的 ID。

- ID 已被占用时，`DuplicatePolicy.FAIL` 拒绝该注册，错误信息会指出当前所有者。

- `DuplicatePolicy.REPLACE` 替换当前条目，替换会使旧注册失效。关闭旧注册不会关闭替换后的注册。

## 管线描述与执行

- `RenderPipelineDescriptor` 是不可变值，包含 ID、执行阶段、优先级和一个 `RenderDrawState`。缺少执行阶段时构建失败。

- 执行阶段可以是 Kasuga 的语义 `RenderPhase`，也可以是 NeoForge 的原生 `RenderLevelStageEvent.Stage`。原生执行阶段不会创建语义阶段。

- `RenderPhase.POST_PROCESS` 映射到原生 `AFTER_LEVEL` 阶段。在这个原生阶段里，Kasuga 使用固定顺序：`POST_PROCESS`、原生 `AFTER_LEVEL` 执行阶段，以及语义 `AFTER_LEVEL`。

- 在同一个执行位置上，优先级数值较小的管线先运行。优先级数值相同时，Kasuga 按 ID 字符串排序。

- 注册表为每个原生阶段缓存一份不可变执行快照。注册、替换或关闭条目会使对应的快照失效。

- world pipeline 回调运行在客户端渲染线程上。只有存在客户端世界时，Kasuga 才调用该回调。

- Kasuga 为每个回调压入独立的姿态栈，并在回调返回或抛异常后弹出该姿态栈。

- 若某个回调抛出运行时异常，Kasuga 记录该异常，同一阶段的其他管线继续运行。

- 若某个回调开始了缓冲批次，必须在返回前结束该批次。

world pipeline 可以使用描述中编译好的 `RenderType`：

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

## 绘制状态与 `RenderType`

- `RenderDrawState` 是不可变的 Minecraft 绘制状态描述。可以复用该绘制状态，也可以用 `toBuilder()` 派生出新值。

- 默认状态使用 `POSITION`、`QUADS`、1536 字节缓冲和 position shader；没有纹理，也不做混合。深度测试为 `LEQUAL`，开启剔除，写主 framebuffer 的颜色与深度。

- 缓冲区大小必须大于零。每个状态分片都必须非空。

- 混合、深度、剔除、分层、目标和写掩码的选择使用稳定枚举。调用方也可以提供原生的 `RenderStateShard`。

- 每条管线注册创建一个 `CompiledRenderPipeline`。代码首次访问 `RenderType` 时，Kasuga 构建并缓存默认的 `RenderType`。

- Kasuga 按「完整纹理 + 模糊 + mipmap」组合缓存纹理变体；自定义纹理状态变体按调用方提供的非空名称缓存。

- `CompiledRenderPipeline` 的缓存只属于它那条注册。替换后的注册会得到新的缓存。

## Shader 来源与注册

- `RenderShaderDescriptor` 支持三种 shader 来源：资源 shader、生成 shader 和自定义工厂。

- 生成的 fullscreen 程序默认使用 `DefaultVertexFormat.BLIT_SCREEN`。生成的 graphics 程序必须指定 Minecraft 顶点格式。

- 描述默认使用 `EAGER`、优先级 `0` 和 `DISABLE_PIPELINE`。

- `ShaderRegistration` 与 `RenderShaderHandle` 绑定到一条确切的注册。

- 句柄跨 F3+T 重载保持稳定。重载会使内部 `ShaderInstance` 失效，句柄随后安装新一代。

- 句柄还保存暴露参数的默认 `ShaderParameterBlock`。F3+T 重载不改变参数取值，这些取值不依赖当前的 `ShaderInstance`。

- 只有在 Kasuga 成功安装新的编译实例之后，`generation()` 才递增。

- shader 不可用时 `get()` 返回 `null`，`shader()` 返回空 optional，`require()` 抛异常。

- `status()` 报告当前状态、加载来源、队列位置、等待时间、generation、准备耗时、编译耗时、转换缓存结果与最近一次错误。

- 可观测状态有 `REGISTERED`、`PREPARING`、`QUEUED`、`COMPILING`、`READY`、`FAILED` 和 `CLOSED`。

- 按 ID 查询注册表或预加载时，使用的是该 ID 当前生效的注册。一条注册或句柄无法访问同 ID 的替换。

## Shader 准备、编译与重载

- 客户端 setup 之后，`EAGER` shader 立即开始生成源码的准备。该 shader 在首次 Minecraft `RegisterShadersEvent` 期间完成编译与链接。若注册发生在资源系统启动之后，Kasuga 会把该 shader 放进每帧编译队列。

- `DEFERRED` shader 不阻塞资源重载。资源可用后，Kasuga 把该 shader 放进每帧编译队列。

- `MANUAL` shader 不会自动入队。只有通过它的注册、句柄或注册表显式预加载之后，该 shader 才入队。

- 从生成的 shader IR 转换到 GLSL 与 JSON 属于 CPU 准备；OpenGL 编译与链接只在渲染线程发生。

- 准备使用独立的有界执行器，队列容量 128。该执行器不使用 JVM 公共池。默认工作线程数为可用 CPU 数的一半，最少 1、最多 4。客户端 setup 时启动这些工作线程。

- `ShaderPreparationScheduler.configureWorkers(0)` 选择自动数量。正值表示用户请求，Kasuga 会把它限制到 JVM 可见的 CPU 数。启动参数 `-Dkasuga.shaderPreparationWorkers=N` 提供同一项设置，`0` 表示自动模式。客户端里用 `/kasuga_effects workers N` 修改该设置，改动立即作用于已入队和后续的任务。

- 准备结果进入最大 128 条的 LRU 转换缓存。相等的 `ShaderProgram` 值复用已生成的 bundle 与已编码的 Minecraft 资源。并发请求对同一个程序只转换一次；多个工作线程可以并行转换不同的程序。

- 准备队列已满时，该注册进入失败状态。Kasuga 不会在调用线程上同步执行准备。

- 一条注册关闭或被替换时，Kasuga 取消尚未开始的准备，并把该任务从执行器队列中移除。

- 默认情况下，每帧编译队列在启动下一项之前检查 2 ms 预算。队列在一帧内最多启动四项。OpenGL 编译一旦开始就无法中止，因此实际帧耗时可能超过预算。

- 编译队列先按预加载优先级、再按队列顺序选取条目。优先级数值较小的先运行。

- 每一帧里，一个所有者可以使用前两个编译槽位，其他等待中的所有者拿剩余槽位。只有在没有其他所有者可运行时，第一个所有者才会拿到剩余槽位。

- `whenReady()` 只等待那条确切的注册。状态为 `READY` 时以 `ShaderStatus` 完成 future；状态为 `FAILED` 或 `CLOSED` 时以异常完成 future。替换同样以异常完成 future。若调用方取消 future，注册表会移除对应的等待者。

- 若 shader 已就绪，`whenReady()` 返回已完成的 future。其他异步完成发生在渲染线程。

- `ShaderLoadListener` 的就绪、失败与失效回调运行在渲染线程。若监听器抛异常，Kasuga 记录该异常，异常不改变 shader 状态。

- `DISABLE_PIPELINE` 把加载失败限制在当前 shader：该 shader 保持不可用，其他资源继续加载。

- 编译失败时，`FAIL_RELOAD` 中止正在进行的资源重载。晚到的预加载没有可中止的重载，此时 shader 状态变为 `FAILED`。

- 资源重载创建的 shader 由 Minecraft 管理；晚到预加载创建的 shader 由注册表管理。在渲染线程上，注册关闭或被替换时注册表释放该 shader。成功安装新一代之后，注册表也会释放旧 shader。


> 本主题的其他页面：[Java Shader DSL 参考](shader-dsl.en.md) 与 [托管效果与后处理](effects-and-postfx.en.md)。
