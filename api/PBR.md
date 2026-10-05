# KasugaLib PMX → PBR 转换

接口、配置和调用参考。原理说明见 [PBR.md](../doc/PBR.md)。

本文说明 KasugaLib 当前的 PMX 风格化 PBR 转换管线、本轮实现变更、可观测性与排障方式，以及玩家配置和 Java API 的自定义方法。

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

## 2. 本轮更改

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

## 3. 可观测性

### 启动 indicator

Minecraft 的资源加载画面会额外显示 KasugaLib 当前阶段和进度，包括：

- 扫描模型资源；
- 当前模型资源；
- ZIP 内当前 PMX 名称和 PMX 数量；
- PBR 缓存项读取；
- 缺失缓存的 GPU bake 数量；
- PBR atlas 拼接。

### 批次日志

每次 PBR 准备完成后会输出一条汇总日志，例如：

```text
Prepared 91 stylized PBR variants from 74 source textures (59.4 MP, 17 shared-source variants): GPU 0, CPU 0, disk hits 91, failures 0, elapsed 880.3 ms
```

字段含义：

| 字段 | 含义 |
| --- | --- |
| variants | 最终 atlas 中的配置变体数量 |
| source textures | 不重复源图数量 |
| MP | 按变体计算的总处理像素量 |
| shared-source variants | 因同一源图使用不同参数而增加的变体数 |
| GPU | 本批次实际 GPU 新生成数量 |
| CPU | 本批次 CPU fallback 新生成数量 |
| disk hits | 本批次磁盘缓存命中数量 |
| failures | 本批次失败并使用默认 PBR 图的数量 |
| elapsed | 缓存预取和缺失项 bake 的总耗时 |

首次加载通常会看到 GPU 数量；热缓存加载通常表现为 `GPU 0, CPU 0` 和较高的 `disk hits`。

### 客户端命令

```text
/kasuga_pbr status
/kasuga_pbr reload_config
/kasuga_pbr rebake
```

`status` 输出：

- `ready`、`failed`、`total`：本次会话内 cache key 的状态计数；
- `GPU`、`CPU`：实际新烘焙数量；
- `disk-cache`：磁盘缓存命中次数；
- `memory-cache`：同一次资源加载内的内存结果命中次数；
- `requests`：协调器收到的总请求数，包含缓存预取和 atlas 消费；
- `key`：生成内容 cache key 的累计耗时；
- `read`：磁盘 PNG 解码累计耗时；
- `GPU-time`、`CPU-time`：对应 baker 累计耗时；
- `write`：写入磁盘缓存的累计耗时。

`reload_config` 重新加载玩家配置并触发资源重载。`rebake` 删除整个 Kasuga PBR 磁盘缓存、清空会话统计并触发完整资源重载。

世界内执行这两个命令时，Kasuga 模型会在资源重载期间暂时隐藏。新模型不会在 atlas ready 之前发布，因此渲染线程不会观察到缺失的 sprite；重载完成后会使用新配置重新创建 backend instance。包括 macOS 在内，缓存未命中项会统一优先使用 GPU baker；只有 GPU shader 或 GL 调用失败时才回退 CPU。

### 排障建议

1. 参数修改后没有变化：执行 `/kasuga_pbr reload_config`。参数参与 cache key，一般不需要手动清缓存。
2. 怀疑旧缓存损坏：执行 `/kasuga_pbr rebake`。
3. `GPU=0`：如果 `disk-cache` 很高，这是正常的缓存命中；如果出现 GPU unavailable 日志，检查 OpenGL/shader 错误，系统会使用 CPU fallback。
4. `failures > 0`：搜索日志中的 `Failed to bake stylized PBR texture`，日志会包含 cache key 和异常。
5. atlas 过大：比较 `variants` 与 `source textures`。差值很大通常表示大量材质使用了不同配置。
6. 启动仍然慢：根据 indicator 判断停在 PMX、缓存读取还是 atlas，并比较批次日志和 `/kasuga_pbr status` 中的分阶段耗时。

## 4. 玩家 JSON 自定义

配置文件位于：

```text
<游戏目录>/config/kasuga-pbr.json
```

文件不存在时会自动生成。基础结构：

```json
{
  "enabled": true,
  "defaults": {},
  "rules": []
}
```

- `enabled: false`：禁用玩家配置层，仍保留 PMX 自动转换和其他 Java 规则。
- `defaults`：覆盖所有 PMX 材质的转换参数。
- `rules`：按数组顺序从上到下应用；后面的匹配规则可以继续覆盖前面的结果。
- 规则只需要填写想匹配和覆盖的字段。

### 匹配字段

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `enabled` | boolean | 设为 false 可临时禁用单条规则 |
| `model` | string glob | 完整模型 `ResourceLocation` |
| `texture` | string glob | 原始纹理 `ResourceLocation`，不是带 hash 的变体 ID |
| `local_name` | string glob | PMX 本地材质名 |
| `english_name` | string glob | PMX 英文材质名 |
| `material_index` | integer | PMX 材质索引，从 0 开始 |
| `min_shininess` | number | PMX shininess 下限，包含边界 |
| `max_shininess` | number | PMX shininess 上限，包含边界 |

字符串匹配不区分大小写，支持 `*` 和 `?`。缺少某个匹配字段表示不限制该字段。

