# 托管效果与后处理

## 可变换粒子实例与整组控制

粒子与托管效果使用不同的生命周期模型：

- `ParticleInstance` 是被动的、可变换的渲染实例。一个粒子实例包含完整的 `Transform`、速度、颜色以及通用的每实例属性。`ParticleInstance` 不要求实现 `tick()`。

- `ParticleSource` 是持续发射的粒子源。应用代码可以放置并移动该源。发射速率以每 tick 粒子数计，支持小数累积。源设置控制粒子工厂、初速度、重力、颜色、初始大小、初始旋转和生命周期。修改源设置只影响新生成的粒子。

- `ParticleState` 向 `ParticleOperator` 提供一份状态值。该状态包含空间 `Transform`、速度、颜色、属性和生命周期进度。`Transform` 包含位置、大小和旋转，速度是独立的物理量。`ParticleOperator` 接收当前状态并返回下一个状态。

- 外部物理系统可以通过 `transform(...)` 替换或原子地改动整个实例变换。

- `ParticleGroup` 拥有自己的实例，并提供两种更新方法。两种方法都分两个阶段执行：通用方法先为整组建立稳定快照，再提交所有改动；打包 buffer 方法把当前状态与下一个状态存进 direct buffer。

- `ParticleGroupBehavior` 在一次调用里更新整组。烟雾流场、雨、雪、爆炸和相对摄像机的天气体适合用这个行为。

- `ParticleBufferGroupBehavior` 在可复用的 `ParticleInstanceBuffer` 里更新整组。本地序（native-order）的 direct `ByteBuffer` 存放 ID、矩阵、速度、颜色、属性和标志位。容量稳定之后，tick 与渲染操作不再创建每实例的快照或更新对象。

- `ParticleBehavior` 是可选的单实例行为，它读取更新前的整组快照。需要邻居数据的鱼群、鸟群和虫群适合用这个行为。

- `ParticleGroupSnapshot.near(position, radius)` 在首次查询时构建空间网格。同半径的查询在一次更新内复用该网格，因此 Boids 不必为每个实例扫描整组。

- 若整组控制器与实例行为都要更新同一个实例，以实例行为的结果为准。返回 `null` 表示不更新该实例。

- `ParticleBatchRenderer` 每次调用都接收一个可复用的 `ParticleInstanceBuffer`。矩阵、位置、速度与颜色的 getter 可以写入目标参数，以避免分配临时对象。回调式 renderer 可以把实例展开进 Minecraft 顶点缓冲。`ParticleInstancedBatchRenderer` 以硬件实例化提交实例。

- 客户端切换或卸载世界时，Kasuga 清空粒子组与已放置的粒子源。若某实例既没有控制器也没有行为，运行时不会移动该实例。

下面的例子注册一条批处理管线：

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

下面的例子在可放置的方块烟雾源里组合物理算子和外观算子：

```java
ParticleOperator cubeSmoke = ParticleOperators
        // 施加浮力、阻力和速度积分。
        .physics(new Vector3f(0, 0.0035f, 0), 0.985f)
        // 在同一个状态函数里控制大小、旋转与不透明度。
        .then(ParticleOperators.scale(1.012f))
        .then(ParticleOperators.rotate(new Vector3f(0.006f, 0.018f, 0.004f)))
        .then(ParticleOperators.fade(0.975f));

ParticleSource source = smoke.source(ParticleSource.Settings.builder()
        .position(new Vector3f(12, 70, -4))
        .emissionRate(0.75f)
        .particleType(spawn -> ParticleInstance.builder(spawn.transform())
                // renderer 可以用这个属性选择立方体、sprite 或自定义 mesh。
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

// 该源可以跟随方块、实体或脚本锚点。
source.position(nextPosition);
```

`ParticleOperators.transform(...)` 接受 `UnaryOperator<Transform>`：算子接收当前变换并返回下一个变换。受重力影响的粒子使用默认的 `integrate()` 和 `.affectedByGravity(true)`。源的 `particleType` 是实例工厂。相关 `ParticleRenderPipeline` 的 renderer 决定画立方体、billboard 还是 mesh。

默认粒子类型直接使用源设置。自定义工厂可以用 `spawn.sequence()` 获得稳定的随机值，并在构造期间修改位置、速度、大小或颜色。

硬件实例化使用与后端无关的 mesh 与绘制契约：

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

