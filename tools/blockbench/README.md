# Kasuga Model Components

将 `kasuga_model_components.js` 通过 Blockbench 的“文件 → 插件 → 从文件加载插件”安装。网页和桌面版均使用这一个文件，不需要构建插件。开发验证使用 Blockbench 5.2.2 官方源码构建版。

打开模型后使用“工具”菜单：

- **编辑 IK / IK 配置模式**：打开常驻侧栏。点击视口中的菱形骨骼或模型体块选择所属组；Ctrl/⌘、Shift 可多选。目标、链根、组父级均可点“点选骨骼”后直接点击模型或大纲中的骨骼。链根必须是末端的祖先，目标须在被驱动分支之外。
- **编辑物理骨骼 / 物理配置模式**：同一侧栏批量设置球/盒/胶囊、偏移、旋转、质量、阻尼、摩擦、碰撞过滤与父刚体关节。盒尺寸为三个半长；胶囊 X 为半径、Y 为圆柱段长度。视口提供位置、旋转、尺寸手柄；骨骼枢轴、IK 目标和刚体参数都有数值输入。
- **新增/编辑粒子源**：粒子源是原生 Locator 定位点，可在大纲选中、平移、旋转、重命名、复制、删除或拖入骨骼。它没有体积，不会生成模型面。设置发射率、寿命、喷射速度/角度、尺寸、颜色、加速度、纹理 ID 和 local/world 空间；喷射方向为定位点局部 +Y。颜色支持预设调色板、渐变选色和 HEX 输入。
- **组件 JSON**：修改高级参数及逐链节限制，未知字段会保留。
- **开始/停止预览**：直接在模型视窗预览 IK、次级骨骼摆动和粒子。编辑、保存、导出、切项目、Undo 或卸载会停止预览并恢复姿态。

先打开 [演示工程](../../examples/model-components/rig.bbmodel)，运行预览，再选择 `tip`、`tail` 或 `exhaust` 编辑参数。参数编辑支持 Undo/Redo。

批量编辑时，每个参数名前的勾选表示“应用此字段”；修改输入框会自动勾选。未勾选字段保留每根骨骼自己的值，混合值会有提示。点击“应用到 N 根骨骼”会先验证整个模型，再生成一次可撤销的修改。Esc 取消关系拾取或正在进行的手柄拖动。“父级骨骼”修改组层级；“父级刚体”设置物理关节，两者独立。

连杆可先选中其组，再点“为连杆创建 IK 控制点”。插件建立杆端和独立目标空组，估算的杆端必须用手柄或枢轴坐标校准到真实销轴中心，然后拖动目标预览。复杂模型可打开“只显示所选与关联骨骼”。这是固定杆长的旋转 IK；套筒与活塞需要先拆成独立组、标定枢轴，当前不自动识别液压机构，也没有伸缩求解器。

## 保存后自动同步

1. 游戏中执行 `/kasuga_models live start`。客户端会创建并挂载当前游戏目录下的 `resourcepacks/kasuga_live`，开始监控发布标记。
2. Blockbench **桌面版**侧栏点“自动同步设置”，选择该目录，填写游戏中使用的模型资源 ID，例如 `kuayue:models/bogey/qj.bbmodel`。默认同步 bbmodel，可内嵌纹理；Java / Bedrock JSON 使用对应项目格式，外部纹理继续按游戏资源 ID 提供。
3. 勾选“保存后自动同步”。随后每次成功保存工程/导出模型，插件原子替换模型文件、更新独立的扫描清单，最后写发布标记。游戏安静 350 ms 后自动执行资源重载；连续保存会合并，在途重载不会重入。
4. `/kasuga_models live status` 查看状态；`/kasuga_models live stop` 关闭监听。重启客户端后需重新开启。同步只替换该资源 ID；模型需要由现有游戏对象或预览场景引用。

同步路径与开关保存在编辑器本机、按工程保存路径隔离，不写入共享模型。网页版保留编辑和视口预览，自动同步需要桌面文件权限。当前使用完整资源重载，重载期间会出现游戏加载界面。

