package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.BodyContact;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.GenericRigidBody;
import lib.kasuga.rendering.models.uml.dynamic.physics.core.SimBody;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MinecraftBlockPlayerContactsTest {

    private static final SimBody PROXY = GenericRigidBody.box(new Vector3f(0.3f), 4f);
    private static final SimBody PROP = GenericRigidBody.box(new Vector3f(0.5f), 4f);

    private static BodyContact contact(float nx, float ny, float nz,
                                       float separation, boolean touching) {
        return new BodyContact(PROXY, Optional.of(PROP),
                new Vector3f(), new Vector3f(nx, ny, nz).normalize(),
                touching ? separation : 0.05f,
                touching ? 1f : 0f, touching ? 1f : 0f, 0f);
    }

    @Test
    void topContactLiftsVerticallyWithoutAnyHorizontalDrift() {
        Vec3 correction = MinecraftBlockPhysics.playerCorrection(
                List.of(contact(0f, 1f, 0f, -0.08f, true)));

        assertEquals(0d, correction.x, "tangential slide from the top face must be discarded");
        assertEquals(0d, correction.z, "tangential slide from the top face must be discarded");
        assertEquals(0.081d, correction.y, 1e-6);
    }

    @Test
    void sideContactStopsHorizontalWalkingLikeAWall() {
        Vec3 correction = MinecraftBlockPhysics.playerCorrection(
                List.of(contact(1f, 0f, 0f, -0.06f, true)));

        assertEquals(0.061d, correction.x, 1e-6, "the push-back must stop forward motion");
        assertEquals(0d, correction.y, 1e-6);
        assertEquals(0d, correction.z, 1e-6);
    }

    @Test
    void deepOrStaleManifoldsAreClampedPerAxis() {
        Vec3 correction = MinecraftBlockPhysics.playerCorrection(
                List.of(contact(0.7f, 0.1f, -0.7f, -5f, true)));

        assertTrue(Math.abs(correction.x) <= MinecraftBlockPhysics.MAX_CORRECTION + 1e-6);
        assertTrue(Math.abs(correction.y) <= MinecraftBlockPhysics.MAX_CORRECTION + 1e-6);
        assertTrue(Math.abs(correction.z) <= MinecraftBlockPhysics.MAX_CORRECTION + 1e-6);
        // Direction is preserved: the horizontal push dominates as the normal dictates.
        assertEquals(0d, correction.y, 0.02f);
        assertEquals(-correction.x, correction.z, 1e-6);
        assertTrue(correction.x > MinecraftBlockPhysics.MAX_CORRECTION * 0.6f,
                "the capped magnitude must still push meaningfully along the normal");
    }

    @Test
    void supportRequiresATouchingUpwardContact() {
        List<BodyContact> contacts = List.of(
                contact(0f, 1f, 0f, -0.01f, true),
                contact(1f, 0f, 0f, -0.02f, true));
        assertTrue(MinecraftBlockPhysics.supportedOnProps(contacts));

        assertFalse(MinecraftBlockPhysics.supportedOnProps(List.of(
                        contact(0f, 1f, 0f, 0.05f, false))),
                "a separated manifold is not support");

        assertFalse(MinecraftBlockPhysics.supportedOnProps(List.of(
                        contact(0f, -1f, 0f, -0.02f, true))),
                "a ceiling is not ground");
    }

    @Test
    void velocityClipRemovesOnlyTheIntoSurfaceComponent() {
        List<BodyContact> floor = List.of(contact(0f, 1f, 0f, -0.01f, true));

        Vec3 fallen = MinecraftBlockPhysics.clipVelocityAgainstContacts(
                new Vec3(0d, -0.5d, 0d), floor);
        assertEquals(0d, fallen.x, 1e-9);
        assertEquals(0d, fallen.y, 1e-6);
        assertEquals(0d, fallen.z, 1e-9);

        Vec3 strafing = MinecraftBlockPhysics.clipVelocityAgainstContacts(
                new Vec3(1d, -0.5d, 0d), floor);
        assertEquals(1d, strafing.x, 1e-6, "ground movement must keep its horizontal speed");
        assertEquals(0d, strafing.y, 1e-6);

        // Diagonal landing keeps lateral motion while killing the fall — this is
        // what makes walking onto a prop stack feel like vanilla ground.
        Vec3 diagonal = MinecraftBlockPhysics.clipVelocityAgainstContacts(
                new Vec3(0.2d, -1.5d, -0.3d), floor);
        assertEquals(0.2d, diagonal.x, 1e-6,
                "contact response removes falling speed instead of adding totalNormalImpulse");
        assertEquals(-0.3d, diagonal.z, 1e-6);
    }

    // ---- migrated from MinecraftBlockRigidBodyTest (player-contact belongs here) ----

    @Test
    void playerContactClipsInwardVelocityWithoutReapplyingBox3dImpulse() {
        List<BodyContact> floor = List.of(
                contact(0f, 1f, 0f, -0.04f, true));

        Vec3 clipped = MinecraftBlockPhysics.clipVelocityAgainstContacts(
                new Vec3(0.2d, -1.5d, -0.3d), floor);
        assertEquals(0.2d, clipped.x, 1e-6d);
        assertEquals(0d, clipped.y, 1e-6d,
                "contact response removes falling speed instead of adding totalNormalImpulse");
        assertEquals(-0.3d, clipped.z, 1e-6d);
        Vec3 correction = MinecraftBlockPhysics.playerCorrection(floor);
        assertEquals(0d, correction.x, 1e-6d);
        assertEquals(0.041d, correction.y, 1e-6d);
        assertEquals(0d, correction.z, 1e-6d);
    }

    @Test
    void playerPenetrationUsesOneDeepestNormalAndIsCapped() {
        BodyContact shallowWall = contact(1f, 0f, 0f, -0.03f, true);
        BodyContact deepFloor = contact(0f, 1f, 0f, -0.4f, true);

        // Deepest contact wins; the per-axis cap keeps a stale manifold from
        // teleporting the player while Box3D moves the prop out of overlap.
        assertEquals(new Vec3(0d, MinecraftBlockPhysics.MAX_CORRECTION, 0d),
                MinecraftBlockPhysics.playerCorrection(List.of(shallowWall, deepFloor)));
    }

    // ---- combined-manifold behavior of the rewritten resolve pass ----

    @Test
    void standingOnAStackWithAWallBesideLiftsAndStopsWithoutDrift() {
        List<BodyContact> contacts = List.of(
                contact(0f, 1f, 0f, -0.08f, true),   // ground support below
                contact(1f, 0f, 0f, -0.03f, true));  // wall walked into

        assertTrue(MinecraftBlockPhysics.supportedOnProps(contacts));
        Vec3 correction = MinecraftBlockPhysics.playerCorrection(contacts);
        // Deepest is the floor: vertical lift only; no tangential drift, and the
        // shallower side manifold does not combine into a diagonal push.
        assertEquals(0d, correction.x, 1e-9);
        assertEquals(0.081d, correction.y, 1e-6);
        assertEquals(0d, correction.z, 1e-9);
    }

    @Test
    void separatedOnlyManifoldYieldsNoCorrection() {
        Vec3 correction = MinecraftBlockPhysics.playerCorrection(
                List.of(contact(0f, 1f, 0f, 0.05f, false)));
        assertEquals(Vec3.ZERO, correction);
        assertFalse(MinecraftBlockPhysics.supportedOnProps(
                List.of(contact(0f, 1f, 0f, 0.05f, false))));
    }
}
