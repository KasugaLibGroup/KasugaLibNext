# 模型材质 Alpha 渲染

模型材质现在按 glTF/通用材质的 alpha contract 分成三类：

| 类别 | GPU 状态 | Fragment 行为 | 顺序 |
| --- | --- | --- | --- |
| `OPAQUE` | 不混合、写深度 | 忽略采样 alpha，输出 1 | `AFTER_ENTITIES` priority 0 |
| `MASK` | 不混合、写深度 | 低于材质 `alphaCutoff` 的 fragment `discard`，其余输出 1 | `AFTER_ENTITIES` priority 1 |
| `BLEND` | OIT accumulation/revealage、不写深度 | 仅丢弃 `<= 1/255` 的近似不可见 alpha，保留实际 alpha | `AFTER_TRANSLUCENT_BLOCKS`，WBOIT 无需排序 |

`BackendInstance` 为每个非空 pass 创建独立的 `FlatModelData` 和 vertex buffer，
因此一个模型含有多种材质时不会把不兼容的几何重复绘制到错误的 RenderType。
OPAQUE/MASK 可以继续进入 global batch，但 batch key 包含 alpha pass；BLEND 不进入
global batch，避免把 OIT 两次几何提交和普通批处理状态混在一起。

glTF 的 `alphaMode` 和 `alphaCutoff` 由 `GltfMaterialData` 传到后端。没有透明度
contract 的旧 loader 默认使用 `OPAQUE`；PMX loader 根据 diffuse alpha 和纹理 alpha
推断 OPAQUE/MASK/BLEND。

BLEND 的非 Iris 路径是两次几何子 pass 的 weighted-blended OIT（WBOIT）：第一遍把
`color * alpha * weight` 与 `alpha * weight` 加到 `RGBA16F` accumulation，第二遍把
`1 - alpha` 乘入 `R16F` revealage。两遍都使用从主 framebuffer 复制的场景深度，只测深度
而不写深度；最后 fullscreen resolve 计算
`color = accumulation.rgb / accumulation.a`、`alpha = 1 - revealage`，再以
straight-alpha source-over 合成回主目标。因此 KasugaLib 的 BLEND 几何在这条路径上
不依赖远到近提交顺序。

启用 Iris shaderpack 时也使用同一套 accumulation/revealage/resolve 目标，但保留 Iris 的
`entities_translucent` shader 来产生材质、PBR、光照和雾化后的 straight-alpha 颜色。由于
无法在不改 shaderpack 的情况下向其 fragment shader 注入 Kasuga 的深度权重，Iris 路径
使用权重为 1 的常权重 WBOIT：RGB 以 `SRC_ALPHA, ONE` 累积，A 以 `ONE, ONE` 累积，
revealage 仍为 `ZERO, ONE_MINUS_SRC_ALPHA`。Iris 1.8.12 的 translucent entity shader
自带 0.1 alpha test，低于 MASK 常用的 0.5 cutoff。

Iris 会在每次 `ShaderInstance.apply()` 中绑定 shaderpack gbuffer 并可覆盖 blend state，
所以 Kasuga 在 apply 后、draw 前重新绑定 OIT attachment，并显式恢复 blend equation、
blend factors、color/depth mask。场景深度从 Iris 的 after-translucent default framebuffer
复制，resolve 也回写同一个 framebuffer；只允许专用的 OIT composite shader 绕过 Iris
unknown-shader guard，不放宽其他 mod shader 的兼容策略。

WBOIT 是近似算法，不是精确的任意层 source-over，也不把 vanilla 水面或 shaderpack
的透明片元纳入同一个 OIT 方程；当前阶段仍是 `AFTER_TRANSLUCENT_BLOCKS`，所以它的
作用域是“KasugaLib BLEND 层叠加到已经完成的场景”。浮点目标不可用、Iris pipeline
没有可绑定的 world framebuffer 或运行时 framebuffer 操作失败时，安全回退到旧的
远到近排序路径。Iris OIT 只 resolve 主颜色；依赖自定义额外 gbuffer 通道表达透明材质的
shaderpack 仍可能需要 pack 专用集成。

MASK 的材质 `alphaCutoff` 与 BLEND 的 `1/255` 近零工作 cutoff 是两个独立概念：前者
决定是否写入深度，后者只移除几乎不可见的 OIT 工作项。

调试命令、快捷键和验收入口见 [API reference](../api/render-alpha-passes.md)。
