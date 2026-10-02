package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.dynamic.morph.types.*;
import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.*;
import lib.kasuga.rendering.models.uml.structure.material.*;
import lib.kasuga.rendering.models.uml.structure.skeleton.*;
import lib.kasuga.rendering.models.uml.util.MeshMode;
import org.joml.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FlatModelUpdateTest {
    @Test void independentConsumersCatchUpAcrossSkippedFramesAndDeactivation() throws Exception {
        Fixture f = fixture();
        try (var first = data(f.model, false); var second = data(f.model, false)) {
            first.getDirtyVertices().clear(); second.getDirtyVertices().clear();
            f.model.getMorph().activateMorph("pos", 1f);
            f.model.getMorph().activateMorph("uv", 1f);
            assertTrue(first.updateModel());
            assertEquals(2f, first.getBuffer().getFloat(first.getPosOffset()));
            assertEquals(.75f, first.getBuffer().getFloat(first.getTextureUvOffset()));
            f.model.getMorph().activateMorph("pos", .5f);
            first.getDirtyVertices().clear(); first.updateModel();
            assertTrue(second.updateModel());
            assertEquals(1f, second.getBuffer().getFloat(second.getPosOffset()));
            assertEquals(.75f, second.getBuffer().getFloat(second.getTextureUvOffset()));
            first.getDirtyVertices().clear(); second.getDirtyVertices().clear();
            f.model.getMorph().deactivateMorph("pos"); first.updateModel(); second.updateModel();
            assertEquals(0f, second.getBuffer().getFloat(second.getPosOffset()));
            assertEquals(.75f, second.getBuffer().getFloat(second.getTextureUvOffset()));
            first.getDirtyVertices().clear(); assertFalse(first.updateModel());
        }
    }

    @Test void materialMorphWritesColorInEveryConsumerAndBrightnessPreservesCoverage() throws Exception {
        Fixture f = fixture();
        try (var first = data(f.model, false); var second = data(f.model, false)) {
            first.getDirtyVertices().clear(); second.getDirtyVertices().clear();
            long skinningVersion = second.getSkinningSourceVersion();
            f.model.getMorph().activateMorph("color", 1f);
            first.updateModel(); second.updateModel();
            assertEquals(128, Byte.toUnsignedInt(first.getBuffer().get(first.getColorOffset() + 3)));
            assertEquals(128, Byte.toUnsignedInt(second.getBuffer().get(second.getColorOffset() + 3)));
            second.getDirtyVertices().clear(); second.setBrightness(.25f); second.updateModel();
            assertEquals(128, Byte.toUnsignedInt(second.getBuffer().get(second.getColorOffset() + 3)));
            assertEquals(64, Byte.toUnsignedInt(second.getBuffer().get(second.getColorOffset())));
            assertFalse(second.getDirtyVertices().isEmpty());
            assertEquals(skinningVersion, second.getSkinningSourceVersion());
        }
    }

    @Test void cpuMorphUpdatesWithoutSkeletonVersionChange() throws Exception {
        Fixture f = fixture();
        try (var data = data(f.model, true)) {
            data.getDirtyVertices().clear();
            f.model.getMorph().activateMorph("pos", 1f); data.updateModel();
            assertEquals(2f, data.getBuffer().getFloat(data.getPosOffset()));
        }
    }

    @Test void cpuSkinningConsumesChangedBoneMatricesAndMorphedPositions() throws Exception {
        Fixture f = fixture();
        Bone root = f.model.getModel().getBones()[0];
        for (Vertex vertex : f.model.getModel().getVertices()) {
            vertex.setBinding(new BoneBinding(new lib.kasuga.structure.Pair[]{lib.kasuga.structure.Pair.of(root,1f)},
                    BoneBindingFunc.BDEF,null));
        }
        try (var data = data(f.model,true)) {
            data.getDirtyVertices().clear();
            f.model.getSkeletonInstance().transformRoot(new Transform().translate(1,2,3));
            data.updateModel();
            assertEquals(1f,data.getBuffer().getFloat(data.getPosOffset()));
            assertEquals(2f,data.getBuffer().getFloat(data.getPosOffset()+4));
            assertEquals(3f,data.getBuffer().getFloat(data.getPosOffset()+8));
            data.getDirtyVertices().clear(); f.model.getMorph().activateMorph("pos",1f); data.updateModel();
            assertEquals(3f,data.getBuffer().getFloat(data.getPosOffset()));
        }
    }

    @Test void layeredMaterialOffsetsUseAllThreeComponents() throws Exception {
        Fixture f = fixture(true);
        try (var data = data(f.model,false)) {
            int secondMaterial = 3 * data.getVertexSize() + data.getPosOffset();
            assertEquals(0f,data.getBuffer().getFloat(secondMaterial));
            assertEquals(0f,data.getBuffer().getFloat(secondMaterial+4));
            assertEquals(.0001f,data.getBuffer().getFloat(secondMaterial+8),1e-7);
        }
    }

    @Test void distinctAlphaPassesBothReceiveSharedVertexChanges() throws Exception {
        Fixture f = fixture(true);
        try (var opaque = data(f.model,false,ModelRenderPass.OPAQUE);
             var mask = data(f.model,false,ModelRenderPass.MASK)) {
            assertEquals(3,opaque.getVertexCount()); assertEquals(3,mask.getVertexCount());
            opaque.getDirtyVertices().clear(); mask.getDirtyVertices().clear();
            f.model.getMorph().activateMorph("pos",1f); f.model.getMorph().activateMorph("uv",1f);
            opaque.updateModel(); mask.updateModel();
            assertEquals(2f,opaque.getBuffer().getFloat(opaque.getPosOffset()));
            assertEquals(2f,mask.getBuffer().getFloat(mask.getPosOffset()));
            assertEquals(.75f,opaque.getBuffer().getFloat(opaque.getTextureUvOffset()));
            assertEquals(0f,mask.getBuffer().getFloat(mask.getTextureUvOffset()));
            opaque.getDirtyVertices().clear(); mask.getDirtyVertices().clear();
            f.model.getMorph().activateMorph("color",1f); mask.updateModel(); opaque.updateModel();
            assertEquals(128,Byte.toUnsignedInt(opaque.getBuffer().get(opaque.getColorOffset()+3)));
            assertEquals(255,Byte.toUnsignedInt(mask.getBuffer().get(mask.getColorOffset()+3)));
        }
    }

    private static FlatModelData data(ModelInstance instance, boolean cpu) {
        return data(instance,cpu,null);
    }
    private static FlatModelData data(ModelInstance instance, boolean cpu, ModelRenderPass pass) {
        return new FlatModelData(instance, RenderState.UML_VERTEX_FORMAT.getVertexSize(),
                FlatModelData.genVertexFormat(RenderState.UML_VERTEX_FORMAT), null, 1, true, cpu, 0, 0, pass);
    }

    private static Fixture fixture() { return fixture(false); }
    private static Fixture fixture(boolean mixed) {
        Bone root = new Bone("root", new Transform(), null); root.setChildren(new Bone[0]);
        Skeleton skeleton = new Skeleton(new Bone[]{root}, root, new Anchor[0], null, new Transform());
        Material material = new Material(new Texture[0], null);
        Sprite sprite = new Sprite(null, new Vector2f(0,0), new Vector2f(1,0), new Vector2f(1,1),
                new Vector2f(0,1), null, null, null, null);
        material.addSprite(new SpriteSet(null, sprite));
        Material mask = new Material(new Texture[0],new lib.kasuga.rendering.models.uml.structure.material.data.MaterialData() {
            @Override public lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode alphaMode() {
                return lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode.MASK;
            }
        });
        mask.addSprite(new SpriteSet(null,sprite));
        Vertex[] vertices = {new Vertex(new Vector3f(), null), new Vertex(new Vector3f(1,0,0), null),
                new Vertex(new Vector3f(0,1,0), null)};
        for (Vertex v : vertices) v.setBinding(new BoneBinding(new lib.kasuga.structure.Pair[0], BoneBindingFunc.IDENTITY, null));
        Mesh mesh = new Mesh(vertices, new Vector3f(0,0,1), new Transform(), mixed ? new Material[]{material,mask} : new Material[]{material}, null);
        for (Vertex v : vertices) { v.getNormals().put(mesh,new Vector3f(0,0,1)); v.addUV(mesh,material,new Vector2f()); v.addUV(mesh,mask,new Vector2f()); }
        MaterialSet materials = new MaterialSet(List.of(),mixed ? List.of(material,mask) : List.of(material));
        Model model = new Model(vertices,new Mesh[]{mesh},new Bone[]{root},skeleton,materials,MeshMode.TRIANGLES,null,null);
        model.getMorph().addMorph("pos", new VertexPosMorph<>(vertices[0],"pos",new Vector3f(2,0,0)));
        model.getMorph().addMorph("uv", new VertexUvMorph<>(vertices[0],"uv",mesh,material,new Vector2f(.75f,.5f)));
        model.getMorph().addMorph("color",new MaterialColorMorph<>(material,"color",new Vector4f(1,1,1,.5f)));
        return new Fixture(new ModelInstance(model,null,null,null,new MaterialSetInstance(materials),null));
    }
    private record Fixture(ModelInstance model) {}
}
