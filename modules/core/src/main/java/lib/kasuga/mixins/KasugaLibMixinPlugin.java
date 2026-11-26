package lib.kasuga.mixins;

import lib.kasuga.inject.mixins.ServiceMixinPlugin;
import org.spongepowered.asm.service.MixinService;
import java.io.InputStream;
import java.util.List;

public class KasugaLibMixinPlugin extends ServiceMixinPlugin {
    @Override
    public List<String> getMixins() {
        // Supply optional classes through the plugin contract; adding a new
        // config from onLoad mutates MixinProcessor's active config iterator.
        String marker = "lib/kasuga/mixins/modelling/PeelShaderLoaderMixin.class";
        try (InputStream resource = MixinService.getService().getResourceAsStream(marker)) {
            if (resource == null) return List.of();
            if (org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().getSide()
                    != org.spongepowered.asm.mixin.MixinEnvironment.Side.CLIENT)
                return List.of("modelling.CameraChunkServerMixin", "modelling.CameraEntityDataAccessor",
                        "modelling.ModelCollisionLevelMixin");
            return List.of(
                    "modelling.MinecraftFrameOutputMixin", "modelling.HeadlessGlfwMixin",
                    "modelling.WorldUiKeyboardMixin",
                    "modelling.HeadlessWindowMixin", "modelling.HeadlessMinecraftMixin", "modelling.HeadlessRenderSystemMixin", "modelling.CameraEntityDataAccessor",
                    "modelling.CameraChunkServerMixin", "modelling.ModelCollisionLevelMixin", "modelling.WorldViewBiomeAccessor",
                    "modelling.WorldViewLevelDataAccessor", "modelling.WorldViewClientLevelAccessor",
                    "modelling.WorldViewChunkLightMixin", "modelling.WorldViewConnectionAccessor",
                    "modelling.CameraNearPlaneAccessor", "modelling.CameraWalkAnimationAccessor",
                    "modelling.WorldViewFogAccessor",
                    "modelling.WorldViewGameRendererMixin", "modelling.PlayerCameraAccessor",
                    "modelling.WorldViewLevelRendererMixin",
                    "modelling.WorldViewSodiumMixin",
                    "modelling.WorldViewSodiumAssetJobMixin",
                    "modelling.WorldViewShaderMixin", "modelling.WorldViewGaussianSamplerMixin", "modelling.WorldViewSkyColorMixin",
                    "modelling.WorldViewMinecraftAccessor", "modelling.WorldViewNativeBuffersAccessor",
                    "modelling.WorldViewSkyBuffersAccessor", "modelling.WorldViewOutlineBuffersAccessor",
                    "modelling.WorldViewBufferPoolMixin", "modelling.WorldViewVanillaChunkJobMixin",
                    "modelling.WorldViewOptionsMixin",
                    "modelling.WorldViewQualityMixin", "modelling.WorldViewLeafQualityMixin", "modelling.WorldViewAssetsMixin",
                    "modelling.PeelShaderLoaderMixin",
                    "modelling.PeelChunkRendererMixin",
                    "modelling.PeelWorldRendererMixin",
                    "modelling.WeatherLevelRendererMixin");
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot inspect optional modelling mixins", exception);
        }
    }

}
