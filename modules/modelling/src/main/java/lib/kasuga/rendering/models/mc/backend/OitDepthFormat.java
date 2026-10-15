package lib.kasuga.rendering.models.mc.backend;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL30;

/** Depth blits require matching precision AND component type, including stencil. */
record OitDepthFormat(int internalFormat, int pixelFormat, int pixelType) {

    static OitDepthFormat matching(int depthBits, int componentType, int stencilBits) {
        if (depthBits != 16 && depthBits != 24 && depthBits != 32) {
            throw new IllegalArgumentException("Unsupported scene depth precision: " + depthBits);
        }
        boolean floating = componentType == GL11.GL_FLOAT;
        if (!floating && componentType != GL30.GL_UNSIGNED_NORMALIZED) {
            throw new IllegalArgumentException("Unsupported scene depth component type: " + componentType);
        }
        if (floating && depthBits != 32) {
            throw new IllegalArgumentException("Unsupported floating depth precision: " + depthBits);
        }
        if (stencilBits != 0) {
            if (stencilBits != 8 || (!floating && depthBits != 24)) {
                throw new IllegalArgumentException("Unsupported scene depth/stencil format");
            }
            return new OitDepthFormat(floating ? GL30.GL_DEPTH32F_STENCIL8 : GL30.GL_DEPTH24_STENCIL8,
                    GL30.GL_DEPTH_STENCIL,
                    floating ? GL30.GL_FLOAT_32_UNSIGNED_INT_24_8_REV : GL30.GL_UNSIGNED_INT_24_8);
        }
        int internal = floating ? GL30.GL_DEPTH_COMPONENT32F : switch (depthBits) {
            case 16 -> GL14.GL_DEPTH_COMPONENT16;
            case 24 -> GL14.GL_DEPTH_COMPONENT24;
            default -> GL14.GL_DEPTH_COMPONENT32;
        };
        return new OitDepthFormat(internal, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT);
    }
}
