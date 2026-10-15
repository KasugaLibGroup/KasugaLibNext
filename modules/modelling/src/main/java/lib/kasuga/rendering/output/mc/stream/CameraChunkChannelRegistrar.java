package lib.kasuga.rendering.output.mc.stream;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
@Context public final class CameraChunkChannelRegistrar {
    @PostConstruct public void init() { CameraChunkChannel.REQUEST.getClass(); CameraChunkChannel.CHUNK.getClass(); CameraChunkChannel.ENTITY.getClass(); }
}
