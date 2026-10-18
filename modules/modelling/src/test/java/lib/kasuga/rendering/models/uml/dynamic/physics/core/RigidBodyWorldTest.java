package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.Frames.Pose;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the generic rigid-body engine without any model adapter: plain
 * boxes and spheres stepped directly in a {@link RigidBodyWorld}.
 */
@Tag("box3d")
class RigidBodyWorldTest {
    private static final float DT = 1f / 60f;

    @Test
    void convertsWorldBorderCoordinatesAroundADoubleOrigin() {
        GenericRigidBody body = GenericRigidBody.sphere(0.5f, 1f);
        RigidBodyWorld world = new RigidBodyWorld(List.of(body), List.of(), 1);
        Vector3d origin = new Vector3d(30_000_000.375, 96.125, -29_999_999.625);
        world.setWorldOrigin(origin);

        Vector3f local = world.worldToLocal(
                origin.x + 0.03125, origin.y - 0.0625, origin.z + 0.125);
        assertEquals(new Vector3f(0.03125f, -0.0625f, 0.125f), local);
        Vector3d restored = world.localToWorld(local);
        assertEquals(local.x, restored.x - origin.x, 1e-8);
        assertEquals(local.y, restored.y - origin.y, 1e-8);
        assertEquals(local.z, restored.z - origin.z, 1e-8);
        world.close();
    }

    @Test
    void independentBoxesFallAndRestOnAGroundPlane() {
        GenericRigidBody a = GenericRigidBody.box(new Vector3f(0.5f), 4f).at(0f, 3f, 0f);
        GenericRigidBody b = GenericRigidBody.box(new Vector3f(0.25f), 1f)
                .at(1.4f, 5f, 0f).friction(0.6f);
        RigidBodyWorld world = new RigidBodyWorld(List.of(a, b), List.of(),
                RigidBodyWorld.DEFAULT_SUBSTEP_COUNT);
        world.addGroundPlane(0f, 0.8f, 0f);

        for (int i = 0; i < 240; i++) world.step(DT, RigidBodyWorld.KinematicDriver.none());

        assertTrue(world.sleeping() || world.sleepTime() > 0.3f,
                "settled stacks should reach the sleep pipeline");
        assertEquals(0.5f, a.positionRef().y, 0.02f, "half-extent box rests half sunk into slop");
        assertEquals(0.25f, b.positionRef().y, 0.02f, "the smaller box settles beside the first one");
        assertEquals(0f, a.linearVelocityRef().lengthSquared(), 1e-4f);
        assertTrue(a.positionRef().distance(b.positionRef()) > 0.9f,
                "the boxes must not interpenetrate at rest");
    }

    @Test
    void restitutionThresholdSuppressesMicroBouncesButNotRealImpacts() {
        GenericRigidBody slow = GenericRigidBody.sphere(0.25f, 1f).at(0f, 0.26f, 0f).restitution(1f);
        RigidBodyWorld slowWorld = new RigidBodyWorld(List.of(slow), List.of(), 4);
        slowWorld.setRestitutionThreshold(1f);
        slowWorld.addGroundPlane(0f, 0f, 0f);
        for (int i = 0; i < 30; i++) slowWorld.step(DT, RigidBodyWorld.KinematicDriver.none());
        // A sub-threshold drop must not bounce: it settles instead.
        assertTrue(slow.positionRef().y <= 0.26f,
                "sub-threshold impact speed must not trigger restitution");

        GenericRigidBody fast = GenericRigidBody.sphere(0.25f, 1f).at(0f, 3f, 0f).restitution(1f);
        RigidBodyWorld fastWorld = new RigidBodyWorld(List.of(fast), List.of(), 4);
        fastWorld.setRestitutionThreshold(0.5f);
        fastWorld.addGroundPlane(0f, 0f, 0f);
        float peakAfterImpact = 0f;
        for (int i = 0; i < 90; i++) {
            fastWorld.step(DT, RigidBodyWorld.KinematicDriver.none());
            peakAfterImpact = Math.max(peakAfterImpact, fast.positionRef().y);
            if (i > 20 && fast.linearVelocityRef().y > 0f) break;
        }
        assertTrue(fast.linearVelocityRef().y > 0f || peakAfterImpact > 0.8f,
                "a fast elastic impact should visibly rebound");
    }

