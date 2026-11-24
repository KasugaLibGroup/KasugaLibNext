package lib.kasuga.core.resource.pack;

import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.lifecycle.*;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.*;
import lib.kasuga.core.resource.view.ResourceCatalog;
import net.minecraft.network.chat.Component;
import net.minecraft.server.packs.*;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.*;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforge.resource.EmptyPackResources;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Register at host bootstrap; the installed finder participates in initial and later repository discovery. */
public final class ApprovedResourcePacks implements AutoCloseable {
    private record Registration(String id, List<ResourceSource> sources, ResourceRouting plan) {}
    private final Map<String, Registration> registrations = new LinkedHashMap<>();
    private final Map<String, GroupHandle> groups = new LinkedHashMap<>();
    private final List<ResourceSnapshot> clientSnapshots = new ArrayList<>(), serverSnapshots = new ArrayList<>();
    private final AtomicLong generation = new AtomicLong();
    private boolean closed;
    public synchronized AutoCloseable register(String id, List<ResourceSource> sources, ResourceRouting plan) {
        ResourcePaths.id(id);
        Registration registration = new Registration(id, List.copyOf(sources), Objects.requireNonNull(plan));
        if (closed || registrations.putIfAbsent(id, registration) != null) throw new IllegalStateException("Closed/duplicate resource pack registration");
        return () -> { synchronized (this) { registrations.remove(id, registration); } };
    }
    /**
     * Captures every provider once and publishes one required empty parent with rank-ordered hidden
     * children. The parent and children share one explicit placement so later repositories cannot
     * split the group.
     */
    public synchronized AutoCloseable registerOrderedGroup(OrderedResourcePackGroup input)
            throws IOException {
        Objects.requireNonNull(input, "input");
        if (closed || groups.containsKey(input.groupId())) {
            throw new IllegalStateException("Closed/duplicate ordered resource pack group");
        }
        validateGroup(input);
        validateIdsAgainstRegisteredPacks(input);

        List<ResourceSnapshot> owned = new ArrayList<>();
        boolean published = false;
        Throwable primary = null;
        try {
            for (OrderedResourcePackGroup.Layer layer : input.layers()) {
                owned.add(layer.source().capture());
            }
            Map<PackType, Optional<Pack>> parents = new EnumMap<>(PackType.class);
            long projectionGeneration = generation.incrementAndGet();
            for (PackType type : List.of(PackType.CLIENT_RESOURCES, PackType.SERVER_DATA)) {
                parents.put(type, createParent(input, owned, type, projectionGeneration));
            }
            Set<String> childIds = input.layers().stream()
                    .map(ApprovedResourcePacks::childPackId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
            GroupHandle handle = new GroupHandle(
                    input.groupId(),
                    Map.copyOf(parents),
                    new ArrayList<>(owned),
                    childIds
            );
            groups.put(input.groupId(), handle);
            published = true;
            return handle;
        } catch (IOException | RuntimeException | Error failure) {
            primary = failure;
            throw failure;
        } finally {
            if (!published) {
                closeSnapshotsWithPrimary(owned, primary);
            }
        }
    }
    public void onAddPackFinders(AddPackFindersEvent event) { event.addRepositorySource(repositorySource(event.getPackType())); }
    public RepositorySource repositorySource(PackType type) { return output -> discover(type, output); }
    private synchronized void discover(PackType type, Consumer<Pack> output) {
        if (closed) throw new IllegalStateException("Resource pack registry closed");
        record OrderedPack(Pack pack, int rank) {}
        List<ResourceSnapshot> pending = new ArrayList<>(); List<OrderedPack> discovered = new ArrayList<>();
        Map<String, Map<Integer, String>> targets = new HashMap<>();
        try {
            for (Registration registration : registrations.values()) {
                List<ResourceSnapshot> captured = new ArrayList<>();
                for (ResourceSource source : registration.sources()) { ResourceSnapshot snapshot = source.capture(); captured.add(snapshot); pending.add(snapshot); }
                ResourceCatalog catalog = new ResourceCatalog("mc/" + type.name() + "/" + registration.id(), generation.incrementAndGet(), captured);
                var side = type == PackType.CLIENT_RESOURCES ? LogicalSide.CLIENT : LogicalSide.SERVER;
                List<ResourceRouting.Route> routes = registration.plan().route(catalog, ResourceEvent.RESOURCE_RELOAD, side).stream()
                        .filter(r -> r.output().entryPointId().equals(ResourceRouting.MINECRAFT_ENTRY_POINT)).toList();
                Map<String, List<ResourceRouting.Route>> layers = new LinkedHashMap<>();
                routes.stream().sorted(Comparator.comparing(ResourceRouting.Route::file, ResourceCatalog.ORDER)).forEach(route -> {
                    var d = route.file().descriptor();
                    String previous = targets.computeIfAbsent(route.targetPath(), ignored -> new HashMap<>()).putIfAbsent(d.source().rank(), d.uri().toString());
                    if (previous != null) throw new IllegalArgumentException("ROUTING_CONFLICT: equal rank/duplicate projected providers");
                    layers.computeIfAbsent(d.uri().sourceId() + "/" + d.uri().rootId(), ignored -> new ArrayList<>()).add(route);
                });
                for (var layer : layers.entrySet()) {
                    ResourceSourceDescriptor source = layer.getValue().getFirst().file().descriptor().source();
                    List<ResourceRouting.Route> inputs = List.copyOf(layer.getValue());
                    String id = "kasuga/" + registration.id() + "/" + source.sourceId() + "/" + source.rootId();
                    var location = new PackLocationInfo(id, Component.literal(id), PackSource.BUILT_IN, Optional.empty());
                    var supplier = new Pack.ResourcesSupplier() {
                        @Override public PackResources openPrimary(PackLocationInfo info) { return new MappedPackResources(info, inputs, type); }
                        @Override public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) { return openPrimary(info); }
                    };
                    Pack pack = Pack.readMetaAndCreate(location, supplier, type, new PackSelectionConfig(true, Pack.Position.TOP, true));
                    if (pack == null) throw new IOException("Invalid projected pack metadata: " + id);
                    discovered.add(new OrderedPack(pack, source.rank()));
                }
            }
            List<ResourceSnapshot> old = type == PackType.CLIENT_RESOURCES ? clientSnapshots : serverSnapshots;
            closeSnapshots(old); old.addAll(pending); pending.clear();
            discovered.stream().sorted(Comparator.comparingInt(OrderedPack::rank).thenComparing(p -> p.pack().getId()))
                    .map(OrderedPack::pack).forEach(output);
            groups.values().stream()
                    .map(group -> group.parents().get(type))
                    .flatMap(Optional::stream)
                    .forEach(output);
        } catch (IOException e) { throw new UncheckedIOException("Resource projection failed", e); }
        finally { closeSnapshots(pending); }
    }
    private static void validateGroup(OrderedResourcePackGroup input) {
        Set<String> roots = new HashSet<>();
        Set<Integer> ranks = new HashSet<>();
        Set<String> childIds = new HashSet<>();
        String parentId = "kasuga/group/" + input.groupId();
        for (OrderedResourcePackGroup.Layer layer : input.layers()) {
            ResourceSourceDescriptor descriptor = layer.source().descriptor();
            String root = descriptor.sourceId() + "/" + descriptor.rootId();
            if (!roots.add(root)) {
                throw new IllegalArgumentException("Duplicate source/root: " + root);
            }
            if (!ranks.add(descriptor.rank())) {
                throw new IllegalArgumentException("Duplicate frozen rank: " + descriptor.rank());
            }
            String childId = childPackId(layer);
            if (childId.equals(parentId) || !childIds.add(childId)) {
                throw new IllegalArgumentException("Duplicate parent/child identity: " + childId);
            }
            if (descriptor.origin() == ResourceSourceDescriptor.Origin.MC_MANAGED) {
                throw new IllegalArgumentException("MC-managed input cannot be projected back");
            }
        }
    }

