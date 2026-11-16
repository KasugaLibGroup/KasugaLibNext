# 模型深度偏移

`ModelInstance` 默认使用 `DepthBias.NONE`，保持正常的深度测试和遮挡关系。
对于明确需要覆盖在另一张表面上的模型，可以在渲染线程设置实例级策略：

```java
import lib.kasuga.rendering.models.uml.backend.DepthBias;

instance.setDepthBias(DepthBias.DECAL);
// 恢复正常深度：
instance.setDepthBias(DepthBias.NONE);
```

`new DepthBias(factor, units)` 分别控制随多边形深度斜率变化的偏移和按后端最小可分辨
深度单位计算的偏移。常规深度方向下，负值向镜头偏移，正值远离镜头；参数必须为有限数。
`DECAL` 使用 `(-1, -1)`，适用于有意共面的覆盖层，具体场景可显式调整。
此偏移适用于填充多边形（三角形、四边形），不改变独立线段或点的深度。

OpenGL 后端在 shader 设置完成后使用 polygon offset，并在每次绘制后恢复原启用状态、
factor 和 units。普通绘制、CPU/GPU 蒙皮、全局批处理和模型透明重放均使用同一参数。
不同偏移策略不会被合入同一批次；已准备的透明绘制捕获参数，供后续子 pass 一致重放。

此策略只改变光栅深度，不改变模型坐标、轮廓投影、骨骼、碰撞或拾取。不要对所有模型
无条件启用，也不要用大偏移修补错误的几何。它不为同一实例内部的重复面规定顺序，
也不解决透明剥离中多个同深度表面的排序。

参数属于当前实例的运行时显示状态，不保存到模型资源或世界存档。资源重载创建新实例时，
调用方需要在新实例上重新设置。

验证入口：

```sh
./gradlew :modules:render-core:test :modules:modelling:modelUnitTest --offline
./gradlew :modules:modelling:renderGlTest --offline
./gradlew :modules:modelling:runClient -PkasugaTestModelDepthBias=true --offline
```

客户端验证使用真实 Minecraft 模型 shader 和离屏目标，报告及截图写入运行目录的
`debug/model-depth-bias/`。跨平台驱动和启用光影包的兼容性需要分别验证。
