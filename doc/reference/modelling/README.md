# Modelling 模块

`modules/modelling` 提供 KasugaLib 的客户端模型加载、模型实例生命周期、Minecraft 渲染后端、纹理图集集成，以及相关的动画和物理功能。

方块实体通过 Proxy Session 驱动模型时，先阅读 [Proxy 系统原理](../../explanation/proxy/README.md) 和
[Proxy 接入指南](../../guides/proxy-integration.md)。

本文说明模组随资源包发布模型时的标准接入流程，以及开发该模块时应使用的验证与调试方法。实现仅限客户端使用，不能从专用服务端访问渲染 API。

## 前置条件

- Java 21。模块的 Gradle toolchain 固定为 Java 21。
- 被扫描到的模型只能在客户端资源重载完成后实例化。开发环境修改模型资源或 `model_proxy.json` 后，重启客户端或按 `F3 + T` 重载资源。
- `lib.kasuga.rendering.models.mc.Constants` 负责初始化模块，并注册内置管线和资源重载监听器。

## 从资源加载模型

### 1. 放入模型资源

将模型放在自己模组的资源目录中。例如：

```text
src/main/resources/assets/examplemod/models/vehicles/engine.bbmodel
```

对应的模型键为：

```text
examplemod:models/vehicles/engine.bbmodel
```

这里的路径必须包含 `models/` 前缀和文件扩展名。该 API 不使用原版模型标识形式 `examplemod:vehicles/engine`。

### 2. 通过 `model_proxy.json` 加入扫描

扫描器只会加载与下列文件匹配的资源：

```text
src/main/resources/assets/<命名空间>/models/model_proxy.json
```

JSON 对象的键是命名空间，值为需要扫描的资源路径模式数组。上例的最小配置为：

```json
{
  "examplemod": [
    "models/vehicles/**/*.bbmodel"
  ]
}
```

模式相对于资源命名空间：

| 模式 | 含义 |
| --- | --- |
| `*` | 除 `/` 外的任意字符 |
| `**` | 可跨目录的任意字符 |
| `?` | 一个任意字符 |
| `!pattern` | 排除匹配 `pattern` 的资源 |

例如，`"models/**/*.obj"` 会包含 `models` 下任意深度的 OBJ 文件；`"!models/legacy/**"` 会排除 `legacy` 子目录。加载器会将其读取到的代理配置中的命名空间合并为一份扫描配置。

### 3. 使用受支持的扩展名

内置路由器（`PipelineRegistry.BUILTIN_ROUTES`）依据文件扩展名选择管线：

| 扩展名 | 管线 ID | Loader / 说明 | 源数据形式 |
| --- | --- | --- | --- |
| `.geo.json` | `be` | `BEModelLoader`（Bedrock） | JSON |
| `.json` | `je` | `JEModelLoader`（Java Edition） | JSON |
| `.obj` | `obj` | `KsgObjLoader`（Wavefront OBJ） | 文本 |
| `.mmd.zip` | `pmx` | `KsgPmxLoader`（PMX/MMD 压缩包） | ZIP |
| `.glb`、`.gltf` | `gltf` | `KsgGltfLoader`（glTF） | 二进制 |
| `.bbmodel` | `bbmodel` | `KsgBbModelLoader`（Blockbench 原生模型） | 文本 |
| `.mtb`、`.fmtb` | `fmt_archive` | `FmtArchiveLoader`（ZIP 内含 `Model.txt` 或 `model.jtmt` 及贴图） | ZIP |
| `.bob`、`.beo`、`.fmf` | `fmt_binary` | `FmtBinaryLoader`（FMT 大端二进制流） | 二进制 |

`.geo.json` 的路由优先于通用 `.json`。其中 `FmtBinaryLoader` 解析 `.bob`/`.beo` 时会按模型路径（去掉 `models/` 前缀并改为 `textures/<路径>.png`）查找外部贴图，未找到时回退到缺失贴图；代码中另有独立的文本流 MTB loader 类 `KsgMtbLoader`（未接入 `PipelineRegistry.BUILTIN_ROUTES`）。此外，`PipelineRegistry.ASSEMBLY`（`"assembly"`）是通过 `PipelineRegistry.assemblies()`（`McModelAssemblies`）发布并由 `McModelHandle.ofAssembly(...)` 使用的组装模型管线，不按文件扩展名路由。若需使用不受支持的格式，应在客户端初始化时通过 `PipelineRegistry` 注册模型管线和路由。

