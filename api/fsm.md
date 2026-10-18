# 状态机与动画剪辑 JSON 参考

> 类型：Reference（信息向，供查阅）。
> 适用版本：Minecraft 1.21.1、NeoForge 21.1.203、Parchment 2024.11.17、Java 21。本页字段只在该版本区间内成立。
> 行号仅作定位提示，可能随源码变化；以类名、方法名和测试名为准。
> 权威来源：元素字段来自 codec（`StateMachineDefinition.CODEC`、`AnimationClip.CODEC` 及其子 codec）；wrapper 与发现规则来自 `StateMachineDefinitionLoader` / `AnimationClipLoader` / `ReloadOrchestrator` / `FsmReloadHandler` / `FsmClipsReloadHandler`。每条规则给出处；本页不沿用重写前的旧结论。
> 边界：本页只讲**状态机定义与动画剪辑的元素格式、文件 wrapper、reload 编排**。索引文件与注册期内容文件见 [`data-driven/schema.md`](data-driven/schema.md)；Java API 签名见 [`data-driven/api.md`](data-driven/api.md)；内容组织实践见 [`data-driven/guide-content.md`](data-driven/guide-content.md)。

## 0. 两个文件、三种读者

动画状态机（FSM，finite state machine）是 Mecanim 风格的分层状态机：多个 layer（状态层）并行运行，每 tick 合成各 layer 的 pose 写进模型。FSM 与它播放的动画剪辑（animation clip，一段按时间轴采样的骨骼 / morph / 材质帧轨道）都以 JSON 定义。

reload 期（`/reload` 触发的资源重读）只有**一个编排者**：`ReloadOrchestrator`（data-driven 模块，包 `lib.kasuga.registration.data_driven.reload`）。它读**包栈**（pack stack，而非 jar），所以数据包可以覆盖这些文件。类型不是它硬编码的：每个已注册 `ReloadHandler` 的 `typeName()` 是一个合法顶层键；modelling 侧注册了两个 handler，对应两个顶层键：

| 顶层键 | handler（modelling） | 转调的解码器 | 内容 |
|--------|-------------------|--------------|------|
| `state_machines` | `FsmReloadHandler` | `StateMachineDefinitionLoader.decodeFile` | 状态机定义 |
| `animation_clips` | `FsmClipsReloadHandler` | `AnimationClipLoader.decodeFile` | 动画剪辑元素 |

两个解码器（`StateMachineDefinitionLoader` / `AnimationClipLoader`）都是**纯文件层组件**，留在 modelling，不再各自持有 reload 监听器或清桶逻辑。两个 `ReloadHandler` 是围绕它们的一层：认领顶层键、声明 glob 目录、写桶、跑收尾校验，由 `FsmReloadHandlerRegistrar` 在 context 启动时按 **fsm → clips** 顺序登记进 `ReloadHandlerRegistry`。reload 周期（清桶 / 发现 / 注册 / 复核）全部由 `ReloadOrchestrator` 拥有，编排器本身不认识 `state_machines` 或 `animation_clips` 任何一个名字。

## 1. 文件位置与发现方式

| 内容 | 位置 | 发现方式 |
|------|------|----------|
| 状态机 | `data/<namespace>/state_machines/<path>.json`，**或**任意被 `on_reload` 列出的路径 | **目录 glob + 索引**双入口 |
| 动画剪辑 | 任意被 `on_reload` 列出的路径（惯例放 `animation_clips/`） | **仅索引** |

- `state_machines/` 有目录 glob：`FsmReloadHandler.globDirectories()` 声明 `StateMachineDefinitionLoader.PATH = "state_machines"`（`FsmReloadHandler.java:69-71`），由 `ReloadOrchestrator.collectGlob`（`:348-367`）用 `ResourceManager.listResources` 按资源位置排序列举该命名空间的 `.json`。**即使文件没被任何索引列出也会被读**。
- `animation_clips/` **没有目录 glob**（`AnimationClipLoader.java:29`；`FsmClipsReloadHandler` 保持 `globDirectories()` 默认空集）。一个没被索引列出的剪辑文件**永远不被读**。测试：`AnimationClipReloadTest.animationClipsDirectoryIsNotGlobDiscovered`。
- 索引列出的 `state_machines/` 文件会被 glob 入口跳过，保证只读一次（`collectGlob`，`ReloadOrchestrator.java:348-367`；测试 `ReloadOrchestratorTest.globAndIndexListingTheSameFileReadItOnce`）。

> 文件路径与定义 `id` **不需要一致**：`id` 由元素自身的 `id` 字段决定（见 §5.1、§6.1）。裸路径（不含冒号）按 `Id.parse` 补默认命名空间 `minecraft`（`Id.java:49-71`）。

## 2. `state_machines` 文件 wrapper

### 2.1 顶层键 `state_machines`

每个 `state_machines` 文件是一个 JSON 对象，其中 `state_machines` 键的值必须是数组：

```json
{ "state_machines": [ <definition>, ... ] }
```

规则（`StateMachineDefinitionLoader.decodeFile`，`:67-100`）：

- body 必须是 JSON 对象；
- 必须有顶层键 `state_machines`（常量 `FIELD_STATE_MACHINES = "state_machines"`，`:42`）；
- 该键的值必须是数组。

