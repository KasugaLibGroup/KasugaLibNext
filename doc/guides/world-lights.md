# 世界空间光源

`WorldLights` 提供客户端动态点光源、矩形面光源和球形体积光。光源属于指定的
`ClientLevel`，使用已有的 `EffectRenderPipeline` 完成可见性筛选、tick、移除和切换世界清理。
默认不创建光源；没有可见光源时不会复制场景或执行光照 shader。

```java
EffectHandle<WorldLight> lamp = WorldLights.spawn(level,
        LightSource.point(10, 65, 10)
                .temperature(2700)
                .intensity(30)
                .range(14)
                .build());

EffectHandle<WorldLight> panel = WorldLights.spawn(level,
        LightSource.area(10, 69, 10, 4, 2)
                .rotation(0, 90)
                .temperature(4500)
                .intensity(8)
                .range(16)
                .build());

EffectHandle<WorldLight> mist = WorldLights.spawn(level,
        LightSource.volumetric(13, 65, 10)
                .temperature(9000)
                .intensity(120)
                .range(4)
                .medium(0.12f, 0.2f)
                .build());
```

导入 `lib.kasuga.rendering.lighting.LightSource`、`lib.kasuga.rendering.effect.EffectHandle`，
以及 `lib.kasuga.rendering.effect.builtin.light` 中的 `WorldLights` 和 `WorldLight`。
在客户端注册完成后创建光源；`WorldLights.ready()` 表示资源 shader 已可用。
渲染和更新属于客户端，服务器不加载这些类。

## 参数和更新

### 放进 scene 并移动

`WorldLightTarget` 是 `WorldDisplayTarget`，可以和模型、UI 一起加入嵌套的 `WorldDisplayScene`。
使用已有 `SceneModels` 挂载到世界；节点的位置、矩形方向和尺寸都会继承 scene 与挂载点的变换。

```java
WorldDisplayScene scene = new WorldDisplayScene();
WorldLightTarget light = scene.add(new WorldLightTarget(
        LightSource.point(0, 3, 0).temperature(2700).intensity(30).build()));

SceneModels models = SceneModels.open(level);
SceneModels.Entry mount = models.add(scene, new Vec3(10, 64, 10));

light.moveBy(2, 0, 0);                      // 在父 scene 坐标中移动单灯
light.position(new Vec3(0, 4, 0));          // 设置单灯局部位置
scene.transform(new Matrix4f().translation(3, 0, 0).rotateY(0.5f));
mount.position(new Vec3(20, 64, 10));        // 移动整个世界挂载点
scene.visible(false);                      // 隐藏整个子树的光源
scene.visible(true);
scene.remove(light);                       // 关闭该节点，移除对应光源
models.close();                           // 关闭挂载、scene 和全部子灯
```

这些 API 均在客户端渲染线程调用。`WorldDisplayScene` 和 `WorldLightTarget` 位于
`lib.kasuga.rendering.models.mc.scene.display`；`SceneModels` 位于 `lib.kasuga.scene`。
`mount.follow(host)` 可复用 `SceneHost` 跟随移动物体，每个相机只采样当前 pose，不推进场景时间。
场景关闭、节点移除或宿主消失时，由挂载的生命周期清理光源。隐藏会移除渲染实例，再次显示时重新挂载。

面光源保留完整旋转（包括 roll）和面板尺寸缩放。非均匀缩放或剪切下，作用范围和球形介质使用包围球近似，
而矩形发光面仍采用完整仿射变换。独立的 `WorldLights.spawn` 光源也提供 `moveTo(x,y,z)` / `moveBy(x,y,z)`。

