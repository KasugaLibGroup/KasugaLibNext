# Minecraft 模型附加组件 v1

Blockbench 编辑与预览插件由独立仓库 [KasugaLib-BlockBenchPlugin](https://github.com/KasugaLibGroup/KasugaLib-BlockBenchPlugin) 维护；本文描述的数据格式和游戏端加载行为仍由 KasugaLibNext 维护。

在既有模型 JSON 中增加 `kasuga_lib` 对象。该对象不取代原模型结构：原生几何、纹理、层级、显示变换、动画等仍由原 codec 原样输出。删除 `kasuga_lib` 后，得到同一份基础模型；普通读取器不需要理解附加组件即可读取静态几何。具体工具若使用拒绝未知字段的严格校验器，需要单独验证接受情况。

| 模型 | 字段位置 | 骨骼来源 |
|---|---|---|
| `.bbmodel` | 根 `kasuga_lib` | 原生大纲组 |
| Java 方块/物品 JSON | 根 `kasuga_lib` | 扩展 `bones`，原 `elements` 不变 |
| Bedrock geometry JSON | 每个 `minecraft:geometry[i].kasuga_lib` | 原生 bones + 扩展中的空控制骨骼 |

```json
{
  "elements": [],
  "textures": {},
  "kasuga_lib": {
    "version": 1,
    "bones": [],
    "bindings": [],
    "emitters": []
  }
}
```

上例只展示放置位置；完整可编辑工程、Java/Bedrock 示例以及 Schema 在 `examples/model-components/`。扩展未知字段在插件内保留。缺少扩展时使用旧模型加载路径；未知版本保留原文并禁止插件编辑，游戏加载器给出版本错误。

## 骨骼与引用

每项骨骼包含 `id`（项目内稳定标识，Blockbench 使用 UUID）、`name`（本次导出的原生骨骼名）、`parent`（父 ID 或 null）、`pivot`（绝对静止枢轴）与 `components`。引用以 ID 为准；游戏加载时映射到本次创建的 Bone 实例，不跨模型缓存。名称和 ID 必须唯一，父级不能成环。重命名后引用仍指向原 UUID。

三种扩展都使用 **Blockbench 坐标**：像素长度、角度单位度、+Y 向上。游戏入口将像素转换为 1/16 方块，角度转换为弧度。Bedrock 原生 pivot 的 X 在其既有加载器中反射；扩展已经是 Blockbench 坐标，不再反射。刚体与粒子源 `position` 是相对绑定骨骼的局部偏移；其 `rotation` 为局部 ZYX 欧拉角。Java 组只提供平移枢轴，模型体块原生旋转仍由 `elements[].rotation` 表达。

Java 的 `bindings` 以最终导出的 `elements` **零基索引**对应骨骼 ID：`{"element":0,"bone":"arm-id"}`；null 或未指定绑定则跟随合成根。映射按 codec 的实际大纲遍历顺序生成，不用名称、位置或 Cube.all 顺序猜测。继承父模型的元素时可继承其扩展；子模型有自有元素则不继承父扩展的索引映射。

## IK

IK 放在末端骨骼的 `components.ik`：

```json
{
  "target": "target-id",
  "links": [
    {"bone": "upper-id"},
    {"bone": "lower-id", "min": [-90, -45, -30], "max": [90, 45, 30]}
  ],
  "iterations": 32,
  "tolerance": 0.01,
  "max_step": 45
}
```

`links` 从根到末梢排列，是末端的连续祖先，不包含末端本身。目标必须在驱动链外，可以是没有几何的组。误差单位像素；`min/max` 是相对动画姿态的 XYZ 旋转增量限制，单位度。求解顺序沿用游戏的动画 → IK → 物理 → 附着点。编辑器使用 CCD，游戏使用既有求解器，因此接近奇异姿态时不保证姿态逐项一致。

## 刚体与关节

每个骨骼最多一个 `components.physics`：

```json
{
  "shape": "capsule", "mode": "rotation",
  "size": [1, 4, 1], "position": [0, 4, 0], "rotation": [0, 0, 0],
  "mass": 1, "linear_damping": 0.2, "angular_damping": 0.2,
  "friction": 0.5, "restitution": 0,
  "collision_group": 0, "non_collision_mask": 0,
  "parent_body": "parent-bone-id",
  "joint": {"rotation_min": [-45, -45, -45], "rotation_max": [45, 45, 45], "rotation_spring": [0, 0, 0]},
  "stiffness": 80, "gravity": [0, -156.8, 0]
}
```

形状为 sphere/box/capsule：球取 size.x 半径，盒取三轴半长，胶囊取 size.x 半径与 size.y 圆柱长度（全高为 y + 2x）。模式 kinematic 跟随动画、dynamic 写回位置与旋转、rotation 只写回旋转。游戏使用既有 SkeletonDynamics/Box3D；`parent_body` 定义连接父刚体，连接点为本骨骼枢轴。关节是当前后端支持的球关节锥角/扭转近似，不宣称完整六自由度限位。

`stiffness` 与每骨骼 `gravity` 仅用于 Blockbench 的弹簧预览，后者单位像素/秒²。它用于观察次级骨骼惯性与阻尼，碰撞、父关节的精确响应以游戏为准；游戏重力由现有物理场景设置。

## 粒子源

粒子源是大纲中的原生 Locator 零体积定位点，可随父骨骼运动，导出不产生三角面。参数保存在扩展 `emitters`：

```json
{
  "id": "emitter-id", "name": "exhaust", "bone": "tail-id",
  "position": [0, 8, 0], "rotation": [0, 0, 90],
  "enabled": true, "rate": 30, "max_particles": 256, "space": "world",
  "particle": {
    "texture": "minecraft:particle/flame",
    "lifetime": 1, "speed": 8, "spread": 15,
    "size": 1, "color": "#ff8844", "gravity": [0, -4, 0]
  }
}
```

骨骼可为 null，表示模型根空间。发射方向为定位点的局部 +Y，`spread` 是喷射锥半角，速度和加速度为像素/秒、像素/秒²，寿命为秒。world 粒子发射后独立运动，加速度位于模型世界空间；local 粒子坐标与加速度跟随粒子源。编辑器和游戏都使用指定颜色与纹理 ID；游戏运行时按固定步更新，并在模型实例销毁、资源重载和世界切换时清理。

每源最多 4096 粒子，发射率最多每秒 10000；固定步模拟限制后台恢复时的追赶量，防止一次喷出长期积压的粒子。保存、导出、Undo、切项目和卸载均停止预览并释放临时资源；预览变换不写入模型的原始骨骼旋转。
