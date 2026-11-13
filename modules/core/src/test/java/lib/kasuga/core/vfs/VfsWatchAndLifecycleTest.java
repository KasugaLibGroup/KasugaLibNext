package lib.kasuga.core.vfs;

import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.pack.HierarchicalScopedPackResources;
import lib.kasuga.core.resource.pack.ScopedPackResources;
import lib.kasuga.core.resource.pack.VfsPackResourcesBridge;
import lib.kasuga.core.resource.pack.VfsScopedPackResourcesAdapter;
import lib.kasuga.core.vfs.backend.DirectoryVfs;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies dual-origin update hooks (INTERNAL_WRITE & EXTERNAL_DISK), lock-free callback re-entrancy,
 * subscription idempotency, VfsWatchService cleanup, and ScopedPackResources/PackResources bridges.
 */
class VfsWatchAndLifecycleTest {

    @Test
    void internalWritesAndExternalDiskEditsEmitDistinctHookOriginsWithoutFalsePositives(@TempDir Path tempDir) throws IOException {
        Path root = tempDir.resolve("watched");
        try (DirectoryVfs vfs = new DirectoryVfs("watched-dir", root)) {
            List<VfsChangeEvent> modelEvents = new CopyOnWriteArrayList<>();
            List<VfsChangeEvent> allEvents = new CopyOnWriteArrayList<>();

            VfsSubscription modelsSub = vfs.watch(VfsPath.of("models"), modelEvents::add);
            VfsSubscription allSub = vfs.watchAll(allEvents::add);

            // Internal write to textures should notify allSub only, not modelsSub
            vfs.writeString(VfsPath.of("textures/a.png"), "tex1");
            assertEquals(0, modelEvents.size());
            assertEquals(1, allEvents.size());
            assertEquals(VfsChangeEvent.ChangeOrigin.INTERNAL_WRITE, allEvents.get(0).origin());

            // Internal write to models notifies both
            vfs.writeString(VfsPath.of("models/loco.json"), "{\"v\":1}");
            assertEquals(1, modelEvents.size());
            assertEquals(2, allEvents.size());

            // Internal writes already update the external baseline, so polling right after must emit nothing
            assertTrue(vfs.pollExternalChanges().isEmpty());

            // External disk edits bypass VFS: create, overwrite (even same length!), and delete
            Path extNew = root.resolve("models/bogey.json");
            Files.writeString(extNew, "{\"v\":1}");
            Path extMod = root.resolve("models/loco.json");
            Files.writeString(extMod, "{\"v\":2}");
            Files.delete(root.resolve("textures/a.png"));

            Optional<VfsChangeEvent> polled = vfs.pollExternalChanges();
            assertTrue(polled.isPresent());
            assertEquals(VfsChangeEvent.ChangeOrigin.EXTERNAL_DISK, polled.get().origin());
            assertEquals(2, modelEvents.size());
            assertEquals(3, allEvents.size());

            // Second poll with no new disk modifications reports empty
            assertTrue(vfs.pollExternalChanges().isEmpty());

            // Closing subscription stops future notifications and is idempotent
            modelsSub.close();
            modelsSub.close();
            assertTrue(modelsSub.isClosed());

            vfs.writeString(VfsPath.of("models/after_unsub.json"), "{}");
            assertEquals(2, modelEvents.size());
            assertEquals(4, allEvents.size());
            allSub.close();
        }
    }

    @Test
    void callbacksRunOutsideInternalLockSoReEntrantReadsWritesAndUnsubscribeDoNotDeadlock(@TempDir Path tempDir) throws Exception {
        try (DirectoryVfs vfs = new DirectoryVfs("reentrant", tempDir.resolve("reentrant"))) {
            AtomicReference<VfsSubscription> selfSub = new AtomicReference<>();
            CountDownLatch callbackEntered = new CountDownLatch(1);
            CountDownLatch concurrentWriteDone = new CountDownLatch(1);
            AtomicInteger selfNotifications = new AtomicInteger();
            AtomicInteger postCloseWriteEvents = new AtomicInteger();

            VfsSubscription observerSub = vfs.watchAll(event -> {
                for (VfsChangeEvent.EntryChange change : event.changes()) {
                    if ("side-effect.txt".equals(change.path().asString())) {
                        postCloseWriteEvents.incrementAndGet();
                    }
                }
            });

            VfsSubscription sub = vfs.watchAll(event -> {
                if ("trigger.txt".equals(event.changes().get(0).path().asString())) {
                    selfNotifications.incrementAndGet();
                    callbackEntered.countDown();
                    // Wait while inside callback for another thread to acquire VFS write lock:
                    // only possible because VFS releases stateLock before dispatching callbacks.
                    try {
                        assertTrue(concurrentWriteDone.await(3, TimeUnit.SECONDS), "Concurrent write deadlocked while callback ran");
                        assertEquals("v1", vfs.readString(VfsPath.of("trigger.txt")));
                        selfSub.get().close();
                        // Subsequent internal write after self-unsubscribe must not invoke this listener again
                        vfs.writeString(VfsPath.of("side-effect.txt"), "from-callback");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                } else {
                    selfNotifications.incrementAndGet();
                }
            });
            selfSub.set(sub);

            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                var future = executor.submit(() -> {
                    assertTrue(callbackEntered.await(3, TimeUnit.SECONDS));
                    vfs.writeString(VfsPath.of("concurrent.txt"), "ok");
                    concurrentWriteDone.countDown();
                    return null;
                });

                vfs.writeString(VfsPath.of("trigger.txt"), "v1");
                future.get(5, TimeUnit.SECONDS);

                // selfNotifications: trigger.txt + concurrent.txt (before selfSub.close()), NOT side-effect.txt (after close)
                assertEquals(2, selfNotifications.get());
                assertEquals(1, postCloseWriteEvents.get());
                assertEquals("from-callback", vfs.readString(VfsPath.of("side-effect.txt")));
                assertEquals("ok", vfs.readString(VfsPath.of("concurrent.txt")));
                observerSub.close();
            } finally {
                executor.shutdownNow();
            }
        }
    }

