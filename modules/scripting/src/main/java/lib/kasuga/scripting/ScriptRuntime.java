package lib.kasuga.scripting;

import com.mojang.logging.LogUtils;
import jakarta.inject.Inject;
import lib.kasuga.core.resource.ScopedResourceManager;
import lib.kasuga.core.resource.ScopedResourcePackListener;
import lib.kasuga.core.resource.pack.ScopedPackResources;
import lib.kasuga.scripting.discovery.PackageLoadingError;
import lib.kasuga.scripting.discovery.PackageSystem;
import lib.kasuga.scripting.discovery.ScriptPackage;
import lib.kasuga.scripting.discovery.ScriptThreadGroup;
import lib.kasuga.scripting.module.PackageRegistry;
import lib.kasuga.scripting.module.ResolvedPackage;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ScriptRuntime implements ScopedResourcePackListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private final ScopedResourceManager resourceManager;
    private volatile ScriptThreadGroup rootThreadGroup;
    private ScriptPackage rootPackage;
    private long reloadSequence;
    private boolean closed;
    private CompletableFuture<Void> reloadTail = CompletableFuture.completedFuture(null);

    @Inject()
    PackageSystem packageSystem;

    @Inject()
    PackageRegistry packageRegistry;

    public ScriptRuntime(@Nullable MinecraftServer server, ScopedResourceManager resourceManager) {
        this.resourceManager = resourceManager;
    }

    public void close() {
        ScriptThreadGroup old;
        synchronized (this) { closed = true; reloadSequence++; old = rootThreadGroup; rootThreadGroup = null; }
        if (old != null) old.shutdown();
    }

    @Override
    public void onReloaded(ScopedResourceManager resourceManager) {
        List<ScopedPackResources> packs = List.copyOf(resourceManager.getPackResources());
        CompletableFuture<Void> scheduled;
        synchronized (this) {
            if (closed) return;
            long requested = ++reloadSequence;
            ScriptThreadGroup old = rootThreadGroup; rootThreadGroup = null;
            CompletableFuture<Void> stopped = old == null ? CompletableFuture.completedFuture(null) : old.shutdown();
            // Serialize scans too: a superseded scan must not clear a newer scan's package registry.
            scheduled = reloadTail.handle((ignored, failure) -> null).thenCompose(ignored -> stopped)
                    .thenRunAsync(() -> {
                        synchronized (this) { if (closed || reloadSequence != requested) return; }
                        reloadLegacy(packs, requested);
                    });
            reloadTail = scheduled;
        }
        // Observe a timeout without completing the real shutdown barrier: a stuck old engine cannot
        // make the next reload overlap it, and the game thread never waits for that engine.
        scheduled.copy().orTimeout(5, TimeUnit.SECONDS).exceptionally(failure -> {
            LOGGER.error("Legacy script reload failed or is still waiting for engine shutdown", failure); return null;
        });
    }

    private void reloadLegacy(List<ScopedPackResources> packs, long requested) {
        // Legacy runtimes still share PackageSystem; serialize its mutable scan state off game threads.
        synchronized (packageSystem) { reloadLegacyExclusive(packs, requested); }
    }

    private void reloadLegacyExclusive(List<ScopedPackResources> packs, long requested) {
        packageSystem.init();
        packageRegistry.clear();

        ScriptThreadGroup newGroup = new ScriptThreadGroup("ScriptRuntime", null);
        ScriptPackage newPackage = new ScriptPackage();

        for (ScopedPackResources packResource : packs) {
            try {
                List<ResolvedPackage> discovered = packageSystem.scan(packResource);
                LOGGER.info("[ScriptRuntime] Scanned pack '{}': found {} packages", packResource.getName(), discovered.size());
                for (ResolvedPackage pkg : discovered) {
                    LOGGER.info("[ScriptRuntime] Discovered package '{}' (engine={})", pkg.info().name(), pkg.info().engine());
                    ScriptPackage scriptPkg = new ScriptPackage(pkg);
                    attachToParent(newPackage, scriptPkg);
                }
            } catch (PackageLoadingError error) {
                packageSystem.addLoadingError(error);
                LOGGER.error("Failed to load script package from resource pack: " + packResource.getName(), error);
            }
        }

        synchronized (this) {
            if (closed || reloadSequence != requested) { newGroup.shutdown(); return; }
            rootPackage = newPackage; rootThreadGroup = newGroup;
        }
        try { newPackage.start(newGroup); }
        catch (RuntimeException failure) {
            synchronized (this) { if (rootThreadGroup == newGroup) rootThreadGroup = null; }
            newGroup.shutdown(); throw failure;
        }
    }

    private void attachToParent(ScriptPackage root, ScriptPackage candidate) {
        if (candidate.getResolved() == null) {
            root.addChild(candidate);
            return;
        }

        String candidateRoot = candidate.getResolved().packRelativeRoot();
        for (ScriptPackage child : root.children()) {
            if (child.getResolved() == null) continue;
            String childRoot = child.getResolved().packRelativeRoot();
            if (candidateRoot.startsWith(childRoot + "/")) {
                attachToParent(child, candidate);
                return;
            }
        }

        root.addChild(candidate);
    }

    public void tick() {
        ScriptThreadGroup group = rootThreadGroup;
        if (group != null) group.dispatchTick();
    }
}