## 创建并渲染实例

资源重载发布模型后，解析其管线、创建实例，并将实例添加到 Minecraft bridge/backend。内置名称分别是 `mc_bridge` 和 `mc_backend`。

```java
import lib.kasuga.rendering.models.mc.registry.PipelineRegistry;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.ModelPipeLine;
import lib.kasuga.rendering.models.uml.math.Transform;
import net.minecraft.resources.ResourceLocation;

ResourceLocation modelKey = ResourceLocation.fromNamespaceAndPath(
        "examplemod", "models/vehicles/engine.bbmodel"
);
ResourceLocation instanceKey = ResourceLocation.fromNamespaceAndPath(
        "examplemod", "engine_at_spawn"
);

ModelPipeLine<ResourceLocation, ResourceLocation> pipeline =
        PipelineRegistry.resolve(modelKey);
if (pipeline == null || !pipeline.hasModel(modelKey)) {
    // 代理规则未匹配，或资源重载尚未完成。
    return;
}

if (!pipeline.hasInstance(modelKey, instanceKey)) {
    Transform transform = new Transform().translate(x, y, z);
    ModelInstance instance = pipeline.createInstance(modelKey, instanceKey, transform, null, null);
    if (instance != null) {
        pipeline.addToRenderer(modelKey, instanceKey, "mc_bridge", "mc_backend");
    }
}
```

仅能在客户端的游戏/渲染流程中调用。模型键尚未发布时，`createInstance` 会返回 `null`。应复用稳定的 `instanceKey`：用相同键再次创建实例会替换管线的查询条目，但不会自动从渲染器移除旧实例。

实例不再使用时，通过管线移除，不要只持有实例引用后自行关闭：

```java
pipeline.removeInstance(modelKey, instanceKey);
```

该方法会从全部已注册后端中移除实例，并关闭其运行时动画和物理状态。

## Blockbench (`.bbmodel`)

`bbmodel` 管线支持 Blockbench 原生 JSON 中的立方体和网格元素、嵌套 outliner 组、元素/组可见性、旋转、按面选纹理、面 UV 旋转、外部纹理和内嵌 `data:image/...` 纹理。

`face.texture` 必须使用 Blockbench 写入的纹理数组索引。外部纹理资源位置可带或不带 `textures/` 和 `.png`，loader 会将其规范化为图集标识。例如 `examplemod:textures/vehicles/engine.png` 与 `examplemod:vehicles/engine` 都会解析为 sprite `examplemod:vehicles/engine`。

Blockbench 的 UV 坐标单位是纹理像素。`KsgBbModelLoader` 在创建顶点时按纹理的 **UV 画布** 将其转换到标准化 UV：优先 `uv_width`/`uv_height`，其次文件声明的纹理尺寸，最后退回项目 `resolution`。画布刻意与解码后的图片尺寸无关，因为 Blockbench 会把图片拉伸到画布上——`qj_bogey_main.bbmodel` 的 64×64 png 位于 128×128 画布上，其面 UV 最大到 128，若除以图片尺寸就会取到错误的区域。此转换刻意限制在 bbmodel loader 内部。不要在 `FlatModelData` 或其他共享后端路径中加入像素 UV 归一化，因为 OBJ、PMX、glTF、Bedrock 和 Java Edition 模型同样会经过这些路径。

立方体面遵循 Blockbench 的 `CubeFace.UVToLocal` 约定：从立方体外侧观察时，`uv` 矩形的 `u` 指向观察者右手方向、`v` 向下（顶面 `u=+X, v=+Z`；底面 `u=+X, v=-Z`），`rotation` 表示纹理顺时针旋转。loader 按左上 → 左下 → 右下 → 右上输出每个面，这同时也是后端生成朝外法线所需的绕序。

