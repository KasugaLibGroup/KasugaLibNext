package lib.kasuga.rendering.effect.pipeline;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Immutable geometry layout and Minecraft draw state, independent from world-stage scheduling. */
public final class RenderDrawState {
    private final VertexFormat vertexFormat;
    private final VertexFormat.Mode primitiveMode;
    private final int bufferSize;
    private final boolean affectsCrumbling;
    private final boolean sortOnUpload;
    private final RenderStateShard.ShaderStateShard shaderState;
    private final RenderStateShard.EmptyTextureStateShard textureState;
    private final RenderStateShard.TransparencyStateShard transparencyState;
    private final RenderStateShard.DepthTestStateShard depthTestState;
    private final RenderStateShard.CullStateShard cullState;
    private final RenderStateShard.LightmapStateShard lightmapState;
    private final RenderStateShard.OverlayStateShard overlayState;
    private final RenderStateShard.LayeringStateShard layeringState;
    private final RenderStateShard.OutputStateShard outputState;
    private final RenderStateShard.TexturingStateShard texturingState;
    private final RenderStateShard.WriteMaskStateShard writeMaskState;
    private final RenderStateShard.LineStateShard lineState;
    private final RenderStateShard.ColorLogicStateShard colorLogicState;
    private final boolean outline;

    private RenderDrawState(Builder builder) {
        vertexFormat = Objects.requireNonNull(builder.vertexFormat, "vertexFormat");
        primitiveMode = Objects.requireNonNull(builder.primitiveMode, "primitiveMode");
        bufferSize = builder.bufferSize;
        affectsCrumbling = builder.affectsCrumbling;
        sortOnUpload = builder.sortOnUpload;
        shaderState = Objects.requireNonNull(builder.shaderState, "shaderState");
        textureState = Objects.requireNonNull(builder.textureState, "textureState");
        transparencyState = Objects.requireNonNull(builder.transparencyState, "transparencyState");
        depthTestState = Objects.requireNonNull(builder.depthTestState, "depthTestState");
        cullState = Objects.requireNonNull(builder.cullState, "cullState");
        lightmapState = Objects.requireNonNull(builder.lightmapState, "lightmapState");
        overlayState = Objects.requireNonNull(builder.overlayState, "overlayState");
        layeringState = Objects.requireNonNull(builder.layeringState, "layeringState");
        outputState = Objects.requireNonNull(builder.outputState, "outputState");
        texturingState = Objects.requireNonNull(builder.texturingState, "texturingState");
        writeMaskState = Objects.requireNonNull(builder.writeMaskState, "writeMaskState");
        lineState = Objects.requireNonNull(builder.lineState, "lineState");
        colorLogicState = Objects.requireNonNull(builder.colorLogicState, "colorLogicState");
        outline = builder.outline;
        if (bufferSize <= 0) throw new IllegalArgumentException("bufferSize must be positive");
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public VertexFormat vertexFormat() { return vertexFormat; }
    public VertexFormat.Mode primitiveMode() { return primitiveMode; }
    public int bufferSize() { return bufferSize; }
    public boolean affectsCrumbling() { return affectsCrumbling; }
    public boolean sortOnUpload() { return sortOnUpload; }
    public RenderStateShard.ShaderStateShard shaderState() { return shaderState; }
    public RenderStateShard.EmptyTextureStateShard textureState() { return textureState; }
    public RenderStateShard.TransparencyStateShard transparencyState() { return transparencyState; }
    public RenderStateShard.DepthTestStateShard depthTestState() { return depthTestState; }
    public RenderStateShard.CullStateShard cullState() { return cullState; }
    public RenderStateShard.LightmapStateShard lightmapState() { return lightmapState; }
    public RenderStateShard.OverlayStateShard overlayState() { return overlayState; }
    public RenderStateShard.LayeringStateShard layeringState() { return layeringState; }
    public RenderStateShard.OutputStateShard outputState() { return outputState; }
    public RenderStateShard.TexturingStateShard texturingState() { return texturingState; }
    public RenderStateShard.WriteMaskStateShard writeMaskState() { return writeMaskState; }
    public RenderStateShard.LineStateShard lineState() { return lineState; }
    public RenderStateShard.ColorLogicStateShard colorLogicState() { return colorLogicState; }
    public boolean outline() { return outline; }

    public static final class Builder {
        private VertexFormat vertexFormat = DefaultVertexFormat.POSITION;
        private VertexFormat.Mode primitiveMode = VertexFormat.Mode.QUADS;
        private int bufferSize = 1536;
        private boolean affectsCrumbling;
        private boolean sortOnUpload;
        private RenderStateShard.ShaderStateShard shaderState = RenderStateShard.POSITION_SHADER;
        private RenderStateShard.EmptyTextureStateShard textureState = RenderStateShard.NO_TEXTURE;
        private RenderStateShard.TransparencyStateShard transparencyState = RenderStateShard.NO_TRANSPARENCY;
        private RenderStateShard.DepthTestStateShard depthTestState = RenderStateShard.LEQUAL_DEPTH_TEST;
        private RenderStateShard.CullStateShard cullState = RenderStateShard.CULL;
        private RenderStateShard.LightmapStateShard lightmapState = RenderStateShard.NO_LIGHTMAP;
        private RenderStateShard.OverlayStateShard overlayState = RenderStateShard.NO_OVERLAY;
        private RenderStateShard.LayeringStateShard layeringState = RenderStateShard.NO_LAYERING;
        private RenderStateShard.OutputStateShard outputState = RenderStateShard.MAIN_TARGET;
        private RenderStateShard.TexturingStateShard texturingState = RenderStateShard.DEFAULT_TEXTURING;
        private RenderStateShard.WriteMaskStateShard writeMaskState = RenderStateShard.COLOR_DEPTH_WRITE;
        private RenderStateShard.LineStateShard lineState = RenderStateShard.DEFAULT_LINE;
        private RenderStateShard.ColorLogicStateShard colorLogicState = RenderStateShard.NO_COLOR_LOGIC;
        private boolean outline;

        private Builder() {}

        private Builder(RenderDrawState state) {
            vertexFormat = state.vertexFormat;
            primitiveMode = state.primitiveMode;
            bufferSize = state.bufferSize;
            affectsCrumbling = state.affectsCrumbling;
            sortOnUpload = state.sortOnUpload;
            shaderState = state.shaderState;
            textureState = state.textureState;
            transparencyState = state.transparencyState;
            depthTestState = state.depthTestState;
            cullState = state.cullState;
            lightmapState = state.lightmapState;
            overlayState = state.overlayState;
            layeringState = state.layeringState;
            outputState = state.outputState;
            texturingState = state.texturingState;
            writeMaskState = state.writeMaskState;
            lineState = state.lineState;
            colorLogicState = state.colorLogicState;
            outline = state.outline;
        }

        public Builder vertexFormat(VertexFormat value) { vertexFormat = Objects.requireNonNull(value); return this; }
        public Builder primitiveMode(VertexFormat.Mode value) { primitiveMode = Objects.requireNonNull(value); return this; }
        public Builder bufferSize(int value) { bufferSize = value; return this; }
        public Builder affectsCrumbling(boolean value) { affectsCrumbling = value; return this; }
        public Builder sortOnUpload(boolean value) { sortOnUpload = value; return this; }
        public Builder shader(RenderShaderHandle value) { return shaderState(Objects.requireNonNull(value).shaderState()); }
        public Builder shaderState(RenderStateShard.ShaderStateShard value) { shaderState = Objects.requireNonNull(value); return this; }
        public Builder texture(ResourceLocation texture, boolean blur, boolean mipmap) { return textureState(new RenderStateShard.TextureStateShard(texture, blur, mipmap)); }
        public Builder textureState(RenderStateShard.EmptyTextureStateShard value) { textureState = Objects.requireNonNull(value); return this; }
        public Builder blend(PipelineBlendMode value) { return transparencyState(Objects.requireNonNull(value).state()); }
        public Builder transparencyState(RenderStateShard.TransparencyStateShard value) { transparencyState = Objects.requireNonNull(value); return this; }
        public Builder depthTest(PipelineDepthTest value) { return depthTestState(Objects.requireNonNull(value).state()); }
        public Builder depthTestState(RenderStateShard.DepthTestStateShard value) { depthTestState = Objects.requireNonNull(value); return this; }
        public Builder cull(PipelineCullMode value) { return cullState(Objects.requireNonNull(value).state()); }
        public Builder cullState(RenderStateShard.CullStateShard value) { cullState = Objects.requireNonNull(value); return this; }
        public Builder lightmap(boolean enabled) { return lightmapState(enabled ? RenderStateShard.LIGHTMAP : RenderStateShard.NO_LIGHTMAP); }
        public Builder lightmapState(RenderStateShard.LightmapStateShard value) { lightmapState = Objects.requireNonNull(value); return this; }
        public Builder overlay(boolean enabled) { return overlayState(enabled ? RenderStateShard.OVERLAY : RenderStateShard.NO_OVERLAY); }
        public Builder overlayState(RenderStateShard.OverlayStateShard value) { overlayState = Objects.requireNonNull(value); return this; }
        public Builder layering(PipelineLayering value) { return layeringState(Objects.requireNonNull(value).state()); }
        public Builder layeringState(RenderStateShard.LayeringStateShard value) { layeringState = Objects.requireNonNull(value); return this; }
        public Builder target(PipelineTarget value) { return outputState(Objects.requireNonNull(value).state()); }
        public Builder outputState(RenderStateShard.OutputStateShard value) { outputState = Objects.requireNonNull(value); return this; }
        public Builder texturingState(RenderStateShard.TexturingStateShard value) { texturingState = Objects.requireNonNull(value); return this; }
        public Builder writeMask(PipelineWriteMask value) { return writeMaskState(Objects.requireNonNull(value).state()); }
        public Builder writeMaskState(RenderStateShard.WriteMaskStateShard value) { writeMaskState = Objects.requireNonNull(value); return this; }
        public Builder lineState(RenderStateShard.LineStateShard value) { lineState = Objects.requireNonNull(value); return this; }
        public Builder colorLogicState(RenderStateShard.ColorLogicStateShard value) { colorLogicState = Objects.requireNonNull(value); return this; }
        public Builder outline(boolean value) { outline = value; return this; }

        public RenderDrawState build() { return new RenderDrawState(this); }
    }
}
