package lib.kasuga.rendering.models.uml.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

public final class ModelProfiler {

    private static final Logger LOGGER = LoggerFactory.getLogger("KasugaModelProfiler");
    private static final Map<String, Summary> SUMMARIES = new LinkedHashMap<>();
    private static long nextReportNanos = System.nanoTime() + reportIntervalNanos();
    private static boolean loggedEnabled;

    private ModelProfiler() {}

    public static boolean enabled() {
        return readBoolean("KASUGA_PROFILE_MODEL", "kasuga.profileModel", false);
    }

    public static long start() {
        if (!enabled()) return 0L;
        logEnabledOnce();
        return System.nanoTime();
    }

    public static void record(String name, long startNanos) {
        record(name, startNanos, null);
    }

    public static void record(String name, long startNanos, String detail) {
        if (!enabled()) return;
        logEnabledOnce();
        long elapsed = System.nanoTime() - startNanos;
        synchronized (SUMMARIES) {
            SUMMARIES.computeIfAbsent(name, ignored -> new Summary()).add(elapsed, detail);
            long now = System.nanoTime();
            if (now >= nextReportNanos) {
                reportLocked();
                nextReportNanos = now + reportIntervalNanos();
            }
        }
    }

    private static void logEnabledOnce() {
        if (loggedEnabled) return;
        synchronized (SUMMARIES) {
            if (loggedEnabled) return;
            LOGGER.info("[model-profile] enabled intervalMs={}", reportIntervalMillis());
            loggedEnabled = true;
        }
    }

    private static long reportIntervalNanos() {
        return reportIntervalMillis() * 1_000_000L;
    }

    private static long reportIntervalMillis() {
        String value = System.getenv("KASUGA_PROFILE_MODEL_INTERVAL_MS");
        if (value == null || value.isBlank()) {
            value = System.getProperty("kasuga.profileModel.intervalMs");
        }
        if (value == null || value.isBlank()) {
            return 2000L;
        }
        try {
            return Math.max(1L, Long.parseLong(value));
        } catch (NumberFormatException ignored) {
            return 2000L;
        }
    }

    private static boolean readBoolean(String envName, String propertyName, boolean fallback) {
        String env = System.getenv(envName);
        if (env != null && !env.isBlank()) {
            return Boolean.parseBoolean(env);
        }
        String property = System.getProperty(propertyName);
        if (property != null && !property.isBlank()) {
            return Boolean.parseBoolean(property);
        }
        return fallback;
    }

    private static void reportLocked() {
        for (Map.Entry<String, Summary> entry : SUMMARIES.entrySet()) {
            Summary summary = entry.getValue();
            if (summary.count == 0) continue;
            double avgMs = nanosToMillis(summary.totalNanos / summary.count);
            double maxMs = nanosToMillis(summary.maxNanos);
            if (summary.lastDetail == null) {
                LOGGER.info("[model-profile] {} count={} avg={}ms max={}ms",
                        entry.getKey(), summary.count, format(avgMs), format(maxMs));
            } else {
                LOGGER.info("[model-profile] {} count={} avg={}ms max={}ms last={}",
                        entry.getKey(), summary.count, format(avgMs), format(maxMs), summary.lastDetail);
            }
            summary.reset();
        }
    }

    private static double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0;
    }

    private static String format(double value) {
        return String.format("%.3f", value);
    }

    private static class Summary {
        private long count;
        private long totalNanos;
        private long maxNanos;
        private String lastDetail;

        private void add(long nanos, String detail) {
            count++;
            totalNanos += nanos;
            maxNanos = Math.max(maxNanos, nanos);
            if (detail != null) {
                lastDetail = detail;
            }
        }

        private void reset() {
            count = 0;
            totalNanos = 0;
            maxNanos = 0;
            lastDetail = null;
        }
    }
}
