package lib.kasuga.core.resource.source;

import java.io.IOException;

public interface ResourceSource extends AutoCloseable {
    ResourceSourceDescriptor descriptor();
    ResourceSnapshot capture() throws IOException;
    @Override void close() throws IOException;
}
