package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.*;

class OitDepthFormatTest {
    @Test
    void appleFloatDepthIsNotReplacedByFixedPointDepth() {
        var floating = OitDepthFormat.matching(32, GL11.GL_FLOAT, 0);
        var fixed = OitDepthFormat.matching(32, GL30.GL_UNSIGNED_NORMALIZED, 0);
        assertEquals(GL30.GL_DEPTH_COMPONENT32F, floating.internalFormat());
        assertEquals(GL14.GL_DEPTH_COMPONENT32, fixed.internalFormat());
        assertNotEquals(floating, fixed);
    }

    @Test
    void preservesFixedDepthPrecision() {
        assertEquals(GL14.GL_DEPTH_COMPONENT16,
                OitDepthFormat.matching(16, GL30.GL_UNSIGNED_NORMALIZED, 0).internalFormat());
        assertEquals(GL14.GL_DEPTH_COMPONENT24,
                OitDepthFormat.matching(24, GL30.GL_UNSIGNED_NORMALIZED, 0).internalFormat());
    }

    @Test
    void preservesCombinedDepthStencilAttachments() {
        assertEquals(new OitDepthFormat(GL30.GL_DEPTH32F_STENCIL8, GL30.GL_DEPTH_STENCIL,
                        GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV),
                OitDepthFormat.matching(32, GL11.GL_FLOAT, 8));
        assertEquals(new OitDepthFormat(GL30.GL_DEPTH24_STENCIL8, GL30.GL_DEPTH_STENCIL,
                        GL30.GL_UNSIGNED_INT_24_8),
                OitDepthFormat.matching(24, GL30.GL_UNSIGNED_NORMALIZED, 8));
    }

    @Test
    void unsupportedAttachmentsFailClosedInsteadOfGuessing() {
        assertThrows(IllegalArgumentException.class, () -> OitDepthFormat.matching(0, GL11.GL_FLOAT, 0));
        assertThrows(IllegalArgumentException.class, () -> OitDepthFormat.matching(24, GL11.GL_FLOAT, 0));
        assertThrows(IllegalArgumentException.class, () -> OitDepthFormat.matching(32, GL11.GL_INT, 0));
        assertThrows(IllegalArgumentException.class, () -> OitDepthFormat.matching(16, GL30.GL_UNSIGNED_NORMALIZED, 8));
    }
}
