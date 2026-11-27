# 3D Model Block Dimensions Interface Design

Status: Implemented
Target: KasugaLibNext `master`
Related implementation: KuaYue PR #14

## Decision

Block dimensions are exposed only by `BlockModelSource`, without extending the generic `MinecraftModelSource`. Models without block-grid semantics—such as UML, glTF, and MMD—should not receive this capability.

## Public Interface

```java
public record BlockDimensions(int length, int width, int height)

public Optional<BlockDimensions> dimensions()
```

| Output | Axis | Calculation |
|---|---|---|
| `length` | X | `maxBlockX - minBlockX + 1` |
| `width` | Z | `maxBlockZ - minBlockZ + 1` |
| `height` | Y | `maxBlockY - minBlockY + 1` |

- Returns the tight bounding dimensions of the outermost non-air blocks.
- Interior gaps are included in the span; outer air is excluded.
- Returns block-grid counts, not block-entity counts.
- An empty source returns `Optional.empty()`.
- Source origin, camera, display transforms, and layer explosion do not affect the result.
- Declared file dimensions are not included in this interface.

Dimensions are computed by scanning block positions once during `BlockModelSource` construction and cached. Querying does not build a snapshot, touch the GPU, or require the render thread.

## PR #14 Integration

```java
var size = source.dimensions()
        .orElseThrow(() -> new IllegalStateException("Blueprint has no blocks"));
float scale = (float) (FIT_EXTENT / size.longestAxis());
```

`test_ss8` has length, width, and height `18 x 5 x 4`, with longest axis `18`, and should use `0.07 / 18` as its scale.

## Verification

- Single block, sparse blocks, translated coordinates, empty model, and non-positive dimensions;
- Client preview smoke logs dimensions for real `BlockModelSource` instances and verifies the fluid fixture;
- Scene unit tests, `contentTesting` compilation, Create downstream compilation, and `git diff --check`.

## UB-mode Self-Check

- **First Make it Work**: minimal interface is `BlockModelSource.dimensions()`.
- **Subtract Before You Add**: no generic source capability, preview forwarding, wrapper adaptation, or composite scene calculation was added.
- **Foundational Thinking**: lock down X/Y/Z mapping and tight non-air extents first.
- **Boundary Discipline**: compute once at the source construction boundary and cache.
- **Test Behavior**: unit tests cover dimension semantics; client smoke covers public method wiring.
