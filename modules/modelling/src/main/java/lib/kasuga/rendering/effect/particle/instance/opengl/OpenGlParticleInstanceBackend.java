package lib.kasuga.rendering.effect.particle.instance.opengl;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.effect.WorldRenderPipelineContext;
import lib.kasuga.rendering.effect.particle.ParticleInstanceBuffer;
import lib.kasuga.rendering.effect.particle.instance.ParticleInstanceMesh;
import lib.kasuga.rendering.effect.particle.instance.ParticleInstanceRenderBackend;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL31;
import org.lwjgl.opengl.GL33;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OpenGL implementation of the backend-neutral particle instancing contract.
 *
 * <p>All VAO/VBO and attribute-location knowledge is isolated in this adapter. The shader must
 * declare {@code Position}, {@code InstanceModel0..3}, {@code InstanceColor}, and
 * {@code CameraOffset} using the standard packed instance layout.</p>
 */
public final class OpenGlParticleInstanceBackend implements ParticleInstanceRenderBackend {
    private static final String[] MODEL_ATTRIBUTES = {
            "InstanceModel0", "InstanceModel1", "InstanceModel2", "InstanceModel3"
    };

    private final RenderShaderHandle shaderHandle;
    private final AtomicBoolean closed = new AtomicBoolean();
    private int vertexArray;
    private int meshBuffer;
    private int instanceBuffer;
    private int instanceCapacityBytes;
    private ParticleInstanceMesh uploadedMesh;
    private ShaderInstance configuredShader;

    public OpenGlParticleInstanceBackend(RenderShaderHandle shaderHandle) {
        this.shaderHandle = Objects.requireNonNull(shaderHandle, "shaderHandle");
    }

    @Override
    public void draw(
            ParticleInstanceMesh mesh,
            ParticleInstanceBuffer instances,
            WorldRenderPipelineContext context
    ) {
        RenderSystem.assertOnRenderThread();
        if (closed.get() || instances.isEmpty()) return;
        ShaderInstance shader = shaderHandle.get();
        if (shader == null) return;

        context.bufferSource().endBatch();
        BufferUploader.reset();
        int previousVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        RenderType renderType = context.pipeline().renderType();
        boolean renderStateActive = false;
        try {
            ensureResources(mesh);
            if (configuredShader != shader) configureAttributes(shader);
            uploadInstances(instances);
            renderType.setupRenderState();
            renderStateActive = true;
            VertexFormat.Mode mode = mode(mesh.topology());
            shader.setDefaultUniforms(
                    mode,
                    context.modelViewMatrix(),
                    context.projectionMatrix(),
                    context.minecraft().getWindow()
            );
            Vec3 camera = context.camera().getPosition();
            shader.safeGetUniform("CameraOffset").set(
                    (float) camera.x, (float) camera.y, (float) camera.z
            );
            shader.apply();
            BufferUploader.reset();
            GL30.glBindVertexArray(vertexArray);
            GL31.glDrawArraysInstanced(
                    primitive(mesh.topology()), 0, mesh.vertexCount(), instances.size()
            );
        } finally {
            shader.clear();
            if (renderStateActive) renderType.clearRenderState();
            BufferUploader.reset();
            GL30.glBindVertexArray(previousVertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        }
    }

    private void ensureResources(ParticleInstanceMesh mesh) {
        if (vertexArray == 0) {
            vertexArray = GL30.glGenVertexArrays();
            meshBuffer = GL15.glGenBuffers();
            instanceBuffer = GL15.glGenBuffers();
        }
        if (uploadedMesh == mesh) return;
        FloatBuffer positions = MemoryUtil.memAllocFloat(mesh.positionComponentCount());
        try {
            mesh.writePositions(positions);
            positions.flip();
            GL30.glBindVertexArray(vertexArray);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, meshBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, positions, GL15.GL_STATIC_DRAW);
        } finally {
            MemoryUtil.memFree(positions);
        }
        uploadedMesh = mesh;
        configuredShader = null;
    }

