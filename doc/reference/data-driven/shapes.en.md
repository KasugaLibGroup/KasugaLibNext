# Data-Driven Shapes

The top-level `shapes` field is an array of definitions. One prepare pass selects and validates the
complete domain, then compiles it into an immutable `ShapeCatalog` for apply. Clients never interpret
the JSON DSL during ticks or rendering.

The formal JSON Schema is
[`modules/data-driven/src/main/resources/schema/kasuga-data-driven-shapes.schema.json`](../../../modules/data-driven/src/main/resources/schema/kasuga-data-driven-shapes.schema.json).
A content file may also contain `registry_groups`, `blocks`, and `items`. Shape entries do not use a
`format_version` field.

## Definitions

Each definition accepts only `id`, optional `parent`, `nodes`, and `output`. A root definition requires
both `nodes` and `output`; with a parent, each may be omitted and inherited separately. `id` and
`parent` are complete `namespace:path` resource locations. `output` is a non-empty node name; it never
contains an id, array, operation, or ShapeRef.

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

A child replaces a parent node by name; arrays and operation arguments are never appended. A child
that omits `output` inherits the parent's selected name, while an explicit value replaces it. To use a
parent's final geometry, write `{"ref":"parent:id"}`. A local string with the same name as a parent node
resolves in the child's merged graph and can therefore create a local cycle.

## Expressions

A node value has one of five forms:

| Expression | Form | Contract |
|---|---|---|
| Box6 | six JSON numbers | Pixel coordinates validated before and after binary32 narrowing |
| Empty | `[]` | A valid empty result, distinct from a missing member |
| Local name | non-empty string | Resolves only in the current definition's merged `nodes`; forward references work |
| ShapeRef | `{"ref":"namespace:path","transform":{...}}` | Compiles the target's `output` in the target's own graph |
| Operation | `{"op":"union|intersection|difference","args":[...]}` | 2–64 recursively validated expressions |

`union`, `intersection`, `difference`, `op`, `args`, and `ref` are ordinary node-name strings. Only the
`op` member of an Operation object is an operator. The retired `["union", ...]` array syntax is
rejected; an expression array is either empty or exactly six numbers.

`difference` subtracts each later operand from the first. Face, edge, and point contacts do not create
volume under the geometry module's regularized solid rules. Compilation uses its depth, work, and result
fragment budgets.

## ShapeRef and transforms

A ShapeRef exposes only the target definition's selected `output`. The target compiles in its own
scope; caller node names never merge into it. Each reference applies its own transform after the
target result is compiled:

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

The order is positive per-axis scale, a Y-axis quarter turn (0, 90, 180, or 270 degrees), then
translation. Pivot and translation use pixels; the default pivot is `[8,0,8]`. Compiled results are
already in block units and must not be divided by 16 again. An explicitly empty target is a valid
operand.

## Diagnostics and dependencies

Strict prepare checks every node, parent edge, and ShapeRef before apply, including references that are
unreachable from the selected output. Diagnostics point at the failing JSON member, such as
`/shapes/0/nodes/result/op`, `/args/2`, or `/output`. Definition ids follow the directory's ordered
selection rules. A cross-owner reference requires a direct dependency. Missing or invisible targets,
ambiguous ids, parent/local/external or mixed cycles, binary32 narrowing, unit-conversion overflow,
and geometry budget failures all block apply.

Each target compiles once per prepare pass. ShapeRef operands share that immutable result and apply
only their own transform. Failed shapes never fall back to a parent, another source, or a native block
shape. P2 supplies definitions and the compiled catalog only; block slots, collision, rendering, and
native fallback belong to host adapters.
