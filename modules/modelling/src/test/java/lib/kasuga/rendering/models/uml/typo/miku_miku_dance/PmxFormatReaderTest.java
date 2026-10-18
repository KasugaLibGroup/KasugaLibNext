package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header.HeaderChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.chunk.header.HeaderInfoChunk;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail.*;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxGlobalInfo;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneFlags;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class PmxFormatReaderTest {
    @Test
    void headerAcceptsExtendedGlobalsAndMapsTextureAndMaterialSizesCorrectly() throws IOException {
        LeWriter w = new LeWriter();
        w.bytes(new byte[]{'P', 'M', 'X', ' '});
        w.f32(2.1f);
        w.u8(10);
        w.bytes(new byte[]{1, 4, 1, 2, 4, 1, 2, 4, 99, 100});
        w.text("モデル");
        w.text("model");
        w.text("説明");
        w.text("description");

        PmxHeader header = (PmxHeader) new HeaderChunk(new HeaderInfoChunk())
                .load(w.buffer(), null);

        assertEquals("PMX ", header.signature);
        assertEquals(2.1f, header.version);
        assertEquals(10, Byte.toUnsignedInt(header.dataSize));
        assertEquals(4, header.info.additionalVec4Count);
        assertEquals(2, header.info.textureIndexSize);
        assertEquals(4, header.info.materialIndexSize);
        assertEquals("モデル", header.localModelName);
    }

    @Test
    void readsEveryPmx21TailSectionAndConsumesTheBuffer() throws IOException {
        PmxHeader header = header(2.1f, 2, 1, 2, 1, 2, 1);
        LeWriter w = new LeWriter();

        w.i32(6); // morphs
        morphHeader(w, "group", 0, 1); w.i16(3); w.f32(0.5f);
        morphHeader(w, "vertex", 1, 1); w.u16(513); w.vec3(1, 2, 3);
        morphHeader(w, "bone", 2, 1); w.i8(-1); w.vec3(4, 5, 6); w.vec4(0, 0, 0, 1);
        morphHeader(w, "uv4", 7, 1); w.u16(7); w.vec4(1, 2, 3, 4);
        morphHeader(w, "material", 8, 1); writeMaterialOffset(w);
        morphHeader(w, "impulse", 10, 1); w.i8(-1); w.u8(1); w.vec3(7, 8, 9); w.vec3(10, 11, 12);

        w.i32(1); // display frames
        w.text("表示"); w.text("display"); w.u8(1); w.i32(2);
        w.u8(0); w.i8(4);
        w.u8(1); w.i16(5);

        w.i32(1); // rigid bodies
        w.text("剛体"); w.text("body"); w.i8(-1); w.u8(15); w.u16(0x8001); w.u8(2);
        w.vec3(1, 2, 3); w.vec3(4, 5, 6); w.vec3(7, 8, 9);
        w.f32(10); w.f32(11); w.f32(12); w.f32(13); w.f32(14); w.u8(2);

        w.i32(1); // joints
        w.text("ジョイント"); w.text("joint"); w.u8(5); w.i8(0); w.i8(-1);
        for (int i = 0; i < 8; i++) w.vec3(i, i + 1, i + 2);

        w.i32(1); // soft bodies
        w.text("布"); w.text("cloth"); w.u8(0); w.i16(2); w.u8(3); w.u16(0x55aa); w.u8(7);
        w.i32(4); w.i32(5); w.f32(6); w.f32(7); w.i32(4);
        for (int i = 0; i < 12; i++) w.f32(i + 0.25f);
        for (int i = 0; i < 6; i++) w.f32(i + 20.25f);
        w.i32(30); w.i32(31); w.i32(32); w.i32(33);
        w.f32(40); w.f32(41); w.f32(42);
        w.i32(1); w.i8(0); w.u16(514); w.u8(1);
        w.i32(2); w.u16(1); w.u16(65535);

        ByteBuffer buffer = w.buffer();
        PmxTail tail = new PmxTailReader(header).load(buffer, null);

        assertFalse(buffer.hasRemaining());
        assertEquals(6, tail.morphs().size());
        assertEquals(513, ((VertexOffset) tail.morphs().get(1).offsets().getFirst()).vertexIndex());
        assertEquals(-1, ((BoneOffset) tail.morphs().get(2).offsets().getFirst()).boneIndex());
        assertEquals(4, ((UvOffset) tail.morphs().get(3).offsets().getFirst()).layer());
        assertTrue(((ImpulseOffset) tail.morphs().get(5).offsets().getFirst()).local());
        assertEquals(0x8001, tail.rigidBodies().getFirst().nonCollisionMask());
        assertEquals(5, tail.joints().getFirst().type());
        assertEquals(0x55aa, tail.softBodies().getFirst().nonCollisionMask());
        assertEquals(65535, tail.softBodies().getFirst().pinnedVertices().get(1));
    }

    @Test
    void rejectsPmx20OnlyMorphsInVersion20() throws IOException {
        PmxHeader header = header(2.0f, 1, 1, 1, 1, 1, 1);
        LeWriter w = new LeWriter();
        w.i32(1);
        morphHeader(w, "impulse", 10, 0);
        PmxFormatException error = assertThrows(PmxFormatException.class,
                () -> new PmxTailReader(header).load(w.buffer(), null));
        assertTrue(error.getMessage().contains("cannot contain morph type 10"));
    }

    @Test
    void rejectsTrailingData() throws IOException {
        PmxHeader header = header(2.0f, 1, 1, 1, 1, 1, 1);
        LeWriter w = new LeWriter();
        w.i32(0); w.i32(0); w.i32(0); w.i32(0); w.u8(99);
        assertThrows(PmxFormatException.class,
                () -> new PmxTailReader(header).load(w.buffer(), null));
    }

    @Test
    void readsACompleteFileAndTreatsExternalParentKeyAsInt32() throws IOException {
        LeWriter w = new LeWriter();
        w.bytes(new byte[]{'P', 'M', 'X', ' '}); w.f32(2.1f); w.u8(8);
        w.bytes(new byte[]{1, 0, 1, 1, 1, 1, 1, 1});
        w.text("model"); w.text("model"); w.text(""); w.text("");

        w.i32(1); // vertices
        w.vec3(0, 0, 0); w.vec3(0, 1, 0); w.f32(0); w.f32(0);
        w.u8(0); w.i8(0); w.f32(1);
        w.i32(3); w.u8(0); w.u8(0); w.u8(0); // surface indices
        w.i32(0); // textures
        w.i32(0); // materials

        w.i32(1); // bones
        w.text("root"); w.text("root"); w.vec3(0, 0, 0); w.i8(-1); w.i32(0);
        w.u8(0); w.u8(0x20); // external-parent flag 0x2000
        w.vec3(0, 1, 0); w.i32(0x12345678);

        w.i32(0); w.i32(0); w.i32(0); w.i32(0); w.i32(0);

        ProbeLoader loader = new ProbeLoader();
        loader.load("test", w.buffer());
        assertEquals(1, loader.getVertices().size());
        assertEquals(1, loader.getMeshes().size());
        assertEquals(1, loader.getBones().size());
        assertEquals(0x12345678, loader.getBones().getFirst().foreignParentBoneIndex.intValue());
        assertTrue(loader.getTail().softBodies().isEmpty());
    }

    @Test
    void convertsAbsolutePmxBonePositionsToUnrotatedLocalBindTransforms() {
        ProbeLoader loader = new ProbeLoader();
        PmxBone parent = bone("parent", new org.joml.Vector3f(2, 3, 4), -1,
                new org.joml.Vector3f(0, 5, 0));
        PmxBone child = bone("child", new org.joml.Vector3f(5, 7, 10), 0,
                new org.joml.Vector3f(3, 0, 0));

        Transform parentTransform = loader.calculateBoneTransform(List.of(parent, child), parent);
        Transform childTransform = loader.calculateBoneTransform(List.of(parent, child), child);

        assertEquals(new org.joml.Vector3f(2, 3, 4), parentTransform.getPosition());
        assertEquals(new org.joml.Vector3f(3, 4, 6), childTransform.getPosition());
        assertEquals(new org.joml.Vector3f(0, 1, 0),
                childTransform.applyDirection(new org.joml.Vector3f(0, 1, 0)));
    }

    @Test
    void buildsAStaticPmxWithoutDeclaredBones() throws IOException {
        LeWriter w = new LeWriter();
        w.bytes(new byte[]{'P', 'M', 'X', ' '}); w.f32(2.0f); w.u8(8);
        w.bytes(new byte[]{1, 0, 1, 1, 1, 1, 1, 1});
        w.text("static"); w.text("static"); w.text(""); w.text("");
        w.i32(0); // vertices
        w.i32(0); // surface indices
        w.i32(0); // textures
        w.i32(0); // materials
        w.i32(0); // bones
        w.i32(0); // morphs
        w.i32(0); // display frames
        w.i32(0); // rigid bodies
        w.i32(0); // joints

        Model model = new BuildProbeLoader().load("static", w.buffer()).get("static");
        assertNotNull(model);
        assertEquals("dummy_root", model.getSkeleton().getRoot().getName());
        assertEquals(1, model.getBones().length);
    }

    private static PmxBone bone(String name, org.joml.Vector3f position, int parent, Object tail) {
        return new PmxBone(name, name, position, parent, 0, new PmxBoneFlags(), tail,
                null, null, null, -1, null);
    }

    private static PmxHeader header(float version, int vertex, int texture, int material,
                                    int bone, int morph, int rigid) {
        PmxGlobalInfo info = new PmxGlobalInfo(StandardCharsets.UTF_8, (byte) 4,
                (byte) vertex, (byte) material, (byte) texture, (byte) bone, (byte) morph, (byte) rigid);
        return new PmxHeader("PMX ", version, (byte) 8, info, "", "", "", "");
    }

    private static void morphHeader(LeWriter w, String name, int kind, int offsets) throws IOException {
        w.text(name); w.text(name); w.u8(4); w.u8(kind); w.i32(offsets);
    }

    private static void writeMaterialOffset(LeWriter w) throws IOException {
        w.i16(-1); w.u8(1); w.vec4(1, 2, 3, 4); w.vec3(5, 6, 7); w.f32(8);
        w.vec3(9, 10, 11); w.vec4(12, 13, 14, 15); w.f32(16);
        w.vec4(17, 18, 19, 20); w.vec4(21, 22, 23, 24); w.vec4(25, 26, 27, 28);
    }

    private static final class LeWriter {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final DataOutputStream out = new DataOutputStream(bytes);

        void bytes(byte[] value) throws IOException { out.write(value); }
        void i8(int value) throws IOException { out.writeByte(value); }
        void u8(int value) throws IOException { out.writeByte(value); }
        void i16(int value) throws IOException { out.writeShort(Short.reverseBytes((short) value)); }
        void u16(int value) throws IOException { i16(value); }
        void i32(int value) throws IOException { out.writeInt(Integer.reverseBytes(value)); }
        void f32(float value) throws IOException { i32(Float.floatToRawIntBits(value)); }
        void vec3(float x, float y, float z) throws IOException { f32(x); f32(y); f32(z); }
        void vec4(float x, float y, float z, float w) throws IOException { f32(x); f32(y); f32(z); f32(w); }
        void text(String value) throws IOException {
            byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
            i32(encoded.length); bytes(encoded);
        }
        ByteBuffer buffer() throws IOException {
            out.flush();
            return ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    private static final class ProbeLoader extends PMXLoader<ByteBuffer, String, String, DummyContext> {
        ProbeLoader() { super("probe"); }

        @Override public ByteBuffer getAsByteBuffer(ByteBuffer input) {
            return input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        }
        @Override public void beforeAllLoaders(ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void build(Map<String, Model> map, String id, ByteBuffer buffer,
                                    SerialContext<DummyContext> context) {}
        @Override public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {}
        @Override public String getTextureIdentifier(String texturePath) { return texturePath; }
        @Override public Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertices) { return null; }
        @Override public Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh) { return null; }
        @Override public Bone getBone(java.util.List<PmxBone> bones, PmxBone bone) { return null; }
        @Override public ModelData getModelData(PmxHeader header) { return header; }
        @Override public lib.kasuga.rendering.models.uml.structure.material.Texture loadTexture(Object id) { return null; }
        @Override public boolean isValidInput(Object input) { return input instanceof ByteBuffer; }
    }

    private static final class BuildProbeLoader extends PMXLoader<ByteBuffer, String, String, DummyContext> {
        BuildProbeLoader() { super("build-probe"); }

        @Override public ByteBuffer getAsByteBuffer(ByteBuffer input) {
            return input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        }
        @Override public void beforeAllLoaders(ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void beforeLoader(StreamLoader loader, ByteBuffer buffer, SerialContext<DummyContext> context) {}
        @Override public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {}
        @Override public String getTextureIdentifier(String texturePath) { return texturePath; }
        @Override public Vertex getVertex(PmxVertex first, Collection<PmxVertex> vertices) { return null; }
        @Override public Mesh getMesh(Vertex v1, Vertex v2, Vertex v3, PmxMesh mesh) { return null; }
        @Override public Bone getBone(java.util.List<PmxBone> bones, PmxBone bone) { return null; }
        @Override public ModelData getModelData(PmxHeader header) { return header; }
        @Override public lib.kasuga.rendering.models.uml.structure.material.Texture loadTexture(Object id) { return null; }
        @Override public boolean isValidInput(Object input) { return input instanceof ByteBuffer; }
    }

    private static final class DummyContext implements ContextData<DummyContext> {
        @Override public void build(SerialContext<DummyContext> context) {}
    }
}
