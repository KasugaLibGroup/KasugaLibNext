package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfAsset;
import lib.kasuga.rendering.models.uml.typo.gltf.GltfModelData;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelRenderPassTest {

    @Test
    void mapsAllFormatNeutralAlphaModesToDistinctPasses() {
        assertEquals(ModelRenderPass.OPAQUE, ModelRenderPass.from(MaterialAlphaMode.OPAQUE));
        assertEquals(ModelRenderPass.MASK, ModelRenderPass.from(MaterialAlphaMode.MASK));
        assertEquals(ModelRenderPass.TRANSLUCENT, ModelRenderPass.from(MaterialAlphaMode.BLEND));
    }

    @Test
    void preservesTheGltfMaskCutoff() {
        GltfAsset.Material definition = new GltfAsset.Material(
                -1, GltfAsset.AlphaMode.MASK, new Vector4f(1f),
                0f, 1f, 0.37f, false);
        GltfModelData.GltfMaterialData data = new GltfModelData.GltfMaterialData(definition);
        Material material = new Material(new lib.kasuga.rendering.models.uml.structure.material.Texture[0], data);

        assertEquals(ModelRenderPass.MASK, ModelRenderPass.classify(material));
        assertEquals(0.37f, ModelRenderPass.alphaCutoff(material));
    }

    @Test
    void unknownMaterialDataUsesTheOpaqueFallback() {
        Material material = new Material(new lib.kasuga.rendering.models.uml.structure.material.Texture[0], null);

        assertEquals(ModelRenderPass.OPAQUE, ModelRenderPass.classify(material));
        assertEquals(0f, ModelRenderPass.alphaCutoff(material));
    }

    @Test
    void blendUsesTheExplicitNearZeroCutoff() {
        assertEquals(1f / 255f, ModelRenderPass.BLEND_ALPHA_CUTOFF);
        assertEquals(2, ModelRenderPass.TRANSLUCENT.shaderAlphaMode());

        GltfAsset.Material definition = new GltfAsset.Material(
                -1, GltfAsset.AlphaMode.BLEND, new Vector4f(1f),
                0f, 1f, 0.8f, false);
        GltfModelData.GltfMaterialData data = new GltfModelData.GltfMaterialData(definition);
        Material material = new Material(new lib.kasuga.rendering.models.uml.structure.material.Texture[0], data);
        assertEquals(ModelRenderPass.TRANSLUCENT, ModelRenderPass.classify(material));
        assertEquals(ModelRenderPass.BLEND_ALPHA_CUTOFF, ModelRenderPass.alphaCutoff(material));
    }

    @Test
    void passOrderKeepsDepthWritingGeometryAheadOfBlend() {
        assertTrue(ModelRenderPass.OPAQUE.ordinal() < ModelRenderPass.MASK.ordinal());
        assertTrue(ModelRenderPass.MASK.ordinal() < ModelRenderPass.TRANSLUCENT.ordinal());
    }
}
