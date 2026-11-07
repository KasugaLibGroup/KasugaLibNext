# JSON resource bindings

Configuration and model schemas use Gson DTOs. `GsonBindings.builder()` supplies
snake_case field names; Explicit DTO names and `@SerializedName` document exceptions such as
`minecraft:geometry`, `_parent_block`, `ambientocclusion`, and `default`.
DTOs initialize optional fields to their format defaults. Domain construction
still validates ranges and required fields, resolves resource identifiers, and
performs coordinate, angle, inheritance and topology conversions.

`JsonFieldPolicy.NonNullFields` distinguishes an omitted field from an explicit
null in schemas that reject null. Unknown fields remain ignored. The opt-in
`DefaultOnMismatch` field policy retains the constructor default for legacy
optional primitives of the wrong JSON kind. Applying these policies does not
mutate caller-owned trees or copy untouched subtrees: extensible factory params
retain their original `JsonObject` identity.

`GsonBindings.STRICT` prevents scalar coercion and rejects fractional or
out-of-range integer values. State property declarations use it before applying
the existing property limits and unknown-key validation. Assembly identifiers,
flags and mesh indices use the corresponding strict adapters; dynamic mapping
values retain their existing conversions.

`ModelJson` supplies model vector and scale adapters. Vectors accept the existing
array or coordinate-object forms with exact dimensions. Metadata scales accept
a number or three-component array and must be finite and positive. JE, BE,
Blockbench geometry/animation, FMT metadata, proxy rules, assembly resources,
ragdoll configuration, PBR rules and shader parameter persistence bind their
fields through Gson. Format-specific unions select a typed adapter before
business conversion; they do not duplicate field extraction.

Data-driven content keeps its extensible property/factory dispatch. Built-in
property values are decoded by Gson, with the existing per-property error
isolation. Index manifest adapters preserve valid sibling fields and entries
while reporting malformed paths. Shader parameter files retain format version
1, deterministic output and atomic replacement; invalid metadata entries are
still isolated. PBR reload failures retain the previous configuration.

JSON trees remain appropriate at structural boundaries: resource dispatch,
entry-level diagnostics, unknown-key checks, JSON generation and tree merging.
FSM and animation definitions retain their standard Mojang Codec contracts over
Gson `JsonElement`/`JsonOps`. Script-value conversion is a host-object bridge.
These operations do not hand-map fixed JSON schemas into Java objects.

Gson's version is unchanged (2.10.1). The utility module exports a compile-only
API dependency because Minecraft supplies Gson at runtime; core's shaded JAR
does not bundle another copy. Independent JVM consumers must provide Gson at
runtime, as render-core and resource-compiler already do.