元素、组与动画的旋转按文件中的数值原样使用（不做符号翻转），并按内旋 Z → Y → X 合成，即矩阵 `Rz·Ry·Rx`——three.js 的 `Euler.order = "ZYX"`、Minecraft `ModelPart` 的 `ZP`/`YP`/`XP` 顺序以及 `RotHelper.rotation` 都是这一顺序。每个元素绕自身 `origin` 旋转，并处于父组已旋转的坐标系中，因此嵌套是相对的。只有跨越多个轴的旋转才能区分顺序，所以顺序写错时表现为“部分部件旋转不对”。Blockbench 的 Bedrock 导出会取反 X/Y 角度（`test_fan_be.bbmodel` 的 `[0,45,0]` 对应 `test_fan_be.geo.json` 的 `[0,-45,0]`），因此 `RotHelper` 里的符号翻转属于 Bedrock 相关 loader，不要照搬到 bbmodel。

仓库中的可运行内容测试示例位于：

```text
modules/modelling/src/contentTesting/resources/assets/kasuga_lib/models/block/test/blockbench/
```

## 光照模式、全局合批、组装与实时同步

### 光照模式（`ModelLightingMode` / `ModelLighting` / `ModelLightingDefaults`）

- `ModelLightingMode`（`lib.kasuga.rendering.models.uml.backend.ModelLightingMode`）定义实例级光照模式：`PBR`（默认）与 `BLOCK`（原版方块/物品明暗）。
- `ModelLightingDefaults.register(ResourceLocation model, ModelLightingMode mode)` 为指定模型键登记默认光照模式；默认值跨资源重载保留，`KasugaModelPipelines.createAndBind` 创建实例时会自动调用 `ModelLightingDefaults.resolve(modelLoc)`（未登记时默认 `ModelLightingMode.PBR`）。
- 宿主可通过 `ModelInstance.setLightingMode(mode)` 或 `McModelHandle.setLightingMode(mode)` 覆写单个实例的光照模式（未挂载时暂存并在资源替换后重新应用）；数据驱动 `proxy_model_be` 方块实体可在 `params.lighting_mode` 中配置 `"pbr"` 或 `"vanilla_block"`。
- `ModelLighting`（`lib.kasuga.rendering.models.mc.backend.ModelLighting`）将策略与方向遮光系数打包为不可变记录，通过 `ModelLighting.world(mode, level)`（按 `level.getShade(...)` 采样四向方块明暗）或 `ModelLighting.item(mode)` 构造，并作为 `GlobalModelBatcher` 的批处理键组成部分，向 `KasugaShaderInstance` / `KasugaGlobalBatchShaderInstance` 传递 `ksg_LightingMode`（`0` = PBR、`1` = 世界方块明暗、`2` = 原版物品漫反射）与 `ksg_BlockShade`。

### 全局合批与自动实例化（`GlobalModelBatcher` / `FlatModelData` / `KasugaGlobalBatchShaderInstance`）

- 在非 Iris 路径的 `OPAQUE` 与 `MASK` 阶段，`MCBackend.renderObjectsInternal` 会启用 `GlobalModelBatcher`；`TRANSLUCENT` 阶段不进入全局合批，以保持 WBOIT 或由远及近排序语义。
- `GlobalModelBatcher` 按 `BatchKey`（包含 pass、`VertexFormat.Mode`、自发光强度、环境光增强、`DepthBias` 与 `ModelLighting`）分组，将每个实例的变换矩阵、法线矩阵、亮度、骨骼偏移、GPU 蒙皮标志、打包光照与 overlay 写入 `ksg_InstanceData` TBO（每对象 `9` 个 texel / `36` 个 float，纹理单元 `10`），并将骨骼变换写入 `ksg_BoneTransforms` TBO（每骨骼 `36` 个 float，纹理单元 `11`），通过 `TextureUploadRing` 上传并由 `KasugaGlobalBatchShaderInstance`（`kasuga_lib:ksglib_global_batch`）绘制。
- **自动实例化**：当 `KasugaGlobalBatchShaderInstance` 具备 `ksg_Instanced` uniform，且连续若干采用 GPU 蒙皮（`!usesCpuSkinning()`）的实例属于同一 `Model` 且通过 `FlatModelData.shareBatchGeometry(...)` 校验（忽略可从实例 TBO 读取的 `UV1`/`UV2` 光照与 overlay 字段）确认顶点载荷一致时，若满足 `shouldInstance`（连续至少 2 个实例，且整批只有一个连续段或 `(count - 1) * meshBytes >= 16 KiB`），`GlobalModelBatcher` 改用共享 VBO 与 `GL31.glDrawElementsInstanced`（shader 中 `ksg_Instanced = 1` 读取 `gl_InstanceID`），不再合并重复顶点并改写 `UV1.x`。每个 chunk 上限受 `MAX_OBJECTS_PER_BATCH`（`Short.MAX_VALUE`）与硬件 `GL_MAX_TEXTURE_BUFFER_SIZE` 约束。

