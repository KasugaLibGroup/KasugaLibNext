package lib.kasuga.rendering.effect.shader;

import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/** Bounded CPU-side preparation pool; OpenGL work never runs here. */
public final class ShaderPreparationScheduler {
    public static final int AUTO_WORKERS = 0;
    public static final int MAX_AUTOMATIC_WORKERS = 4;
    public static final int QUEUE_CAPACITY = 128;
    public static final String WORKER_COUNT_PROPERTY = "kasuga.shaderPreparationWorkers";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object CONFIGURATION_LOCK = new Object();
    private static final int AVAILABLE_PROCESSORS = Math.max(1, Runtime.getRuntime().availableProcessors());
    private static volatile int requestedWorkers = AUTO_WORKERS;
    private static final int INITIAL_WORKERS = resolveWorkerCount(AUTO_WORKERS, AVAILABLE_PROCESSORS);

    private static final AtomicLong COMPLETED = new AtomicLong();
    private static final AtomicLong CANCELLED = new AtomicLong();
    private static final AtomicLong REJECTED = new AtomicLong();
    private static final AtomicLong FAILED = new AtomicLong();
    private static final AtomicLong TOTAL_NANOS = new AtomicLong();
    private static final AtomicLong MAX_NANOS = new AtomicLong();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            INITIAL_WORKERS,
            INITIAL_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY),
            new PreparationThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy()
    );

    private ShaderPreparationScheduler() {}

    /**
     * Reconfigures source-preparation concurrency. Zero selects the CPU-aware automatic policy;
     * an explicit value is capped at the number of processors visible to the JVM.
     */
    public static void configureWorkers(int value) {
        if (value < AUTO_WORKERS) {
            throw new IllegalArgumentException("Shader preparation workers cannot be negative");
        }
        int resolved = resolveWorkerCount(value, AVAILABLE_PROCESSORS);
        synchronized (CONFIGURATION_LOCK) {
            int previous = EXECUTOR.getCorePoolSize();
            if (resolved > previous) {
                EXECUTOR.setMaximumPoolSize(resolved);
                EXECUTOR.setCorePoolSize(resolved);
            } else if (resolved < previous) {
                EXECUTOR.setCorePoolSize(resolved);
                EXECUTOR.setMaximumPoolSize(resolved);
            }
            requestedWorkers = value;
        }
    }

    /** Reads {@value #WORKER_COUNT_PROPERTY}; missing or blank values select automatic sizing. */
    public static void configureFromSystemProperty() {
        String configured = System.getProperty(WORKER_COUNT_PROPERTY);
        if (configured == null || configured.isBlank()) {
            configureWorkers(AUTO_WORKERS);
            return;
        }
        try {
            configureWorkers(Integer.parseInt(configured.trim()));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Invalid -D{}={}; using automatic shader preparation workers",
                    WORKER_COUNT_PROPERTY, configured);
            configureWorkers(AUTO_WORKERS);
        }
    }

    /** Starts configured workers before the first generated shader is registered. */
    public static int prestartWorkers() {
        return EXECUTOR.prestartAllCoreThreads();
    }

    public static int workerCount() {
        return EXECUTOR.getCorePoolSize();
    }

    public static int requestedWorkerCount() {
        return requestedWorkers;
    }

    static <T> CompletableFuture<T> submit(
            ResourceLocation owner,
            ResourceLocation shaderId,
            Supplier<T> preparation
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(shaderId, "shaderId");
        Objects.requireNonNull(preparation, "preparation");
        PreparationFuture<T> result = new PreparationFuture<>();
        Runnable task = () -> {
            long started = 0L;
            try {
                if (result.isCancelled()) return;
                started = System.nanoTime();
                result.complete(preparation.get());
            } catch (Throwable throwable) {
                FAILED.incrementAndGet();
                result.completeExceptionally(new IllegalStateException(
                        "Shader preparation failed for " + shaderId + " owned by " + owner,
                        throwable
                ));
            } finally {
                result.detach();
                if (started != 0L) {
                    long elapsed = Math.max(0L, System.nanoTime() - started);
                    COMPLETED.incrementAndGet();
                    TOTAL_NANOS.addAndGet(elapsed);
                    MAX_NANOS.accumulateAndGet(elapsed, Math::max);
                }
            }
        };
        result.attach(task);
        try {
            EXECUTOR.execute(task);
        } catch (RejectedExecutionException exception) {
            result.detach();
            REJECTED.incrementAndGet();
            result.completeExceptionally(new IllegalStateException(
                    "Shader preparation queue is full for " + shaderId + " owned by " + owner,
                    exception
            ));
        }
        return result;
    }

    public static ShaderPreparationStats stats() {
        return new ShaderPreparationStats(
                EXECUTOR.getCorePoolSize(), QUEUE_CAPACITY,
                EXECUTOR.getActiveCount(), EXECUTOR.getQueue().size(),
                COMPLETED.get(), CANCELLED.get(), REJECTED.get(), FAILED.get(),
                TOTAL_NANOS.get(), MAX_NANOS.get(),
                requestedWorkers, AVAILABLE_PROCESSORS
        );
    }

    static int resolveWorkerCount(int requested, int availableProcessors) {
        if (requested < AUTO_WORKERS) {
            throw new IllegalArgumentException("Shader preparation workers cannot be negative");
        }
        int available = Math.max(1, availableProcessors);
        if (requested > AUTO_WORKERS) return Math.min(requested, available);
        int half = Math.max(1, (available + 1) / 2);
        return Math.min(half, MAX_AUTOMATIC_WORKERS);
    }

    private static final class PreparationFuture<T> extends CompletableFuture<T> {
        private volatile Runnable task;

        private void attach(Runnable value) {
            task = value;
        }

        private void detach() {
            task = null;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(false);
            if (!cancelled) return false;
            CANCELLED.incrementAndGet();
            Runnable queued = task;
            if (queued != null) EXECUTOR.remove(queued);
            return true;
        }
    }

    private static final class PreparationThreadFactory implements ThreadFactory {
        private final AtomicInteger nextId = new AtomicInteger();

        @Override
        public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "Kasuga-Shader-Prepare-" + nextId.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Thread.NORM_PRIORITY - 1);
            return thread;
        }
    }
}
