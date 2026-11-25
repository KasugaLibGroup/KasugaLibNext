package lib.kasuga.mixins;

import lib.kasuga.inject.mixins.ServiceMixinPlugin;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;
import org.spongepowered.asm.service.MixinService;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class KasugaLibMixinPlugin extends ServiceMixinPlugin {
    @Override
    public List<String> getMixins() {
        // Supply optional classes through the plugin contract; adding a new
        // config from onLoad mutates MixinProcessor's active config iterator.
        String marker = "lib/kasuga/mixins/modelling/PeelShaderLoaderMixin.class";
        try (var resource = MixinService.getService().getResourceAsStream(marker)) {
            if (resource == null) return List.of();
            if (org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().getSide()
                    != org.spongepowered.asm.mixin.MixinEnvironment.Side.CLIENT)
                return List.of("modelling.CameraChunkServerMixin", "modelling.CameraEntityDataAccessor",
                        "modelling.ModelCollisionLevelMixin");
            var mixins = new java.util.ArrayList<>(List.of(
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
                    "modelling.WorldViewShaderMixin", "modelling.WorldViewGaussianSamplerMixin", "modelling.WorldViewSkyColorMixin",
                    "modelling.WorldViewMinecraftAccessor", "modelling.WorldViewNativeBuffersAccessor",
                    "modelling.WorldViewSkyBuffersAccessor", "modelling.WorldViewOutlineBuffersAccessor",
                    "modelling.WorldViewBufferPoolMixin", "modelling.WorldViewVanillaChunkJobMixin",
                    "modelling.WorldViewOptionsMixin",
                    "modelling.WorldViewQualityMixin", "modelling.WorldViewLeafQualityMixin", "modelling.WorldViewAssetsMixin",
                    "modelling.PeelShaderLoaderMixin",
                    "modelling.PeelChunkRendererMixin",
                    "modelling.PeelWorldRendererMixin",
                    "modelling.WeatherLevelRendererMixin"));
            try (var iris = MixinService.getService().getResourceAsStream("net/irisshaders/iris/Iris.class")) {
                if (iris != null) {
                    mixins.addAll(List.of(
                            "modelling.WorldViewIrisMixin", "modelling.WorldViewIrisStateAccessor",
                            "modelling.WorldViewIrisTimeAccessor", "modelling.WorldViewIrisCounterAccessor",
                            "modelling.WorldViewIrisMaterialsMixin", "modelling.WorldViewIrisChunkJobMixin"));
                    if (hasCloudCameraAbi()) mixins.add("modelling.WorldViewIrisCloudMixin");
                } else {
                    mixins.add("modelling.WorldViewSodiumAssetJobMixin");
                }
            }
            return mixins;
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("Cannot inspect optional modelling mixins", exception);
        }
    }

    private static boolean hasCloudCameraAbi() {
        // Iris 1.8.12 adds a static format flag to Sodium's cloud renderer. Only
        // those builds need per-camera isolation, and our wrapper needs this exact render method.
        ClassNode iris = readOptionalClass("net/irisshaders/iris/compat/sodium/mixin/MixinCloudRenderer.class");
        if (iris == null) return false;
        for (FieldNode field : iris.fields) {
            if (!field.name.equals("hadShadersOn") || !field.desc.equals("Z") || (field.access & Opcodes.ACC_STATIC) == 0) continue;
            ClassNode sodium = readOptionalClass("net/caffeinemc/mods/sodium/client/render/immediate/CloudRenderer.class");
            if (sodium == null) return false;
            String descriptor = "(Lnet/minecraft/client/Camera;Lnet/minecraft/client/multiplayer/ClientLevel;"
                    + "Lorg/joml/Matrix4f;Lcom/mojang/blaze3d/vertex/PoseStack;FF)V";
            for (MethodNode method : sodium.methods) {
                if (method.name.equals("render") && method.desc.equals(descriptor)) return true;
            }
        }
        return false;
    }

    private static ClassNode readOptionalClass(String resourceName) {
        try (InputStream resource = MixinService.getService().getResourceAsStream(resourceName)) {
            if (resource == null) return null;
            ClassNode node = new ClassNode();
            new ClassReader(resource).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        } catch (IOException | IllegalArgumentException unreadable) {
            return null;
        }
    }
}
