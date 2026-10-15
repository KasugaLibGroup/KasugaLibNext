package lib.kasuga.rendering.models.uml.backend.gpu;

public interface GpuContext<T> {

    void enter(T object);

    void exit(T object);

    boolean isEmpty();
}
