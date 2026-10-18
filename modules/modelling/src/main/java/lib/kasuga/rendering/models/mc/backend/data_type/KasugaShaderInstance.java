package lib.kasuga.rendering.models.mc.backend.data_type;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.shaders.Uniform;
import lib.kasuga.rendering.models.mc.Constants;
import lib.kasuga.rendering.models.uml.math.Transform;
import lombok.Getter;
import lombok.Setter;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL31;

import java.io.IOException;

public class KasugaShaderInstance extends ShaderInstance {

    private static final Matrix4f IDENTITY_M4F = new Matrix4f().identity();
    private static final Matrix3f IDENTITY_M3F = new Matrix3f().identity();
    private static final int BONE_TRANSFORM_TEXTURE_UNIT = 11;

    @Getter
    private float emissiveStrength = 1.0f;

    @Getter
    private float parallaxScale = 0.0025f;

    @Getter
    private int parallaxSamplerTimes = 4;

    @Getter
    private float ambientLightEnhancement = 1.5f;

    @Getter
    private float stylizedShadingStrength = 0.65f;

    private float brightness = 1.0f;
    private int packedLight = 0;
    private int packedOverlay = 0;
    private boolean gpuSkinningEnabled = false;
    private int boneTransformTextureId = 0;
    private int alphaMode = 0;
    /** 0 = normal output, 1 = weighted accumulation, 2 = revealage. */
    private int oitMode = 0;

    @Setter
    private @Nullable PoseStack.Pose currentPose;


    public KasugaShaderInstance(ResourceProvider p_173336_, ResourceLocation shaderLocation, VertexFormat p_173338_) throws IOException {
        super(p_173336_, shaderLocation, p_173338_);
    }

    public void setEmissiveStrength(float emissiveStrength) {
        this.emissiveStrength = Math.clamp(emissiveStrength, 0f, 1f);
    }

    public void disableEmissive() {
        setEmissiveStrength(0f);
    }

    public void disablePose() {
        this.currentPose = null;
    }

    public void setParallaxScale(float parallaxScale) {
        this.parallaxScale = Math.clamp(parallaxScale, 0f, 1f);
    }

    public void resetParallaxScale() {
        setParallaxScale(0.0025f);
    }

    public void disableParallax() {
        setParallaxScale(0f);
    }

    public void setParallaxSamplerTimes(int times) {
        this.parallaxSamplerTimes = Math.clamp(times, 1, 64);
    }

    public void resetParallaxSamplerTimes() {
        setParallaxSamplerTimes(4);
    }

    public void disableSteepParallaxSampling() {
        setParallaxSamplerTimes(1);
    }

    public void setAmbientLightEnhancement(float enhancement) {
        this.ambientLightEnhancement = Math.clamp(enhancement, 0f, 10000f);
    }

    public void resetAmbientLightEnhancement() {
        setAmbientLightEnhancement(1.5f);
    }

    public void disableAmbientLightEnhancement() {
        setAmbientLightEnhancement(1f);
    }

    /** Blends between physically-shaped Lambert lighting and the readable toon ramp. */
    public void setStylizedShadingStrength(float strength) {
        this.stylizedShadingStrength = Math.clamp(strength, 0f, 1f);
    }

    public void resetStylizedShadingStrength() {
        setStylizedShadingStrength(0.65f);
    }

    public void disableStylizedShading() {
        setStylizedShadingStrength(0f);
    }

    public void setLightData(float brightness, int packedLight, int packedOverlay) {
        this.brightness = brightness;
        this.packedLight = packedLight;
        this.packedOverlay = packedOverlay;
    }

    public void setGpuSkinningState(boolean enabled, int boneTransformTextureId) {
        this.gpuSkinningEnabled = enabled && boneTransformTextureId > 0;
        this.boneTransformTextureId = boneTransformTextureId;
    }

    /** 0 = opaque, 1 = alpha mask, 2 = conventional translucent blend. */
    public void setAlphaMode(int alphaMode) {
        this.alphaMode = Math.clamp(alphaMode, 0, 2);
    }

    public int getAlphaMode() {
        return alphaMode;
    }

    public void setOitMode(int oitMode) {
        // 3 is the depth-writing opaque coverage of a BLEND material.
        // 5 marks the visible translucent footprint for local depth peeling.
        this.oitMode = Math.clamp(oitMode, 0, 5);
    }

    public int getOitMode() {
        return oitMode;
    }

    protected void applyAdditionalData() {
        // TODO: 记得同步修改着色器的相关系数
        this.safeGetUniform("ksg_EmissiveStrength").set(this.emissiveStrength);
        this.safeGetUniform("ksg_ParallaxScale").set(this.parallaxScale);
        this.safeGetUniform("ksg_ParallaxSamples").set(this.parallaxSamplerTimes);
        this.safeGetUniform("ksg_AmbientLightEnhancement").set(this.ambientLightEnhancement);
        this.safeGetUniform("ksg_StylizedShadingStrength").set(this.stylizedShadingStrength);
        this.safeGetUniform("ksg_BrightnessScale").set(this.brightness);
        this.safeGetUniform("ksg_PackedLight").set(this.packedLight & 0xffff, (this.packedLight >>> 16) & 0xffff);
        this.safeGetUniform("ksg_PackedOverlay").set(this.packedOverlay & 0xffff, (this.packedOverlay >>> 16) & 0xffff);
        this.safeGetUniform("ksg_GpuSkinningEnabled").set(this.gpuSkinningEnabled ? 1 : 0);
        this.safeGetUniform("ksg_AlphaMode").set(this.alphaMode);
        this.safeGetUniform("ksg_OitMode").set(this.oitMode);
        if (currentPose != null) {
            this.safeGetUniform("ksg_ModelPoseMat").set(this.currentPose.pose());
            this.safeGetUniform("ksg_ModelNormalMat").set(this.currentPose.normal());
        } else {
            this.safeGetUniform("ksg_ModelPoseMat").set(IDENTITY_M4F);
            this.safeGetUniform("ksg_ModelNormalMat").set(IDENTITY_M3F);
        }
        this.setSampler("ksg_NormalMap", Constants.TEXTURE_BASIC.getNormalMap().getId());
        this.setSampler("ksg_SpecularMap", Constants.TEXTURE_BASIC.getSpecularMap().getId());
    }

    @Override
    public void apply() {
        applyAdditionalData();
        super.apply();
        applyGpuSkinningTexture();
        lib.kasuga.rendering.models.mc.backend.LayeredTransparency.bindShader(getId());
    }

    private void applyGpuSkinningTexture() {
        int location = Uniform.glGetUniformLocation(getId(), "ksg_BoneTransforms");
        if (location < 0) {
            return;
        }
        int previousActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + BONE_TRANSFORM_TEXTURE_UNIT);
        GL11.glBindTexture(GL31.GL_TEXTURE_BUFFER, this.gpuSkinningEnabled ? this.boneTransformTextureId : 0);
        Uniform.uploadInteger(location, BONE_TRANSFORM_TEXTURE_UNIT);
        RenderSystem.activeTexture(previousActiveTexture);
    }
}
