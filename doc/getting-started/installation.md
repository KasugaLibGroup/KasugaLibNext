# 安装与部署

本页面向服主、整合包作者及负责构建发布产物的开发者，说明如何从源码构建可部署产物、正确放入 `mods/`、处理可选依赖与跨平台原生库，以及常见服务端 / 客户端配置项。

## 1. 部署前确认清单

| 项目 | 要求 |
| --- | --- |
| Minecraft 版本 | `1.21.1` |
| Mod Loader | NeoForge `21.1.203` 及以上（`[21.1.203,)`） |
| 运行时 Java | Java 21 |
| 核心模组产物 | `kasuga-library-1.0.0-all.jar`（带 `-all` 后缀的 Shadow JAR） |
| 伴随模组（聚合包） | Create `6.0.6-98` + Flywheel `1.0.4` + Ponder `1.0.59` + Registrate `MC1.21-1.3.0+62`（见第 3 节） |

> **切勿放入未 Shadow 的瘦 JAR 或拆分子模块 JAR**
>
> - 不要部署 `kasuga-library-1.0.0.jar`（约 44 KB，缺少 Shadow 重定位后的 Micronaut / Caliko / jgltf / ZXing 与嵌入式子模块类）。
> - 不要同时放入 `kasuga-core-*.jar`、`kasuga-modelling-*.jar` 等单个子模块 JAR，否则 FML / JPMS 会因共享包或重复模组 ID 报类加载错误。

## 2. 从源码构建部署产物

仓库当前仅在模块构建脚本中配置了本地目录出版（`file://${project.projectDir}/repo`）。在仓库根目录执行以下命令构建聚合模组 JAR（需 JDK 21）：

```bash
./gradlew :modules:library:assemble
```

构建完成后，取以下文件放入游戏或服务端的 `mods/` 目录：

- `modules/library/build/libs/kasuga-library-1.0.0-all.jar`

若需要完整校验全部构建产物与纯 JVM / 渲染发布检查（不含真实世界运行），可执行：

```bash
./gradlew renderingReleaseCheck
```

各检验任务说明见 [构建与验证入口](../guides/verification.md)。

## 3. `mods/` 中的依赖与可选兼容模组

### 3.1 Create 及其前置依赖

虽然 KasugaLibNext 的 Micronaut Bean 通过 `@CreateModBeans`（`IfCreateModLoaded`）在未安装 Create 时跳过加载，但当前 `:modules:library` 的 `META-INF/neoforge.mods.toml` 声明了：

- `kasuga_lib.mixins.json`
- `kasuga_lib_iris.mixins.json`（`required: false`）
- `kasuga_lib_create.mixins.json`（`required: true`，由 `CreateMixinPlugin` 加载）

`CreateMixinPlugin` 会在应用轨道 Mixin 时检查 `com/simibubi/create/content/trains/track/BezierConnection.class`，若缺失则抛出 `IllegalStateException("Create BezierConnection is missing")`。因此部署完整 `kasuga-library-*-all.jar` 时，请同时在 `mods/` 中安装对应版本的 **Create**（`1.21.1` / `6.0.6-98` 兼容版及其依赖 Ponder / Flywheel / Registrate）。

### 3.2 Javet JavaScript 脚本引擎（`kasuga_lib_javet`）

仅当整合包、数据包或下游模组加载声明了 `javascript` 引擎的脚本包（`package.toml`）时才需要安装伴生模组 `kasuga_lib_javet`（源码位于 `external/slp/javet`）。

> **打包注意（重要）**：`external/slp/javet/build.gradle` 当前未像 `modules/library/build.gradle` 那样将 `shadowJar` 限定到 `shadowLibrary`，因此直接执行 `:external:slp:javet:shadowJar` 生成的 `kasuga-javet-1.0.0-all.jar` 会把整个 `runtimeClasspath`（含 `net.minecraft.*` 与 `kasuga_lib` 类，体积约 116 MB）打入 JAR，**不可直接作为模组放入生产 `mods/`**。而薄包 `kasuga-javet-1.0.0.jar` 不含 `com.caoccao.javet:javet:5.0.1` 与 `org.apache.httpcomponents:httpclient:4.5.13`。在构建发布包前，请确保仅将 `javet` 与 `httpclient` 打包或以依赖提供，而非包含 Minecraft / `kasuga_lib` 本体类。

**离线 / 受限网络环境的 V8 原生库预部署**：

1. 运行时 `JavetDownloader` 会检查 `<gameDir>/native-libraries/javet/` 下是否存在对应平台库文件（如 `libjavet-v8-<os>-<arch>.*`）。
2. 若不存在，首次启动时会自动尝试从 `maven.aliyun.com` 或 `repo1.maven.org` 下载 `javet-<runtime>-<os>-<arch>-5.0.1.jar` 并解压其中的原生库。
3. 若服务器处于离线环境，可预先从 `com.caoccao.javet:javet-v8-<os>-<arch>:5.0.1` 中提取原生动态库放入 `<gameDir>/native-libraries/javet/`。
4. 仅服务端兜底：若服务器临时不想加载脚本而在未安装 `kasuga_lib_javet` 时阻止启动中断，可在服务端配置将 `ignore_scripting_errors` 设为 `true`（详见第 5 节）。

### 3.3 客户端可选模组（Iris / Sodium / Voxy / Sable / UI）

