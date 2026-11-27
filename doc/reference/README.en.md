# API Reference

API signatures, constructor parameters, configuration schemas, call examples, and development commands are kept together in this directory.
Explanations of algorithms, module boundaries, data flow, and resource ownership are collected in [Explanations and Design](../explanation/README.md).
References are organized against the current source; historical performance/client-acceptance records state the environment in effect at the time, and a documentation migration does not mean they were re-verified this time.

## Core, Dependency Injection, and Build

| Document | Content |
| --- | --- |
| [Core Mod Entry and Registration System](core-registration.md) | `KasugaLib` entry, `Reg` / `RegistryGroup` / `Registry`, stage dispatch, and Minecraft/custom registration DSL |
| [Core Runtime Infrastructure and Content Subsystems](core-runtime.md) | Networking RPC, `SavedSystem`, `CompatCapability`, Codec/Holder, core rendering hooks, and device/document/graph/QR subsystems |
| [Inject Dependency Injection and Environment Wiring](inject.md) | Micronaut 4.9.10 bootstrap, `@BeanOnlyIn`, `Configurable`, `Saved<T>`, and `ServiceMixinPlugin` |
| [Utils Module](utils.md) | Conditional Mixins, codegen marker annotations, `LazyRecomputable`/`MapSet`/`Pair`, path/glob matchers, and `GsonBindings` |
| [Lifecycle and Initialization Order](lifecycle.md) | Mod loading, Micronaut DI startup, `RegistrationStage` dispatch order, render/script init, and shutdown cleanup |
| [Configuration Options](configuration.md) | `Configurable` / `ModConfigSpec` options, JSON/TOML runtime files, JVM system properties, and environment variables |
| [Extension Point Catalog](extensions.md) | Public SPIs, registries, factories, handlers, and pipeline hooks across modules with ownership and cleanup contracts |
| [Sidedness Model](sidedness.md) | Physical side (`Dist` / `@BeanOnlyIn`), logical side (`LogicalSide`), Mixin isolation, and subsystem sidedness matrix |
| [Gradle Plugins, Codegen, and Resource Compiler Reference](gradle-and-codegen.md) | `builder.kasuga.*` plugins, `@RegGenerator`/`@CodeTemplate` codegen, and `compileResources` |
| [Library Packaging, Shade Relocation, and Multiversion Structure Reference](library-packaging.md) | Root build conventions, `:modules:library` Shadow relocation, `neoforge.mods.toml`, and multiversion boundaries |

## Models, Skeletons, and Animation

| Document | Content |
| --- | --- |
| [Modelling Module](modelling/README.md) | Resource formats (`.bbmodel`/`.geo.json`/`.json`/`.obj`/`.mmd.zip`/`.glb`/`.mtb`/`.fmtb`/`.bob`/`.beo`/`.fmf`), `model_proxy`, lighting modes, global batching, instancing, and live sync |
| [Generic model posing and animation API](model-posing.md) | Model clip library, format integration, ModelPosing, static pose, IK channels, and API migration |
| [Model assembly and outfit cache API](model-assembly.md) | Part Builder, shared skeleton, hidden regions, reference remapping, LRU cache, and extension APIs |
| [Minecraft outfit resources and instance switching API](minecraft-model-assembly.md) | Resource-pack JSON, programmatic registration, handle swap, resource reload, and instance state migration |
| [Minecraft Model Components v1](mc-model-components.md) | Blockbench plugin data format, IK/physics/particle extensions, and the game-side loading contract |
| [Anchor and Minecraft equipment attachment](anchors.md) | Generic anchors, camera-relative coordinates, held/head items, custom equipment renderers, and lifecycle |
| [Skeleton Dynamics](skeleton-dynamics.md) | Generic rigid bodies/joints, Reader/Builder, IK limits and reverse mapping, diamond closure, instance control, and diagnostics |
| [Physics, IK, and ModelTickLoop API](physics.md) | Native world, compound shapes, joints, force/impulse, environment, drag, active ragdoll, anchors, and block physics |
| [PMX and glTF Ragdoll Configuration](mmd-ragdoll.md) | model manifest, profile fields, character limits, runtime deployment, and commands |
| [Camera Control and Animation Tracks](camera-animation.md) | Direct control, keyframes, JSON, shared timeline, and model clip binding |
| [State Machine and Animation Clip JSON Reference](fsm.md) | State variables, layers/states/transitions, pose schema, and host binding |
| [Model Variant Selection (Multiplexer) Reference](multiplexer.md) | Variant selection DSL, Builder, instance state, and FSM composition |
| [Real-Asset Wardrobe Swap and MC Equipment Example](../guides/wardrobe-example.md) | RibbonDress texture/model swap, MC held items, client screenshots, and performance comparison |
| [Integrating Content With the Proxy Kernel](../guides/proxy-integration.md) | Definition/type registration, automatic session attachment, typed ports, tick/frame, lifecycle, and troubleshooting |

## Rendering, Previews, and World UI

