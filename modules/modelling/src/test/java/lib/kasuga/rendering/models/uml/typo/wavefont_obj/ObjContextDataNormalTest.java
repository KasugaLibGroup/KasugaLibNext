package lib.kasuga.rendering.models.uml.typo.wavefont_obj;

import lib.kasuga.rendering.models.mc.typo.KsgObjLoader;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.basic.data.BoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.Texture;
import lib.kasuga.rendering.models.uml.structure.skeleton.Anchor;
import lib.kasuga.rendering.models.uml.structure.skeleton.Bone;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.BoneData;
import lib.kasuga.rendering.models.uml.structure.skeleton.data.SkeletonData;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObjContextDataNormalTest {

    @Test
    void zeroVertexNormalFallsBackToFaceNormal() {
        var loader = new MinimalObjLoader();
        var models = loader.load(
                ResourceLocation.fromNamespaceAndPath("kasuga_lib", "normal_test"),
                """
                o triangle
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 0 1
                vn 0 0 0
                f 1/1/1 2/2/1 3/3/1
                """
        );

        assertFalse(models.isEmpty());
        var model = models.values().iterator().next();
        assertTrue(model.getVertices().length > 0);
        for (var vertex : model.getVertices()) {
            assertFalse(vertex.getNormals().isEmpty());
            for (var normal : vertex.getNormals().values()) {
                assertTrue(Float.isFinite(normal.x()));
                assertTrue(Float.isFinite(normal.y()));
                assertTrue(Float.isFinite(normal.z()));
                assertTrue(normal.lengthSquared() > 1e-12f);
            }
        }
    }

    @Test
    void flatObjLoaderUsesTheSameFaceNormalFallback() {
        var mesh = KsgObjLoader.loadFlatMesh("""
                mtllib zero-normal.mtl
                o triangle
                v 0 0 0
                v 1 0 0
                v 0 1 0
                vt 0 0
                vt 1 0
                vt 0 1
                vn 0 0 0
                usemtl surface
                f 1/1/1 2/2/1 3/3/1
                """, """
                newmtl surface
                map_Kd kasuga_lib:block/test/surface
                newmtl none
                """, null);

        for (int vertex = 0; vertex < mesh.vertexCount(); vertex++) {
            int offset = vertex * 16 + 3;
            assertTrue(Float.isFinite(mesh.vertices()[offset]));
            assertTrue(Float.isFinite(mesh.vertices()[offset + 1]));
            assertTrue(Float.isFinite(mesh.vertices()[offset + 2]));
            float lengthSquared = mesh.vertices()[offset] * mesh.vertices()[offset]
                    + mesh.vertices()[offset + 1] * mesh.vertices()[offset + 1]
                    + mesh.vertices()[offset + 2] * mesh.vertices()[offset + 2];
            assertTrue(lengthSquared > 1e-12f);
        }
    }

    private static final class MinimalObjLoader
            extends ObjModelLoader<String, ResourceLocation, String> {

        private final Material material =
                new Material(new Texture[]{new Texture("surface", 1f, 1f, null)}, null);

        private MinimalObjLoader() {
            super("minimal_obj", true, true, false);
        }

        @Override
        public VertexData getVertexData(ObjContextData data, ObjModelLoader loader, String boneName) {
            return null;
        }

        @Override
        public MeshData getMeshData(ObjContextData data, ObjModelLoader loader) {
            return null;
        }

        @Override
        public Texture getTexture(ObjContextData data, ObjModelLoader loader, String mtlName) {
            return material.getTextures()[0];
        }

        @Override
        public Material getMaterial(ObjContextData data, ObjModelLoader loader, String mtlName) {
            return material;
        }

        @Override
        public BoneData getBoneData(ObjModelLoader loader, String boneName) {
            return null;
        }

        @Override
        public BoneBindingData getBoneBindingData(ObjModelLoader loader, Bone bone, Vertex vertex) {
            return null;
        }

        @Override
        public SkeletonData getSkeletonData(ObjModelLoader loader, Bone[] bones) {
            return null;
        }

        @Override
        public ModelData getModelData(ObjModelLoader loader) {
            return null;
        }

        @Override
        public ResourceLocation getIdentifier(ObjModelLoader loader, String input) {
            return ResourceLocation.fromNamespaceAndPath("kasuga_lib", "normal_test");
        }

        @Override
        public Anchor[] getAnchors(ObjModelLoader loader, Bone[] bones, Bone rootBone) {
            return new Anchor[0];
        }

        @Override
        public void consumeTexture(ObjTextureData texture) {
        }

        @Override
        public String getTextureContent(String mtlUrl) {
            return "";
        }

        @Override
        public String getAsString(String input) {
            return input;
        }

        @Override
        public boolean isValidInput(Object input) {
            return input instanceof String;
        }

        @Override
        public Texture loadTexture(Object textureIdentifier) {
            return material.getTextures()[0];
        }
    }
}
