package lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lombok.Getter;
import org.lwjgl.opengl.*;

import java.nio.ByteBuffer;
import java.util.function.Supplier;

public class TBOBuffer extends GpuBuffer<TBOBuffer> {

    public static final String
            TYPE = "TBO",
            CLOSE_MSG = "Cannot operate on a closed TBO Buffer.";

    @Getter
    private final int textureId, bufferId, textureUnitId;

    public TBOBuffer(Supplier<Integer> textureUnitSupplier,
                     long size, int usage) {
        super(size, usage, TYPE, new TBOContext());

        bufferId = GL15.glGenBuffers();
        textureId = GL11.glGenTextures();
        textureUnitId = textureUnitSupplier.get();

        context.enter(this);

        try {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, bufferId);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, size, usage);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, bufferId);
        } finally {
            context.exit(this);
        }
    }

    public TBOBuffer(Supplier<Integer> texUnitSupplier,
                     ByteBuffer byteData, int usage) {
        super(byteData.remaining(), usage, TYPE, new TBOContext());

        bufferId = GL15.glGenBuffers();
        textureId = GL11.glGenTextures();
        textureUnitId = texUnitSupplier.get();

        context.enter(this);
        try {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, bufferId);
            GL15.glBufferData(GL31.GL_TEXTURE_BUFFER, byteData, usage);
            GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
            GL31.glTexBuffer(GL31.GL_TEXTURE_BUFFER, GL30.GL_RGBA32F, bufferId);
        } finally {
            context.exit(this);
        }
    }

    @Override
    public void resize(long newSize, boolean copyDataToNewBuffer) {
        checkOpen(CLOSE_MSG);
        if (newSize == size) return;
        if (newSize <= 0) {
            throw new IllegalArgumentException("New size must be greater than zero.");
        }
        context.enter(this);
        try {
            resizeStorage(GL31.GL_TEXTURE_BUFFER, bufferId, newSize, copyDataToNewBuffer);
        } finally {
            context.exit(this);
        }
    }

    @Override
    public void bind(int index) {
        checkOpen(CLOSE_MSG);
        if (index < 0) return;
        context.enter(this);
        GL13.glActiveTexture(GL13.GL_TEXTURE0 + textureUnitId);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
    }

    public void unbind() {
        checkOpen(CLOSE_MSG);
        context.exit(this);
    }

    @Override
    public void updateAll(ByteBuffer data) {
        checkOpen(CLOSE_MSG);
        if (data.remaining() > size) {
            throw new IllegalArgumentException("Data size exceeds buffer capacity.");
        }
        context.enter(this);
        try {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, bufferId);
            GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, 0, data);
        } finally {
            context.exit(this);
        }
    }

    public void updateRange(ByteBuffer data, long offset) {
        checkOpen(CLOSE_MSG);
        if (offset + data.remaining() > size) {
            throw new IllegalArgumentException("Data range exceeds buffer capacity.");
        }
        context.enter(this);
        try {
            GL15.glBindBuffer(GL31.GL_TEXTURE_BUFFER, bufferId);
            GL15.glBufferSubData(GL31.GL_TEXTURE_BUFFER, offset, data);
        } finally {
            context.exit(this);
        }
    }

    @Override
    public void close() throws Exception {
        if (isClosed) return;
        if (!context.isEmpty()) context.exit(this);
        GL15.glDeleteBuffers(bufferId);
        GL11.glDeleteTextures(textureId);
        super.close();
    }
}
