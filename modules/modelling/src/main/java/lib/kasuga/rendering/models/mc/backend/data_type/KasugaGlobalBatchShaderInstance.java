package lib.kasuga.rendering.models.mc.backend.data_type;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.models.mc.Constants;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL31;

import java.io.IOException;

public class KasugaGlobalBatchShaderInstance extends KasugaShaderInstance {

    private static final int INSTANCE_DATA_TEXTURE_UNIT = 10;
    private static final int BONE_TRANSFORM_TEXTURE_UNIT = 11;

    private int instanceDataTextureId;
    private int boneTransformTextureId;

    public KasugaGlobalBatchShaderInstance(ResourceProvider provider,
                                           ResourceLocation shaderLocation,
                                           VertexFormat format) throws IOException {
        super(provider, shaderLocation, format);
    }

    public void setBatchTextures(int instanceDataTextureId, int boneTransformTextureId) {
        this.instanceDataTextureId = instanceDataTextureId;
        this.boneTransformTextureId = boneTransformTextureId;
    }

    @Override
    protected void applyAdditionalData() {
        safeGetUniform("ksg_EmissiveStrength").set(getEmissiveStrength());
        safeGetUniform("ksg_ParallaxScale").set(getParallaxScale());
        safeGetUniform("ksg_ParallaxSamples").set(getParallaxSamplerTimes());
        safeGetUniform("ksg_AmbientLightEnhancement").set(getAmbientLightEnhancement());
        safeGetUniform("ksg_StylizedShadingStrength").set(getStylizedShadingStrength());
        safeGetUniform("ksg_AlphaMode").set(getAlphaMode());
        // Global batches are currently restricted to OPAQUE/MASK. Explicitly
        // reset this value so a reused shader instance can never leak an OIT
        // output mode into the ordinary batch draw.
        safeGetUniform("ksg_OitMode").set(0);
        setSampler("ksg_NormalMap", Constants.TEXTURE_BASIC.getNormalMap().getId());
        setSampler("ksg_SpecularMap", Constants.TEXTURE_BASIC.getSpecularMap().getId());
    }

    @Override
    public void apply() {
        setGpuSkinningState(false, 0);
        disablePose();
        super.apply();
        bindTextureBuffer("ksg_InstanceData", INSTANCE_DATA_TEXTURE_UNIT, instanceDataTextureId);
        bindTextureBuffer("ksg_BoneTransforms", BONE_TRANSFORM_TEXTURE_UNIT, boneTransformTextureId);
    }

    private void bindTextureBuffer(String sampler, int textureUnit, int textureId) {
        int location = Uniform.glGetUniformLocation(getId(), sampler);
        if (location < 0) return;

        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + textureUnit);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, textureId);
        Uniform.uploadInteger(location, textureUnit);
        RenderSystem.activeTexture(previousActiveTexture);
    }
}
