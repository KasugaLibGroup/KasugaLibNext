# 已知限制

本页汇总当前源码、构建配置与各子系统实测文档中已明确记录的限制、平台特性与兼容性边界。

## 图形后端与历史模块

- **仅实现了 OpenGL / Minecraft 宿主后端**：`modules/render-core` 定义了图形 API 无关的契约，但当前可用渲染实现仅包含 `modules/render-opengl` 与 `modules/modelling`（Minecraft Blaze3D）。Vulkan 与 Metal 后端尚未实现；工作区中残留的 `modules/render-metal` 构建目录未被 `settings.gradle` 或 Git 纳入工程。
- **历史建模 UI 原型不参与构建**：`modules/old` 仅用于保留历史原型源码浏览（需显式传入 `-PkasugaIncludeOld=true` 才纳入 Gradle），依赖已退役的旧注册接口，无法直接编译。

## macOS 与平台差异

- **macOS TBO 上传策略**：受 Apple OpenGL 驱动在 `glMapBuffer` / `glBufferSubData` 更新纹理缓冲后触发高代价校验的影响，`modules/render-opengl` 为 macOS 单独使用 `MacTboUploadDevice`（每次上传通过 `glBufferData` 执行 `macos-tbo-orphan`），VBO 仍使用通用 fence 映射路径。
- **macOS 深度精度与线程要求**：`ksglib_main.fsh` 记录了 macOS 固定管线深度写入可能向下舍入 1 ULP 以及水面渲染顺序敏感的问题；独立 LWJGL 渲染测试（`renderGlTest` / `renderPreview` / `renderBench`）在 macOS 上依赖 `-XstartOnFirstThread`。
- **macOS / Windows 无窗口模式仍依赖桌面会话**：`runClientHeadless` 在桌面平台默认采用 `hidden`（隐藏的 GLFW 窗口），仍需要桌面图形环境；仅有 Linux 支持基于 patched GLFW 3.4 Null 平台的 `egl`（`EGL_PLATFORM=surfaceless`）无显示服务启动。

## Iris、Sodium 与着色器兼容边界

- **字节码 ABI 门控与降级**：`modules/iris-compat` 中的 `IrisCameraMixinPlugin` 与 `CompatCapability` 在加载期探测 Iris / Sodium 类与方法签名。当用户环境安装的 Iris / Sodium ABI 不匹配时，多机位 Iris 隔离、PBR 模型格式化或加权 OIT 会记录警告并弹出提示（`kasuga_lib.iris.models.unavailable.message` / `kasuga_lib.oit.fallback.iris`），安全退回无光影路径或排序透明渲染，而不保证兼容任意版本的 Iris/Sodium。
- **加权 OIT（WBOIT）范围限制**：
  - 当前渲染阶段为 `AFTER_TRANSLUCENT_BLOCKS`，只负责把 KasugaLib 的 `BLEND` 层合成到已完成场景上，**不**把原版水面或光影包自身的透明片元纳入同一 OIT 方程。
  - 启用 Iris 光影包时无法在不修改第三方光影包的前提下向其 fragment shader 注入 Kasuga 深度权重，因此采用权重为 1 的常权重 WBOIT，并受 Iris 自身 `entities_translucent` shader 约 `0.1` alpha test 的影响；该路径仅 resolve 主颜色目标，依赖额外自定义 gbuffer 通道表达透明材质的光影包可能需要专门集成。
  - 若浮点目标不可用或 Iris world framebuffer 不可绑定，系统回退至按距离由远到近排序的传统透明渲染。
- **多机位与光影顺序约束**：多机位在同一个主 OpenGL context 上**顺序**渲染，不支持多线程并发绘制机位；额外机位仅输出世界渲染阶段与 Iris 最终合成，不含 HUD、GUI、第一人称手部与发光轮廓。
- **天气滤镜与光影交互**：`WeatherFilters` 在启用 Iris 光影包时会自动从 GPU 顶视深度剔除回退到 CPU 列高度裁剪；[天空体积云](../reference/volumetric-clouds.md)与[世界空间光源](../guides/world-lights.md)目前均未在任意第三方光影包的自定义 gbuffer 通道下完成全面验证。

## 世界空间光源、体积云与粒子流体

- **`WorldLights` 的深度重建光照边界**（[世界空间光源](../guides/world-lights.md)）：
  - 运行在 `POST_PROCESS`（priority 700/701），基于场景深度重建相邻法线、以屏幕颜色近似反照率；**不支持阴影贴图、离屏遮挡查询、间接光反弹**（墙体不会向背面投射阴影），没有写入深度的透明表面无法独立受光。
  - 不修改原版方块光照（light engine），不影响刷怪或游戏逻辑。
  - 体积光在球形介质内执行 48 步积分，多体积重叠仅近似相加散射与光学厚度，不做多次散射耦合。
- **天空体积云边界**（[天空体积云](../reference/volumetric-clouds.md)）：当前不提供长距离跨云遮光、地面云影或完整气象物理模拟；无原版云层的维度不绘制天空云场。`HIGH` 质量在云层内部每帧 GPU 成本显著高于 `BALANCED`（默认采用 `BALANCED`）。
- **粒子流体求解器近似**（[托管效果与后处理](../reference/effects-and-postfx.md)）：`StableFluidGrid3D`（`GasSmokePreset` / `LiquidFlowPreset`）仅面向视觉效果，不含自由表面、表面张力、液滴合并、守恒 FLIP/PIC，也不对实体、活塞或原版流体方块施加双向物理推力。

