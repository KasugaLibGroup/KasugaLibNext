# Minecraft 结构与 Create 模型预览

`MinecraftModelSource` 是 Minecraft 原生内容与 Kasuga 模型的共同显示入口。`GuiModelPreview`
接收这个入口后，仍可交给原版 `ModelPreviewWidget`、LDLib2 `ModelPreviewElement`，或放进
`UIBackend` 的世界面板。预览持有独立的深度画布；源数据不会被放置进游戏世界。

创建、采样、渲染和关闭 source 都在 Minecraft 客户端渲染线程执行。`setView`、`setLayers`
只发布不可变的显示参数，可以从 UI 线程调用。

```java
var minecraft = Minecraft.getInstance();
var source = MinecraftModelSources.structure(minecraft.level,
        ResourceLocation.parse("minecraft:end_city/base_floor"));
var preview = new GuiModelPreview(source);
var frame = source.snapshot(LayerLayout.ASSEMBLED, 0);
minecraft.setScreen(new MinecraftModelPreviewScreen(Component.literal("末地城"), preview,
        (int) Math.floor(frame.bounds().minY()), (int) Math.ceil(frame.bounds().maxY()) - 1));
```

这个完整查看器支持左键拖动旋转、中键拖动平移、滚轮缩放和 R 重置视图，
以及逐层码放、单层显示、恢复全部层和爆炸间距滑块。
旋转和平移须从画布内开始，拖出画布后仍捕获到释放；从底部控件开始的拖动只操作该控件。
关闭屏幕会关闭 preview；宿主屏幕使用独立 widget 时，仍应在 `removed()` 中调用 widget 的 `close()`。
画布中短按左键可以查看方块名称、注册 ID 和源坐标；拖动仍为旋转。

| 来源 | 工厂 |
|---|---|
| 游戏自带建筑 / 单人数据包中的结构 | `MinecraftModelSources.structure(level, ResourceLocation)`，例如 `minecraft:end_city/base_floor` |
| 结构方块保存的压缩 NBT | `MinecraftModelSources.structure(level, Path)` |
| 已加载的原版模板 | `MinecraftModelSources.structure(level, StructureTemplate)` |
| Litematica / Forgematica `.litematic` v5–v7 | `MinecraftModelSources.litematic(level, Path)` |
| 客户端已有方块区域 | `MinecraftModelSources.capture(level, minimum, maximum)` |
| 外部投影 / 自定义方块数据 | `MinecraftModelSources.blocks(level, List<StructureBlockInfo>)` |
| Create 机器区域 | `CreateModelSources.machine(level, minimum, maximum)` |
| Create 活动结构实体 | `CreateModelSources.contraption(AbstractContraptionEntity)` |
| 已关联实体的 Create Contraption | `CreateModelSources.contraption(Contraption)`，使用 Create 自己的 `entity` 反向引用 |
| Create 蓝图物品 | `CreateModelSources.schematic(level, ItemStack)` |
| Create 已加载的蓝图模板 | `CreateModelSources.schematic(level, StructureTemplate)` |
| 本机 Create 结构 NBT | `CreateModelSources.schematic(level, Path)` |

Create 工厂位于可选的 `modules/create`，包名为 `lib.kasuga.create.client.model`。公共建模模块
不直接依赖 Create。完整 Contraption 使用 Create 自己的 `ContraptionRenderInfo` 几何与
`MovementBehaviour.renderInContraption`；完整蓝图使用 Create `SchematicRenderer`。
分层时，方块沿原生 baked-model/方块实体 renderer 绘制，actor 按其所在方块的层归属移动。
虚拟世界使用普通 renderer，因此世界中的 Flywheel 实例化不会使 UI 的机器部件被跳过。

`.litematic` 导入保留多区域的位置、负方向 Size、连续位打包的调色板及方块实体 NBT。
旧版 Minecraft 数据经原版结构 DataFixer 更新；来自更新 Minecraft 版本的数据会被拒绝。
遇到缺失模组的方块会返回明确异常，避免将其静默变为空气。文件与内存导入工厂在失败时抛异常；
source 的绘制失败会让 `preview.render(...)` 返回 `false`，并由 `preview.failure()` 提供原因。

