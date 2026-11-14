# 3D 模型方块尺寸接口设计

状态：Implemented
目标：KasugaLibNext `master`
关联实现：KuaYue PR #14

## 结论

方块尺寸只由 `BlockModelSource` 输出，不扩展通用 `MinecraftModelSource`。UML、glTF、MMD
等模型没有方块格语义，不应获得该能力。

## 公开接口

```java
public record BlockDimensions(int length, int width, int height)

public Optional<BlockDimensions> dimensions()
```

| 输出 | 坐标轴 | 计算 |
|---|---|---|
| `length` | X | `maxBlockX - minBlockX + 1` |
| `width` | Z | `maxBlockZ - minBlockZ + 1` |
| `height` | Y | `maxBlockY - minBlockY + 1` |

- 返回最外层非空气方块的紧凑包围尺寸。
- 中间空位计入跨度，外层空气不计入。
- 返回方块格数量，不是方块实体数量。
- 空 source 返回 `Optional.empty()`。
- source origin、相机、显示变换和分层爆炸不影响结果。
- 文件声明尺寸不包含在本接口中。

尺寸在 `BlockModelSource` 构造时扫描一次方块位置并缓存。查询不构建 snapshot、不访问 GPU，
也不需要渲染线程。

## PR #14 接入

```java
var size = source.dimensions()
        .orElseThrow(() -> new IllegalStateException("Blueprint has no blocks"));
float scale = (float) (FIT_EXTENT / size.longestAxis());
```

`test_ss8` 的长宽高为 `18 x 5 x 4`，最长轴为 18，应使用 `0.07 / 18` 作为缩放。

## 验证

- 单方块、稀疏方块、坐标平移、空模型和非正尺寸；
- 客户端预览 smoke 对真实 `BlockModelSource` 记录尺寸并校验流体 fixture；
- 场景单元测试、contentTesting 编译、Create 下游编译和 `git diff --check`。

## UB-mode 自查

- **First Make it Work**：最小接口为 `BlockModelSource.dimensions()`。
- **Subtract Before You Add**：没有增加通用 source 能力、预览转发、包装器适配或复合场景计算。
- **Foundational Thinking**：先固定 X/Y/Z 映射与非空气紧凑范围。
- **Boundary Discipline**：在 source 构造边界计算一次并缓存。
- **Test Behavior**：单元测试覆盖尺寸语义，客户端 smoke 覆盖公开方法接线。
