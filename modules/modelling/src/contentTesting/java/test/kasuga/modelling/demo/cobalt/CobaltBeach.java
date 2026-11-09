package test.kasuga.modelling.demo.cobalt;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.effect.RegisterRenderPipelinesEvent;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.pipeline.RenderPhase;
import lib.kasuga.rendering.effect.pipeline.RenderPipelineDescriptor;
import lib.kasuga.rendering.models.mc.typo.KsgObjLoader;
import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.vbo.VBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.texture.GpuTexture2D;
import lib.kasuga.rendering.models.uml.backend.gpu.vao.VertexArrayObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Render-thread-owned custom OBJ coast for the Cobalt demo.
 *
 * <p>The general model atlas is intentionally bypassed here: a coast-sized single atlas is
 * rendered directly with a small GLSL material so the non-uniform waterline remains pixel-stable
 * without waiting for another resource-atlas generation.</p>
 */
@EventBusSubscriber(modid = "kasuga_lib", value = Dist.CLIENT)
final class CobaltBeach implements AutoCloseable {
    private static final org.slf4j.Logger LOGGER = LogUtils.getLogger();
    static final ResourceLocation MODEL = id("models/cobalt/beach.obj");
    private static final ResourceLocation OBJ = MODEL;
    private static final ResourceLocation MTL = id("models/cobalt/beach.mtl");
    private static final ResourceLocation ALBEDO = id("textures/cobalt/coast.png");
    private static final ResourceLocation VERTEX = id("shaders/core/cobalt_beach.vert");
    private static final ResourceLocation FRAGMENT = id("shaders/core/cobalt_beach.frag");
    private static final ResourceLocation PIPELINE = id("cobalt_beach");
    private static final AtomicInteger ACTIVE = new AtomicInteger();
    private static volatile GpuCoast active;

    private final GpuCoast coast;
    private boolean closed;

    private CobaltBeach(GpuCoast coast) {
        this.coast = coast;
        ACTIVE.incrementAndGet();
    }

    static boolean ready() {
        var resources = Minecraft.getInstance().getResourceManager();
        return resources.getResource(OBJ).isPresent()
                && resources.getResource(MTL).isPresent()
                && resources.getResource(ALBEDO).isPresent()
                && resources.getResource(VERTEX).isPresent()
                && resources.getResource(FRAGMENT).isPresent();
    }

    static CobaltBeach mount() {
        RenderSystem.assertOnRenderThread();
        if (!ready()) throw new IllegalStateException("Cobalt beach custom-model resources are not loaded");
        if (active != null) throw new IllegalStateException("Cobalt beach is already mounted");
        var level = Minecraft.getInstance().level;
        if (level == null) throw new IllegalStateException("Cobalt beach requires a client level");
        var mounted = new GpuCoast(level);
        active = mounted;
        LOGGER.info("COBALT_BEACH_MOUNT triangles={} vertices={} texture={}",
                mounted.triangleCount, mounted.vertexCount, ALBEDO);
        return new CobaltBeach(mounted);
    }

    static int activeInstances() {
        return ACTIVE.get();
    }

    @SubscribeEvent
    public static void registerPipelines(RegisterRenderPipelinesEvent event) {
        event.registrar(PIPELINE).world(RenderPipelineDescriptor.builder(id("beach_world"),
                        RenderPhase.AFTER_BLOCK_ENTITIES)
                .priority(90).build(), CobaltBeach::render);
    }

    private static void render(WorldRenderPipelineContext context) {
        var current = active;
        if (current != null && current.level == context.level()) current.render(context);
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (active == coast) active = null;
        try {
            coast.close();
        } finally {
            ACTIVE.updateAndGet(count -> Math.max(0, count - 1));
        }
    }

    private static final class GpuCoast implements AutoCloseable {
        private static final int FLOATS_PER_VERTEX = 16;

        private final ClientLevel level;
        private final int triangleCount;
        private final int vertexCount;
        private final Matrix4f viewProjection = new Matrix4f();
        private GpuTexture2D albedo;
        private int program;
        private VBOBuffer vbo;
        private VertexArrayObject vao;
        private boolean closed;
        private boolean projectionProbeLogged;

