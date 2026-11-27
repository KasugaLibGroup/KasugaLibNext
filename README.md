
Installation information
=======

This template repository can be directly cloned to get you started with a new
mod. Simply create a new repository cloned from this one, by following the
instructions provided by [GitHub](https://docs.github.com/en/repositories/creating-and-managing-repositories/creating-a-repository-from-a-template).

Once you have your clone, simply open the repository in the IDE of your choice. The usual recommendation for an IDE is either IntelliJ IDEA or Eclipse.

If at any point you are missing libraries in your IDE, or you've run into problems you can
run `gradlew --refresh-dependencies` to refresh the local cache. `gradlew clean` to reset everything
{this does not affect your code} and then start the process again.

Mapping Names:
============
By default, the MDK is configured to use the official mapping names from Mojang for methods and fields
in the Minecraft codebase. These names are covered by a specific license. All modders should be aware of this
license. For the latest license text, refer to the mapping file itself, or the reference copy here:
https://github.com/NeoForged/NeoForm/blob/main/Mojang.md

Additional Resources:
==========
Community Documentation: https://docs.neoforged.net/  
NeoForged Discord: https://discord.neoforged.net/

Custom rendering:
==========
The custom rendering surface includes owner-scoped world pipelines, managed effects,
post-processing graphs, reload-safe resource/generated shaders, the imperative Java Shader DSL,
background shader preparation and schema-driven runtime parameters.

See [doc/reference/effect-rendering.md](doc/reference/effect-rendering.md) for the API behavior, examples and the GLSL-to-Java
Shader DSL guide.

The optional `modules:shader-graal` module adds a GraalJS frontend that constructs the same typed
shader programs. See [GraalJS scripting](doc/reference/shader-dsl.en.md#graaljs-scripting) for dependencies and examples.

Camera movement, rotation, zoom and keyframe playback are documented in
[doc/reference/camera-animation.md](doc/reference/camera-animation.md). Player / Free / Fixed creation and entity following are documented in
[doc/reference/cameras.md](doc/reference/cameras.md).

World-space UI panels and transparent free-cursor input are documented in
[doc/reference/world-ui-input.md](doc/reference/world-ui-input.md).

Optional world-wide volumetric skies, placeable cumulonimbus volumes and standalone previews are documented in
[doc/reference/volumetric-clouds.md](doc/reference/volumetric-clouds.md).

The [Nebula demo](doc/guides/nebula-demo.md) combines graph-managed MRT, OBJ/PBR assets,
volumetric warp effects and shared camera animation, with deterministic 4K60 recording.

Run the focused release gate with:

```bash
./gradlew renderingReleaseCheck
```

The manual and artifact checks for a release candidate are recorded in
[API validation guide](doc/guides/verification.md).


Documentation:
==========

- [Documentation home](doc/README.en.md) / [中文](doc/README.md): overview of getting started, guides, API references, explanations and project metadata.
- [Getting started](doc/getting-started/README.en.md) / [中文](doc/getting-started/README.md): dependencies, minimal example project, and installation/deployment.
- [Guides](doc/guides/README.en.md) / [中文](doc/guides/README.md): data-driven walkthroughs, world display, world lights, model collision/LOD, proxy integration, Nebula demo and verification.
- [API reference index](doc/reference/README.en.md) / [中文](doc/reference/README.md): interfaces, builders, configuration schemas, examples and commands.
  - Core, DI & build: [Core entry & registration](doc/reference/core-registration.en.md), [Core runtime](doc/reference/core-runtime.en.md), [Inject DI](doc/reference/inject.en.md), [Utils](doc/reference/utils.en.md), [Scripting & Javet](doc/reference/scripting.en.md), [Gradle plugins & codegen](doc/reference/gradle-and-codegen.en.md), [Library packaging](doc/reference/library-packaging.en.md).
  - Create integration: [Create module reference](doc/reference/create-integration.en.md), [Moving scene hosts](doc/reference/moving-scene-hosts.en.md), [Sable scene hosts](doc/reference/sable-scene-hosts.en.md).
- [Architecture and principles](doc/explanation/README.en.md) / [中文](doc/explanation/README.md): data flow, ownership, algorithms, threading model and implementation boundaries.
- [Proxy kernel and content integration](doc/explanation/proxy/README.en.md): sessions, typed ports, lifecycle, and a practical integration guide.
- [Skeleton physics, IK and diamond closures](doc/reference/skeleton-dynamics.en.md).
- [Model loading and rendering](doc/reference/modelling/README.en.md) / [中文](doc/reference/modelling/README.md).
- [Formula API](doc/reference/formula/README.en.md) / [中文](doc/reference/formula/README.md).
- [Project & version metadata](doc/meta/README.en.md) / [中文](doc/meta/README.md): compatibility matrix, changelog, licensing, limitations and glossary.
