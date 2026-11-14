package lib.kasuga.registration.data_driven.pipeline;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide, host-owned finalizers for multi-source data-driven builds.
 */
public final class DataDrivenBuildHooks {
    private static final Map<String, List<BuildFinalizer>> FINALIZERS =
            new ConcurrentHashMap<>();

    public static void registerFinalizer(String hostModId, BuildFinalizer finalizer) {
        String host = requireHost(hostModId);
        Objects.requireNonNull(finalizer, "finalizer");
        FINALIZERS.computeIfAbsent(host, ignored -> new CopyOnWriteArrayList<>())
                .add(finalizer);
    }

    public static List<BuildFinalizer> finalizersFor(String hostModId) {
        return List.copyOf(FINALIZERS.getOrDefault(requireHost(hostModId), List.of()));
    }

    private static String requireHost(String hostModId) {
        if (hostModId == null || hostModId.isBlank()) {
            throw new IllegalArgumentException("hostModId must not be blank");
        }
        return hostModId;
    }

    private DataDrivenBuildHooks() {
    }
}
