package lib.kasuga.slp.javet.module;

import jakarta.annotation.Nullable;
import lib.kasuga.scripting.module.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Resolves an in-package script path to a concrete file inside the package's own resource root.
 *
 * <p>Every entry point funnels through {@link #locateScript(ResolvedPackage, List)}: relative
 * {@code require} calls (via {@link PackageAwareRequireResolver}), the package {@code main} entry and
 * the {@code package.toml} {@code entry.*} names (via {@code ScriptPackage.executeEntry}). The path is
 * therefore normalized here, segment-by-segment, so that a {@code ..} can never climb above the
 * package root — an out-of-bounds path resolves to nothing instead of reading a script outside the
 * package.
 */
public class JsModuleResolver implements ModuleResolver {

    @Override
    @Nullable
    public ResolvedScript locateScript(ResolvedPackage pkg, List<String> relativeSegments) {
        List<String> segments = relativeSegments.isEmpty() ? mainSegments(pkg) : relativeSegments;

        if (segments.isEmpty()) return null;

        List<String> normalized = normalizeSegments(segments);
        if (normalized == null) {
            return null; // the path climbs above the package root
        }
        if (normalized.isEmpty()) {
            // Degenerate path that normalizes back onto the package root (`./`, `sub/..`): keep the
            // existing "resolve the declared main entry" semantics.
            normalized = normalizeSegments(mainSegments(pkg));
            if (normalized == null || normalized.isEmpty()) return null;
        }

        String basePath = String.join("/", normalized);

        List<String> candidates = new ArrayList<>();
        candidates.add(basePath + ".js");
        candidates.add(basePath + "/index.js");

        for (String candidate : candidates) {
            if (pkg.packResources().exists(pkg.packRelativeRoot(), candidate)) {
                return new ResolvedScript(candidate, pkg.packResources(), new ModuleId(normalized), pkg);
            }
        }

        return null;
    }

    private static List<String> mainSegments(ResolvedPackage pkg) {
        return pkg.info().main() != null ? List.of(pkg.info().main()) : List.of();
    }

    /**
     * Splits, drops and resolves {@code relativeSegments} into a normalized segment list, or returns
     * {@code null} when a {@code ..} would pop past the package root. Each segment is split on
     * {@code /} again first: entry names ({@code package.toml} {@code entry.*} / {@code main}) arrive
     * as a <em>single</em> segment that may itself contain slashes, so checking whole segments for
     * {@code ".."} would miss them. Empty segments (including the leftover of a leading {@code /},
     * i.e. an absolute-style path) and {@code .} segments are dropped, matching
     * {@code ScopedPackResources.safeResolve}'s slash stripping.
     */
    @Nullable
    private static List<String> normalizeSegments(List<String> relativeSegments) {
        List<String> normalized = new ArrayList<>();
        for (String raw : relativeSegments) {
            for (String part : raw.split("/")) {
                if (part.isEmpty() || part.equals(".")) {
                    continue;
                }
                if (part.equals("..")) {
                    if (normalized.isEmpty()) {
                        return null; // would escape the package root
                    }
                    normalized.remove(normalized.size() - 1);
                } else {
                    normalized.add(part);
                }
            }
        }
        return normalized;
    }

    @Override
    public String getExtension() {
        return "js";
    }
}
