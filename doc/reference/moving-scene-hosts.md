# 移动结构上的场景与 UI

`SceneHost` 把外部移动结构的姿态接入 `SceneModels` 和世界 UI。当前提供原版 Create
6.0.6-98 与 6.0.10-280 的 `AbstractContraptionEntity` 适配，支持实体平移及 `applyLocalTransforms`
包含的旋转、枢轴偏移。所有入口都在客户端渲染线程调用。

## 场景挂载

```java
var host = CreateSceneHosts.contraption(entity);
var scene = SceneModels.open(Minecraft.getInstance().level);
var model = new ModelDisplayTarget(CreateModelSources.contraptionLocal(entity));
model.panel(new Matrix4f().translation(2, 3, 1).scale(.01f, -.01f, .01f), 260, 240);
var root = new WorldDisplayScene();
root.add(model);
var entry = scene.add(root, host);

// 可选：位置和矩阵都在宿主的局部坐标系中。
entry.position(new Vec3(0, 0, 2));
// 主动卸载一个挂载或整个场景。
entry.close();
scene.close();
```

`contraptionLocal` 输出结构原始局部坐标中的模型。独立预览继续用原有
`CreateModelSources.contraption(entity)`；它包含预览旋转，不能再套同一宿主旋转。
挂载现有 UML 模型或组合场景时，只需将自己的 `MinecraftModelSource`/`WorldDisplayTarget`
交给 `scene.add(source, host)`。这会增加一个展示实例；不会替换 Create 的原生结构渲染器。

场景拥有 source 与其子面板；host 只被借用。实体删除、区块卸载或实体的 contraption
身份更换后，该挂载自动关闭；隐藏的挂载仍检查宿主。换世界关闭整个场景。重新出现的
结构需要新建挂载，旧实体 ID 被复用不会让原面板挂到另一个实体。

模型和附属 UI 使用同一帧宿主姿态。`scene.pick(worldFrom, worldTo, partialTick)`
对移动挂载使用最后实际采样的主视图帧，命中方块位置仍为源局部坐标；命中点是世界坐标。
该接口只提供拾取，不替代 Create 的服务端交互、权限和距离校验。面板回调继续使用原业务网络接口。

## 独立世界面板

```java
// panel 是已创建的 Renderable；交互需要同时实现 GuiEventListener，并具有可查询的尺寸。
Constants.UI_BACKEND.addRenderable(panel, host,
        new Matrix4f().translation(2, 3, 1).scale(.01f, -.01f, .01f));

// 主动解绑：
Constants.UI_BACKEND.removeRenderable(panel);
```

矩阵把 UI 像素映射到宿主局部方块坐标，局部 +X 向右、+Y 向下，因此常用负 Y 缩放。
已用 LDLib2 等适配器创建的世界面板也可以通过此入口重设为移动挂载。宿主失效时移除
后端注册、释放画布并平衡鼠标/键盘捕获；UI 工具包的其它资源仍由其既有生命周期管理。

静态 `add(source, Vec3)` 和 `addRenderable(panel, Matrix4fc)` 保持原语义。
静态面板需要远坐标精度时，可以使用 `addRenderable(panel, new ScenePose(origin, localMatrix))`。
`ScenePose` 保留双精度世界原点，在减去相机位置后才生成浮点矩阵。

## 其他移动系统

航空学使用 Sable 的 `SubLevel`，不是普通 Create 的 `AbstractContraptionEntity`。
其独立区块空间、`ClientSubLevel.renderPose(partialTick)`、旋转中心及拆分生命周期需要另一个
`SceneHost` 实现；载具上若还安装普通 Create 机械结构，还需要按实际参照系组合父子姿态。
Sable / 航空学通过单独的 `SableSceneHosts` 适配，见[航空学接入](sable-scene-hosts.md)。
Valkyrien Skies 尚未适配；各适配器的验收范围分别记录。

自定义 host 实现 `isAlive()` 和 `pose(partialTick)`。采样不能推进物理或游戏 tick。
返回 `ScenePose` 表示当前插值姿态；宿主消失时 `isAlive()` 返回 false 或 `pose` 返回 null，
挂载会永久失效。不要把临时资源未就绪当作宿主销毁。

## 开发演示与验证

开发客户端执行 `/kasuga_create_mount`，会进入隔离演示维度，组装原生 Create 风车并附着
蓝色场景面板、绿色独立面板和一个缩小的结构副本。两个面板可点击计数。
`/kasuga_create_mount stop` 关闭展示并返回原位置。

```sh
./gradlew :modules:create:runClientHeadless \
  -PkasugaClientDirectory=/absolute/path/to/isolated/game \
  -PkasugaQuickPlayWorld=your-test-world \
  -PkasugaTestCreateMounts=true --offline --console=plain
```

自动验收检查旋转和平移中的最终 framebuffer RGBA、两类面板点击、结构拾取，以及宿主
删除后的画布注册、焦点和鼠标捕获清理；报告和截图位于游戏目录
`debug/create-moving-scene/`。Gradle 成功退出不能代替检查报告中的 `passed: true`。
数值测试另外覆盖三千万格附近的亚像素往返、多相机采样隔离与隐藏宿主清理。
