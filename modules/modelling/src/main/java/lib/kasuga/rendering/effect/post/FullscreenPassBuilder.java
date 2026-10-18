package lib.kasuga.rendering.effect.post;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.ShaderInstance;
import lib.kasuga.rendering.effect.shader.ShaderParameterBlock;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/** Declarative one-shot fullscreen draw with feedback validation. */
public final class FullscreenPassBuilder {
    private static final Consumer<ShaderInstance> NO_UNIFORMS = ignored -> {};

    private final RenderTarget output;
    private final ShaderInstance shader;
    private final Map<String, Object> samplers = new LinkedHashMap<>();
    private Consumer<ShaderInstance> uniformSetup = NO_UNIFORMS;
    private ShaderParameterBlock parameters;
    private boolean samplesOutput;

    FullscreenPassBuilder(RenderTarget output, ShaderInstance shader) {
        this.output = Objects.requireNonNull(output, "output");
        this.shader = Objects.requireNonNull(shader, "shader");
    }

    public FullscreenPassBuilder colorSampler(String name, RenderTarget input) {
        Objects.requireNonNull(input, "input");
        samplesOutput |= input == output;
        samplers.put(Objects.requireNonNull(name, "name"), input.getColorTextureId());
        return this;
    }

    public FullscreenPassBuilder depthSampler(String name, RenderTarget input) {
        Objects.requireNonNull(input, "input");
        if (!input.useDepth) throw new IllegalArgumentException("Render target has no depth attachment");
        samplesOutput |= input == output;
        samplers.put(Objects.requireNonNull(name, "name"), input.getDepthTextureId());
        return this;
    }

    /** Adds an externally owned texture ID. The caller is responsible for feedback safety. */
    public FullscreenPassBuilder textureSampler(String name, int textureId) {
        samplers.put(Objects.requireNonNull(name, "name"), textureId);
        return this;
    }

    public FullscreenPassBuilder uniforms(Consumer<ShaderInstance> setup) {
        Objects.requireNonNull(setup, "setup");
        uniformSetup = uniformSetup == NO_UNIFORMS ? setup : uniformSetup.andThen(setup);
        return this;
    }

    /** Replaces the exposed-parameter values bound by this pass. */
    public FullscreenPassBuilder parameters(ShaderParameterBlock value) {
        parameters = Objects.requireNonNull(value, "value");
        return this;
    }

    public void draw(boolean clearOutput) {
        if (samplesOutput) {
            throw new IllegalStateException("A fullscreen pass cannot sample from its output RenderTarget");
        }
        FullscreenPassRenderer.draw(output, shader, clearOutput, samplers, uniformSetup, parameters);
    }
}
