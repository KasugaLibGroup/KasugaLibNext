package lib.kasuga.rendering.models.mc.backend.data_type;

import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;

/**
 * Dedicated type for the OIT resolve shader.
 *
 * <p>Iris keys its unknown-shader compatibility policy by the concrete shader
 * class. Keeping the resolve in its own class lets the Iris bridge permit this
 * shader without weakening the policy for every vanilla or modded shader.</p>
 */
public final class OitCompositeShaderInstance extends ShaderInstance {

    public OitCompositeShaderInstance(ResourceProvider provider, ResourceLocation location,
                                      VertexFormat format) throws IOException {
        super(provider, location, format);
    }

    /** Used by Iris versions that opt into per-shader compatibility hooks. */
    @SuppressWarnings("unused")
    public boolean iris$skipDraw() {
        return false;
    }
}
