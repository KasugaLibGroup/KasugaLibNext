package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.fbo.FrameBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo.TBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.ubo.UBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.vbo.VBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.vao.VertexArrayObject;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryStack;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.expect;

/** Exercises the legacy GPU wrappers against actual GL object lifetimes and buffer contents. */
final class GpuBufferLifecycleRegression {
    static void run() throws Exception {
        int sentinel = GL15.glGenBuffers();
        int read = GL15.glGenBuffers(), write = GL15.glGenBuffers();
        int texture = GL11.glGenTextures();
        int active = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        try {
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, sentinel);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, 16, GL15.GL_DYNAMIC_DRAW);
            GL15.glBindBuffer(GL31.GL_UNIFORM_BUFFER, sentinel);
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, sentinel);
            GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, read);
            GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, write);
            GL13.glActiveTexture(GL13.GL_TEXTURE4);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            try (var vbo = new VBOBuffer(16, GL15.GL_DYNAMIC_DRAW);
                 var ubo = new UBOBuffer(16, GL15.GL_DYNAMIC_DRAW);
                 var tbo = new TBOBuffer(() -> 4, 16, GL15.GL_DYNAMIC_DRAW)) {
                verifyStorage(vbo, vbo.getId(), GL15.GL_ARRAY_BUFFER);
                verifyStorage(ubo, ubo.getId(), GL31.GL_UNIFORM_BUFFER);
                verifyStorage(tbo, tbo.getBufferId(), GL31.GL_TEXTURE_BUFFER);
                expect(GL15.glGetInteger(GL31.GL_TEXTURE_BUFFER), sentinel, 0, "TBO buffer binding restored");
                expect(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), GL13.GL_TEXTURE1, 0, "active texture restored");
                GL13.glActiveTexture(GL13.GL_TEXTURE4);
                expect(GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER), texture, 0, "TBO unit binding restored");
                GL13.glActiveTexture(GL13.GL_TEXTURE1);
                tbo.bind(0); tbo.unbind();
                expect(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE), GL13.GL_TEXTURE1, 0, "TBO bind/unbind active unit");
                GL30.glBindBufferBase(GL31.GL_UNIFORM_BUFFER, 2, sentinel);
                ubo.bind(2); ubo.unbind();
                expect(GL30.glGetIntegeri(GL31.GL_UNIFORM_BUFFER_BINDING, 2), sentinel, 0, "UBO indexed binding restored");
                check(vbo.getContext().isEmpty() && tbo.getContext().isEmpty(), "contexts reusable after resize");
            }
            expect(GL15.glGetInteger(GL31.GL_COPY_READ_BUFFER), read, 0, "copy read restored");
            expect(GL15.glGetInteger(GL31.GL_COPY_WRITE_BUFFER), write, 0, "copy write restored");
            vertexArrays();
            framebuffers(false);
            framebuffers(true);
        } finally {
            GL13.glActiveTexture(GL13.GL_TEXTURE4);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
            GL13.glActiveTexture(active);
            GL11.glDeleteTextures(texture);
            GL15.glDeleteBuffers(sentinel); GL15.glDeleteBuffers(read); GL15.glDeleteBuffers(write);
        }
    }

    private static void verifyStorage(GpuBuffer<?> buffer, int id, int target) {
        try (var stack = MemoryStack.stackPush()) {
            var input = stack.malloc(16).putInt(11).putInt(22).putInt(33).putInt(44).flip();
            buffer.updateAll(input);
            buffer.resize(32, true);
            buffer.resize(8, true);
            expect(buffer.getSize(), 8, 0, "resize capacity metadata");
            int previous = GL15.glGetInteger(switch (target) {
                case GL15.GL_ARRAY_BUFFER -> GL15.GL_ARRAY_BUFFER_BINDING;
                case GL31.GL_UNIFORM_BUFFER -> GL31.GL_UNIFORM_BUFFER_BINDING;
                default -> target;
            });
            GL15.glBindBuffer(target, id);
            var actual = stack.malloc(8);
            GL15.glGetBufferSubData(target, 0, actual);
            expect(actual.getInt(0), 11, 0, "resize preserved prefix 0");
            expect(actual.getInt(4), 22, 0, "resize preserved prefix 1");
            GL15.glBindBuffer(target, previous);
            buffer.resize(48, false);
            buffer.updateAll(stack.malloc(4).putInt(55).flip());
            buffer.updateRange(stack.malloc(4).putInt(66).flip(), 44);
            GL15.glBindBuffer(target, id);
            expect(GL15.glGetBufferParameteri(target, GL15.GL_BUFFER_SIZE), 48, 0, "partial update keeps capacity");
            GL15.glBindBuffer(target, previous);
        }
    }

    private static void vertexArrays() throws Exception {
        int previous = GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        var vao = new VertexArrayObject(0, 0);
        int id = vao.getVaoId();
        vao.bind(0);
        check(GL30.glIsVertexArray(id), "VAO exists after binding");
        vao.close(); vao.close();
        check(!GL30.glIsVertexArray(id), "VAO deleted by close");
        expect(GL30.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING), previous, 0, "VAO binding restored on close");
    }

    private static void framebuffers(boolean depth) throws Exception {
        int draw = GL30.glGenFramebuffers(), read = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, draw);
        GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, read);
        try {
            var fbo = new FrameBuffer.Builder(8, 8).addColorAttachment(GL11.GL_RGBA, GL11.GL_RGBA8, GL11.GL_UNSIGNED_BYTE)
                    .setHasDepth(depth).build();
            int id = fbo.getFboId();
            fbo.resize(16, 12);
            expect(fbo.getDepthAttachment() >= 0 ? 1 : 0, depth ? 1 : 0, 0, "optional depth preserved");
            expect(GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), draw, 0, "draw FBO restored");
            expect(GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), read, 0, "read FBO restored");
            int color = fbo.getColorTextureId(0), rbo = fbo.getDepthAttachment();
            fbo.bind(0);
            GL11.glClearColor(.25f, .5f, .75f, 1f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);
            try (var stack = MemoryStack.stackPush()) {
                var pixel = stack.mallocFloat(4);
                GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, pixel);
                expect(pixel.get(1), .5f, .01f, "FBO color attachment draw selection");
            }
            fbo.close(); fbo.close();
            check(!GL30.glIsFramebuffer(id) && !GL11.glIsTexture(color), "FBO and color attachment deleted");
            if (depth) check(!GL30.glIsRenderbuffer(rbo), "depth attachment deleted");
            expect(GL30.glGetInteger(GL30.GL_DRAW_FRAMEBUFFER_BINDING), draw, 0, "close restores draw FBO");
            expect(GL30.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING), read, 0, "close restores read FBO");
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            GL30.glDeleteFramebuffers(draw); GL30.glDeleteFramebuffers(read);
        }
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
