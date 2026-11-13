package lib.kasuga.core.resource.lifecycle;

import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.view.ResourceCatalog;
import java.util.List;

public record ResourceBatch(ResourceCatalog catalog, List<ResourceRouting.Route> routes) {
    public ResourceBatch { routes = List.copyOf(routes); }
}