    private void validateIdsAgainstRegisteredPacks(OrderedResourcePackGroup input) {
        Set<String> existing = new HashSet<>();
        for (Registration registration : registrations.values()) {
            for (ResourceSource source : registration.sources()) {
                ResourceSourceDescriptor descriptor = source.descriptor();
                existing.add("kasuga/" + registration.id()
                        + "/" + descriptor.sourceId()
                        + "/" + descriptor.rootId());
            }
        }
        for (GroupHandle group : groups.values()) {
            existing.addAll(group.childPackIds);
            existing.add("kasuga/group/" + group.id);
        }
        String parentId = "kasuga/group/" + input.groupId();
        if (existing.contains(parentId)) {
            throw new IllegalArgumentException(
                    "Ordered parent conflicts with an existing projected pack: " + parentId
            );
        }
        for (OrderedResourcePackGroup.Layer layer : input.layers()) {
            String childId = childPackId(layer);
            if (!existing.add(childId)) {
                throw new IllegalArgumentException(
                        "Ordered child conflicts with an existing projected pack: " + childId
                );
            }
        }
    }
    private static Optional<Pack> createParent(
            OrderedResourcePackGroup input,
            List<ResourceSnapshot> snapshots,
            PackType type,
            long projectionGeneration
    ) throws IOException {
        OrderedResourcePackGroup.Placement placement = input.placements().get(type);
        List<PreparedLayer> layers = new ArrayList<>();
        Set<TargetRank> seen = new HashSet<>();
        for (int index = 0; index < input.layers().size(); index++) {
            OrderedResourcePackGroup.Layer layer = input.layers().get(index);
            PreparedLayer prepared = prepareLayer(
                    layer,
                    snapshots.get(index),
                    type,
                    projectionGeneration
            );
            for (ResourceRouting.Route route : prepared.routes()) {
                if (!seen.add(new TargetRank(route.targetPath(), prepared.source().rank()))) {
                    throw new IllegalArgumentException(
                            "Equal-rank projected providers: " + route.targetPath()
                    );
                }
            }
            if (!prepared.routes().isEmpty()) layers.add(prepared);
        }
        if (layers.isEmpty()) return Optional.empty();

        layers.sort(Comparator.comparingInt(layer -> layer.source().rank()));
        List<Pack> children = new ArrayList<>(layers.size());
        for (PreparedLayer layer : layers) {
            PackLocationInfo location = new PackLocationInfo(
                    layer.childPackId(),
                    Component.literal(layer.childPackId()),
                    PackSource.BUILT_IN,
                    Optional.empty()
            );
            Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
                @Override
                public PackResources openPrimary(PackLocationInfo info) {
                    return new MappedPackResources(info, layer.routes(), type);
                }

                @Override
                public PackResources openFull(PackLocationInfo info, Pack.Metadata metadata) {
                    return openPrimary(info);
                }
            };
            Pack child = Pack.readMetaAndCreate(
                    location,
                    supplier,
                    type,
                    new PackSelectionConfig(
                            false,
                            placement.defaultPosition(),
                            placement.fixedPosition()
                    )
            );
            if (child == null) {
                throw new IOException("Invalid projected pack metadata: " + layer.childPackId());
            }
            children.add(child);
        }

