# 天空体积云与积雨云实例

接口、配置和调用参考。原理说明见 [volumetric-clouds.md](../explanation/volumetric-clouds.md)。

体积云是独立的可选功能，默认关闭。`CloudEffects.setEnabled(true)` 自动绘制随
观察位置铺开的天空云场，同时隐藏主视图和独立摄像机中的原版方块云。
关闭后恢复原来的云设置；开关不会修改或保存用户的原版云选项。退出世界自动关闭。
没有原版云层的维度不绘制天空云场。

## 开启天空云场

```java
import lib.kasuga.rendering.cloud.SkyCloudSettings;
import lib.kasuga.rendering.effect.builtin.cloud.CloudEffects;

CloudEffects.sky().settings(SkyCloudSettings.DEFAULT);
CloudEffects.setSkyEnabled(true);
CloudEffects.setEnabled(true);

// 可实时调整云量、种子、质量；风速变化不会使已有云团瞬移。
CloudEffects.sky().settings(CloudEffects.sky().settings()
        .withQuality(SkyCloudSettings.Quality.FAST));
CloudEffects.setEnabled(false);
```

云场叠加 1–4 个独立的连续密度层。低层积云具有翻卷顶部、暗色云底与不同的
发展高度；中层云幕铺展成片，高层薄云拉长成条带。天气区域控制云团、云隙和
厚度变化，云体能跨越天气网格边界、相连或重叠，不再以一格一朵的方式排列。
低层中保留少量使用独立形态包络的积雨云。

默认三个云层的云底 Y 为 900、2700、5400，厚度为 2100、900、600。
这些数值是可调的场景尺度；积云和积雨云也可以延伸到其他
云层的高度。`SCATTERED`、`CLOUDY`、`OVERCAST` 预设分别采用 .38、.80、.98 的
覆盖控制：少云保留大块晴空，多云使云团连接，阴天形成连续云幕。

`SkyCloudSettings` 参数为种子、天气格尺寸、原始云底、积雨云最大厚度、覆盖控制、
最大显示距离、X/Z 风速、质量、层列表和渲染配置。九参数构造器按云底和厚度生成三个默认层。
各层的实际高度由 `SkyCloudLayer` 控制，列表可以包含同类型的多个云层；修改质量、
覆盖或种子保留层列表。风速使用世界单位/秒。
`coverage=0` 在晴天完全隐藏云，降雨增加云量；太阳方向、日夜和天气颜色来自世界。

每层分别控制类型、云底、厚度、水平形态尺度、覆盖偏移和光学密度。
云量和密度分开设置，少云时也能保持单朵云的实体感。密度为零隐藏该层，
包含嵌入其中的积雨云。配置可以在运行中替换：

```java
import java.util.List;
import lib.kasuga.rendering.cloud.SkyCloudLayer;

CloudEffects.sky().settings(SkyCloudSettings.CLOUDY.withLayers(List.of(
        new SkyCloudLayer(SkyCloudLayer.Type.CUMULUS, 900, 2100, 5200, 0, 2),
        new SkyCloudLayer(SkyCloudLayer.Type.STRATIFORM, 2700, 900, 3400, -.08f, 1.5f),
        new SkyCloudLayer(SkyCloudLayer.Type.CIRRUS, 5400, 600, 8000, -.12f, .55f))));
```

每个观察位置使用 32×32 的天气窗口。窗口随观察位置重定位，重叠部分的云保持
原样；独立摄像机可在遥远的位置观察自己的局部窗口。世界边界附近先用 double
减去相机位置，再转换为 GPU 坐标。`Frame.weatherCellCount()` 仅统计天气控制格的
候选占用率；连续云体没有逐朵编号，该统计也不能代表屏幕云量。每层天气独立生成。

`SkyCloudRendering` 控制消光、侵蚀、细节频率、前后散射、多次散射近似、环境光、
采样次数、分辨率、调试视图、颜色空间与时域积累。消光使用世界单位的倒数；采样和
分辨率值为零时使用质量预设。可以在运行中替换配置：

