# 数据驱动上手：从空目录到方块动起来

> 类型：Tutorial（学习向，跟着做一遍）。
> 适用版本：Minecraft 1.21.1 · NeoForge 21.1.203 · Parchment 2024.11.17 · Java 21。本页的路径与行为只在这组版本下成立。
> 代码基准：`KasugaLibNext` 分支 `model-loader`（2026-10-04，基准 commit `ec864a1`）。文中行号对应当前工作树（未提交的 data-driven 重实现）。
> 本文只负责把你带起来。字段的完整清单看 [schema.md](schema.md)，Java 类与方法的签名看 [api.md](api.md)，具体任务（加方块、配创造栏、继承）看 [guide-content.md](guide-content.md)，写自定义 TypeHandler 看 [guide-extension.md](guide-extension.md)。

## 1. 数据驱动解决什么问题

数据驱动让你用 JSON 声明「要注册哪些方块、物品、分组」，框架在启动时把它们读进 Minecraft 的注册表，而不是在 Java 里一个个 `register()`。

它不替你做掉 Java 那一半。JSON 说「注册一个 id 为 `mymod:panel` 的方块，类型是 `simple_block`」，`simple_block` 必须先在 Java 里注册成一个工厂；工厂怎么造这个方块，仍由代码决定。

分工因此是这样：有 Java 基础的人搭一次工厂、注册一次创造栏，之后团队里不写 Java 的成员只用 JSON 往里加内容。

本轮的适用范围要先划清：数据包作者用不上这套注册管线。注册期只从 mod jar 读文件，数据包碰不到（原因见下一节）。

## 2. 先记住一件事：两个时机

这是全篇最重要的概念，后面几乎所有规则都由它推出来。

| | 注册期（registration） | reload 期（reload） |
|---|---|---|
| 什么时候跑 | mod 启动、构造期间，一次 | 执行 `/reload` 或资源重载时 |
| 对象去哪 | MC 注册表（如 `BuiltInRegistries.BLOCK`） | reload 桶（不进 MC 注册表） |
| 从哪读 | mod jar | 包栈（pack stack） |
| 能热更吗 | 不能，改完要重启游戏 | 能，`/reload` 就生效 |
| 数据包能覆盖吗 | 不能 | 能 |
| 内容长什么样 | `registry_groups` / `blocks` / `items`（内嵌 `block_entity`） | `state_machines` / `animation_clips` |
| 代码入口 | `JsonTreeBuilder.buildForMod` | `ReloadOrchestrator.reload` |

一句话的记忆法：**要进 MC 注册表的东西属于注册期，不进注册表的东西属于 reload 期。**

一份内容归哪个时机，由它在索引文件里被列进 `on_register` 还是 `on_reload` 决定，与它放在哪个目录无关。

## 3. 三件套

数据驱动的全部输入是三样文件。

索引文件是唯一入口，只回答「要读哪些文件」，自身不含任何对象。位置固定：`data/<namespace>/kasuga_lib/data_driven/<名字>.json`。

内容文件放对象定义。路径相对 `data/<namespace>/`，顶层字段名（`blocks`、`registry_groups`……）决定交给哪个 handler。

reload 期的状态机与动画剪辑文件同样是内容文件，只是被列在 `on_reload`。

「怎么被发现」在两类 reload 内容上不一样，这是最容易踩空的地方。`state_machines/` 目录有 glob，`data/<namespace>/state_machines/*.json` 里的文件即使没被任何索引列出，也会被读；`animation_clips/` 目录没有 glob，没被任何 `on_reload` 列出的 clip 文件永远不会被读。

索引可以是多份。同一 mod 的 `data_driven/` 目录下每个 `.json` 都算索引，按文件名升序读取。

## 4. 动手（一）：注册一个方块

### 4.1 前提：`type` 要先有工厂

JSON 里的 `type` 是一个**工厂注册键**。照抄下面的例子前，先确认这个键在你的环境里存在：

- 库主体内置的方块工厂只有 `fsm_block`（modelling 模块）；
- 本文示例用的 `simple_block`、`simple_item` 这类键由本仓 `contentTesting` 源集注册，是**测试夹具，不随库发布**。

`type` 找不到工厂时，该条目会被打一条 WARN 然后跳过（不进错误桶）。给自己的模组写工厂的方法见 [guide-extension.md](guide-extension.md)。

