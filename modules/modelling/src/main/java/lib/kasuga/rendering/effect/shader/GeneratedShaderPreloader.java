package lib.kasuga.rendering.effect.shader;

import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.backend.MinecraftGlsl150Backend;
import lib.kasuga.shader.backend.MinecraftShaderBundle;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Bounded translation cache used before generated shaders reach OpenGL. */
final class GeneratedShaderPreloader {
    private static final int MAX_ENTRIES = 128;
    private static final Object LOCK = new Object();
    private static final Map<ShaderProgram, CompletableFuture<CachedBundle>> CACHE =
            new LinkedHashMap<>(16, 0.75f, true);

    private static long translations;
    private static long cacheHits;
    private static long translationNanos;

    private GeneratedShaderPreloader() {}

    static Prepared prepare(ShaderProgram program) {
        Objects.requireNonNull(program, "program");
        CompletableFuture<CachedBundle> future;
        boolean translate = false;
        synchronized (LOCK) {
            future = CACHE.get(program);
            if (future == null) {
                future = new CompletableFuture<>();
                CACHE.put(program, future);
                trimLocked();
                translate = true;
            } else {
                cacheHits++;
            }
        }

        if (translate) {
            try {
                long started = System.nanoTime();
                MinecraftShaderBundle bundle = MinecraftGlsl150Backend.generate(program);
                long elapsed = System.nanoTime() - started;
                CachedBundle cached = new CachedBundle(
                        new GeneratedShaderResourceProvider(bundle), elapsed
                );
                synchronized (LOCK) {
                    translations++;
                    translationNanos += elapsed;
                }
                future.complete(cached);
            } catch (RuntimeException | Error exception) {
                synchronized (LOCK) {
                    if (CACHE.get(program) == future) CACHE.remove(program);
                }
                future.completeExceptionally(exception);
                throw exception;
            }
        }

        CachedBundle cached = join(future);
        return new Prepared(cached.resources, cached.translationNanos, !translate);
    }

    static Stats stats() {
        synchronized (LOCK) {
            return new Stats(CACHE.size(), translations, cacheHits, translationNanos);
        }
    }

    static void clear() {
        synchronized (LOCK) {
            CACHE.clear();
        }
    }

    private static void trimLocked() {
        while (CACHE.size() > MAX_ENTRIES) CACHE.remove(CACHE.keySet().iterator().next());
    }

    private static CachedBundle join(CompletableFuture<CachedBundle> future) {
        try {
            return future.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw exception;
        }
    }

    record Prepared(
            GeneratedShaderResourceProvider resources,
            long translationNanos,
            boolean cacheHit
    ) {}

    record Stats(int cachedPrograms, long translations, long cacheHits, long translationNanos) {}

    private record CachedBundle(
            GeneratedShaderResourceProvider resources,
            long translationNanos
    ) {}
}
