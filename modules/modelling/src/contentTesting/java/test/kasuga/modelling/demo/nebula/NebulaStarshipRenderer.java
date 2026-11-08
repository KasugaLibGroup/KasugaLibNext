package test.kasuga.modelling.demo.nebula;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import lib.kasuga.rendering.models.uml.typo.wavefont_obj.ObjTextureData;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.vbo.VBOBuffer;
import lib.kasuga.rendering.models.uml.backend.gpu.texture.GpuTexture2D;
import lib.kasuga.rendering.models.uml.backend.gpu.vao.VertexArrayObject;
import org.joml.Matrix4f;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;


/** Render-thread-owned rasterizer for the standalone OBJ used by Nebula. */
final class NebulaStarshipRenderer {
    private static final String OBJ = "assets/kasuga_demo/models/constitution_starship.obj";
    private static final String MTL = "assets/kasuga_demo/models/constitution_starship.mtl";
    private static final String VERTEX = "assets/kasuga_demo/shaders/core/nebula_starship.vert";
    private static final String FRAGMENT = "assets/kasuga_demo/shaders/core/nebula_starship.frag";
    private static final int FLOATS_PER_VERTEX = 16;
    private final Matrix4f viewProjection = new Matrix4f();
    private final Matrix4f view = new Matrix4f();
    private GpuTexture2D albedoMap, normalMap, metallicRoughnessMap, emissiveMap;
    private int program;
    private VertexArrayObject vao;
    private VBOBuffer vbo;
    private int vertexCount, triangleCount;
    private boolean deleteVerified = true;
    private float textureStrength = 1;

    void textureStrength(float value) { textureStrength = value; }
    private float minX, minY, minZ, maxX, maxY, maxZ;

    int triangleCount() { return triangleCount; }
    boolean allocated() {
        return albedoMap != null || normalMap != null || metallicRoughnessMap != null
                || emissiveMap != null || program != 0 || vao != null || vbo != null;
    }
    boolean lastDeleteVerified() { return deleteVerified; }
    boolean pbrSlotsReady() {
        return albedoMap != null && albedoMap.isOpen() && normalMap != null && normalMap.isOpen()
                && metallicRoughnessMap != null && metallicRoughnessMap.isOpen()
                && emissiveMap != null && emissiveMap.isOpen();
    }

    void ensureReady() {
        RenderSystem.assertOnRenderThread();
        if (program != 0 && vao != null && vbo != null
                && albedoMap != null && normalMap != null && metallicRoughnessMap != null && emissiveMap != null
                && !vbo.isClosed() && !vao.isClosed()
                && albedoMap.isOpen() && normalMap.isOpen() && metallicRoughnessMap.isOpen() && emissiveMap.isOpen()) return;
        release();
        MeshData mesh = parseMesh(readResource(OBJ), readResource(MTL));
        triangleCount = mesh.triangleCount;
        vertexCount = mesh.vertexCount;
        minX = mesh.minX; minY = mesh.minY; minZ = mesh.minZ;
        maxX = mesh.maxX; maxY = mesh.maxY; maxZ = mesh.maxZ;
        program = GlslProgram.link(readResource(VERTEX), readResource(FRAGMENT));
        var material = mesh.materials().get("hull_mid");
        if (material == null) throw new IllegalStateException("Starship hull material is missing");
        albedoMap = texture(modelTexture(material.getMap_Kd()), true);
        normalMap = texture(modelTexture(material.getMap_Bump()), false);
        metallicRoughnessMap = texture(modelTexture(material.getMap_Pr()), false);
        emissiveMap = texture(modelTexture(material.getMap_Ke()), false);
        ByteBuffer vertexBytes = BufferUtils.createByteBuffer(mesh.vertices.length * Float.BYTES);
        vertexBytes.asFloatBuffer().put(mesh.vertices);
        vbo = new VBOBuffer(vertexBytes, GL15.GL_STATIC_DRAW);
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
    }

    void render(NebulaFlight.Pose pose, NebulaFlight.WarpState warp, Matrix4f projection,
                Matrix4f inverseView, RenderTarget target, boolean visible) {
        RenderSystem.assertOnRenderThread();
        ensureReady();
        if (!new Matrix4f(inverseView).invert(view).isFinite()) {
            throw new IllegalStateException("Starship view matrix is not finite");
        }
        viewProjection.set(projection).mul(view);

        int previousProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int previousArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int previousActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        int[] previousTextures = new int[4];
        for (int unit = 0; unit < previousTextures.length; unit++) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
            previousTextures[unit] = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        }
        int[] viewport = new int[4];
        GL11.glGetIntegerv(GL11.GL_VIEWPORT, viewport);
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        boolean depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        int depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        float[] clear = new float[4];
        GL11.glGetFloatv(GL11.GL_COLOR_CLEAR_VALUE, clear);
        try {
            target.bindWrite(true);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDisable(GL11.GL_BLEND);
            GL11.glDisable(GL11.GL_CULL_FACE);
            GL11.glEnable(GL11.GL_DEPTH_TEST);
            GL11.glDepthFunc(GL11.GL_LEQUAL);
            GL11.glDepthMask(true);
            GL11.glClearColor(0, 0, 0, 0);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);
            if (!visible) return;

