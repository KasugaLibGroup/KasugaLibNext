package lib.kasuga.rendering.models.mc.compat.iris;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.logging.LogUtils;
import lib.kasuga.core.compat.CompatCapability;
import lib.kasuga.rendering.models.mc.backend.FlatModelData;
import lib.kasuga.rendering.models.mc.backend.vbuffer.IVertexBuffer;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import lib.kasuga.rendering.output.mc.CameraShaderSession;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/** Client-side entry point for the optional Iris module. */
public class IrisCompat {
    public static final String IRIS_MOD_ID = "iris";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CompatCapability<IrisIntegration> ADAPTER = new CompatCapability<>("Kasuga Iris adapter", () ->
            CompatCapability.requireClass("lib.kasuga.compat.iris.IrisRenderingIntegration", IrisCompat.class.getClassLoader())
                    .asSubclass(IrisIntegration.class).getConstructor().newInstance());

    private static Optional<? extends ModContainer> irisModContainer = Optional.empty();
    @Nullable
    private static IrisIntegration adapter;
    private static boolean modelFormatReported;

    public static void onStart() {
        irisModContainer = ModList.get().getModContainerById(IRIS_MOD_ID);
        if (!isIrisPresent()) return;

        CompatCapability.Result<IrisIntegration> result = ADAPTER.result();
        adapter = result.value();
        if (adapter == null) {
            LOGGER.warn("Kasuga Iris integration disabled ({}).", result.reason(), result.cause());
            return;
        }
        adapter.registerPbrLoader();
    }

    public static Optional<? extends ModContainer> getIrisModContainer() {
        return irisModContainer;
    }

    public static boolean isIrisPresent() {
        return irisModContainer.isPresent();
    }

    public static void onIrisPresentOrElse(Consumer<ModContainer> onPresent, Runnable onAbsent) {
        irisModContainer.ifPresentOrElse(onPresent, onAbsent);
    }

    public static void onIrisPresent(Consumer<ModContainer> onPresent) {
        irisModContainer.ifPresent(onPresent);
    }

    public static void onIrisAbsent(Runnable onAbsent) {
        if (irisModContainer.isEmpty()) onAbsent.run();
    }

    public static boolean isUsingShaderPack() {
        return adapter != null && adapter.isUsingShaderPack();
    }

    public static boolean supportsModelRendering() {
        return adapter != null && adapter.supportsModelRendering();
    }

    public static void reportUnsupportedModelFormat() {
        if (modelFormatReported) return;
        modelFormatReported = true;
        String version = irisModContainer.map(mod -> mod.getModInfo().getVersion().toString()).orElse("unknown");
        Minecraft client = Minecraft.getInstance();
        client.getToasts().addToast(SystemToast.multiline(client, new SystemToast.SystemToastId(),
                Component.translatable("kasuga_lib.iris.models.unavailable.title"),
                Component.translatable("kasuga_lib.iris.models.unavailable.message", version)));
    }

    @Nullable
    public static Object createPBRSpriteContent(ResourceLocation name, FrameSize size, NativeImage image,
                                                ResourceMetadata meta, Object pbrType) throws Exception {
        return adapter == null ? null : adapter.createPbrSpriteContent(name, size, image, meta, pbrType);
    }

    public static Object createPBRTextureAtlasSprite(ResourceLocation name, Object content, int x0, int y0, int x1,
                                                    int y1, TextureAtlasSprite sprite) throws Exception {
        return adapter == null ? null : adapter.createPbrTextureAtlasSprite(name, content, x0, y0, x1, y1, sprite);
    }

    public static VertexFormat getIrisCompatibleFormat(VertexFormat format) {
        return isUsingShaderPack() && supportsModelRendering() ? adapter.compatibleFormat(format, false) : format;
    }

    public static VertexFormat getIrisFormat(VertexFormat format, boolean iris) {
        return iris && supportsModelRendering() ? adapter.compatibleFormat(format, true) : format;
    }

    public static IVertexBuffer createVertexBuffer(FlatModelData data, VertexFormat format, int parallelThreshold,
                                                   int maxMergeGap, ExecutorService executor) {
        if (adapter == null) throw new IllegalStateException("Kasuga Iris integration unavailable");
        return adapter.createVertexBuffer(data, format, parallelThreshold, maxMergeGap, executor);
    }

    /** Binds the current world target if Iris is rendering a supported shader pack. */
    public static boolean bindWorldFramebuffer() {
        return isUsingShaderPack() && bindShaderPackWorldFramebuffer();
    }

    static boolean framebufferUnavailable() {
        return adapter == null || adapter.framebufferUnavailable();
    }

    static boolean bindShaderPackWorldFramebuffer() {
        return adapter != null && adapter.bindWorldFramebuffer();
    }

    @Nullable
    public static CameraShaderSession openCamera(CameraRenderSettings.Shader shader) throws IOException {
        if (adapter != null) return adapter.openCamera(shader);
        if (isIrisPresent()) throw new IOException("Kasuga's Iris camera integration is unavailable");
        if (shader.pack() != null) throw new IllegalStateException("This camera requires Iris");
        return null;
    }

    public static void closeCameraBuffers(MultiBufferSource.BufferSource source) {
        if (adapter != null) adapter.closeCameraBuffers(source);
    }
}
