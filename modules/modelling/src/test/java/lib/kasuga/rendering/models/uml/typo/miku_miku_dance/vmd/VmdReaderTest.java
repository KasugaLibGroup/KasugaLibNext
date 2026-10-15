package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd;

import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.vmd.VmdMotion.BoneKeyframe;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;

import static org.junit.jupiter.api.Assertions.*;

class VmdReaderTest {
    @Test
    void parsesAndSortsEveryVmd0002Section() throws IOException {
        LeWriter w = new LeWriter();
        w.fixed("Vocaloid Motion Data 0002", 30);
        w.fixed("初音ミク", 20);

        w.i32(2);
        writeBone(w, "センター", 10, false);
        writeBone(w, "センター", 0, true);

        w.i32(1);
        w.fixed("笑い", 15); w.i32(4); w.f32(0.75f);

        w.i32(1);
        w.i32(6); w.f32(-30); w.vec3(1, 2, 3); w.vec3(4, 5, 6);
        for (int i = 0; i < 24; i++) w.u8(i);
        w.i32(45); w.u8(0);

        w.i32(1);
        w.i32(7); w.vec3(0.1f, 0.2f, 0.3f); w.vec3(4, 5, 6);

        w.i32(1);
        w.i32(8); w.u8(2); w.f32(0.05f);

        w.i32(1);
        w.i32(9); w.u8(1); w.i32(1); w.fixed("左足ＩＫ", 20); w.u8(0);
        w.bytes(new byte[]{'t', 'a', 'i', 'l'});

        VmdMotion motion = new VmdReader().read(w.buffer());

        assertEquals("初音ミク", motion.modelName());
        assertEquals(2, motion.boneTracks().get("センター").size());
        BoneKeyframe first = motion.boneTracks().get("センター").getFirst();
        assertEquals(0, first.frame());
        assertTrue(first.interpolation().physicsEnabled());
        BoneKeyframe disabledPhysics = motion.boneTracks().get("センター").get(1);
        assertFalse(disabledPhysics.interpolation().physicsEnabled());
        assertEquals(32f / 127f, disabledPhysics.interpolation().z().x1(), 1e-6f);
        assertEquals(0.75f, motion.morphTracks().get("笑い").getFirst().weight());
        assertTrue(motion.cameraTrack().getFirst().perspective());
        assertEquals(45, motion.cameraTrack().getFirst().fieldOfViewDegrees());
        assertEquals(new Vector3f(0.1f, 0.2f, 0.3f), motion.lightTrack().getFirst().color());
        assertEquals(2, motion.shadowTrack().getFirst().mode());
        assertFalse(motion.propertyTrack().getFirst().ikStates().getFirst().enabled());
        assertArrayEquals(new byte[]{'t', 'a', 'i', 'l'}, motion.trailingData());
    }

    @Test
    void acceptsLegacyHeaderAndEarlyEndAfterBoneSection() throws IOException {
        LeWriter w = new LeWriter();
        w.fixed("Vocaloid Motion Data file", 30);
        w.fixed("legacy", 10);
        w.i32(0);

        VmdMotion motion = new VmdReader().read(w.buffer());
        assertEquals("legacy", motion.modelName());
        assertTrue(motion.morphTracks().isEmpty());
        assertTrue(motion.cameraTrack().isEmpty());
    }

    @Test
    void evaluatesBezierBySolvingItsXCoordinate() {
        VmdBezier linear = new VmdBezier(0.25f, 0.25f, 0.75f, 0.75f);
        assertEquals(0.4f, linear.evaluate(0.4f), 1e-4f);
        VmdBezier easeIn = new VmdBezier(0.5f, 0f, 1f, 0.5f);
        assertTrue(easeIn.evaluate(0.5f) < 0.5f);
    }

    private static void writeBone(LeWriter w, String name, int frame, boolean physics) throws IOException {
        w.fixed(name, 15); w.i32(frame); w.vec3(frame, frame + 1, frame + 2); w.vec4(0, 0, 0, 1);
        byte[] interpolation = new byte[64];
        interpolation[0] = 20; interpolation[1] = 21;
        interpolation[2] = (byte) (physics ? 22 : 99);
        interpolation[3] = (byte) (physics ? 23 : 15);
        interpolation[4] = 24; interpolation[5] = 25; interpolation[6] = 26; interpolation[7] = 27;
        interpolation[8] = 80; interpolation[9] = 81; interpolation[10] = 82; interpolation[11] = 83;
        interpolation[12] = 90; interpolation[13] = 91; interpolation[14] = 92; interpolation[15] = 93;
        interpolation[17] = 32; interpolation[18] = 33;
        w.bytes(interpolation);
    }

    private static final class LeWriter {
        private static final Charset WINDOWS_31J = Charset.forName("windows-31j");
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);
        void u8(int value) throws IOException { out.writeByte(value); }
        void i32(int value) throws IOException { out.writeInt(Integer.reverseBytes(value)); }
        void f32(float value) throws IOException { i32(Float.floatToRawIntBits(value)); }
        void vec3(float x, float y, float z) throws IOException { f32(x); f32(y); f32(z); }
        void vec4(float x, float y, float z, float a) throws IOException { f32(x); f32(y); f32(z); f32(a); }
        void bytes(byte[] value) throws IOException { out.write(value); }
        void fixed(String value, int length) throws IOException {
            byte[] encoded = value.getBytes(WINDOWS_31J);
            if (encoded.length > length) throw new IllegalArgumentException("test text too long");
            out.write(encoded);
            out.write(new byte[length - encoded.length]);
        }
        ByteBuffer buffer() throws IOException {
            out.flush();
            return ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        }
    }
}