| Document | Content |
| --- | --- |
| [Kasuga Custom Rendering Specification](effect-rendering.md) | Owner scopes, world pipeline registration, shaders, and post-processing integration contract |
| [Java Shader DSL Reference](shader-dsl.md) | Java/GraalJS Shader DSL, GLSL emission, Gradle integration, and runtime parameters |
| [Managed Effects and Post-Processing](effects-and-postfx.md) | Managed effect pipelines, post-processing graphs, world-space weather filters, and diagnostic commands |
| [Built-in Effects, Water Simulation, and Spatial Audio Reference](builtin-effects-and-audio.md) | Black-hole lensing, shallow-water simulation and optics, positional spatial audio, and `/kasuga_effects` |
| [Sky Volumetric Clouds and Cumulonimbus Instances](volumetric-clouds.md) | Multi-layer sky configuration, single cloud instance, quality, preview, and verification entry |
| [UML rendering framework and backend interfaces](uml-render-framework.md) | backend/context/factory, the public interfaces and lifecycle contracts for scheduling and buffers |
| [Rendering backend integration reference](rendering-backends.md) | Standalone backend modules, host frame context, upload and output adaptation |
| [Iris Compatibility Layer Reference](iris-compat.md) | `modules/iris-compat` isolation, `CompatCapability` probing, mixin gating, and framebuffer/vertex bridges |
| [Model render scheduling (Render Scheduling)](render-scheduling.md) | McModelHandle, visibility, Entity/BlockEntity renderer integration |
| [Model Material Alpha Rendering Reference](render-alpha-passes.md) | Material passes, Iris OIT fallback, debug scenes, commands, keybinds, and acceptance metrics |
| [KasugaLib PMX → PBR Conversion](pbr.md) | Player JSON, Java conversion rules, context, priority, and diagnostic commands |
| [Model Depth Bias](model-depth-bias.md) | `DepthBias` policies, instance-level bias parameters, batching keys, and OpenGL backend behavior |
| [ItemModelRenderers / ItemModelRenderer](item-model-renderers.md) | `ItemModelRenderers` mapping, the shared BEWLR, the `builtin/entity` precondition, display transforms, and scheduling |
| [MC final frame offscreen output](frame-output.md) | Subscriptions, shared frames, MIRROR/OFFSCREEN_ONLY, exceptions, and closing |
| [Player / Free / Fixed Camera](cameras.md) | Camera types, creation, generic follow targets, entity binding, projection, and runtime render configuration |
| [Multi-camera output contract](multi-camera-output.md) / [Offscreen Output Walkthrough](../guides/offline-rendering.md) | Camera lifecycle, resource packs, standalone windows, startup, FBO consumption, and recording |
| [Minecraft structures and Create model previews](minecraft-model-preview.md) | Native model sources, structure NBT, Litematica/Forgematica, layer/exploded views and UI/scene post-processing |
| [UI model previews](ui-model-preview.md) | `GuiModelPreview` standalone instances, local depth canvas, and view controls |
| [World UI and Free-Cursor Input](world-ui-input.md) | Transparent input layer, screen unprojection, world panels, drag capture, and keyboard focus |
| [LDLib2 Compose Integration](ldlib-compose.md) | `external/uip/ldlib-compose` declarative world UI and client-local world panels |

## Data-Driven, Resources, Scripting, and Formula

| Document | Content |
| --- | --- |
| [Data-Driven Registration](data-driven/README.md) | Data-driven entry point: index, schema, extension, diagnostics, and reload (six reference pages + three guides) |
| [Data-driven JSON format specification (schema)](data-driven/schema.md) | Index manifest and content-file schema, built-in `proxy_model` / `proxy_model_be` params, STRICT/LEGACY, and D1–D6 rules |
| [Data-driven block-state properties](data-driven/block-state-properties.md) | Block-state declarations, vanilla properties, and custom factories |
| [Data-driven registration-phase API](data-driven/api-registration.md) | v2 multi-source build pipeline, host providers, snapshot sources, and legacy `JsonTreeBuilder` |
| [Data-driven extension API](data-driven/api-extension.md) | `TypeHandler`, `HandlerCapability`, `ValidationContext`, `FactoryRegistry`, and property parsers |
| [Data-Driven Diagnostics API](data-driven/api-diagnostics.md) | The `Diagnostics` facade, `/kasuga_data`, `BuildDiagnostic`, `/kasuga_proxy list`, and error classification |
| [Data-Driven Reload API](data-driven/api-reload.md) | `ReloadOrchestrator`, `ReloadHandler`, `ManagedReloadAllowlist`, and the assertable pure-function surface |
| [Unified Resource Platform and Script Tickets](resource-platform.md) | Source identity, content signatures, routing, initial MC discovery, generations, and asynchronous script blocks |
| [Virtual file systems](vfs.md) | `VirtualFileSystem`, `VfsPath`, directory/memory/ZIP backends, overlays, mounts, transactions, and MC pack bridges |
| [Scripting and Javet Runtime Reference](scripting.md) | `ScriptRuntime`, package scanning, `package.toml`, module resolution, `@Api` security, FSM bridge, and Javet backend |
| [Formula Module](formula/README.md) | Expressions, Namespace, functions/variables, operators, and errors |
| [Build and Verification Entries](../guides/verification.md) | Gradle tasks, standalone GL, client acceptance, and evidence boundaries |

## Create and Moving-Host Integration

| Document | Content |
| --- | --- |
| [Create Module Integration Reference](create-integration.md) | Create 6 track and bogey registration DSL, train devices, signal segments, bogey/contraption Proxy, and interaction shapes |
| [Scenes and UI on moving structures](moving-scene-hosts.md) | `SceneHost`, `CreateSceneHosts`, Create contraption local transforms, and UI attachment |
| [Aeronautics / Sable scene hosts](sable-scene-hosts.md) | `SableSceneHosts` adapting Sable `SubLevel` plot coordinates to `SceneModels` and world UI |

## Documentation Maintenance

Add package names, signatures, units, default values, error conditions, and lifecycle for new callable APIs in this directory, and link to the corresponding explanation.
Update call examples and configuration tables when changing an API. Update this index, the root README, relative links, and anchors when migrating or renaming.
Explanation docs may use type names, mathematical expressions, and diagrams to explain the mechanics; method references, configuration tables, and operating steps are maintained in this directory.
