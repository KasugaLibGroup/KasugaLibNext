package lib.kasuga.rendering.models.mc.typo;

import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ObjMaterialDataTest {

    @Test
    void binaryTextureTransparencyUsesMaskInsteadOfOpaque() {
        assertEquals(
                MaterialAlphaMode.MASK,
                ObjMaterialData.from(1f, true, false).alphaMode()
        );
    }

    @Test
    void partialTransparencyUsesBlend() {
        assertEquals(
                MaterialAlphaMode.BLEND,
                ObjMaterialData.from(1f, true, true).alphaMode()
        );
    }

    @Test
    void mtlDissolveUsesBlend() {
        assertEquals(
                MaterialAlphaMode.BLEND,
                ObjMaterialData.from(0.75f, false, false).alphaMode()
        );
    }

    @Test
    void opaqueTextureRemainsOpaque() {
        assertEquals(
                MaterialAlphaMode.OPAQUE,
                ObjMaterialData.from(1f, false, false).alphaMode()
        );
    }
}
