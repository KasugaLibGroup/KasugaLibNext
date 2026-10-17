---
name: feature-impact-analysis
description: This skill should be used before writing any code for a new feature, refactor, or behavioral change in the KasugaLibNext / KuaYue Minecraft mod projects. It runs a mandatory three-stage analysis chain — (1) 改动分析: enumerate which of 17 capability domains the change touches and whether the project already provides each one; (2) 风险分析: judge the change against 48 fixed risk dimensions covering persistence versioning, save-file pollution, network sync, chunk boundaries, coordinate reference frames, mod interop, native platforms, rendering pixel correctness; (3) 测试策略: derive verifiable tests with mandatory level justification. Trigger when the user proposes a new feature, asks "会影响什么 / 有什么风险 / 要测什么", describes a gameplay or infrastructure addition, or requests impact analysis before implementation. Do NOT use for pure documentation edits or typo fixes.
agent_created: true
---

# 变更影响与风险分析

## 何时使用

在以下情况**动手写代码之前**使用：

- 用户提出新功能需求（玩法、基建、安全、架构增强任一）
- 用户要求评估"会影响什么 / 有什么风险 / 该测什么"
- 要改已有能力的内部实现（可能破坏调用方）
- 修 bug 但不确定影响面

**不适用**：纯文档修改、拼写修正、格式化、无行为变化的注释调整。

## 为什么存在

有一类缺陷反复出现：画面只在像素层面错、原生库只在特定平台失败、模组之间互相干扰、
只在服务端才暴露、只在旧存档里才炸。它们的共同点是——
**写单元测试时根本想不到要测**，因为测试维度由经验直觉决定，而经验直觉系统性遗漏这些类别。

根因不是测试数量不够，是**测试的维度不对**。
本 skill 把「想不想得到」变成三段固定的、可逐条执行的动作。

## 产出位置（硬性）

三份产出**统一放在**：

```
doc/local/[local]<feature-name>/
├── 01-impact.md     改动分析      ← 阶段 1
├── 02-risk.md       风险分析      ← 阶段 2
└── 03-test.md       测试策略      ← 阶段 3
```

| 规则 | 说明 |
|---|---|
| **路径** | 仓库根下的 `doc/local/`，不是 `docs/`，也不放在 `doc/` 根目录 |
| **目录名** | `[local]<feature-name>`，**保留方括号前缀** —— 沿用项目已有的"`[local]` = 不提交"约定 |
| **feature-name** | 小写短横线，如 `train-coupling`、`bbmodel-support` |
| **是否提交** | **不提交**。`doc/local/` 已在 `.gitignore` 里 |

**为什么要这个约定**：这三份文档是**过程产物**，不是长期资产——
它们服务于"这一次改动"，改完之后价值主要在 PR 讨论和当事人的记忆里，
长期保留会让 `doc/` 变成垃圾场。

> 如果某份分析值得长期保留（如架构决策），**单独提炼**成正式文档放到 `doc/` 或 `api/` 下，
> 而不是把整个 `doc/local/` 目录提交上去。

**创建**：三份文件都用 `assets/` 下对应的模板复制，改名为固定文件名
（`01-impact.md` / `02-risk.md` / `03-test.md`），不要带功能名——目录名已经表明了。

---

## 三阶段流程

```
原始需求
   ├─[阶段1]→ 改动分析     「要动什么」
   │             ↓
   ├─[阶段2]→ 风险分析报告  「会出什么问题」
   │             ↓
   └─[阶段3]→ 测试策略     「怎么验证没有」
```

**每阶段产出是下一阶段的唯一输入。顺序不可颠倒。**
混着做会让风险评估被改动方案绑架，测试只覆盖已识别风险，改一次设计测试全作废。

---

## 阶段 1 · 改动分析

**输入**：用户的需求原文
**产出**：`doc/local/[local]<feature-name>/01-impact.md`

1. 建 `doc/local/[local]<feature-name>/`，复制 `assets/01-impact-template.md` 为 `01-impact.md`
2. **原样粘贴需求原文**，不许提前抽象。抽象一发生，后面所有推导都脱离真实诉求
3. 填归类四问：玩家可感知？用已有能力还是造新能力？引入新持久化？涉及双端交互？
4. 逐行填 19 行能力盘点表（17–19 项的边界见 `references/decision-rules.md` 1.2b）：
   - **用途说明**：写清"这个能力拿来做什么"，用业务语言。写不出用途却标了「需要」，
     说明这一行判错了 —— 回头重判
   - **是否存在**：查 `references/capability-inventory.md`，**不许凭印象**
5. 汇总改动点清单 `C1..Cn`

**关键机制**：盘点表「是否存在」列的三种答案**机械决定**阶段 2 展开哪类风险：

| 是否存在 | 操作类型 | 阶段 2 侧重 |
|---|---|---|
| 直接可用 | **消费** | 用对了吗 |
| 存在需改 | **扩展/修改** | 会不会改坏现有调用方 |
| 不存在 | **新建** | 设计是否完整 |

这一步直接回答"项目已有这个能力则改动少"：网络同步从
「新建（要问协议设计、版本兼容、重传）」变成「消费（只问 6 个消费维度）」。

---

## 阶段 2 · 风险分析

**输入**：阶段 1 的改动分析
**产出**：`doc/local/[local]<feature-name>/02-risk.md`

1. 复制 `assets/02-risk-template.md` 为 `doc/local/[local]<feature-name>/02-risk.md`
2. 把「需要=是」的能力**机械展开**成维度清单（查 `references/risk-dimensions.md` 的目录）
   —— 漏列一个维度 = 漏判一个风险面