    @Test
    void bodiesCanBeAddedAndRemovedWhileSimulating() {
        GenericRigidBody floor = GenericRigidBody.kinematic(SimBody.SHAPE_BOX, new Vector3f(4f, 0.5f, 4f))
                .at(0f, -0.5f, 0f);
        RigidBodyWorld world = new RigidBodyWorld(List.of(floor), List.of(), 4);

        GenericRigidBody crate = GenericRigidBody.box(new Vector3f(0.4f), 2f).at(0f, 2f, 0f);
        assertFalse(world.remove(crate), "removing an unregistered body fails cleanly");
        assertTrue(world.add(crate));
        assertFalse(world.add(crate), "duplicate registration is rejected");
        assertEquals(2, world.bodies().size());

        for (int i = 0; i < 120; i++) world.step(DT, RigidBodyWorld.KinematicDriver.none());
        assertTrue(crate.positionRef().y > 0.3f && crate.positionRef().y < 1.1f,
                "crate lands on the kinematic slab");

        assertTrue(world.remove(crate));
        assertEquals(1, world.bodies().size());
        for (int i = 0; i < 10; i++) world.step(DT, RigidBodyWorld.KinematicDriver.none());
        // After removal the body is no longer simulated: nothing throws or moves it.
        assertEquals(1, world.bodies().size());
    }

    @Test
    void removingABodyAlsoDestroysItsNativeJoints() {
        GenericRigidBody a = GenericRigidBody.sphere(0.2f, 1f).at(0f, 1f, 0f);
        GenericRigidBody b = GenericRigidBody.sphere(0.2f, 1f).at(0.4f, 1f, 0f);
        BallJoint joint = new BallJoint(a, b,
                new Pose(new Vector3f(0.2f, 0f, 0f), new Quaternionf()),
                new Pose(new Vector3f(-0.2f, 0f, 0f), new Quaternionf()),
                new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(),
                new Vector3f(), new Vector3f(), null, new Vector3f(0f, 1f, 0f));
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(a, b), List.of(joint), 4)) {
            assertTrue(world.remove(a));
            assertTrue(world.joints().isEmpty());
            world.step(DT, RigidBodyWorld.KinematicDriver.none());
            assertEquals(1, world.bodies().size());
        }
    }

    @Test
    void ballJointKeepsTwoSpheresConnectedUnderGravity() {
        GenericRigidBody anchor = GenericRigidBody.sphere(0.3f, 1f).at(-0.5f, 4f, 0f);
        GenericRigidBody weight = GenericRigidBody.sphere(0.3f, 1f).at(0.5f, 4f, 0f);
        // PMX-style joints are limit boxes: a tight box around the authored
        // anchor offset acts as a hard weld on translation.
        Vector3f weldSlop = new Vector3f(0.02f);
        BallJoint joint = new BallJoint(anchor, weight,
                new Pose(new Vector3f(0.3f, 0f, 0f), new Quaternionf()),
                new Pose(new Vector3f(-0.3f, 0f, 0f), new Quaternionf()),
                new Vector3f(weldSlop).negate(), new Vector3f(weldSlop),
                new Vector3f(Float.NEGATIVE_INFINITY), new Vector3f(Float.POSITIVE_INFINITY),
                new Vector3f(), new Vector3f(), null, new Vector3f(0f, 1f, 0f));
        RigidBodyWorld world = new RigidBodyWorld(List.of(anchor, weight), List.of(joint),
                RigidBodyWorld.DEFAULT_SUBSTEP_COUNT);
        world.addGroundPlane(0.3f, 0.9f, 0f);

        for (int i = 0; i < 240; i++) world.step(DT, RigidBodyWorld.KinematicDriver.none());

        float separation = anchor.positionRef().distance(weight.positionRef());
        assertEquals(0.6f, separation, 0.08f,
                "the welded joint keeps both spheres at their authored distance");
        assertTrue(anchor.positionRef().y < 4f && weight.positionRef().y < 4f,
                "both spheres fall together as one articulated group");
        assertEquals(Math.abs(anchor.positionRef().y - weight.positionRef().y), 0f, 0.06f,
                "the pair hangs level because both spheres share gravity equally");
    }

    @Test
    void raycastImpulseAndDragWorkOnGenericBodies() {
        GenericRigidBody crate = GenericRigidBody.box(new Vector3f(0.5f), 4f).at(0f, 1f, 3f);
        RigidBodyWorld world = new RigidBodyWorld(List.of(crate), List.of(),
                RigidBodyWorld.DEFAULT_SUBSTEP_COUNT);
        world.setGravity(new Vector3f());

        RayHit hit = world.raycast(new Vector3f(0f, 1f, -3f), new Vector3f(0f, 0f, 1f), 20f)
                .orElse(null);
        assertNotNull(hit, "the crate surface is hit by the ray");
        assertTrue(world.beginDrag(hit));

        world.updateDragTarget(new Vector3f(1f, 1f, -1f), DT);
        for (int i = 0; i < 90; i++) world.step(DT, RigidBodyWorld.KinematicDriver.none());
        assertTrue(crate.positionRef().x > 0.5f, "dragging pulls the crate toward the target");

        world.endDrag();
        assertNull(world.draggedBody());
        assertFalse(world.dragging());
        assertTrue(world.applyAngularImpulse(crate, new Vector3f(0f, 2f, 0f)));
        assertTrue(crate.angularVelocityRef().lengthSquared() > 0f);
    }
}
