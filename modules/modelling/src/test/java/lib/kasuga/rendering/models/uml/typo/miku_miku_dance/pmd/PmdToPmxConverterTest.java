package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.pmd;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.Collection;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PmdToPmxConverterTest {
    @Test
    void convertsLegacyBonesRigidBodiesAndJointsIntoCompletePmx() {
        Le out = new Le();
        out.raw(new byte[]{'P', 'm', 'd'}); out.f32(1); out.fixed("モデル", 20); out.fixed("comment", 256);
        out.i32(0); out.i32(0); out.i32(0);
        out.u16(1); out.fixed("センター", 20); out.u16(0xffff); out.u16(0xffff); out.u8(0); out.u16(0); out.vec3(0, 1, 0);
        out.u16(0);
        out.u16(1); out.fixed("base", 20); out.i32(0); out.u8(0);
        out.u8(0); out.u8(0); out.i32(0); out.u8(0);
        for (int i = 0; i < 10; i++) out.fixed("toon" + (i + 1) + ".bmp", 100);
        out.i32(1); out.fixed("body", 20); out.u16(0); out.u8(1); out.u16(2); out.u8(0);
        out.vec3(1, 1, 1); out.vec3(0, 2, 0); out.vec3(0, 0, 0);
        out.f32(1); out.f32(0.1f); out.f32(0.2f); out.f32(0.3f); out.f32(0.4f); out.u8(1);
        out.i32(1); out.fixed("joint", 20); out.i32(0); out.i32(0);
        for (int i = 0; i < 8; i++) out.vec3(i, i + 1, i + 2);

        ByteBuffer pmx = new PmdToPmxConverter().convert(out.buffer());
        ProbeLoader loader = new ProbeLoader();
        loader.load("converted", pmx);

        assertEquals("モデル", loader.getHeader().localModelName);
        assertEquals(1, loader.getBones().size());
        assertEquals(1, loader.getTail().rigidBodies().size());
        assertEquals(1, loader.getTail().joints().size());
        assertEquals(3f, loader.getTail().rigidBodies().getFirst().position().y, 1e-6f,
                "PMD rigid-body offsets are made model-space during conversion");
    }

    @Test
    void rejectsInvalidSignature() {
        assertThrows(PmdFormatException.class, () -> new PmdToPmxConverter().convert(
                ByteBuffer.wrap(new byte[]{'B', 'a', 'd'})));
    }

    private static final class Le {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        void raw(byte[] value) { out.writeBytes(value); } void u8(int v) { out.write(v); }
        void u16(int v) { u8(v); u8(v >>> 8); } void i32(int v) { u8(v); u8(v >>> 8); u8(v >>> 16); u8(v >>> 24); }
        void f32(float v) { i32(Float.floatToRawIntBits(v)); }
        void vec3(float x, float y, float z) { f32(x); f32(y); f32(z); }
        void fixed(String value, int size) {
            byte[] encoded = value.getBytes(Charset.forName("windows-31j"));
            byte[] result = new byte[size]; System.arraycopy(encoded, 0, result, 0, Math.min(size, encoded.length)); raw(result);
        }
        ByteBuffer buffer() { return ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN); }
    }

    private static final class ProbeLoader extends PMXLoader<ByteBuffer, String, String, DummyContext> {
        ProbeLoader() { super("pmd-probe"); }
        @Override public ByteBuffer getAsByteBuffer(ByteBuffer input) { return input.duplicate().order(ByteOrder.LITTLE_ENDIAN); }
        @Override public void beforeAllLoaders(ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void build(Map<String, Model> map, String id, ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {}
        @Override public String getTextureIdentifier(String texturePath) { return texturePath; }
        @Override public Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertices) { return null; }
        @Override public Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh) { return null; }
        @Override public Bone getBone(java.util.List<PmxBone> bones, PmxBone bone) { return null; }
        @Override public ModelData getModelData(PmxHeader header) { return header; }
        @Override public Texture loadTexture(Object id) { return null; }
        @Override public boolean isValidInput(Object input) { return input instanceof ByteBuffer; }
    }
    private static final class DummyContext implements ContextData<DummyContext> {
        @Override public void build(SerialContext<DummyContext> context) {}
    }
}
