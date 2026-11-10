# Box3D integration

`BOX3D_VERSION` records the upstream repository, branch and exact source commit.
The entire `box3d` directory is an immutable copy of that commit's vendored
`CMakeLists.txt`, `include`, `src` and `LICENSE`. Do not patch, reformat or apply
build-time substitutions to upstream files. Adaptations belong in Kasuga's
JNI bridge, parent CMake project or Java adapter, using public Box3D APIs.

## Source verification

`BOX3D_SOURCES.json` records SHA-256 hashes of all 113 files from the pinned
official Git tree. `:modules:modelling:verifyBox3DUpstream` checks the version,
complete file set and file contents without network access. It runs before
native configuration and as part of `check`; changed, extra or missing files
fail the build. When updating, regenerate the manifest from the new official
commit, not from a locally modified vendor directory.

## Passive ragdoll profiles

The upstream update exposed a local ragdoll-policy error. Generated profiles
implicitly enabled a 2 Hz alignment spring toward each joint's bind rotation,
including in the skeleton's RELAXED state. An independent force-free test
showed that a legal bend moved toward bind pose despite no gravity, contact or
limit violation. In the glTF high-drop scene this extra drive also affected
the resting-limit result (approximately 3.93 degrees before the correction).

Generated profiles now leave alignment springs disabled. Cone/twist limits,
joint friction and explicit pose drives remain enabled by their existing
policies; custom SimBody implementations can still explicitly opt into an
alignment spring. No model JSON, solver strength, simulation step or test
tolerance is changed. The unchanged `GltfLoaderTest` passes its original
3-degree high-drop check for both optional models with unmodified upstream
source. `SkeletonRagdollTest` also covers force-free passive bends at three
segment lengths, without requiring private model assets.

`NativeBox3DTest` checks moving capsules on convex and mesh floors for continued
contact and no tunnelling, without asserting an upstream caching strategy.
`Box3DCollisionIntegrationTest` covers high-speed collisions. Run
`./gradlew :modules:modelling:modelUnitTest` from the repository root.