| 模组 | 侧别 | 行为说明 |
| --- | --- | --- |
| **Iris**（验证版 `1.8.12`）与 **Sodium**（验证版 `0.6.13`） | 仅客户端 | `kasuga_lib_iris.mixins.json` 为非强制配置；`IrisCameraMixinPlugin` 与 `IrisCompat` 在启动期通过字节码检查探测 ABI，不安装或版本不匹配时自动禁用光影包适配并保持原生渲染 |
| **Voxy** | 仅客户端 | `VoxyCompat` 通过 `ModList.get().isLoaded("voxy")` 自动探测，无需额外开关 |
| **Sable** | 双端可选 | 仅在安装 Sable 时启用对应场景宿主与 `enable_sable_weather_filter` |
| **LDLib2**（`2.2.44`）/ **ModernUI** | 可选 | 仅当安装对应的 `external/uip/*` UI 提供方伴生模组时需要 |

## 4. Box3D 原生物理库与跨平台打包

布娃娃与刚体物理（[PMX 与 glTF 布娃娃配置](../reference/mmd-ragdoll.md)）由内嵌的 Box3D C17 引擎通过 JNI 库 `kasuga_box3d` 执行。`NativeBox3D` 按运行平台加载 JAR 内的 `/native/<os>-<arch>/<mappedName>`：

- 本机执行 `./gradlew :modules:library:assemble` 时，仅当本机 `PATH` 中存在 `cmake`，才会编译**当前主机 OS/架构**的动态库（例如 macOS ARM64 为 `native/macos-aarch64/libkasuga_box3d.dylib`）。
- 缺少 `cmake` 时构建仍会成功，并打印警告；运行期 `NativeBox3D.availableOrWarn()` 会安全禁用 Box3D 物理，不影响模型渲染、FSM、数据驱动注册等其余能力。
- **跨平台分发**：若需让同一个 JAR 在 Windows x86_64、Linux x86_64 与 macOS ARM64 上均支持物理，应参考仓库 CI（`.github/workflows/build.yml` 中的 `package-universal` 流程）分别在三端运行 `buildBox3D` 产出 `native/<os>-<arch>/`，再用 `-PkasugaBox3DNativeResources=<合并目录>` 重新打包或合并进 JAR 的顶级 `native/` 目录。

## 5. 服务端 / 客户端配置与无窗口运行

KasugaLibNext 使用 NeoForge `ModConfigSpec`（通过 `@Configurable` 自动注册），会在运行目录的 `config/` 下生成三份配置：

| 配置文件 | 代表配置项与默认值 | 说明 |
| --- | --- | --- |
| `config/kasuga_lib-server.toml` | `ignore_scripting_errors = false` | 专用服务端缺少脚本包所需引擎时是否仅记 WARN 而不中断启动（`ScriptingServerApplication`） |
| `config/kasuga_lib-common.toml` | `enable_multi_bogey_carriage = false`<br>`enable_carriage_y_offset = false`<br>`max_bogey_offset = 1.0`<br>`max_carriage_y_offset = 0.5`<br>`derailed_on_bogey_offset_overflow = false`<br>`enable_contraption_lod = true`<br>`enable_contraption_weather_filter = true`<br>`enable_sable_weather_filter = true` | Create 铁路、多转向架车厢、动态结构 LOD 与室内天气遮挡开关（`CreateConfig`，仅 Create 存在时注册） |
| `config/kasuga_lib-client.toml` | `ignore_block_entity_renderer = false`<br>`enable_weather_filter = true`<br>`included_entity_types = []`<br>`excluded_entity_types = []` | 客户端 BER 注册开关（`ClientRenderingRegistry`）与室内 3D 天气过滤（`WeatherFilterConfig`） |

完整配置参考见 [配置项](../reference/configuration.md) 与 [PBR 配置](../reference/pbr.md)。

### 专用服务端与无窗口客户端

- **专用服务端（`DEDICATED_SERVER`）**：无需 OpenGL 或显示设备。`KasugaLibMixinPlugin` 与 `IrisCameraMixinPlugin` 会跳过所有客户端 Mixin，且标注 `@BeanOnlyIn.Client` 的服务 Bean 不会实例化。
- **无窗口 / CI 预览客户端**：若在无显示服务器或自动化环境中运行客户端（如 `runClientHeadless`），需要提前关闭 NeoForge 的 early window（`config/fml.toml` 中设置 `earlyWindowControl = false`），并根据平台指定 `-Dkasuga.headless=hidden`（桌面隐藏窗口）或 `-Dkasuga.headless=egl`（Linux 无 X11/Wayland 下的 EGL pbuffer）。详见 [离屏输出与多机位渲染](../guides/offline-rendering.md)。

## 6. 整合包资源热更与常用排障命令

在整合包中调整数据时，区分两个时机（参见 [数据驱动上手](../guides/data-driven/intro.md)）：

- **注册期内容（不可热更）**：`data/<namespace>/kasuga_lib/data_driven/*.json` 中 `on_register` 列出的方块、物品与注册组定义仅从 mod JAR 读取，修改后必须重启服务端和客户端。
- **reload 期与客户端资源（支持热更）**：`on_reload` 列出的状态机（`state_machines/`）与动画剪辑（`animation_clips/`）受数据包栈覆盖，服务端执行 `/reload` 即生效；客户端 `assets/<namespace>/models/model_proxy.json` 与模型/材质资产按 `F3 + T` 重载。

常用游戏内诊断命令（OP / 权限等级 2 适用于数据指令）：

- `/kasuga_data errors` / `/kasuga_data errors <mod>`：查看注册域与重载域诊断错误桶。
- `/kasuga_proxy list`：查看当前加载的 `proxy_model` / `proxy_model_be` 状态。
- `/kasuga_effects` / `/kasuga_pbr status`：检查客户端特效管线与 PMX→PBR 状态。
