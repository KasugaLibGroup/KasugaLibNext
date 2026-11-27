# Guides

This section holds recipes and examples for readers arriving with a concrete task: verifying builds, swapping outfits, composing world displays and lights, running offscreen multi-camera output, integrating model content and the Proxy Kernel, and walking through data-driven and Create multi-bogey carriage workflows.

| Page | Content |
| --- | --- |
| [Build and Verification Entries](verification.md) | Gradle tasks, standalone GL, client acceptance, and evidence boundaries |
| [Real-Asset Wardrobe Swap and MC Equipment Example](wardrobe-example.md) | RibbonDress texture/model swap, MC held items, client screenshots, and performance comparison |
| [Content Integration: Block and Item Model Resources](model-content.md) | blockstate variant rules, the placeholder model's role, the two item-model forms, three-step BEWLR setup, and the display transform table |
| [Integrating Content With the Proxy Kernel](proxy-integration.md) | Define ports, assemble a composition, register a session, attach automatically, manage lifecycle, and troubleshoot |
| [Blueprints and Nestable World Displays](world-display.md) | `WorldDisplayTarget`, `ModelDisplayTarget`, blueprint import, world panels, and nested display scenes |
| [World-Space Lights](world-lights.md) | Dynamic point lights, rectangular area lights, spherical volumetric lights, color temperature, and live-world demos |
| [Model Collision, Chunk Lifecycle, and Distant Rendering](world-model-collision-and-lod.md) | Animated entity collision, chunk unload/reattach lifecycle, Create contraption LOD, and verification entry points |
| [Offscreen Output and Multi-Camera Rendering](offline-rendering.md) | API, startup, and operating reference; standalone windows and FBO consumption |
| [Nebula Warp Flight Demo](nebula-demo.md) | Managed MRT, OBJ/PBR, volumetric smoke, shared camera clock, and 4K60 recording |
| [Data-driven getting started: from an empty directory to a moving block](data-driven/intro.md) | From an empty directory to a block that moves: the two phases, the minimal path, and common errors |
| [Path 1: adding content with data-driven files (organization and best practices)](data-driven/guide-content.md) | Add blocks, items, groups, state machines, and animation clips with JSON alone, without writing Java |
| [Extension guide: what you have to write Java for (How-to)](data-driven/guide-extension.md) | Custom TypeHandlers and adding capabilities to data-driven |
| [Multi-Bogey Train Carriages](create/multi-bogey-carriage.md) | Switches and configs, building a multi-bogey structure, curve tracking and derailing, and changing defaults from other mods |
| [Carriage Vertical Posture](create/carriage-posture.md) | The posture event, config limits, server-authoritative values, and client smoothing |