### 模型组装与 Dummy 分组（`McModelAssemblies` / `McDummyModels`）

- `McModelAssemblies` 将 `ModelAssemblyRegistry<McModelAssemblies.Reference, ResourceLocation>` 与 `ModelAssemblyCache` 接入 `PipelineRegistry.ASSEMBLY`（通过 `PipelineRegistry.assemblies()` 获取），支持以 `McModelAssemblies.Reference(model, modelName)` 引用其他已发布模型、编程式 `register`/`unregister`、资源重载原子替换（`replaceResourceDefinitions` / `publishResourceGeneration`）、依赖模型变更失效，以及 `McModelHandle.ofAssembly(...)` / `switchAssembly(...)`。详见 [Model 组装与穿搭缓存 API](../model-assembly.md) 与 [Minecraft 穿搭资源与实例切换 API](../minecraft-model-assembly.md)。
- `McDummyModels.create()` / `McDummyModels.create(@Nullable Transform rootTransform)` 创建无几何数据的纯变换骨架实例 `DummyModelInstance`（`Model.isRenderable()` 为 `false`，不分配顶点/网格/骨骼 TBO），并注册到 `MinecraftModelRuntime` 自动逐帧求值；可通过 `defineAnchor`、`setAnchorTransform` 与 `attach(anchorName, child[, localOffset])` 组织父子变换树，`close()` 时自动注销并解绑子模型。详见 [Anchor 与 Minecraft 装备挂接](../anchors.md)。

### 本地实时模型同步（`LiveModelSync`）

- `LiveModelSync`（`lib.kasuga.rendering.models.mc.component.LiveModelSync`）提供可选的本地开发预览桥接：将客户端游戏目录下的 `resourcepacks/kasuga_live` 通过 `DirectoryVfs` 与 `ResourceSystem.mountClientScoped(...)` 挂载为 `file/kasuga_live` 资源包（自动生成 `pack.mcmeta`，`pack_format: 34`），并在客户端未显示加载遮罩时每 `100 ms` 轮询 `.kasuga-sync.json`（`version: 1`、`generation`，最大 8 KiB）。
- `LiveModelSyncState` 提供 `350 ms` 去抖与单飞（single-flight）控制，检测到有效变更后触发 `Minecraft#reloadResourcePacks()` 并在完成后记录 `KASUGA_LIVE_MODEL_RELOAD_COMPLETE`。
- 客户端命令：`/kasuga_models live start`、`/kasuga_models live stop`、`/kasuga_models live status`。

## 开发与调试

以下命令均在仓库根目录运行。Windows 使用 `gradlew.bat`，macOS/Linux 使用 `./gradlew`。

### 编译与单元测试

```powershell
.\gradlew.bat :modules:modelling:compileJava
.\gradlew.bat :modules:modelling:test
.\gradlew.bat :modules:modelling:modelUnitTest
```

只运行 Blockbench loader 测试：

```powershell
.\gradlew.bat :modules:modelling:test --tests lib.kasuga.rendering.models.mc.typo.bbmodel.BbModelDefinitionTest
```

`modelUnitTest` 是模块的纯 Java 测试任务；常规 `test` 使用已配置的 NeoForge 测试运行时。

### 启动客户端并选择烟雾测试模型

`contentTesting` 开发客户端默认显示模型烟雾测试，生产代码不会创建这些实例。默认类型为 `bbmodel`。传入文件名或完整资源路径即可在玩家前方只显示一个 Blockbench 模型：

```powershell
.\gradlew.bat :modules:modelling:runClient `
  '-PkasugaTestModel=bbmodel' `
  '-PkasugaTestBbmodel=qj_bogey_main.bbmodel'
```

简写形式的 `kasugaTestBbmodel` 会在下面目录中解析：

```text
models/block/test/blockbench/
```