### 4.2 放三个文件

假设你的命名空间是 `mymod`：

```
src/main/resources/data/mymod/kasuga_lib/data_driven/content.json   ← 索引
src/main/resources/data/mymod/kasuga_lib_content/groups.json        ← 内容：分组
src/main/resources/data/mymod/kasuga_lib_content/blocks.json        ← 内容：方块
```

`kasuga_lib_content/` 只是库内的惯例目录名，不强制；内容文件放在 `data/mymod/` 下任何相对路径都合法。

### 4.3 写索引

`data/mymod/kasuga_lib/data_driven/content.json`：

```json
{
  "on_register": [
    "kasuga_lib_content/groups.json",
    "kasuga_lib_content/blocks.json"
  ]
}
```

只有 `on_register`，因为下面两个文件都在注册期消费。条目路径**相对 `data/mymod/`**，且要带 `.json`（是 `kasuga_lib_content/blocks.json`，不是 `data/mymod/...`，也不是 `blocks`）。

### 4.4 写分组

`data/mymod/kasuga_lib_content/groups.json`：

```json
{
  "registry_groups": [
    {
      "id": "mymod:panels",
      "item_properties": {
        "tab": "mymod:main"
      }
    }
  ]
}
```

分组是「一组方块共享属性的容器」，方块物品进哪个创造栏也挂在这里的 `item_properties.tab`；`tab` 必须是你已经注册过的创造栏 id。`registry_groups` 的字段全表见 [schema.md](schema.md) 的「2.2 `registry_groups` 条目」。

### 4.5 写方块

`data/mymod/kasuga_lib_content/blocks.json`：

```json
{
  "blocks": [
    {
      "id": "mymod:panel",
      "type": "simple_block",
      "registry_group": "mymod:panels",
      "properties": {
        "destroy_time": 2.0
      }
    }
  ]
}
```

`registry_group` 指向 4.4 的分组。不写它方块照样注册，只是不继承分组属性、物品也不会进创造栏。`blocks` 的字段全表见 [schema.md](schema.md) 的「2.3 `blocks` 条目」。

示例里的字段名与形状照抄自本仓夹具 `contentTesting/resources/data/kasuga_lib/kasuga_lib_content/`，只换了命名空间；这类形状能被解析、加载无错，由 `JsonTreeBuilderIndexPathTest.contentTestingIndexLoadsWithoutErrors` 覆盖。

### 4.6 路径规则

三条，违反任一条都会得到一条 error 并跳过该条（其余条目继续）：

1. 索引目录必须正好是 `data/<namespace>/kasuga_lib/data_driven/`，**四段**。`kasuga_lib` 是固定拼写，`data_driven` 用下划线；写成 `kasuga-lib` 或漏掉 `kasuga_lib` 这一层，结果是**静默加载 0 条**。出处：`JsonTreeBuilder.indexDirectorySegments`，由测试 `JsonTreeBuilderIndexPathTest.indexDirectorySegmentsMatchCanonicalLayout` 锁死。
2. 索引条目相对 `data/<namespace>/` 解析，必须以 `.json` 结尾，不能以 `/` 开头，不能含空段或 `.`/`..` 段。出处：`JsonTreeBuilder.validateSourcePath`，测试 `JsonTreeBuilderSourcePathTest`。
3. 条目不能跨命名空间。这由实现保证：加载时路径永远被拼在 `data/<modId>/` 之下（`JsonTreeBuilder.parseSource`）。

## 5. 动手（二）：让方块动起来

注册期只让方块存在。要让它动起来，也就是接上状态机与动画剪辑，就得进 reload 期。这一节用本仓 modelling 模块的真实夹具走一遍，它是这份文档里唯一一个端到端跑通、且有测试覆盖的完整实例。

夹具在 `modules/modelling/src/contentTesting/resources/data/kasuga_lib/`，命名空间是 `kasuga_lib`。

### 5.1 索引（同时声明两个域）

`data/kasuga_lib/kasuga_lib/data_driven/modelling.json`：

```json
{
  "on_register": [
    "kasuga_lib_content/fan_formula_data_driven.json",
    "kasuga_lib_content/fsm_blocks.json"
  ],
  "on_reload": [
    "animation_clips/fan_fsm_data_driven.json"
  ]
}
```

