# KasugaLibNext 文档

本站是 KasugaLibNext 及其下游模组的开发文档，面向要接入核心注册、依赖注入、脚本、渲染、模型、数据驱动与 Create 集成能力的模组作者。

## 站内结构

| 节 | 适合的读者 | 内容 |
| --- | --- | --- |
| [getting-started/](getting-started/) | 第一次接入 | 依赖与运行环境、最小示例工程与安装部署 |
| [guides/](guides/) | 带着明确任务来 | 数据驱动上手、内容与 Proxy 接入、世界显示与光源、模型碰撞、示例配方与验证步骤 |
| [reference/](reference/) | 对照签名查阅 | Core/Inject/Utils、脚本与 Gradle 构建、数据驱动、模型与渲染、Create 集成的接口、schema 与命令 |
| [explanation/](explanation/) | 想理解机制 | 线程模型、数据驱动 v2、Proxy、动画作用域与目标、渲染管线、物理与模块边界 |
| [meta/](meta/) | 项目与版本 | 兼容矩阵、迁移指南、更新日志、许可证、贡献者文档、已知限制、使用方、术语表与验证记录 |

## 文档维护

新增可调用接口时在 `reference/` 补全包名、签名、单位、默认值、错误条件与生命周期，并链接到对应原理；
修改接口时同步调用示例与配置表；迁移或改名时更新索引、根 README、相对链接与锚点。
原理文档用类型名、数学表达式和流程图解释机制，方法参考、配置表和操作步骤归 `reference/`。

完整规约见 [reference/README.md](reference/README.md) 的「文档维护」段。
