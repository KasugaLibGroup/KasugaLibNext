package lib.kasuga.core.resource.routing;

import com.google.gson.*;
import lib.kasuga.core.resource.identity.ResourcePaths;
import lib.kasuga.core.resource.lifecycle.LogicalSide;
import lib.kasuga.core.resource.lifecycle.ResourceEvent;
import lib.kasuga.core.resource.source.ResourceSnapshot.LocatedResourceFile;
import lib.kasuga.core.resource.view.ResourceCatalog;
import java.util.*;
import static lib.kasuga.core.resource.routing.StrictResourceJson.*;

/** Pure routing: resolve/validate the whole plan before calling any consumer. */
public final class ResourceRouting {
    public static final String MINECRAFT_SCHEMA = "minecraft_resource_schema";
    public static final String MINECRAFT_ENTRY_POINT = "kasuga:minecraft_resources";
    public record Output(String id, String entryPointId, ResourceEvent when, LogicalSide side, String targetPrefix) {
        public Output {
            requireId(id); requireId(entryPointId); Objects.requireNonNull(when); Objects.requireNonNull(side);
            ResourcePaths.path(targetPrefix, true);
            if (when == ResourceEvent.CLIENT_STARTUP && (side == LogicalSide.SERVER || side == LogicalSide.BOTH)
                    || when == ResourceEvent.SERVER_STARTUP && (side == LogicalSide.CLIENT || side == LogicalSide.BOTH))
                throw new IllegalArgumentException("UNSUPPORTED_LIFECYCLE: incompatible event/side");
        }
        public boolean matches(ResourceEvent event, LogicalSide actual) {
            return when == event && (side == LogicalSide.NONE || side == actual
                    || side == LogicalSide.BOTH && (actual == LogicalSide.CLIENT || actual == LogicalSide.SERVER));
        }
    }
    public record Template(String id, String parent, List<String> removeOutputs, List<Output> outputs) {
        public Template { requireId(id); Objects.requireNonNull(parent); removeOutputs = List.copyOf(removeOutputs); outputs = List.copyOf(outputs); }
    }
    public record Binding(String id, String sourceId, String sourcePrefix, List<Output> outputs) {
        public Binding { requireId(id); ResourcePaths.id(sourceId); ResourcePaths.path(sourcePrefix, true); outputs = List.copyOf(outputs); }
    }
    public record Route(String bindingId, Output output, LocatedResourceFile file, String targetPath) {}
    private final List<Binding> bindings;
    public ResourceRouting(List<Binding> bindings) {
        this.bindings = List.copyOf(bindings);
        Set<String> ids = new HashSet<>(), prefixes = new HashSet<>();
        for (Binding b : bindings) {
            if (!ids.add(b.id()) || !prefixes.add(b.sourceId() + "/" + b.sourcePrefix()))
                throw new IllegalArgumentException("ROUTING_CONFLICT: duplicate binding/prefix: " + b.id());
            Set<String> outputs = new HashSet<>();
            for (Output o : b.outputs()) if (!outputs.add(o.id())) throw new IllegalArgumentException("ROUTING_CONFLICT: output ID " + o.id());
        }
    }
    public List<Binding> bindings() { return bindings; }
    public static Map<String, Template> builtinTemplates() {
        return Map.of(MINECRAFT_SCHEMA, new Template(MINECRAFT_SCHEMA, "", List.of(), List.of(
                new Output("mc", MINECRAFT_ENTRY_POINT, ResourceEvent.RESOURCE_RELOAD, LogicalSide.BOTH, ""))));
    }

    public static Template decodeTemplate(String json) {
        JsonObject object = parse(json); fields(object, "format_version", "id", "extends", "remove_outputs", "outputs"); version(object);
        return new Template(string(object, "id"), optional(object, "extends", ""), removals(object), outputs(object));
    }
    public static ResourceRouting decode(String json, Map<String, Template> templates, Set<String> authorizedSources) {
        JsonObject root = parse(json); fields(root, "format_version", "bindings"); version(root);
        List<Binding> bindings = new ArrayList<>();
        // Validate the entire template registry, including templates with no current files.
        for (String id : templates.keySet()) resolve(id, templates, new LinkedHashSet<>());
        for (JsonElement element : array(root, "bindings")) {
            JsonObject binding = object(element);
            fields(binding, "id", "source_id", "source_prefix", "schema_id", "remove_outputs", "outputs");
            String source = string(binding, "source_id");
            if (!authorizedSources.contains(source)) throw new IllegalArgumentException("PERMISSION_DENIED: routing source");
            String template = optional(binding, "schema_id", "");
            List<Output> inherited = template.isEmpty() ? List.of() : resolve(template, templates, new LinkedHashSet<>());
            bindings.add(new Binding(string(binding, "id"), source, optional(binding, "source_prefix", ""),
                    merge(inherited, removals(binding), outputs(binding))));
        }
        return new ResourceRouting(bindings);
    }

