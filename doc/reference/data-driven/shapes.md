# 数据驱动形状

顶层 `shapes` 是一个定义数组。所有定义在同一次 prepare 中选择、验证并编译；编译结果在 apply
阶段以不可变的 `ShapeCatalog` 提供，不在客户端 tick 或渲染时解释 JSON。

正式 JSON Schema 位于
[`modules/data-driven/src/main/resources/schema/kasuga-data-driven-shapes.schema.json`](../../../modules/data-driven/src/main/resources/schema/kasuga-data-driven-shapes.schema.json)。
内容文件可同时包含 `registry_groups`、`blocks`、`items` 和 `shapes`。该格式不使用
`format_version`。

## 定义

每个定义只接受 `id`、可选的 `parent`、`nodes` 和 `output`。根定义的 `nodes` 与 `output` 都必填；
有 parent 时可分别省略并继承。`id` 和 `parent` 都是完整的 `namespace:path`。`output` 是非空节点名，
不会把 `id`、数组、运算对象或 ShapeRef 当作输出值。

```json
{
  "shapes": [
    {
      "id": "example:frame",
      "nodes": {
        "outer": [0.0, 0.0, 0.0, 32.0, 32.0, 4.0],
        "opening": [8.0, 0.0, 0.0, 24.0, 30.0, 4.0],
        "result": {"op": "difference", "args": ["outer", "opening"]}
      },
      "output": "result"
    }
  ]
}
```

父节点按名字整项覆盖，不拼接数组或 args。子定义省略 `output` 时继承父定义的输出名；在子定义中
显式写 `output` 会整体替换。若要引用父级最终几何，应使用 `{"ref":"parent:id"}`；在子定义节点中
引用与父节点同名的本地节点，会解析到合并后的有效节点并可能形成局部循环。

## 表达式

节点值可以是以下五种表达式：

| 表达式 | 写法 | 说明 |
|---|---|---|
| Box6 | 六个 JSON number | 像素坐标，每个坐标先验证有限范围，再窄化为 float32 |
| Empty | `[]` | 有效空结果，与缺少字段不同 |
| Local name | 非空字符串 | 只在当前定义合并后的 `nodes` 中解析，可前向引用 |
| ShapeRef | `{"ref":"namespace:path","transform":{...}}` | 解析目标的 `output` 在其自身节点图中求值 |
| Operation | `{"op":"union|intersection|difference","args":[...]}` | `args` 为 2–64 个递归表达式 |

`union`、`intersection`、`difference`、`op`、`args`、`ref` 等词都可作为普通节点名。只有 Operation
对象中的 `op` 成员是运算符。旧的 `["union", ...]` 运算数组不受支持；表达式数组只能为空或恰好
六个数字。

`difference` 按顺序从第一项扣除后续项。交点、面或线接触的交集按常规化实心 CSG 规则处理为无体积。
编译沿用 geometry 模块的深度、工作量和结果片段预算。

## ShapeRef 与变换

ShapeRef 只公开目标定义所选 `output` 的结果。目标内部的同名节点不会与调用方合并，调用方的节点
也不能改变目标的局部引用。引用结果先按目标作用域编译，再施加本次引用自己的变换：

```json
{
  "ref": "example:frame",
  "transform": {
    "scale": [1.0, 1.0, 1.0],
    "rotate_y": 90,
    "pivot": [8.0, 0.0, 8.0],
    "translate": [0.0, 0.0, 0.0]
  }
}
```

变换顺序是正逐轴缩放、Y 轴 0/90/180/270 度旋转、平移；pivot 与 translate 使用像素，默认 pivot 是
`[8,0,8]`。编译结果和共享引用缓存已在格单位，不能再除 16。显式空目标可作为有效操作数。

## 诊断与依赖

STRICT prepare 在 apply 前检查所有节点、parent 和 ShapeRef，包括从当前输出不可达的非法引用。
错误定位到具体 JSON Pointer，例如 `/shapes/0/nodes/result/op`、`/args/2` 或 `/output`。同一 id
沿用目录的有序选择；跨 owner 引用要求直接依赖。缺失目标、不可见目标、重名歧义、父级/局部/外部
或混合循环、float32 舍入退化、单位换算溢出和 geometry 预算错误都会阻止 apply。

同一轮 prepare 内，目标定义只编译一次；ShapeRef 复用该不可变结果，并在引用位置应用自己的变换。
失败内容不会回退到 parent、另一来源或原生方块形状。P2 只提供形状定义与编译 catalog；方块用途、
碰撞、渲染和无形状原生回退由宿主适配层负责。
