package lib.kasuga.core.rendering;

import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.jetbrains.annotations.Nullable;


@FunctionalInterface
public interface BufferBuilderSupplier {

    @Nullable
    BufferBuilder init(ByteBufferBuilder buffer, VertexFormat.Mode mode, VertexFormat format);
}
