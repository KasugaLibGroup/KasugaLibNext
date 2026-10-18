# 数据驱动注册专题

> **陷阱：旧布局与当前实现不符。** 上游早期文档描述的布局是 `data/<modid>/kasugalib/*.json`
> （平铺文件、`sources` 字段），已废弃。现行契约是索引目录 `data/<modid>/kasuga_lib/data_driven/`
> 加内容目录 `data/<modid>/kasuga_lib_content/`。

参考页（本目录）：

| 文档 | 类型 | 内容 |
| --- | --- | --- |
| [schema.md](schema.md) | Reference | 索引清单与内容文件的字段、类型、D1–D6 规则、错误分类 |
| [api-registration.md](api-registration.md) | Reference | 注册期入口 `JsonTreeBuilder`、索引 API、内容分派与冲突解决 |
| [api-extension.md](api-extension.md) | Reference | `TypeHandler` 体系、`BuildContext`、`FactoryRegistry`、属性解析器 |
| [api-diagnostics.md](api-diagnostics.md) | Reference | `Diagnostics` 门面、`/kasuga_data` 命令与错误分类 |
| [api-reload.md](api-reload.md) | Reference | reload API、可断言的纯函数面与调用示例 |

入门与教程（`guides/` 节）：

| 文档 | 类型 | 内容 |
| --- | --- | --- |
| [intro.md](../../guides/data-driven/intro.md) | Tutorial | 从空目录到方块动起来的入门路径 |
| [guide-content.md](../../guides/data-driven/guide-content.md) | How-to | 内容组织、分组继承、last-wins、验证与排查 |
| [guide-extension.md](../../guides/data-driven/guide-extension.md) | How-to | 自定义 TypeHandler / 工厂 / 属性编译器 / ReloadHandler |

原理概览见 [数据驱动注册原理](../../explanation/data-driven-registration.md)。

> 上游旧页中仍然有效的少数限制条目（模型/纹理与翻译键不自动生成、内置物品属性解析器的覆盖面、
> 内嵌 BE 的 `data_type` 不能从 JSON 配置）已并入
> [guide-content.md](../../guides/data-driven/guide-content.md) 第 7 节与
> [guide-extension.md](../../guides/data-driven/guide-extension.md) 第 10 节。
