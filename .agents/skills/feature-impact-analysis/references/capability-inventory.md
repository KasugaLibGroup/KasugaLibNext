# 能力现状清单

> **用途**：填 [01-impact-template.md](../assets/01-impact-template.md) 第 2 节"项目中是否存在且可直接用"时查这里。
> 数据来自**实际扫描代码**，不是印象。渲染与输入项最后核对于 2026-10-24，其余项为 2026-10-17。
>
> 这一项决定了阶段 2 展开哪些维度：
> `直接可用` → 只问「消费」类风险；`不存在` → 还要问「设计是否完整」。

---

| # | 能力 | 项目中的位置 | 成熟度 | 缺口 |
|---|---|---|---|---|
| 1 | 存档持久化 | `core/saved/`（`CustomSavedData`、`SavedSystem`、`ScopedNbtSavedData`） | ⚠ 可用但不成熟 | **无持久化语义测试**（旧档/卸载/重复加载均未覆盖） |
| 2 | 网络同步 | `core/networking/rpc/`、`registration/stages/PayloadRegistrationStage`、`external/uip/ldlib/LdMenuElement` | ✅ 可用 | 通用网络有4个测试类；LDLib2同步菜单可移交世界宿主，Compose动态模型/RPC与每玩家隔离已有双客户端LAN验证；通用网络其他多人场景仍缺验证 |
| 3 | 资源加载 | `core/resource/`（`ResourceSystem`、`ScopedResourceManager`） | ✅ 可用 | 含重载链路 |
| 4 | 模型/资产加载 | `render-core/.../uml/loaders/`、`.../uml/typo/`（gltf/mmd/obj/bbmodel） | ✅ 成熟 | 多格式齐全，测试覆盖 morph/骨骼/UV；OBJ 静态顶点视图复用原 processor，MTL 支持 Ke/Pr 与 Bump/Pr/Ke 贴图槽，保留 usemtl 跨 group/object 继承 |
| 5 | 注册 | `core/registration/minecraft/`（block/block_entity/entity/menu/payload/data_component） | ✅ 可用 | 含代码模板；顺序依赖曾出过 bug |
| 6 | 数据驱动注册 | `data-driven/` | ✅ 成熟 | 20 个测试类 |
| 7 | 编解码 | `core/codec/KasugaCodec` | ✅ 可用 | — |
| 8 | 渲染 | `core/rendering`（钩子）+ `render-core` + `render-opengl` + `modelling/rendering` | ⚠ 可用 | `renderGlTest` 17 场景（含 MRT 半浮点附件/可采样深度、纹理上传绑定恢复）；post graph 支持多颜色附件与 TBO sampler，默认单 RGBA8 路径保留；世界 UI 有独立深度/alpha/resize/销毁回归（退役即时检查纹理/FBO/深度附件，客户端反复缩放检查当前附件与像素），隐藏客户端验证真实中文字体/控件在 MIN/0/MAX_GUI_Z 像素一致；UI模型预览使用独立实例/深度画布，原版、LDLib2和ModernUI均有原生客户端验证；玩家操作体验仍需验收 |
| 9 | 空间与坐标 | `render-core/.../uml/math/`（`Transform`）+ `modelling/.../backend/ui` | ⚠ 部分 | 世界 UI 已覆盖视图投影、局部平面、边界与多目标路由；其他对象的坐标系仍缺少完整覆盖 |
| 10 | 状态机/动画 | `render-core/.../dynamic/fsm/`、`.../dynamic/animation/` | ✅ 成熟 | 29 个测试类；Java 轨道可复用 quinticHermite 端点速度/加速度，Nebula 用共享 timeline、camera player 与 FSM 验证固定步与跨阶段采样 |
| 11 | 时序 | `render-core/.../dynamic/tick_loop/` | ✅ 可用 | 有 `ModelTickLoopTest`；2026-11-07 标准 skeleton ANIMATED/RELAXED/DRIVEN 状态、共享物理屏障与固定步控制器已统一，通用 MinecraftModelRuntime 管理自动帧；相关 JVM/native 回归和 PMX/glTF 145 帧 M3 Max/Iris+Sodium 无光影包 smoke 通过 |
| 12 | 事件与生命周期 | `core/registration/stages/`、`core/resource/*Event`、`modelling/.../backend/ui`、`external/uip/ldlib/LdElement` | ⚠ 可用但有缺陷 | 世界 UI 已覆盖捕获/焦点/松键清理；LDLib2 适配器管理 tick、幂等关闭、断线及世界切换清理，通用注册仍由调用方管理；其他资源释放链路有已知参数为 null 导致清理未执行的缺陷；模型发布已统一原子 generation、同锁订阅/状态与显式 owner 线程释放，准备回调不持锁、缓存先于全部 listener 失效，abort 保留旧缓存 |
| 13 | 第三方集成 | `core/mixins/`（含 `KasugaLibMixinPlugin`）、`modelling/.../backend/ui/WorldUiTextInput`、`external/uip/ldlib`、`external/uip/ldlib-compose` | ⚠ 可用但危险 | LDLib2世界控件与文字焦点已有真实客户端交互smoke；ModernUI模型通过MinecraftSurfaceView回调绘制并跨线程清理；Compose依赖位于独立可选模块，双端固定注册、客户端重组/模型节点和世界菜单已有原生客户端与双客户端LAN验证；LDLib2 2.2.44的UISurface与SDF兼容限外部适配器；共享后端不依赖第三方UI库；物理专用服务端/Iris尚未验证 |
| 14 | 脚本运行时 | `scripting/`；可选 `shader-graal/` | ✅ 可用 | 通用 scripting 有14个测试类，沙箱 **fail-open**；shader-graal 是独立Java21/GraalJS构建期前端，直接复用类型化DSL，显式host成员/类型白名单、语句预算和每次独立Context，不自动注册通用ScriptEngine；35个JVM场景，2026-11-04 M3 Max/MC1.21.1/NeoForge21.1.203/Iris+Sodium无光影包已验证原screen-tint.js生成、加载、全画面RGBA、真实滑块/重置及稳定GPU program；其他平台/启用光影包需另验 |
| 15 | 平台与原生 | `external/slp/javet/`、`modelling/src/main/native/`（box3d） | ⚠ 可用但危险 | **无哈希校验**；移动端加载失败过 |
| 16 | 配置 | `core/.../ClientRenderingRegistry`（ModConfigSpec）、`modelling/.../effect/shader/ShaderParameterPersistence`（专用JSON） | ✅ 可用 | 旧清单的“全仓零命中”已不符合当前源码；shader参数有schema校验、异步JSON保存和重置默认语义，2026-11-04真实参数面板回归已覆盖按钮随滑块变化刷新；其他配置类型仍需按具体实现核对 |
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