        private GpuCoast(ClientLevel level) {
            this.level = level;
            var mesh = KsgObjLoader.loadFlatMesh(read(OBJ), read(MTL), ignored -> 0f);
            triangleCount = mesh.triangleCount();
            vertexCount = mesh.vertexCount();
            try {
                albedo = texture(ALBEDO);
                program = GlslProgram.link(read(VERTEX), read(FRAGMENT));
                ByteBuffer bytes = BufferUtils.createByteBuffer(mesh.vertices().length * Float.BYTES);
                bytes.asFloatBuffer().put(mesh.vertices());
                vbo = new VBOBuffer(bytes, GL15.GL_STATIC_DRAW);
                vao = new VertexArrayObject(0, 0);
                vao.bind(0);
                vbo.bind(0);
                int stride = FLOATS_PER_VERTEX * Float.BYTES;
                vao.attribute(0, 3, stride, 0);
                vao.attribute(1, 3, stride, 3L * Float.BYTES);
                vao.attribute(2, 2, stride, 6L * Float.BYTES);
                vao.attribute(3, 4, stride, 8L * Float.BYTES);
                vao.attribute(4, 4, stride, 12L * Float.BYTES);
                vbo.unbind();
                vao.unbind();
            } catch (RuntimeException failure) {
                release();
                throw failure;
            }
        }

        private void render(WorldRenderPipelineContext context) {
            RenderSystem.assertOnRenderThread();
            // Raw draws must target the same depth attachment that WaterEffects captures later.
            context.mainRenderTarget().bindWrite(true);
            viewProjection.set(context.projectionMatrix()).mul(context.modelViewMatrix());
            if (!projectionProbeLogged) {
                projectionProbeLogged = true;
                var eye = context.camera().getPosition();
                var probe = new Vector4f((float) eye.x, (float) (eye.y - 1.5), (float) (eye.z + 2), 1);
                viewProjection.transform(probe);
                LOGGER.info("COBALT_BEACH_PROJECTION camera={} clip={} ndcZ={}",
                        eye, probe, probe.w == 0 ? Float.NaN : probe.z / probe.w);
            }
            int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            int previousTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            try {
                GL11.glDisable(GL11.GL_BLEND);
                GL11.glDisable(GL11.GL_CULL_FACE);
                GL11.glEnable(GL11.GL_DEPTH_TEST);
                GL11.glDepthFunc(GL11.GL_LEQUAL);
                GL11.glDepthMask(true);
                GlslProgram.use(program);
                GlslProgram.bindTexture2D(program, "CoastMap", albedo.id(), 0);
                GlslProgram.uniform(program, "ViewProjection", viewProjection);
                vao.bind(0);
                vao.drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
            } finally {
                if (vao != null && !vao.isClosed()) vao.unbind();
                GL30.glBindVertexArray(previousVao);
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
                GlslProgram.use(previousProgram);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTexture);
                GL13.glActiveTexture(previousActive);
                if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
                if (cull) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
                if (blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
                GL11.glDepthMask(depthMask);
                GL11.glDepthFunc(depthFunc);
            }
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            release();
        }

        private void release() {
            if (vbo != null) {
                try { vbo.close(); } catch (Exception ignored) {}
                vbo = null;
            }
            if (vao != null) {
                try { vao.close(); } catch (Exception ignored) {}
                vao = null;
            }
            if (albedo != null) {
                albedo.close();
                albedo = null;
            }
            if (program != 0) {
                GlslProgram.delete(program);
                program = 0;
            }
        }
    }

    private static GpuTexture2D texture(ResourceLocation resource) {
        try (InputStream stream = open(resource)) {
            return GpuTexture2D.png(stream, false);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode cobalt coast texture: " + resource, failure);
        }
    }

    private static String read(ResourceLocation resource) {
        try (InputStream stream = open(resource)) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read cobalt beach resource: " + resource, failure);
        }
    }

    private static InputStream open(ResourceLocation resource) throws IOException {
        return Minecraft.getInstance().getResourceManager().getResourceOrThrow(resource).open();
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_demo", path);
    }
}
