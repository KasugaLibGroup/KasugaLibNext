package lib.kasuga.rendering.output.mc;

import com.mojang.blaze3d.systems.RenderSystem;
import lib.kasuga.rendering.output.camera.CameraRenderSettings;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.EntityModelSet;
import net.minecraft.client.renderer.BlockEntityWithoutLevelRenderer;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Immutable loaded asset sets may be shared by identical camera pack stacks, independently of main reloads. */
public final class CameraAssets implements AutoCloseable {
    private record Key(List<Path> packs, int mipmaps) {}
    private static final Map<Key, CameraAssets> CACHE = new HashMap<>();
    private static final ThreadLocal<CameraAssets> ACTIVE = new ThreadLocal<>();
    private final Key key;
    public final ReloadableResourceManager resources = new ReloadableResourceManager(PackType.CLIENT_RESOURCES);
    public final TextureManager textures;
    public final ModelManager models;
    public final EntityModelSet entityModels = new EntityModelSet();
    public final BlockEntityRenderDispatcher blockEntities;
    public final BlockEntityWithoutLevelRenderer builtinEntities;
    public final BlockRenderDispatcher blocks;
    public final ItemRenderer items;
    public final EntityRenderDispatcher entities;
    private CompletableFuture<Void> loaded;
    private int owners;
    private boolean closed;

    public static CameraAssets acquire(CameraRenderSettings settings) {
        RenderSystem.assertOnRenderThread();
        Key key = new Key(settings.resourcePacks(), settings.mipmapLevels());
        CameraAssets assets = CACHE.get(key);
        if (assets == null) { assets = new CameraAssets(key); CACHE.put(key, assets); }
        assets.owners++;
        return assets;
    }
    public static CameraAssets current() { return ACTIVE.get(); }
    /** A queued vanilla rebuild owns a lease until cancellation or its CPU build completes. */
    public void retain() {
        RenderSystem.assertOnRenderThread();
        if (owners <= 0 || closed) throw new IllegalStateException("Camera assets retired");
        owners++;
    }
    public Scope enter() { return use(this); }
    public static Scope use(CameraAssets assets) { return new Scope(assets); }
    public static final class Scope implements AutoCloseable {
        private final CameraAssets previous = ACTIVE.get();
        private Scope(CameraAssets assets) { if (assets == null) ACTIVE.remove(); else ACTIVE.set(assets); }
        @Override public void close() { if (previous == null) ACTIVE.remove(); else ACTIVE.set(previous); }
    }
    private BlockRenderDispatcher blocks() { return blocks; }
    private ItemRenderer items() { return items; }
    private EntityRenderDispatcher entities() { return entities; }
    private CameraAssets(Key key) {
        this.key = key;
        Minecraft mc = Minecraft.getInstance();
        textures = new CameraTextureManager(resources, mc.getTextureManager());
        models = new ModelManager(textures, mc.getBlockColors(), key.mipmaps);
        blockEntities = new BlockEntityRenderDispatcher(mc.font, entityModels, this::blocks, this::items, this::entities);
        builtinEntities = new BlockEntityWithoutLevelRenderer(blockEntities, entityModels);
        blocks = new BlockRenderDispatcher(models.getBlockModelShaper(), builtinEntities, mc.getBlockColors());
        items = new ItemRenderer(mc, textures, models, mc.getItemColors(), builtinEntities);
        entities = new EntityRenderDispatcher(mc, textures, items, blocks, mc.font, mc.options, entityModels);
        List<PackResources> packs = new ArrayList<>();
        try {
            // Keep the client's required vanilla/mod resources, excluding its chosen file/server packs.
            for (var pack : mc.getResourcePackRepository().getSelectedPacks()) {
                if (pack.getId().equals("vanilla") || pack.getId().equals("mod_resources") || pack.getId().startsWith("mod/"))
                    packs.add(pack.open());
            }
            if (packs.isEmpty()) throw new IllegalStateException("Camera has no vanilla/mod base resource packs");
            for (Path path : key.packs) {
                if (!Files.exists(path)) throw new IllegalArgumentException("Camera resource pack does not exist: " + path);
                var location = new PackLocationInfo("camera/" + path.getFileName(), Component.literal(path.getFileName().toString()),
                        PackSource.DEFAULT, Optional.empty());
                packs.add(Files.isDirectory(path) ? new PathPackResources(location, path)
                        : new FilePackResources.FileResourcesSupplier(path).openPrimary(location));
            }
            resources.registerReloadListener(models);
            Executor background = task -> Util.backgroundExecutor().execute(() -> { try (var ignored = enter()) { task.run(); } });
            Executor render = task -> mc.execute(() -> { try (var ignored = enter()) { task.run(); } });
            loaded = resources.createReload(background, render, CompletableFuture.completedFuture(net.minecraft.util.Unit.INSTANCE), packs)
                    .done().thenRunAsync(() -> {
                        entityModels.onResourceManagerReload(resources);
                        builtinEntities.onResourceManagerReload(resources);
                        blocks.onResourceManagerReload(resources);
                        items.onResourceManagerReload(resources);
                        entities.onResourceManagerReload(resources);
                        blockEntities.onResourceManagerReload(resources);
                    }, render);
        } catch (RuntimeException failure) {
            packs.forEach(PackResources::close); models.close(); textures.close(); resources.close(); throw failure;
        }
    }
    boolean ready() {
        if (!loaded.isDone()) return false;
        loaded.join(); return true;
    }
    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if (owners <= 0) return;
        if (--owners != 0) return;
        CACHE.remove(key, this);
        // Reload continuations can still upload: retire after completion instead of freeing underneath them.
        loaded.handle((ok, failure) -> null).thenRunAsync(() -> {
            if (closed) return; closed = true;
            try { models.close(); } finally { try { textures.close(); } finally { resources.close(); } }
        }, Minecraft.getInstance());
    }
}
