# Box3D integration

`BOX3D_VERSION` records the upstream repository, branch and exact source commit.
The vendored `CMakeLists.txt`, `include`, `src` and `LICENSE` come from that commit.
The JNI bridge and parent CMake project are maintained locally.

## Local patch: fast convex contact recycling

Upstream commit `954cf879e717334eb96da9a5c255788fdc9f5f87` (#157) changed
the contact-recycling gate from excluding fast mesh contacts to excluding all
fast contacts. Small convex bodies can be classified as fast while their motion
remains within the existing manifold-reuse tolerance.

`box3d/src/physics_world.c` retains the earlier convex-contact gate. It leaves
fast mesh contacts on the fresh-manifold path and preserves all rotation,
translation and conservative-motion checks inside the recycling path. No
solver strength, model profile, simulation step or test tolerance is changed.

Controlled comparisons on #157 and the recorded upstream revision isolated
the glTF ragdoll resting-limit regression to this gate: restoring this condition
alone makes the original 3-degree limit test pass. This establishes the
behavioral trigger; it does not establish a defect in the joint equations.

Regression coverage uses `NativeBox3DTest` for a small moving capsule on convex
and mesh floors, `Box3DCollisionIntegrationTest` for high-speed collision, and
the unchanged `GltfLoaderTest` high-drop scene when its optional models exist.

Run `./gradlew :modules:modelling:modelUnitTest` from the repository root.
When updating the vendor again, review this patch and compare the remaining
files with the pinned upstream commit.
