
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

See [api/EFFECT_RENDERING.md](api/EFFECT_RENDERING.md) for the API behavior, examples and the GLSL-to-Java
Shader DSL guide.

Camera movement, rotation, zoom and keyframe playback are documented in
[api/camera-animation.md](api/camera-animation.md).

Optional world-wide volumetric skies, placeable cumulonimbus volumes and standalone previews are documented in
[api/volumetric-clouds.md](api/volumetric-clouds.md).

Run the focused release gate with:

```bash
./gradlew renderingReleaseCheck
```

The manual and artifact checks for a release candidate are recorded in
[API validation guide](api/verification.md).


Documentation:
==========

- [API reference index](api/README.md): interfaces, builders, configuration schemas, examples and commands.
- [Architecture and principles](doc/README.md): data flow, ownership, algorithms and implementation boundaries.
- [Skeleton physics, IK and diamond constraints](api/skeleton-dynamics.md).
- [Model loading and rendering](api/modelling/README.md) / [中文](api/modelling/README.zh-CN.md).
- [Formula API](api/formula/README.md) / [中文](api/formula/README.zh-CN.md).
