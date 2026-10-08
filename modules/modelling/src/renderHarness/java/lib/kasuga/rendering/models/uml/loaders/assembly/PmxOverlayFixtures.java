package lib.kasuga.rendering.models.uml.loaders.assembly;

import lib.kasuga.rendering.models.uml.loaders.MaterialSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.SpriteSetBuilder;
import lib.kasuga.rendering.models.uml.loaders.serial.ContextData;
import lib.kasuga.rendering.models.uml.loaders.serial.SerialContext;
import lib.kasuga.rendering.models.uml.loaders.serial.byte_stream.StreamLoader;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.PMXLoader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.MmdModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.PmxTail;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBone;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh.PmxMesh;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex.PmxVertex;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/** Uses the production PMX conversion; texture descriptors are retained without decoding or displaying images. */
final class PmxOverlayFixtures extends PMXLoader<ByteBuffer, String, String, PmxOverlayFixtures.Context> {
    private final Path file;
    private final Vector3f scale = new Vector3f(.1f);

    private PmxOverlayFixtures(Path file) { super("overlay-fixture"); this.file = file; }
    static Model read(Path file) throws Exception {
        var loader = new PmxOverlayFixtures(file);
        return loader.load(file.toString(), ByteBuffer.wrap(Files.readAllBytes(file))).get(file.toString());
    }
    public ByteBuffer getAsByteBuffer(ByteBuffer input) { return input.duplicate().order(ByteOrder.LITTLE_ENDIAN); }
    public void beforeAllLoaders(ByteBuffer input, SerialContext<Context> context) {}
    public void beforeLoader(StreamLoader loader, ByteBuffer input, SerialContext<Context> context) {}
    public boolean isValidInput(Object input) { return input instanceof ByteBuffer; }
    public String getTextureIdentifier(String path) { return path; }
    public Texture loadTexture(Object id) { return new Texture(file + "#" + id, 1, 1, null); }
    public void buildMaterial(MaterialSetBuilder builder, PmxMaterial material) {
        int index = material.textureIndex.intValue();
        String texture = index >= 0 && index < getTextures().size() ? getTextures().get(index) : "__white";
        if (builder.getTexture(texture) == null) builder.registerTexture(texture, loadTexture(texture));
        builder.useTexture(texture).addSpriteBuildingFunc((materials, sprites, output) ->
                ((SpriteSetBuilder) sprites).textureId(texture).culled(!material.flags.noCull)
                        .shade(material.flags.drawShadow).color(new org.joml.Vector4f(material.diffuseColor)).endSprite())
                .endMaterial(material);
    }
    public Vertex getVertex(PmxVertex first, Collection<PmxVertex> duplicates) {
        if (first.binding.data != null) {
            first.binding.data.c().mul(scale); first.binding.data.r0().mul(scale); first.binding.data.r1().mul(scale);
        }
        return new Vertex(new Vector3f(first.position).mul(scale), first);
    }
    public Mesh getMesh(Vertex a, Vertex b, Vertex c, PmxMesh mesh) {
        return new Mesh(new Vertex[]{a, b, c}, new Vector3f(), new Transform(), new Material[1], mesh);
    }
    public void scaleBone(PmxBone bone) {
        bone.position.mul(scale);
        if (bone.tailObject instanceof Vector3f offset) offset.mul(scale);
    }
    protected Vector3f scaleMmdTranslation(Vector3f value) { return value.mul(scale); }
    public Bone getBone(List<PmxBone> bones, PmxBone bone) {
        return new Bone(bone.localBoneName, calculateBoneTransform(bones, bone), bone);
    }
    public ModelData getModelData(PmxHeader header) { return header; }
    public ModelData getModelData(PmxHeader header, PmxTail tail) {
        return new MmdModelData(header, tail, scale, getBones().size());
    }
    static final class Context implements ContextData<Context> {
        public void build(SerialContext<Context> context) {}
    }
}
