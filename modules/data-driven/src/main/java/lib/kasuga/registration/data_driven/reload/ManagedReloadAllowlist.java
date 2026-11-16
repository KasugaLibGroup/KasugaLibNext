package lib.kasuga.registration.data_driven.reload;

import lib.kasuga.registration.data_driven.pipeline.LocatedContentPath;
import lib.kasuga.registration.data_driven.pipeline.LocatedIndexPath;
import lib.kasuga.registration.data_driven.source.LogicalPath;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Process-wide allowlist for resources projected by a managed registration session.
 *
 * <p>Only resources whose ApprovedResourcePacks identity matches a prepared source/root are
 * filtered. Legacy mod and datapack packs keep their historical reload behavior.
 */
public final class ManagedReloadAllowlist {
    private static final Map<String, Policy> POLICIES = new LinkedHashMap<>();

    private ManagedReloadAllowlist() {
    }

    public static synchronized void install(
            String hostModId,
            List<LocatedIndexPath> indexes,
            List<LocatedContentPath> reloadFiles
    ) {
        install(hostModId, List.of(), indexes, reloadFiles);
    }

    public static synchronized void install(
            String hostModId,
            List<SourceDescriptor> sources,
            List<LocatedIndexPath> indexes,
            List<LocatedContentPath> reloadFiles
    ) {
        install(hostModId, sources, Map.of(), indexes, reloadFiles);
    }

    public static synchronized void install(
            String hostModId,
            List<SourceDescriptor> sources,
            Map<String, String> projectedSourcePackIds,
            List<LocatedIndexPath> indexes,
            List<LocatedContentPath> reloadFiles
    ) {
        Objects.requireNonNull(hostModId, "hostModId");
        Objects.requireNonNull(projectedSourcePackIds, "projectedSourcePackIds");
        Set<String> childPackIds = new LinkedHashSet<>();
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (SourceDescriptor source : sources) {
            String childPackId = projectedSourcePackIds.getOrDefault(
                    sourceKey(source),
                    childPackId(source)
            );
            childPackIds.add(childPackId);
            ranks.merge(childPackId, sourceOrder(source), Math::min);
        }
        for (LocatedIndexPath index : indexes) {
            String childPackId = projectedSourcePackIds.getOrDefault(
                    sourceKey(index.source()),
                    childPackId(index.source())
            );
            childPackIds.add(childPackId);
            ranks.merge(childPackId, index.sourceOrder(), Math::min);
        }
        for (LocatedContentPath file : reloadFiles) {
            String childPackId = projectedSourcePackIds.getOrDefault(
                    sourceKey(file.source()),
                    childPackId(file.source())
            );
            childPackIds.add(childPackId);
            ranks.merge(childPackId, file.sourceOrder(), Math::min);
        }
        Set<ResourceLocation> indexLocations = new LinkedHashSet<>();
        for (LocatedIndexPath index : indexes) {
            indexLocations.add(resourceLocation(index.logicalPath()));
        }
        Set<ResourceLocation> contentLocations = new LinkedHashSet<>();
        for (LocatedContentPath file : reloadFiles) {
            contentLocations.add(resourceLocation(file.logicalPath()));
        }
        POLICIES.put(hostModId, new Policy(
                Set.copyOf(childPackIds),
                Map.copyOf(ranks),
                Set.copyOf(indexLocations),
                Set.copyOf(contentLocations)
        ));
    }

    public static synchronized boolean isManaged(String sourcePackId) {
        return sourcePackId != null
                && POLICIES.values().stream().anyMatch(policy -> policy.managedSource(sourcePackId));
    }

    public static synchronized boolean allowsIndex(String sourcePackId, ResourceLocation location) {
        return POLICIES.values().stream()
                .filter(policy -> policy.managedSource(sourcePackId))
                .findFirst()
                .map(policy -> policy.indexes().contains(location))
                .orElse(true);
    }

    public static synchronized boolean allowsContent(String sourcePackId, ResourceLocation location) {
        return POLICIES.values().stream()
                .filter(policy -> policy.managedSource(sourcePackId))
                .findFirst()
                .map(policy -> policy.content().contains(location))
                .orElse(true);
    }

    public static synchronized int priority(String sourcePackId) {
        return POLICIES.values().stream()
                .filter(policy -> policy.managedSource(sourcePackId))
                .map(policy -> policy.ranks().get(sourcePackId))
                .filter(Objects::nonNull)
                .findFirst()
                .orElse(Integer.MAX_VALUE);
    }

    public static synchronized void clearForTests() {
        POLICIES.clear();
    }

    private static String childPackId(SourceDescriptor source) {
        return "kasuga/" + source.sourceId() + "-" + source.rootId()
                + "/" + source.sourceId() + "/" + source.rootId();
    }

    private static String sourceKey(SourceDescriptor source) {
        return source.sourceId() + "/" + source.rootId();
    }

    private static int sourceOrder(SourceDescriptor source) {
        return source.resource() == null ? 0 : source.resource().rank();
    }

    private static ResourceLocation resourceLocation(LogicalPath path) {
        List<String> segments = path.segments();
        if (segments.size() < 4 || !segments.getFirst().equals("data")) {
            throw new IllegalArgumentException("Managed reload path is not under data/: " + path);
        }
        String namespace = segments.get(1);
        String relative = String.join("/", segments.subList(2, segments.size()));
        return ResourceLocation.fromNamespaceAndPath(namespace, relative);
    }

    private record Policy(
            Set<String> childPackIds,
            Map<String, Integer> ranks,
            Set<ResourceLocation> indexes,
            Set<ResourceLocation> content
    ) {
        private boolean managedSource(String sourcePackId) {
            return childPackIds.contains(sourcePackId);
        }
    }
}
