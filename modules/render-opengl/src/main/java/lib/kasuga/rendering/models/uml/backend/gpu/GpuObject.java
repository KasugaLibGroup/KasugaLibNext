package lib.kasuga.rendering.models.uml.backend.gpu;

public interface GpuObject<T extends GpuObject<T>> extends AutoCloseable {

    void bind(int index);

    void unbind();

    void close() throws Exception;

    GpuContext<T> getContext();
}