**多顶层键合法**：文件可以同时携带其它顶层键（如 `animation_clips`），本解码器只读自己的键、忽略其余，不再因此拒绝文件。其中不被任何 reload 类型认领的键（真未知键）由编排器报一条 `unsupported top-level field`，但**不影响** `state_machines` 的贡献（见 §2.4）。

三种违规——非对象 body、缺键、值非数组——**拒绝整个文件**，返回一条 error，不注册任何定义。逐条文案见 `decodeFile`（`:67-100`）；缺键诊断会把期望形状 `{"state_machines": [ <definition>, ... ]}` 原样引用出来（`EXPECTED_SHAPE`，`:45`）。

### 2.2 合法示例

下面是一个当前 loader 可解析的完整文件（`id = example:lamp`，两个状态、一条触发器转移 + 一条守卫转移）：

```json
{
  "state_machines": [
    {
      "id": "example:lamp",
      "state_vars": [
        { "name": "lit", "type": "bool", "default": false, "ephemeral": true }
      ],
      "layers": [
        {
          "id": "main",
          "mode": "base",
          "initial_state": "off",
          "states": [
            { "id": "off" },
            { "id": "on", "pose": { "morphs": { "glow": 1.0 } } }
          ],
          "transitions": [
            { "id": "off_to_on", "from": "off", "to": "on", "trigger_on": "lit", "cross_fade_seconds": 0.2 },
            { "id": "on_to_off", "from": "on", "to": "off", "when": ["example:not_lit"], "cross_fade_seconds": 0.2 }
          ]
        }
      ]
    }
  ]
}
```

`trigger_on: "lit"` 指向本机 `state_vars` 里声明的布尔变量名；`when: ["example:not_lit"]` 指向代码侧注册的守卫函数。两者未注册只会在构建机器时警告，不影响文件解码（见 §5.7）。

### 2.3 旧的无 wrapper 形状会被拒绝（破坏性变更）

**旧格式把定义直接放在顶层**（没有 `state_machines` 包裹）。这是当前实现的 legacy 形状，**整文件被拒**：

```json
{
  "id": "example:lamp",
  "state_vars": [ { "name": "lit", "type": "bool" } ],
  "layers": [ { "id": "main", "initial_state": "off" } ]
}
```

照这份写会得到 `missing top-level key 'state_machines'`，**0 条注册**。重写前的 `fsm.md` 给出的正是这种格式，二者直接相反。

可执行证据：

- `StateMachineDefinitionLoaderTest.legacyShapeIsRejectedAndReportsExpectedShape`（`:77-83`）：断言解码结果为空定义列表，且 error 文本包含 `state_machines`；
- `ReloadOrchestratorTest.legacyShapeRegistersNothing`（`:244-247`）：走完整 reload 周期后该 id 为 `null`。

### 2.4 多余顶层键不再拒绝文件

```json
{
  "state_machines": [
    { "id": "example:lamp", "layers": [ { "id": "main", "initial_state": "off" } ] }
  ],
  "extra": 1
}
```

`extra` 不被任何 reload 类型认领，编排器对它报一条 `unsupported top-level field 'extra'`（并附反向 D9 提示）；但 `state_machines` 照常解码、照常注册。判定不再依赖「顶层键数量」，而是逐个未知键检查（`ReloadOrchestrator.dispatch`；`ContentStructure.unknownKeys`）。测试 `StateMachineDefinitionLoaderTest.extraTopLevelKeyDoesNotAffectThisType`（`:96-102`）、`ReloadOrchestratorTest.extraTopLevelKeyIsReportedButDoesNotRejectTheFile`（`:265-275`）。

> 早先版本在 `decodeFile` 里检查 `root.size() != 1`，多一个键就整文件拒绝；该检查已删除。

## 3. `animation_clips` 文件 wrapper

形状对称，认领的顶层键为 `animation_clips`（常量 `AnimationClipLoader.FIELD_ANIMATION_CLIPS = "animation_clips"`，`:45`）：

```json
{ "animation_clips": [ <clip>, ... ] }
```

完全相同的三种文件级违规、同样的整文件拒绝语义（`AnimationClipLoader.decodeFile`，`:69-102`）。测试 `AnimationClipLoaderTest`：`rejectsANonObjectBody` / `rejectsAMissingTopLevelKey` / `ignoresAnExtraTopLevelKey` / `rejectsANonArrayValue`。

多顶层键合法：一个文件**可以同时**声明 `state_machines` 和 `animation_clips`，两个 handler 各自认领自己的键、各贡献各的条目，互不影响（`ReloadOrchestrator.dispatch`，`:429-470`；测试 `AnimationClipReloadTest.mixedTopLevelKeysAreConsumedByBothHandlers`）。真未知键（不被任何 handler 认领）才报 `unsupported top-level field`，且不阻断已知键。

## 4. 文件级严格 vs 元素级容错

两个解码器共享同一套失败隔离，边界是**文件**与**元素**：

| 层级 | 违规 | 后果 |
|------|------|------|
| 文件级 | 非对象 body / 缺顶层键 / 值非数组 | **整个文件拒绝**，一条 error，0 条注册 |
| 文件级 | 顶层有真未知键（不被任何 handler 认领） | **报一条 `unsupported top-level field`**，已知键照常贡献（半应用） |
| 元素级 | 数组元素不是对象 | **只跳过该元素**，报 `key[i] must be an object ...; element skipped` |
| 元素级 | 元素是对象但 codec 解码失败（缺必填字段 / 类型不对 / 枚举 token 未知） | **只跳过该元素**，报 `key[i] failed to decode ...; element skipped`，兄弟照常 |
| 元素级 | 元素对象里有 codec **没命名**的键 | **不报错，被忽略** |

