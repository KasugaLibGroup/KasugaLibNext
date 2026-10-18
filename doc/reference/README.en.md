# API Reference

API signatures, constructor parameters, configuration schemas, call examples, and development commands are kept together in this directory.
Explanations of algorithms, module boundaries, data flow, and resource ownership are collected in [doc/](../explanation/README.md).
References are organized against the current source; historical performance/client-acceptance records state the environment in effect at the time, and a documentation migration does not mean they were re-verified this time.

## Models, Skeletons, and Animation

| Document | Content |
| --- | --- |
| [Generic model posing and animation](model-posing.md) | Model clip library, format integration, ModelPosing, static pose, IK channels, and API migration |
| [Model assembly and outfit cache](model-assembly.md) | Part Builder, shared skeleton, hidden regions, reference remapping, LRU cache, and extension APIs |
| [Minecraft outfit resources and swapping](minecraft-model-assembly.md) | Resource-pack JSON, programmatic registration, handle swap, resource reload, and instance state migration |
| [Anchor and MC equipment](anchors.md) | Generic anchors, camera-relative coordinates, held/head items, custom equipment renderers, and lifecycle |
| [Real-asset outfit swap example](../guides/wardrobe-example.md) | RibbonDress texture/model swap, MC held items, client screenshots, and performance comparison |
| [Skeleton Dynamics](skeleton-dynamics.md) | Generic rigid bodies/joints, Reader/Builder, IK limits and reverse mapping, diamond closure, instance control, and diagnostics |
| [Physics and tick loop](physics.md) | Native world, compound shapes, joints, force/impulse, environment, drag, active ragdoll, anchors, and block physics |
| [MMD/glTF configuration and deployment](mmd-ragdoll.md) | model manifest, profile fields, character limits, runtime deployment, and commands |
| [Model loading and rendering](modelling/README.md) | Resource formats, model_proxy, instance creation, Blockbench, and run entry points |
| [Cameras and animation](camera-animation.md) | Direct control, keyframes, JSON, shared timeline, and model clip binding |
| [FSM](fsm.md) | State variables, layers/states/transitions, pose schema, and host binding |
| [Multiplexer](multiplexer.md) | Variant selection DSL, Builder, instance state, and FSM composition |

## Rendering and Output

| Document | Content |
| --- | --- |
| [Custom rendering, Effects, and Shader DSL](effect-rendering.md) / [Shader DSL](shader-dsl.md) / [Effects and post-processing](effects-and-postfx.md) | Registration/scope, pipeline descriptors, shaders, parameters, DSL, effects, and post-processing graphs |
| [UML framework](uml-render-framework.md) | backend/context/factory, the public interfaces and lifecycle contracts for scheduling and buffers |
| [Backend extension](rendering-backends.md) | Standalone backend modules, host frame context, upload and output adaptation |
| [Scheduling and model handles](render-scheduling.md) | McModelHandle, visibility, Entity/BlockEntity renderer integration |
| [Final frame output](frame-output.md) | Subscriptions, shared frames, MIRROR/OFFSCREEN_ONLY, exceptions, and closing |
| [Player / Free / Fixed Camera](cameras.md) | Camera types, creation, generic follow targets, entity binding, projection, and runtime render configuration |
| [Multi-camera output contract](multi-camera-output.md) / [Startup and verification walkthrough](../guides/offline-rendering.md) | Camera lifecycle, resource packs, standalone windows, startup, FBO consumption, and recording |
| [PBR](pbr.md) | Player JSON, Java conversion rules, context, priority, and diagnostic commands |
| [Alpha/OIT](render-alpha-passes.md) | Material passes, debug scenes, commands, keybinds, and acceptance metrics |
| [Volumetric clouds](volumetric-clouds.md) | Multi-layer sky configuration, single cloud instance, quality, preview, and verification entry |

## Data and Tools

| Document | Content |
| --- | --- |
| [Data-driven registration](data-driven/README.md) | Content schema, factory and property extension, diagnostics, and reload; getting started and tutorials are in [data-driven/](../guides/data-driven/intro.md) (four reference pages + three guides) |
| [Formula](formula/README.md) | Expressions, Namespace, functions/variables, operators, and errors |
| [Build and verification entry](../guides/verification.md) | Gradle tasks, standalone GL, client acceptance, and evidence boundaries |

## Documentation Maintenance

Add package names, signatures, units, default values, error conditions, and lifecycle for new callable APIs in this directory, and link to the corresponding explanation.
Update call examples and configuration tables when changing an API. Update this index, the root README, relative links, and anchors when migrating or renaming.
Explanation docs may use type names, mathematical expressions, and diagrams to explain the mechanics; method references, configuration tables, and operating steps are maintained in this directory.
