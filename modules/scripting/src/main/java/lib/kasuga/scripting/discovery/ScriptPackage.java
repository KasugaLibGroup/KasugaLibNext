package lib.kasuga.scripting.discovery;

import com.mojang.logging.LogUtils;
import jakarta.annotation.Nullable;
import lib.kasuga.scripting.ScriptConsole;
import lib.kasuga.scripting.ScriptEngine;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.ScriptException;
import lib.kasuga.scripting.module.ResolvedPackage;
import lib.kasuga.scripting.module.ResolvedScript;
import lib.kasuga.core.resource.identity.ResourceUri;
import lib.kasuga.core.resource.lifecycle.LogicalSide;
import lib.kasuga.scripting.resource.ScriptBlockRegistry;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicBoolean;

public class ScriptPackage {

    private static final Logger LOGGER = LogUtils.getLogger();

    @lombok.Getter
    private final ResolvedPackage resolved;

    private final List<ScriptPackage> children = new ArrayList<>();

    @lombok.Getter
    private ScriptThreadGroup threadGroup;

    public ScriptPackage(ResolvedPackage resolved) {
        this.resolved = resolved;
    }

    public ScriptPackage() {
        this.resolved = null;
    }

    public boolean isRunnable() {
        return resolved != null;
    }

    @Nullable
    public PackageInfo info() {
        return resolved == null ? null : resolved.info();
    }

    @Nullable
    public ScriptEngineType<?> engine() {
        return resolved == null ? null : resolved.engine();
    }

    public void addChild(ScriptPackage child) {
        children.add(child);
    }

    public List<ScriptPackage> children() {
        return children;
    }

    public void start(ScriptThreadGroup parentGroup) {
        startInternal(parentGroup, null);
    }

    /** Opt-in logical-side startup. Legacy start() retains its physical-side contract. */
    public void startManaged(ScriptThreadGroup parentGroup, ScriptBlockRegistry registry,
                             Function<ScriptPackage, ResourceUri> identities, LogicalSide side, boolean explicitlyTrusted) {
        if (side == LogicalSide.BOTH) throw new IllegalArgumentException("Concrete logical script side required");
        startInternal(parentGroup, new ManagedStart(registry, identities, side, explicitlyTrusted));
    }

    private record ManagedStart(ScriptBlockRegistry registry, Function<ScriptPackage, ResourceUri> identities,
                                LogicalSide side, boolean trusted) {}

    private void startInternal(ScriptThreadGroup parentGroup, @Nullable ManagedStart managed) {
        if (!isRunnable()) {
            for (ScriptPackage child : children) {
                if (!child.isRunnable()) continue;
                child.startInternal(parentGroup, managed);
            }
            return;
        }
        String groupName = resolved.info().name() != null
                ? resolved.info().name()
                : "anonymous-" + resolved.packRelativeRoot();
        LOGGER.info("[ScriptPackage] Starting package '{}'", groupName);
        this.threadGroup = parentGroup.createChild(groupName);

        ScriptConsole console = new ScriptConsole() {
            @Override public void log(String message) { LOGGER.info("[{}] {}", groupName, message); }
            @Override public void warn(String message) { LOGGER.warn("[{}] {}", groupName, message); }
            @Override public void debug(String message) { LOGGER.debug("[{}] {}", groupName, message); }
            @Override public void info(String message) { LOGGER.info("[{}] {}", groupName, message); }
            @Override public void error(String message) { LOGGER.error("[{}] {}", groupName, message); }
        };

        ScriptEngine engineInstance;
        try {
            engineInstance = managed == null ? resolved.engine().create(console)
                    : resolved.engine().create(console, managed.registry().modulePackages());
        } catch (ScriptException e) {
            LOGGER.error("Failed to create engine for package '{}'", groupName, e);
            if (managed != null) throw new IllegalStateException("Script engine preparation failed", e);
            return;
        }
        ScriptThread thread;
        try { thread = threadGroup.createThread(groupName, engineInstance); }
        catch (RuntimeException failure) {
            try { engineInstance.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        ScriptBlockRegistry.PackageSession session;
        try {
            session = managed == null ? null : managed.registry().attach(this, thread, engineInstance,
                    managed.identities().apply(this), managed.trusted(), 1024);
        } catch (RuntimeException failure) { thread.shutdown(); throw failure; }
        AtomicBoolean failed = new AtomicBoolean();
        if (session != null) thread.recordCall(() -> {
            try { session.install(); } catch (RuntimeException error) { failed.set(true); session.failPreparation(error); }
        });

        // Physical-side filtering: common entries run on both sides; server entries only on a dedicated
        // server, client entries only on the client. Without this, a server entry would also run in the
        // client engine (and vice versa). (For integrated-server logic that must run on a physical client
        // too, use a common entry.)
        List<String> entriesToRun = new ArrayList<>(resolved.info().entry().common());
        if (managed != null && managed.side() == LogicalSide.SERVER || managed == null && FMLEnvironment.dist == Dist.DEDICATED_SERVER) {
            entriesToRun.addAll(resolved.info().entry().server());
        } else if (managed != null && managed.side() == LogicalSide.CLIENT || managed == null && FMLEnvironment.dist == Dist.CLIENT) {
            entriesToRun.addAll(resolved.info().entry().client());
        }
        for (String entry : entriesToRun) {
            LOGGER.info("[ScriptPackage] Scheduling entry '{}' for package '{}'", entry, groupName);
            thread.recordCall(() -> {
                if (failed.get()) return;
                if (!executeEntry(engineInstance, entry) && session != null) {
                    failed.set(true); session.failPreparation(new IllegalStateException("Script entry failed: " + entry));
                }
            });
        }
        if (session != null) thread.recordCall(() -> { if (!failed.get()) session.markReady(); });

        for (ScriptPackage child : children) {
            if (!child.isRunnable()) continue;
            child.startInternal(threadGroup, managed);
        }
    }

    private boolean executeEntry(ScriptEngine engine, String entryName) {
        try {
            LOGGER.info("[ScriptPackage] Executing entry '{}' in package '{}'", entryName, resolved.info().name());
            ResolvedScript script = resolved.engine().resolver.locateScript(resolved, List.of(entryName));
            if(script == null) {
                LOGGER.error("[ScriptPackage] Entry script '{}' not found in package '{}'", entryName, resolved.info().name());
                return false;
            }
            try (InputStream is = script.open()) {
                // Pass the owning package so the require resolver can scope the entry's relative requires.
                engine.executeEntry(entryName, is, resolved);
                LOGGER.info("[ScriptPackage] Entry '{}' executed successfully in package '{}'", entryName, resolved.info().name());
                return true;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to execute entry '{}' in package '{}'", entryName, resolved.info().name(), e);
            return false;
        }
    }
}
