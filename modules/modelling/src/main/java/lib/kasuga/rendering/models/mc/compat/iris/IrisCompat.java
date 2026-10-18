package lib.kasuga.rendering.models.mc.compat.iris;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexFormat;
import lib.kasuga.rendering.models.mc.backend.data_type.OitCompositeShaderInstance;
import lombok.Getter;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.util.Optional;
import java.util.function.Consumer;

public class IrisCompat {

    public static final String IRIS_MOD_ID = "iris";

    @Getter
    private static Optional<? extends ModContainer> irisModContainer;

    private static Class<?> pbrContentClazz, pbrTextureClazz;
    private static Constructor<?> pbrContentConstructor, pbrTextureConstructor;

    public static void onStart() {
        irisModContainer = ModList.get().getModContainerById(IRIS_MOD_ID);
        if (isIrisPresent()) {
            onIrisSetup();
        }
    }

    private static void onIrisSetup() {
        try {
            pbrContentClazz = Class.forName("net.irisshaders.iris.pbr.loader.AtlasPBRLoader$PBRSpriteContents");
            pbrTextureClazz = Class.forName("net.irisshaders.iris.pbr.loader.AtlasPBRLoader$PBRTextureAtlasSprite");
            pbrContentConstructor = pbrContentClazz.getDeclaredConstructor(
                    ResourceLocation.class,
                    FrameSize.class,
                    NativeImage.class,
                    ResourceMetadata.class,
                    Class.forName("net.irisshaders.iris.pbr.texture.PBRType")
            );
            pbrContentConstructor.setAccessible(true);

            pbrTextureConstructor = pbrTextureClazz.getDeclaredConstructor(
                    ResourceLocation.class,
                    pbrContentClazz,
                    int.class, int.class, int.class, int.class,
                    TextureAtlasSprite.class
            );
            pbrTextureConstructor.setAccessible(true);
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            e.printStackTrace();
        }
        IrisConstants.register();
    }

    public static boolean isIrisPresent() {
        return irisModContainer != null && irisModContainer.isPresent();
    }

    public static void onIrisPresentOrElse(Consumer<ModContainer> onPresent, Runnable onAbsent) {
        irisModContainer.ifPresentOrElse(onPresent, onAbsent);
    }

    public static void onIrisPresent(Consumer<ModContainer> onPresent) {
        irisModContainer.ifPresent(onPresent);
    }

    public static void onIrisAbsent(Runnable onAbsent) {
        if (irisModContainer.isEmpty()) {
            onAbsent.run();
        }
    }

    public static boolean isUsingShaderPack() {
        return isIrisPresent() && IrisApi.getInstance().isShaderPackInUse();
    }

    @Nullable
    public static Object createPBRSpriteContent(ResourceLocation name, FrameSize size, NativeImage image, ResourceMetadata meta, Object pbrType) throws Exception {
        if (!isIrisPresent()) return null;
        return pbrContentConstructor.newInstance(name, size, image, meta, pbrType);
    }

    public static Object createPBRTextureAtlasSprite(ResourceLocation name, Object content, int x0, int y0, int x1, int y1, TextureAtlasSprite sprite) throws Exception {
        if (!isIrisPresent()) return null;
        return pbrTextureConstructor.newInstance(name, content, x0, y0, x1, y1, sprite);
    }

    public static VertexFormat getIrisCompatibleFormat(VertexFormat format) {
        if (!isUsingShaderPack()) return format;
        return IrisConstants.getIrisFormat(format);
    }

    public static VertexFormat getIrisFormat(VertexFormat format, boolean iris) {
        return iris && isIrisPresent() ? IrisConstants.forceIrisFormat(format) : format;
    }

    /** Allows only Kasuga's framebuffer-aware OIT resolve through Iris' unknown-shader guard. */
    public static void allowOitCompositeShader() {
        if (isIrisPresent()) {
            IrisConstants.allowShaderClass(OitCompositeShaderInstance.class);
        }
    }

    /** Binds Iris' current before/after-translucent world framebuffer. */
    public static boolean bindWorldFramebuffer() {
        return isUsingShaderPack() && IrisConstants.bindWorldFramebuffer();
    }
}
