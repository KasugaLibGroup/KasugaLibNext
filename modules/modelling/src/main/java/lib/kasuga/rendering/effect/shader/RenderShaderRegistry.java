package lib.kasuga.rendering.effect.shader;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import lib.kasuga.rendering.effect.DuplicatePolicy;
import lib.kasuga.shader.ShaderProgram;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Registry for reload-safe resource or imperative Java shaders. */
public final class RenderShaderRegistry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object LOCK = new Object();
    private static final Map<ResourceLocation, Entry> ENTRIES = new LinkedHashMap<>();

    private static ResourceProvider currentResources;
    private static long compileAttempts;
    private static long successfulCompiles;
    private static long failedCompiles;
    private static long latePreloads;
    private static long totalCompileNanos;
    private static long lastReloadNanos;

    private RenderShaderRegistry() {}

    public static ShaderRegistration register(
            ResourceLocation owner,
            RenderShaderDescriptor descriptor,
            DuplicatePolicy duplicatePolicy,
            ShaderLoadListener listener
    ) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(duplicatePolicy, "duplicatePolicy");
        Objects.requireNonNull(listener, "listener");
        Entry entry;
        Entry replaced;
        List<CompletableFuture<ShaderStatus>> replacedWaiters = List.of();
        synchronized (LOCK) {
            replaced = ENTRIES.get(descriptor.id());
            if (replaced != null && duplicatePolicy == DuplicatePolicy.FAIL) {
                throw new IllegalStateException(
                        "Shader ID " + descriptor.id() + " is already owned by " + replaced.owner
                );
            }
            entry = new Entry(
                    owner, descriptor,
                    new RenderShaderHandle(descriptor.id(), descriptor.parameterSchema()), listener
            );
            ENTRIES.put(descriptor.id(), entry);
            if (replaced != null) {
                replacedWaiters = invalidateLocked(replaced, ShaderLoadState.CLOSED);
            }
            publishStatusLocked(entry);
        }
        if (replaced != null) {
            disposeOwned(replaced);
            completeWaitersFailure(replacedWaiters, unavailable(replaced));
            notifyInvalidated(replaced);
        }
        observePreparation(entry);
        scheduleAutomaticPreload(entry);
        return new RegistrationImpl(entry);
    }

    public static ShaderRegistration register(
            ResourceLocation owner,
            RenderShaderDescriptor descriptor,
            DuplicatePolicy duplicatePolicy
    ) {
        return register(owner, descriptor, duplicatePolicy, ShaderLoadListener.NONE);
    }

    public static ShaderRegistration register(
            ResourceLocation owner,
            ShaderProgram program,
            DuplicatePolicy duplicatePolicy,
            ShaderLoadListener listener
    ) {
        return register(owner, RenderShaderDescriptor.generated(program), duplicatePolicy, listener);
    }

    public static Optional<RenderShaderHandle> get(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(id);
            return entry == null ? Optional.empty() : Optional.of(entry.handle);
        }
    }

    public static boolean isRegistered(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        synchronized (LOCK) {
            return ENTRIES.containsKey(id);
        }
    }

    public static List<ResourceLocation> registeredIds() {
        synchronized (LOCK) {
            return ENTRIES.keySet().stream().sorted().toList();
        }
    }

    /** Queues the currently active shader with this ID. Prefer registration/handle preload controls. */
    public static boolean preload(ResourceLocation id) {
        Objects.requireNonNull(id, "id");
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(id);
        }
        return entry != null && scheduleLatePreload(entry, false);
    }

    static boolean preload(RenderShaderHandle handle) {
        Entry entry;
        synchronized (LOCK) {
            entry = ENTRIES.get(handle.id());
            if (entry == null || entry.handle != handle) return false;
        }
        return scheduleLatePreload(entry, false);
    }

    static ShaderStatus status(RenderShaderHandle handle) {
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(handle.id());
            if (entry == null || entry.handle != handle) return handle.cachedStatus();
            ShaderStatus status = statusLocked(entry);
            handle.updateStatus(status);
            return status;
        }
    }

    static CompletableFuture<ShaderStatus> whenReady(RenderShaderHandle handle) {
        Objects.requireNonNull(handle, "handle");
        synchronized (LOCK) {
            Entry entry = ENTRIES.get(handle.id());
            if (entry == null || entry.handle != handle) {
                return CompletableFuture.failedFuture(new IllegalStateException(
                        "Shader registration is no longer active: " + handle.id()
                ));
            }
            ShaderStatus status = statusLocked(entry);
            handle.updateStatus(status);
            if (status.state() == ShaderLoadState.READY) {
                return CompletableFuture.completedFuture(status);
            }
            if (status.state() == ShaderLoadState.FAILED || status.state() == ShaderLoadState.CLOSED) {
                return CompletableFuture.failedFuture(unavailable(entry));
            }
            CompletableFuture<ShaderStatus> waiter = new CompletableFuture<>();
            entry.readyWaiters.add(waiter);
            waiter.whenComplete((ignored, failure) -> {
                if (!waiter.isCancelled()) return;
                synchronized (LOCK) {
                    entry.readyWaiters.remove(waiter);
                }
            });
            return waiter;
        }
    }

    /** Administrative diagnostics action that retries every unavailable shader. */
    @ApiStatus.Internal
    public static int preloadPending() {
        List<Entry> pending;
        synchronized (LOCK) {
            pending = ENTRIES.values().stream()
                    .filter(entry -> entry.state == ShaderLoadState.REGISTERED
                            || entry.state == ShaderLoadState.FAILED)
                    .toList();
        }
        int queued = 0;
        for (Entry entry : pending) if (scheduleLatePreload(entry, false)) queued++;
        return queued;
    }

    public static List<ShaderSnapshot> snapshots() {
        synchronized (LOCK) {
            return ENTRIES.values().stream()
                    .map(entry -> {
                        ShaderStatus status = statusLocked(entry);
                        entry.handle.updateStatus(status);
                        return new ShaderSnapshot(
                                entry.owner, entry.descriptor.id(), entry.descriptor.sourceKind(),
                                status.state(), status.origin(), entry.descriptor.preloadPolicy(),
                                entry.descriptor.preloadPriority(), entry.descriptor.failurePolicy(),
                                status.queuePosition(), status.queueWaitNanos(), status.generation(),
                                status.preparationNanos(), status.translationCacheHit(),
                                status.compileNanos(), status.error()
                        );
                    })
                    .sorted(Comparator.comparing(snapshot -> snapshot.id().toString()))
                    .toList();
        }
    }

    public static PreloadStats preloadStats() {
        GeneratedShaderPreloader.Stats generated = GeneratedShaderPreloader.stats();
        ShaderPreloadScheduler.Stats scheduler = ShaderPreloadScheduler.stats();
        synchronized (LOCK) {
            long ready = ENTRIES.values().stream().filter(entry -> entry.handle.isReady()).count();
            long queued = ENTRIES.values().stream()
                    .filter(entry -> entry.state == ShaderLoadState.PREPARING
                            || entry.state == ShaderLoadState.QUEUED
                            || entry.state == ShaderLoadState.COMPILING)
                    .count();
            long failed = ENTRIES.values().stream()
                    .filter(entry -> entry.state == ShaderLoadState.FAILED).count();
            return new PreloadStats(
                    ENTRIES.size(), ready, queued, failed,
                    compileAttempts, successfulCompiles, failedCompiles, latePreloads,
                    totalCompileNanos, lastReloadNanos,
                    generated.cachedPrograms(), generated.translations(), generated.cacheHits(),
                    generated.translationNanos(), ShaderPreparationScheduler.stats(),
                    ShaderPreloadScheduler.settings(), scheduler
            );
        }
    }

    /** Called from the modelling module's RegisterShadersEvent bridge. */
    @ApiStatus.Internal
    public static void registerShaders(RegisterShadersEvent event) {
        Objects.requireNonNull(event, "event");
        List<Entry> snapshot;
        synchronized (LOCK) {
            currentResources = event.getResourceProvider();
            snapshot = new ArrayList<>(ENTRIES.values());
        }
        snapshot.sort(Comparator.comparing(entry -> entry.descriptor.id()));

        long reloadStarted = System.nanoTime();
        for (Entry entry : snapshot) {
            if (entry.descriptor.preloadPolicy() != ShaderPreloadPolicy.EAGER) continue;
            long request;
            boolean invalidated;
            synchronized (LOCK) {
                if (ENTRIES.get(entry.descriptor.id()) != entry) continue;
                cancelQueuedLocked(entry);
                invalidated = entry.handle.isReady();
                entry.handle.invalidate();
                request = ++entry.request;
                entry.state = entry.preparation.isDone()
                        ? ShaderLoadState.COMPILING
                        : ShaderLoadState.PREPARING;
                entry.origin = ShaderLoadOrigin.RESOURCE_RELOAD;
                entry.error = null;
                publishStatusLocked(entry);
            }
            if (invalidated) notifyInvalidated(entry);

            RenderShaderDescriptor.PreparedSource prepared;
            long started = 0L;
            ShaderInstance compiled = null;
            try {
                prepared = awaitPreparation(entry);
                synchronized (LOCK) {
                    if (!isCurrentRequest(entry, request)) continue;
                    entry.state = ShaderLoadState.COMPILING;
                    compileAttempts++;
                    publishStatusLocked(entry);
                }
                started = System.nanoTime();
                compiled = entry.descriptor.create(event.getResourceProvider(), prepared);
                entry.handle.validateParameters(compiled);
                long elapsed = System.nanoTime() - started;
                event.registerShader(compiled, loaded -> installIfCurrent(
                        entry, request, loaded, false, elapsed, ShaderLoadOrigin.RESOURCE_RELOAD
                ));
                recordSuccessfulCompile(entry, elapsed);
                compiled = null;
            } catch (IOException | RuntimeException exception) {
                closeShader(compiled);
                boolean compileAttempted = started != 0L;
                long elapsed = compileAttempted ? System.nanoTime() - started : 0L;
                markFailure(entry, request, elapsed, compileAttempted, exception);
                if (entry.descriptor.failurePolicy() == ShaderFailurePolicy.FAIL_RELOAD) {
                    throw new IllegalStateException("Failed to load shader '" + entry.descriptor.id() + "'", exception);
                }
                LOGGER.error("Shader reload disabled optional pipeline {}", entry.descriptor.id(), exception);
            }
        }

        for (Entry entry : snapshot) {
            if (entry.descriptor.preloadPolicy() == ShaderPreloadPolicy.DEFERRED) {
                rescheduleDeferred(entry);
            }
        }
        synchronized (LOCK) {
            lastReloadNanos = System.nanoTime() - reloadStarted;
        }
    }

    private static void scheduleAutomaticPreload(Entry entry) {
        if (entry.descriptor.preloadPolicy() != ShaderPreloadPolicy.MANUAL) {
            scheduleLatePreload(entry, false);
        }
    }

    private static void rescheduleDeferred(Entry entry) {
        boolean invalidated;
        synchronized (LOCK) {
            if (ENTRIES.get(entry.descriptor.id()) != entry) return;
            cancelQueuedLocked(entry);
            invalidated = entry.handle.isReady();
            entry.handle.invalidate();
            entry.request++;
            entry.state = ShaderLoadState.REGISTERED;
            publishStatusLocked(entry);
        }
        if (invalidated) notifyInvalidated(entry);
        scheduleLatePreload(entry, true);
    }

    private static boolean scheduleLatePreload(Entry entry, boolean refreshReady) {
        ResourceProvider resources;
        long request;
        synchronized (LOCK) {
            if (ENTRIES.get(entry.descriptor.id()) != entry
                    || (entry.state != ShaderLoadState.REGISTERED
                    && entry.state != ShaderLoadState.FAILED
                    && !(refreshReady && entry.state == ShaderLoadState.READY))) {
                return false;
            }
            resources = currentResources;
            if (resources == null) return false;
            if (!entry.preparation.isDone()) {
                if (entry.preloadAfterPreparation) return false;
                entry.preloadAfterPreparation = true;
                entry.state = ShaderLoadState.PREPARING;
                entry.origin = ShaderLoadOrigin.LATE_PRELOAD;
                entry.error = null;
                publishStatusLocked(entry);
                return true;
            }
            try {
                recordPreparationLocked(entry, entry.preparation.join());
            } catch (CompletionException exception) {
                entry.state = ShaderLoadState.FAILED;
                entry.error = conciseError(exception.getCause() == null ? exception : exception.getCause());
                publishStatusLocked(entry);
                return false;
            }
            request = ++entry.request;
            entry.state = ShaderLoadState.QUEUED;
            entry.origin = ShaderLoadOrigin.LATE_PRELOAD;
            entry.error = null;
            latePreloads++;
            entry.queuedJob = ShaderPreloadScheduler.enqueue(
                    entry.owner, entry.descriptor.id(), entry.descriptor.preloadPriority(),
                    () -> compileLate(entry, request, resources)
            );
            publishStatusLocked(entry);
        }
        return true;
    }

    private static void compileLate(Entry entry, long request, ResourceProvider resources) {
        synchronized (LOCK) {
            if (!isCurrentRequest(entry, request)) return;
            entry.lastQueueWaitNanos = ShaderPreloadScheduler.waitNanos(entry.queuedJob);
            entry.state = ShaderLoadState.COMPILING;
            compileAttempts++;
            publishStatusLocked(entry);
        }

        RenderShaderDescriptor.PreparedSource prepared;
        long started = System.nanoTime();
        ShaderInstance compiled = null;
        try {
            prepared = awaitPreparation(entry);
            compiled = entry.descriptor.create(resources, prepared);
            entry.handle.validateParameters(compiled);
            long elapsed = System.nanoTime() - started;
            recordSuccessfulCompile(entry, elapsed);
            installIfCurrent(
                    entry, request, compiled, true, elapsed, ShaderLoadOrigin.LATE_PRELOAD
            );
            compiled = null;
        } catch (IOException | RuntimeException exception) {
            closeShader(compiled);
            markFailure(entry, request, System.nanoTime() - started, true, exception);
            LOGGER.error("Late shader preload failed for {}", entry.descriptor.id(), exception);
        }
    }

    private static void recordSuccessfulCompile(Entry entry, long elapsed) {
        synchronized (LOCK) {
            totalCompileNanos += elapsed;
            successfulCompiles++;
            publishStatusLocked(entry);
        }
    }

    private static void observePreparation(Entry entry) {
        entry.preparation.whenComplete((prepared, failure) -> {
            boolean schedule = false;
            String error = null;
            List<CompletableFuture<ShaderStatus>> failedWaiters = List.of();
            synchronized (LOCK) {
                if (ENTRIES.get(entry.descriptor.id()) != entry) return;
                if (failure == null) {
                    recordPreparationLocked(entry, prepared);
                    if (entry.preloadAfterPreparation) {
                        entry.preloadAfterPreparation = false;
                        entry.state = ShaderLoadState.REGISTERED;
                        schedule = true;
                    } else if (entry.request == 0 && entry.state == ShaderLoadState.PREPARING) {
                        entry.state = ShaderLoadState.REGISTERED;
                    }
                } else {
                    boolean ownsFailure = entry.request == 0 || entry.preloadAfterPreparation;
                    entry.preloadAfterPreparation = false;
                    if (ownsFailure) {
                        entry.state = ShaderLoadState.FAILED;
                        Throwable cause = failure instanceof CompletionException && failure.getCause() != null
                                ? failure.getCause() : failure;
                        entry.error = conciseError(cause);
                        error = entry.error;
                        failedWaiters = drainWaitersLocked(entry);
                    }
                }
                publishStatusLocked(entry);
            }
            if (error != null) {
                completeWaitersFailure(failedWaiters, unavailable(entry));
                notifyFailure(entry, error);
            }
            if (schedule) scheduleLatePreload(entry, false);
        });
    }

    private static RenderShaderDescriptor.PreparedSource awaitPreparation(Entry entry) {
        try {
            RenderShaderDescriptor.PreparedSource prepared = entry.preparation.join();
            synchronized (LOCK) {
                recordPreparationLocked(entry, prepared);
            }
            return prepared;
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            throw exception;
        }
    }

    private static void recordPreparationLocked(
            Entry entry,
            RenderShaderDescriptor.PreparedSource prepared
    ) {
        entry.lastPreparationNanos = prepared.preparationNanos();
        entry.translationCacheHit = prepared.translationCacheHit();
    }

    private static void installIfCurrent(Entry entry, long request, ShaderInstance shader,
                                         boolean registryOwned, long elapsed, ShaderLoadOrigin origin) {
        ShaderInstance previousOwned = null;
        boolean installed = false;
        ShaderStatus readyStatus = null;
        List<CompletableFuture<ShaderStatus>> readyWaiters = List.of();
        synchronized (LOCK) {
            if (isCurrentRequest(entry, request)) {
                previousOwned = entry.ownedShader;
                entry.ownedShader = registryOwned ? shader : null;
                entry.handle.install(shader);
                entry.state = ShaderLoadState.READY;
                entry.origin = origin;
                entry.lastCompileNanos = elapsed;
                entry.error = null;
                entry.queuedJob = null;
                publishStatusLocked(entry);
                readyStatus = entry.handle.cachedStatus();
                readyWaiters = drainWaitersLocked(entry);
                installed = true;
            }
        }
        closeShader(previousOwned);
        if (!installed && registryOwned) closeShader(shader);
        if (installed) {
            completeWaitersReady(readyWaiters, readyStatus);
            notifyReady(entry, shader);
        }
    }

    private static void markFailure(
            Entry entry,
            long request,
            long elapsed,
            boolean compileAttempted,
            Exception exception
    ) {
        String error = conciseError(exception);
        boolean current;
        List<CompletableFuture<ShaderStatus>> failedWaiters = List.of();
        synchronized (LOCK) {
            if (compileAttempted) {
                totalCompileNanos += elapsed;
                failedCompiles++;
            }
            current = isCurrentRequest(entry, request);
            if (current) {
                entry.handle.invalidate();
                entry.state = ShaderLoadState.FAILED;
                entry.lastCompileNanos = elapsed;
                entry.error = error;
                entry.queuedJob = null;
                publishStatusLocked(entry);
                failedWaiters = drainWaitersLocked(entry);
            }
        }
        if (current) {
            completeWaitersFailure(failedWaiters, unavailable(entry));
            notifyFailure(entry, error);
        }
    }

    private static boolean isCurrentRequest(Entry entry, long request) {
        return ENTRIES.get(entry.descriptor.id()) == entry && entry.request == request;
    }

    private static boolean isActive(Entry entry) {
        synchronized (LOCK) {
            return ENTRIES.get(entry.descriptor.id()) == entry;
        }
    }

    private static void unregister(Entry entry) {
        boolean removed;
        List<CompletableFuture<ShaderStatus>> waiters = List.of();
        synchronized (LOCK) {
            removed = ENTRIES.get(entry.descriptor.id()) == entry;
            if (removed) {
                ENTRIES.remove(entry.descriptor.id());
                waiters = invalidateLocked(entry, ShaderLoadState.CLOSED);
            }
        }
        if (removed) {
            disposeOwned(entry);
            completeWaitersFailure(waiters, unavailable(entry));
            notifyInvalidated(entry);
        }
    }

    private static List<CompletableFuture<ShaderStatus>> invalidateLocked(
            Entry entry,
            ShaderLoadState finalState
    ) {
        cancelQueuedLocked(entry);
        entry.preparation.cancel(false);
        entry.request++;
        entry.handle.invalidate();
        entry.state = finalState;
        entry.error = null;
        publishStatusLocked(entry);
        return drainWaitersLocked(entry);
    }

    private static void cancelQueuedLocked(Entry entry) {
        ShaderPreloadScheduler.cancel(entry.queuedJob);
        entry.queuedJob = null;
    }

    private static ShaderStatus statusLocked(Entry entry) {
        long wait = entry.queuedJob == null
                ? entry.lastQueueWaitNanos
                : ShaderPreloadScheduler.waitNanos(entry.queuedJob);
        return new ShaderStatus(
                entry.state, entry.origin, ShaderPreloadScheduler.position(entry.queuedJob), wait,
                entry.handle.generation(), entry.lastPreparationNanos, entry.translationCacheHit,
                entry.lastCompileNanos, entry.error
        );
    }

    private static void publishStatusLocked(Entry entry) {
        entry.handle.updateStatus(statusLocked(entry));
    }

    private static void disposeOwned(Entry entry) {
        if (entry == null) return;
        ShaderInstance owned;
        synchronized (LOCK) {
            owned = entry.ownedShader;
            entry.ownedShader = null;
        }
        closeShader(owned);
    }

    private static void closeShader(ShaderInstance shader) {
        if (shader == null) return;
        if (RenderSystem.isOnRenderThread()) shader.close();
        else RenderSystem.recordRenderCall(shader::close);
    }

    private static void notifyReady(Entry entry, ShaderInstance shader) {
        if (entry.listener == ShaderLoadListener.NONE) return;
        long generation = entry.handle.generation();
        dispatchRenderCallback(() -> {
            try {
                entry.listener.onReady(shader, generation);
            } catch (RuntimeException exception) {
                LOGGER.error("Shader ready listener failed for {}", entry.descriptor.id(), exception);
            }
        });
    }

    private static void notifyFailure(Entry entry, String error) {
        if (entry.listener == ShaderLoadListener.NONE) return;
        dispatchRenderCallback(() -> {
            try {
                entry.listener.onFailure(error);
            } catch (RuntimeException exception) {
                LOGGER.error("Shader failure listener failed for {}", entry.descriptor.id(), exception);
            }
        });
    }

    private static void notifyInvalidated(Entry entry) {
        if (entry.listener == ShaderLoadListener.NONE) return;
        dispatchRenderCallback(() -> {
            try {
                entry.listener.onInvalidated();
            } catch (RuntimeException exception) {
                LOGGER.error("Shader invalidation listener failed for {}", entry.descriptor.id(), exception);
            }
        });
    }

    private static List<CompletableFuture<ShaderStatus>> drainWaitersLocked(Entry entry) {
        if (entry.readyWaiters.isEmpty()) return List.of();
        List<CompletableFuture<ShaderStatus>> waiters = List.copyOf(entry.readyWaiters);
        entry.readyWaiters.clear();
        return waiters;
    }

    private static void completeWaitersReady(
            List<CompletableFuture<ShaderStatus>> waiters,
            ShaderStatus status
    ) {
        if (waiters.isEmpty()) return;
        dispatchRenderCallback(() -> waiters.forEach(waiter -> waiter.complete(status)));
    }

    private static void completeWaitersFailure(
            List<CompletableFuture<ShaderStatus>> waiters,
            RuntimeException failure
    ) {
        if (waiters.isEmpty()) return;
        dispatchRenderCallback(() -> waiters.forEach(waiter -> waiter.completeExceptionally(failure)));
    }

    private static void dispatchRenderCallback(Runnable callback) {
        if (RenderSystem.isOnRenderThread()) callback.run();
        else RenderSystem.recordRenderCall(callback::run);
    }

    private static IllegalStateException unavailable(Entry entry) {
        String detail = entry.error == null ? entry.state.name() : entry.error;
        return new IllegalStateException("Shader " + entry.descriptor.id() + " is unavailable: " + detail);
    }

    private static String conciseError(Throwable throwable) {
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) return throwable.getClass().getSimpleName();
        return throwable.getClass().getSimpleName() + ": " + message;
    }

    private static final class Entry {
        private final ResourceLocation owner;
        private final RenderShaderDescriptor descriptor;
        private final RenderShaderHandle handle;
        private final ShaderLoadListener listener;
        private final CompletableFuture<RenderShaderDescriptor.PreparedSource> preparation;
        private long request;
        private ShaderLoadState state;
        private ShaderLoadOrigin origin = ShaderLoadOrigin.NONE;
        private ShaderPreloadSchedulerCore.Job queuedJob;
        private long lastQueueWaitNanos;
        private long lastPreparationNanos;
        private boolean translationCacheHit;
        private long lastCompileNanos;
        private String error;
        private ShaderInstance ownedShader;
        private boolean preloadAfterPreparation;
        private final List<CompletableFuture<ShaderStatus>> readyWaiters = new ArrayList<>();

        private Entry(ResourceLocation owner, RenderShaderDescriptor descriptor,
                      RenderShaderHandle handle, ShaderLoadListener listener) {
            this.owner = owner;
            this.descriptor = descriptor;
            this.handle = handle;
            this.listener = listener;
            if (descriptor.sourceKind() == RenderShaderDescriptor.SourceKind.GENERATED) {
                state = ShaderLoadState.PREPARING;
                preparation = ShaderPreparationScheduler.submit(
                        owner, descriptor.id(), descriptor::prepareSource
                );
            } else {
                state = ShaderLoadState.REGISTERED;
                preparation = CompletableFuture.completedFuture(descriptor.prepareSource());
            }
        }
    }

    public record ShaderSnapshot(
            ResourceLocation owner,
            ResourceLocation id,
            RenderShaderDescriptor.SourceKind sourceKind,
            ShaderLoadState state,
            ShaderLoadOrigin origin,
            ShaderPreloadPolicy preloadPolicy,
            int preloadPriority,
            ShaderFailurePolicy failurePolicy,
            int queuePosition,
            long queueWaitNanos,
            long generation,
            long preparationNanos,
            boolean translationCacheHit,
            long compileNanos,
            String error
    ) {}

    public record PreloadStats(
            long registered,
            long ready,
            long queued,
            long failed,
            long compileAttempts,
            long successfulCompiles,
            long failedCompiles,
            long latePreloads,
            long totalCompileNanos,
            long lastReloadNanos,
            long cachedGeneratedPrograms,
            long generatedTranslations,
            long generatedCacheHits,
            long generatedTranslationNanos,
            ShaderPreparationStats preparation,
            ShaderPreloadScheduler.Settings schedulerSettings,
            ShaderPreloadScheduler.Stats scheduler
    ) {}

    private static final class RegistrationImpl implements ShaderRegistration {
        private final Entry entry;
        private volatile boolean closed;

        private RegistrationImpl(Entry entry) {
            this.entry = entry;
        }

        @Override public ResourceLocation id() { return entry.descriptor.id(); }
        @Override public ResourceLocation owner() { return entry.owner; }
        @Override public boolean isActive() { return !closed && RenderShaderRegistry.isActive(entry); }
        @Override public RenderShaderDescriptor descriptor() { return entry.descriptor; }
        @Override public RenderShaderHandle handle() { return entry.handle; }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            unregister(entry);
        }
    }
}