- `ParticleInstanceMesh`、`ParticleInstanceBuffer`、`ParticleInstanceShaderPrograms` 和 `ParticleInstanceRenderBackend` 中不含 VAO、VBO 或 LWJGL 类型。

- alpha 混合的 renderer 可以复用 `ParticleDepthSorter`。排序器由远及近提交实例，开启深度测试并关闭深度写入。需要逐粒子面排序时，quad 管线可以用 `sortOnUpload(true)`；`pipeline.sortBackToFront(true)` 在渲染前重排打包好的实例 buffer。因此回调式 renderer 与硬件实例化 renderer 使用同一套半透明排序。

- 当前 `OpenGlParticleInstanceBackend` 拥有全部 OpenGL 资源、属性和 `glDrawArraysInstanced` 调用。基础 mesh 用一个静态 VBO，打包实例 buffer 用一个 stream VBO；stream VBO 按需扩容并每帧更新。一个实例组对应一次 draw 提交。

- Kasuga Shader IR 描述实例的矩阵与颜色语义。粒子 API 里不含 GLSL。Vulkan 后端可以复用 mesh、打包布局、模拟和 Shader IR，只需替换 `ParticleInstanceRenderBackend` 实现及其 shader 编译器。

- 当前标准布局每个实例占 144 字节，向 GPU 暴露 `mat4` 的四列和颜色。速度和属性留在打包 buffer 中供模拟使用，标准 shader 不读取速度和属性。

- 目前不提供半透明实例的 GPU 深度排序。需要精确半透明顺序的效果，请用加法混合、加权 OIT，或在实例上传前先做排序。

内置行为预设：

| 预设 | 更新方式 | 用途 |
| --- | --- | --- |
| `SmokePlumePreset` | 整组控制器 | 解析式上升、扰动、扩张、淡出与回收 |
| `GasSmokePreset` | 打包 buffer 控制器 | 密度与速度体、浮力、扩散、压力投影与示踪粒子平流 |
| `LiquidFlowPreset` | 打包 buffer 控制器 | 低粘度不可压缩速度场、重力、压力投影与示踪粒子平流 |
| `RainFieldPreset` | 整组控制器 | 风驱动下落、相对摄像机或相对世界的体、超范围复用 |
| `BoidsPreset` | 可选实例行为 | 分离、对齐、聚集与组边界 |

`GasSmokePreset` 与 `LiquidFlowPreset` 共用 `StableFluidGrid3D`。半拉格朗日平流、高斯-赛德尔扩散与压力投影使用预分配的 direct `FloatBuffer`。该实现是面向实时效果的 Stable Fluids 近似，主要计算复杂度为 `O(gridSize^3 * iterations)`。气体使用默认的封闭求解边界。液体使用开放边界：示踪粒子离开有限求解网格后保留其世界空间动量与重力，不会被裁剪到模拟体内。`FluidTracerCollision3D` 在该网格之外的世界坐标中仍然有效。Minecraft 方块适配器用已加载区块中的扫掠碰撞形状查询实现该接口，因此不受限的示踪粒子会继续与方块碰撞、滑动，且不强制加载区块。

要提高模拟细节：

1. 增加示踪粒子数量。
2. 仍不够时，提高网格分辨率。

`FluidEnvironment3D` 组合流体环境约束。每个模拟步都在压力求解器启动之前施加这些约束：

```java
liquid.environment(FluidEnvironment3D.builder()
        .add(FluidConstraints3D.solidBox(minimum, maximum))
        .add(FluidConstraints3D.solidSphere(center, radius))
        .add(FluidConstraints3D.directionalForce(regionMin, regionMax, acceleration))
        .add(FluidConstraints3D.source(regionMin, regionMax, densityPerSecond, velocity))
        .add(FluidConstraints3D.drain(regionMin, regionMax, densityRetention, acceleration))
        .build());
```

应用代码可以实现 `FluidConstraint3D` 来定义自定义动态约束。约束上下文提供归一化的网格单元坐标、密度、速度与固体掩码。应用代码可以从方块碰撞形状、管道网络或移动力场生成约束。求解器与预设都不依赖 Minecraft 世界。

下面的例子通过独立适配器接入 Minecraft 方块碰撞：

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

- 适配器扫描模拟体范围内已加载区块中真实的方块 `VoxelShape`。它把楼梯、台阶、栅栏等碰撞盒栅格化为固体单元，不会把所有非空气方块都当作完整立方体。