材质名和纹理名可以作为辅助过滤条件，但不建议把它们作为唯一的材质分类依据。对于确定模型，优先使用 `model + material_index`；对于跨模型规则，优先使用 PMX 数值范围或 Java API 中的结构化上下文。

### 输出字段

| 字段 | 范围 | 说明 |
| --- | --- | --- |
| `smoothness` | 0–1 | 感知光滑度 |
| `f0_code` | 0–255 | LabPBR specular G 通道代码 |
| `subsurface` | 0–1 | 次表面散射强度 |
| `normal_strength` | 0–1 | 从基础色亮度导出法线的强度 |
| `emission` | 0–1 | 自发光强度 |

超出范围的值会由 `PbrConversionSettings` 自动 clamp。

LabPBR 预定义金属代码：

| 金属 | `f0_code` |
| --- | ---: |
| Iron | 230 |
| Gold | 231 |
| Aluminum | 232 |
| Chrome | 233 |
| Copper | 234 |
| Lead | 235 |
| Platinum | 236 |
| Silver | 237 |
| 使用 albedo 作为金属色 | 255 |

0–229 用于 dielectric F0。自动转换不会产生 230 以上的金属代码。

### 配置示例

下面的示例针对一个确定模型的材质索引进行风格化：

```json
{
  "enabled": true,
  "defaults": {
    "normal_strength": 0.06
  },
  "rules": [
    {
      "model": "kasuga_lib:models/pmx/test2.mmd.zip/unfading_flowers_miku_black.pmx",
      "material_index": 12,
      "smoothness": 0.72,
      "f0_code": 230,
      "normal_strength": 0.035
    },
    {
      "model": "kasuga_lib:models/pmx/test2.mmd.zip/unfading_flowers_miku_black.pmx",
      "material_index": 18,
      "smoothness": 0.38,
      "subsurface": 0.45,
      "normal_strength": 0.025
    },
    {
      "model": "kasuga_lib:models/pmx/*",
      "min_shininess": 96,
      "smoothness": 0.85
    }
  ]
}
```

保存后执行：

```text
/kasuga_pbr reload_config
```

## 5. Java API 自定义

公开 API 位于：

```text
lib.kasuga.rendering.models.mc.api.pbr
```

主要类型：

- `PbrConversionSettings`：不可变的最终转换参数，构造时自动 clamp，并提供 `with...` 方法。
- `PbrMaterialContext`：当前模型、纹理、材质索引、PMX 名称、颜色、shininess、flags 和纹理尺寸的只读快照。
- `PbrConversionRule`：转换函数。
- `PbrConversionRegistry`：规则注册、注销和排序。
- `PbrMetalPreset`：LabPBR 金属代码枚举。

### 注册规则

```java
import lib.kasuga.rendering.models.mc.api.pbr.PbrConversionRegistry;
import lib.kasuga.rendering.models.mc.api.pbr.PbrMetalPreset;
import net.minecraft.resources.ResourceLocation;

PbrConversionRegistry.Registration registration = PbrConversionRegistry.register(
        ResourceLocation.fromNamespaceAndPath("example_mod", "character_materials"),
        500,
        (context, current) -> {
            if (!context.modelId().getNamespace().equals("example_mod")) {
                return current;
            }
            if (context.materialIndex() == 4) {
                return current
                        .withMetal(PbrMetalPreset.SILVER)
                        .withSmoothness(0.82f)
                        .withNormalStrength(0.04f);
            }
            if (context.shininess() < 8.0f && context.diffuseAlpha() < 1.0f) {
                return current
                        .withSmoothness(0.3f)
                        .withSubsurface(0.2f);
            }
            return current;
        }
);
```

不再需要该规则时调用：

```java
registration.close();
```

也可以使用同一 ID 重新注册；旧规则会先被移除。

### 优先级和错误行为

- 规则按 `priority` 从小到大执行。
- 相同 priority 按规则 ID 的字符串顺序执行。
- 玩家 JSON 配置注册在 priority `1000`。
- 希望在玩家配置之前提供基础值时，使用小于 1000 的 priority。
- 希望强制覆盖玩家配置时，使用大于 1000 的 priority；这会降低玩家自定义能力，应谨慎使用。
- 规则返回 `null` 或抛出运行时异常时，KasugaLib 会记录 warning，并保留上一条规则的结果继续执行。

### `PbrMaterialContext` 字段

代码规则可访问：

- `modelId`、`textureId`、`materialIndex`；
- `localName`、`englishName`、`metadata`；
- diffuse RGBA；
- specular RGB；
- ambient RGB；
- `shininess`；
- `noCull`、`receivesShadow`；
- `textureWidth`、`textureHeight`。

推荐规则使用稳定的模型 ID、材质索引和 PMX 数值组合。若必须使用名称，应同时限制模型 ID，并为模型版本变化预留回退行为。

## 6. 兼容性与注意事项

- 当前烘焙发生在客户端资源加载/重载阶段，不会在每帧执行。
- PBR atlas 与 Iris shader 路径配套；没有 Iris 或 shader pack 时，基础色模型仍可使用，但 PBR 通道是否产生视觉效果取决于实际渲染路径。
- 同一基础纹理使用大量不同配置会增加 atlas 面积和显存占用。尽量复用完全相同的参数组合。
- 修改 baker 通道语义或算法时必须提升 `PbrBakeProfile.VERSION`，使旧磁盘缓存失效。
- `rebake` 会删除全部 Kasuga PBR 缓存，下一次加载成本较高，只应在排障或算法升级时使用。
