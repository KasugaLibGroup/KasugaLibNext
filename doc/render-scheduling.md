# 模型渲染调度（Render Scheduling）

公共调度与 buffer/后端契约见 [UML 渲染 framework](uml-render-framework.md)。
本文的 `ModelRenderScheduler` 是 MC 兼容适配器，策略与帧标记由
`uml.framework.schedule` 管理；`VANILLA_RENDERER` 对应公共 `HOST_RENDERER`。
多机位的可见性标记按视角清空，动画采样按共享帧 token 去重，见
[多机位与离屏输出文档](../docs/offline_rendering_and_multi_cam.md)。

UML 模型挂载到 `mc_backend` 之后由分阶段 world pipeline 绘制。本文回答
"模型什么时候该渲染、什么时候不该渲染"，以及如何与 Minecraft 原版的
渲染器（renderer）调度机制对接。

## 0. 模型句柄：逻辑端与渲染端的分工

上层代码按 MC 的心智模型分两半操作同一个模型：

```text
上层逻辑端 ────► 状态机（FSM / PoseDriver）     决定"做什么动作"：状态、转移、动画行为
                     │ 写骨骼局部变换
                     ▼
              ModelInstance ◄───────────────────── 同一个实例
                     ▲ 写根变换 / 调度 / 锚点 / 物理
上层渲染端 ────► McModelHandle                  决定"放在哪、可不可见、走哪条调度"
```

`McModelHandle`（`mc/api/McModelHandle`）是渲染端的唯一入口：

```java
// 渲染端：句柄可先于资源发布创建，姿态暂存、mount 时生效
McModelHandle handle = McModelHandle.of(modelLoc, null, instanceLoc, new Vec3(x, y, z));
handle.mount();                       // 资源未发布时返回 false，下 tick 重试
handle.setPos(entity.position());     // vanilla 风格动词
handle.scheduleVanillaRenderer();     // 可见性交给原版 renderer 决定
handle.attachToAnchor("hand_r", (name, t) -> placeItem(t));  // 手持物
handle.enablePhysics(UpdateMode.RENDER_FRAME);
handle.hide() / show() / markRenderedThisFrame();
```

状态机通过句柄接入（三种形态）：

```java
// ① 本地数据驱动：按定义 id 构建，逻辑端拿绑定对象 tick
var binding = handle.attachLocalStateMachine(Id.of("kasuga_lib", "door"));
if (binding != null) {
    binding.machine().mutableVars().set(OPEN, true);  // 逻辑端触发转移
    binding.tick();                                   // BE/Entity 的 game tick 里调
}

// ② 本地程序化：直接接管已构建的机器
var machine = StateMachine.builder(owner)...build();
handle.attachLocalStateMachine(machine);

// ③ 带服务器权威同步的完整宿主（BE/Entity 推荐）：
//    句柄用 ofFsm 创建以对齐实例标识；内部托管 FsmAnimatedModel，
//    客户端绑定的实例自动收编进句柄，渲染调度照常生效。
McModelHandle handle = McModelHandle.ofFsm(modelLoc, null, pos.asLong(), null);
FsmAnimatedModel fsm = handle.attachSyncedStateMachine(this, pos.asLong(), fsmId);
fsm.tick(level, pos);        // BE ticker 里转发（服务端推同步/客户端驱动傀儡）
```

若实例由既有组件先行创建（如 `AnimationBlockEntity` 内部的 `FsmAnimatedModel`），
渲染端用 `McModelHandle.ofExisting(modelLoc, modelName, instanceLoc)` 收编它，
不要重复建第二个实例。

## 1. 三种调度模式

`ModelRenderScheduler` 为每个挂载实例维护一条策略，默认 `ALWAYS`（历史行为：
每帧无条件绘制）。切换模式：

```java
ModelRenderScheduler.setMode(instance, RenderScheduleMode.VANILLA_RENDERER);
```

| 模式 | 决策者 | 典型宿主 |
| --- | --- | --- |
| `ALWAYS` | 全局管线每帧绘制 | 静态装饰、特效类内容 |
| `MANUAL` | 宿主代码：`setVisible(instance, bool)` 或 `pipeline.setRendering(model, id, bool)` | 自定义 LOD/开关逻辑 |
| `VANILLA_RENDERER` | 原版渲染器是否被调用 | 实体（EntityRenderer）、方块实体（BER） |

模式之外还有两个正交的闸门：

