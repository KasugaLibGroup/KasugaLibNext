# 真实素材换装与 MC 装备示例

开发示例类：`lib.kasuga.rendering.models.mc.api.WardrobeExample`，位于 `contentTesting` source set。
它通过生产 PMX Reader 加载用户提供的素材，随后只使用通用 `Model`、组装缓存、材质实例、
Skeleton Anchor 和 MC 装备适配器。示例代码不进入发布包。

## 本次实现与验证（2026-10-08）

本次改动提供真实模型的换纹理、换衣服模型和 MC 原生手持武器示例，所有穿搭均带上
用户提供的 13 号头发。公共数据由通用 `Model`、`Skeleton`、材质实例与组装接口组成。

| 改动 | 作用 |
| --- | --- |
| `WardrobeExample` | 将用户素材接入现有 Reader，演示材质帧切换、模型替换、缓存复用与右手首持剑 |
| `KsgPmxLoader` | 图集纹理 ID 由压缩包、PMX entry、贴图 entry 共同生成 SHA-256，修复跨压缩包同名素材串图 |
| `KsgPmxTextureIdentityTest` | 覆盖不同压缩包、不同模型/贴图、Unicode 名称及旧字符串 hash 碰撞 |
| Iris 顶点上传与 MC 渲染栈 | 显式补齐中间 UV、切线和实体属性，支持工作线程与局部更新；渲染异常时用 finally 恢复 PoseStack |
| Shader 冒烟入口 | 可选真实 shader pack，验证活跃 Iris pipeline；默认只输出通过/失败日志，不截图、不采样性能 |
| `ModelAssemblyProbe` | 增加带头发的 RibbonDress 短袖/长袖基准，记录 JVM PID、Java 版本与堆上限 |
| 本地素材准备脚本 | 将已解压素材转为本地资源包，可追加启用配置，保留其他资源包 |
| 性能汇总脚本 | 校验三个独立 JVM 的报告并仅输出 Markdown，不生成 HTML 或额外汇总 JSON |
| Gradle 与 API 文档 | 添加 opt-in 示例/自动验收参数、运行步骤和性能解释 |

本次提交范围为上述源码、测试、脚本与 API Markdown 文档。模型、贴图、资源包、存档、
截图、HTML、原始 JSON 报告、日志及 build 产物不纳入提交。

验证环境为 macOS / aarch64、Apple M3 Max、Java 21.0.3、Minecraft 1.21.1、NeoForge 21.1.203。
客户端加载 Iris 1.8.12 / Sodium 0.6.13。此前截图与性能观测使用 disabled 配置；
另用 BSL 8.2.04 跑通启用 shader pack 的六阶段冒烟检查。验证范围限于该环境与此 shader pack。

| 验证 | 结果 |
| --- | --- |
| `renderingReleaseCheck` | 通过 |
| `modelUnitTest` | 811 个用例，0 失败、0 错误、3 个条件跳过 |
| `renderGlTest` | 13 组通过；真实配套素体/长裙的 21,259 个顶点通过 CPU/GPU 蒙皮对比 |
| 本地 PMX 探针 | 三次 JVM 均成功加载全部 92 个 PMX，0 加载失败 |
| 带头发的真实客户端示例 | `WARDROBE_EXAMPLE_PASS`，180 帧、61 次原生物品提交 |
| BSL 8.2.04 shader 路径 | `WARDROBE_SHADER_SMOKE_PASS`，六阶段全部走完，实际进入 IrisRenderingPipeline，未回退到无 shader |

客户端依次验证原配色、Rose 配色、长袖模型、切回短袖、MC 持剑和手腕旋转。
纹理切换保持同一个 Model；模型切换后换回短袖，取得第一次的同一个组装 Model，
build 计数不增加。新 session 总计构建两套组装模型。
这里的换回操作由穿搭注册表复用已解析结果，底层组装缓存 `hits` 为 0、`builds` 为 2，
不会把注册表复用错误计入底层 cache hit。

