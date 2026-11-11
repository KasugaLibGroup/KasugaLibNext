# 航空学 / Sable 的场景宿主

航空学的物理结构由 Sable `SubLevel` 承载，其方块保存在独立的 plot 坐标空间。
`SableSceneHosts` 通过官方 Sable Companion API，把这些结构接入已有 `SceneHost`、
`SceneModels` 和世界 UI。代码位于 `modules/create` 的客户端适配包；不修改 Sable 或航空学源码。

## 方块锚点

```java
// plotBlockPos 是方块实际存储位置，例如 BlockEntity.getBlockPos()，不是投影后的世界位置。
SableSceneHosts.block(minecraft.level, plotBlockPos).ifPresent(host -> {
    var scene = SceneModels.open(minecraft.level);
    scene.add(myDisplayScene, host);
    Constants.UI_BACKEND.addRenderable(myWorldPanel, host,
            new Matrix4f().translation(0, 2, 1).scale(.01f, -.01f, .01f));
    // 保存 scene 的引用，在内容所属生命周期结束时 scene.close()。
});
```

局部 `(0,0,0)` 对应锚点方块的下角。模型与 UI 的偏移以方块为单位，UI 矩阵把像素映射到
宿主局部空间。场景附属面板会自动共用模型的采样姿态；独立面板则在 UI 绘制帧采样。

适配器调用 `ClientSubLevelAccess.renderPose(partialTick)`，先用双精度将 plot 锚点经过
旋转中心、朝向及缩放转换到世界坐标，再构造附近的浮点基。不会先把远处的 plot 原点
转换成 `Matrix4f`，也不会用未插值的逻辑物理姿态绘制。

没有安装 Companion、目标不在子世界内、区块/锚点未就绪时，工厂返回 `Optional.empty()`。
已知属于 Sable 的目标应等待就绪后重试，不能在等待期间按普通世界位置挂载。
`isAvailable()` 只表示可选 API 在 classpath 中，不表示指定位置一定存在子世界。

## 船体上的普通 Create 结构

```java
SableSceneHosts.contraption(entity).ifPresent(host -> {
    var source = CreateModelSources.contraptionLocal(entity);
    scene.add(new ModelDisplayTarget(source), host);
});
```

这个入口按顺序组合 Sable 的父姿态与 Create 的实体局部姿态，避免重复应用旋转。
独立于 Sable 的普通 Create 结构继续用 `CreateSceneHosts.contraption(entity)`。
模型源采用 `contraptionLocal`；原独立预览源已经包含旋转，不适用于再次挂同一个移动宿主。

## 生命周期和业务边界

适配器持续解析原锚点/子实体所在的子世界，并检查原对象身份及 UUID。原锚点被破坏、
拆分后被移出原 plot、子实体离开原子世界、卸载或世界切换时，旧挂载永久失效。
之后重新出现的相同 UUID 或存储格不会复活旧面板；内容层应在新对象的生命周期中创建新挂载。

模型和输入资源的清理由现有 `SceneModels` / `UIBackend` 完成。适配器只读姿态，不创建
新的物理权威、网络协议或模型 JSON 字段；按钮涉及的玩法操作仍须通过原服务端接口校验。

## 可选依赖与验证环境

生产代码仅 `compileOnly` 依赖 Companion 1.6.0，运行时由 Sable 提供该 API。
未安装 Companion 的普通 Create 环境可以加载可选入口并得到空结果。完整的 Sable、
航空学与 Rapier 库都不打入 KasugaLib 的产物中。

当前集成目标为官方 Sable 2.0.6、航空学 1.3.2、Companion 1.6.0。
这些发布版要求 NeoForge 至少 21.1.228、Create 6.0.10、Flywheel 1.0.6。
基础工程的版本保持原值，测试通过显式属性和初始化脚本选择隔离运行配置。

将官方 Sable 和航空学 bundled JAR 放入隔离游戏目录的 `mods`，使用单独的测试存档：

```sh
./gradlew :modules:create:runClientHeadless \
  -I scripts/sable-scene-smoke.init.gradle \
  -Pneo_version=21.1.228 -Pcreate_version=6.0.10-280 \
  -Pflywheel_version=1.0.6 -Pregistrate_version=MC1.21-1.3.0+67 \
  -PkasugaSableSmokeJar=/absolute/path/sable-neoforge-1.21.1-2.0.6.jar \
  -PkasugaClientDirectory=/absolute/path/to/isolated/game \
  -PkasugaQuickPlayWorld=sable-scene -PkasugaTestSableScene=true --console=plain
```

完整 Sable API 仅用于有 `kasugaSableSmokeJar` 时编译的开发验收源，不进入默认源码集。
测试用原服务端组装/物理 teleport API 驱动真实子世界，包含航空学 levitite 方块和
船上的普通 Create 轴承。检查主 framebuffer RGBA、场景/独立/嵌套三类面板点击、
结构拾取与卸载清理。轴承夹具设为停止时不放回方块，避免无动力时自动拆卸。
结果写入游戏目录
`debug/sable-scene/report.json`，应检查 `passed`，不能只看 Gradle 的退出码。

纯 JVM 回归使用实际 Companion 的 `Pose3d`，验证远坐标、旋转中心、非均匀缩放、
嵌套 Create 的父子组合、身份失效及无 Companion 的类加载路径。

2026-11-11 在 macOS arm64、官方 Sable 2.0.6 / 航空学 1.3.2 上完成上述实机验收：
两阶段各 30 帧，三类面板各累计点击 60 次，船体平移/旋转、子轴承 25°/65° 旋转、
模型拾取和宿主删除清理均通过。原生 Rapier 库直接使用官方 JAR 内的 aarch64 macOS 版本。
普通 Create 6.0.6 与 6.0.10 的风车演示分别回归，曲线轨距的两条实际烘焙路径也被调用验证。
测试未覆盖专用服务器、多客户端、Valkyrien Skies 或所有第三方着色器组合。