## 物理、布娃娃、模型格式与碰撞

- **Box3D 原生编译与能力限制**（[PMX 与 glTF 布娃娃配置](../reference/mmd-ragdoll.md)）：
  - 未在 `PATH` 中检测到 `cmake` 时，Gradle 会跳过 `kasuga_box3d` 编译并排除 `box3d` 标签测试。
  - Box3D 不提供流体体积 / 浮力接口；旧配置中的 `simulation.solver_iterations`、`max_angular_speed`、`limit_stiffness` 与 `sleeping.delay_seconds` 仅保留反序列化兼容，不再改变求解结果；PMX 六轴关节的三轴平移当前收紧为固定锚点。
  - PMX 2.1 逐顶点 soft-body 仅完成读取解析，尚未接入 Box3D 求解与顶点写回。
  - glTF 布娃娃要求相邻的 `.gltf.json` manifest 与显式 `ragdoll` profile，不能自动猜测人体刚体拓扑。
- **穿搭与碰撞边界**：
  - 穿搭组装示例（[换装验证记录](wardrobe-benchmarks.md)）未实现自动体型拟合或跨骨架重定向，不保证任意不配套服装模型无穿插。
  - 服务端带动画实体碰撞（`ServerCollisionModels`）目前由数据包直接支持 `.bbmodel` 与独立 `.gltf`/`.glb`；其他格式须由代码包装为 `ServerCollisionModels.Asset` 后显式挂载（[模型碰撞与远景](../guides/world-model-collision-and-lod.md)）。

## 离屏渲染、无窗口与世界空间 UI

- **无内置离线视频编码器**：`MinecraftFrameOutputs` 在真实游戏客户端渲染线程交付只读 GPU FBO / 纹理，游戏 tick 照常推进，不提供固定时间步离线导出器或内置编码器（[离屏输出与多机位渲染](../guides/offline-rendering.md)、[MC 最终画面离屏输出](../reference/frame-output.md)）。
- **Linux EGL 与多窗口限制**：Linux `egl` 模式需要配合仓库补丁构建的 GLFW 3.4（Null 平台 pbuffer）及支持桌面 OpenGL 的 EGL 驱动，不能直接用原版 GLFW 替代；Linux Null/EGL 模式下**不支持多窗口**（`MinecraftFrameWindows` 会直接报错）。
- **世界空间 UI 射线与输入限制**（[世界空间 UI 与自由光标输入](../reference/world-ui-input.md)）：
  - 面板平面相交不做方块遮挡测试，也不做纹理 alpha 测试；默认最大命中距离为 5 格。
  - 自动化冒烟测试覆盖按键与字符流，不覆盖各操作系统原生输入法（IME）候选框定位与交互手感。
- **蓝图显示上限**（[蓝图与可嵌套世界显示](../guides/world-display.md)）：`BlueprintImporters` 限制单次输入 NBT <= 64 MiB、方块数 <= 131,072、单轴跨度 <= 4096；内置只识别原版/Create 结构、Litematic v5–v7 与 Sponge `.schem` v2–v3，不包含旧版数字 ID `.schematic`。

## 脚本引擎与 Java Shader DSL

- **Javet 首次运行依赖原生库拉取**（`JavetDownloader.java`）：`kasuga_lib_javet` 不支持 Android；若 `<gameDir>/native-libraries/javet/` 尚未预置对应平台动态库，首次加载需要从 Maven 镜像网络下载或手动放入该目录。
- **Java Shader DSL 类型化 IR 限制**（[Java Shader DSL 参考](../reference/shader-dsl.md)）：类型化 API 当前不支持自定义用户函数、struct uniform、struct 阶段输入/输出、非 float 的 uniform 数组、任意 swizzle、`discard`、导数与高级纹理采样；这些特性目前只能借助 `@DelicateShaderApi` raw 逃生口或独立 GLSL 资源文件实现。

## 数据驱动 v2 与整合打包边界

- **v2 构建管线边界**（[排查与迁移](../explanation/data-driven/08-troubleshooting-migration.md)）：
  - `DataDrivenBuildPipeline` 的 reload 预检不扫描 `globDirectories()`（如未写入 manifest 的 `state_machines/` 文件），也不预构造 `ResourceLocation`，因此大小写或非法字符仍可能在运行期 `/reload` 时被拒绝。
  - `build()` 在发生 `FAILED` 时不具备事务回滚能力；`DataDrivenRegistrationProviders` 每个宿主仅允许安装一个 provider；`FactoryRegistry` 与 `DataDrivenBuildHooks` 当前未提供生产级注销接口。
  - KasugaLib 不负责世界存档兼容检测、旧包历史校验或资源包物理发现，这些策略由宿主模组自行实现。
- **Create / Sable 与 JPMS 打包注意**：
  - `SableSceneHosts` 目前面向 Sable `2.0.6` / 航空学 `1.3.2`（Companion `1.6.0`），未覆盖 Valkyrien Skies 或独立专用服务器实机回归；`ContraptionLod` 仅同步玩家 512 格以内已加载区块中的结构，不会强加载区块。
  - 下游模组引入 `kasuga-library`（`-all`）并同时通过 `inject` 引入依赖时，必须排除独立的 `lib.kasuga:utils` 运行时依赖，否则 FML/JPMS 会因重复包导出在启动早期抛出 `ResolutionException`。
