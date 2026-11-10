package lib.kasuga.rendering.models.uml.dynamic.physics.box3d;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("box3d")
class NativeBox3DTest {
    @Test
    void dynamicBoxFallsAndRestsOnStaticGround() {
        NativeBox3D.requireAvailable();
        int world = NativeBox3D.createWorld(0f, -10f, 0f, true, true);
        long ground = 0L;
        long box = 0L;
        try {
            ground = body(world, NativeBox3D.STATIC_BODY,
                    50f, 0.5f, 50f, 0f, 0f, -0.5f, 0f);
            box = body(world, NativeBox3D.DYNAMIC_BODY,
                    0.5f, 0.5f, 0.5f, 1f, 0f, 4f, 0f);

            for (int step = 0; step < 180; step++) {
                NativeBox3D.step(world, 1f / 60f, 4);
            }

            float[] state = new float[13];
            NativeBox3D.readBodyState(box, state);
            assertEquals(0.5f, state[1], 0.03f);
            assertTrue(Math.abs(state[8]) < 0.05f, "vertical velocity should settle");
        } finally {
            if (box != 0L) NativeBox3D.destroyBody(box);
            if (ground != 0L) NativeBox3D.destroyBody(ground);
            NativeBox3D.destroyWorld(world);
        }
    }

    @Test
    void raycastReturnsTheDynamicBody() {
        int world = NativeBox3D.createWorld(0f, 0f, 0f, true, true);
        long box = body(world, NativeBox3D.DYNAMIC_BODY,
                0.5f, 0.5f, 0.5f, 1f, 0f, 0f, 0f);
        try {
            float[] hit = new float[7];
            long result = NativeBox3D.raycast(world, 0f, 0f, -3f,
                    0f, 0f, 1f, 10f, hit);
            assertEquals(box, result);
            assertEquals(2.5f, hit[6], 0.001f);
        } finally {
            NativeBox3D.destroyBody(box);
            NativeBox3D.destroyWorld(world);
        }
    }

    @Test
    void smallFastCapsuleReusesValidConvexGroundContacts() {
        assertTrue(slideSmallCapsule(false) > 0,
                "fast classification alone must not discard a convex manifold within its motion tolerance");
    }

    @Test
    void smallFastCapsuleRefreshesMeshGroundContacts() {
        assertEquals(0, slideSmallCapsule(true),
                "fast mesh contacts must still be refreshed for continuous collision detection");
    }

    private static int slideSmallCapsule(boolean meshGround) {
        int world = NativeBox3D.createWorld(0f, -10f, 0f, false, true);
        long capsule = 0L;
        long ground = 0L;
        long[] mesh = null;
        try {
            if (meshGround) {
                mesh = NativeBox3D.createStaticMesh(world,
                        new float[]{-50f, 0f, -50f, 50f, 0f, -50f,
                                50f, 0f, 50f, -50f, 0f, 50f},
                        new int[]{0, 2, 1, 0, 3, 2}, 0f, 0f);
            } else {
                ground = body(world, NativeBox3D.STATIC_BODY,
                        50f, 0.5f, 50f, 0f, 0f, -0.5f, 0f);
            }
            capsule = NativeBox3D.createBody(world, NativeBox3D.DYNAMIC_BODY,
                    0f, 0.02f, 0f, 0f, 0f, 0f, 1f,
                    2f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, false);
            NativeBox3D.addCapsuleShape(capsule,
                    -0.05f, 0f, 0f, 0.05f, 0f, 0f, 0.02f,
                    1f, 0f, 0f, 0f, -1L, -1L, 0);
            NativeBox3D.finalizeBodyMass(capsule, 1f);

            int recycled = 0;
            for (int step = 0; step < 60; step++) {
                NativeBox3D.step(world, 1f / 120f, 4);
                recycled += NativeBox3D.recycledContactCount(world);
            }
            float[] state = new float[13];
            NativeBox3D.readBodyState(capsule, state);
            assertTrue(state[0] > 0.5f, "the capsule must keep sliding across the floor");
            assertTrue(state[1] > 0f && state[1] < 0.05f,
                    "the moving capsule must remain in contact without tunnelling: y=" + state[1]);
            assertTrue(NativeBox3D.contactCount(world) > 0);
            return recycled;
        } finally {
            if (capsule != 0L) NativeBox3D.destroyBody(capsule);
            if (ground != 0L) NativeBox3D.destroyBody(ground);
            if (mesh != null) NativeBox3D.destroyStaticMesh(mesh[0], mesh[1]);
            NativeBox3D.destroyWorld(world);
        }
    }

    private static long body(int world, int type, float sx, float sy, float sz,
                             float mass, float px, float py, float pz) {
        long body = NativeBox3D.createBody(world, type,
                px, py, pz, 0f, 0f, 0f, 1f,
                0f, 0f, 0f, 0f, 0f, 0f,
                0f, 0f, false);
        NativeBox3D.addBoxShape(body, 0f, 0f, 0f,
                0f, 0f, 0f, 1f, sx, sy, sz,
                type == NativeBox3D.DYNAMIC_BODY ? 1f : 0f,
                0.5f, 0f, 0f, -1L, -1L, 0);
        NativeBox3D.finalizeBodyMass(body, mass);
        return body;
    }
}
