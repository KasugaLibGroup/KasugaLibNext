# Explanations and Design

This directory explains algorithms, data flow, module boundaries, timing, and resource ownership. Concrete APIs, parameters, schemas, integration code, and commands are collected in [API reference](../reference/README.md).

| Topic | Explanation |
| --- | --- |
| Format Readers and common subsystems, pose composition, and outfit cache boundaries | [Model Subsystems](model-subsystems.md) |
| Shared skeleton definitions, Caliko reverse mapping, and diamond closure | [Skeleton Dynamics](skeleton-dynamics.md) |
| Fixed step, native solving, animation targets, and physics writeback | [Physics](physics.md) |
| Author physics, body profile, secondary motion, and large coordinates | [MMD/glTF](mmd-ragdoll.md) |
| Camera component tracks, optical zoom, and shared clock | [Camera Animation](camera-animation.md) |
| Layered states, actions, and pose blending | [FSM](fsm.md) |
| Shared variant definitions and per-instance selection state | [Multiplexer](multiplexer.md) |
| Descriptor/execution separation, shader preparation, effects, and post-processing graphs | [Custom Rendering](effect-rendering.md) |
| Layer responsibilities, mount publishing, and in-flight GPU reads | [UML Framework](uml-render-framework.md) |
| Core, graphics API, and host adaptation boundaries | [Rendering Backends](rendering-backends.md) |
| Vanilla visibility, per-view marks, and sampling deduplication | [Render Scheduling](render-scheduling.md) |
| Screen unprojection, world UI, and input ownership | [World UI Input](world-ui-input.en.md) |
| Final blit, read-only borrow, and shared capture | [Frame Output](frame-output.md) |
| Same-process camera view isolation, world subscription, and GPU synchronization | [Multi-Camera and Offscreen](offline-rendering-and-multi-cam.md) |
| Translucent passes, WBOIT, and depth compositing | [Alpha Rendering](render-alpha-passes.md) |
| Stylized textures, material variants, and generation-based caching | [PBR](pbr.md) |
| Weather density layers, ray marching, and shadowing | [Volumetric Clouds](volumetric-clouds.md) |
| Content tree, property conversion, factories, and registration dispatch | [Data-Driven Registration](data-driven-registration.md) |
