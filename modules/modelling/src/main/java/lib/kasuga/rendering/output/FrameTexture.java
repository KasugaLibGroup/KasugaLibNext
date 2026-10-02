package lib.kasuga.rendering.output;

/**
 * Borrowed read-only OpenGL color handles, shared between a view's consumers.
 * Bottom-left origin, no depth. IDs are GPU handles, not CPU memory addresses;
 * do not write, resize or delete these objects. The GL API cannot enforce this contract.
 */
public record FrameTexture(int framebufferId, int textureId, int width, int height) {}
