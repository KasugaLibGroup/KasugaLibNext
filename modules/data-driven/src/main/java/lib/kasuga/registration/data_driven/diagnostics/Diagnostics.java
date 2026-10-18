package lib.kasuga.registration.data_driven.diagnostics;

import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The single diagnostic outlet of the unified data-driven framework: every load failure — whatever
 * domain produced it and whenever it happened — is reported here and read from here.
 *
 * <p>Errors are addressed in two dimensions, and the dimensions never mix:
 * <ul>
 *     <li><b>by mod id</b> — the registration domain's bucket, which is the existing
 *     {@link JsonTreeBuilder} bucket. {@link #report(String, Throwable)} and {@link #errors(String)}
 *     delegate to it verbatim, so the documented contract ("assert
 *     {@code JsonTreeBuilder.getLoadingErrors(modId)} is empty") keeps holding;</li>
 *     <li><b>by domain and source key</b> — for failures that belong to a source rather than to a mod:
 *     a resource path in the asset domain, a config file in the config domain, the owning namespace in
 *     the reload domain. The reload domain writes here today ({@link Domain#RELOAD_DATA}): the reload
 *     orchestrator and the modelling-side reload handlers report a reload failure under the namespace
 *     that owns the offending file. The remaining domains (asset reload, config) are still reserved, so
 *     they can report here later without reshaping the outlet.</li>
 * </ul>
 *
 * <p>Reporting never aborts a load: a failure handed in is recorded, never rethrown, and the buckets
 * stay readable while a load is in flight (per key, individually synchronized). Null arguments are
 * rejected up front — that is a programming error, not load data.
 */
public final class Diagnostics {

    /**
     * The four data domains of the unified framework (see the design's four-domain model). A
     * source-keyed bucket is scoped to exactly one of them so that "clear this domain" is a meaningful
     * operation and two domains cannot accidentally share a key.
     */
    public enum Domain {
        /** Mod-jar registration, read once at mod construction. */
        REGISTRATION,
        /** Data-pack reload data, read from the pack stack on {@code /reload}. */
        RELOAD_DATA,
        /** Pack-stack assets, read on resource reload. */
        RELOAD_ASSETS,
        /** Files under {@code config/}, read and written outside both stacks. */
        CONFIG
    }

    /** Identity of one source-keyed bucket. */
    private record SourceKey(Domain domain, String key) {}

    /** Source-keyed buckets; a separate map keeps them structurally isolated from the mod buckets. */
    private static final Map<SourceKey, List<Throwable>> errorsBySource = new ConcurrentHashMap<>();

    // --- dimension 1: per mod (delegates to the existing bucket) ---

    /**
     * Records a failure against {@code modId}. Equivalent to
     * {@link JsonTreeBuilder#addLoadingError(String, Throwable)}; use whichever names the caller's
     * intent.
     */
    public static void report(String modId, Throwable error) {
        JsonTreeBuilder.addLoadingError(modId, error);
    }

    /**
     * Snapshot of the errors recorded for {@code modId}; empty when the mod has none. Delegates to
     * {@link JsonTreeBuilder#getLoadingErrors(String)}, whose result is the documented assertion
     * target.
     */
    public static List<Throwable> errors(String modId) {
        return JsonTreeBuilder.getLoadingErrors(modId);
    }

    /** Every mod's errors keyed by mod id, mods ordered by id. Delegates to the existing bucket. */
    public static Map<String, List<Throwable>> errorsByMod() {
        return JsonTreeBuilder.getLoadingErrorsByMod();
    }

    /** Drops {@code modId}'s bucket; other mods and every source bucket are untouched. */
    public static void clear(String modId) {
        JsonTreeBuilder.clearLoadingErrors(modId);
    }

    // --- dimension 2: per domain and source key ---

    /**
     * Records a failure against one source of one domain — for example
     * {@code report(Domain.RELOAD_ASSETS, "models/model_proxy.json", error)}.
     *
     * <p>The key is the addressing unit of that domain: a resource path for assets, a file name for
     * config, the owning namespace for reload data. It is never a mod id bucket — that is dimension 1.
     */
    public static void report(Domain domain, String key, Throwable error) {
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(error, "error");
        List<Throwable> bucket = errorsBySource.computeIfAbsent(new SourceKey(domain, key), k -> new ArrayList<>());
        synchronized (bucket) {
            bucket.add(error);
        }
    }

    /** Snapshot of one source bucket; empty when nothing was reported for that domain/key pair. */
    public static List<Throwable> errors(Domain domain, String key) {
        List<Throwable> bucket = errorsBySource.get(new SourceKey(domain, key));
        if (bucket == null) return List.of();
        synchronized (bucket) {
            return List.copyOf(bucket);
        }
    }

    /** Every error of {@code domain}, sources ordered by key. */
    public static List<Throwable> errors(Domain domain) {
        List<Throwable> all = new ArrayList<>();
        for (String key : sourceKeys(domain)) {
            all.addAll(errors(domain, key));
        }
        return List.copyOf(all);
    }

    /** Drops every source bucket of {@code domain}, leaving other domains and all mod buckets alone. */
    public static void clear(Domain domain) {
        errorsBySource.keySet().removeIf(sourceKey -> sourceKey.domain() == domain);
    }

    /** Drops both dimensions completely. */
    public static void clearAll() {
        errorsBySource.clear();
        JsonTreeBuilder.clearLoadingErrors();
    }

    // --- read-side: one summary line per bucket ---

    /**
     * One line per non-empty bucket, to be logged after startup or a reload: {@code "<mod>: N error(s)"}
     * for a mod bucket and {@code "<DOMAIN>[<key>]: N error(s)"} for a source bucket. Mods come first,
     * ordered by id; then the domains in declaration order, their sources ordered by key. Deterministic,
     * so callers (the {@code /kasuga_data errors} command, tests) can assert on it.
     */
    public static List<String> summarize() {
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, List<Throwable>> entry : errorsByMod().entrySet()) {
            if (!entry.getValue().isEmpty()) {
                lines.add(entry.getKey() + ": " + entry.getValue().size() + " error(s)");
            }
        }
        for (Domain domain : Domain.values()) {
            for (String key : sourceKeys(domain)) {
                List<Throwable> bucket = errors(domain, key);
                if (!bucket.isEmpty()) {
                    lines.add(bucketLabel(domain, key) + ": " + bucket.size() + " error(s)");
                }
            }
        }
        return List.copyOf(lines);
    }

    /**
     * The label {@link #summarize()} gives one source bucket: {@code "<DOMAIN>[<key>]"}. Kept here so
     * the per-source detail view ({@code /kasuga_data errors <mod>}) prefixes an individual reload
     * failure with the exact same label the summary line uses, instead of a second copy of the format.
     */
    static String bucketLabel(Domain domain, String key) {
        return domain + "[" + key + "]";
    }

    /** Source keys of one domain that currently hold errors, ordered by key. */
    private static List<String> sourceKeys(Domain domain) {
        List<String> keys = new ArrayList<>();
        for (SourceKey sourceKey : errorsBySource.keySet()) {
            if (sourceKey.domain() == domain) {
                keys.add(sourceKey.key());
            }
        }
        keys.sort(String::compareTo);
        return keys;
    }

    private Diagnostics() {}
}
