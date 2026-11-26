package lib.kasuga.rendering.models.mc.backend.data_type;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;

import java.io.IOException;

/** Reloadable fullscreen resolve program shared by native and shader-pack rendering. */
public final class OitCompositeShaderInstance extends ShaderInstance {
    private final int accumulationSampler;
    private final int revealageSampler;

    public OitCompositeShaderInstance(ResourceProvider provider, ResourceLocation location,
                                      VertexFormat format) throws IOException {
        super(provider, location, format);
        accumulationSampler = GL20.glGetUniformLocation(getId(), "AccumulationSampler");
        revealageSampler = GL20.glGetUniformLocation(getId(), "RevealageSampler");
        if (accumulationSampler < 0 || revealageSampler < 0) {
            close();
            throw new IOException("OIT composite requires AccumulationSampler and RevealageSampler");
        }
    }

    /** The resolve owns its framebuffer and samples only Kasuga attachments. Binding the linked
     * program directly keeps shader-pack overrides confined to the two world geometry passes. */
    public void draw(int accumulationTexture, int revealageTexture) {
        int program = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int texture0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int texture1 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        try {
            GL20.glUseProgram(getId());
            GL20.glUniform1i(accumulationSampler, 0);
            GL20.glUniform1i(revealageSampler, 1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, revealageTexture);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, accumulationTexture);
            BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(VertexFormat.Mode.QUADS, getVertexFormat());
            builder.addVertex(0, 0, 0);
            builder.addVertex(1, 0, 0);
            builder.addVertex(1, 1, 0);
            builder.addVertex(0, 1, 0);
            BufferUploader.draw(builder.buildOrThrow());
        } finally {
            // Raw bindings leave Minecraft's and Iris' state caches untouched. Put the actual
            // GL state back before either renderer uses its cached bindings again.
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture0);
            GL13.glActiveTexture(GL13.GL_TEXTURE1);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture1);
            GL13.glActiveTexture(activeTexture);
            GL20.glUseProgram(program);
        }
    }
}
