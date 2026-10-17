# 能力现状清单

> **用途**：填 [01-impact-template.md](../assets/01-impact-template.md) 第 2 节"项目中是否存在且可直接用"时查这里。
> 数据来自**实际扫描代码**，不是印象。最后核对于 2026-10-17。
>
> 这一项决定了阶段 2 展开哪些维度：
> `直接可用` → 只问「消费」类风险；`不存在` → 还要问「设计是否完整」。

---

| # | 能力 | 项目中的位置 | 成熟度 | 缺口 |
|---|---|---|---|---|
| 1 | 存档持久化 | `core/saved/`（`CustomSavedData`、`SavedSystem`、`ScopedNbtSavedData`） | ⚠ 可用但不成熟 | **无持久化语义测试**（旧档/卸载/重复加载均未覆盖） |
| 2 | 网络同步 | `core/networking/rpc/`、`registration/stages/PayloadRegistrationStage` | ✅ 可用 | 4 个测试类；缺多玩家真实场景验证 |
| 3 | 资源加载 | `core/resource/`（`ResourceSystem`、`ScopedResourceManager`） | ✅ 可用 | 含重载链路 |
| 4 | 模型/资产加载 | `render-core/.../uml/loaders/`、`.../uml/typo/`（gltf/mmd/obj/bbmodel） | ✅ 成熟 | 多格式齐全，测试覆盖 morph/骨骼/UV |
| 5 | 注册 | `core/registration/minecraft/`（block/block_entity/entity/menu/payload/data_component） | ✅ 可用 | 含代码模板；顺序依赖曾出过 bug |
| 6 | 数据驱动注册 | `data-driven/` | ✅ 成熟 | 20 个测试类 |
| 7 | 编解码 | `core/codec/KasugaCodec` | ✅ 可用 | — |
| 8 | 渲染 | `core/rendering`（钩子）+ `render-core` + `render-opengl` + `modelling/rendering` | ⚠ 可用 | `renderGlTest` 13 场景，**中文字体零覆盖** |
| 9 | 空间与坐标 | `render-core/.../uml/math/`（`Transform`） | ⚠ 部分 | 变换数学有测试，**"对象自带坐标系"零覆盖** |
| 10 | 状态机/动画 | `render-core/.../dynamic/fsm/`、`.../dynamic/animation/` | ✅ 成熟 | 29 个测试类 |
| 11 | 时序 | `render-core/.../dynamic/tick_loop/` | ✅ 可用 | 有 `ModelTickLoopTest` |
| 12 | 事件与生命周期 | `core/registration/stages/`、`core/resource/*Event` | ⚠ 可用但有缺陷 | **资源释放链路已知缺陷**：清理钩子可能因参数为 null 而不执行 |
| 13 | 第三方集成 | `core/mixins/`（含 `KasugaLibMixinPlugin`） | ⚠ 可用但危险 | **无 mixin 生效性自检**；Iris 会静默跳过 |
| 14 | 脚本运行时 | `scripting/` | ✅ 可用 | 14 个测试类；沙箱 **fail-open** |
| 15 | 平台与原生 | `external/slp/javet/`、`modelling/src/main/native/`（box3d） | ⚠ 可用但危险 | **无哈希校验**；移动端加载失败过 |
| 16 | 配置 | — | ❌ **不存在** | 全仓 `ModConfigSpec` / `ConfigTracker` 零命中 |
| 17 | 音效与粒子 | 音效：`core/src/codeTemplate/.../registration/minecraft/sound/SoundReg`；粒子：`modelling/.../rendering/effect/particle/` | ⚠ 分散 | **两处分散**，没有统一的"表现层"门面；播放时机属于业务，无处挂 |
| 18 | 领域规则 / 业务逻辑 | — | — | 不依赖项目能力，属于需求自身；填这一项说明要**自己设计规则** |
| 19 | 其他新子系统 | — | — | 每次现场建。**只有前 18 项都放不进去时才用** |

---

## 判据说明

**成熟度三档**

| 档 | 含义 | 对分析的影响 |
|---|---|---|
| ✅ 成熟 | 有实现 + 有覆盖该能力语义的测试 | 正常走「消费」路径 |
| ✅ 可用 | 有实现，测试偏形式或未覆盖关键场景 | 「消费」但要额外关注缺口栏 |
| ⚠ 部分 / 有缺陷 | 有实现但已知在某场景失效 | 「消费」时必须先确认缺陷是否影响本次改动 |
| ❌ 不存在 | 没有 | 走「新建」路径，展开设计类风险 |

**缺口栏为什么要看**：第 1 项「无持久化语义测试」意味着——
**即使你只是"消费"它，也没有任何测试能替你兜住旧档加载这类问题**。
这类缺口必须在本需求里自己补测。

---

## 维护

- 改动某个能力本身（扩展/修改）后，回来更新这一行。
- 新增能力（如"配置"从不存在变存在）后，加一行。
- 每次线上 bug 归因到某个能力时，检查它的成熟度是否该下调。
