# How MMD and glTF Ragdoll Adaptation Works

For profile, manifest, runtime deployment, and commands see the [API reference](../reference/mmd-ragdoll.md).
For the generic data and the Caliko structures see [Skeleton Dynamics](skeleton-dynamics.md).

## Reading Layer and Generic Runtime

The MMD Reader converts the author's rigid bodies, joints, and IK into generic skeleton definitions, and keeps the raw
tables available for format access.
glTF provides no standard rigid body/IK tables, so the Reader only keeps physics candidates arranged by node; the human
topology has to be described explicitly.
Both enter the same skeleton runtime; bone references, writeback strategy, and scale information travel with the
definitions, so the model format is not re-decided during the physics step.

## Author Physics and the Body Profile

Using the author's rigid bodies directly suits secondary motion such as skirts, hair, and accessories. The body profile
generates capsules, semantic masses, and joints from the actual bone segments, and replaces author shapes that are not
meant for human collision with body colliders.
Equal-length bone segments pick their direction by a stable bone order, so the object allocation order does not change
the collider orientation.

Unregistered secondary-motion chains can keep their original shape, damping, and filtering relationships. An attachment
point on the main body drives the secondary-motion chains one-way through a kinematic proxy that moves with the main
body, with the filter layer isolated from the main body, which keeps a large number of skirt/hair rigid bodies from
dragging the main body backward.
Under hybrid mode, secondary-motion bones keep the animated translation and write back only the physics rotation, so a
change of reference frame does not stretch the bone segments.

## Scale and Large Coordinates

Vertices, bones, rigid body shapes, and joint positions must use a consistent import scale; gravity always stays in world
units and must not shrink with the model scale, otherwise a small character falls in the wrong slow motion. A glTF affine
bone hierarchy also keeps scale/shear, so writing back the physics TR requires computing the local pose within the full
parent transform, to avoid applying the parent scale twice.

World translation is stored in a separate double anchor. Bones and native physics use float coordinates near the origin;
at render time the difference between the world anchor and the camera is computed in double first, and only then written
into the float matrix. This way short bone segments and small colliders are not quantized at large coordinates.

## Native Joints and Environment Boundaries

Box3D handles integration, contact, CCD, sleeping, drag, and joint solving. Author spheres/boxes/capsules map to native
shapes, and the Minecraft environment is connected through incremental static colliders. An infinite plane is
approximated by a large thin box placed along its normal.
PMX six-axis joints are currently approximated as spherical cone/twist, and the three-axis translation is tightened to a
fixed anchor; the various author joint types are not fully distinguished.
Per-vertex soft bodies and fluid buoyancy are not integrated. A dimension change, leaving the world, and deployment
teardown all need to release the environment, the drag, and the native resources.
