# Geometry

`modules:geometry` is a plain Java 21 geometry library for data-driven block shapes. Its public
coordinates are immutable `float` block units: one block is sixteen authoring pixels.

`Box` is one regularized solid primitive. `Shape` contains the actual, non-overlapping boxes that
form a result, so holes and disconnected regions remain real geometry. `Shape#bounds()` is only a
broad-phase extent and must not be used as the result. `Shape#boundaryEdges()` returns the merged
outer and hole boundary of the final solid, with coplanar internal seams removed.

Union, intersection, and difference preserve regularized closed solids: zero-volume face, edge, or
point contacts do not create volume. Difference always applies its subtractors from left to right.
`GeometryBudget` limits operation nesting, work, and result fragments. It is checked before large
grid allocations and whenever a result adds another fragment.

`Box#fromPixels` validates raw pixel values, binary32 narrowing, range, and per-axis positive size.
`Transform#fromPixels` accepts the shape-reference transform contract and applies scale, then a
quarter turn around Y, then translation. Resource lookup, JSON decoding, Minecraft adapters, and
graphics conversion deliberately live outside this module.
