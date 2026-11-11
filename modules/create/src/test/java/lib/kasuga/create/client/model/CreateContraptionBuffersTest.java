package lib.kasuga.create.client.model;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.RenderType;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CreateContraptionBuffersTest {
    @Test void installedCreateExposesAResolvableNativeCacheWithTheSharedSignature() {
        assertEquals(MethodType.methodType(SuperByteBuffer.class, Contraption.class, VirtualRenderWorld.class, RenderType.class),
                CreateContraptionBuffers.signature());
    }
}
