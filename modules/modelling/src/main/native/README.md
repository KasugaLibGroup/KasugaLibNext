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

## Known integration regression

The unmodified `336263320469e5e9febfe18697ab562488f7e97a` revision exposes a
glTF high-drop resting-limit regression: Maribel's spine joint exceeds the
existing 3-degree tolerance (approximately 3.93 degrees in the recorded run).
Bisecting isolated the behavioral trigger to the contact-recycling gate in
upstream commit `954cf879e717334eb96da9a5c255788fdc9f5f87` (#157). This does
not establish a defect in joint equations or in Kasuga's frame mapping.

The earlier local vendor compatibility patch has been removed. No solver
strength, model profile, simulation step or test tolerance is changed to hide
the regression. The unchanged `GltfLoaderTest` still checks the original limit
when its optional model fixtures exist. Until an integration-layer correction
is independently established, this regression remains unresolved.

`NativeBox3DTest` checks moving capsules on convex and mesh floors for continued
contact and no tunnelling, without asserting an upstream caching strategy.
`Box3DCollisionIntegrationTest` covers high-speed collisions. Run
`./gradlew :modules:modelling:modelUnitTest` from the repository root.