3. 逐维度判定：`有风险` / `无风险` / `待定`
   - `无风险` 必须写**具体理由**（"因为 X 所以不会发生"）。**「不适用」不算理由**
   - `待定` 必须写**什么时候能定**
4. 汇总风险清单，按严重度排序（致命 / 严重 / 一般）
5. **每条风险四选一**：`改设计` / `加测试` / `接受` / `推迟`
   - `接受`、`推迟` **必须写理由**。没有理由的接受等于默认它不存在

**这是整套流程最关键的产出**：风险分析的最高价值是**设计变更**，不是测试清单。

典型情况：一个横跨持久化、双端同步、外部集成的功能，风险清单里往往只有一半能靠测试防住，
**另一半必须改设计**——
它们不是"可能写错"，是"设计上就错了"。只输出测试清单的话，那 9 条一条都防不住。

---

## 阶段 3 · 测试策略

**输入**：阶段 2 的风险清单
**产出**：`doc/local/[local]<feature-name>/03-test.md`

**这份产出的读者有两个**：agent 照它写自动化测试，**人照它进游戏做实测**。两栏都要填。

1. 复制 `assets/03-test-template.md` 为 `doc/local/[local]<feature-name>/03-test.md`
2. 按**改动性质**选层级，不是"越便宜越好" —— 完整的层级表、两条原则、
   以及"怎么判断走哪条"见 `references/decision-rules.md` 第 3.1–3.3 节
3. 填第 2 节「人工实测清单」：每项写清 **环境 / 步骤 / 通过标准 / 失败特征**
4. 填第 3 节自动化方案，四字段齐全：
   - **动作**（哪个测试任务、加什么场景、断言什么值）
   - **层级**：写全名，**不要用 L1–L5 代号**（代号脱离层级表就没人看得懂，而层级表不随产出文件发出去）
   - **为什么是这一层**（选低的说明高的为何不必要；选人工的说明为何代码测不了）
   - **验收标准**（能判定真假：「0 个 NaN/Inf」而非「测试通过」）
5. 两条固定项，任何功能都必须有：**基础可用性 smoke** + **CI 与代码规范**

> **为什么人工实测的优先级要提到并列**：某次字体在十个色号下全渲染成黑色——
> 这类问题**开发环境启动一次游戏就能看出来**，不需要复杂测试。
> 但如果清单里没写「进游戏看十种颜色」，就永远没人看。
> 同理，业务功能"点挂钩能不能分开、分开的样子对不对"，只有人能判断。

---

## 硬性纪律

| 规则 | 原因 |
|---|---|
| **禁止先实现后补分析** | 分析会被实现牵着走，失去意义 |
| **「无风险」必须写具体理由** | 允许沉默跳过，agent 就会跳过 |
| **禁止改写维度库的检查项** | 改写会稀释约束力；觉得不对就改库 |
| **命中未建维度时显式标注「此维度未建」** | 「我没有清单」比「我编一个清单」安全 |
| **禁止为让测试通过而弱化断言** | 假绿比没测更糟 |
| **新增测试必须先失败一次** | 一写就过 = 断言太弱，或它测的东西本来没坏 |
| **判定测试真伪读 JUnit XML** | Gradle 退出码不反映测试失败：测试抛 `NoClassDefFoundError` 时它仍可能打印 `BUILD SUCCESSFUL` |

---

## 回验（实现完成后）

```bash
# 在目标仓库目录内执行（脚本会向上找 git 根）
python3 .agents/skills/feature-impact-analysis/scripts/change_analyze.py --base <基线>

# 或在任意目录用 --repo 指定仓库
python3 <skill路径>/scripts/change_analyze.py --repo <仓库路径> --base <基线>
```

对照阶段 1 的盘点表。**出现预估之外的能力 = 漏判**，必须回填
`references/capability-inventory.md`。

> 这一步是唯一能让"事前分析"可被证伪的机制。没有它，谁都能写一份
> 看起来很周全的风险清单，然后永远没人知道准不准。

---

## 资源

| 文件 | 何时读 |
|---|---|
| `references/decision-rules.md` | **填任一模板前**：三档判定、严重度、应对方式、层级怎么选、自检清单 |
| `references/capability-inventory.md` | 阶段 1 填「是否存在」列时 |
| `references/risk-dimensions.md` | 阶段 2 展开维度时（48 个维度的固定检查项） |
| `assets/01-impact-template.md` | 阶段 1 开始时复制 |
| `assets/02-risk-template.md` | 阶段 2 开始时复制 |
| `assets/03-test-template.md` | 阶段 3 开始时复制 |
| `scripts/change_analyze.py` | 实现完成后回验 |

完整填写示例（三种需求类型，对照看自己的需求属于哪类）：

| 示例 | 类型 | 风险重心 | 人工实测量 |
|---|---|---|---|
| `references/example-01-gameplay.md` | **玩法功能**（可拆分复合结构） | 状态与同步 | 多（10 项） |
| `references/example-02-extension.md` | **能力扩展**（新增模型格式） | 转换语义 | 多（9 项） |
| `references/example-03-infrastructure.md` | **基建**（网络包处理） | 契约完整性 | **少（4 项）** |

---

## 维护

**每次处理完玩家反馈的 bug，回写 `references/risk-dimensions.md`**：
属于现有维度 → 补一行典型失效；不属于 → 新增维度。
这份库的价值随事故数量增长，不随时间衰减。

新增能力（如"配置"从不存在变存在）后，更新 `references/capability-inventory.md`。
