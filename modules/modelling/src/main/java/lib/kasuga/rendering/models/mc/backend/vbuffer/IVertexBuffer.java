package lib.kasuga.rendering.models.mc.backend.vbuffer;

import com.mojang.blaze3d.vertex.VertexBuffer;
import lib.kasuga.mixins.client.AccessorVertexBuffer;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import lib.kasuga.rendering.models.uml.framework.buffer.RenderBuffer;
import net.minecraft.client.renderer.ShaderInstance;

/** Minecraft/OpenGL handles layered over the host-neutral upload/draw contract. */
public interface IVertexBuffer extends RenderBuffer<FlatModelData, ShaderInstance> {
    VertexBuffer getVertexBuffer();

    default int getBufferId() {
        return ((AccessorVertexBuffer) getVertexBuffer()).getVertexBufferId();
    }
}
