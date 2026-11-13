package lib.kasuga.scripting.resource;

import lib.kasuga.core.resource.lifecycle.*;
import lib.kasuga.core.resource.pack.CapturedScopedPackResources;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.script.ScriptBlockRef;
import lib.kasuga.core.resource.view.ResourceDescriptor;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.discovery.*;
import lib.kasuga.scripting.module.ResolvedPackage;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.*;

/** Opt-in script packages from routed, leased files. Does not share legacy global package registries. */
public final class ManagedScriptEntryPoint implements ResourceEntryPoint {
    public static final String ID = "kasuga:script_blocks";
    private final Function<String, ScriptEngineType<?>> engines;
    private final Predicate<ResourceDescriptor> trusted;
    private final Predicate<ScriptBlockRef> authorized;
    @FunctionalInterface public interface AccessFactory {
        lib.kasuga.core.resource.view.ResourceAccess create(ResourceBatch batch, InvocationContext context, lib.kasuga.core.resource.identity.ResourceUri scriptPackage);
    }
    private final AccessFactory access;
    public ManagedScriptEntryPoint(Function<String, ScriptEngineType<?>> engines, Predicate<ResourceDescriptor> trusted, Predicate<ScriptBlockRef> authorized) {
        this(engines, trusted, authorized, (batch, context, identity) -> null);
    }
    public ManagedScriptEntryPoint(Function<String, ScriptEngineType<?>> engines, Predicate<ResourceDescriptor> trusted, Predicate<ScriptBlockRef> authorized, AccessFactory access) {
        this.engines = engines; this.trusted = trusted; this.authorized = authorized; this.access = access;
    }
    @Override public EntryPointDescriptor descriptor() {
        return new EntryPointDescriptor(ID, Set.of(), Set.of(ResourceEvent.RESOURCE_RELOAD, ResourceEvent.CLIENT_STARTUP, ResourceEvent.SERVER_STARTUP),
                Set.of(LogicalSide.CLIENT, LogicalSide.SERVER, LogicalSide.NONE), Set.of(), true);
    }
    @Override public CompletionStage<? extends PreparedConsumerBatch> prepare(ResourceBatch batch, InvocationContext context) {
        var registry = new ScriptBlockRegistry(context.scopeId(), context.generation(), context.side(), authorized);
        registry.resourceAccess(identity -> access.create(batch, context, identity));
        var threads = new ScriptThreadGroup("resources-" + context.scopeId() + "-" + context.generation(), null);
        List<CapturedScopedPackResources> packs = new ArrayList<>(); AtomicBoolean stopped = new AtomicBoolean();
        Runnable stop = () -> {
            if (!stopped.compareAndSet(false, true)) return;
            registry.close();
            threads.shutdown().whenComplete((ignored, failure) -> packs.forEach(CapturedScopedPackResources::close));
        };
        try {
            Map<String, List<ResourceRouting.Route>> roots = new LinkedHashMap<>();
            for (var route : batch.routes()) {
                var uri = route.file().descriptor().uri();
                roots.computeIfAbsent(uri.sourceId() + "/" + uri.rootId(), ignored -> new ArrayList<>()).add(route);
            }
            record Startup(ScriptPackage pkg, lib.kasuga.core.resource.identity.ResourceUri identity, boolean trusted) {}
            List<Startup> startups = new ArrayList<>();
            for (var root : roots.entrySet()) {
                var pack = new CapturedScopedPackResources(root.getKey(), root.getValue()); packs.add(pack);
                for (var route : root.getValue()) {
                    if (!route.targetPath().equals("package.toml") && !route.targetPath().endsWith("/package.toml")) continue;
                    String directory = route.targetPath().equals("package.toml") ? "" : route.targetPath().substring(0, route.targetPath().length() - 13);
                    PackageInfo info = new PackageSystem().readPackageInfo(pack, directory);
                    ScriptEngineType<?> engine = engines.apply(info.engine());
                    if (engine == null || !engine.isAvailable()) throw new IllegalArgumentException("Script engine unavailable");
                    boolean approved = root.getValue().stream().allMatch(r -> trusted.test(r.file().descriptor()));
                    ResolvedPackage resolved = new ResolvedPackage(info, pack, directory, engine);
                    registry.modulePackages().register(resolved);
                    startups.add(new Startup(new ScriptPackage(resolved), route.file().descriptor().uri(), approved));
                }
            }
            for (Startup startup : startups) startup.pkg().startManaged(threads, registry, pkg -> startup.identity(), context.side(), startup.trusted());
            return registry.preparation().toCompletableFuture().orTimeout(10, TimeUnit.SECONDS).thenApply(ignored -> (PreparedConsumerBatch) new PreparedConsumerBatch() {
                @Override public Object value() { return registry; }
                @Override public void retire() { stop.run(); }
                @Override public void close() { stop.run(); }
            }).whenComplete((value, error) -> { if (error != null) stop.run(); });
        } catch (RuntimeException error) { stop.run(); return CompletableFuture.failedFuture(error); }
    }
}
