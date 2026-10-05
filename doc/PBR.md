# PMX 风格化 PBR 烘焙原理

配置、规则注册、上下文和诊断命令见 [API reference](../api/PBR.md)。

## 1. 工作方式

PMX 原始材质主要提供基础纹理、漫反射色、高光色和高光强度，并不直接包含完整的 PBR 纹理。KasugaLib 在客户端资源加载阶段执行一次预烘焙：

1. 读取 PMX 材质的数值属性，生成自动转换参数。
2. 依次应用 Java 注册规则和玩家 JSON 规则。
3. 根据基础纹理的亮度梯度生成法线纹理。
4. 生成符合当前 LabPBR/Iris 路径的 specular 纹理。
5. 将基础色、法线和 specular 分别拼接到三个对应 atlas。
6. 把结果写入磁盘缓存，后续资源重载优先复用。

### 自动参数

- `smoothness`：由 PMX `shininess` 映射得到。
- `f0_code`：由 PMX 高光颜色的平均值生成，自动值限制在 4–90，不会自动猜测金属。
- `subsurface`：默认 0。
- `normal_strength`：默认 0.08。
- `emission`：默认 0。

### 输出通道

法线纹理：

| 通道 | 内容 |
| --- | --- |
| R | 编码后的切线空间 X |
| G | 编码后的切线空间 Y |
| B | AO，当前为 255 |
| A | Height，当前为 255，即不启用视差高度 |

Specular 纹理：

| 通道 | 内容 |
| --- | --- |
| R | Perceptual smoothness |
| G | Dielectric F0 或 LabPBR 金属代码 |
| B | Porosity/SSS；非零 SSS 会映射到 65–255 |
| A | Emission |

法线采样在纹理边界使用 repeat/wrap，与 PMX 可重复 UV 的行为一致。

## 2. 材质变体、缓存与加载

### 独立材质变体

atlas 标识由“源纹理 + 最终 PBR 参数”共同决定：

- 相同源纹理、相同参数：复用同一个变体。
- 相同源纹理、不同参数：生成独立变体和独立法线/specular sprite。
- 同一 atlas 标识如果再次绑定不同源图或参数：立即抛出冲突错误，不再静默平均。

### GPU、CPU 与缓存

- 首选在渲染线程通过离屏 framebuffer 和 MRT 一次生成法线、specular 两个输出。
- GPU shader 不可用时，本次会话自动切换到确定性的 CPU reference baker。
- GPU baker 的 GLSL 位于 `assets/kasuga_lib/shaders/pbr/stylized_bake.vsh` 和 `stylized_bake.fsh`。
- GPU baker 会显式保存、清零并恢复 `GL_PIXEL_PACK_BUFFER`、`GL_PIXEL_UNPACK_BUFFER` 以及 pack/unpack 的 row-length/skip 状态。

### 加载性能

- 修复 PMX 顶点去重中的线性 `indexOf` 查找，将大模型的近似 O(n²) 路径改为哈希索引。
- 不再把未使用的基础纹理副本重复放入 PBR atlas。
- PBR 磁盘缓存使用资源加载线程并行预取，只有未命中项才回到渲染线程执行 GPU 烘焙。
- 同一源图和 profile 的内容哈希会在一次加载中复用。
- `BufferedImage` 直接转换为 `NativeImage`，不再先编码为内存 PNG 再解码。
- CPU fallback 同时最多执行两个 bake，避免占满所有资源加载线程。
- 世界内资源重载采用分代发布：新模型先在后台构建，atlas 和 sprite 映射全部 ready 后再在游戏线程原子替换；重载期间暂停 Kasuga 模型绘制，并清理被替换模型的旧 backend instance。

烘焙发生在加载/重载阶段，不在每帧执行。通道的实际视觉效果取决于宿主与 shaderpack；多参数变体会增加 atlas 面积。算法或通道语义变化必须使旧缓存失效。