## 本机性能结果（2026-10-08）

分别运行三个独立 JavaExec JVM，PID 为 `73993`、`74024`、`74050`，测量顺序为
`uncached-first`、`cached-first`、`uncached-first`。Java runtime 为 `21.0.3+10-LTS`，
每次最大堆配置为 4 GiB。

以下结果为各 JVM 批次均值 p50 的中位数。未缓存组装包含公共几何/骨架组装工作；
热缓存两列分别测量重新构建请求再查缓存、复用已有请求只查缓存。
文件解析、贴图解码、GPU 蒙皮与绘制均不计入这些 CPU 组装指标。

| 用例 | 顶点 | 骨骼 | 未缓存组装（ms） | 构建请求＋热缓存（µs） | 已有请求查缓存（µs） |
| --- | ---: | ---: | ---: | ---: | ---: |
| Ayuchan 配套素体＋长裙 | 21,259 | 316 | 70.168 | 7.400 | 0.263 |
| 素体＋RibbonDress 短袖＋13 号头发 | 21,397 | 263 | 60.738 | 3.015 | 0.041 |
| 素体＋RibbonDress 长袖＋13 号头发 | 22,069 | 263 | 63.751 | 2.286 | 0.040 |
| 素体＋1 份真实小衣物 | 8,806 | 181 | 34.303 | 4.105 | 0.063 |
| 素体＋10 份重复小衣物 | 12,955 | 694 | 41.089 | 20.289 | 0.093 |
| 素体＋100 份重复小衣物 | 54,445 | 5,824 | 125.227 | 131.003 | 0.730 |

100 份压力用例重复同一个 461 顶点的小衣物，仅共享绑定兼容的骨骼；其余骨骼独立命名。
该结果不代表 100 件不同衣服已经拟合到素体，也不能换算为游戏 FPS 提升。
批次均值的 p50 不等于逐调用延迟分布，已有请求的缓存查询不等于整次换装。

实际客户端中还记录了以下单次 CPU 调用耗时，它们没有预热统计：

| 实际操作 | 单次 CPU 观测（ms） |
| --- | ---: |
| 短袖切换 Rose 纹理 | 0.087 |
| 替换为长袖模型（含实例与 backend 挂载） | 289.673 |
| 换回缓存的短袖模型（含实例与 backend 挂载） | 171.290 |
| 短袖切回 Blue 纹理，两次观测 | 0.073 / 0.014 |

CPU 组装缓存能够避免再次组装同一几何与骨架，仍会创建新的 ModelInstance 和 backend
渲染资源。本次缓存未消除整次换装的这部分开销；上表中的单次调用也不是 GPU 或整帧耗时。

## 当前适配与验证边界

RibbonDress 与该素体的同名骨骼绑定位置不同，衣服与头发保留各自的独立绑定骨架。
本例不启用物理，也不执行全身动画；未实现自动体型拟合或骨骼重定向，不能据此保证
任意服装都无穿插。Ayuchan 配套长裙对照使用严格兼容的共享骨骼。

手持物品使用真实右手首 Anchor 与原生 ItemRenderer，不修改玩家物品栏。
本次客户端实际验收的是 Diamond Sword；Iron Pickaxe 是示例提供的另一个选项。
穿戴防具的 humanoid armor layer、其他 shader pack 与其他操作系统未在此示例中验收。
BSL 路径只验证能跑通，不将此解释为新的画质验收或性能测量。

交互命令和独立预览窗口入口已提供；上述客户端实测使用自动 headless Free Camera 输出，
不将其解释为本次重新验证了独立窗口操作。

## 本地素材准备

使用已解压的测试目录 `pack_1` … `pack_9`，本例选取：

