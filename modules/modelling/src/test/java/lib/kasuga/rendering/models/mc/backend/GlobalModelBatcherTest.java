package lib.kasuga.rendering.models.mc.backend;

import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.models.uml.backend.DepthBias;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GlobalModelBatcherTest {

    @Test
    void limitsAChunkByInstanceTextureCapacity() {
        assertEquals(3, GlobalModelBatcher.nextChunkEnd(5, 0, 27, ignored -> 0));
        assertEquals(5, GlobalModelBatcher.nextChunkEnd(5, 3, 27, ignored -> 0));
    }

    @Test
    void limitsAChunkByCombinedBoneTextureCapacity() {
        assertEquals(2, GlobalModelBatcher.nextChunkEnd(4, 0, 100, ignored -> 40));
    }

    @Test
    void preservesTheSignedShortObjectIndexLimit() {
        assertEquals(Short.MAX_VALUE, GlobalModelBatcher.nextChunkEnd(
                40_000, 0, Integer.MAX_VALUE, ignored -> 0));
    }

    @Test
    void rejectsAnItemThatCannotFitInTheAvailableTextureBuffer() {
        assertThrows(IllegalStateException.class,
                () -> GlobalModelBatcher.nextChunkEnd(1, 0, 8, ignored -> 0));
        assertThrows(IllegalStateException.class,
                () -> GlobalModelBatcher.nextChunkEnd(1, 0, 100, ignored -> 101));
    }

    @Test
    void instancesIdenticalBatchesAndKeepsSmallRunsInMixedBatchesMerged() {
        assertFalse(GlobalModelBatcher.shouldInstance(1, 1, 32_768));
        assertTrue(GlobalModelBatcher.shouldInstance(2, 2, 512));
        assertFalse(GlobalModelBatcher.shouldInstance(2, 4, 512));
        assertTrue(GlobalModelBatcher.shouldInstance(2, 4, 32_768));
    }

    @Test
    void keepsOpaqueAndMaskSectionsInDifferentBatches() {
        GlobalModelBatcher.BatchKey opaque = new GlobalModelBatcher.BatchKey(
                ModelRenderPass.OPAQUE, VertexFormat.Mode.QUADS, 0, 0, DepthBias.NONE);
        GlobalModelBatcher.BatchKey mask = new GlobalModelBatcher.BatchKey(
                ModelRenderPass.MASK, VertexFormat.Mode.QUADS, 0, 0, DepthBias.NONE);

        assertNotEquals(opaque, mask);
    }

    @Test
    void separatesDepthPoliciesWithoutSplittingEquivalentPolicies() {
        var plain = new GlobalModelBatcher.BatchKey(
                ModelRenderPass.OPAQUE, VertexFormat.Mode.QUADS, 0, 0, DepthBias.NONE);
        var overlay = new GlobalModelBatcher.BatchKey(
                ModelRenderPass.OPAQUE, VertexFormat.Mode.QUADS, 0, 0, DepthBias.DECAL);
        var equivalent = new GlobalModelBatcher.BatchKey(
                ModelRenderPass.OPAQUE, VertexFormat.Mode.QUADS, 0, 0, new DepthBias(-1, -1));
        assertNotEquals(plain, overlay, "a decal must not inherit a neighbouring object's depth policy");
        assertEquals(overlay, equivalent);
    }
}
