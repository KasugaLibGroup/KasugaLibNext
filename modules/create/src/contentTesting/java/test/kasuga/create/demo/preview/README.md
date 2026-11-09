# Create model gallery

开发客户端的独立示例；正式能力在 `CreateModelSources`、`MinecraftModelSources`、
`GuiModelPreview` 和 `SceneModels`。示例、维度和自动化 smoke 均属于 `contentTesting`，不进发布 jar。

`/kasuga_model_preview` 打开来源菜单。点击来源进入 UI，或点击 `World` 在玩家面前显示一个
缩小的世界模型；`/kasuga_model_preview clear` 清除。UI 内左键旋转、中键平移、滚轮缩放，
R 重置，底部按钮控制逐层/单层和爆炸间距。
短按左键显示方块名、注册 ID 与源坐标；拖拽旋转不会误触点击。

`/kasuga_create_gallery` 进入独立的空中展示维度。钻探平台包含 Create 齿轮、轴、风帆、
框架、管道、驾驶舱、十个钻头和六个机械手，使用原生 Contraption、BE、actor renderer。
玩家处于创造飞行状态，可以 WASD/鼠标绕行、空格上升、Shift 下降。
准星对准模型后左击，会显示方块信息 5 秒，点击不向真实世界发送破坏动作。
按 I 打开世界模型的 inspection 面板：逐层/单层/全部、爆炸间距、整体缩放和重置。
面板保持世界画面可见；Done、I 或 Esc 关闭面板，再继续飞行环绕。
面板的 `Rig / Windmill` 切换到真实风车。风车由服务器原生 `WindmillBearingBlockEntity`
装配并运行 `ControlledContraptionEntity`；显示端通过 `contraption.entity` 关联借用它。
转子物理源与展示位置分开，分层、爆炸和单层模式继续采样实时转角，不暂停或修改实体运动。
`/kasuga_create_gallery stop` 清理模型并恢复进入前的世界、位置、模式和飞行状态。

模型是客户端 scene 内容，不是真实放置的建筑，也不参与动力网络、碰撞或破坏。
展示位置固定为 `(0, 108, 0)`，在玩家转动或移动相机时保持世界位置。

启动并自动进入 gallery（参数仅打开一次，无自动移动或退出）：

```sh
./gradlew :modules:create:runClient -PkasugaCreateGallery=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=preview
```

UI 自动化与世界自动化是独立任务。用隔离世界运行，查看 JSON 的 `passed`，不能只看 Gradle 退出码：

```sh
./gradlew :modules:create:runClientHeadless -PkasugaTestMinecraftPreview=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=preview
./gradlew :modules:create:runClientHeadless -PkasugaTestCreateGallery=true \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=preview
```

报告分别为 `ksg_debug/minecraft-preview/report.json` 和 `debug/create-gallery/report.json`。
后者包含正面、两侧、背面、爆炸图、隐藏和返回原世界检查，截图来自实际游戏帧缓冲。

只读打开本机 Create 结构 NBT：

```sh
./gradlew :modules:create:runClient -PkasugaMinecraftPreviewInteractive=true \
  -PkasugaPreviewSchematic=/absolute/path/to/structure.nbt \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=preview
```

在菜单点击 `external-create-schematic` 打开 UI 预览；点击同一行右侧 **Inspect** 进入工厂世界检查，
会自动打开分层/爆炸控制面板。Done、I 或 Esc 关闭面板后可自由飞行，再按 I 打开。
也可执行 `/kasuga_create_gallery factory` 直接进入，或在已有面板里点 **Factory**。
面板支持工厂与 Rig / Windmill 切换，工厂加载和几何缓存会在会话内复用。
源文件不会复制到资源包或提交。
`JAVA_TOOL_OPTIONS=-Dkasuga.testExternalSchematic=true` 可运行导入与实际截图验收；
`JAVA_TOOL_OPTIONS=-Dkasuga.testWindmill=true` 则验证真实转子在 assembled、exploded、single slice
三种模式中跨 tick 的角度与实际几何深度像素都在变化。两种 smoke 分别写入
`debug/external-schematic/report.json` 与 `debug/windmill/report.json`。
`JAVA_TOOL_OPTIONS=-Dkasuga.testFactoryInspection=true` 从真实菜单的 Inspect 按钮开始验证工厂
分层、爆炸、缩放、拾取、目标切换及退出恢复，报告为 `debug/factory-inspection/report.json`。

性能与缓存验收（使用独立游戏目录，保持蓝图/分辨率/相机一致）：

```sh
JAVA_TOOL_OPTIONS='-Dkasuga.testSchematicPerformance=true -Dkasuga.schematicPerformance.output=/absolute/path/to/report' \
  ./gradlew :modules:create:runClientHeadless -PkasugaPreviewSchematic=/absolute/path/to/structure.nbt \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=preview
JAVA_TOOL_OPTIONS='-Dkasuga.testGeometryCache=true' \
  ./gradlew :modules:create:runClientHeadless -PkasugaPreviewSchematic=/absolute/path/to/structure.nbt \
  -PkasugaClientDirectory=/absolute/path/to/isolated-client -PkasugaQuickPlayWorld=preview
```

性能报告分别列出完整、局部层、局部爆炸和全部爆炸的首帧、稳态 CPU/提交纳秒、分配量与 JFR；
它们是预览绘制耗时，不是纯 GPU 时间或游戏 FPS。缓存验收在同帧对比即时/缓存 RGBA，
检查改变视图/间距不重建几何、实际资源重载后重建以及关闭后 VBO 回收。

The development-only gallery uses the public source and scene APIs. `/kasuga_create_gallery`
opens a floating native Create contraption in a disposable dimension with creative flight;
`/kasuga_create_gallery stop` restores the original player state. It is a visual model, with no
live kinetic network, collision or block placement. Use `-PkasugaTestCreateGallery=true` for the
separate opt-in world pixel/depth test; ordinary gallery mode never drives the player or exits.
