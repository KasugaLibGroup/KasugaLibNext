# 数据驱动注册专题

> **陷阱：旧布局与当前实现不符。** 上游早期文档描述的布局是 `data/<modid>/kasugalib/*.json`
> （平铺文件、`sources` 字段），已废弃。现行契约是索引目录 `data/<modid>/kasuga_lib/data_driven/`
> 加内容目录（如 `data/<modid>/kasuga_lib_content/` 或索引中声明的相对路径）。
>
> 当前工作树同时包含两代注册入口：v2 宿主接管的 **多来源构建管线**（`DataDrivenRegistrationProviders` +
> `ContentSourceFactory.snapshot` + `DataDrivenBuildPipeline`）与用于单 mod JAR 的 **旧兼容入口**
> （`JsonTreeBuilder.buildForMod`）。

参考页（本目录）：

| 文档 | 类型 | 内容 |
| --- | --- | --- |
| [schema.md](schema.md) | Reference | 索引清单与内容文件的字段、类型、内建 `proxy_model` / `proxy_model_be` 参数、STRICT/LEGACY 差异、D1–D6 规则、错误分类 |
| [block-state-properties.md](block-state-properties.md) | Reference | 方块状态属性格式、原版属性引用与工厂扩展 |
| [api-registration.md](api-registration.md) | Reference | v2 宿主接管与构建管线（`DataDrivenRegistrationProviders`、`ContentSourceFactory`、`SnapshotContentSource`、`DataDrivenBuildPipeline`、`BuildOptions`、`BuildResult`、`DataDrivenBuildHooks`）与旧入口 `JsonTreeBuilder`、索引 API、冲突解决 |
| [api-extension.md](api-extension.md) | Reference | `TypeHandler` 体系、`HandlerCapability`（含 `OVERLAY`）、`DefinitionKey`、`RegistryClaim`、`ValidationContext`、`BuildContext`、`FactoryRegistry`（含内建工厂 `proxy_model` / `proxy_model_be`）、属性解析器 |
| [api-diagnostics.md](api-diagnostics.md) | Reference | `Diagnostics` 门面、`/kasuga_data` 命令、`BuildDiagnostic`、`/kasuga_proxy list` 与错误分类 |
| [api-reload.md](api-reload.md) | Reference | `ReloadOrchestrator`、`ReloadHandler`、`ManagedReloadAllowlist`、可断言的纯函数面与调用示例 |

> **章节编号对照**：本目录四篇 `api-*.md` 曾是同一页 `api.md`，按读者任务拆开后**保留了原章节号 §1–§15**，
> 以免全树已经写好的 `§N` 交叉引用失效；v2 新增的多来源管线与宿主接管 API 按 §16 起续编：
>
> | 页 | 章节号 |
> | --- | --- |
> | [api-registration.md](api-registration.md) | §1 模块与包结构、§2 JsonTreeBuilder、§3 索引 API、§4 内容分派与 apply、§5 冲突解决、§16 宿主接管与 Provider、§17 内容来源与快照（`ContentSourceFactory` / `SnapshotContentSource`）、§18 多来源构建管线（`DataDrivenBuildPipeline` / `BuildOptions` / `BuildResult` / `DataDrivenBuildHooks`） |
> | [api-extension.md](api-extension.md) | §8 TypeHandler 体系（含 `HandlerCapability` / `DefinitionKey` / `RegistryClaim`）、§9 BuildContext / RegBuildContext / ValidationContext、§10 FactoryRegistry 与内建工厂、§11 属性解析器与严格校验 |
> | [api-diagnostics.md](api-diagnostics.md) | §6 Diagnostics 门面、§7 `/kasuga_data errors` 命令、§12 错误分类、§19 结构化 `BuildDiagnostic` 与 `/kasuga_proxy list` |
> | [api-reload.md](api-reload.md) | §13 重载 API（含 `ManagedReloadAllowlist` 与启动预检）、§14 测试可断言的纯函数面、§15 调用示例 |

入门与教程（`guides/` 节）：

| 文档 | 类型 | 内容 |
| --- | --- | --- |
| [intro.md](../../guides/data-driven/intro.md) | Tutorial | 从空目录到方块动起来的入门路径 |
| [guide-content.md](../../guides/data-driven/guide-content.md) | How-to | 内容组织、分组继承、last-wins、验证与排查 |
| [guide-extension.md](../../guides/data-driven/guide-extension.md) | How-to | 自定义 TypeHandler / 工厂 / 属性编译器 / ReloadHandler |

原理与架构说明：

- [数据驱动系统（v2 架构与设计说明）](../../explanation/data-driven/README.md)（含概念生命周期、内容来源快照、多来源管线、宿主接线与迁移指南）
- [数据驱动注册原理](../../explanation/data-driven-registration.md)

> 上游旧页中仍然有效的少数限制条目（模型/纹理与翻译键不自动生成、内置物品属性解析器的覆盖面、
> 内嵌 BE 的 `data_type` 不能从 JSON 配置）已并入
> [guide-content.md](../../guides/data-driven/guide-content.md) 第 7 节与
> [guide-extension.md](../../guides/data-driven/guide-extension.md) 第 10 节。
