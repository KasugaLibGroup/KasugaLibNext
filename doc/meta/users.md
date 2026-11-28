# 使用方

本页仅记录在当前工作区或仓库中可以直接查证的使用方工程；未在工作区核实过的外部下游模组不在此列举或推测。

## 已核实的下游模组：`KuaYue`（跨越）

- **项目路径与标识**：工作区同级仓库 `KuaYue`（`mod_id=kuayue`，`mod_version=2.0.0`，`mod_group_id=cn.kycraft.kuayue`，采用 MIT License）。
- **构建与目标环境**：面向 `Minecraft 1.21.1` / `NeoForge 21.1.268`（Parchment `2024.11.17`、`Java 21`），`KuaYue/settings.gradle` 通过 Gradle 复合构建 `includeBuild("../KasugaLibNext")` 直接消费 `KasugaLibNext`，将 `lib.kasuga:kasuga_lib` 替换为 `:modules:library`（`Bundling.SHADOWED`），同时替换 `inject`、`generator`、`resource-compiler`、`formula` 并引入 `../KasugaLibNext/modules/gradle-plugin`。
- **子模块结构**：包含 `modules/core`、`modules/decoration`、`modules/train-parts`、`modules/scheduling`、`modules/ui`、`modules/provisions` 与 `temporal/migration`，在模块中应用 `builder.kasuga.monorepo`、`builder.kasuga.generator`、`builder.kasuga.resource-compiler` 插件并启用 Micronaut `4.9.10` DI。

### `KuaYue` 实际使用的 KasugaLibNext 能力

| 子系统 | 在 `KuaYue` 中的实际使用证据 |
| --- | --- |
| DI 与注册系统 | `KuaYue.java` 使用 `ModApplicationContext.create(KuaYue.class)` / `init(...)` 与 `KasugaLibRegistry.getRegistryOf(MODID)`；车厢部件与调度模块使用 `Reg`、`Registry`、`RegistryGroup`、`BlockReg`、`ItemReg`、`BlockEntityReg`、`BlockEntityRendererReg`、`RpcApi`、`@BeanOnlyIn` |
| 数据驱动 v2 与资源平台 | `KuaYue.java` 安装 `DataDrivenRegistrationProviders.install(MODID, KuaYueContentRegistrationProvider::build)` 并对接 `ResourceSystem` / `ResourceSources` / `ResourceSnapshot`；`KuaYue/doc/数据驱动注册-快速上手指南.md` 与 `KuaYue/doc/轨道数据驱动Schema.md` 使用 `on_register` 清单、`registry_groups`、预设车包 `kuayue-preset-running-gear`（`tracks` parcel）与自定义 `TrackTypeHandler`，`KuaYue/modules/train-parts/resources/packs/` 另包含 `kuayue-preset-industrial`（内含 `parcels/jy_maintenance/parcel.json`）等预设包 |
| Create 铁路与转向架 | 使用 `RailwayData`、`RailwayManager`、`BogeySizeReg`、`TrackReg`、`TrackMaterialReg`、`CustomTrackSegment`、`ProxyBogeyVisual`、`ContraptionShapeProvider` 等扩展接口 |
| 模型 Proxy、动画域与尺寸 | 在 `modules/train-parts` 等处使用 `ProxyModelBlockFactories`、`BlockEntityProxyDefinitions`、`ProxyComposition`、`ProxyControllerPort`、`ScopedPlaybackCommandApplier`、`ControlFrame`、`ControllerCheckpoint`；`KuaYue/modules/ui` 的 `BlueprintBlockRenderer` 使用 `BlockDimensions` / `BlockModelSource` |
| 打包排重实践 | `KuaYue/modules/scheduling/build.gradle` 与 `KuaYue/modules/ui/build.gradle` 显式从运行时类路径排除独立的 `lib.kasuga:utils`，避免与 `kasuga_lib` 的 shaded `-all` 包产生 JPMS 重复包导出冲突 |

## 仓库内参考集成与示例模块

除了独立下游模组 `KuaYue` 外，主仓库自身还包含以下用于验证下游接入契约的模块与示例：

- **`external/uip/ldlib`、`external/uip/ldlib-compose`、`external/uip/modernui`**：以独立 NeoForge 适配模组形态依赖 `modules/library` 暴露的渲染、UI 后端与模型预览 API。
- **`external/slp/javet`**：独立模组 `kasuga_lib_javet`，作为 `modules/scripting` 的 JavaScript 引擎扩展实现运行在 `kasuga_lib` 之上。
- **`examples/` 与 `contentTesting` 源集**：提供着色器 DSL、资源平台、世界显示、换装与物理布娃娃的可运行接入样例（见 [构建与验证入口](../guides/verification.md)）。

> 如果你的下游项目同样基于 `KasugaLibNext` 构建并希望列入本页，请在 `KasugaLibGroup/KasugaLibNext` 的 `master` 分支提交包含仓库链接与版本说明的 PR 或 Issue。
