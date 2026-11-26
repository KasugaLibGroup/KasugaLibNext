package lib.kasuga.rendering.models.mc.compat.iris;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import lib.kasuga.rendering.models.mc.backend.vbuffer.IVertexBuffer;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.CameraShaderSession;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.concurrent.ExecutorService;

/** Client adapter contract. Values crossing this boundary use Minecraft or Kasuga types. */
public interface IrisIntegration {
    void registerPbrLoader();

    boolean isUsingShaderPack();

    boolean supportsModelRendering();

    VertexFormat compatibleFormat(VertexFormat format, boolean force);

    IVertexBuffer createVertexBuffer(FlatModelData data, VertexFormat format, int parallelThreshold,
                                     int maxMergeGap, ExecutorService executor);

    boolean framebufferUnavailable();

    boolean bindWorldFramebuffer();

    @Nullable
    CameraShaderSession openCamera(CameraRenderSettings.Shader shader) throws IOException;

    void closeCameraBuffers(MultiBufferSource.BufferSource source);

    @Nullable
    Object createPbrSpriteContent(ResourceLocation name, FrameSize size, NativeImage image,
                                  ResourceMetadata metadata, Object pbrType) throws Exception;

    @Nullable
    Object createPbrTextureAtlasSprite(ResourceLocation name, Object content, int width, int height,
                                       int x, int y, TextureAtlasSprite sprite) throws Exception;
}
