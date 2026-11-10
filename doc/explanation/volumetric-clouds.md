# 多层体积云渲染原理

云场配置、实例控制、预览与验证入口见 [API reference](../reference/volumetric-clouds.md)。

## 天气与密度层

天空由相互独立的低、中、高密度层组成，天气窗口控制云团、空隙与厚度变化。
连续密度可以跨天气网格边界连接与重叠，避免一格一朵的离散排列。低层保留积雨云包络，
中层铺展，高层采用拉长的各向异性形态；云塔也可以跨越多个高度层。
场景高度与覆盖预设是艺术配置，不是气象测量结果。

## 渲染与成本

云场通过一次覆盖天空的光线步进和一次合成绘制，不逐朵创建全屏 pass。
先求射线与各层高度区间的交集，排序并合并重叠区间，直接跳过层间空隙。
按区间长度的平方根分配采样预算，避免高层薄云被低层耗尽预算而消失；
同一点的多个云层密度相加。每条射线的采样总量受预算限制，透射率很低时提前结束。
天气窗口与 GPU 纹理使用最多 8 项的缓存，避免旅行导致内存持续增长。

共享的形态纹理保留积雨云云塔和云砧包络；后台生成的周期性
Perlin-Worley/Worley 噪声提供连续云层的主体、翻卷和侵蚀。每层使用独立的天气、
噪声相位与形态尺度，薄云采用拉长的各向异性形态。云场沿太阳方向按二次分布的区间
积分到各层出口，近似软自遮光。视线按 Beer–Lambert 消光前向累积预乘辐射和透射率；
空域使用低频包络探测和跳步。双叶 Henyey–Greenstein 控制前后散射，三个散射阶逐级减半
消光和各向异性，能量按配置衰减并归一化；这仍是多次散射近似，并未求解完整辐射输运。
单个积雨云使用缓存的光照体素图集，通常每 0.25 秒更新一次。

云场 FAST/BALANCED/HIGH 的宽高分辨率分别为原视口的 1/3、1/2、1；
每条射线总预算 112/160/256 次，光照采样为 2/4/6 次。
单云质量的对应分辨率为 1/3、1/2、1，最多 48/80/128 个细步进。
低分辨率结果使用保守深度和深度感知插值放大。两种路径都截断于不透明场景
深度，不写深度，天空云不受地形远平面限制，也支持无限远透视投影，并恢复宿主 GL 状态。

RGBA16F 保存云辐射/不透明度，RG32F 保存消光权重平均云距离与场景射线端点。
稳定 viewId 启用独立双缓冲历史：以平均云距离重投影，补偿相机与风的位移，对深度、
场景端点、边界、时间、光照和配置做拒绝判断，并将旧辐射/不透明度限制在当前 3×3 邻域。
传送、换世界、resize 和光照突变会丢弃历史；最多缓存八个视图。匿名视图或 historyWeight=0
保留独立空间路径。平均深度不能精确表达同一射线上分离的多层云，因此采用保守拒绝，
不通过历史恢复当前为空的云轮廓。

合成单独复制宿主颜色，在线性光中计算 `Lcloud + Tcloud * Lscene`。默认显式解码/编码显示
sRGB；线性 HDR 模式不做色调映射或截断，宿主负责最终曝光。

远景加入宿主天空色的大气透视及距离渐隐。当前没有地面云影或完整
气象/光谱大气模拟；与水面等透明物体按渲染阶段合成。Iris shaderpack 额外 gbuffer 通道
尚未验证，禁用 shaderpack 的验收不能推断其兼容性。

## 参考

- [用户提供的《地平线：零之曙光》的体积云景实现](https://zhuanlan.zhihu.com/p/638440336)
- [Guerrilla：Nubis，2017 原始资料](https://www.guerrilla-games.com/read/nubis-authoring-real-time-volumetric-cloudscapes-with-the-decima-engine)
- [SIGGRAPH 2015：The Real-time Volumetric Cloudscapes of Horizon: Zero Dawn](https://advances.realtimerendering.com/s2015/)
- [WMO：积云轮廓、翻卷顶部与平坦云底](https://cloudatlas.wmo.int/en/definition-cumulus-cu.html)
- [WMO：积雨云的云塔与云砧](https://cloudatlas.wmo.int/en/cumulonimbus-cb.html)
- [WMO：低、中、高云层及跨层分布](https://cloudatlas.wmo.int/en/clouds-definitions.html)
- [NOAA：十种基本云型及多层云的形态](https://www.noaa.gov/jetstream/clouds/ten-basic-clouds)

本实现使用连续密度层、程序化天气区域和少量积雨云形态包络；未照搬原系统的
球壳云层及分帧重投影。
引用资料的硬件耗时不代表本仓库实现的性能。
