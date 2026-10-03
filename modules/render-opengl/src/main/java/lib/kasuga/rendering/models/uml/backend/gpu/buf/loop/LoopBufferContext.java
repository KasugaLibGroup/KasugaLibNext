package lib.kasuga.rendering.models.uml.backend.gpu.buf.loop;

import lib.kasuga.rendering.models.uml.backend.gpu.GpuContext;

public class LoopBufferContext<T extends LoopBuffer<T>> implements GpuContext<T> {

    private boolean empty = true;

    @Override
    public void enter(LoopBuffer buffer) {
        empty = false;
    }

    @Override
    public void exit(LoopBuffer buffer) {
        empty = true;
    }

    @Override
    public boolean isEmpty() {
        return empty;
    }
}
