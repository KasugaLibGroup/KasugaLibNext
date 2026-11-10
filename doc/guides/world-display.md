# 蓝图与可嵌套世界显示

`WorldDisplayTarget` 是客户端显示子树的所有者，包含模型、面板和 proxy 控制器。
`ModelDisplayTarget` 接受任意 `MinecraftModelSource`，包括原版结构、Litematic、Create 和 UML 模型。
`WorldDisplayScene` 本身也是 `WorldDisplayTarget`，可以继续嵌套。

```java
var source = BlueprintImporters.load(client.level, Path.of("building.litematic"));
var building = new ModelDisplayTarget(source).fit(3).autoPanel();
var group = new WorldDisplayScene();
group.add(building);
var root = new WorldDisplayScene();
root.add(group).transform(new Matrix4f().rotateY(.3f));

// World mount takes ownership of root, including all nested model/UI resources.
var world = SceneModels.open(client.level);
world.add(root, new Vec3(10, 80, 10));
building.stacking().secondsPerLayer(.35).play();
// Pause/resume preserve progress. play() restarts; showAll() stops and assembles.
// world.close() closes the whole subtree and removes its world panels.
```

`fit()` 根据模型 bounds 缩放；`autoPanel()` 在模型旁生成与其一起移动的面板。
绝对世界位置交给 `SceneModels.Entry.position(Vec3)`，不要把远距离世界坐标塞入浮点局部矩阵。
子目标与父 scene 的矩阵同时作用于模型、panel 和拾取。不要把一个目标挂给两个父节点；环和重复所有权会被拒绝。
隐藏只影响显示，逻辑时钟继续推进。

`ModelDisplayPanel` 可放入任意 Screen 或 `UIBackend`。它直接控制同一个目标，支持重播、暂停/恢复、
速度、非空层切换、堆叠/单层、轴切换与展开；下面按方块注册 ID 显示当前层的数量。
计数忽略空气，同种方块的不同 block state 合并。长列表可滚动；非方块模型显示“无方块数据”。
`statistics()` 返回以源局部层坐标为键的只读表，不把动画中的显示位移计入材料数量。

```java
client.setScreen(new WorldDisplayScreen(Component.literal("Blueprint"),
    root, building, false, false, returnAction));
```

`WorldDisplayScreen` 的 `world` 参数选择透视世界检查或独立 3D viewer；`ownsTarget` 决定关闭屏幕时是否关闭目标。
挂在世界的目标使用 `ownsTarget=false`。独立 viewer 可用 `true`。
viewer 支持旋转、平移、缩放、重置与点击方块检查，面板按窗口大小安排。
屏幕只驱动尚未挂载的根；已挂世界的目标由 `SceneModels` 驱动。
更底层的 `GuiModelPreview(target.borrowedSource())` 只采样，关闭它只释放自己的画布。

播放沿用 `ProxySession` 与 `ManagedAnimationScope`，每个逻辑 tick 只有一个推进入口，渲染不会推进时钟。
可以在第一次 tick 前调用 `target.controller(RuntimeController<Void>)` 接入其他控制器，
例如已有的 `ProxyMachineController` 与模型 FSM；控制器必须使用 Scope checkpoint 管理可回滚状态。
目标可通过 `proxySession()` 暴露现有 proxy 命令、生命周期和 revision 接口。

本地导入 UI 是 `BlueprintImportScreen(WorldMount, returnAction)`。
开发客户端 `/kasuga_model_preview import` 或预览选择器中的“导入蓝图”打开它。
输入任意本地路径后可选择 viewer 或世界+panel；输入文件只读，失败显示原因并保留旧世界显示。
默认世界摆放距离在现有 UIBackend 的 5 格触达范围内；也可通过世界 UI 指针模式操作面板。
Create 工厂检查入口的“Animation + counts / 分层统计”打开相同的通用控制面板。

内置 importer 按 NBT 内容识别原版/Create 结构、Litematic v5–v7 和 Sponge/WorldEdit .schem v2–v3，支持 gzip 与未压缩 NBT，
不依赖文件扩展名。未知格式会明确拒绝，可以调用
`BlueprintImporters.register(id, matches, decoder)` 增加格式；decoder 返回原版 `StructureBlockInfo`，
其余分层、统计、panel 和 viewer 自动复用。旧数字 ID `.schematic` 等其他格式需相应 importer。
Sponge schema 参考 [官方 v2/v3 规范](https://github.com/SpongePowered/Schematic-Specification/tree/master/versions)。
沿用现有上限：输入 NBT 64 MiB、131072 方块、轴跨度 4096；缺失模组方块和更新版本的数据不会静默替换为空气。

验证入口：

```sh
./gradlew :modules:modelling:test --tests '*WorldDisplayTest' --tests '*LayeredModelSourceTest'
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=run/disposable-display-test \
  -PkasugaQuickPlayWorld=display-test -PkasugaTestWorldDisplay=true \
  -PkasugaWorldDisplayOutput=build/world-display-client
```

真实客户端 smoke 在独立测试存档中验证文件导入、逐层统计、真实 panel 按钮、暂停 RGBA 不变、
完成后的整帧像素与完整蓝图一致、嵌套世界 panel 输入及关闭清理，并输出截图和 `report.json`。
