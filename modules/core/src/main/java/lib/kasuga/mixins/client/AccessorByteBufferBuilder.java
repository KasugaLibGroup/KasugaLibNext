package lib.kasuga.mixins.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.lang.annotation.Target;

@Mixin(ByteBufferBuilder.class)
public interface AccessorByteBufferBuilder {

    @Accessor("pointer")
    long getPointer();

    @Accessor("capacity")
    int getCapacity();

    @Accessor("writeOffset")
    void setWriteOffset(int writeOffset);
}
