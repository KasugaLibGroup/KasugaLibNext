# 模型碰撞、区块生命周期与远景

## 带动画的实体碰撞

在服务端为 `AnimationBlockEntity` 调用 `collisionModel(id)`。以 `example:models/gate.bbmodel` 为例，把同源几何放在 `data/example/models/gate.bbmodel`，客户端从资源包加载可见模型。数据包入口支持 Blockbench 和独立的 glTF/GLB。其他格式可以把已加载的 UML `Model` 包装成 `ServerCollisionModels.Asset`，再通过 `MinecraftModelCollisions.attach` 接入自定义方块实体。自定义宿主在服务端 tick 驱动句柄，并在拆除时关闭它。

装载时把每个骨骼关联的顶点从绑定姿态换算到骨骼局部空间，记录六个极值。带多个正权重的顶点进入各自关联骨骼的包围盒。服务端每 tick 用最终骨骼矩阵变换这些包围盒，使用 Minecraft 世界轴对齐碰撞形状参与实体移动。旋转后的 AABB 会覆盖盒子的外接范围。碰撞在游戏 tick 更新，服务端会把形状同步给对应区块的观察玩家，供本地移动预测使用。

## 玩家离开与返回

区块卸载时，宿主释放模型实例、动画绑定和碰撞。客户端还检查原始宿主对象是否仍在真实区块缓存中，以覆盖缩小视距造成的缓存淘汰。靠近时，客户端收到区块与方块实体，`onLoad` 或首个宿主 tick 挂载模型。资源尚在发布时，之后的 tick 会继续尝试。默认模型由全局渲染管线裁剪。使用 `VANILLA_RENDERER` 调度的模型由原版方块实体渲染回调标记，并在同一帧的方块实体阶段之后绘制。

- JVM 测试覆盖宿主实例释放、过期回调、首个可见帧，以及跨区块碰撞同步。运行 `./gradlew :modules:modelling:modelUnitTest`。
- 专用服务器 GameTest 用盔甲架验证绑定位姿、动画移位后的碰撞和销毁清理。运行 `./gradlew :modules:modelling:runGameTestServer`。
- 客户端测试在临时集成世界先挂载模型并收到碰撞，退到 512 格等两端卸载，再到 112 格和 48 格逐步接近，确认客户端重新创建实例并收到碰撞。运行 `./gradlew :modules:modelling:runClientHeadless -PkasugaTestModelLifecycle=true -PkasugaClientDirectory=/tmp/kasuga-model-lifecycle`。

## Voxy 与 Create

远景与地形共享主视图的投影和深度。Voxy 运行时走独立的模型透明通道，保留 Voxy 的地形通道。独立场景中，可以通过 `SceneModels.distantRange(blocks)` 扩展静态放置模型的远景距离。跟随宿主的放置项仍按宿主生命周期回收。

组合测试使用 Minecraft 1.21.1、NeoForge 21.1.228、Sodium 0.6.13、Voxy NeoForge 0.6.0 和 Forgified Fabric API 0.116.7+2.2.0。该版本的 Forgified Fabric API 要求 NeoForge 至少为 21.1.217。

服务端为距离玩家 512 格以内的活动 Create Contraption 同步简化方块网格，旋转和移动跟随服务端姿态。客户端在正常实体渲染可用时使用 Create 的渲染器。远景网格从 2 格一单元开始降采样，大型结构采用更粗的采样。方块实体渲染、运动部件特效和载具交互由近景路径处理。远景同步不会强加载服务端区块。服务端卸载或移除 Contraption 实体后，客户端移除远景网格。通过通用配置 `enable_contraption_lod` 可以开关此功能。

Create 客户端回归在 4 区块视距下组装真实风车，测试 320 格外的画面和深度变化、接近时的实体跟踪和远景移除。运行 `./gradlew :modules:create:runClientHeadless -PkasugaTestContraptionLod=true -PkasugaClientDirectory=/tmp/kasuga-contraption-lod`。在支持 Voxy 的图形环境里，把 Voxy 及其依赖放入该目录的 `mods/`，并加上 `-Pneo_version=21.1.228 -PkasugaTestContraptionLodVoxy=true`，测试会检查 Voxy 地形渲染器是否启用。
