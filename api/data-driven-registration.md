# 数据驱动注册（参考入口）

> **本页已重组。** 上游版本描述的是旧布局 `data/<modid>/kasugalib/*.json`（平铺文件、`sources` 字段），
> 与当前实现不符。现行契约是索引目录 `data/<modid>/kasuga_lib/data_driven/` 加内容目录
> `data/<modid>/kasuga_lib_content/`。

数据驱动的完整文档在 [`data-driven/`](data-driven/)：

| 文档 | 类型 | 内容 |
| --- | --- | --- |
| [intro.md](data-driven/intro.md) | Tutorial | 从空目录到方块动起来的入门路径 |
| [guide-content.md](data-driven/guide-content.md) | How-to | 内容组织、分组继承、last-wins、验证与排查 |
| [guide-extension.md](data-driven/guide-extension.md) | How-to | 自定义 TypeHandler / 工厂 / 属性编译器 / ReloadHandler |
| [schema.md](data-driven/schema.md) | Reference | 索引清单与内容文件的字段、类型、D1–D6 规则、错误分类 |
| [api.md](data-driven/api.md) | Reference | Java API、Diagnostics 门面、`/kasuga_data` 命令、reload API |

原理概览见 [`../doc/data-driven-registration.md`](../doc/data-driven-registration.md)。

> 上游旧页中仍然有效的少数限制条目（模型/纹理与翻译键不自动生成、内置物品属性解析器的覆盖面、
> 内嵌 BE 的 `data_type` 不能从 JSON 配置）已并入
> [guide-content.md](data-driven/guide-content.md) 第 7 节与
> [guide-extension.md](data-driven/guide-extension.md) 第 10 节。