| 组件 | 素材 |
| --- | --- |
| 素体 | `pack_7/TDA Morphable Base by Ayuchan513/TDA Base Edit by Ayuchan.pmx` |
| 可换纹理的衣服 | `pack_4/RibbonDress/Dress_ShortSleeves.pmx` |
| 配色 | 素材原始 `Dress_Sapphire.jpg` / `Dress_Rose.jpg` |
| 可替换的衣服模型 | `pack_4/RibbonDress/Dress_LongSleeves.pmx`，原始 Mist 配色 |
| 配套骨骼组装对照 | `pack_3/#1 Dress by Ayuchan513/Dress by Ayuchan513.pmx` |
| 头发 | `pack_8/VRoid Overall Hair Presets/13.pmx`，所有穿搭均包含 |
| 手持物品 | MC 原生 Diamond Sword；也可切换 Iron Pickaxe |

从仓库根目录运行：

```bash
python3 scripts/prepare-wardrobe-example.py \
  --fixtures /absolute/path/to/extracted-fixtures \
  --output /tmp/kasuga-wardrobe-client/resourcepacks/kasuga-wardrobe-local \
  --enable
```

脚本仅需 Python 标准库；它保留原始 PMX 和贴图内容，生成六个 `.mmd.zip`、对应 scale=0.1
配置和 `model_proxy.json`。Rose archive 只将短袖的 Sapphire 贴图替换成原始 Rose 贴图，用于
预加载第二个 atlas sprite，运行时不会同时渲染两份衣服。`--enable` 将该目录资源包追加到
测试客户端 `options.txt`，保留其他资源包；不加该参数则在 MC 资源包界面手动启用。

素材、资源包、存档和报告应放在本地测试目录，不纳入 Git。

## 可交互运行

```bash
./gradlew :modules:modelling:runClient \
  -PkasugaClientDirectory=/tmp/kasuga-wardrobe-client \
  -PkasugaWardrobeDemo=true
```

进入测试世界后示例自动开启独立预览窗口；相机看向玩家上方 40 格处的真实模型。
同一客户端只允许一个 Wardrobe session。也可不传属性，进入世界后运行 `/ksglib wardrobe start`。

| 命令 | 行为 |
| --- | --- |
| `/ksglib wardrobe start` | 重新创建示例与独立窗口；来源未就绪时报错 |
| `/ksglib wardrobe texture blue` / `rose` | 在 short 模型上切换材质帧，保留同一网格和实例 |
| `/ksglib wardrobe model short` / `long` / `ayuchan` | 替换衣服模型，保留同一身体与句柄 |
| `/ksglib wardrobe item sword` / `pickaxe` / `none` | 更换手持物品，不修改实际玩家的物品栏 |
| `/ksglib wardrobe capture` | 自动拍摄六个阶段，输出到 `debug/wardrobe-example/` |
| `/ksglib wardrobe status` | 显示当前模型、纹理、物品和组装缓存计数 |
| `/ksglib wardrobe stop` | 释放窗口、相机、装备绑定、实例、组装及来源引用 |

资源 reload 或离开当前世界会关闭 session；重新 `start` 会取新发布的素材。
纹理帧不会自动迁移到另一个衣服模型，本例切回 short 时明确选择 blue。

## 自动截图验收

先在上述测试目录创建测试存档，或复制已有测试存档；例如存档名为 `wardrobe`：

```bash
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=/tmp/kasuga-wardrobe-client \
  -PkasugaQuickPlayWorld=wardrobe \
  -PkasugaWardrobeDemo=true -PkasugaWardrobeCapture=true
```

该模式不创建预览窗口，捕获 960×720 的 Free Camera 输出。依次生成：

1. 短袖 Sapphire 原配色。
2. 同一短袖模型的 Rose 配色。
3. 替换成长袖 Mist 模型。
4. 换回已经缓存的短袖，校验 `Model` 对象相同且 build 计数不增加。
5. 原生 MC 剑在真实右手首锚点上渲染。
6. 旋转右手首后再次渲染，验证武器跟随。

每阶段等待 30 帧后截图，报告记录材质/模型/装备/骨骼旋转的图像差异。
新 session 的自动模式总共两个组装 build；已操作过的交互 session 允许复用之前的缓存结果。
成功日志为 `WARDROBE_EXAMPLE_PASS`，同时 `report.json.passed=true`；失败日志为
`WARDROBE_EXAMPLE_FAIL`。Gradle 的退出成功不能代替上述客户端证据。自动模式结束会退出 MC。

