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
