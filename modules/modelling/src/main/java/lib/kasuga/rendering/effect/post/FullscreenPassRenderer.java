package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import lib.kasuga.rendering.effect.shader.ShaderParameterBlock;
import lib.kasuga.rendering.models.uml.backend.gpu.buf.tbo.TBOBuffer;
import net.neoforged.neoforge.client.GlStateBackup;

import java.util.Objects;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Draws the standard 0..1 fullscreen quad using a BLIT_SCREEN ShaderInstance. */
public final class FullscreenPassRenderer {
    private FullscreenPassRenderer() {}

    public static void draw(RenderTarget output, ShaderInstance shader,
                            boolean clearOutput, Consumer<ShaderInstance> configureShader) {
        draw(output, shader, clearOutput, null,
                Objects.requireNonNull(configureShader, "configureShader"), null);
    }

    static void draw(RenderTarget output, ShaderInstance shader, boolean clearOutput,
                     Map<String, Object> samplers, Consumer<ShaderInstance> uniformSetup,
                     ShaderParameterBlock parameters) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(output, "output");
        Objects.requireNonNull(shader, "shader");
        Objects.requireNonNull(uniformSetup, "uniformSetup");
        if (shader.getVertexFormat() != DefaultVertexFormat.BLIT_SCREEN) {
            throw new IllegalArgumentException("Fullscreen shaders must use DefaultVertexFormat.BLIT_SCREEN");
        }

        GlStateBackup backup = new GlStateBackup();
        RenderSystem.backupGlState(backup);
        List<TBOBuffer> boundBuffers = new ArrayList<>();
        try {
            if (clearOutput) output.clear(Minecraft.ON_OSX);
            output.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            if (samplers != null) {
                for (Map.Entry<String, Object> sampler : samplers.entrySet()) {
                    // ShaderInstance owns 2D samplers only. Buffer samplers are
                    // bound below, after apply() has selected the program.
                    if (!(sampler.getValue() instanceof TBOBuffer)) {
                        shader.setSampler(sampler.getKey(), sampler.getValue());
                    }
                }
            }
            uniformSetup.accept(shader);
            if (parameters != null) parameters.apply(shader);
            shader.apply();
            if (samplers != null) {
                for (Map.Entry<String, Object> sampler : samplers.entrySet()) {
                    if (sampler.getValue() instanceof TBOBuffer buffer) {
                        int location = Uniform.glGetUniformLocation(shader.getId(), sampler.getKey());
                        if (location < 0) continue;
                        buffer.bind(buffer.getTextureUnitId());
                        boundBuffers.add(buffer);
                        Uniform.uploadInteger(location, buffer.getTextureUnitId());
                    }
                }
            }

            BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN
            );
            builder.addVertex(0.0f, 0.0f, 0.0f);
            builder.addVertex(1.0f, 0.0f, 0.0f);
            builder.addVertex(1.0f, 1.0f, 0.0f);
            builder.addVertex(0.0f, 1.0f, 0.0f);
            BufferUploader.draw(builder.buildOrThrow());
        } finally {
            for (int index = boundBuffers.size() - 1; index >= 0; index--) {
                boundBuffers.get(index).unbind();
            }
            shader.clear();
            RenderSystem.restoreGlState(backup);
        }
    }
}
