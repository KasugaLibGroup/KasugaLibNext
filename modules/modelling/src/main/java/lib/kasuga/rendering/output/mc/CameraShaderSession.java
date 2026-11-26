package lib.kasuga.rendering.output.mc;

import java.io.IOException;

/** The optional shader pipeline owned by an independent world camera. */
public interface CameraShaderSession extends AutoCloseable {
    Scope enter();

    void finish();

    @Override
    void close() throws IOException;

    interface Scope extends AutoCloseable {
        void beginFrame();

        @Override
        void close();
    }
}
