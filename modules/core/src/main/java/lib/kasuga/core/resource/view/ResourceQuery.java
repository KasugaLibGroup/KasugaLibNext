package lib.kasuga.core.resource.view;

import lib.kasuga.core.resource.identity.ResourcePaths;
import java.util.Set;

public record ResourceQuery(String prefix, String type, String subtype, Set<String> tags,
                            Set<String> sources, Set<String> owners, boolean allProviders, int offset, int limit) {
    public ResourceQuery {
        ResourcePaths.path(prefix, true);
        if (type == null || subtype == null || offset < 0 || limit < 1 || limit > 10_000)
            throw new IllegalArgumentException("Invalid query or query budget");
        tags = Set.copyOf(tags); sources = Set.copyOf(sources); owners = Set.copyOf(owners);
    }
    public static ResourceQuery all(int limit) { return new ResourceQuery("", "", "", Set.of(), Set.of(), Set.of(), true, 0, limit); }
}