- **视距**：`setMaxRenderDistance(instance, blocks)`，按相机距离平方裁剪；
  0 表示不限。
- **视锥**：管线对每个实例用「顶点包围球平移到根部位置」做
  `Frustum.isVisible` 测试（包围半径首次使用时计算并缓存）。
  被剔除的实例**当帧不采样动画、不上传顶点**——与"原版生物超距离后
  renderer 不被调用"语义一致。物理推进独立于可见性
  （`MinecraftRagdollRuntime`），不会因剔除冻结。

卸载实例（`stopRendering`/`removeInstance`）会自动清理其调度状态。

## 2. 对接原版 Entity 渲染器

原版流程：`EntityRenderDispatcher.shouldRender`（tracking distance +
frustum）通过后才会调用 `EntityRenderer.render`。让实体模型继承这个调度：

1. 用 `VANILLA_RENDERER` 模式挂载模型；
2. 提供一个继承 `UmlModelEntityRenderer<T>` 的 renderer 并注册到实体类型：

```java
public class MyEntityRenderer extends UmlModelEntityRenderer<MyEntity> {
    public MyEntityRenderer(EntityRendererProvider.Context ctx) { super(ctx); }

    @Override protected ModelInstance resolveModel(MyEntity entity) {
        return entity.getKasugaModel();   // 宿主持有的实例
    }

    @Override protected ResourceLocation modelTexture(MyEntity entity) {
        return null; // 仅 vanilla UI 覆盖层查询；null → white fallback
    }
}
```

被 tracking range 剔除或视锥外的实体，其 renderer 的 `render()` 不会被
原版调用 → 无标记 → 管线当帧跳过采样与绘制。

## 3. 对接原版 BlockEntity 渲染器

原版流程：`BlockEntityRenderDispatcher.render` 先查
`BlockEntityRenderer.shouldRender`（view distance，默认 64 格），
外层再过 section frustum；两关都过才调用 `render()`。

```java
BlockEntityRenderers.register(MY_BE_TYPE,
        context -> new UmlBlockEntityRenderer<>(context,
                be -> be.getKasugaRender().getModelInstance("main")));
```

同样配合 `VANILLA_RENDERER` 模式挂载。`getViewDistance` 可在构造时调整。

## 4. "走 chunk dispatcher 的方块"怎么办

现代原版把静态方块几何直接烘进整个 chunk section 的 mesh
（`SectionRenderDispatcher` 批量构建），没有逐方块的 renderer 回调——
这是刻意的批处理优化。结论分两类：

- **纯静态模型**：可以继续烘进 chunk mesh（或用普通 BakedModel），
  享受原版的 section 级剔除与合批；但代价是它永远不可动。
- **动态/骨骼模型**：无法烘进 section mesh（顶点每帧都在变），因此
  原版没有为它准备 dispatcher 入口。两条正路：
  1. **升级为 BlockEntity** + 上面的 BER 适配器 —— 推荐，完全复用原版调度；
     这也是原版自身处理"需要逐帧变化的方块"的方式（箱子开合动画、
     旗帜等都是 BE）。
  2. 保持全局管线挂载，用 `MANUAL` 模式 + 自己的可见性逻辑，
     或依赖管线的内置视锥/视距剔除（`ALWAYS` 模式也享受这两个闸门）。

简言之：**chunk 烘焙路径只承载静态内容；任何需要调度的动态模型都应
落在 Entity / BlockEntity renderer 或显式 MANUAL 控制上。**

## 5. 帧时序

```text
vanilla pass: entities + block entities
  └─ EntityRenderer.render / BER.render
       └─ ModelRenderScheduler.markRenderedThisFrame(instance)
kasuga pipelines:
  └─ AFTER_ENTITIES (priority 0 → 1)
       ├─ OPAQUE: no blend + depth write
       └─ MASK: alpha discard + depth write
  └─ AFTER_TRANSLUCENT_BLOCKS
       └─ BLEND: low alpha discard + no depth write + weighted-blended OIT
          (Iris/unsupported-target fallback: far-to-near sort)

  The first pass calls flipFrame() to 固化本帧标记. The sampled-instance
  cache remains alive until BLEND, so a mixed model is sampled once even
  though its three material classes are submitted independently.
```

标记缓冲采用双 buffer 交换：vanilla pass 写入的标记在管线开始时被固化，
下一帧的标记写入另一份缓冲，两个阶段无需同步屏障。
