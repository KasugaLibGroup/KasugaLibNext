package lib.kasuga.rendering.models.mc.backend.transform;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import lib.kasuga.rendering.models.mc.backend.RenderState;
import lombok.Getter;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.lwjgl.opengl.*;

import java.io.Reader;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.LongSupplier;
import lib.kasuga.rendering.models.uml.backend.SkinningWork;
import lib.kasuga.rendering.models.uml.backend.gpu.GlslProgram;
import lib.kasuga.rendering.models.uml.backend.gpu.SkinningAttributes;
import lib.kasuga.rendering.models.uml.backend.gpu.GpuUploadRing;

@Getter
public class TransformFeedbackProgram implements AutoCloseable {

    public static final int STRIDE = 12;

    protected int programId = 0;
    protected boolean closed = false;
    protected final ResourceLocation programLocation;

    protected final Supplier<ByteBuffer> byteBufferSupplier;

    protected final Map<VertexFormatElement, Integer> bufOffsets;

    protected final int vertexSize;

    @Getter
    private int sourceBufferId = 0,
            sourceVaoId = 0,
            outputBufferId = 0;

    private final SkinningWork work = new SkinningWork();
    private final LongSupplier sourceVersion;
    private int outputVertices = -1;
    private final GpuUploadRing sources = new GpuUploadRing();

    public TransformFeedbackProgram(ResourceLocation programLocation, Supplier<ByteBuffer> bufSupplier,
                                    Map<VertexFormatElement, Integer> bufOffsets, int vertexSize) {
        this(programLocation, bufSupplier, bufOffsets, vertexSize, () -> 0L);
    }

    public TransformFeedbackProgram(ResourceLocation programLocation, Supplier<ByteBuffer> bufSupplier,
                                    Map<VertexFormatElement, Integer> bufOffsets, int vertexSize,
                                    LongSupplier sourceVersion) {
        this.sourceVersion = sourceVersion;
        this.programLocation = programLocation;
        this.programId = createProgram();
        this.byteBufferSupplier = bufSupplier;
        this.bufOffsets = bufOffsets;
        this.vertexSize = vertexSize;
    }

    public void bind(int textureUnit, int textureId) {
        if (programId == 0) {return;}
        GL20.glUseProgram(programId);
        int samplerLocation = GL20.glGetUniformLocation(programId, "ksg_BoneTransforms");
        if (samplerLocation >= 0) {
            GL20.glUniform1i(samplerLocation, textureUnit - GL13.GL_TEXTURE0);
        }
        if (textureId != 0) {
            RenderSystem.activeTexture(textureUnit);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
        }
    }

    public void unbind(int previousProgram) {
        GL20.glUseProgram(previousProgram);
    }

    public boolean isValid() {
        return !closed && programId != 0;
    }


    @Override
    public void close() throws Exception {
        if (closed) {return;}
        if (programId != 0) {
            GL20.glDeleteProgram(programId);
            programId = 0;
        }
        sources.close();
        sourceBufferId = 0;
        if (sourceVaoId != 0) {
            GL30.glDeleteVertexArrays(sourceVaoId);
            sourceVaoId = 0;
        }
        if (outputBufferId != 0) {
            GL15.glDeleteBuffers(outputBufferId);
            outputBufferId = 0;
        }
        work.invalidate();
        closed = true;
    }

    protected int createProgram() {
        String shaderSource = loadShaderSource();
        if (shaderSource.isBlank()) {
            throw new IllegalStateException("Failed to load Transform Feedback " +
                    "shader source from " + programLocation);
        }
        return GlslProgram.link(shaderSource, null, SkinningAttributes.LOCATIONS,
                new String[]{"tf_Position"});
    }

    protected String loadShaderSource() {
        Optional<Resource> resourceOpt = Minecraft.getInstance()
                .getResourceManager()
                .getResource(programLocation);
        if (resourceOpt.isEmpty()) return "";
        StringBuilder builder = new StringBuilder();
        char[] buffer = new char[1024];
        try (Reader reader = resourceOpt.get().openAsReader()) {
            int len;
            while ((len = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, len);
            }
            return builder.toString();
        } catch (Exception e) {
            return "";
        }
    }

    public boolean needsDispatch(long skeletonVersion, int vertices) {
        return work.needsDispatch(sourceVersion.getAsLong(), skeletonVersion, vertices);
    }

    public void dispatched(long skeletonVersion, int vertices) {
        work.dispatched(sourceVersion.getAsLong(), skeletonVersion, vertices);
    }

    public void markSourceSubmitted() { sources.markSubmitted(); }

    public void ensureSkinningObjects(int numVertices) {
        if (closed) throw new IllegalStateException("TransformFeedbackProgram is closed");
        if (numVertices < 0) throw new IllegalArgumentException("Negative vertex count");
        if (sourceVaoId == 0) {
            sourceVaoId = GL30.glGenVertexArrays();
        }
        if (outputBufferId == 0) {
            outputBufferId = GL15.glGenBuffers();
        }
        if (outputVertices != numVertices) {
            int previous = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            try {
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, outputBufferId);
                GL15.glBufferData(GL15.GL_ARRAY_BUFFER, (long) numVertices * STRIDE, GL15.GL_DYNAMIC_DRAW);
                outputVertices = numVertices;
            } finally {
                GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previous);
            }
        }
    }

    public void uploadSkinningSourceIfNeeded() {
        long version = sourceVersion.getAsLong();
        if (!work.needsSource(version)) {
            return;
        }
        int previousVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int previousArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        try {
            GL30.glBindVertexArray(sourceVaoId);
            ByteBuffer source = byteBufferSupplier.get().duplicate();
            source.clear();
            sourceBufferId = sources.upload(source);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, sourceBufferId);
            setupIrisGpuSkinningSourceAttributes();
        } finally {
            GL30.glBindVertexArray(previousVao);
            GlStateManager._glBindBuffer(GL15.GL_ARRAY_BUFFER, previousArrayBuffer);
        }
        work.sourceUploaded(version);
    }

    protected void setupIrisGpuSkinningSourceAttributes() {
        SkinningAttributes.configure(vertexSize, bufOffsets.get(VertexFormatElement.POSITION),
                bufOffsets.get(VertexFormatElement.NORMAL), bufOffsets.get(RenderState.TANGENT),
                bufOffsets.get(RenderState.BONE_BINDING_TYPE), bufOffsets.get(RenderState.BONE_INDICES),
                bufOffsets.get(RenderState.BONE_WEIGHTS), bufOffsets.get(RenderState.SDEF_R0),
                bufOffsets.get(RenderState.SDEF_R1), bufOffsets.get(RenderState.SDEF_C));
    }
}
