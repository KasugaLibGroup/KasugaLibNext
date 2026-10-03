package lib.kasuga.rendering.models.uml.backend.gpu.buf.ssbo;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lombok.Getter;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class SSBOBuffer extends GpuBuffer<SSBOBuffer> {

    @Getter
    private final int id;

    public static final String
            TYPE = "SSBO",
            CLOSE_MSG = "Cannot operate on a closed SSBO Buffer.";

    public SSBOBuffer(long size, int usage) {
        super(size, usage, TYPE, new SSBOContext());
        id = GL15.glGenBuffers();

        bind(0);
        try {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, size, usage);
        } finally {
            unbind();
        }
    }

    public SSBOBuffer(ByteBuffer byteData, int usage) {
        super(byteData.remaining(), usage, TYPE, new SSBOContext());
        id = GL15.glGenBuffers();

        bind(0);
        try {
        GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, byteData, usage);
        } finally {
            unbind();
        }
    }

    @Override
    public void resize(long newSize, boolean copyDataToNewBuffer) {
        checkOpen(CLOSE_MSG);
        if (newSize == size) return;
        if (newSize <= 0) {
            throw new IllegalArgumentException("Buffer size must be greater than 0.");
        }
        if (copyDataToNewBuffer && size > 0) {
            ByteBuffer oldData = ByteBuffer.allocateDirect((int) Math.min(size, Integer.MAX_VALUE));
            bind(-1);
            try {
                GL15.glGetBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, oldData);
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, newSize, usage);
                oldData.flip();
                int copySize = (int) Math.min(size, newSize);
                GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, 0, oldData.limit(copySize));
                MemoryUtil.memFree(oldData);
            } finally {
                unbind();
            }
        } else {
            bind(-1);
            try {
                GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, newSize, usage);
            } finally {
                unbind();
            }
        }
        this.size = newSize;
    }

    @Override
    public void bind(int index) {
        checkOpen(CLOSE_MSG);
        context.enter(this);
        GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, id);
        if (index >= 0) {
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, index, id);
        }
    }

    @Override
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
        bind(-1);
        try {
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, data, usage);
        } finally {
            unbind();
        }
    }

    public void updateRange(ByteBuffer data, long offset) {
        checkOpen(CLOSE_MSG);
        if (offset + data.remaining() > size) {
            throw new IllegalArgumentException("Data range exceeds buffer capacity.");
        }
        bind(-1);
        try {
            GL15.glBufferSubData(GL43.GL_SHADER_STORAGE_BUFFER, offset, data);
        } finally {
            unbind();
        }
    }

    @Override
    public void close() throws Exception {
        if (isClosed) return;
        if (!context.isEmpty()) context.exit(this);
        GL15.glDeleteBuffers(id);
        super.close();
    }
}