- 分界由 `decodeFile` 的两段逻辑实现：形状检查先做、整文件返回；通过后才逐个 `CODEC.parse(...).resultOrPartial(...)`（`StateMachineDefinitionLoader.java:88-98`；`AnimationClipLoader.java:90-100`）。
- 元素级容错的测试：`StateMachineDefinitionLoaderTest.badElementIsSkippedWhileSiblingsDecode`（`:85-94`，数组里夹一个数字和一个缺 `layers` 的对象，两个好元素仍解码）、`AnimationClipLoaderTest.skipsAMalformedElementAndKeepsItsSiblings`（`:104-114`）与 `skipsAnElementWithoutAnId`（`:117-126`）。
- 元素内未知键被忽略：`AnimationClipLoaderTest.ignoresKeysTheClipRecordDoesNotName`（`:132-144`）。**这是元素层与文件层的最大差异**——文件顶层的真未知键会被报告（但文内已知键照常生效），元素对象里多一个键则完全无声忽略。状态机元素同理（`StateMachineDefinition.CODEC` 也是 `RecordCodecBuilder`），但该点目前只有剪辑侧有专项测试。
- 空数组合法：`{"animation_clips": []}` 干净通过（`AnimationClipLoaderTest.emptyClipArrayIsValid`，`:53-59`）。

## 5. `state_machines` 元素字段

元素 schema 由 `StateMachineDefinition.CODEC` 决定（`StateMachineDefinition.java:21-25`），**与 wrapper 无关**——wrapper 只存在于文件层，内联 / 脚本注册、内容哈希、程序化注册都不受影响（`StateMachineDefinitionLoader` 类 Javadoc `:14-33`）。

### 5.1 定义顶层

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string（`namespace:path`） | **是** | — | 机器标识，方块 / BE 用它绑定；由 `Id.CODEC` 解析（`Id.java:112`） |
| `state_vars` | 数组 | 否 | `[]` | 类型化变量声明 |
| `layers` | 数组 | **是** | — | 并行状态层 |

出处：`StateMachineDefinition.CODEC`（`StateMachineDefinition.java:21-25`）。`id` 缺失或非法 → 该元素解码失败、被跳过。

### 5.2 `state_vars[]`

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `name` | string | **是** | — | 本机内引用名；`trigger_on` 按它解析（先查本机声明，再查注册表） |
| `type` | string | 否 | `"float"` | 内置类型 token，见下 |
| `default` | 任意 JSON | 否 | 该类型的零值 | 与 `type` 的 codec 匹配的默认值 |
| `reference` | string | 否 | — | `namespace:path`，引用已注册变量；给了它则忽略 `type` / `default` |
| `ephemeral` | bool | 否 | `false` | `true` = tick 末自动清除，用作触发器 |
| `external_writable` | bool | 否 | `true` | `false` = 派生参数，仅机器内部可写 |
| `sync` | bool | 否 | `false` | `true` = 走 FSM 同步通道（服务端权威） |

出处：`StateVarDefinition.CODEC`（`StateVarDefinition.java:51-59`）；字段语义见该类 Javadoc（`:12-27`）。

内置 `type` token 目录（`StateVarType`，`StateVarType.java:24-28`）：`bool` / `int` / `float` / `string` / `vec3`。注意三点：

- **`type` 与 `default` 是构建期才解析的**：`StateVarDefinition.CODEC` 把它们当字符串 / 原始 JSON 存下来，`type` 未知或 `default` 解错**不会拒绝文件**。解析在 `DefinitionStateMachineFactory.resolveInline`（`:124-138`）：未知 `type` 会由 `StateVarType.byToken` 抛异常；`default` 解码失败只 WARN 并回落到该类型零值（`decodeOrDefault`，`:140-146`）。
- `reference` 模式解析见 `resolveReference`（`:112-121`）：引用的 id 不存在 → WARN，该变量不生效。
- 重写前的文档把 `resource` 也列为内置类型。当前 `StateVarType` 目录里**没有** `resource`，全仓也搜不到 `StateVarType.register(...)` 调用；`resource` 目前只在 scripting 的 Javadoc 里被称作内置（`AnimatorBuilderApi.java:192`）。见附录 B「待确认」。

### 5.3 `layers[]`

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | **是** | — | 层标识 |
| `mode` | string | 否 | `"base"` | `base`（底层）/ `additive`（累加）/ `override`（覆盖） |
| `weight` | float | 否 | `1.0` | 该层权重 |
| `bone_mask` | string | 否 | 全部 | 骨骼掩码（只影响指定骨骼组）；解析见 `LayerDefinition.resolvedMask` |
| `states` | 数组 | 否 | `[]` | 状态节点 |
| `transitions` | 数组 | 否 | `[]` | 转移边 |
| `initial_state` | string | **是** | — | 初始状态 id |

出处：`LayerDefinition.CODEC`（`LayerDefinition.java:24-32`）。`mode` 由 `BlendMode.CODEC` 解析（`BlendMode.java:18-23`），**未知值直接报 decode error**，导致该定义元素整条被跳过。

