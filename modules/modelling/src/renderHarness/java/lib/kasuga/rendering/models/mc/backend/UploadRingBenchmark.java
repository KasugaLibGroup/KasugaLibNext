package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import lib.kasuga.rendering.models.uml.backend.gpu.TextureUploadRing;
import lib.kasuga.rendering.models.uml.backend.gpu.GpuUploadRing;
import java.util.BitSet;
import org.lwjgl.opengl.*;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** A/B upload + TF submission; all completion/readback is outside CPU intervals. */
final class UploadRingBenchmark {
    private static final int VERTICES = 8192;

    static List<Map<String, Object>> run(int warmup, int frames) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (int bytes : new int[]{64 * 36 * 4, 512 * 36 * 4}) for (boolean queued : new boolean[]{false, true}) {
            List<Map<String, Object>> trials = new ArrayList<>();
            for (int trial = 0; trial < 3; trial++) {
                Map<String, Object> baseline, ring;
                if (trial % 2 == 0) {
                    baseline = measure(false, queued, bytes, warmup, frames);
                    ring = measure(true, queued, bytes, warmup, frames);
                } else {
                    ring = measure(true, queued, bytes, warmup, frames);
                    baseline = measure(false, queued, bytes, warmup, frames);
                }
                trials.add(Map.of("baseline", baseline, "ring", ring));
            }
            result.add(Map.of("payloadBytes", bytes, "mode", queued ? "queued: flush after submission" : "retired: finish outside CPU interval",
                    "scope", "full TBO upload + real TF palette reads; synthetic, not whole-game FPS",
                    "baseline", "glBufferData per snapshot", "trials", trials));
        }
        for (boolean queued : new boolean[]{false, true}) {
            List<Map<String, Object>> trials = new ArrayList<>();
            for (int trial = 0; trial < 3; trial++) {
                Map<String, Object> baseline, ring;
                if (trial % 2 == 0) {
                    baseline = sparseVertices(false, queued, warmup, frames);
                    ring = sparseVertices(true, queued, warmup, frames);
                } else {
                    ring = sparseVertices(true, queued, warmup, frames);
                    baseline = sparseVertices(false, queued, warmup, frames);
                }
                trials.add(Map.of("baseline", baseline, "ring", ring));
            }
            result.add(Map.of("payloadBytes", 4096 * 16, "mode", queued ? "queued" : "retired",
                    "scope", "one dirty vertex among 4096 + real point rasterization; synthetic, not whole-game FPS",
                    "baseline", "single VBO glBufferSubData", "trials", trials));
        }
        return result;
    }

    private static Map<String, Object> sparseVertices(boolean useRing, boolean queued, int warmup, int frames) {
        final int count = 4096;
        try (var fixture = new GlFixture(); var ring = new GpuUploadRing()) {
            int program = fixture.program("""
                    #version 150
                    in vec4 Value;
                    out vec4 Color;
                    void main() {
                        vec2 p = vec2(gl_VertexID % 64, gl_VertexID / 64);
                        gl_Position = vec4((p + 0.5) / 32.0 - 1.0, 0.0, 1.0);
                        gl_PointSize = 1.0;
                        Color = Value;
                    }
                    """, """
                    #version 150
                    in vec4 Color;
                    out vec4 fragColor;
                    void main() { fragColor = Color; }
                    """);
            int color = fixture.texture(GL30.GL_RGBA32F, GL11.GL_RGBA, 64, 64, null);
            fixture.framebuffer(color, 0); GL11.glViewport(0, 0, 64, 64);
            int baseline = GL15.glGenBuffers();
            ByteBuffer data = MemoryUtil.memAlloc(count * 16).order(ByteOrder.nativeOrder());
            try {
                for (int i = 0; i < count; i++) for (int c = 0; c < 4; c++) data.putFloat(i * 16 + c * 4, .25f);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, baseline);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, data, GL15.GL_DYNAMIC_DRAW);
                int location = GL20.glGetAttribLocation(program, "Value");
                GL20.glEnableVertexAttribArray(location); GL20.glUseProgram(program);
                BitSet dirty = new BitSet(); long[] samples = new long[frames];
                long allocationsBefore = 0, bytesBefore = 0;
                for (int frame = 0; frame < warmup + frames; frame++) {
                    int vertex = frame % count; dirty.clear(); dirty.set(vertex);
                    data.putFloat(vertex * 16, frame % 16 / 16f);
                    if (frame == warmup) {
                        GL11.glFinish(); allocationsBefore = ring.stats().storageAllocations(); bytesBefore = ring.stats().bytesUploaded();
                    }
                    long start = System.nanoTime();
                    int buffer;
                    if (useRing) buffer = ring.upload(data, dirty, 16, 0, false);
                    else {
                        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, baseline);
                        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, (long) vertex * 16,
                                data.duplicate().position(vertex * 16).limit((vertex + 1) * 16));
                        buffer = baseline;
                    }
                    GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, buffer);
                    GL20.glVertexAttribPointer(location, 4, GL11.GL_FLOAT, false, 16, 0L);
                    GL11.glDrawArrays(GL11.GL_POINTS, 0, count);
                    if (useRing) ring.markSubmitted();
                    long elapsed = System.nanoTime() - start;
                    if (frame >= warmup) samples[frame - warmup] = elapsed;
                    if (queued) GL11.glFlush(); else GL11.glFinish();
                }
                GL11.glFinish(); GlFixture.noError("sparse VBO A/B benchmark"); Arrays.sort(samples);
                return Map.of("strategy", useRing ? ring.strategy() : "baseline-subdata", "frames", frames, "warmup", warmup,
                        "cpuP50Us", percentile(samples, .5), "cpuP95Us", percentile(samples, .95), "cpuP99Us", percentile(samples, .99),
                        "storageAllocations", useRing ? ring.stats().storageAllocations() - allocationsBefore : 0,
                        "bytesUploaded", useRing ? ring.stats().bytesUploaded() - bytesBefore : (long) frames * 16,
                        "busyOrphans", useRing ? ring.stats().busyOrphans() : 0, "explicitWaitIncludedInCpuTiming", false);
            } finally { GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0); GL15.glDeleteBuffers(baseline); MemoryUtil.memFree(data); }
        }
    }

    private static Map<String, Object> measure(boolean useRing, boolean queued, int bytes, int warmup, int frames) {
        TraceDevice trace = new TraceDevice();
        try (var fixture = new GlFixture(); var ring = new TextureUploadRing(new GpuUploadRing(3, trace))) {
            int program = GlslProgram.link("""
                    #version 150
                    uniform samplerBuffer Palette;
                    uniform int Texels;
                    out vec4 Result;
                    void main() {
                        int i = (gl_VertexID * 97) % Texels;
                        Result = texelFetch(Palette, i) + texelFetch(Palette, (i + 17) % Texels);
                        gl_Position = vec4(0.0);
                    }
                    """, null, Map.of(), new String[]{"Result"});
            int baseline = GL15.glGenBuffers(), output = GL15.glGenBuffers(), texture = GL11.glGenTextures();
            ByteBuffer data = MemoryUtil.memAlloc(bytes).order(ByteOrder.nativeOrder());
            try {
                for (int i = 0; i < bytes / 4; i++) data.putFloat(i * 4, i % 32 * .125f);
                GL15.glBindBuffer(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, output);
                GL15.glBufferData(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, (long) VERTICES * 16, GL15.GL_STREAM_READ);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, output);
                GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, texture);
                GL20.glUseProgram(program);
                GlFixture.integer(program, "Palette", 0); GlFixture.integer(program, "Texels", bytes / 16);
                GL11.glEnable(GL30.GL_RASTERIZER_DISCARD);
                var floats = data.asFloatBuffer();
                long[] samples = new long[frames];
                long allocationsBefore = 0, orphanBefore = 0;
                for (int frame = 0; frame < warmup + frames; frame++) {
                    data.putFloat(0, frame);
                    if (frame == warmup) {
                        GL11.glFinish();
                        allocationsBefore = ring.stats().storageAllocations(); orphanBefore = ring.stats().busyOrphans();
                        trace.reset();
                    }
                    long start = System.nanoTime();
                    int buffer;
                    if (useRing) {
                        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, ring.upload(floats));
                        buffer = ring.bufferId();
                    }
                    else {
                        int previous = GL11.glGetInteger(GL31.GL_COPY_WRITE_BUFFER);
                        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, baseline);
                        GL15.glBufferData(GL31.GL_COPY_WRITE_BUFFER, data, GL15.GL_STREAM_DRAW);
                        GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, previous);
                        buffer = baseline;
                    }
                    if (!useRing) GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, buffer);
                    GL30.glBeginTransformFeedback(GL11.GL_POINTS);
                    GL11.glDrawArrays(GL11.GL_POINTS, 0, VERTICES);
                    GL30.glEndTransformFeedback();
                    if (useRing) ring.markSubmitted();
                    long elapsed = System.nanoTime() - start;
                    if (frame >= warmup) samples[frame - warmup] = elapsed;
                    if (queued) GL11.glFlush(); else GL11.glFinish();
                }
                GL11.glFinish(); GlFixture.noError("upload A/B benchmark");
                Arrays.sort(samples);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("strategy", useRing ? ring.strategy() : "baseline-orphan");
                result.put("frames", frames); result.put("warmup", warmup);
                result.put("cpuP50Us", percentile(samples, .50));
                result.put("cpuP95Us", percentile(samples, .95));
                result.put("cpuP99Us", percentile(samples, .99));
                result.put("storageAllocations", useRing ? ring.stats().storageAllocations() - allocationsBefore : frames);
                result.put("busyOrphans", useRing ? ring.stats().busyOrphans() - orphanBefore : 0);
                result.put("explicitWaitIncludedInCpuTiming", false);
                if (useRing) result.put("ringOperationAverageUs", Map.of("fence",trace.fences / (frames * 1000.0),
                        "poll",trace.polls / (frames * 1000.0), "write", trace.writes / (frames * 1000.0)));
                return result;
            } finally {
                GL11.glDisable(GL30.GL_RASTERIZER_DISCARD);
                GL30.glBindBufferBase(GL30.GL_TRANSFORM_FEEDBACK_BUFFER, 0, 0);
                GL15.glBindBuffer(GL31.GL_COPY_WRITE_BUFFER, 0); GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, 0);
                GL20.glUseProgram(0); GL15.glDeleteBuffers(baseline); GL15.glDeleteBuffers(output);
                GL11.glDeleteTextures(texture); GL20.glDeleteProgram(program); MemoryUtil.memFree(data);
            }
        }
    }

    private static final class TraceDevice implements GpuUploadRing.Device {
        final GpuUploadRing.Device delegate = TextureUploadRing.defaultDevice();
        @Override public int createBuffer() { return delegate.createBuffer(); }
        @Override public void deleteBuffer(int id) { delegate.deleteBuffer(id); }
        @Override public boolean orphanEveryUpload() { return delegate.orphanEveryUpload(); }
        @Override public String strategy() { return delegate.strategy(); }
        @Override public void allocate(int buffer, int size) { delegate.allocate(buffer, size); }
        @Override public void deleteFence(long fence) { delegate.deleteFence(fence); }
        @Override public void uploadOrphaned(int buffer, ByteBuffer data) {
            long start = System.nanoTime();
            try { delegate.uploadOrphaned(buffer, data); } finally { writes += System.nanoTime() - start; }
        }
        long fences, polls, writes;
        void reset() { fences = polls = writes = 0; }
        @Override public long fence() {
            long start = System.nanoTime();
            try { return delegate.fence(); } finally { fences += System.nanoTime() - start; }
        }
        @Override public boolean ready(long fence) {
            long start = System.nanoTime();
            try { return delegate.ready(fence); } finally { polls += System.nanoTime() - start; }
        }
        @Override public void write(int buffer, ByteBuffer snapshot, BitSet elements, int stride, int gap) {
            long start = System.nanoTime();
            try { delegate.write(buffer, snapshot, elements, stride, gap); } finally { writes += System.nanoTime() - start; }
        }
    }

    private static double percentile(long[] values, double fraction) {
        return values[Math.min(values.length - 1, (int) Math.ceil(values.length * fraction) - 1)] / 1000.0;
    }
}
