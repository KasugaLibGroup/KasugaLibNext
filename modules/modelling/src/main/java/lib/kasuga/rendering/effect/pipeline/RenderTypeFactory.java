package lib.kasuga.rendering.effect.pipeline;

import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** Native RenderType compiler used by {@link CompiledRenderPipeline}. */
@ApiStatus.Internal
public final class RenderTypeFactory {
    private RenderTypeFactory() {}

    public static RenderType create(
            ResourceLocation id,
            RenderDrawState drawState,
            RenderStateShard.EmptyTextureStateShard textureState,
            @Nullable String variant
    ) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(drawState, "drawState");
        Objects.requireNonNull(textureState, "textureState");
        String name = id.toString();
        if (variant != null && !variant.isBlank()) name += "/" + variant;

        RenderType.CompositeState state = RenderType.CompositeState.builder()
                .setShaderState(drawState.shaderState())
                .setTextureState(textureState)
                .setTransparencyState(drawState.transparencyState())
                .setDepthTestState(drawState.depthTestState())
                .setCullState(drawState.cullState())
                .setLightmapState(drawState.lightmapState())
                .setOverlayState(drawState.overlayState())
                .setLayeringState(drawState.layeringState())
                .setOutputState(drawState.outputState())
                .setTexturingState(drawState.texturingState())
                .setWriteMaskState(drawState.writeMaskState())
                .setLineState(drawState.lineState())
                .setColorLogicState(drawState.colorLogicState())
                .createCompositeState(drawState.outline());
        return RenderType.create(
                name,
                drawState.vertexFormat(),
                drawState.primitiveMode(),
                drawState.bufferSize(),
                drawState.affectsCrumbling(),
                drawState.sortOnUpload(),
                state
        );
    }
}
