package lib.kasuga.rendering.effect.shader;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import lib.kasuga.shader.ShaderParameterSchema;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Stable reference to a shader whose underlying GPU program is replaced on resource reload.
 * The ShaderInstance is owned and closed by Minecraft's GameRenderer.
 */
public final class RenderShaderHandle implements Supplier<ShaderInstance> {
    private final ResourceLocation id;
    private final RenderStateShard.ShaderStateShard shaderState;
    private final ShaderParameterBlock parameters;
    private volatile ShaderInstance shader;
    private volatile long generation;
    private volatile ShaderStatus status = new ShaderStatus(
            ShaderLoadState.REGISTERED, ShaderLoadOrigin.NONE, 0, 0L,
            0L, 0L, false, 0L, null
    );

    RenderShaderHandle(ResourceLocation id, ShaderParameterSchema parameterSchema) {
        this.id = Objects.requireNonNull(id, "id");
        parameters = new ShaderParameterBlock(Objects.requireNonNull(parameterSchema, "parameterSchema"));
        ShaderParameterPersistence.restore(id, parameters);
        parameters.onChange(() -> ShaderParameterPersistence.record(id, parameters));
        shaderState = new RenderStateShard.ShaderStateShard(this::get);
    }

    public ResourceLocation id() {
        return id;
    }

    /** Returns null before the first successful resource reload or after deregistration. */
    @Override
    @Nullable
    public ShaderInstance get() {
        return shader;
    }

    public Optional<ShaderInstance> shader() {
        return Optional.ofNullable(shader);
    }

    public ShaderInstance require() {
        ShaderInstance value = shader;
        if (value == null) {
            throw new IllegalStateException("Shader is not loaded: " + id);
        }
        return value;
    }

    public boolean isReady() {
        return shader != null;
    }

    /** Requests preload for this exact handle; a replacement with the same ID is never affected. */
    public boolean preload() {
        return RenderShaderRegistry.preload(this);
    }

    public ShaderStatus status() {
        return RenderShaderRegistry.status(this);
    }

    /**
     * Completes when this exact registration reaches READY. A failed, closed, or replaced
     * registration completes exceptionally; it never follows a newer shader with the same ID.
     */
    public CompletableFuture<ShaderStatus> whenReady() {
        return RenderShaderRegistry.whenReady(this);
    }

    /** Increases each time a newly compiled instance is installed. */
    public long generation() {
        return generation;
    }

    public RenderStateShard.ShaderStateShard shaderState() {
        return shaderState;
    }

    /** Global/default values used when this handle is passed directly to a fullscreen pass. */
    public ShaderParameterBlock parameters() {
        return parameters;
    }

    /** Creates an independent value block for another pass or effect instance. */
    public ShaderParameterBlock createParameterBlock() {
        return new ShaderParameterBlock(parameters.schema());
    }

    /** Uploads the default parameter block to the currently loaded shader. */
    public void applyParameters() {
        parameters.apply(require());
    }

    void install(ShaderInstance value) {
        shader = Objects.requireNonNull(value, "value");
        generation++;
    }

    void validateParameters(ShaderInstance value) {
        parameters.validate(value);
    }

    void invalidate() {
        shader = null;
    }

    void updateStatus(ShaderStatus value) {
        status = Objects.requireNonNull(value, "value");
    }

    ShaderStatus cachedStatus() {
        return status;
    }
}