        String parentId = "kasuga/group/" + input.groupId();
        PackLocationInfo parentLocation = new PackLocationInfo(
                parentId,
                input.title(),
                PackSource.BUILT_IN,
                Optional.empty()
        );
        PackMetadataSection metadata = new PackMetadataSection(
                input.title(),
                net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(type)
        );
        Pack parent = Pack.readMetaAndCreate(
                parentLocation,
                new EmptyPackResources.EmptyResourcesSupplier(metadata),
                type,
                new PackSelectionConfig(true, placement.defaultPosition(), placement.fixedPosition())
        );
        if (parent == null) {
            throw new IOException("Invalid host-generated parent metadata: " + parentId);
        }
        return Optional.of(parent.withChildren(List.copyOf(children)));
    }
    private static PreparedLayer prepareLayer(
            OrderedResourcePackGroup.Layer input,
            ResourceSnapshot snapshot,
            PackType type,
            long projectionGeneration
    ) {
        ResourceSourceDescriptor root = input.source().descriptor();
        ResourceCatalog catalog = new ResourceCatalog(
                "mc/" + type.name() + "/" + input.legacyRegistrationId(),
                projectionGeneration,
                List.of(snapshot)
        );
        LogicalSide side = RootSide.of(type);
        List<ResourceRouting.Route> accepted = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (ResourceRouting.Route route : input.routing().route(
                catalog,
                ResourceEvent.RESOURCE_RELOAD,
                side
        )) {
            if (!route.output().entryPointId().equals(ResourceRouting.MINECRAFT_ENTRY_POINT)) {
                continue;
            }
            String path = route.targetPath();
            if (!path.equals("pack.mcmeta")
                    && !path.equals("pack.png")
                    && !path.startsWith("assets/")
                    && !path.startsWith("data/")) {
                continue;
            }
            MappedPackResources.validateMinecraftPath(path);
            ResourceSourceDescriptor provider = route.file().descriptor().source();
            if (!root.ownerId().equals(provider.ownerId())
                    || !root.sourceId().equals(provider.sourceId())
                    || !root.rootId().equals(provider.rootId())
                    || root.rank() != provider.rank()) {
                throw new IllegalArgumentException(
                        "Foreign projected provider: " + route.file().descriptor().uri()
                );
            }
            if (!paths.add(path)) {
                throw new IllegalArgumentException("Duplicate projected path: " + path);
            }
            accepted.add(route);
        }
        return new PreparedLayer(childPackId(input), root, List.copyOf(accepted));
    }
    private static String childPackId(OrderedResourcePackGroup.Layer input) {
        ResourceSourceDescriptor descriptor = input.source().descriptor();
        return "kasuga/" + input.legacyRegistrationId()
                + "/" + descriptor.sourceId() + "/" + descriptor.rootId();
    }
    private static void closeSnapshotsWithPrimary(
            List<ResourceSnapshot> snapshots,
            Throwable primary
    )
            throws IOException {
        IOException cleanup = null;
        for (ResourceSnapshot snapshot : snapshots) {
            try {
                snapshot.close();
            } catch (IOException failure) {
                if (cleanup == null) cleanup = failure;
                else cleanup.addSuppressed(failure);
            }
        }
        if (cleanup == null) return;
        if (primary != null) primary.addSuppressed(cleanup);
        else throw cleanup;
    }
    private static void closeSnapshots(List<ResourceSnapshot> snapshots) {
        IOException failure = closeSnapshots(snapshots, null);
        if (failure != null) {
            throw new UncheckedIOException(failure);
        }
    }
    @Override public synchronized void close() {
        if (closed) return; closed = true; registrations.clear();
        List<GroupHandle> retiringGroups = new ArrayList<>(groups.values());
        groups.clear();
        IOException failure = null;
        for (GroupHandle group : retiringGroups) {
            group.retired = true;
            failure = closeSnapshots(group.snapshots, failure);
        }
        failure = closeSnapshots(clientSnapshots, failure);
        failure = closeSnapshots(serverSnapshots, failure);
        if (failure != null) {
            throw new UncheckedIOException("Resource pack registry cleanup failed", failure);
        }
    }

    private static IOException closeSnapshots(
            List<ResourceSnapshot> snapshots,
            IOException previous
    ) {
        IOException failure = previous;
        for (ResourceSnapshot snapshot : snapshots) {
            try {
                snapshot.close();
            } catch (IOException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        snapshots.clear();
        return failure;
    }

    private record PreparedLayer(
            String childPackId,
            ResourceSourceDescriptor source,
            List<ResourceRouting.Route> routes
    ) {
    }
    private record TargetRank(String target, int rank) {
    }

    private final class GroupHandle implements AutoCloseable {
        private final String id;
        private final Map<PackType, Optional<Pack>> parents;
        private final List<ResourceSnapshot> snapshots;
        private final Set<String> childPackIds;
        private boolean retired;

        private GroupHandle(
                String id,
                Map<PackType, Optional<Pack>> parents,
                List<ResourceSnapshot> snapshots,
                Set<String> childPackIds
        ) {
            this.id = id;
            this.parents = parents;
            this.snapshots = snapshots;
            this.childPackIds = Set.copyOf(childPackIds);
        }

        private Map<PackType, Optional<Pack>> parents() {
            synchronized (ApprovedResourcePacks.this) {
                if (retired) return Map.of();
                return parents;
            }
        }

        @Override
        public void close() throws IOException {
            synchronized (ApprovedResourcePacks.this) {
                if (retired) return;
                retired = true;
                groups.remove(id, this);
            }
            closeSnapshots(snapshots, null);
        }
    }

    private static final class RootSide {
        private static LogicalSide of(PackType type) {
            return type == PackType.CLIENT_RESOURCES
                    ? LogicalSide.CLIENT
                    : LogicalSide.SERVER;
        }
    }
}
