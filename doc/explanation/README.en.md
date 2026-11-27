# Explanations and Design

This directory explains algorithms, data flow, module boundaries, timing, and resource ownership. Concrete APIs, parameters, schemas, integration code, and commands are collected in [API reference](../reference/README.md).

## Architecture and Threading

| Topic | Explanation |
| --- | --- |
| Thread confinement, bounded/lock-free cross-thread handoffs, and generational atomic publication | [Threading Model](threads.md) |

## Models, Physics, Animation, and Proxy

| Topic | Explanation |
| --- | --- |
| Format Readers and common subsystems, pose composition, and outfit cache boundaries | [Model Subsystems](model-subsystems.md) |
| Shared skeleton definitions, Caliko reverse mapping, and diamond closure | [Skeleton Dynamics](skeleton-dynamics.md) |
| Fixed step, native solving, animation targets, and physics writeback | [Physics](physics.md) |
| Author physics, body profile, secondary motion, and large coordinates | [MMD/glTF](mmd-ragdoll.md) |
| Camera component tracks, optical zoom, and shared clock | [Camera Animation](camera-animation.md) |
| Layered states, actions, and pose blending | [FSM](fsm.md) |
| Shared variant definitions and per-instance selection state | [Multiplexer](multiplexer.md) |
| Logical time, input/control transactions, and same-generation publication | [Animation Scope](animation/animation-scope.md) |
| Typed output slots, continuity, and safe rebinding | [Animation Target](animation/animation-target.md) |
| Element adaptation, registration/session ownership, typed ports, tick/frame boundaries, and lifecycle | [Proxy](proxy/README.md) |

## Rendering and Output

| Topic | Explanation |
| --- | --- |
| Descriptor/execution separation, shader preparation, effects, and post-processing graphs | [Custom Rendering](effect-rendering.md) |
| Layer responsibilities, mount publishing, and in-flight GPU reads | [UML Framework](uml-render-framework.md) |
| Core, graphics API, and host adaptation boundaries | [Rendering Backends](rendering-backends.md) |
| Vanilla visibility, per-view marks, and sampling deduplication | [Render Scheduling](render-scheduling.md) |
| Screen unprojection, world UI, and input ownership | [World UI Input](world-ui-input.md) |
| Final blit, read-only borrow, and shared capture | [Frame Output](frame-output.md) |
| Same-process camera view isolation, world subscription, and GPU synchronization | [Multi-Camera and Offscreen](offline-rendering-and-multi-cam.md) |
| Translucent passes, WBOIT, and depth compositing | [Alpha Rendering](render-alpha-passes.md) |
| Stylized textures, material variants, and generation-based caching | [PBR](pbr.md) |
| Weather density layers, ray marching, and shadowing | [Volumetric Clouds](volumetric-clouds.md) |

## Data-Driven System

| Topic | Explanation |
| --- | --- |
| Content tree, property conversion, factories, and registration dispatch overview | [Data-Driven Registration](data-driven-registration.md) |
| v2 fixed sources, dual registration entry points, four data domains, and key invariants | [KasugaLib Data-Driven System](data-driven/README.md) |
| Fixed sources, registration domain, reload domain, `prepare`/`build`, and startup ordering | [Concepts and Lifecycle](data-driven/01-concepts-lifecycle.md) |
| Standard layout, scene indexing, main/parcel roots, manifests, and strict JSON | [Index and JSON Format](data-driven/02-index-and-content-format.md) |
| `TypeHandler` lifecycle, identity, dependency visibility, validation, and registry claims | [TypeHandler and Preflight](data-driven/03-handlers-and-preflight.md) |
| `ReloadOrchestrator`, pack-stack discovery, FSM handlers, deduplication, and diagnostics | [ReloadHandler and Resource Reload](data-driven/04-reload-pipeline.md) |
| `ResourceSource`, `ResourceSnapshot`, `ContentSourceFactory.snapshot`, limits, and close semantics | [Content Sources, Snapshots, and Security](data-driven/05-content-sources.md) |
| Global rank, `prepare`/`build`, LEGACY/STRICT, diagnostics, finalizers, and providers | [Multi-Source Build Pipeline](data-driven/06-build-pipeline.md) |
| `FactoryRegistry`, derived claims, `dispatchWhen`, fixed roots, and MC resource projection | [Factories, Registry Groups, and Host Integration](data-driven/07-factories-groups-integration.md) |
| Common symptoms, error diagnosis, legacy entry compatibility, and migration checklist | [Troubleshooting and Migration](data-driven/08-troubleshooting-migration.md) |

## Create Integration

| Topic | Explanation |
| --- | --- |
| Create station assembly, multi-bogey capture, collapse, persistence, and sync, curve tracking, and derailing | [Multi-Bogey Carriages](create/multi-bogey-carriage.md) |
| Posture event, authoritative carriage state, client chasing, persistence, and render consumers | [Carriage Vertical Posture](create/carriage-posture.md) |
