package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import lib.kasuga.mixins.modelling.*;
import net.minecraft.client.renderer.*;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class CameraNativeResources {
    static void close(LevelRenderer renderer, RenderBuffers buffers) {
        if (renderer != null) {
            var sky = (WorldViewSkyBuffersAccessor) renderer;
            for (var buffer : new com.mojang.blaze3d.vertex.VertexBuffer[]{sky.kasuga$stars(), sky.kasuga$sky(), sky.kasuga$dark(), sky.kasuga$clouds()})
                if (buffer != null) buffer.close();
        }
        Set<ByteBufferBuilder> nativeBuffers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (var type : RenderType.chunkBufferLayers()) nativeBuffers.add(buffers.fixedBufferPack().buffer(type));
        collect(buffers.bufferSource(), nativeBuffers);
        collect(buffers.crumblingBufferSource(), nativeBuffers);
        collect(((WorldViewOutlineBuffersAccessor) buffers.outlineBufferSource()).kasuga$outline(), nativeBuffers);
        nativeBuffers.remove(null); nativeBuffers.forEach(ByteBufferBuilder::close);
        ((CameraBufferPool) buffers.sectionBufferPool()).kasuga$close();
    }
    private static void collect(MultiBufferSource.BufferSource source, Set<ByteBufferBuilder> nativeBuffers) {
        if (net.neoforged.fml.ModList.get().isLoaded("iris")) CameraIrisNativeBuffers.close(source);
        var access = (WorldViewNativeBuffersAccessor) source;
        nativeBuffers.add(access.kasuga$shared());
        if (access.kasuga$fixed() != null) nativeBuffers.addAll(access.kasuga$fixed().values());
    }
}