层合成语义（`BlendMode` Javadoc，`BlendMode.java:10-17`）：`base` 是底层、其 pose 即基准；`additive` 在基准上累加；`override` 对掩码选中的通道做覆盖。

> 这里的 `BlendMode`（层合成：base / additive / override）与 `MorphInstance.BlendMode`（单 morph 的颜色乘加）是两条不同的轴（`BlendMode.java:6-9`）。

### 5.4 `states[]`

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | **是** | — | 状态标识 |
| `duration_ticks` | int | 否 | 无 | 状态持续 tick 数；配 `when_complete` 转移自动结束 |
| `pose` | object | 否 | 空 pose | 该状态对模型施加的静态 pose（见 §5.6） |
| `on_enter` / `on_exit` / `on_update` | id[] | 否 | `[]` | 动作函数 id（见 §5.7） |
| `clip` | string 或 object | 否 | 无 | 动画剪辑引用（见 §5.8） |

出处：`StateDefinition.CODEC`（`StateDefinition.java:25-33`）。

### 5.5 `transitions[]`

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string | **是** | — | 转移标识 |
| `from` / `to` | string | **是** | — | 起 / 止状态 id |
| `trigger_on` | string | 否 | 无 | 触发器：指向布尔变量名 / id，置位时触发 |
| `when_complete` | bool | 否 | `false` | `from` 状态到 `duration_ticks` 时触发 |
| `cross_fade_seconds` | float | 否 | `0` | 淡入淡出秒数；0 = 立即切换 |
| `when` | id[] | 否 | `[]` | 守卫函数 id，全真才允许转移 |
| `on_fire` | id[] | 否 | `[]` | 转移触发时执行的动作函数 id |

出处：`TransitionDefinition.CODEC`（`TransitionDefinition.java:25-34`）。`trigger_on` 要求变量是布尔类型；非 `ephemeral` 的变量只 WARN 并按 tick 语义处理（`DefinitionStateMachineFactory.resolveTrigger`，`:231-248`）。`from` / `to` 指向不存在的状态时，该转移在构建期被跳过（`buildLayer`，`:175-180`）。

### 5.6 `pose`

