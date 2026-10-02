package lib.kasuga.rendering.output.mc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;

/** Mutable dispatchers belong to a camera even when immutable model/atlas assets are shared. */
public final class CameraSceneRenderers {
    private static final ThreadLocal<CameraSceneRenderers> ACTIVE = new ThreadLocal<>();
    public final BlockEntityRenderDispatcher blockEntities;
    public final BlockEntityWithoutLevelRenderer builtinEntities;
    public final BlockRenderDispatcher blocks;
    public final ItemRenderer items;
    public final EntityRenderDispatcher entities;
    CameraSceneRenderers(CameraAssets assets) {
        Minecraft mc = Minecraft.getInstance();
        blockEntities = new BlockEntityRenderDispatcher(mc.font, assets.entityModels, this::blocks, this::items, this::entities);
        builtinEntities = new BlockEntityWithoutLevelRenderer(blockEntities, assets.entityModels);
        blocks = new BlockRenderDispatcher(assets.models.getBlockModelShaper(), builtinEntities, mc.getBlockColors());
        items = new ItemRenderer(mc, assets.textures, assets.models, mc.getItemColors(), builtinEntities);
        entities = new EntityRenderDispatcher(mc, assets.textures, items, blocks, mc.font, mc.options, assets.entityModels);
        try (var ignored = assets.enter(); var scene = enter()) {
            builtinEntities.onResourceManagerReload(assets.resources);
            blocks.onResourceManagerReload(assets.resources);
            items.onResourceManagerReload(assets.resources);
            entities.onResourceManagerReload(assets.resources);
            blockEntities.onResourceManagerReload(assets.resources);
        }
    }
    private BlockRenderDispatcher blocks() { return blocks; }
    private ItemRenderer items() { return items; }
    private EntityRenderDispatcher entities() { return entities; }
    public static CameraSceneRenderers current() { return ACTIVE.get(); }
    public Scope enter() { return use(this); }
    public static Scope use(CameraSceneRenderers renderers) { return new Scope(renderers); }
    public static final class Scope implements AutoCloseable {
        private final CameraSceneRenderers previous = ACTIVE.get();
        private Scope(CameraSceneRenderers renderers) { if (renderers == null) ACTIVE.remove(); else ACTIVE.set(renderers); }
        @Override public void close() { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
}
