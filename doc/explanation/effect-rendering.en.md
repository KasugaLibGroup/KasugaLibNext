# How custom render pipelines work

For the interfaces, Shader DSL, and integration examples see the [API reference](../reference/effect-rendering.md).

## Descriptor and execution separation

A pipeline keeps its draw state, execution stage, and ordering information in an immutable descriptor; the
registry activates, replaces, and releases it. A configuration that describes the same content can be reused,
while the execution object that holds GPU resources and host state belongs to one registration. After a
registration is replaced, an old handle still refers to the old object, so unloading an old module does not
delete a new object by mistake.

World drawing is organized by host stage. Within a stage, entries are ordered by priority and then by resource
identity. The registry caches an execution snapshot per stage and invalidates it on change, so the draw traversal
does not depend directly on the mutable registration list. A failure in one callback does not stop the other
pipelines in the same stage. Pose stacks are isolated per callback, and a buffer batch must complete within
that draw.

## Ownership and generation-based resources

A static registration belongs to the client lifecycle, while dynamic content belongs to a scope that can be
closed. A parent scope owns its child scopes, and resources are released in reverse registration order. Draw
state and the texture-variant cache belong to the registration object itself, and replacing a name yields an
independent cache.

A shader handle stays stable across a resource reload, while the compiled instance inside it is replaced per
generation. The generation advances only when a new instance is installed successfully. Editable parameters are
stored independently of the compiled instance, so a resource reload does not lose user parameters. Different
registrations cannot share a lifecycle just because they use the same ID.

## Shader preparation and the render thread

Converting generated IR into GLSL and resource descriptors is CPU work and runs on a bounded background
executor; OpenGL compilation and linking must return to the render thread. Concurrent requests for the same
program share one conversion, whose result goes into a bounded cache. A full preparation queue fails explicitly
rather than falling back to a synchronous conversion on the calling thread.

Each frame the compilation queue bounds how many jobs start and the budget spent before starting, and schedules
across owners. A driver compilation that has already started cannot be cut off midway, so the budget is not a
strict cap on frame time. Closing and replacing cancel preparation tasks that have not started yet. The failure
policy decides whether to disable the current pipeline or abort the reload in progress; a late preload has no
reload context to abort.

## Execution model of the Java DSL

A Java builder builds typed shader IR immediately; it does not parse a Java AST or decompile lambdas. Java
conditions and loops only control how the IR is built, and runtime GPU control flow has to be represented
explicitly in the IR. Reusing an expression object is not the same as creating a GLSL local variable. The
graphics program checks, at build time, the names and types of vertex outputs and fragment inputs, plus the
consistency of uniform and sampler declarations across stages. The current backend converts IR into GLSL 150 and
Minecraft core-shader resources.

## Effect instances and post-processing graphs

Effects of the same kind share a renderer and pipeline state, and each instance holds position, lifetime, and
interpolation data. The client tick advances effects; the draw stage culls by interpolated bounding volume and
sorts by distance to the camera when needed. A level switch clears instances and the post-processing target pool.

Post-processing builds a directed acyclic graph from declarative read/write relations and explicit dependencies.
Every intermediate target has one clear producer; cycles, multiple writers, and undeclared targets are rejected
at build time. A single pass does not read and write the same target, which avoids framebuffer feedback. A
target's physical identity includes the graph ID, so identically named logical targets in several graphs do not
collide. Targets are reused by descriptor and actual size, and the last graph registration releases the
targets it manages.

The pre-execution preparation can cancel the frame without allocating unused targets. Within a frame the
preparation and the passes share temporary data containers, and target access is bounded by the declarations.
The host's main target is restored when post-processing ends or throws. Touching low-level GPU objects directly
bypasses these constraints, and state restoration is then the caller's responsibility.
