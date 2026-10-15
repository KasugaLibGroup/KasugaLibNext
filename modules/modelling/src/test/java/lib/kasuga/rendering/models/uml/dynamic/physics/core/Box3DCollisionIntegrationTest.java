package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("box3d")
class Box3DCollisionIntegrationTest {
    private static final float DT = 1f / 120f;

    @Test
    void sphereBoxAndCapsuleSettleOnNativeGround() {
        GenericRigidBody sphere = GenericRigidBody.sphere(0.3f, 1f).at(-1f, 3f, 0f);
        GenericRigidBody box = GenericRigidBody.box(new Vector3f(0.3f), 1f).at(0f, 3f, 0f);
        GenericRigidBody capsule = GenericRigidBody.capsule(0.2f, 0.8f, 1f).at(1f, 3f, 0f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(sphere, box, capsule), List.of(), 4)) {
            world.addGroundPlane(0f, 0.7f, 0f);
            step(world, 360);

            assertEquals(0.3f, sphere.position().y, 0.03f);
            assertEquals(0.3f, box.position().y, 0.03f);
            assertEquals(0.6f, capsule.position().y, 0.04f);
            assertEquals(3, world.staticContactBodyCount());
            assertEquals(0, world.selfContactCount());
            BodyContact contact = world.contacts(sphere).stream()
                    .filter(BodyContact::touching).findFirst().orElseThrow();
            assertTrue(contact.other().isEmpty(), "ground is native static geometry, not a Java body");
            assertTrue(contact.normal().y > 0.8f, "contact normal points from ground toward the sphere");
        }
    }

    @Test
    void pmxStyleNonCollisionMasksMapToBox3DFilters() {
        GenericRigidBody a = GenericRigidBody.sphere(0.5f, 1f)
                .at(-0.2f, 0f, 0f).filter(0, 1 << 1);
        GenericRigidBody b = GenericRigidBody.sphere(0.5f, 1f)
                .at(0.2f, 0f, 0f).filter(1, 0);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(a, b), List.of(), 4)) {
            world.setGravity(new Vector3f());
            step(world, 60);
            assertEquals(0.4f, a.position().distance(b.position()), 0.01f);
        }
    }

    @Test
    void incrementalMinecraftBoxesBecomeNativeStaticBodies() {
        GenericRigidBody sphere = GenericRigidBody.sphere(0.5f, 1f).at(0.5f, 3f, 0.5f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(sphere), List.of(), 4)) {
            StaticEnvironmentMesh mesh = world.addEnvironmentMesh(0.7f, 0f);
            mesh.putCell(1L, new EnvironmentCell(
                    List.of(new EnvironmentBox(new Vector3f(), new Vector3f(1f)))));
            step(world, 240);
            assertEquals(1.5f, sphere.position().y, 0.04f);

            mesh.removeCell(1L);
            step(world, 120);
            assertTrue(sphere.position().y < 1f);
        }
    }

    @Test
    void adjacentTerrainCellsCompileToOneSeamlessMesh() {
        GenericRigidBody sphere = GenericRigidBody.sphere(0.35f, 1f).at(1f, 3f, 0.5f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(sphere), List.of(), 4)) {
            StaticEnvironmentMesh mesh = world.addEnvironmentMesh(0.8f, 0f);
            mesh.putCell(1L, new EnvironmentCell(List.of(
                    new EnvironmentBox(new Vector3f(0f, 0f, 0f), new Vector3f(1f, 1f, 1f)))));
            mesh.putCell(2L, new EnvironmentCell(List.of(
                    new EnvironmentBox(new Vector3f(1f, 0f, 0f), new Vector3f(2f, 1f, 1f)))));

            assertEquals(60, mesh.geometry().indices().length,
                    "the two opposing internal faces must be removed");
            step(world, 300);

            assertEquals(1.35f, sphere.position().y, 0.04f);
            assertEquals(1f, sphere.position().x, 0.04f,
                    "a body resting on the cell boundary must not receive a lateral seam impulse");
        }
    }

    @Test
    void compoundBodyUsesEveryOffsetChildShape() {
        GenericRigidBody compound = GenericRigidBody.compound(List.of(
                new BodyShape.Box(new Vector3f(-0.75f, 0f, 0f), new Vector3f(0.25f)),
                new BodyShape.Box(new Vector3f(0.75f, 0f, 0f), new Vector3f(0.25f))), 2f)
                .at(0f, 1f, 0f);
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(compound), List.of(), 4)) {
            world.setGravity(new Vector3f());
            assertTrue(world.raycast(new Vector3f(0.75f, 1f, -2f),
                    new Vector3f(0f, 0f, 1f), 4f).isPresent());
            assertTrue(world.raycast(new Vector3f(0f, 1f, -2f),
                    new Vector3f(0f, 0f, 1f), 4f).isEmpty(),
                    "the gap between compound children must remain empty");
        }
    }

    @Test
    void box3dContinuousCollisionStopsFastBodyAtStaticBox() {
        GenericRigidBody sphere = GenericRigidBody.sphere(0.1f, 1f)
                .at(-1f, 0.5f, 0.5f).velocity(new Vector3f(120f, 0f, 0f));
        try (RigidBodyWorld world = new RigidBodyWorld(List.of(sphere), List.of(), 4)) {
            world.setGravity(new Vector3f());
            world.addStaticBoxCollider(new Vector3f(), new Vector3f(1f), 0f, 0f);
            step(world, 4);
            assertTrue(sphere.position().x < 1.2f, "fast body must not tunnel through the static cube");
        }
    }

    private static void step(RigidBodyWorld world, int count) {
        for (int index = 0; index < count; index++) world.step(DT, RigidBodyWorld.KinematicDriver.none());
    }
}
