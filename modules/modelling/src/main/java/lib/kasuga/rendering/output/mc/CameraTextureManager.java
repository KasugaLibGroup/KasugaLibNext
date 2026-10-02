package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

/** Camera-owned pack textures; generated connection/mod textures are borrowed without taking ownership. */
final class CameraTextureManager extends TextureManager {
    private final TextureManager connectionTextures;
    private final ResourceManager resources;
    CameraTextureManager(ResourceManager resources, TextureManager connectionTextures) {
        super(resources); this.resources = resources; this.connectionTextures = connectionTextures;
    }
    private AbstractTexture shared(ResourceLocation path) {
        String name = path.getPath();
        boolean generated = path.getNamespace().equals("minecraft") && (name.startsWith("skins/")
                || name.startsWith("capes/") || name.startsWith("elytra/")
                || name.startsWith("dynamic/") || name.startsWith("default/")
                || name.equals("textures/atlas/particles.png"));
        if (!generated && resources.getResource(path).isPresent()) return null;
        return connectionTextures.getTexture(path, null);
    }
    @Override public AbstractTexture getTexture(ResourceLocation path) {
        var owned = super.getTexture(path, null);
        if (owned != null) return owned;
        var borrowed = shared(path);
        return borrowed == null ? super.getTexture(path) : borrowed;
    }
    @Override public AbstractTexture getTexture(ResourceLocation path, AbstractTexture fallback) {
        var owned = super.getTexture(path, null);
        if (owned != null) return owned;
        var borrowed = shared(path);
        return borrowed == null ? super.getTexture(path, fallback) : borrowed;
    }
    @Override public void bindForSetup(ResourceLocation path) {
        if (!RenderSystem.isOnRenderThread()) RenderSystem.recordRenderCall(() -> getTexture(path).bind());
        else getTexture(path).bind();
    }
}