注意状态机文件不在这里，它靠 5.3 的目录 glob 被发现。这正是第 3 节那条 glob 差异的实例。`fsm_blocks.json` 原属 data-driven 的 contentTesting，因 `fsm_block` / `fsm_be` 工厂只在 modelling 而迁到 modelling 侧。

### 5.2 注册期：方块加内嵌方块实体

`kasuga_lib_content/fan_formula_data_driven.json`：

```json
{
  "blocks": [
    {
      "id": "kasuga_lib:test_fan_formula_data_driven",
      "type": "fan_block",
      "block_entity": {
        "type": "fan_be",
        "params": {
          "model": "kasuga_lib:models/be/test_fan_be.bbmodel",
          "state_machine": "kasuga_lib:fan_machine_data_driven"
        }
      }
    }
  ]
}
```

方块通过内嵌的 `block_entity` 绑定一个方块实体，`params` 把模型路径和状态机 id 交给 BE 工厂。`fan_block` / `fan_be` 是 Java 侧 `FanDataDrivenFactories` 注册的工厂。内嵌类型只能写在父对象的键里；写成顶层字段会得到一条带提示的 error。

这个方块没有 `registry_group`，物品不会自动进创造栏，可用 `/give kasuga_lib:test_fan_formula_data_driven` 取得。

### 5.3 reload 期：状态机（目录 glob 发现）

`state_machines/fan_machine_data_driven.json`：

```json
{
  "state_machines": [
    {
      "id": "kasuga_lib:fan_machine_data_driven",
      "state_vars": [
        { "name": "cycle", "type": "bool", "default": false, "ephemeral": true, "reference": "kasuga_lib:fan/cycle" },
        { "name": "kasuga_lib:fan/current_speed", "reference": "kasuga_lib:fan/current_speed" },
        { "name": "kasuga_lib:fan/angle", "reference": "kasuga_lib:fan/angle" }
      ],
      "layers": [
        {
          "id": "gear",
          "mode": "base",
          "initial_state": "off",
          "states": [
            { "id": "off", "clip": { "id": "kasuga_lib:fan_fsm_data_driven", "loop": true } },
            { "id": "g1", "clip": { "id": "kasuga_lib:fan_fsm_data_driven", "loop": true } },
            { "id": "g2", "clip": { "id": "kasuga_lib:fan_fsm_data_driven", "loop": true } },
            { "id": "g3", "clip": { "id": "kasuga_lib:fan_fsm_data_driven", "loop": true } }
          ],
          "transitions": [
            { "id": "off_to_g1", "from": "off", "to": "g1", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
            { "id": "g1_to_g2", "from": "g1", "to": "g2", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
            { "id": "g2_to_g3", "from": "g2", "to": "g3", "trigger_on": "cycle", "cross_fade_seconds": 0.25 },
            { "id": "g3_to_off", "from": "g3", "to": "off", "trigger_on": "cycle", "cross_fade_seconds": 0.25 }
          ]
        }
      ]
    }
  ]
}
```

顶层是 `state_machines` 键，值是定义数组（多余的顶层键不再拒绝文件，见 [fsm.md](../../api/fsm.md) §2.4）。定义元素内部（`state_vars`、`layers`……）属于状态机规范，见 [fsm.md](../../api/fsm.md) 与 [schema.md](schema.md) 的「4.2 `state_machines` wrapper」。

### 5.4 reload 期：动画剪辑（只走索引）

`animation_clips/fan_fsm_data_driven.json`：

```json
{
  "animation_clips": [
    {
      "id": "kasuga_lib:fan_fsm_data_driven",
      "duration_seconds": 12.0,
      "functions": [
        { "bone": "group", "channel": "rotate", "y": "query.angle" },
        { "bone": "fan", "channel": "rotate", "y": "11 * query.angle" },
        { "bone": "cover", "channel": "rotate", "z": "sin(rad(2 * query.angle)) * 30" }
      ]
    }
  ]
}
```

这个文件**必须**出现在 `on_reload` 里（5.1 已列），因为 `animation_clips/` 没有 glob。状态机里 `states[].clip` 引用的 `kasuga_lib:fan_fsm_data_driven` 就是这里的 id。元素格式见 [schema.md](schema.md) 的「4.3 `animation_clips` wrapper」。

### 5.5 热更

改 5.3 或 5.4 的文件，执行 `/reload`，新内容立即生效。改 5.2 的方块（注册期）则要重启游戏。第 2 节那张表在实操里就是这个样子。

