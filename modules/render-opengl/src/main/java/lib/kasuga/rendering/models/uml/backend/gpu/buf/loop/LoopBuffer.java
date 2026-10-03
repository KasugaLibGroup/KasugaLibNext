package lib.kasuga.rendering.models.uml.backend.gpu.buf.loop;

import lib.kasuga.rendering.models.uml.backend.gpu.buf.GpuBuffer;
import lombok.Getter;

import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.function.Consumer;
import java.util.function.Function;

public class LoopBuffer<T extends GpuBuffer<T>> extends GpuBuffer<T> {

    public static final String
            TYPE = "LoopBuffer",
            CLOSE_MSG = "Could not operation on closed LoopBuffer.";

    private boolean shouldChangeBuffer = false;


    private GpuBuffer<T>[] buffers;

    @Getter
    private GpuBuffer<T> current;

    @Getter
    private int currentIndex;

    private Queue<Consumer<T>> pendingUpdates;

    public LoopBuffer(long size, int usage, int bufferCount, Function<LoopBuffer<T>, T> bufferFactory) {
        super(size, usage, TYPE, new LoopBufferContext());
        buffers = new GpuBuffer[bufferCount];
        pendingUpdates = new LinkedList<>();
        for (int i = 0; i < bufferCount; i++) {
            buffers[i] = bufferFactory.apply(this);
        }
        currentIndex = 0;
        current = buffers[currentIndex];
    }

    public boolean hasPendingUpdates() {
        return !pendingUpdates.isEmpty();
    }

    @Override
    public void bind(int index) {
        checkOpen(CLOSE_MSG);
        context.enter((T) this);
        getNextBuffer().bind(index);
    }

    @Override
    public void unbind() {
        checkOpen(CLOSE_MSG);
        getNextBuffer().unbind();
        context.exit((T) this);
    }

    @Override
    public void updateAll(final ByteBuffer data) {
        checkOpen(CLOSE_MSG);
        updateExcludeCurrent(buf -> buf.updateAll(data));
    }

    @Override
    public void updateRange(final ByteBuffer data, long offset) {
        checkOpen(CLOSE_MSG);
        updateExcludeCurrent(buf -> buf.updateRange(data, offset));
    }

    @Override
    public void resize(long newSize, boolean copyDataToNewBuffer) {
        updateExcludeCurrent(buf -> buf.resize(newSize, copyDataToNewBuffer));
    }

    public boolean isBinding() {
        return current != null && !context.isEmpty();
    }

    protected void updateExcludeCurrent(Consumer<T> update) {
        if (isBinding()) {
            pendingUpdates.add(update);
            shouldChangeBuffer = true;
            for (GpuBuffer<T> buffer : buffers) {
                if (buffer == current) continue;
                update.accept((T) buffer);
            }
            return;
        }
        for (GpuBuffer<T> buffer : buffers) {
            update.accept((T) buffer);
        }
    }

    protected GpuBuffer getNextBuffer() {
        if (!shouldChangeBuffer || isBinding()) return current;
        while (hasPendingUpdates()) {
            pendingUpdates.poll().accept((T) current);
        }
        currentIndex = (currentIndex + 1) % buffers.length;
        current = buffers[currentIndex];
        shouldChangeBuffer = false;
        return current;
    }

    @Override
    public void close() throws Exception {
        if (isBinding()) unbind();
        for (GpuBuffer<T> buffer : buffers) {
            buffer.close();
        }
        super.close();
    }
}
