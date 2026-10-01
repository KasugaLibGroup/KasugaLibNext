package lib.kasuga.mixins.client;

import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import lib.kasuga.KasugaLib;
import lib.kasuga.core.rendering.ShaderFunctionImport;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.io.File;
import java.io.FileWriter;
import java.util.Objects;
import java.util.Optional;

@Mixin(GlslPreprocessor.class)
public class MixinGlslPreprocessor {

    @Redirect(method = "processImports", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/preprocessor/GlslPreprocessor;applyImport(ZLjava/lang/String;)Ljava/lang/String;"))
    private String kasugaLib$applyImport(GlslPreprocessor instance, boolean pIsVertex, String pImport) {
        boolean debug = Boolean.getBoolean("kasuga.debugShaderImports");
        ResourceLocation rl = ResourceLocation.parse(pImport);
        if (!rl.getNamespace().equals(KasugaLib.MODID))
            return instance.applyImport(pIsVertex, pImport);
        rl = ResourceLocation.tryBuild(KasugaLib.MODID, "shaders/" + rl.getPath());
        Objects.requireNonNull(rl);
        Optional<Resource> resourceOpt = Minecraft.getInstance().getResourceManager().getResource(rl);
        if (resourceOpt.isEmpty()) {
//                .warn("Failed to load GLSL import: Resource {} not found", rl);
            return instance.applyImport(pIsVertex, pImport);
        }
        Resource resource = resourceOpt.get();
        try (var reader = resource.openAsReader()) {
            String result = ShaderFunctionImport.read(reader);
            if (debug) {
                File file = new File("ksg_debug/" + rl.getPath());
                if (!file.exists()) {
                    file.getParentFile().mkdirs();
                    file.createNewFile();
                }
                try (var writer = new FileWriter(file)) {
                    writer.write(result);
                } catch (Exception e) {
                    // Ignore currently.
                }
            }
            return result;
        } catch (Exception e) {
//                KasugaLib.LOGGER.warn("Failed to load GLSL import: Resource {} could not be read", rl, e);
            return instance.applyImport(pIsVertex, pImport);
        }
    }
}