    @Test
    void watchServiceDetectsExternalChangesInBackgroundAndClosesCleanly(@TempDir Path tempDir) throws Exception {
        Path root = tempDir.resolve("bg-watch");
        CountDownLatch externalSeen = new CountDownLatch(1);
        try (
                DirectoryVfs vfs = new DirectoryVfs("bg", root);
                VfsWatchService watchService = new VfsWatchService(25L)
        ) {
            vfs.watchAll(event -> {
                if (event.origin() == VfsChangeEvent.ChangeOrigin.EXTERNAL_DISK) {
                    externalSeen.countDown();
                }
            });
            watchService.register(vfs);
            assertEquals(1, watchService.watchedCount());

            Files.writeString(root.resolve("external.txt"), "hello-watcher");
            assertTrue(externalSeen.await(3, TimeUnit.SECONDS), "Background watcher should observe external edit");

            watchService.close();
            assertTrue(watchService.isClosed());
            assertEquals(0, watchService.watchedCount());
        }
    }

    @Test
    void bridgesExposeVfsThroughScopedPackResourcesAndMinecraftPackResources() throws IOException {
        try (InMemoryVfs vfs = new InMemoryVfs("kuayue-vfs")) {
            vfs.writeString(VfsPath.of("pack.mcmeta"), "{\"pack\":{\"description\":\"vfs\",\"pack_format\":34}}");
            vfs.writeString(VfsPath.of("assets/kuayue/models/coach.json"), "{\"coach\":true}");
            vfs.writeString(VfsPath.of("data/kuayue/recipes/rail.json"), "{\"rail\":true}");

            PackLocationInfo info = new PackLocationInfo("kuayue_vfs_pack", Component.literal("KuaYue VFS"), PackSource.BUILT_IN, Optional.empty());
            VfsPackResourcesBridge bridge = new VfsPackResourcesBridge(vfs, info);

            assertEquals(Set.of("kuayue"), bridge.getNamespaces(PackType.CLIENT_RESOURCES));
            assertEquals(Set.of("kuayue"), bridge.getNamespaces(PackType.SERVER_DATA));

            IoSupplier<InputStream> rootRes = bridge.getRootResource("pack.mcmeta");
            assertNotNull(rootRes);
            try (InputStream in = rootRes.get()) {
                assertTrue(new String(in.readAllBytes(), StandardCharsets.UTF_8).contains("vfs"));
            }

            ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("kuayue", "models/coach.json");
            IoSupplier<InputStream> clientRes = bridge.getResource(PackType.CLIENT_RESOURCES, rl);
            assertNotNull(clientRes);
            try (InputStream in = clientRes.get()) {
                assertEquals("{\"coach\":true}", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }

            // Verify ScopedResourceManager.adapt recognizes VfsPackResourcesBridge
            ResourceManager proxyRm = (ResourceManager) Proxy.newProxyInstance(
                    ResourceManager.class.getClassLoader(),
                    new Class<?>[]{ResourceManager.class},
                    (proxy, method, args) -> {
                        if ("listPacks".equals(method.getName())) {
                            return Stream.of(bridge);
                        }
                        throw new UnsupportedOperationException(method.getName());
                    }
            );

            ScopedResourceManager scopedRm = new ScopedResourceManager(proxyRm);
            assertEquals(1, scopedRm.getPackResources().size());
            ScopedPackResources adapted = scopedRm.getPackResources().get(0);
            assertTrue(adapted instanceof VfsScopedPackResourcesAdapter);
            assertTrue(adapted instanceof HierarchicalScopedPackResources);

            HierarchicalScopedPackResources hierarchical = (HierarchicalScopedPackResources) adapted;
            assertEquals(List.of("coach.json"), hierarchical.list("assets/kuayue", "models"));
            assertTrue(adapted.exists("data/kuayue", "recipes/rail.json"));
            assertFalse(adapted.isDirectory("data/kuayue", "recipes/rail.json"));
            assertTrue(adapted.isRegularFile("data/kuayue", "recipes/rail.json"));
        }
    }
}
