package lib.kasuga.mixins.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ByteBufferBuilder.class)
public interface AccessorByteBufferBuilder {

    @Accessor("pointer")
    long kasuga$getPointer();

    @Accessor("capacity")
    int kasuga$getCapacity();

    @Accessor("writeOffset")
    void kasuga$setWriteOffset(int writeOffset);
}
