# KasugaLibNext 文档

本站是 KasugaLibNext 及其下游模组的开发文档，面向要接入渲染、模型与数据驱动能力的模组作者。

## 站内结构

| 节 | 适合的读者 | 内容 |
| --- | --- | --- |
| [getting-started/](getting-started/) | 第一次接入 | 从零建立工程、跑通第一个示例 |
| [guides/](guides/) | 带着明确任务来 | 上手教程、示例配方与验证步骤 |
| [reference/](reference/) | 对照签名查阅 | 接口、配置 schema、命令与失败行为 |
| [explanation/](explanation/) | 想理解机制 | 模块边界、数据流、算法与资源所有权 |
| [meta/](meta/) | 项目与版本 | 兼容矩阵、迁移指南、许可证、贡献者文档与验证记录 |

## 文档维护

新增可调用接口时在 `reference/` 补全包名、签名、单位、默认值、错误条件与生命周期，并链接到对应原理；
修改接口时同步调用示例与配置表；迁移或改名时更新索引、根 README、相对链接与锚点。
原理文档用类型名、数学表达式和流程图解释机制，方法参考、配置表和操作步骤归 `reference/`。

完整规约见 [reference/README.md](reference/README.md) 的「文档维护」段。
