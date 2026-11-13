package lib.kasuga.scripting.discovery;

import com.electronwill.nightconfig.core.CommentedConfig;
import com.electronwill.nightconfig.toml.TomlParser;
import com.mojang.logging.LogUtils;
import io.micronaut.context.annotation.Context;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import lib.kasuga.core.resource.pack.HierarchicalScopedPackResources;
import lib.kasuga.core.resource.pack.ScopedPackResources;
import lib.kasuga.core.vfs.VfsInvalidPathException;
import lib.kasuga.scripting.ScriptEngineRegistry;
import lib.kasuga.scripting.ScriptEngineType;
import lib.kasuga.scripting.module.DuplicatePackageException;
import lib.kasuga.scripting.module.PackageRegistry;
import lib.kasuga.scripting.module.ResolvedPackage;
import lombok.Getter;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Context()
public class PackageSystem {

    private static final Logger LOGGER = LogUtils.getLogger();

    @Inject()
    ScriptEngineRegistry engineRegistry;

    @Inject()
    PackageRegistry packageRegistry;

    protected final List<PackageLoadingError> loadingErrors = new ArrayList<>();

    @Getter
    protected final Map<String, List<Throwable>> engineErrors = new ConcurrentHashMap<>();

    protected final TomlParser scriptInfoParser = new TomlParser();

    protected final Set<String> engineMissing = new HashSet<>();


    public void init() {
        synchronized (loadingErrors) { loadingErrors.clear(); }
        engineErrors.clear();
        synchronized (engineMissing) { engineMissing.clear(); }
    }

    public boolean hasLoadingErrors() {
        synchronized (loadingErrors) {
            return !loadingErrors.isEmpty();
        }
    }

    public List<PackageLoadingError> getLoadingErrors() {
        synchronized (loadingErrors) {
            return List.copyOf(loadingErrors);
        }
    }

    public void addLoadingError(PackageLoadingError error) {
        synchronized (loadingErrors) {
            loadingErrors.add(error);
        }
    }

    @Nullable
    public PackageInfo readPackageInfo(ScopedPackResources spr, String packRelativeRoot) {
        if (!spr.exists(packRelativeRoot, "package.toml"))
            return null;
        CommentedConfig config;
        try (var input = spr.open(packRelativeRoot, "package.toml");
             var reader = new InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8)) {
            // Managed resource generations can prepare independently from the legacy runtime scan.
            config = new TomlParser().parse(reader);
        } catch (IOException e) {
            throw new PackageLoadingError(e);
        }

        return new PackageInfo(
                config.get("name"),
                config.get("engine"),
                config.get("description"),
                config.get("version"),
                config.get("main"),
                formatEngineObj(config.get("workspaces")),
                new PackageInfo.EntryConfig(
                        formatEngineObj(config.get("entry.server")),
                        formatEngineObj(config.get("entry.client")),
                        formatEngineObj(config.get("entry.common"))
                )
        );
    }

    public List<ResolvedPackage> scan(ScopedPackResources packResource) {
        List<ResolvedPackage> result = new ArrayList<>();

        ResolvedPackage rootPkg = tryResolve(packResource, "scripts");
        if (rootPkg == null) return result;
        result.add(rootPkg);

        for (String workspaceGlob : rootPkg.info().workspaces()) {
            List<ResolvedPackage> subPackages = scanWorkspace(packResource, "scripts", workspaceGlob);
            result.addAll(subPackages);
        }

        return result;
    }

    private List<ResolvedPackage> scanWorkspace(ScopedPackResources packResource, String prefix, String workspaceGlob) {
        List<ResolvedPackage> result = new ArrayList<>();

        if (!(packResource instanceof HierarchicalScopedPackResources hierarchical)) {
            return result;
        }

        String globPattern = workspaceGlob.endsWith("/*")
            ? workspaceGlob.substring(0, workspaceGlob.length() - 2)
            : workspaceGlob;

        try {
            globPattern = ScopedPackResources.toLogicalPath(globPattern).asString();
        } catch (VfsInvalidPathException e) {
            addLoadingError(new PackageLoadingError(e));
            return result;
        }

        try {
            List<String> entries = hierarchical.list(prefix, globPattern);
            for (String entry : entries) {
                String subPath = globPattern.isEmpty() ? entry : globPattern + "/" + entry;
                if (!hierarchical.isDirectory(prefix, subPath)) continue;

                ResolvedPackage subPkg = tryResolve(packResource, prefix + "/" + subPath);
                if (subPkg != null) {
                    result.add(subPkg);
                }
            }
        } catch (IOException e) {
            addLoadingError(new PackageLoadingError(e));
        }

        return result;
    }

    @Nullable
    private ResolvedPackage tryResolve(ScopedPackResources packResource, String packRelativeRoot) {
        PackageInfo info = readPackageInfo(packResource, packRelativeRoot);
        if (info == null) return null;

        LOGGER.info("[PackageSystem] Resolving package at '{}': name={}, engine={}", packRelativeRoot, info.name(), info.engine());

        ScriptEngineType<?> engine = resolveEngine(info.engine());
        if (engine == null) {
            LOGGER.warn("[PackageSystem] Engine '{}' not available for package at '{}'", info.engine(), packRelativeRoot);
            return null;
        }

        ResolvedPackage pkg = new ResolvedPackage(info, packResource, packRelativeRoot, engine);
        try {
            packageRegistry.register(pkg);
        } catch (DuplicatePackageException e) {
            addLoadingError(new PackageLoadingError(e));
            return null;
        }
        LOGGER.info("[PackageSystem] Resolved package '{}' at '{}'", info.name(), packRelativeRoot);
        return pkg;
    }

    @Nullable
    private ScriptEngineType<?> resolveEngine(@Nullable String engineName) {
        if (engineName == null) return null;
        ScriptEngineType<?> type = engineRegistry.resolve(engineName, true);
        if (type == null) {
            synchronized (engineMissing) { engineMissing.add(engineName); }
        } else if (!type.loadingIssues.isEmpty()) {
            engineErrors.put(engineName, List.copyOf(type.loadingIssues));
            synchronized (engineMissing) { engineMissing.add(engineName); }
            return null;
        }
        return type;
    }

    public Set<String> getMissingEngines() {
        synchronized (engineMissing) {
            return Set.copyOf(engineMissing);
        }
    }

    protected static List<String> formatEngineObj(Object origin) {
        if(origin == null)
            return List.of();
        if(origin instanceof String str)
            return List.of(str);
        if(origin instanceof Collection<?> coll) {
            return coll.stream().map(PackageSystem::formatEngineObj).flatMap(Collection::stream).toList();
        }
        return List.of(origin.toString());
    }
}