            GlslProgram.use(program);
            GlslProgram.bindTexture2D(program, "AlbedoMap", albedoMap.id(), 0);
            GlslProgram.uniform(program, "HullTextureStrength", textureStrength);
            GlslProgram.bindTexture2D(program, "NormalMap", normalMap.id(), 1);
            GlslProgram.bindTexture2D(program, "MetallicRoughnessMap", metallicRoughnessMap.id(), 2);
            GlslProgram.bindTexture2D(program, "EmissiveMap", emissiveMap.id(), 3);
            GlslProgram.uniform(program, "ViewProjection", viewProjection);
            GlslProgram.uniform(program, "CraftPosition", pose.position());
            GlslProgram.uniform(program, "CraftRight", pose.right());
            GlslProgram.uniform(program, "CraftUp", pose.up());
            GlslProgram.uniform(program, "CraftForward", pose.forward());
            GlslProgram.uniform(program, "ShipScale", NebulaFlight.SHIP_SCALE);
            GlslProgram.uniform(program, "WarpStretch", Math.max(warp.stretch(), 1f));
            GlslProgram.uniform(program, "WarpCharge", warp.charge());
            GlslProgram.uniform(program, "WarpActive", warp.warpActive());
            vao.bind(0);
            vao.drawArrays(GL11.GL_TRIANGLES, 0, vertexCount);
            vao.unbind();
        } finally {
            if (vao != null && !vao.isClosed()) vao.unbind();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArray);
            GlslProgram.use(previousProgram);
            for (int unit = 0; unit < previousTextures.length; unit++) {
                GL13.glActiveTexture(GL13.GL_TEXTURE0 + unit);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, previousTextures[unit]);
            }
            GL13.glActiveTexture(previousActive);
            GL11.glViewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            if (depth) GL11.glEnable(GL11.GL_DEPTH_TEST); else GL11.glDisable(GL11.GL_DEPTH_TEST);
            if (cull) GL11.glEnable(GL11.GL_CULL_FACE); else GL11.glDisable(GL11.GL_CULL_FACE);
            if (blend) GL11.glEnable(GL11.GL_BLEND); else GL11.glDisable(GL11.GL_BLEND);
            if (scissor) GL11.glEnable(GL11.GL_SCISSOR_TEST); else GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glDepthMask(depthMask);
            GL11.glDepthFunc(depthFunc);
            GL11.glClearColor(clear[0], clear[1], clear[2], clear[3]);
        }
    }

    void release() {
        RenderSystem.assertOnRenderThread();
        deleteVerified = true;
        for (GpuTexture2D texture : new GpuTexture2D[]{albedoMap, normalMap, metallicRoughnessMap, emissiveMap}) {
            if (texture != null) {
                int retired = texture.id();
                texture.close();
                deleteVerified &= !GL11.glIsTexture(retired);
            }
        }
        albedoMap = normalMap = metallicRoughnessMap = emissiveMap = null;
        if (vbo != null) {
            int retired = vbo.getId();
            try { vbo.close(); } catch (Exception failure) { deleteVerified = false; }
            deleteVerified &= !GL15.glIsBuffer(retired);
            vbo = null;
        }
        if (vao != null) {
            int retired = vao.getVaoId();
            try { vao.close(); } catch (Exception failure) { deleteVerified = false; }
            deleteVerified &= !org.lwjgl.opengl.GL30.glIsVertexArray(retired);
            vao = null;
        }
        if (program != 0) {
            GlslProgram.delete(program);
            deleteVerified &= !GL20.glIsProgram(program);
            program = 0;
        }
        vertexCount = triangleCount = 0;
    }

    private static String modelTexture(String relative) {
        if (relative == null) throw new IllegalStateException("Starship PBR texture slot is missing");
        return OBJ.substring(0, OBJ.lastIndexOf('/') + 1) + relative;
    }

    private static InputStream openResource(String path) throws IOException {
        var parts = path.split("/", 3);
        var id = ResourceLocation.fromNamespaceAndPath(parts[1], parts[2]);
        return Minecraft.getInstance().getResourceManager().getResourceOrThrow(id).open();
    }

    private static GpuTexture2D texture(String resource, boolean srgb) {
        try (InputStream stream = openResource(resource)) {
            if (stream == null) throw new IllegalStateException("Missing PBR texture: " + resource);
            return GpuTexture2D.png(stream, srgb);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode PBR texture: " + resource, failure);
        }
    }

    private static String readResource(String path) {
        try (InputStream stream = openResource(path)) {
            if (stream == null) throw new IllegalStateException("Missing Nebula starship resource: " + path);
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read Nebula starship resource: " + path, failure);
        }
    }

    static MeshData parseMesh(String objSource, String mtlSource) {
        var mesh = lib.kasuga.rendering.models.mc.typo.KsgObjLoader.loadFlatMesh(
                objSource, mtlSource, NebulaStarshipRenderer::kind
        );
        if (mesh.triangleCount() < 1000) {
            throw new IllegalStateException("Starship OBJ is unexpectedly sparse");
        }
        return new MeshData(mesh.vertices(), mesh.triangleCount(), mesh.vertexCount(),
                mesh.minX(), mesh.minY(), mesh.minZ(), mesh.maxX(), mesh.maxY(), mesh.maxZ(), mesh.materials());
    }

    private static float kind(String name) {
        if (name.startsWith("hull_")) return 0;
        if (name.startsWith("trim_")) return 1;
        if (name.equals("coil_blue")) return 2;
        if (name.equals("collector_red")) return 3;
        if (name.startsWith("window_")) return 4;
        if (name.startsWith("hangar_")) return 5;
        if (name.startsWith("deflector_")) return 6;
        if (name.equals("impulse_red")) return 7;
        return 0;
    }

    record MeshData(float[] vertices, int triangleCount, int vertexCount,
                    float minX, float minY, float minZ, float maxX, float maxY, float maxZ,
                    Map<String, ObjTextureData> materials) {}

}