## JSON 兼容

插件采用增量格式，只在宿主编解码器生成的 JSON 中附加 `kasuga_lib`：bbmodel 与 Java 模型放在根对象，Bedrock 放在对应的 `minecraft:geometry[]` 对象。原来的 `elements`、`bones`、`textures`、`groups` 和其他字段不替换、不删除；不带扩展的模型继续走既有加载流程。Java 原生无骨架，额外骨骼和元素索引绑定全部放在扩展里。

格式、单位和默认值见 [组件参考](../../doc/reference/mc-model-components.md)，完整示例与 JSON Schema 位于 [examples/model-components](../../examples/model-components)。保留原生结构使普通读取器仍可读取静态几何；IK/物理/发射行为需要理解扩展的读取器。对会拒绝未知键的严格格式校验器，须另外验证其兼容性，不能从 JSON 合法推断其运行时接受。

## 当前预览范围

编辑器 IK 使用 CCD；游戏转换为已有 SkeletonDynamics 求解结构。物理视窗提供弹簧次级骨骼与刚体轮廓，便于调弹性/阻尼/重力，不模拟游戏原生刚体碰撞。`stiffness` 和每骨骼 `gravity` 是编辑器预览参数；游戏物理环境的重力由现有物理场景设置。粒子纹理 ID 会保存，编辑器先用着色圆点表示粒子。粒子源的游戏自动播放已接入模型实例生命周期，使用现有世界 billboard 管线。

游戏的 bbmodel、Java 和 Bedrock 加载器已读取 IK/刚体/粒子扩展；粒子源随模型实例创建、后端挂载、资源重载和实例销毁自动启动或清理。手动 F3+T 和上面的保存自动同步均沿用这条生命周期。

## 验证

纯逻辑测试：

```sh
node --test tools/blockbench/test/components.test.cjs
```

真实宿主测试使用官方 Blockbench 的本地网页构建，以及测试环境提供的 Playwright：

```sh
BLOCKBENCH_URL=http://127.0.0.1:8794 \
NODE_PATH=/path/to/blockbench/node_modules \
node tools/blockbench/test/blockbench-smoke.cjs
```

当前演示：`build/reports/model-components/config-modes/config-modes.mp4`，含 QJ 连杆 IK、DF11G 父级点选、双骨骼物理编辑及粒子调色板。两个模型副本、截图和 `report.json` 位于同目录，用户原始文件保持不变。

录制与真实鼠标测试（用户模型不入库）：

```sh
KASUGA_PRIVATE_MODELS=/path/to/private/models \
NODE_PATH=/path/to/blockbench/node_modules \
node tools/blockbench/test/config-modes.cjs
```

需要 Chrome、Playwright 和 PATH 中的 FFmpeg；省略 `KASUGA_PRIVATE_MODELS` 使用仓库演示模型。`KASUGA_CONFIG_OUTPUT` 可指定输出目录。

`CHROME_PATH` 可指定 Chrome 可执行文件，`KASUGA_SMOKE_OUTPUT` 指定截图/导出目录。测试检查三种真实 codec 只添加命名空间字段、IK 收敛、Java 临时骨架恢复、物理/粒子画面、常驻面板、调色板、保存回调（网页宿主使用测试文件系统适配器）、往返、Undo、跨项目隔离及卸载清理。纯 JVM 任务为 `:modules:modelling:modelUnitTest`，目标类 `*ModelComponent*Test`、`*LiveModelSyncStateTest` 与 `*ModelEmitterLifecycleTest`。

游戏自动重载 smoke：在隔离游戏目录、测试世界中运行 `scripts/run-headless-client.sh -PkasugaClientDirectory=/path/to/isolated/game -PkasugaQuickPlayWorld=world -PkasugaTestModelEmitters=true -PkasugaTestModelLiveSync=true`，结果输出为游戏目录 `debug/model-emitters/report.json`，断言自动重载、旧实例释放及最终粒子像素。