`pose` 是状态对模型施加的通用静态姿态：morph 权重、骨骼变换、材质帧与 IK 开关。
同一 Pose 也可通过 [ModelPosing](model-posing.md) 使用，公共运行时不要求目标模型来自某种文件格式。

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `morphs` | object（string→float） | 否 | `{}` | morph 名 → 权重 |
| `bones` | 数组 | 否 | `[]` | 骨骼变换列表 |
| `frames` | 数组 | 否 | `[]` | 材质帧列表 |
| `ik_enabled` | object（string→bool） | 否 | `{}` | 通用 IK 链名到启用状态，见 [Pose IK 通道](model-posing.md#pose-的-ik-通道) |

出处：`PoseDefinition.CODEC`。IK 通道遵循相同的姿态混合与写入所有权规则，停止驱动或切换到缺少该通道的姿态后恢复默认启用状态。

`bones[]`（`PoseDefinition.BoneDefinition.CODEC`，`:31-35`）：

| 字段 | 类型 | 必填 | 默认 |
|------|------|------|------|
| `name` | string | **是** | — |
| `transform` | object | **是** | — |
| `mode` | string | 否 | `"replace"`（`replace` / `add` / `multiply`；未知值回落到 `replace`，`ApplyMode.byName`，`ApplyMode.java:28-38`） |

`transform`（`TransformDefinition.CODEC`，`TransformDefinition.java:26-30`）：`translate` / `rotate` / `scale` 都是**恰好 3 个 float** 的数组（`VEC3F_CODEC`，`:19-22`），缺省分别 `[0,0,0]` / `[0,0,0]` / `[1,1,1]`；`rotate` 单位为**度**。

`frames[]`（`PoseDefinition.FrameDefinition.CODEC`，`:42-45`）：`material`（string，必填）、`frame`（int，必填）。

### 5.7 行为函数引用

`when` / `on_enter` / `on_exit` / `on_update` / `on_fire` 里的 id 由**代码侧**（Java 或脚本）用 `FsmFunctionLibrary` 注册：守卫用 `registerCondition`，动作用 `registerAction`（`FsmFunctionLibrary.java:19-25`）。JSON 只存 id。

未注册的函数**不影响文件解码**：构建机器时统一汇总成一条 WARN，缺失条件降级为 `false`、缺失动作是 no-op（`DefinitionStateMachineFactory.validateDefinition` Javadoc，`:321-326`）。

### 5.8 `clip`

`states[].clip` 引用一个已注册的动画剪辑，有两种写法（`StateDefinition.ClipDefinition.CODEC`，`StateDefinition.java:47-50`）：

- 对象：`{ "id": "ns:clip", "loop": true }`，`loop` 默认 `false`（`:40-45`）；
- 字符串简写：`"ns:clip"`，等价于 `loop: false`。

引用解析与悬空处理见 §9。真实夹具 `fan_machine_data_driven.json` 的四个状态都用对象形式引同一个剪辑并置 `loop: true`。

## 6. `animation_clips` 元素字段

元素 schema 由 `AnimationClip.CODEC` 决定（`AnimationClip.java:47-54`）。元素**自带 `id`**（就是 `states[].clip` 引用的那个 id），loader 不额外抽取 id（`AnimationClipLoader` 类 Javadoc `:20-26`）。

### 6.1 剪辑顶层

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `id` | string（`namespace:path`） | **是** | — | 剪辑标识 |
| `duration_seconds` | float | 否 | `1.0` | 剪辑时长（秒） |
| `bones` | 数组 | 否 | `[]` | 骨骼关键帧轨道 |
| `morphs` | 数组 | 否 | `[]` | morph 值轨道 |
| `frames` | 数组 | 否 | `[]` | 材质帧轨道 |
| `functions` | 数组 | 否 | `[]` | 公式轨道（每帧求值） |

### 6.2 `bones[]`

| 字段 | 类型 | 必填 | 默认 |
|------|------|------|------|
| `bone` | string | **是** | — |
| `keyframes` | 数组 | **是** | — |

`keyframes[]`（`Keyframe.CODEC`，`AnimationClip.java:71-75`）：`time`（float，必填）、`transform`（`TransformDefinition`，必填，字段同 §5.6）、`easing`（string，选填，默认 `"linear"`）。

### 6.3 `morphs[]`

`morph`（string，必填）+ `keyframes`（必填）；关键帧为 `{ "time": float, "value": float, "easing": string? }`（`MorphKeyframe.CODEC`，`:93-97`）。

### 6.4 `frames[]`

`material`（string，必填）+ `keyframes`（必填）；关键帧为 `{ "time": float, "frame": int }`（`FrameKeyframe.CODEC`，`:111-114`）。材质帧轨道按最近关键帧取整，不做插值（`FrameTrack` Javadoc，`:100`）。

### 6.5 `functions[]`

| 字段 | 类型 | 必填 | 默认 | 含义 |
|------|------|------|------|------|
| `bone` | string | **是** | — | 目标骨骼 |
| `channel` | string | **是** | — | `rotate` / `translate` / `scale`；未知值回落到 `rotate`（`CHANNEL_CODEC`，`:133-145`） |
| `x` / `y` / `z` | string | 否 | `""` | 各轴公式表达式；空串 = 该轴保持单位值（rotate 0 / translate 0 / scale 1） |

出处：`FunctionTrack.CODEC`（`AnimationClip.java:147-153`）；表达式在运行期按 `lib.kasuga.formula` 命名空间逐帧求值。真实夹具（`fan_fsm_data_driven.json`）就是这样三条公式轨道：

```json
{
  "animation_clips": [
    {
      "id": "example:fan",
      "duration_seconds": 12.0,
      "functions": [
        { "bone": "group", "channel": "rotate", "y": "query.angle" }
      ]
    }
  ]
}
```

> 编码时，取值等于默认值的字段会被省略。真实夹具 `fan_fsm_data_driven.json` 里三条公式轨道的空轴（`x` / `z`）不写出来，正是 `AnimationClip.CODEC` 的规范编码形态；`FanDataDrivenClipTest.clipFileIsInCanonicalEncodedShape` 锁定该文件逐字等于 codec 的编码结果。

### 6.6 easing 名称

`easing` 由字符串映射到内置缓动（`EASING_CODEC`，`AnimationClip.java:39-45`）。**未知名称不报错，回落到 `linear`**。可用名称是 `Easing.NAMED`（`Easing.java:232-246`）：

`linear`、`ease_in_quad`、`ease_out_quad`、`ease_in_out_quad`、`ease_in_cubic`、`ease_out_cubic`、`ease_in_out_cubic`、`ease_in_sine`、`ease_out_sine`、`ease_in_out_sine`、`back`、`elastic`、`bounce`。

### 6.7 元素内未知键被忽略

`AnimationClip` 记录**没有命名**的键被 codec 忽略，作者可以加注释类字段而不破坏加载（`AnimationClipLoader` 类 Javadoc `:24-26`；测试 `AnimationClipLoaderTest.ignoresKeysTheClipRecordDoesNotName`，`:132-144`）。

## 7. 两条发现路径的差异

| | `state_machines` | `animation_clips` |
|--|------------------|-------------------|
| 目录 glob | 有（`data/<ns>/state_machines/*.json`） | **无** |
| 索引 `on_reload` 列出 | 支持 | **唯一入口** |
| 未列索引时 | 仍会被 glob 读到 | **永远不读** |
| 两类入口同时列出同一文件 | glob 入口跳过，读一次 | 不适用 |

出处：`FsmReloadHandler.globDirectories()`（`FsmReloadHandler.java:69-71`）声明 `StateMachineDefinitionLoader.PATH`（`StateMachineDefinitionLoader.java:39`）；glob 列举在 `ReloadOrchestrator.collectGlob`（`:348-367`）；`AnimationClipLoader` 类 Javadoc（`:29`）；`ReloadOrchestrator` 类 Javadoc（`:77-91`）。测试 `AnimationClipReloadTest.animationClipsDirectoryIsNotGlobDiscovered`、`ReloadOrchestratorTest.globAndIndexListingTheSameFileReadItOnce`。

> glob 只管**发现**，**路由**永远按文件的顶层键走。一个落在 `state_machines/` 目录、顶上却写 `animation_clips` 的文件，仍由剪辑 handler 处理（`ReloadOrchestrator.collectGlob` Javadoc，`:340-347`）。

## 8. 编排者 `ReloadOrchestrator` 与两个 handler

### 8.1 `reload()` 的 stage 顺序

`reload(ResourceManager)`（`:186-193`）包住私有 `runCycle`（`:198-248`），一轮顺序固定、类型无关（遍历注册表，而非硬编码两个类型）：

1. **构周期**：对 `ReloadHandlerRegistry.all()` 里每个 handler 各建一个 `HandlerCycle`（`:199-201`）。
2. **清桶**：逐 handler 调 `runClear(entry)` → `clearBucket()`，**一轮恰好一次**（`runClear`，`:250-261`；调用点 `:213-215`）。`clearBucket()` 抛异常会被 `runClear` 就地捕获、记入 `Domain.RELOAD_DATA` 诊断，其余 handler 照常清桶（per-handler 守卫，与收尾阶段同款）。`FsmReloadHandler` 只清 `FsmDefinitions` 的 RESOURCE 半区、`FsmClipsReloadHandler` 只清 `FsmAnimationClips` 的 RESOURCE 半区（script wins，见 §10）。
3. **聚合 glob 目录**：收齐每个 handler 的 `globDirectories()`（`:222-225`）；`state_machines` 贡献 `state_machines/`，clips 贡献空。
4. **列命名空间**并排序，**每命名空间双路发现**（`collectNamespace`，`:266-288`）：先 `readReloadPaths`（`:307`）读索引清单的 `on_reload`，再 `collectGlob`（`:348-367`）扫聚合 glob 目录（跳过已列路径），最后 `collectIndex`（`:369`）读 `on_reload` 指向的文件。
5. **注册**：逐 handler 调 `register(entry)`（`:507-526`），glob 候选在前、索引候选在后（`HandlerCycle.candidates()`，`:595-599`）→ 去重并写桶。
6. **收尾复核**：逐 handler 调 `runAfterReload(entry)`（`:530-538`），只对本轮实际注册的条目、且在所有 register 之后。`FsmReloadHandler.afterReload` 在此跑悬空 clip 检查。

索引侧复用注册域的 `JsonTreeBuilder.parseIndexManifest` / `resolveIndexManifests`，因此 D6 的「同一路径双列 → 两域都不消费」在 reload 侧同样生效（`readReloadPaths`，`:307`）。

### 8.2 类型由注册表驱动，不硬编码

编排器不知道任何 reload 类型名：顶层键集合由 `knownTypeNames()`（`:473-486`）从 `ReloadHandlerRegistry.all()` 派生，分派（`dispatch`，`:429-470`）按每个 handler 的 `typeName()` 路由。新增一种 reload 类型 = 实现一个 `ReloadHandler` 并在 `@Context` bean 里 `ReloadHandlerRegistry.register`，**不改编排器**。这就是 §4.6 所说的「reload 侧与注册期对称」；两个 FSM 类型由 `FsmReloadHandlerRegistrar`（`:23-31`）按 **fsm → clips** 顺序登记，顺序决定 dispatch/注册顺序，进而决定跨类型 last-wins。

要理解一个 handler 的具体职责分工，看 §8.5。

### 8.3 last-wins 与「败者无副作用」

跨两个入口按加载序列取最后一个同 id 候选为胜者（glob 在前、索引在后；文件内按数组书写顺序）。裁决用共享的 `DuplicateIdResolver`，**败者根本不进 handler 的 `register`**，因此不会触发覆盖通知；每个冲突记 WARN + 一条桶条目（`resolveAndReport`，`:545-556`）。测试：`ReloadOrchestratorTest.indexEntryWinsOverTheGlobEntryAndTheLoserIsNotRegistered`、`duplicateIdWithinFileLastWins`；剪辑侧 `AnimationClipReloadTest.laterClipFileWinsForTheSameId`。

### 8.4 四层异常守卫 + 兜底

`reload()` 在 reload 监听器路径上，抛异常会中止整包重载、且桶已半清空，所以任何失败都必须就地转成「日志 + 桶」诊断：

| 层 | 位置 | 计费对象 |
|----|------|----------|
| 逐 handler（清桶） | `runClear` `:250-261` | 库 mod（`KasugaLib.MODID`） |
| 逐文件（读 / 解码） | `readAndDispatch` `:405-415` | 文件所在命名空间 |
| 逐命名空间（发现机制） | `collectNamespace` `:266-288` | 该命名空间 |
| 逐条目（注册） | `register` `:507-526` | 条目所在命名空间 |
| 逐 handler（收尾复核） | `runAfterReload` `:530-538` | 库 mod（无更细归属时） |
| 兜底（无归属可计） | `reload()` `:186-193` | 库 mod（`KasugaLib.MODID`） |

测试 `ReloadOrchestratorExceptionBoundaryTest`：`uppercaseIndexPathIsRejectedAndTheCycleContinues`、`throwingFileIsIsolatedAndTheNextFileStillLoads`、`wholeCycleFailureIsCaughtAndRecordedAgainstTheLibraryMod`。整轮全部失败时桶保持空——空是真实结果，错误留在桶里（类 Javadoc `:106-114`）。

### 8.5 两个 handler 的职责分工

| | `FsmReloadHandler` | `FsmClipsReloadHandler` |
|--|--------------------|-------------------------|
| `typeName()` | `state_machines` | `animation_clips` |
| `globDirectories()` | `{state_machines}` | 默认空 = index-only |
| `describeFile()` | `"state machine file"` | `"animation clip file"` |
| `clearBucket()` | `definitions.clearResource()` | `clips.clearResource()` |
| `decode()` | 转调 `StateMachineDefinitionLoader.decodeFile` | 转调 `AnimationClipLoader.decodeFile` |
| `register()` | `definitions.registerResource(id, def)` | `AnimationClipLoader.register(clips, clip)` |
| `afterReload()` | 跑悬空 clip 检查（见 §9） | 未覆写（默认空） |

### 8.6 诊断走域/源键维（`Domain.RELOAD_DATA`）

`ReloadOrchestrator.reportError`（`:566-573`）与两个 handler 的 `reportError` / `reportWarning` 都调 `Diagnostics.report(Diagnostics.Domain.RELOAD_DATA, namespace, ...)`，`namespace` = 出错文件所在的命名空间。reload 期的诊断走**域/源键维**（`Domain.RELOAD_DATA`，键 = 命名空间），与注册期的 mod 维分开；`runCycle` 开头按轮清空该维，不累积（`Diagnostics.clear(Domain.RELOAD_DATA)`，`:208`）。读取用 `/kasuga_data errors [mod]`（同时列出两维）；细节见 [`data-driven/api.md`](data-driven/api.md) 第 6、13 节。

> reload 期还会对**未知顶层键**给出反向提示：若这些字段其实是注册内容（如 `blocks` / `items` / `registry_groups`），把文件改列到 `on_register`（`dispatch`，`:439-446`）。提示里的注册类型示例运行时从 `TypeHandlerRegistry` 派生。

## 9. clip 引用与悬空引用

- **构建期**：`states[].clip` 在 `FsmAnimationClips` 里按 id 解析；不存在 → WARN，状态降级为静态 pose（`configureState`，`:184`）。
- **reload 收尾**：`FsmReloadHandler.afterReload`（`:95-116`）对本轮**实际落地**的定义逐个跑 `DefinitionStateMachineFactory.unknownClipReferences`（`:303-323`），把悬空引用（层 id + 状态 id + 缺失的剪辑 id）汇总成一条 WARN + 桶条目。
- 悬空引用**只记账，不阻止注册**，也不改变构建期的降级行为（`ReloadOrchestrator` 类 Javadoc `:100-104`；`FsmReloadHandler.afterReload`）。
- 已被脚本同 id 定义遮蔽的候选会跳过复核——它已不是桶里的活条目（`FsmReloadHandler.java:99-104`）。
- 测试：`AnimationClipReloadTest.missingClipFileIsReportedAfterTheLoad`、`wrongClipIdInTheClipFileIsReported`；同一规则也喂给构建期的聚合警告（`unknownClipReferences` 的 Javadoc `:289-302`）。

## 10. script-wins（分桶）

两个桶都把条目按**来源**分半：

| 桶 | SCRIPT 入口 | RESOURCE 入口 | 清桶 |
|----|-------------|---------------|------|
| `FsmAnimationClips` | `register`（`FsmAnimationClips.java:53-59`） | `registerResource`（`:66-74`） | `clearResource`（`:100-102`）只丢 RESOURCE；`clear()` = `clearResource`（`:117-119`）；`clearAll`（`:108-110`）全清 |
| `FsmDefinitions` | `register`（`FsmDefinitions.java:59-67`） | `registerResource`（`:71-81`） | `clearResource`（`:84-92`）只丢 RESOURCE |

- **写入侧**不变量：`register`（SCRIPT）会覆盖 RESOURCE（带 WARN，"script wins"）；`registerResource`（文件）**不覆盖** SCRIPT 条目。
- 所以 `AnimationClipLoader.register` 永远走 `registerResource`（`:113-121`）：文件剪辑永远不能遮蔽脚本 / 内容测试 / 游戏测试注册的同 id 剪辑。
- 每轮 reload 的 `clips.clearResource()` 只丢文件来源，脚本注册的剪辑跨 reload 存活。
- 测试：`AnimationClipReloadTest.scriptClipWinsOverTheFileVersion`、`secondCycleReplaysClipFilesWithoutLeftovers`；桶单测 `FsmAnimationClipsTest`。

## 11. 反例汇总

以下片段列在这里是为了让读者一眼认出「照旧文档抄出来的写法」，其中两条在新契约下已**不再非法**：

1. **无 wrapper 的顶层定义**（§2.3）→ `missing top-level key 'state_machines'`，整文件拒绝。
2. **真未知顶层键**（§2.4）→ 报一条 `unsupported top-level field '...'` 并附反向 D9 提示，但**已知键照常贡献**（不再是「整文件拒绝」）。
3. **顶层键值写成对象**（如 `{"state_machines": {}}`）→ `must be an array`，整文件拒绝（`StateMachineDefinitionLoaderTest.nonArrayValueRejectsWholeFile`；`ReloadOrchestratorTest.NON_ARRAY_JSON`）。
4. **一个文件同时写两类内容**（§3）→ 现在**合法**：两个 handler 各认领自己的键、各贡献条目，0 诊断。
5. **元素缺 `layers`**（§4）→ 只跳过该元素，兄弟照常。

## 12. 绑定到方块

数据驱动注册的方块通过工厂 `fsm_block` / `fsm_be` 绑定一个机器 id：`fsm_block` 造方块（带默认方块物品），`fsm_be` 造 `AnimationBlockEntity`，从 `params` 读 `state_machine` / `model`（`FsmBlockEntityFactories.java:27-30,48-74`）。方块条目顶层的字符串 `state_machine` 会被转发进内嵌 BE 的 `params.state_machine`（`BlockEntityTypeHandler.extractEmbedded`，见 schema.md §2.5）。

这部分属于**注册期内容文件**，格式见 [`data-driven/schema.md`](data-driven/schema.md) 的 `blocks` 与内嵌 `block_entity` 章节；一个可跑通的端到端例子见 [`data-driven/guide-content.md`](data-driven/guide-content.md)。

## 13. 延伸阅读

| 你想要 | 去哪 |
|--------|------|
| 索引文件、注册期内容文件、错误分类 | [`data-driven/schema.md`](data-driven/schema.md) |
| Java 类 / 方法签名、加载时序 | [`data-driven/api.md`](data-driven/api.md) |
| 从零搭第一个内容文件 | [`data-driven/guide-content.md`](data-driven/guide-content.md) |
| 数据驱动总览 | [`data-driven/intro.md`](data-driven/intro.md) |
| 自定义 `type` 工厂 / TypeHandler | [`data-driven/guide-extension.md`](data-driven/guide-extension.md) |

## 附录 A：规则出处对照

| 规则 | 出处（类:行 / 方法 / 测试名） |
|------|-------------------------------|
| `state_machines` 顶层键、三种文件级违规 | `StateMachineDefinitionLoader.decodeFile` `:67-100`；`StateMachineDefinitionLoaderTest` |
| legacy 无 wrapper 被拒 | `StateMachineDefinitionLoaderTest.legacyShapeIsRejectedAndReportsExpectedShape`；`ReloadOrchestratorTest.legacyShapeRegistersNothing` |
| `animation_clips` wrapper | `AnimationClipLoader.decodeFile` `:69-102`；`AnimationClipLoaderTest` |
| 文件级严格 / 元素级容错 | `StateMachineDefinitionLoaderTest.badElementIsSkippedWhileSiblingsDecode`；`AnimationClipLoaderTest.skipsAMalformedElementAndKeepsItsSiblings` / `skipsAnElementWithoutAnId` |
| 元素内未知键忽略 | `AnimationClipLoaderTest.ignoresKeysTheClipRecordDoesNotName`（剪辑侧） |
| 状态机元素字段 | `StateMachineDefinition.CODEC` `StateMachineDefinition.java:21-25`；子 codec `StateVarDefinition` / `LayerDefinition` / `StateDefinition` / `TransitionDefinition` / `PoseDefinition` / `TransformDefinition` |
| `state_vars` 内置类型 | `StateVarType.java:24-28`；构建期解析 `DefinitionStateMachineFactory.resolveInline` `:124-146` |
| 剪辑元素字段 | `AnimationClip.CODEC` `AnimationClip.java:47-54` 及子 codec；easing `Easing.java:232-246` |
| 目录 glob vs index-only | `ReloadOrchestrator.collectGlob` `:348-367`；`AnimationClipLoader.java:29`；`FsmReloadHandler.globDirectories` `:69-71` |
| reload stage 顺序 / 双入口 | `ReloadOrchestrator.reload` `:186-193`、`runCycle` `:198-248`、`collectNamespace` `:266-288` |
| last-wins | `ReloadOrchestrator.resolveAndReport` `:545-556`；`DuplicateIdResolver` |
| 四层异常守卫 + 兜底（+ 清桶守卫） | `ReloadOrchestrator` `:250-261,405-415,266-288,507-526,530-538,186-193`；`ReloadOrchestratorExceptionBoundaryTest` |
| 诊断走域/源键维 | `ReloadOrchestrator.reportError` `:566-573`；`FsmReloadHandler.reportWarning` `:127-130`；`Diagnostics` || 悬空 clip 引用 | `DefinitionStateMachineFactory.unknownClipReferences` `:303-323`；`FsmReloadHandler.afterReload` `:95-116` |
| 类型由注册表驱动 | `ReloadOrchestrator.knownTypeNames` `:473-486`、`dispatch` `:429-470`；`ReloadHandlerRegistry`；`FsmReloadHandlerRegistrar` `:23-31` |
| script-wins 分桶 | `FsmAnimationClips.java:53-119`；`FsmDefinitions.java:59-92`；`AnimationClipLoader.register` `:113-121` |

## 附录 B：待确认

- **`resource` 类型 token**：`StateVarType` 目录里没有它，全仓也没有 `StateVarType.register(...)` 调用；但 scripting 的 `AnimatorBuilderApi.java:192` Javadoc 把它列为内置 token 之一。两者矛盾，暂按「当前不可用」写。若下游有注册，需补。
- **状态机元素内未知键是否同样被静默忽略**：从 `StateMachineDefinition.CODEC`（`RecordCodecBuilder`）推断应忽略，但只有剪辑侧有专项测试。
- 本页的 JSON 示例只做了语法校验（`JSON.parse`）与逐字段核对 codec，未执行 Java loader；`state_vars[].type` 等**构建期**字段的解码不在文件层验证范围内。
