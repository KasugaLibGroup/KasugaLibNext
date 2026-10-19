# 真实素材换装与 MC 装备示例

开发示例类：`lib.kasuga.rendering.models.mc.api.WardrobeExample`，位于 `contentTesting` source set。
它通过生产 PMX Reader 加载用户提供的素材，随后只使用通用 `Model`、组装缓存、材质实例、
Skeleton Anchor 和 MC 装备适配器。示例代码不进入发布包。

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
[组装边界](../reference/model-assembly.md)和 [Anchor 与刚性装备](../reference/anchors.md)。


> 本文是配方篇。本次实现与验证记录、本机性能基线见 [换装示例验证记录与性能基线](../meta/wardrobe-benchmarks.md)。