显示参数只影响本地显示：

```java
preview.setView(-.55f, .25f, 1, .1f, -.05f);             // 弧度、倍率、向右 10% / 向上 5%
preview.setView(-.55f, .25f, 1);                         // 三参数重载清零平移
preview.setLayers(LayerLayout.ASSEMBLED.range(0, 5));      // 从底部累计展示至第 5 层
preview.setLayers(LayerLayout.ASSEMBLED.range(5, 5));      // 单层
preview.setLayers(LayerLayout.ASSEMBLED.separation(1.25)); // 每层增加 1.25 格间距
preview.setLayers(LayerLayout.ASSEMBLED.axis(LayerLayout.Axis.X).separation(.75));
preview.setLayers(LayerLayout.ASSEMBLED);                 // 恢复原状
```

层坐标属于源的局部空间，默认按 Y 分组。空层间隔仍会保留；隐藏层不改变其余层的位移，
相机始终使用整份爆炸模型的边界，逐层显示时不会反复缩放。被剥开的层会重新暴露原来被邻层遮住的面。
连接纹理和模型数据始终读取完整原结构；分层仅改变切面遮挡，保留方块本身的朝向与连接状态。
默认最多接受 131072 个方块、4096 层、每轴 4096 格跨度，以及解压后 64 MiB 的 NBT。
预览不会 tick 方块实体、播放声音、发送包或改变真实实体姿态。
Create 缓存蓝图 renderer 的 bounding volume 单独限制为 1048576 格；实际方块数仍遵循上述上限。

静态方块和流体几何按完整模型或轴/层缓存；旋转、平移、缩放和爆炸间距只改变显示矩阵。
方块实体、actor、真实 Contraption 转角和动画贴图仍继续更新，透明四边形保留共享排序。
首次进入一种层布局需要构建几何；资源重载会重建缓存，关闭或换世界会释放。
Create 完整蓝图保留原生的实时方向光着色。自定义 `LayeredModelSource` 可用 `geometryKey()`
返回不可变几何的身份值，几何改变时换 key；默认 `null` 保留动态逐帧规划。

同一个画布里组合 UML 模型与 Minecraft 内容：

```java
var source = MinecraftModelSources.compose(
        new MinecraftModelSources.Placement(structure, new Matrix4f()),
        new MinecraftModelSources.Placement(
                MinecraftModelSources.model(() -> alreadyLoadedKasugaModel),
                new Matrix4f().translate(2, 1, 0).scale(.5f)));
var preview = new GuiModelPreview(source);
```

组合源接管子源的关闭责任；同一 source 不能交给两个 owner。UML asset 本身仍由原模型加载器持有，
预览只创建私有 `ModelInstance` 和 GPU 状态。外部蓝图还可以实现 `LayeredModelSource`，提供局部
`Element` 列表并实现 `renderSlice`，从而使用自己模组的原生 renderer。

原生/组合源的 `GuiModelPreview` 提供可采样的颜色和深度：

```java
preview.setFrameProcessor(input -> {
    // input.color() 是借用的 FrameTexture；input.depthTexture() 是当前画布的深度纹理。
    // 可用 FullscreenPassRenderer 或已有后处理 shader 绘制到调用方持有的另一个 target。
    runMyPostProcess(input.color(), input.depthTexture(), myTarget);
    return new FrameTexture(myTarget.frameBufferId, myTarget.getColorTextureId(),
            myTarget.width, myTarget.height);
});
```

输出必须与输入尺寸一致；回调创建的 target 由回调宿主管理。禁止读取并同时写入同一个 attachment。
`currentFrame()` 是借用结果，有效期到下一帧、缩放或关闭。原有资源地址/Supplier 构造器保留原来的
renderbuffer 深度兼容性；需要深度纹理时使用 source 构造器。

同样的 source 也可加入已有 scene，在世界后处理之前与模型共享场景深度：

