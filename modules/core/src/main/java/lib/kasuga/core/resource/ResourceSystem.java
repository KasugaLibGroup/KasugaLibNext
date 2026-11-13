package lib.kasuga.core.resource;

import io.micronaut.context.annotation.Context;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.VfsSubscription;
import lib.kasuga.core.vfs.VirtualFileSystem;
import lib.kasuga.core.vfs.backend.MountTableVfs;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Context()
public class ResourceSystem implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(ResourceSystem.class);

    ScopedResourceManager client = null;
    HashMap<MinecraftServer, ScopedResourceManager> managers = new HashMap<>();
    private final CopyOnWriteArrayList<AutoCloseable> managedCloseables = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<MinecraftServer, CopyOnWriteArrayList<AutoCloseable>> serverManagedCloseables = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<AutoCloseable> clientManagedCloseables = new CopyOnWriteArrayList<>();
    private final MountTableVfs unifiedVfs = new MountTableVfs("kasuga_unified");
    private final lib.kasuga.core.resource.pack.ApprovedResourcePacks approvedPacks = new lib.kasuga.core.resource.pack.ApprovedResourcePacks();
    private final ConcurrentHashMap<String, lib.kasuga.core.resource.lifecycle.ResourceCoordinator> resourceScopes = new ConcurrentHashMap<>();
    private boolean closed;

    public void onServerReloading(MinecraftServer server) {
        ScopedResourceManager scopedResourceManager = managers.get(server);
        if(scopedResourceManager != null) {
            scopedResourceManager.reload(server.getResourceManager());
        }
    }

    @Inject() @Named("forgeEventBus")
    IEventBus eventBus;

    @Inject @Named("modEventBus")
    IEventBus modEventBus;

    @PostConstruct()
    public void init() {
        eventBus.addListener(this::onServerStarting);
        eventBus.addListener(this::onServerStopping);
        modEventBus.addListener(this::onClientSetup);
        modEventBus.addListener(approvedPacks::onAddPackFinders);
        eventBus.addListener((GameShuttingDownEvent event) -> close());
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceManager manager = minecraft.getResourceManager();
        ScopedResourceManager srm = new ScopedResourceManager(manager);
        client = srm;
        if(manager instanceof ReloadableResourceManager reloadable) {
            reloadable.registerReloadListenerIfNotPresent(srm);
        }
        for (ScopedResourceManagerConsumer consumer : this.consumers) {
            consumer.onResourceManagerAdded(null, client);
        }
    }

    private void onServerStarting(ServerStartingEvent event) {
        managers.computeIfAbsent(
                event.getServer(),
                rm -> {
                    ScopedResourceManager srm = new ScopedResourceManager(rm.getResourceManager());
                    if(rm.getResourceManager() instanceof ReloadableResourceManager reloadable) {
                        reloadable.registerReloadListenerIfNotPresent(srm);
                    }
                    return srm;
                }
        );
        for (ScopedResourceManagerConsumer consumer : this.consumers) {
            consumer.onResourceManagerAdded(event.getServer(), managers.get(event.getServer()));
        }
    }

    private void onServerStopping(ServerStoppingEvent event) {
        ScopedResourceManager removed = managers.remove(event.getServer());
        try {
            if (removed != null) notifyRemoved(event.getServer(), removed);
        } finally { closeManagedResources(event.getServer()); }
    }

    public MountTableVfs getUnifiedVfs() {
        return unifiedVfs;
    }

    public lib.kasuga.core.resource.pack.ApprovedResourcePacks getApprovedPacks() { return approvedPacks; }

    public lib.kasuga.core.resource.lifecycle.ResourceCoordinator createServerResourceScope(MinecraftServer server, String id) {
        java.util.Objects.requireNonNull(server, "server");
        return createResourceScope(id, closeable -> registerManagedCloseable(server, closeable));
    }

    public lib.kasuga.core.resource.lifecycle.ResourceCoordinator createClientResourceScope(String id) {
        return createResourceScope(id, this::registerClientManagedCloseable);
    }

    public lib.kasuga.core.resource.lifecycle.ResourceCoordinator createProcessResourceScope(String id) {
        return createResourceScope(id, this::registerManagedCloseable);
    }

    private synchronized lib.kasuga.core.resource.lifecycle.ResourceCoordinator createResourceScope(String id, java.util.function.Consumer<AutoCloseable> owner) {
        if (closed) throw new IllegalStateException("Resource system closed");
        if (id == null || id.isBlank()) throw new IllegalArgumentException("scope ID required");
        var coordinator = new lib.kasuga.core.resource.lifecycle.ResourceCoordinator(id);
        coordinator.register(new lib.kasuga.core.resource.pack.MinecraftResourceEntryPoint());
        resourceScopes.compute(id, (key, previous) -> {
            if (previous != null && !previous.isClosed()) throw new IllegalArgumentException("Duplicate resource scope: " + id);
            return coordinator;
        });
        owner.accept(() -> { try { coordinator.close(); } finally { resourceScopes.remove(id, coordinator); } });
        return coordinator;
    }

    /**
     * Registers a VFS mount or {@link lib.kasuga.core.vfs.VfsWatchService} to be closed cleanly
     * when the server shuts down.
     */
    public void registerManagedCloseable(AutoCloseable closeable) {
        registerManagedCloseable(null, closeable);
    }

    public void registerManagedCloseable(@Nullable MinecraftServer server, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        if (server == null) {
            managedCloseables.addIfAbsent(closeable);
        } else {
            serverManagedCloseables
                    .computeIfAbsent(server, ignored -> new CopyOnWriteArrayList<>())
                    .addIfAbsent(closeable);
        }
    }

    public void unregisterManagedCloseable(AutoCloseable closeable) {
        unregisterManagedCloseable(null, closeable);
    }

    public void unregisterManagedCloseable(@Nullable MinecraftServer server, AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        if (server == null) {
            managedCloseables.remove(closeable);
        } else {
            CopyOnWriteArrayList<AutoCloseable> list = serverManagedCloseables.get(server);
            if (list != null) {
                list.remove(closeable);
            }
        }
    }

    public void registerClientManagedCloseable(AutoCloseable closeable) {
        if (closeable != null) {
            clientManagedCloseables.addIfAbsent(closeable);
        }
    }

    public void unregisterClientManagedCloseable(AutoCloseable closeable) {
        if (closeable != null) {
            clientManagedCloseables.remove(closeable);
        }
    }

    public VfsSubscription mountScoped(
            @Nullable MinecraftServer server,
            VfsPath mountPoint,
            VirtualFileSystem backend,
            boolean closeBackendOnUnmount
    ) {
        VfsSubscription subscription = replaceUnifiedMount(mountPoint, backend, closeBackendOnUnmount);
        registerManagedCloseable(server, subscription);
        return subscription;
    }

    public VfsSubscription mountClientScoped(
            VfsPath mountPoint,
            VirtualFileSystem backend,
            boolean closeBackendOnUnmount
    ) {
        VfsSubscription subscription = replaceUnifiedMount(mountPoint, backend, closeBackendOnUnmount);
        registerClientManagedCloseable(subscription);
        return subscription;
    }

    private VfsSubscription replaceUnifiedMount(
            VfsPath mountPoint,
            VirtualFileSystem backend,
            boolean closeBackendOnUnmount
    ) {
        if (unifiedVfs.mounts().containsKey(mountPoint)) {
            unifiedVfs.unmount(mountPoint);
        }
        return unifiedVfs.mount(mountPoint, backend, closeBackendOnUnmount);
    }

    public void closeManagedResources(@Nullable MinecraftServer server) {
        if (server != null) {
            closeAndClear(serverManagedCloseables.remove(server));
        } else {
            closeAndClear(managedCloseables);
        }
    }

    public void closeManagedResources() {
        try { approvedPacks.close(); }
        finally {
            closeAndClear(managedCloseables);
            for (MinecraftServer key : new ArrayList<>(serverManagedCloseables.keySet())) {
                closeAndClear(serverManagedCloseables.remove(key));
            }
        }
    }

    /** Called by GameShuttingDownEvent while the client GL context is still valid. Never joins script threads. */
    @Override public void close() {
        synchronized (this) { if (closed) return; closed = true; }
        if (client != null) { notifyRemoved(null, client); client = null; }
        for (var entry : new ArrayList<>(managers.entrySet())) notifyRemoved(entry.getKey(), entry.getValue());
        managers.clear();
        try { closeClientManagedResources(); }
        finally {
            try { closeManagedResources(); }
            finally { try { unifiedVfs.close(); } catch (java.io.IOException e) { LOGGER.warn("Unified VFS shutdown failed", e); } }
        }
    }

    private void notifyRemoved(@Nullable MinecraftServer server, ScopedResourceManager manager) {
        for (ScopedResourceManagerConsumer consumer : this.consumers) {
            try { consumer.onResourceManagerRemoved(server, manager); }
            catch (RuntimeException e) { LOGGER.warn("Resource consumer shutdown failed", e); }
        }
    }

    public void closeClientManagedResources() {
        closeAndClear(clientManagedCloseables);
    }

    private static void closeAndClear(@Nullable CopyOnWriteArrayList<AutoCloseable> list) {
        if (list == null) {
            return;
        }
        for (AutoCloseable closeable : list) {
            try {
                closeable.close();
            } catch (Exception e) {
                LOGGER.warn("Error closing managed VFS resource during shutdown", e);
            }
        }
        list.clear();
    }

    public ScopedResourceManager getServerManager(MinecraftServer server) {
        if(server == null) return client;
        return managers.get(server);
    }

    public ScopedResourceManager getManager(Level level) {
        if(level.isClientSide) {
            return getServerManager(null);
        } else if(level instanceof ServerLevel serverLevel) {
            return getServerManager(serverLevel.getServer());
        }
        throw new IllegalStateException("Unknown level: " + level);
    }

    public List<ScopedResourceManagerConsumer> consumers = new ArrayList<>();

    public void registerConsumer(ScopedResourceManagerConsumer consumer) {
        consumers.add(consumer);
        if(this.client != null)
            consumer.onResourceManagerAdded(null, client);
        for (var entry : managers.entrySet()) {
            consumer.onResourceManagerAdded(entry.getKey(), entry.getValue());
        }
    }
}