    private void configureAttributes(ShaderInstance shader) {
        GL30.glBindVertexArray(vertexArray);

        int position = requireAttribute(shader, "Position");
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, meshBuffer);
        GL20.glEnableVertexAttribArray(position);
        GL20.glVertexAttribPointer(position, 3, GL11.GL_FLOAT, false, 3 * Float.BYTES, 0L);
        GL33.glVertexAttribDivisor(position, 0);

        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        for (int column = 0; column < MODEL_ATTRIBUTES.length; column++) {
            int location = requireAttribute(shader, MODEL_ATTRIBUTES[column]);
            GL20.glEnableVertexAttribArray(location);
            GL20.glVertexAttribPointer(
                    location,
                    4,
                    GL11.GL_FLOAT,
                    false,
                    ParticleInstanceBuffer.STRIDE_BYTES,
                    ParticleInstanceBuffer.MATRIX_OFFSET_BYTES + (long) column * 4 * Float.BYTES
            );
            GL33.glVertexAttribDivisor(location, 1);
        }
        int color = requireAttribute(shader, "InstanceColor");
        GL20.glEnableVertexAttribArray(color);
        GL20.glVertexAttribPointer(
                color,
                4,
                GL11.GL_FLOAT,
                false,
                ParticleInstanceBuffer.STRIDE_BYTES,
                ParticleInstanceBuffer.COLOR_OFFSET_BYTES
        );
        GL33.glVertexAttribDivisor(color, 1);
        configuredShader = shader;
    }

    private void uploadInstances(ParticleInstanceBuffer instances) {
        int requiredBytes = Math.multiplyExact(instances.size(), ParticleInstanceBuffer.STRIDE_BYTES);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, instanceBuffer);
        if (requiredBytes > instanceCapacityBytes) {
            instanceCapacityBytes = nextPowerOfTwo(requiredBytes);
            GL15.glBufferData(
                    GL15.GL_ARRAY_BUFFER, (long) instanceCapacityBytes, GL15.GL_STREAM_DRAW
            );
        }
        GL15.glBufferSubData(GL15.GL_ARRAY_BUFFER, 0, instances.uploadBytes());
    }

    private static int requireAttribute(ShaderInstance shader, String name) {
        int location = GL20.glGetAttribLocation(shader.getId(), name);
        if (location < 0) {
            throw new IllegalStateException(
                    "Instanced particle shader is missing active attribute '" + name + "'"
            );
        }
        return location;
    }

    private static int nextPowerOfTwo(int value) {
        int highest = Integer.highestOneBit(value);
        if (highest == value) return value;
        if (highest > (1 << 30)) return value;
        return highest << 1;
    }

    private static int primitive(ParticleInstanceMesh.Topology topology) {
        return switch (topology) {
            case LINES -> GL11.GL_LINES;
            case TRIANGLES -> GL11.GL_TRIANGLES;
            case TRIANGLE_STRIP -> GL11.GL_TRIANGLE_STRIP;
        };
    }

    private static VertexFormat.Mode mode(ParticleInstanceMesh.Topology topology) {
        return switch (topology) {
            case LINES -> VertexFormat.Mode.LINES;
            case TRIANGLES -> VertexFormat.Mode.TRIANGLES;
            case TRIANGLE_STRIP -> VertexFormat.Mode.TRIANGLE_STRIP;
        };
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        int oldVertexArray = vertexArray;
        int oldMeshBuffer = meshBuffer;
        int oldInstanceBuffer = instanceBuffer;
        vertexArray = meshBuffer = instanceBuffer = 0;
        uploadedMesh = null;
        configuredShader = null;
        instanceCapacityBytes = 0;
        if (oldVertexArray == 0 && oldMeshBuffer == 0 && oldInstanceBuffer == 0) return;
        Runnable release = () -> {
            if (oldVertexArray != 0) GL30.glDeleteVertexArrays(oldVertexArray);
            if (oldMeshBuffer != 0) GL15.glDeleteBuffers(oldMeshBuffer);
            if (oldInstanceBuffer != 0) GL15.glDeleteBuffers(oldInstanceBuffer);
        };
        if (RenderSystem.isOnRenderThread()) release.run();
        else RenderSystem.recordRenderCall(release::run);
    }
}