    private static List<Output> resolve(String id, Map<String, Template> templates, Set<String> visiting) {
        if (!visiting.add(id) || visiting.size() > 32) throw new IllegalArgumentException("SCHEMA_CYCLE: " + id);
        Template template = templates.get(id);
        if (template == null || !template.id().equals(id)) throw new IllegalArgumentException("Unknown schema: " + id);
        List<Output> inherited = template.parent().isEmpty() ? List.of() : resolve(template.parent(), templates, visiting);
        List<Output> result = merge(inherited, template.removeOutputs(), template.outputs());
        visiting.remove(id); return result;
    }
    private static List<Output> merge(List<Output> parent, List<String> remove, List<Output> outputs) {
        LinkedHashMap<String, Output> merged = new LinkedHashMap<>(); parent.forEach(o -> merged.put(o.id(), o));
        Set<String> removed = new HashSet<>(), added = new HashSet<>();
        for (String id : remove) if (!removed.add(id) || merged.remove(id) == null) throw new IllegalArgumentException("Unknown/duplicate removed output: " + id);
        for (Output o : outputs) {
            if (!added.add(o.id()) || removed.contains(o.id())) throw new IllegalArgumentException("Conflicting output: " + o.id());
            merged.put(o.id(), o);
        }
        return List.copyOf(merged.values());
    }
    private static List<String> removals(JsonObject object) {
        List<String> result = new ArrayList<>();
        for (JsonElement value : array(object, "remove_outputs")) {
            if (!(value instanceof JsonPrimitive p) || !p.isString()) throw new IllegalArgumentException("Expected output ID");
            result.add(p.getAsString());
        }
        return result;
    }
    private static List<Output> outputs(JsonObject object) {
        List<Output> outputs = new ArrayList<>();
        for (JsonElement value : array(object, "outputs")) {
            JsonObject output = object(value); fields(output, "id", "entry_point_id", "when", "side", "target_prefix");
            outputs.add(new Output(string(output, "id"), string(output, "entry_point_id"), ResourceEvent.parse(string(output, "when")),
                    LogicalSide.parse(string(output, "side")), optional(output, "target_prefix", "")));
        }
        return outputs;
    }
    public List<Route> route(ResourceCatalog catalog, ResourceEvent event, LogicalSide side) {
        if (side == LogicalSide.BOTH) throw new IllegalArgumentException("Invocation must have a concrete logical side");
        List<Route> routes = new ArrayList<>(); Set<String> unique = new HashSet<>();
        for (LocatedResourceFile file : catalog.files()) {
            var d = file.descriptor();
            if (d.uri().childKey().isEmpty() && d.uri().path().equals("kasuga.resources.json")) continue;
            Binding owner = null;
            for (Binding binding : bindings) {
                if (binding.sourceId().equals(d.source().sourceId()) && ResourcePaths.contains(binding.sourcePrefix(), d.logicalPath())
                        && (owner == null || binding.sourcePrefix().length() > owner.sourcePrefix().length())) owner = binding;
            }
            if (owner == null) continue;
            String relative = owner.sourcePrefix().isEmpty() ? d.logicalPath()
                    : d.logicalPath().equals(owner.sourcePrefix()) ? "" : d.logicalPath().substring(owner.sourcePrefix().length() + 1);
            for (Output output : owner.outputs()) {
                if (!output.matches(event, side)) continue; // Structural ownership is already decided. No parent fallback.
                if (output.entryPointId().equals(MINECRAFT_ENTRY_POINT)
                        && d.source().origin() == lib.kasuga.core.resource.source.ResourceSourceDescriptor.Origin.MC_MANAGED)
                    throw new IllegalArgumentException("PROJECTION_CYCLE: MC-managed input cannot be projected back");
                String target = ResourcePaths.path(ResourcePaths.join(output.targetPrefix(), relative), false);
                String key = d.uri() + "|" + output.entryPointId() + "|" + target;
                if (!unique.add(key)) throw new IllegalArgumentException("ROUTING_CONFLICT: duplicate output: " + target);
                routes.add(new Route(owner.id(), output, file, target));
            }
        }
        routes.sort(Comparator.comparing((Route r) -> r.output().entryPointId()).thenComparing(Route::targetPath)
                .thenComparing(Route::file, ResourceCatalog.ORDER).thenComparing(Route::bindingId).thenComparing(r -> r.output().id()));
        return List.copyOf(routes);
    }
    private static void requireId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_.:-]{1,160}")) throw new IllegalArgumentException("Invalid routing ID");
    }
}