```java
import lib.kasuga.rendering.cloud.SkyCloudRendering;
import lib.kasuga.rendering.cloud.SkyCloudTemporal;

var rendering = SkyCloudRendering.DEFAULT
        .withDensityDetail(.009f, .12f, 4)
        .withScattering(.55f, -.25f, .15f, .5f, 1)
        .withSampling(160, 4, 2, true)
        .withTemporal(SkyCloudTemporal.STABLE);
CloudEffects.sky().settings(CloudEffects.sky().settings()
        .withWind(8, 3).withRendering(rendering));
```

`SkyCloudTemporal.STABLE` 默认权重为 .65，最大时间间隔 .2 秒、相机位移 16 格、相对深度
容差 .05；`OFF` 禁用历史。`withDebug(...)` 可选 `DENSITY`、`TRANSMITTANCE`、`RADIANCE`、
`MARCH_STEPS` 和 `HISTORY_VALIDITY`，用 `NONE` 恢复正常画面。`withColorSpace(...)` 选择
`DISPLAY_SRGB` 或 `LINEAR_HDR`，应与宿主目标的颜色空间一致。

## 创建单个积雨云

单云 API 保留，可与天空云场一起使用，也可关闭云场单独观察：

```java
import lib.kasuga.rendering.cloud.CloudPose;
import lib.kasuga.rendering.cloud.CloudSettings;
import lib.kasuga.rendering.effect.builtin.cloud.CumulonimbusCloud;

var cloud = new CumulonimbusCloud(CloudPose.at(100, 900, 1800).withYaw(20),
        CloudSettings.CUMULONIMBUS);
var handle = CloudEffects.spawn(cloud);
CloudEffects.setSkyEnabled(false);  // 仅观察显式创建的实例
CloudEffects.setEnabled(true);
cloud.wind(.6, 0, .2);
cloud.moveBy(20, 0, 0);
cloud.rotateBy(30);                // 绕世界 Y 轴，单位为度
cloud.scale(1.2f, 1, 1.2f);
cloud.settings(CloudSettings.CUMULONIMBUS.withCoverage(.75f));
handle.remove();
cloud.close();
CloudEffects.setEnabled(false);
```

单云位置是云底中心，默认尺寸为宽 1600、高 1200、深 1200。
`CloudSettings` 控制密度、消光、侵蚀、覆盖、云砧、种子与质量。
直接变换立即生效，风与内部噪声只由客户端 tick 推进，暂停时停止；
主视图和多个摄像机采样同一时刻，不通过渲染次数推进动画。

## 渲染与成本

云场通过一次覆盖天空的光线步进和一次合成绘制，不逐朵创建全屏 pass。
先求射线与各层高度区间的交集，排序并合并重叠区间，直接跳过层间空隙。
按区间长度的平方根分配采样预算，避免高层薄云被低层耗尽预算而消失；
同一点的多个云层密度相加。每条射线的采样总量受预算限制，透射率很低时提前结束。
天气窗口与 GPU 纹理使用最多 8 项的缓存，避免旅行导致内存持续增长。

共享的形态纹理保留积雨云云塔和云砧包络；后台生成的周期性
Perlin-Worley/Worley 噪声提供连续云层的主体、翻卷和侵蚀。每层使用独立的天气、
噪声相位与形态尺度，薄云采用拉长的各向异性形态。云场沿太阳方向按二次分布的区间
积分到各层出口，近似软自遮光。Beer–Lambert 消光累积预乘辐射与透射率，双叶
Henyey–Greenstein 控制前后散射；三个近似散射阶逐级减半消光和各向异性，并归一化能量。
单个积雨云使用缓存的光照体素图集，通常每 0.25 秒更新一次。

云场 FAST/BALANCED/HIGH 的宽高分辨率分别为原视口的 1/3、1/2、1；
每条射线总预算 112/160/256 次，光照采样为 2/4/6 次。
单云质量的对应分辨率为 1/3、1/2、1，最多 48/80/128 个细步进。
低分辨率结果使用保守深度和深度感知插值放大。两种路径都截断于不透明场景
深度，不写深度，天空云不受地形远平面限制，并恢复宿主 GL 状态。

RGBA16F 保存云辐射/不透明度，RG32F 保存消光权重平均云距离与场景射线端点。
稳定 viewId 启用独立双缓冲历史：以平均云距离重投影，补偿相机与风的位移，对深度、
场景端点、边界、时间、光照和配置做拒绝判断，并将旧辐射/不透明度限制在当前 3×3 邻域。
传送、换世界、resize 和光照突变会丢弃历史；最多缓存八个视图。匿名视图或 historyWeight=0
保留独立空间路径，当前为空的云射线不会通过历史恢复旧轮廓。
resize 替换并释放旧的历史目标；视图缓存淘汰、换世界和渲染器关闭也会释放历史纹理。

