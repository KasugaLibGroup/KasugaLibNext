package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;

/** Creates and compiles one shader instance during Minecraft's shader reload. */
@FunctionalInterface
public interface RenderShaderFactory {
    ShaderInstance create(ResourceProvider resources, ResourceLocation shaderId,
                          VertexFormat vertexFormat) throws IOException;
}