| 参数 | 语义 |
| --- | --- |
| 世界坐标 | 双精度方块坐标。GPU 上传前先减去当前相机坐标，保留远处世界的局部精度 |
| `temperature(kelvin)` | 可选的 1000–40000 K 近似色温；不设置时为白色 |
| `color(new LightColor(r, g, b))` | 直接设置线性 RGB；与 `temperature` 互相替换，以最后设置为准 |
| `intensity(value)` | 非负相对强度，0 关闭发光；并非校准的流明或坎德拉 |
| `range(blocks)` | 正数，有限作用范围；边界平滑衰减到 0。体积光同时以此作为介质半径 |
| 面光源尺寸 | 矩形宽、高，单位方块；强度代表单位面积辐射亮度，因此扩大面板会增加总能量 |
| `rotation(yaw, pitch)` | 度数，零角度面朝 +Z，pitch +90 向下；矩形宽度轴随 yaw 旋转 |
| `twoSided(true)` | 矩形两面发光，默认单面；不创建可见的灯具模型 |
| `medium(density, anisotropy)` | 非负散射/消光系数；各向异性范围 -0.9–0.9。仅用于体积光 |

`LightSource` 为不可变值，更新时整体替换，避免 tick 和渲染读到一半修改的数据：

```java
LightSource current = lamp.effect().source();
lamp.effect().source(current.toBuilder().position(12, 65, 10).intensity(40).build());
lamp.remove(); // 或 lamp.effect().close()
panel.remove();
mist.remove();
```

色温使用 [Tanner Helland 的近似拟合](https://tannerhelland.com/2012/09/18/convert-temperature-rgb-algorithm-code.html)，
并将结果转换为线性 RGB；它是美术用色调，不是光谱模拟。

## 渲染路径与边界

复用现有 shader 注册、后处理图、场景颜色/深度复制、fullscreen draw 和 `TBOBuffer`。
收集与处理运行在 `POST_PROCESS` 的 priority 700/701，位于内置水面处理之前。
三种光源合并为一次全屏处理，光源数据按实际可见数量上传，不固定截断数量。
相机各自使用其矩阵、世界、位置和输出尺寸；绘制不会推进光源时间。

shader 文件位于 `assets/kasuga_lib/shaders/core/lighting/world_lights.{json,vsh,fsh}`，
通过 resources 加载，支持现有资源重载流程。程序中没有内嵌新增 GLSL。

这是基于场景深度的动态光照：能影响写入该深度的原版方块和 Kasuga 模型，并从相邻深度重建法线。
表面颜色作为材质反照率的近似，不提供独立 PBR 材质通道、阴影贴图、离屏遮挡查询或间接光反弹。
因此墙体不会向背后的接收面投射该光源的阴影；透明材质没有独立深度/法线时也无法单独受光。
这些光源不修改原版方块光照值，不影响刷怪或游戏逻辑。开启 shader pack 时，其深度及色彩约定需要单独验证。

体积光在球形介质内进行 48 步积分，包含平滑密度、消光和 Henyey–Greenstein 相函数；
积分范围由当前视线的场景深度截断，支持相机位于介质内部。它不是 billboard。
多个体积的散射近似相加、光学厚度相加，没有完整求解重叠介质之间的多次散射。
可见光源越多越耗时，面光源每像素需要 16 个采样，体积光每次相交需要 48 步。

## Demo 和验证

```sh
./gradlew :modules:modelling:modelUnitTest :modules:modelling:renderGlTest --offline
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaTestWorldLights=true \
  -PkasugaClientDirectory=run/world-lights-demo --offline
```

客户端每次会自动创建独立的 `world-light-demo-<时间戳>` 世界，在玩家出生点附近搭建测试房间，
以 960×600 和 480×300 两个机位输出基线、暖/冷点光、强度、面光、体积光、组合、单灯移动、
父 scene 移动、隐藏、恢复、关闭，以及 Blockbench 导出光源、骨骼移动与模型关闭后的画面，共 30 张。
图片及 `report.json` 写入指定运行目录的 `debug/world-lights/`，完成后自动退出。
请使用专用运行目录；该 demo 会修改其测试世界。

独立 GL 回归使用生产 shader 和生产数据布局，验证色温、衰减、强度、面板方向、双面发光、
体积深度遮挡、插入顺序与移除恢复，并保存低分辨率像素验证图。