也可以传入 `models/vehicles/engine.bbmodel`。所选资源必须在启动客户端前已被当前 `model_proxy.json` 包含。内嵌纹理会在资源重载构建图集时注册，因此图集发布后不支持临时加载新的 bbmodel。

其他内置烟雾测试类型为 `obj`、`be`、`je`；其他任意值会走 MMD 测试路径：

```powershell
.\gradlew.bat :modules:modelling:runClient '-PkasugaTestModel=obj'
```

排查与模型无关的渲染问题时，可向 JVM 传入 `-Dkasuga.renderTestModels=false` 来关闭全部硬编码烟雾模型。

### 日志与故障定位

客户端运行目录：

```text
modules/modelling/run/client/
```

资源重载或客户端运行后查看 `logs/latest.log`。

| 现象 | 检查项 |
| --- | --- |
| 模型未显示 | 确认精确的 `ResourceLocation`，检查 `model_proxy.json` 是否匹配，随后重启/重载资源。查找 `Test bbmodel ... is unavailable after resource reload` 警告。 |
| 出现了错误模型 | 同时确认 `kasugaTestModel` 和 `kasugaTestBbmodel` Gradle 属性。bbmodel 简写始终会从测试 Blockbench 目录解析。 |
| 绿色或缺失贴图 | 检查外部纹理的命名空间和路径，确认纹理存在于 `assets/<命名空间>/textures` 下，并搜索图集/sprite 错误。 |
| 贴图呈颗粒状或平铺 | 检查纹理的 UV 画布而不只是图片：loader 按 `uv_width`/`uv_height`（退回声明尺寸、再退回项目 `resolution`）归一化像素 UV，因此图片小于画布时，所有面 UV 仍必须落在画布内。不要通过修改共享后端 UV 来补偿。 |
| 面出现镜像、旋转错误或受光方向相反 | 面 `uv` 矩形按 Blockbench 约定映射（从外侧看 `u` 指向右手、`v` 向下；`rotation` 为顺时针），并按朝外绕序输出。可与 `KsgBbModelLoaderFaceUvTest` 对照；不要单独调整某个面的角点顺序而不连带移动其 UV。 |
| 单轴旋转正常，但个别旋转部件不对 | 这些部件在 2～3 个轴上都有旋转，差别在欧拉顺序：必须是内旋 Z → Y → X（`Rz·Ry·Rx`，见 `KsgBbModelLoaderRotationTest`）。角度本身不取负；X/Y 取负是 Bedrock 相关 loader（`RotHelper`）的职责，因为 Blockbench 的 Bedrock 导出已经写入了取负后的 X/Y。 |
| 模型已加载但不可见 | 依次检查 `pipeline.hasModel`、`pipeline.hasInstance` 和 `pipeline.isRendering(modelKey, instanceKey, "mc_backend")`。确认变换位置在相机前方；重建变更过的实例前先使用 `removeInstance`。 |
| loader 报错 | 在 `latest.log` 中搜索 `Invalid Blockbench model`、`Unable to decode embedded texture` 和模型资源路径。 |

## 相关源码位置

| 范围 | 源码 |
| --- | --- |
| 客户端初始化和烟雾渲染器 | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/Constants.java` |
| 内置模型管线和扩展名路由 | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/registry/PipelineRegistry.java` |
| 模型生命周期 API | `modules/modelling/src/main/java/lib/kasuga/rendering/models/uml/dynamic/ModelPipeLine.java` |
| Blockbench loader | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/typo/bbmodel/KsgBbModelLoader.java` |
| FMT 压缩包 / 二进制 / MTB loader | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/typo/fmt/FmtArchiveLoader.java`、`FmtBinaryLoader.java`、`mtb/KsgMtbLoader.java` |
| 模型光照、合批、组装、Dummy 与实时同步 | `ModelLightingDefaults.java`、`ModelLighting.java`、`GlobalModelBatcher.java`、`McModelAssemblies.java`、`McDummyModels.java`、`LiveModelSync.java` |
| 资源代理匹配器 | `modules/modelling/src/main/java/lib/kasuga/rendering/models/mc/source/model/ModelProxyConfig.java` |
| Iris 兼容与内置效果/音频 | [Iris 兼容层](../iris-compat.md)、[内置效果与空间音频](../builtin-effects-and-audio.md) |
