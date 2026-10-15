package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("box3d")
class PhysicsJointTest {

    @Test
    void weldJointKeepsTwoBoxesAtTheirAuthoredRelativePose() {
        GenericRigidBody anchor = GenericRigidBody.kinematic(SimBody.SHAPE_BOX,
                new Vector3f(0.5f)).at(0f, 0f, 0f);
        GenericRigidBody crate = GenericRigidBody.box(new Vector3f(0.5f), 4f)
                .at(1.2f, 0f, 0f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(anchor, crate), List.of(), 1)) {
            world.setGravity(new Vector3f());
            WeldJoint weld = WeldJoint.atCurrentPose(anchor, crate).build();
            world.add(weld);

            float initialDistance = crate.positionRef().distance(anchor.positionRef());
            Quaternionf initialRotation = new Quaternionf(crate.rotationRef());

            world.applyImpulse(crate, new Vector3f(6f, 0f, 0f));
            for (int step = 0; step < 60; step++) world.step(1f / 60f, RigidBodyWorld.KinematicDriver.none());

            assertEquals(initialDistance, crate.positionRef().distance(anchor.positionRef()), 1e-3f,
                    "welded bodies must translate together without drifting apart");
            assertTrue(initialRotation.difference(crate.rotationRef()).angle() < 1e-2f,
                    "welded bodies must not rotate independently");
        }
    }

    @Test
    void distanceJointSpringSuspendsABodyNearItsRestLength() {
        GenericRigidBody anchor = GenericRigidBody.kinematic(SimBody.SHAPE_SPHERE,
                new Vector3f(0.1f)).at(0f, 4f, 0f);
        GenericRigidBody payload = GenericRigidBody.sphere(0.25f, 2f).at(0f, 2.5f, 0f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(anchor, payload), List.of(), 2)) {
            DistanceJoint rope = DistanceJoint.between(anchor, payload)
                    .length(1f)
                    .spring(true, 3f, 0.9f)
                    .limits(true, 0.5f, 1.5f)
                    .build();
            world.add(rope);
            world.setGravity(new Vector3f(0f, -9.80665f, 0f));

            for (int step = 0; step < 240; step++) world.step(1f / 120f, RigidBodyWorld.KinematicDriver.none());

            float hangDistance = anchor.positionRef().distance(payload.positionRef());
            assertTrue(hangDistance < 2.2f,
                    "the rope must catch the falling payload instead of free fall");
            assertTrue(rope.isBound(), "the backend must bind registered joints");
        }
    }

    @Test
    void revoluteJointLimitBlocksRotationPastItsBounds() {
        GenericRigidBody frame = GenericRigidBody.kinematic(SimBody.SHAPE_BOX,
                new Vector3f(0.5f)).at(0f, 0f, 0f);
        GenericRigidBody door = GenericRigidBody.box(new Vector3f(0.5f), 4f)
                .at(0f, 0f, 0f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(frame, door), List.of(), 2)) {
            world.setGravity(new Vector3f());
            RevoluteJoint hinge = RevoluteJoint.between(frame, door)
                    .hingeAxis(new Vector3f(0f, 1f, 0f))
                    .limits(true, -(float) Math.PI * 0.25f, (float) Math.PI * 0.25f)
                    .motor(true, -6f, 40f)
                    .build();
            world.add(hinge);

            for (int step = 0; step < 180; step++) world.step(1f / 120f, RigidBodyWorld.KinematicDriver.none());

            float yaw = Frames.euler(Frames.relative(
                            new Frames.Pose(frame.positionRef(), frame.rotationRef()),
                            new Frames.Pose(door.positionRef(), door.rotationRef())).rotation).y;
            assertTrue(yaw <= (float) Math.PI * 0.25f + 0.05f,
                    "the hinge must stop at its upper limit instead of spinning freely");
            assertFalse(door.linearVelocityRef().lengthSquared() > 1e6f);
        }
    }

    @Test
    void prismaticJointRestrictsMotionToItsSlideAxis() {
        GenericRigidBody rail = GenericRigidBody.kinematic(SimBody.SHAPE_BOX,
                new Vector3f(2f, 0.1f, 0.1f)).at(0f, 0f, 0f);
        GenericRigidBody slider = GenericRigidBody.box(new Vector3f(0.2f), 2f)
                .at(-1f, 0f, 0f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(rail, slider), List.of(), 2)) {
            world.setGravity(new Vector3f());
            PrismaticJoint piston = PrismaticJoint.between(rail, slider)
                    .slideAxis(new Vector3f(1f, 0f, 0f))
                    .limits(true, -1.2f, 1.2f)
                    .motor(true, 2f, 200f)
                    .build();
            world.add(piston);

            for (int step = 0; step < 240; step++) world.step(1f / 120f, RigidBodyWorld.KinematicDriver.none());

            Vector3f position = slider.positionRef();
            assertTrue(Math.abs(position.x) <= 1.25f,
                    "the slider must stop at its translation limit");
            assertTrue(Math.abs(position.y) < 1e-2f && Math.abs(position.z) < 1e-2f,
                    "the slider must not leave the slide axis");
        }
    }

    @Test
    void removingABodyDetachesItsGenericJointsToo() {
        GenericRigidBody first = GenericRigidBody.box(new Vector3f(0.5f), 4f).at(0f, 0f, 0f);
        GenericRigidBody second = GenericRigidBody.box(new Vector3f(0.5f), 4f).at(1f, 0f, 0f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(first, second), List.of(), 1)) {
            WeldJoint weld = WeldJoint.atCurrentPose(first, second).build();
            world.add(weld);
            assertEquals(1, world.physicsJoints().size());

            world.remove(second);

            assertTrue(world.physicsJoints().isEmpty(),
                    "joints attached to a removed body must be destroyed with it");
            assertFalse(weld.isBound());
        }
    }
}
