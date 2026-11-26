package lib.kasuga.compat.iris;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import lib.kasuga.compat.iris.vertex.IrisVertexBuffer;
import lib.kasuga.core.compat.CompatCapability;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import lib.kasuga.rendering.models.mc.backend.vbuffer.IVertexBuffer;
import lib.kasuga.rendering.models.mc.compat.iris.IrisIntegration;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.CameraShaderSession;
import lib.kasuga.rendering.output.mc.iris.CameraIrisSession;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.function.BooleanSupplier;

/** Loaded on the client after NeoForge confirms that Iris is present. */
public final class IrisRenderingIntegration implements IrisIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final BooleanSupplier shaderPackInUse = IrisApi.getInstance()::isShaderPackInUse;
    private final CompatCapability<Boolean> modelFormats =
            new CompatCapability<>("Iris model vertex format", () -> IrisModelFormats.probe());
    private final CompatCapability<KasugaPBRLoader> pbr =
            new CompatCapability<>("Iris PBR loader", () -> KasugaPBRLoader.register());

    private boolean packQueryFailed;
    private boolean formatFailureReported;
    private boolean cameraBuffersUnavailable;

    @Override
    public void registerPbrLoader() {
        CompatCapability.Result<KasugaPBRLoader> result = pbr.result();
        if (!result.available()) {
            LOGGER.warn("Kasuga Iris PBR disabled ({}).", result.reason(), result.cause());
        }
    }

    @Override
    public boolean isUsingShaderPack() {
        if (packQueryFailed) return false;
        try {
            return shaderPackInUse.getAsBoolean();
        } catch (LinkageError failure) {
            packQueryFailed = true;
            LOGGER.warn("Kasuga could not query the active Iris shader pack. Using native model rendering.", failure);
            return false;
        }
    }

    @Override
    public boolean supportsModelRendering() {
        CompatCapability.Result<Boolean> result = modelFormats.result();
        if (!result.available() && !formatFailureReported) {
            formatFailureReported = true;
            LOGGER.warn("Kasuga cannot render world models with Iris shaders enabled ({}).", result.reason(), result.cause());
        }
        return result.available();
    }

    @Override
    public VertexFormat compatibleFormat(VertexFormat format, boolean force) {
        return IrisModelFormats.compatibleFormat(format, force);
    }

    @Override
    public IVertexBuffer createVertexBuffer(FlatModelData data, VertexFormat format, int parallelThreshold,
                                            int maxMergeGap, ExecutorService executor) {
        return new IrisVertexBuffer(data, format, parallelThreshold, maxMergeGap, executor);
    }

    @Override
    public boolean framebufferUnavailable() {
        return IrisWorldFramebuffer.framebufferUnavailable();
    }

    @Override
    public boolean bindWorldFramebuffer() {
        return IrisWorldFramebuffer.bindWorldFramebuffer();
    }

    @Override
    public CameraShaderSession openCamera(CameraRenderSettings.Shader shader) throws IOException {
        if (!IrisCameraMixinPlugin.supportsCamera()) {
            throw new IOException("Iris camera shader isolation is unavailable with the installed Iris and Sodium versions");
        }
        try {
            return new CameraIrisSession(shader);
        } catch (LinkageError failure) {
            throw new IOException("Cannot start the Iris camera shader session", failure);
        }
    }

    @Override
    public void closeCameraBuffers(MultiBufferSource.BufferSource source) {
        if (cameraBuffersUnavailable) return;
        try {
            CameraIrisNativeBuffers.close(source);
        } catch (LinkageError failure) {
            cameraBuffersUnavailable = true;
            LOGGER.warn("Kasuga cannot release Iris-specific camera buffers with this Iris version.", failure);
        }
    }

    @Override
    public Object createPbrSpriteContent(ResourceLocation name, FrameSize size, NativeImage image,
                                         ResourceMetadata metadata, Object pbrType) throws Exception {
        KasugaPBRLoader loader = pbr.result().value();
        return loader == null ? null : loader.createSpriteContent(name, size, image, metadata, pbrType);
    }

    @Override
    public Object createPbrTextureAtlasSprite(ResourceLocation name, Object content, int width, int height,
                                              int x, int y, TextureAtlasSprite sprite) throws Exception {
        KasugaPBRLoader loader = pbr.result().value();
        return loader == null ? null : loader.createAtlasSprite(name, content, width, height, x, y, sprite);
    }
}