RibbonDress 与该素体的同名骨骼绑定位置不同，示例明确保留衣服的独立绑定骨架，
不启用物理，也不执行全身动画；这不是自动体型拟合或骨骼重定向。
`ayuchan` 对照使用配套长裙与严格兼容的共享骨骼。详见
[组装边界](model-assembly.md)和 [Anchor 与刚性装备](anchors.md)。

## 启用 shader pack 的冒烟验证

使用已有本地 shader pack，例如 BSL 8.2.04；相对路径从仓库根目录解析。

```bash
./gradlew :modules:modelling:runClientHeadless \
  -PkasugaClientDirectory=/tmp/kasuga-wardrobe-client \
  -PkasugaQuickPlayWorld=wardrobe \
  -PkasugaWardrobeShaderSmoke=true \
  -PkasugaWardrobeShaderPack=/absolute/path/to/BSL_v8.2.04.zip
```

`kasugaWardrobeShaderSmoke=true` 自动启动同一套带头发的六阶段示例，不需要再开启
`kasugaWardrobeDemo`。在真实模型与物品渲染阶段检查当前 Iris pack 非空、shader pack 正在使用，
且实际 pipeline 为 `IrisRenderingPipeline`；只有阶段全部完成并通过 GL 检查才记录
`WARDROBE_SHADER_SMOKE_PASS`。失败记录 `WARDROBE_SHADER_SMOKE_FAIL`，不把 Gradle
退出成功当作 shader 验收成功。

该模式默认不读取像素、不保存图片/JSON、不记录操作耗时；不能同时打开 `kasugaWardrobeCapture`。
仅使用相机自己的 shader session，不将 BSL 设为主视角 shader pack。
如需查看实际结果，追加 `-PkasugaWardrobeShaderPreview=true`，只在持剑阶段保存一张
`debug/wardrobe-example/shader-preview.png`，仍不采样性能。图片为本地查看用，不纳入提交。

本机 BSL 8.2.04 路径已完成六阶段，Iris OIT 正常启用，无模型上传失败、矩阵栈异常或
无 shader 回退。因用户要求查看结果，另外取了一张持剑画面；原无 shader 的截图和性能报告未被覆盖。
这项验证确认运行路径通畅，不证明其他 shader pack、其他平台或最终画质均已通过。

## 性能展示

用 [modelAssemblyProbe](model-assembly.md#本地真实素材探针与缓存基准) 连续运行三次独立 JavaExec JVM，
顺序交替为 `uncached-first`、`cached-first`、`uncached-first`，报告路径每次不同。
除了配套长裙和 1/10/100 份重复小衣物，探针还会测量本例的素体、RibbonDress 短袖/长袖与头发组装。
完成后运行：

```bash
python3 scripts/summarize-wardrobe-example.py \
  --capture /tmp/kasuga-wardrobe-client/debug/wardrobe-example \
  /tmp/wardrobe-bench-1.json /tmp/wardrobe-bench-2.json /tmp/wardrobe-bench-3.json
```

仅输出同目录的 `summary.md`；`--output /path/to/report.md` 可指定 Markdown 路径。
汇总文件不依赖图片、HTML 或额外汇总 JSON。表格展示三个 JVM 各自批次均值 p50 的中位数，并区分未缓存组装、构建请求再查热缓存、
已有请求查热缓存。输入解析、贴图解码、GPU 蒙皮和绘制均不计入这些 CPU 组装指标。
100 份压力用例重复同一件真实小衣物，不能解释为 100 件不同且已适配的衣服。

客户端报告中的 `operations` 是少数实际操作的 CPU 观测值；模型切换包含实例与 backend 挂载，
不能与上述预热基准或 GPU/整帧性能混用。性能数字应以本机实际生成报告为准。
