package lib.kasuga.rendering.models.mc.source.texture.bake;

import com.mojang.logging.LogUtils;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PbrBakeCoordinator {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PbrBakeCoordinator INSTANCE = new PbrBakeCoordinator();

    private final Map<String, PbrBakeState> states = new ConcurrentHashMap<>();
    private final Map<String, PbrBakeResult> memoryResults = new ConcurrentHashMap<>();
    private final Map<String, Object> keyLocks = new ConcurrentHashMap<>();
    private final Map<BufferedImage, ConcurrentMap<PbrBakeProfile, String>> memoizedCacheKeys =
            Collections.synchronizedMap(new WeakHashMap<>());
    private final Semaphore parallelBakes = new Semaphore(2);
    private final PbrBaker cpuBaker = new StylizedPbrBaker();
    private final GpuStylizedPbrBaker gpuBaker = new GpuStylizedPbrBaker();
    private final AtomicInteger gpuBakeCount = new AtomicInteger();
    private final AtomicInteger cpuBakeCount = new AtomicInteger();
    private final AtomicInteger cacheHitCount = new AtomicInteger();
    private final AtomicInteger memoryHitCount = new AtomicInteger();
    private final AtomicInteger failureCount = new AtomicInteger();
    private final AtomicLong requestCount = new AtomicLong();
    private final AtomicLong cacheKeyComputations = new AtomicLong();
    private final AtomicLong cacheKeyMemoHits = new AtomicLong();
    private final AtomicLong cacheKeyNanos = new AtomicLong();
    private final AtomicLong diskReadNanos = new AtomicLong();
    private final AtomicLong gpuBakeNanos = new AtomicLong();
    private final AtomicLong cpuBakeNanos = new AtomicLong();
    private final AtomicLong diskWriteNanos = new AtomicLong();
    private volatile boolean gpuDisabled;

    private PbrBakeCoordinator() {}

    public static PbrBakeCoordinator getInstance() {
        return INSTANCE;
    }

    public PbrBakeResult getOrBake(BufferedImage source, PbrBakeProfile profile) {
        return getOrBake(source, profile, false);
    }

    public PbrBakeResult getOrBakeGpu(BufferedImage source, PbrBakeProfile profile) {
        if (!RenderSystem.isOnRenderThread()) {
            throw new IllegalStateException("GPU PBR baking must run on the render thread");
        }
        return getOrBake(source, profile, true);
    }

    /**
     * Loads an existing cache entry without baking. This method is safe to run
     * on resource-loader workers so PNG decoding does not stall the render
     * thread before a GPU bake batch.
     */
    public boolean loadFromCache(BufferedImage source, PbrBakeProfile profile) {
        requestCount.incrementAndGet();
        String key = cacheKey(source, profile);
        states.put(key, PbrBakeState.QUEUED);
        PbrBakeResult inMemory = memoryResults.get(key);
        if (inMemory != null) {
            memoryHitCount.incrementAndGet();
            states.put(key, PbrBakeState.READY);
            return true;
        }
        synchronized (keyLocks.computeIfAbsent(key, ignored -> new Object())) {
            inMemory = memoryResults.get(key);
            if (inMemory != null) {
                memoryHitCount.incrementAndGet();
                states.put(key, PbrBakeState.READY);
                return true;
            }
            try {
                return loadDiskCache(key) != null;
            } catch (IOException exception) {
                LOGGER.warn("Failed to read stylized PBR cache {}; it will be regenerated", key, exception);
                return false;
            }
        }
    }

    private PbrBakeResult getOrBake(BufferedImage source, PbrBakeProfile profile, boolean preferGpu) {
        requestCount.incrementAndGet();
        String key = cacheKey(source, profile);
        states.put(key, PbrBakeState.QUEUED);
        PbrBakeResult inMemory = memoryResults.get(key);
        if (inMemory != null) {
            memoryHitCount.incrementAndGet();
            states.put(key, PbrBakeState.READY);
            return inMemory;
        }

        synchronized (keyLocks.computeIfAbsent(key, ignored -> new Object())) {
            inMemory = memoryResults.get(key);
            if (inMemory != null) {
                memoryHitCount.incrementAndGet();
                states.put(key, PbrBakeState.READY);
                return inMemory;
            }

            try {
                PbrBakeResult diskCached = loadDiskCache(key);
                if (diskCached != null) return diskCached;

                states.put(key, PbrBakeState.BAKING);
                PbrBakeResult result = null;
                if (preferGpu && !gpuDisabled) {
                    try {
                        long gpuStart = System.nanoTime();
                        result = gpuBaker.bake(source, profile);
                        gpuBakeNanos.addAndGet(System.nanoTime() - gpuStart);
                        gpuBakeCount.incrementAndGet();
                    } catch (RuntimeException exception) {
                        gpuDisabled = true;
                        LOGGER.warn("GPU PBR baking is unavailable; falling back to the CPU reference baker", exception);
                    }
                }
                if (result == null) {
                    parallelBakes.acquire();
                    try {
                        long cpuStart = System.nanoTime();
                        result = cpuBaker.bake(source, profile);
                        cpuBakeNanos.addAndGet(System.nanoTime() - cpuStart);
                        cpuBakeCount.incrementAndGet();
                    } finally {
                        parallelBakes.release();
                    }
                }
                Path directory = cacheDirectory().resolve(key);
                Files.createDirectories(directory);
                long diskWriteStart = System.nanoTime();
                if (!ImageIO.write(result.normalMap(), "png", directory.resolve("normal.png").toFile())
                        || !ImageIO.write(result.specularMap(), "png", directory.resolve("specular.png").toFile())) {
                    throw new IOException("No PNG encoder is available for PBR cache output");
                }
                diskWriteNanos.addAndGet(System.nanoTime() - diskWriteStart);
                memoryResults.put(key, result);
                states.put(key, PbrBakeState.READY);
                return result;
            } catch (Exception exception) {
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
                failureCount.incrementAndGet();
                states.put(key, PbrBakeState.FAILED);
                LOGGER.warn("Failed to bake stylized PBR texture {}; using default PBR maps", key, exception);
                return null;
            }
        }
    }

    private PbrBakeResult loadDiskCache(String key) throws IOException {
        Path directory = cacheDirectory().resolve(key);
        Path normalPath = directory.resolve("normal.png");
        Path specularPath = directory.resolve("specular.png");
        if (!Files.isRegularFile(normalPath) || !Files.isRegularFile(specularPath)) return null;
        long diskReadStart = System.nanoTime();
        BufferedImage normal = ImageIO.read(normalPath.toFile());
        BufferedImage specular = ImageIO.read(specularPath.toFile());
        diskReadNanos.addAndGet(System.nanoTime() - diskReadStart);
        if (normal == null || specular == null) return null;
        PbrBakeResult cached = new PbrBakeResult(normal, specular);
        memoryResults.put(key, cached);
        cacheHitCount.incrementAndGet();
        states.put(key, PbrBakeState.READY);
        return cached;
    }

    public Map<String, PbrBakeState> states() {
        return Map.copyOf(states);
    }

    public PbrBakeStats stats() {
        return new PbrBakeStats(
                requestCount.get(), gpuBakeCount.get(), cpuBakeCount.get(), cacheHitCount.get(),
                memoryHitCount.get(), failureCount.get(), memoryResults.size(), cacheKeyComputations.get(),
                cacheKeyMemoHits.get(), cacheKeyNanos.get(), diskReadNanos.get(), gpuBakeNanos.get(),
                cpuBakeNanos.get(), diskWriteNanos.get(), gpuDisabled
        );
    }

    public void releaseMemoryResults() {
        memoryResults.clear();
        memoizedCacheKeys.clear();
    }

    public void clearCache() throws IOException {
        Path root = cacheDirectory();
        if (Files.exists(root)) {
            try (var paths = Files.walk(root)) {
                paths.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException exception) {
                        throw new RuntimeException(exception);
                    }
                });
            } catch (RuntimeException exception) {
                if (exception.getCause() instanceof IOException io) throw io;
                throw exception;
            }
        }
        memoryResults.clear();
        keyLocks.clear();
        states.clear();
        gpuBakeCount.set(0);
        cpuBakeCount.set(0);
        cacheHitCount.set(0);
        memoryHitCount.set(0);
        failureCount.set(0);
        requestCount.set(0);
        cacheKeyComputations.set(0);
        cacheKeyMemoHits.set(0);
        cacheKeyNanos.set(0);
        diskReadNanos.set(0);
        gpuBakeNanos.set(0);
        cpuBakeNanos.set(0);
        diskWriteNanos.set(0);
        gpuDisabled = false;
    }

    private Path cacheDirectory() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("cache").resolve("kasuga-pbr");
    }

    private String cacheKey(BufferedImage source, PbrBakeProfile profile) {
        ConcurrentMap<PbrBakeProfile, String> profileKeys;
        synchronized (memoizedCacheKeys) {
            profileKeys = memoizedCacheKeys.computeIfAbsent(source, ignored -> new ConcurrentHashMap<>());
        }
        String cached = profileKeys.get(profile);
        if (cached != null) {
            cacheKeyMemoHits.incrementAndGet();
            return cached;
        }
        return profileKeys.computeIfAbsent(profile, ignored -> {
            long start = System.nanoTime();
            String computed = computeCacheKey(source, profile);
            cacheKeyNanos.addAndGet(System.nanoTime() - start);
            cacheKeyComputations.incrementAndGet();
            return computed;
        });
    }

    private String computeCacheKey(BufferedImage source, PbrBakeProfile profile) {
        return PbrCacheKey.compute(source, profile.cacheDescriptor());
    }

    public record PbrBakeStats(
            long requests,
            int gpuBakes,
            int cpuBakes,
            int cacheHits,
            int memoryHits,
            int failures,
            int liveMemoryResults,
            long cacheKeyComputations,
            long cacheKeyMemoHits,
            long cacheKeyNanos,
            long diskReadNanos,
            long gpuBakeNanos,
            long cpuBakeNanos,
            long diskWriteNanos,
            boolean gpuDisabled
    ) {}
}
