package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import lib.kasuga.rendering.effect.shader.ShaderParameterBlock;
import net.neoforged.neoforge.client.GlStateBackup;

import java.util.Objects;
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
        try {
            if (clearOutput) output.clear(Minecraft.ON_OSX);
            output.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.depthMask(false);
            RenderSystem.disableCull();
            if (samplers != null) {
                for (Map.Entry<String, Object> sampler : samplers.entrySet()) {
                    shader.setSampler(sampler.getKey(), sampler.getValue());
                }
            }
            uniformSetup.accept(shader);
            if (parameters != null) parameters.apply(shader);
            shader.apply();

            BufferBuilder builder = RenderSystem.renderThreadTesselator().begin(
                    VertexFormat.Mode.QUADS, DefaultVertexFormat.BLIT_SCREEN
            );
            builder.addVertex(0.0f, 0.0f, 0.0f);
            builder.addVertex(1.0f, 0.0f, 0.0f);
            builder.addVertex(1.0f, 1.0f, 0.0f);
            builder.addVertex(0.0f, 1.0f, 0.0f);
            BufferUploader.draw(builder.buildOrThrow());
        } finally {
            shader.clear();
            RenderSystem.restoreGlState(backup);
        }
    }
}