```java
var scene = SceneModels.open(minecraft.level);
scene.add(source, new Vec3(10, 70, 10))
        .layers(LayerLayout.ASSEMBLED.separation(.5))
        .transform(new Matrix4f().rotateY(.4f).scale(.75f));
// 场景结束时 scene.close()；换世界也会自动释放。
```

位置为世界坐标；玩家绕行时模型保持原位，深度与世界共享。`Entry.position(...)` 可移动模型，
`transform(...)` 设置局部旋转/缩放，`visible(false)` 隐藏；这些操作和 scene 的创建/关闭都在渲染线程。
具体 UI 与世界样例位于 `test.kasuga.modelling.demo.preview.MinecraftPreviewDemo`，
Create 样例位于 `test.kasuga.create.demo.preview`。开发客户端可执行 `/kasuga_model_preview`；
`/kasuga_create_gallery` 进入可飞行环绕的悬空机械平台，`/kasuga_create_gallery stop` 返回。
详细步骤见 [Create gallery 示例](../../modules/create/src/contentTesting/java/test/kasuga/create/demo/preview/README.md)。

方块拾取也是公共能力，使用源方块的原版 outline shape，并遵循分层、爆炸、组合与场景变换：

```java
// GUI：最新显示帧，坐标为画布左上角开始的 0..1 比例。
preview.pick(.5, .5).ifPresent(hit -> inspect(hit.state(), hit.position()));
// 世界：from/to 与命中点是世界坐标；position 保持原结构中的方块坐标。
scene.pick(eye, eye.add(direction.scale(64)), partialTick).ifPresent(hit -> inspect(hit.state(), hit.position()));
```

无方块元数据的 UML 源返回空命中。`ModelBlockInfo.draw(...)` 可复用查看器的信息卡。
Create gallery 里准星对准模型后左击，会显示 5 秒信息，点击不会破坏方块。
按 I 打开透明的世界模型控制面板，支持逐层/单层/全部、爆炸间距、整体缩放与重置；
Done 或 Esc 关闭后继续绕行，当前分层和爆炸状态仍然保留。
面板中的 `Rig / Windmill` 可切换真实风车实体：原生轴承负责 tick 与同步，显示源持续读取
`ControlledContraptionEntity` 的转角，分层、单层和爆炸图仍继续旋转。
携带 `-PkasugaPreviewSchematic=...` 启动开发客户端后，来源菜单中工厂行右侧的 **Inspect**
进入同一个世界检查面板；也可执行 `/kasuga_create_gallery factory` 或点面板内 **Factory**。
工厂同样支持 I 面板、分层、爆炸图、缩放、重置和方块点击信息。

验证命令：

```bash
./gradlew :modules:modelling:modelUnitTest --tests 'lib.kasuga.rendering.models.mc.scene.*'
./gradlew :modules:modelling:test --tests 'lib.kasuga.rendering.models.mc.scene.SchematicImportTest'
./gradlew :modules:create:compileJava :modules:create:compileContentTestingJava
./gradlew :modules:modelling:runClientHeadless -PkasugaTestMinecraftPreview=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=fixture
./gradlew :modules:create:runClientHeadless -PkasugaTestMinecraftPreview=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-create-client -PkasugaQuickPlayWorld=fixture
```

客户端 smoke 使用原版末地城建筑、生成的负尺寸 `.litematic` 和可选 Create 蓝图/钻头 actor。
它读取 RGBA，操作真实层按钮/爆炸滑块及旋转、平移、缩放、重置手势，
检查透明背景、可采样深度、后处理、调整窗口和关闭后的附件释放；
结果写入游戏目录的 `ksg_debug/minecraft-preview/`。数据/编译测试成功不能代替这些像素与交互验收。

可见客户端的人工测试入口：

```bash
./gradlew :modules:create:runClient -PkasugaMinecraftPreviewInteractive=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-create-client -PkasugaQuickPlayWorld=fixture
```

进入世界后显示来源选择菜单。Esc 从预览返回菜单，再按 Esc 返回游戏。
此模式没有自动点击、切换、超时或退出；仅开发用 contentTesting 源集提供此菜单。
