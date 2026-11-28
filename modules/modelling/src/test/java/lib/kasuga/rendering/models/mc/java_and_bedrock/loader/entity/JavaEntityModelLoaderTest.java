package lib.kasuga.rendering.models.mc.java_and_bedrock.loader.entity;

import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.structure.Model;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.MaterialSet;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import net.minecraft.client.model.CreeperModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaEntityModelLoaderTest {
    @Test
    void importsCreeperQuadsAndSkinUvsInModelSpace() {
        Model model = bake(creeperRoot());
        assertEquals(36, model.getMeshes().length);
        assertEquals(7, model.getBones().length);
        assertEquals(0, Arrays.stream(model.getVertices()).mapToDouble(vertex -> vertex.getPosition().y).min().orElseThrow(), 1e-6);
        assertEquals(1.625, Arrays.stream(model.getVertices()).mapToDouble(vertex -> vertex.getPosition().y).max().orElseThrow(), 1e-6);

        for (Mesh mesh : model.getMeshes()) {
            assertEquals(4, mesh.getVertices().length);
            Vector3f first = mesh.getVertices()[0].getPosition();
            Vector3f edge = new Vector3f(mesh.getVertices()[1].getPosition()).sub(first);
            Vector3f diagonal = new Vector3f(mesh.getVertices()[2].getPosition()).sub(first);
            assertTrue(edge.cross(diagonal).dot(mesh.getNormal()) > 0);
            for (var vertex : mesh.getVertices()) {
                var uv = vertex.getUV(mesh, mesh.getMaterials()[0]);
                assertTrue(uv.x >= 0 && uv.x <= 1 && uv.y >= 0 && uv.y <= 1);
                assertEquals(1f, vertex.getBinding().getWeights()[0].getSecond(), 0);
            }
        }
    }

    @Test
    void posesAnInstanceAroundTheOriginalPartPivot() {
        Model model = bake(creeperRoot());
        ModelInstance instance = new ModelInstance(model, null, null, null, null, null);
        ModelInstance other = new ModelInstance(model, null, null, null, null, null);
        Bone head = model.getSkeleton().getBoneMap().get("root/head");

        ModelPart pose = creeperRoot();
        pose.getChild("head").yRot = (float) (Math.PI / 2);
        JavaEntityModelLoader.applyPose(instance, pose);
        instance.getSkeletonInstance().updateTransform();

        Vector3f local = new Vector3f(0, 0, -0.25f);
        Vector3f bindPoint = model.getSkeleton().getBindingAbsolute(head).apply(new Vector3f(local));
        Vector3f posedPoint = instance.getSkeletonInstance().getAbsoluteTransforms().get(head).apply(new Vector3f(local));
        Vector3f otherPoint = other.getSkeletonInstance().getAbsoluteTransforms().get(head).apply(new Vector3f(local));
        assertEquals(0.25f, posedPoint.x, 1e-6f);
        assertEquals(bindPoint.y, posedPoint.y, 1e-6f);
        assertEquals(0, posedPoint.z, 1e-6f);
        assertEquals(bindPoint, otherPoint);
    }

    @Test
    void retainsGeometryUnderAnEmptyParentAndHonorsHiddenSubtrees() {
        MeshDefinition definition = new MeshDefinition();
        CubeListBuilder cube = CubeListBuilder.create().addBox(0, 0, 0, 2, 2, 2);
        PartDefinition group = definition.getRoot().addOrReplaceChild("group", CubeListBuilder.create(), PartPose.offset(0, 2, 0));
        group.addOrReplaceChild("child", cube, PartPose.ZERO);
        PartDefinition hidden = definition.getRoot().addOrReplaceChild("hidden", cube, PartPose.ZERO);
        hidden.addOrReplaceChild("child", cube, PartPose.ZERO);
        ModelPart root = definition.getRoot().bake(16, 16);
        root.getChild("hidden").visible = false;

        Model model = bake(root);
        Bone groupBone = model.getSkeleton().getBoneMap().get("root/group");
        Bone childBone = model.getSkeleton().getBoneMap().get("root/group/child");
        assertSame(groupBone, childBone.getParent());
        assertEquals(6, model.getMeshes().length);
        assertTrue(Arrays.stream(model.getVertices()).allMatch(vertex -> vertex.getBinding().getWeights()[0].getFirst() == childBone));
    }

    @Test
    void rejectsASingularBindPose() {
        ModelPart root = creeperRoot();
        root.getChild("head").xScale = 0;

        assertThrows(IllegalArgumentException.class, () -> bake(root));
    }

    private static ModelPart creeperRoot() {
        return CreeperModel.createBodyLayer(CubeDeformation.NONE).bakeRoot();
    }

    private static Model bake(ModelPart root) {
        Texture texture = new Texture("test", 64, 32, null);
        Material material = new Material(new Texture[]{texture}, null);
        return JavaEntityModelLoader.bake(root, new MaterialSet(texture, material), material);
    }
}