合成单独复制宿主颜色，在线性光中计算 `Lcloud + Tcloud * Lscene`。默认显式解码/编码显示
sRGB；线性 HDR 模式不做色调映射或截断，宿主负责最终曝光。

远景加入大气透视及距离渐隐。当前没有长距离跨云遮光、地面云影或完整气象模拟；与水面等
透明物体按渲染阶段合成。Iris shaderpack 额外 gbuffer 通道尚未验证，禁用 shaderpack 的
验收不能推断其兼容性。

## 示例与验证

开发客户端：

- `/ksglib debug cloud` 开启整个天空云场。
- `/ksglib debug cloud sparse` 切换为稀疏、多层的少云状态。
- `/ksglib debug cloud dense` 切换为云团相连的多云状态。
- `/ksglib debug cloud overcast` 切换为连续云幕。
- `/ksglib debug cloud single` 在前方放置一个积雨云，关闭天空云场。
- `/ksglib debug cloud stop` 关闭体积云并恢复原版云。

独立预览，无需启动 Minecraft：

```bash
./gradlew :modules:modelling:renderPreview -PkasugaRenderScene=sky \
  -PkasugaRenderWidth=1920 -PkasugaRenderHeight=1080 --offline --no-daemon
```

方向键改变观察方向，W/S 飞行，Escape 退出。`-PkasugaRenderFrames=60` 自动结束并
保存预览图；`-PkasugaRenderScene=cloud` 保留原来的单朵积雨云环绕预览。
图片与报告位于 `modules/modelling/build/reports/renderHarness/`。

```bash
./gradlew renderingReleaseCheck :modules:modelling:renderGlTest --offline --no-daemon
./gradlew :modules:modelling:renderBench -PkasugaRenderScene=sky \
  -PkasugaRenderWidth=1280 -PkasugaRenderHeight=720 \
  -PkasugaRenderWarmup=20 -PkasugaRenderFrames=60 --offline --no-daemon
```

基准使用 `GL_TIME_ELAPSED`，包含深度复制、完整云场、合成，覆盖地面、少云、阴天、
云层内部、降雨与远处观察位置。这是独立场景的 GPU 耗时，不能换算成 Minecraft 整帧 FPS。

2026-11-09，在 Apple M3 Max 的 OpenGL 4.1 Metal、1280×720、20 帧预热和 60 帧采样下：

| 视角 / 状态 | GPU P50 | GPU P95 |
| --- | ---: | ---: |
| FAST 地面 | 2.05 ms | 2.35 ms |
| BALANCED 地面 | 5.86 ms | 6.43 ms |
| BALANCED 少云 | 3.67 ms | 4.22 ms |
| BALANCED 云层内部 | 6.80 ms | 9.01 ms |
| BALANCED 时域历史 | 5.89 ms | 6.39 ms |
| HIGH 地面 | 25.91 ms | 29.38 ms |
| HIGH 云层内部 | 44.66 ms | 50.57 ms |

HIGH 在密集场景和云层内部的成本明显更高，因此实际默认使用 BALANCED。数值来自独立运行的 `renderBench`，
报告位于 `build/reports/renderHarness/bench/report.json`，以模块目录为起点。

纯 JVM 回归检查各层天气独立性、配置不可变性、天气连续性、空间重叠、
风与插值、世界边界精度和缓存边界。生产 shader 的 GPU 回归检查少云与阴天的
画面覆盖差异、各层单独可见性、预算相对双倍采样的误差、多个方向、远处/云内/
上方视角、遮挡、重定位连续性、状态恢复及缓存，并保留单云回归。

临时世界的客户端验收用 `-PkasugaTestSkyCloud=true`，验证近处与 40000 单位外的
真实摄像机、主画面、共享时钟和原版云开关恢复。成功日志为 `SKY_CLOUD_SMOKE_PASS`，
报告和截图位于游戏目录的 `debug/sky-cloud/`。`-PkasugaTestCloud=true` 保留单云验收。
这些开关只用于专门创建的临时测试世界。
