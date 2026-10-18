package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import lib.kasuga.rendering.models.uml.backend.gpu.GpuUploadRing;
import lib.kasuga.rendering.models.uml.backend.gpu.TextureUploadRing;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.BitSet;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

import static lib.kasuga.rendering.models.mc.backend.GlFixture.*;

/** Queues real GPU readers before reusing both VBO and samplerBuffer storage. */
final class UploadRingRegression {
    static void run() throws Exception {
        queuedReads(false);
        queuedReads(true);
        sparseReuseAndResize();
        textureViewsResize(false);
        textureViewsResize(true);
    }

    private static void textureViewsResize(boolean common) throws Exception {
        try (var fixture = new GlFixture(); var ring = common
                ? new TextureUploadRing(new GpuUploadRing()) : new TextureUploadRing()) {
            int color = fixture.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 1, 1, null);
            fixture.framebuffer(color, 0); GL11.glViewport(0, 0, 1, 1);
            int program = fixture.program("""
                    #version 150
                    uniform samplerBuffer Source;
                    uniform int Index;
                    out vec4 fragColor;
                    void main() { fragColor = texelFetch(Source, Index); }
                    """);
            FloatBuffer data = MemoryUtil.memAllocFloat(2048), actual = MemoryUtil.memAllocFloat(4);
            try {
                for (int i = 0; i < data.capacity(); i++) data.put(i, i * .125f);
                GL20.glUseProgram(program); integer(program, "Source", 0);
                for (int size : new int[]{0, 4, 4, 4, 1024, 4, 4, 4, 2048, 2048, 2048}) {
                    data.position(0).limit(size);
                    GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, ring.upload(data));
                    if (size == 0) continue;
                    integer(program, "Index", size / 4 - 1);
                    GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3); ring.markSubmitted();
                    actual.clear(); GL11.glReadPixels(0, 0, 1, 1, GL11.GL_RGBA, GL11.GL_FLOAT, actual);
                    for (int c = 0; c < 4; c++) expect(actual.get(c), (size - 4 + c) * .125f, 0, "resized TBO view");
                    if (data.position() != 0 || data.limit() != size) throw new AssertionError("TBO consumed input");
                }
                noError("texture ring views, empty upload and resize");
            } finally {
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0); GL20.glUseProgram(0);
                MemoryUtil.memFree(data); MemoryUtil.memFree(actual);
            }
        }
    }

    private static void queuedReads(boolean forceBusy) throws Exception {
        int frames = 24;
        var device = new GpuUploadRing.GlDevice() {
            @Override public boolean ready(long fence) { return !forceBusy && super.ready(fence); }
        };
        try (var fixture = new GlFixture(); var vertices = new GpuUploadRing(3, device);
             var palette = new TextureUploadRing(new GpuUploadRing(3, device))) {
            int program = GlslProgram.link("""
                    #version 150
                    in vec4 Value;
                    uniform samplerBuffer Palette;
                    out vec4 Result;
                    void main() { Result = Value + texelFetch(Palette, 0); gl_Position = vec4(0.0); }
                    """, null, Map.of("Value", 0), new String[]{"Result"});
            int texture = GL11.glGenTextures(), output = GL15.glGenBuffers(), sentinel = GL15.glGenBuffers();
            ByteBuffer source = MemoryUtil.memAlloc(16).order(ByteOrder.nativeOrder());
            FloatBuffer bones = MemoryUtil.memAllocFloat(4), result = MemoryUtil.memAllocFloat(frames * 4);
            Set<Integer> views = new HashSet<>();
            try {
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, output);
                GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, (long) frames * 16, GL15.GL_STREAM_READ);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, sentinel);
                GL20.glUseProgram(program); integer(program, "Palette", 0);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
                for (int frame = 0; frame < frames; frame++) {
                    for (int c = 0; c < 4; c++) source.putFloat(c * 4, frame + c * .25f);
                    bones.clear(); for (int c = 0; c < 4; c++) bones.put(frame * 2 + c * .5f); bones.flip();
                    int previousTexture = GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER);
                    int vbo = vertices.upload(source), textureView = palette.upload(bones);
                    views.add(textureView);
                    if (GL11.glGetInteger(GL31.GL_TEXTURE_BINDING_BUFFER) != previousTexture)
                        throw new AssertionError("Texture ring changed caller binding");
                    if (GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER) != sentinel)
                        throw new AssertionError("Ring changed COPY_WRITE binding");
                    if (source.position() != 0 || bones.position() != 0)
                        throw new AssertionError("Ring consumed staging input");
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vbo);
                    GL20.glEnableVertexAttribArray(0);
                    GL20.glVertexAttribPointer(0, 4, GL11.GL_FLOAT, false, 16, 0L);
                    GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureView);
                    GL30.glBindBufferRange(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, output, (long) frame * 16, 16);
                    GL30.glBeginTransformFeedback(GL11.GL_POINTS);
                    GL11.glDrawArrays(GL11.GL_POINTS, 0, 1);
                    GL30.glEndTransformFeedback();
                    vertices.markSubmitted(); palette.markSubmitted();
                }
                // Read once after all readers have been queued, so an overwrite
                // of in-flight storage would corrupt an earlier frame's result.
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, output);
                GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, 0, result);
                for (int frame = 0; frame < frames; frame++) for (int c = 0; c < 4; c++)
                    expect(result.get(frame * 4 + c), frame * 3 + c * .75f, 0, "queued ring frame=" + frame);
                if (forceBusy && (vertices.stats().busyOrphans() != frames - 3 || palette.stats().busyOrphans() != frames - 3))
                    throw new AssertionError("Forced busy storage did not orphan");
                noError("queued upload rings");
                int lastVbo = vertices.bufferId(), lastTbo = palette.bufferId();
                vertices.close(); palette.close(); vertices.close(); palette.close();
                if (GL15.glIsBuffer(lastVbo) || GL15.glIsBuffer(lastTbo)) throw new AssertionError("Ring leaked buffers");
                if (views.size() != 3 || views.stream().anyMatch(GL11::glIsTexture))
                    throw new AssertionError("Texture ring did not retire its three views");
            } finally {
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0); GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0); GL20.glUseProgram(0);
                GL15.glDeleteBuffers(output); GL15.glDeleteBuffers(sentinel); GL11.glDeleteTextures(texture);
                GL20.glDeleteProgram(program);
                MemoryUtil.memFree(source); MemoryUtil.memFree(bones); MemoryUtil.memFree(result);
            }
        }
    }

    private static void sparseReuseAndResize() {
        try (var ring = new GpuUploadRing()) {
            ByteBuffer data = MemoryUtil.memAlloc(1024).order(ByteOrder.nativeOrder());
            ByteBuffer actual = MemoryUtil.memAlloc(1024);
            try {
                for (int i = 0; i < 256; i++) data.putInt(i * 4, i);
                BitSet dirty = new BitSet();
                for (int frame = 0; frame < 32; frame++) {
                    dirty.clear(); int vertex = (frame * 7) % 256; dirty.set(vertex);
                    data.putInt(vertex * 4, frame + 1000);
                    int buffer = ring.upload(data, dirty, 4, 1, false);
                    GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, buffer);
                    actual.clear(); GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, 0, actual);
                    for (int i = 0; i < 1024; i++) if (actual.get(i) != data.get(i))
                        throw new AssertionError("Sparse slot lost change at " + i);
                    ring.markSubmitted(); GL11.glFinish();
                }
                if (ring.stats().storageAllocations() != 3 || ring.stats().busyOrphans() != 0)
                    throw new AssertionError("Ready ring failed to reuse allocations");
                data.limit(16); ring.upload(data, new BitSet(), 4, 0, false); ring.markSubmitted();
                data.clear(); dirty.clear(); dirty.set(255); ring.upload(data, dirty, 4, 0, false);
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, ring.bufferId());
                actual.clear(); GL15.glGetBufferSubData(GL31.GL_COPY_READ_BUFFER, 0, actual);
                for (int i = 0; i < 1024; i++) if (actual.get(i) != data.get(i))
                    throw new AssertionError("Resize revived stale slot bytes");
                noError("sparse upload ring and resize");
            } finally {
                GL15.glBindBuffer(GL31.GL_COPY_READ_BUFFER, 0);
                MemoryUtil.memFree(data); MemoryUtil.memFree(actual);
            }
        }
    }
}
