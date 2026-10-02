package lib.kasuga.rendering.output.mc;
/** Native pool close retires borrowed packs only after their asynchronous job returns them. */
public interface CameraBufferPool {
    void kasuga$own();
    void kasuga$close();
}