- 碰撞盒与流体单元中心对齐。这样对齐可以避免墙面向外扩张半个单元。较薄的碰撞盒映射到最接近碰撞盒中心的单元，因此栅栏这类薄物体仍会留在固体掩码里。

- 求解器清空固体单元中的密度与速度。在相邻的流体单元中，求解器把指向固体单元的速度夹取掉，压力投影与平流随后绕开方块。若粗网格的一步把液体示踪粒子移进固体单元，该示踪粒子回到上一位置。

- 扫描结果按配置的游戏 tick 间隔留在缓存中。体积移动、level 变化或调用 `invalidate()` 之后，适配器重建结果。适配器跳过未加载的区块。流体模拟不强制加载区块。

- 碰撞只从方块作用于流体。模拟出的水不会推动活塞或实体，不会弄湿方块，也不改变原版的含水状态。模拟不会放置或移除 Minecraft 流体方块。

在这套 API 里，液体指的是一块不可压缩的速度与密度体。固体掩码是近似的无滑移障碍物。该模型不包含自由表面、表面张力、液滴合并，也不包含守恒的 FLIP 或 PIC 粒子，同样不包含流体与刚体之间的双向作用。该模型支持水流、魔法液体、管道流动这类视觉效果，但不是完整的水体求解器。

以下命令在开发客户端里控制粒子预设：

| 命令 | 结果 |
| --- | --- |
| `/kasuga_particle_preset smoke` | 启动烟雾预设。 |
| `/kasuga_particle_preset cube_smoke` | 启动方块烟雾预设。 |
| `/kasuga_particle_preset liquid` | 启动液体预设。 |
| `/kasuga_particle_preset rain` | 启动降雨预设。 |
| `/kasuga_particle_preset boids` | 启动 Boids 预设。 |
| `/kasuga_particle_preset all` | 启动所有预设。 |
| `/kasuga_particle_preset clear` | 停止并清空所有预设。 |
| `/kasuga_particle_preset status` | 显示每个组的实例数量。 |

普通烟雾在每个实例化立方体内部对湍流三维密度场做光线步进，并沿视线积分不透明度。立方体只是体的边界；片元 shader 丢弃空采样，因此看不到 billboard 或实心立方体表面。方块烟雾开启背面剔除，还为每个粒子的位置、速度和颜色加入稳定扰动。这些设置避免半透明的正面与背面被重复混合。

## 托管效果

- `EffectRenderPipeline` 负责效果实例的 tick、生命周期检查、视锥剔除、可选的由远及近排序，以及 renderer 调用。

- 客户端未暂停且存在世界时，Kasuga 在每个客户端 tick 更新一次每个活跃效果。

- 效果 tick 之前或之后，Kasuga 会移除已不存活的效果。若 tick 抛出运行时异常，Kasuga 只移除相关实例，并记录该异常。

- 绘制遍历用插值包围体做视锥剔除。开启排序时，Kasuga 按插值位置到相机的距离由远及近排序。一帧之内，每个可见效果只计算一次该距离。效果可以覆写 `distanceToSqr(partialTick, observer)`，以避免分配临时位置对象。

- 只有至少一个实例可见时，renderer 才调用 `begin` 与 `end`。一帧之内，每条管线各自调用这两个方法一次。

- 若某个实例在渲染时抛异常，Kasuga 记录该异常并继续处理其他实例。Kasuga 不会自动移除失败的实例。

- `spawn()`、`clear()` 和 `EffectHandle.remove()` 允许来自不同线程的调用。Kasuga 在下一个客户端 tick 或绘制遍历时应用列表改动。

- 只有第一次成功移除时，`EffectHandle.remove()` 才返回 `true`。

- 客户端切换或卸载世界时，Kasuga 清空所有效果管线中的全部实例，同时清空后处理目标池。客户端暂停时 Kasuga 不 tick 效果。

- 效果管线关闭后拒绝新实例。已关闭的管线不参与 tick 与绘制。

同一类效果的实例共享 renderer 与管线状态，每个实例由独立的句柄控制。下面的例子生成并移除一个效果：

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

## 后处理 pass

- 后处理 pass 必须使用语义阶段 `RenderPhase.POST_PROCESS`。其他执行阶段都会被注册拒绝。

- pass 始终运行在渲染线程上。pass 结束或抛异常之后，Kasuga 重新绑定主渲染目标。