## 6. 怎么知道跑通了

### 6.1 看日志

注册期成功会打一行 INFO（每个 mod 一次），格式是：

```
Loaded N JSON entries across M types for mod '<mod id>'
```

本仓 contentTesting 夹具的真实输出（出处：`modules/data-driven/build/test-results/test/TEST-lib.kasuga.test.registration.data_driven.JsonTreeBuilderIndexPathTest.xml` 的 `system-out`）：

```
Loaded 23 JSON entries across 4 types for mod 'kasuga_lib'
```

每个被应用的顶层类型另有一行 DEBUG 级 `Applying ...`。下游 KuaYue 运行时的真实实例：

```
Loaded 174 JSON entries across 2 types for mod 'kuayue'
Applying 8 entries for type 'registry_groups'
Applying 166 entries for type 'blocks'
```

`N` 是 0、或者根本没有这一行，说明索引没被找到、或没声明出任何条目。

reload 期每次 `/reload` 打的是逐条 INFO。真实输出（出处：`modules/modelling/build/test-results/test/TEST-lib.kasuga.rendering.models.mc.dynamic.fsm.FanDataDrivenReloadTest.xml` 的 `system-out`）：

```
Loaded state machine definition 'kasuga_lib:fan_machine_data_driven' from 'data/kasuga_lib/state_machines/fan_machine_data_driven.json' (reload domain, mod 'kasuga_lib')
Loaded animation clip 'kasuga_lib:fan_fsm_data_driven' from 'data/kasuga_lib/animation_clips/fan_fsm_data_driven.json' (reload domain, mod 'kasuga_lib')
```

### 6.2 用命令查

游戏内（需要权限等级 2）：

- `/kasuga_data errors`：每个有错误的桶一行——mod 维形如 `<mod>: N error(s)`，reload 维形如 `RELOAD_DATA[<命名空间>]: N error(s)`；全干净时输出 `Kasuga data: no loading errors recorded`。
- `/kasuga_data errors <mod>`：该 mod 的两维逐条错误——注册期形如 `[<mod>] <错误>`，reload 期形如 `[RELOAD_DATA[<命名空间>]] <错误>`。

比翻日志快，且覆盖注册期与 reload 期两边的桶。出处：`DataDiagnosticsCommands`，测试 `DataDiagnosticsCommandsTest`。

## 7. 新手最常踩的三个坑

### 7.1 写了 `sources`

`sources` 是旧字段，现在是**非法字段**。写成：

```json
{ "sources": ["kasuga_lib_content/blocks.json"] }
```

会同时拿到两条 error：`contains unsupported field 'sources'`，加上 `declares neither 'on_register' nor 'on_reload'`，结果一条都不加载。改法就是把 `sources` 换成 `on_register`（reload 域内容换成 `on_reload`）。

### 7.2 索引列了，内容文件路径不对

条目是相对 `data/<namespace>/`，不是相对索引目录、也不是资源根。常见的两种错写法：多写了 `data/mymod/` 前缀，或漏了 `.json` 后缀。前者报 `Source file not found for mod '<mod>': data/<mod>/<path>`，后者报路径非法；两种都只跳过该条，其余条目照常。

### 7.3 `on_register` 里列了 reload 域的内容

把状态机文件（顶层是 `state_machines`）列进 `on_register`，注册期不认识这个顶层字段，会报 `unsupported top-level field 'state_machines'`，并在消息尾部提示 `list this file under 'on_reload' instead`。同一个文件里其它注册字段仍会正常处理。反方向（`on_reload` 里列了 `blocks`）有对称的提示，指回 `on_register`。

## 8. 下一步读什么

- [schema.md](schema.md)：JSON 字段、类型、必填、默认与规则出处的完整规范。写内容时当字典查。
- [api.md](api.md)：`JsonTreeBuilder`、`Diagnostics`、`ReloadOrchestrator`、`ReloadHandler` 等类的签名与行为契约。
- [guide-content.md](guide-content.md)：具体任务的分步做法（加方块、配创造栏、属性继承……）。
- [guide-extension.md](guide-extension.md)：Java 侧扩展。注册自己的工厂与 TypeHandler，把团队私有内容接进数据驱动。
- [fsm.md](../../api/fsm.md)：状态机定义与动画片段元素的格式。
