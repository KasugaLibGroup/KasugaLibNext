package lib.kasuga.scripting.module;

import io.micronaut.context.annotation.Context;

import java.util.HashMap;
import java.util.Map;

@Context()
public class PackageRegistry {

    private final Map<String, ResolvedPackage> packages = new HashMap<>();

    public synchronized void register(ResolvedPackage pkg) {
        String name = pkg.info().name();
        if (packages.containsKey(name)) {
            throw new DuplicatePackageException(name, packages.get(name).packResources().getName());
        }
        packages.put(name, pkg);
    }

    public synchronized ResolvedPackage lookup(String packageName) {
        return packages.get(packageName);
    }

    public synchronized Map<String, ResolvedPackage> all() {
        return Map.copyOf(packages);
    }

    public synchronized void clear() {
        packages.clear();
    }
}