- 目标描述支持相对屏幕的尺寸或固定尺寸，也支持可选的深度附件、nearest 或 linear 过滤以及清屏颜色。解析出的宽高最小为 1 像素。

- 目标池复用物理 ID 相同的目标。描述或解析尺寸变化时销毁旧目标，再由目标池创建新目标。

- 颜色拷贝的源与目标为同一个目标时失败。深度拷贝在任一目标没有深度附件时失败。

- fullscreen 绘制只接受 `BLIT_SCREEN` shader。绘制过程会保存并恢复 GL 状态。

- fullscreen 绘制拒绝从当前输出目标采样颜色或深度，这条限制用于避免 framebuffer feedback。调用方必须自行保证直接绑定的外部纹理 ID 不会造成 feedback。

## 后处理图

- 图的 ID 必须与对应管线描述的 ID 一致。

- 图构造期会拒绝重复目标、重复 pass 和未声明的目标。Kasuga 拒绝没有生产者却读取托管目标，也拒绝多个写者对同一托管目标，还拒绝缺失的显式依赖和依赖环。

- 每个 pass 至少要声明一个输出。一个 pass 不能同时读写同一个目标。

- 主目标是外部目标，允许多个 pass 写入。其余目标都是由图托管的托管目标。

- pass 顺序先满足显式的 `after` 依赖，再满足读写关系带来的依赖。在所有可执行的 pass 中，优先级数值较小的先运行。优先级数值相同时，Kasuga 按 pass ID 排序。

- `prepare` 在托管目标分配与 pass 执行之前运行。若 `prepare` 返回 `false`，Kasuga 在该帧不分配目标也不运行 pass。

- 每次图执行都会新建一个 `PostProcessGraphFrame`。本次 `prepare` 调用与后续 pass 共享该帧数据。读取按声明的类型进行，数据缺失或类型不同时读取抛异常。

- pass context 只能读写该 pass 声明的目标。访问未声明的目标会在运行时失败。

- Kasuga 把每个逻辑图目标转换成包含图 ID 的物理 ID，因此不同图里相同的逻辑名不会冲突。物理 ID 与替换后的目标描述在图构造期算出，图执行时复用这些值。

- 托管目标按每条图注册计引用数。最后一条图注册关闭时，Kasuga 在渲染线程释放该目标。

- `prepare` 抛异常时，Kasuga 补上图 ID 再抛出。pass 抛异常时补上 pass ID。world pipeline 分派器记录该异常，后续管线继续运行。

- `raw()` 绕过图的资源访问限制。调用 `raw()` 之后，资源声明与 feedback 安全由调用方负责。

下面这张图先把主画面保存下来，再用一个 fullscreen shader 把画面合成回主目标：

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

## 开箱即用的 PostFX 预设

`PostFxPresetDemo` 只用一条生成的 shader 注册，且该注册跨重载保持有效。参数块选择不同的调色设置，切换预设不会重新注册或重新编译 shader。

| 预设 | 开发客户端命令 |
| --- | --- |
| 电影感调色 | `/kasuga_postfx cinematic` |
| 高对比黑白 | `/kasuga_postfx noir` |
| 高饱和鲜艳色彩 | `/kasuga_postfx vivid` |
| 关闭 | `/kasuga_postfx off` |

`/kasuga_effects` 显示图、shader 状态与可调参数。

## 诊断与参数界面

- `/kasuga_effects` 打开客户端诊断与参数界面。该界面不会暂停游戏。

- Effects 页显示所有者、管线 ID、效果类型、活跃数量、可见数量与最近一次渲染耗时。

- Shaders 页显示所有者、来源、状态、策略、优先级、加载来源、generation、队列等待、准备耗时、编译耗时、缓存结果、暴露参数数量与失败信息。

- shader 摘要显示准备执行器请求的与实际的工作线程数，显示可用 CPU 数，还显示渲染线程上预加载调度器的队列状态。

- `Parameters` 打开通用 schema 参数编辑器。该编辑器对内置效果和黑洞示例都没有特殊逻辑。

- Pipelines 页显示所有者、执行阶段、优先级、绘制模式、缓冲大小，以及缓存的 `RenderType` 变体数量。

> 本主题的其他页面：[自定义渲染规范](effect-rendering.en.md) 与 [Java Shader DSL 参考](shader-dsl.en.md)。
