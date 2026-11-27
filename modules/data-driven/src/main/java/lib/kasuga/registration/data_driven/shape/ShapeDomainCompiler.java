package lib.kasuga.registration.data_driven.shape;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import lib.kasuga.geometry.Box;
import lib.kasuga.geometry.GeometryBudget;
import lib.kasuga.geometry.GeometryException;
import lib.kasuga.geometry.Shape;
import lib.kasuga.geometry.Transform;
import lib.kasuga.registration.data_driven.pipeline.ArtifactPreparationContext;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.JsonPointers;
import lib.kasuga.registration.data_driven.pipeline.LocatedDefinition;
import lib.kasuga.registration.data_driven.validation.ValidationContext;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiles the shapes DSL into the immutable geometry module representation.
 *
 * <p>The compiler intentionally runs before apply. Shape references compile the target's selected
 * output in the target definition's scope, so caller-local node names can never leak into another
 * shape.</p>
 */
public final class ShapeDomainCompiler {
    public static final String TYPE_NAME = "shapes";
    public static final int MAX_EXPRESSION_DEPTH = 64;
    public static final int MAX_OPERATION_ARGS = 64;

    private ShapeDomainCompiler() {
    }

    public static ShapeCatalog compileStrict(
            List<? extends LocatedDefinition<?>> definitions,
            ArtifactPreparationContext contexts
    ) {
        List<Unit> units = new ArrayList<>(definitions.size());
        for (LocatedDefinition<?> definition : definitions) {
            if (!(definition.definition() instanceof ShapeDefinition shape)) {
                throw new IllegalArgumentException(
                        "The shapes handler received a non-shape definition");
            }
            ValidationContext context = contexts.validationFor(definition);
            units.add(new Unit(
                    definition.key() == null ? null : definition.key().id(),
                    shape,
                    context,
                    definition.jsonPointer(),
                    definition.source().ownerId(),
                    definition.source().namespaces()
            ));
        }
        return compile(units, false, List.of());
    }

    public static ShapeCatalog compileLegacy(
            String ownerId,
            Collection<? extends ShapeDefinition> definitions
    ) {
        List<String> failures = new ArrayList<>();
        List<Unit> units = new ArrayList<>(definitions.size());
        int order = 0;
        for (ShapeDefinition definition : definitions) {
            Object id = rawString(definition.get("id"));
            units.add(new Unit(
                    id instanceof String value && !value.isBlank() ? value : null,
                    definition,
                    null,
                    "/shapes/" + order++,
                    ownerId,
                    Set.of(ownerId)
            ));
        }
        ShapeCatalog catalog = compile(units, true, failures);
        if (!failures.isEmpty()) {
            throw new ShapeCompilationException(failures);
        }
        return catalog;
    }

    private static ShapeCatalog compile(
            List<Unit> units,
            boolean legacy,
            List<String> legacyFailures
    ) {
        Engine engine = new Engine(units, legacy, legacyFailures);
        return engine.compile();
    }

    private static Object rawString(JsonElement element) {
        if (element == null || !element.isJsonPrimitive()) return null;
        JsonPrimitive primitive = element.getAsJsonPrimitive();
        return primitive.isString() ? primitive.getAsString() : null;
    }

    private record Unit(
            String id,
            ShapeDefinition definition,
            ValidationContext context,
            String pointer,
            String owner,
            Set<String> namespaces
    ) {
        String displayId() {
            return id == null ? "<missing id>" : id;
        }
    }

    private record NodeValue(JsonElement value, Unit origin, String pointer) {
    }

    private record Effective(
            Map<String, NodeValue> nodes,
            String output,
            Unit outputOrigin,
            String outputPointer
    ) {
    }

    private enum Visit {
        VISITING,
        DONE
    }

    private static final class Engine {
        private final List<Unit> units;
        private final Map<String, Unit> byId = new LinkedHashMap<>();
        private final Map<Unit, Visit> validation = new IdentityHashMap<>();
        private final Map<Unit, Effective> effective = new IdentityHashMap<>();
        private final Map<Unit, Map<String, Shape>> localResults = new IdentityHashMap<>();
        private final Set<Unit> compilingDefinitions =
                Collections.newSetFromMap(new IdentityHashMap<>());
        private final Map<String, ShapeCatalog.Entry> compiled = new LinkedHashMap<>();
        private final Set<String> reportedCycles = new LinkedHashSet<>();
        private final boolean legacy;
        private final List<String> failures;
        private final GeometryBudget budget = GeometryBudget.standard();

        private Engine(List<Unit> units, boolean legacy, List<String> legacyFailures) {
            this.units = List.copyOf(units);
            this.legacy = legacy;
            this.failures = legacyFailures;
            for (Unit unit : units) {
                if (unit.id() != null) {
                    Unit prior = byId.putIfAbsent(unit.id(), unit);
                    if (prior != null) {
                        error(unit, "", BuildDiagnostic.Code.CONTENT_INVALID,
                                "Duplicate selected shape id '" + unit.id() + "'",
                                "Keep exactly one winning definition for this id.");
                    }
                }
            }
        }

        ShapeCatalog compile() {
            for (Unit unit : units) {
                validate(unit);
            }
            validateDefinitionCycles();
            for (Unit unit : units) {
                compileDefinition(unit);
            }
            return new ShapeCatalog(compiled);
        }

        private Effective validate(Unit unit) {
            Visit state = validation.get(unit);
            if (state == Visit.DONE) return effective.get(unit);
            if (state == Visit.VISITING) {
                error(unit, "/parent", BuildDiagnostic.Code.GROUP_CYCLE,
                        "Shape parent cycle includes " + unit.displayId(),
                        "Remove the parent cycle.");
                return invalid(unit);
            }
            validation.put(unit, Visit.VISITING);

            JsonObject json = unit.definition().json();
            for (String key : json.keySet()) {
                if (!Set.of("id", "parent", "nodes", "output").contains(key)) {
                    error(unit, "/" + JsonPointers.escape(key), BuildDiagnostic.Code.CONTENT_INVALID,
                            "Unknown shape definition field '" + key + "'",
                            "Use only id, parent, nodes, and output.");
                }
            }

            validateId(unit);
            Unit parent = null;
            if (json.has("parent")) {
                String parentId = requireResourceId(unit, "/parent", json.get("parent"), "parent");
                if (parentId != null) {
                    parent = resolve(unit, parentId, "/parent");
                    if (parent != null) validate(parent);
                }
            }

            Map<String, NodeValue> nodes = new LinkedHashMap<>();
            String output = null;
            Unit outputOrigin = null;
            String outputPointer = "/output";

            if (parent != null && validation.get(parent) == Visit.DONE) {
                Effective inherited = effective.get(parent);
                nodes.putAll(inherited.nodes());
                output = inherited.output();
                outputOrigin = inherited.outputOrigin();
                outputPointer = inherited.outputPointer();
            }

            if (json.has("nodes")) {
                JsonElement declaredNodes = json.get("nodes");
                if (!declaredNodes.isJsonObject()) {
                    error(unit, "/nodes", BuildDiagnostic.Code.CONTENT_INVALID,
                            "Shape nodes must be an object",
                            "Map each node name to an expression.");
                } else {
                    for (Map.Entry<String, JsonElement> entry
                            : declaredNodes.getAsJsonObject().entrySet()) {
                        String name = entry.getKey();
                        String pointer = "/nodes/" + JsonPointers.escape(name);
                        if (name.isEmpty()) {
                            error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                                    "Shape node names must not be empty",
                                    "Choose a non-empty local node name.");
                            continue;
                        }
                        scanExpression(entry.getValue(), unit, pointer, 0);
                        nodes.put(name, new NodeValue(entry.getValue(), unit, pointer));
                    }
                }
            } else if (parent == null) {
                error(unit, "/nodes", BuildDiagnostic.Code.CONTENT_INVALID,
                        "A root shape definition requires 'nodes'",
                        "Add a nodes object or declare a parent.");
            }

            if (json.has("output")) {
                String selected = requireResourceName(unit, "/output", json.get("output"), "output");
                if (selected != null) {
                    output = selected;
                    outputOrigin = unit;
                    outputPointer = "/output";
                }
            } else if (parent == null) {
                error(unit, "/output", BuildDiagnostic.Code.CONTENT_INVALID,
                        "A root shape definition requires 'output'",
                        "Select one of the effective node names.");
            }

            Effective result = new Effective(
                    Collections.unmodifiableMap(nodes),
                    output,
                    outputOrigin,
                    outputPointer
            );
            effective.put(unit, result);
            validation.put(unit, Visit.DONE);
            validateNodeGraph(unit, result);
            return result;
        }

        private Effective invalid(Unit unit) {
            Effective value = new Effective(Map.of(), null, unit, "/output");
            effective.put(unit, value);
            validation.put(unit, Visit.DONE);
            return value;
        }

        private void validateId(Unit unit) {
            JsonElement idElement = unit.definition().get("id");
            if (idElement == null) {
                error(unit, "/id", BuildDiagnostic.Code.INVALID_IDENTITY,
                        "Shape definition requires an explicit namespace-qualified id",
                        "Write the id as '<namespace>:<path>'.");
                return;
            }
            String id = requireResourceId(unit, "/id", idElement, "id");
            if (id == null) return;
            String namespace = id.substring(0, id.indexOf(':'));
            if (!unit.namespaces().contains(namespace)) {
                error(unit, "/id", BuildDiagnostic.Code.INVALID_IDENTITY,
                        "Shape id namespace '" + namespace + "' is not declared by its source "
                                + unit.namespaces(),
                        "Use a namespace declared by this source.");
            }
        }

        private void validateNodeGraph(Unit unit, Effective effectiveValue) {
            if (effectiveValue.output() != null
                    && !effectiveValue.nodes().containsKey(effectiveValue.output())) {
                error(effectiveValue.outputOrigin(), effectiveValue.outputPointer(),
                        BuildDiagnostic.Code.MISSING_REFERENCE,
                        "Shape output node '" + effectiveValue.output() + "' does not exist in the "
                                + "effective node table for '" + unit.displayId() + "'",
                        "Use a node declared by this definition or its parent.");
            }

            for (Map.Entry<String, NodeValue> entry : effectiveValue.nodes().entrySet()) {
                for (String local : collectLocalReferences(entry.getValue().value())) {
                    if (!effectiveValue.nodes().containsKey(local)) {
                        error(entry.getValue().origin(), entry.getValue().pointer(),
                                BuildDiagnostic.Code.MISSING_REFERENCE,
                                "Local shape node '" + local + "' does not exist in the effective graph "
                                        + "for '" + unit.displayId() + "'",
                                "Add the node or use a ShapeRef for an external definition.");
                    }
                }
            }
            detectLocalCycles(unit, effectiveValue);
        }

        private void detectLocalCycles(Unit unit, Effective effectiveValue) {
            Map<String, Visit> states = new LinkedHashMap<>();
            List<String> path = new ArrayList<>();
            Set<String> cycles = new LinkedHashSet<>();
            for (String node : effectiveValue.nodes().keySet()) {
                visitLocal(unit, effectiveValue, node, states, path, cycles);
            }
            for (String cycle : cycles) {
                NodeValue origin = effectiveValue.nodes().get(cycle.split(" -> ", 2)[0]);
                error(origin == null ? unit : origin.origin(),
                        origin == null ? "/nodes" : origin.pointer(),
                        BuildDiagnostic.Code.GROUP_CYCLE,
                        "Local node cycle in shape '" + unit.displayId() + "': " + cycle,
                        "Break the local node reference cycle.");
            }
        }

        private void visitLocal(
                Unit unit,
                Effective value,
                String node,
                Map<String, Visit> states,
                List<String> path,
                Set<String> cycles
        ) {
            Visit state = states.get(node);
            if (state == Visit.DONE) return;
            if (state == Visit.VISITING) {
                int start = path.indexOf(node);
                List<String> cycle = new ArrayList<>(
                        start < 0 ? path : path.subList(start, path.size()));
                cycle.add(node);
                cycles.add(String.join(" -> ", cycle));
                return;
            }
            NodeValue valueAtNode = value.nodes().get(node);
            if (valueAtNode == null) return;
            states.put(node, Visit.VISITING);
            path.add(node);
            for (String target : collectLocalReferences(valueAtNode.value())) {
                if (value.nodes().containsKey(target)) {
                    visitLocal(unit, value, target, states, path, cycles);
                }
            }
            path.removeLast();
            states.put(node, Visit.DONE);
        }

        private void validateDefinitionCycles() {
            Map<Unit, List<Edge>> edges = new IdentityHashMap<>();
            for (Unit unit : units) {
                List<Edge> outgoing = new ArrayList<>();
                Effective value = effective.get(unit);
                if (value == null) value = invalid(unit);
                if (unit.definition().has("parent")) {
                    String parentId = requireResourceId(
                            unit,
                            "/parent",
                            unit.definition().get("parent"),
                            "parent"
                    );
                    Unit parent = parentId == null ? null : resolve(unit, parentId, "/parent");
                    if (parent != null) outgoing.add(new Edge(parent, unit, "/parent"));
                }
                for (NodeValue node : value.nodes().values()) {
                    for (RefLocation ref : collectExternalReferences(node.value())) {
                        Unit target = resolve(unit, ref.id(), join(node.pointer(), ref.pointer()));
                        if (target != null) {
                            outgoing.add(new Edge(
                                    target,
                                    unit,
                                    join(node.pointer(), ref.pointer())
                            ));
                        }
                    }
                }
                edges.put(unit, outgoing);
            }

            Map<Unit, Visit> states = new IdentityHashMap<>();
            List<Unit> path = new ArrayList<>();
            for (Unit unit : units) {
                visitDefinition(unit, edges, states, path);
            }
        }

        private void visitDefinition(
                Unit unit,
                Map<Unit, List<Edge>> edges,
                Map<Unit, Visit> states,
                List<Unit> path
        ) {
            Visit state = states.get(unit);
            if (state == Visit.DONE) return;
            if (state == Visit.VISITING) {
                int start = path.indexOf(unit);
                List<Unit> cycle = new ArrayList<>(
                        start < 0 ? path : path.subList(start, path.size()));
                cycle.add(unit);
                String description = cycle.stream().map(Unit::displayId).toList() + "";
                if (reportedCycles.add(description)) {
                    Edge entry = findCycleEdge(cycle, edges);
                    Unit origin = entry == null ? unit : entry.origin();
                    error(origin, entry == null ? "" : entry.pointer(),
                            BuildDiagnostic.Code.GROUP_CYCLE,
                            "Shape definition cycle includes " + description,
                            "Remove the parent/reference cycle.");
                }
                return;
            }
            states.put(unit, Visit.VISITING);
            path.add(unit);
            for (Edge edge : edges.getOrDefault(unit, List.of())) {
                visitDefinition(edge.target(), edges, states, path);
            }
            path.removeLast();
            states.put(unit, Visit.DONE);
        }

        private Edge findCycleEdge(List<Unit> cycle, Map<Unit, List<Edge>> edges) {
            Set<Unit> members = Collections.newSetFromMap(new IdentityHashMap<>());
            members.addAll(cycle);
            for (Unit member : cycle) {
                for (Edge edge : edges.getOrDefault(member, List.of())) {
                    if (members.contains(edge.target())) return edge;
                }
            }
            return null;
        }

        private void compileDefinition(Unit unit) {
            if (compiled.containsKey(unit.id())) return;
            if (!compilingDefinitions.add(unit)) return;
            try {
                Effective value = effective.get(unit);
                if (value == null || value.output() == null) return;
                NodeValue output = value.nodes().get(value.output());
                if (output == null) return;
                Map<String, Shape> nodeCache = localResults.computeIfAbsent(
                        unit, ignored -> new LinkedHashMap<>());
                List<String> stack = new ArrayList<>();
                Shape shape = compileNode(unit, value, value.output(), nodeCache, stack);
                if (shape != null) {
                    compiled.put(unit.id(),
                            new ShapeCatalog.Entry(unit.id(), value.output(), shape));
                }
            } finally {
                compilingDefinitions.remove(unit);
            }
        }

        private Shape compileNode(
                Unit unit,
                Effective value,
                String name,
                Map<String, Shape> cache,
                List<String> stack
        ) {
            Shape cached = cache.get(name);
            if (cached != null) return cached;
            if (stack.contains(name)) return Shape.empty();
            NodeValue node = value.nodes().get(name);
            if (node == null) return Shape.empty();
            stack.add(name);
            Shape result = compileExpression(
                    unit,
                    value,
                    node.value(),
                    node.pointer(),
                    0,
                    cache,
                    stack
            );
            stack.removeLast();
            if (result != null) cache.put(name, result);
            return result == null ? Shape.empty() : result;
        }

        private Shape compileExpression(
                Unit unit,
                Effective value,
                JsonElement expression,
                String pointer,
                int depth,
                Map<String, Shape> cache,
                List<String> stack
        ) {
            if (depth > MAX_EXPRESSION_DEPTH) {
                error(unit, pointer, BuildDiagnostic.Code.VALIDATION_FAILED,
                        "Shape expression exceeds the maximum depth " + MAX_EXPRESSION_DEPTH,
                        "Reduce expression nesting.");
                return Shape.empty();
            }
            if (expression == null || expression.isJsonNull()) {
                return Shape.empty();
            }
            if (expression.isJsonArray()) {
                JsonArray array = expression.getAsJsonArray();
                if (array.isEmpty()) return Shape.empty();
                if (array.size() != 6) return Shape.empty();
                try {
                    return Shape.of(List.of(box(array)), budget);
                } catch (GeometryException failure) {
                    error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                            "Invalid shape box: " + failure.getMessage(),
                            "Use six finite float32 pixel coordinates with a positive extent per axis.");
                    return Shape.empty();
                }
            }
            if (expression.isJsonPrimitive()) {
                Object local = rawString(expression);
                if (local instanceof String name && !name.isEmpty()) {
                    return compileNode(unit, value, name, cache, stack);
                }
                return Shape.empty();
            }
            if (!expression.isJsonObject()) return Shape.empty();

            JsonObject object = expression.getAsJsonObject();
            if (object.has("ref")) {
                String ref = requireResourceId(unit, join(pointer, "/ref"), object.get("ref"), "ref");
                Unit target = ref == null ? null : resolve(unit, ref, join(pointer, "/ref"));
                if (target == null) return Shape.empty();
                compileDefinition(target);
                ShapeCatalog.Entry targetEntry = compiled.get(target.id());
                if (targetEntry == null) return Shape.empty();
                Transform transform = transform(
                        unit,
                        object.get("transform"),
                        join(pointer, "/transform")
                );
                if (transform == null) return targetEntry.shape();
                try {
                    return transform.apply(targetEntry.shape(), budget);
                } catch (GeometryException failure) {
                    error(unit, pointer, BuildDiagnostic.Code.VALIDATION_FAILED,
                            "Shape reference transform failed: " + failure.getMessage(),
                            "Reduce the transform or remove degenerate coordinates.");
                    return Shape.empty();
                }
            }

            JsonElement opElement = object.get("op");
            JsonElement argsElement = object.get("args");
            String op = requireResourceName(unit, join(pointer, "/op"), opElement, "op");
            if (op == null || !Set.of("union", "intersection", "difference").contains(op)) {
                if (op != null) {
                    error(unit, join(pointer, "/op"), BuildDiagnostic.Code.CONTENT_INVALID,
                            "Unknown shape operation '" + op + "'",
                            "Use union, intersection, or difference.");
                }
                return Shape.empty();
            }
            if (argsElement == null || !argsElement.isJsonArray()) {
                error(unit, join(pointer, "/args"), BuildDiagnostic.Code.CONTENT_INVALID,
                        "Shape operation requires an args array",
                        "Provide 2 to 64 expressions.");
                return Shape.empty();
            }
            JsonArray args = argsElement.getAsJsonArray();
            if (args.size() < 2 || args.size() > MAX_OPERATION_ARGS) {
                error(unit, join(pointer, "/args"), BuildDiagnostic.Code.CONTENT_INVALID,
                        "Shape operation args must contain 2 to " + MAX_OPERATION_ARGS
                                + " expressions, got " + args.size(),
                        "Adjust the number of operands.");
                return Shape.empty();
            }
            List<Shape> operands = new ArrayList<>(args.size());
            for (int index = 0; index < args.size(); index++) {
                Shape operand = compileExpression(
                        unit,
                        value,
                        args.get(index),
                        join(pointer, "/args/" + index),
                        depth + 1,
                        cache,
                        stack
                );
                operands.add(operand == null ? Shape.empty() : operand);
            }
            try {
                return switch (op) {
                    case "union" -> Shape.union(operands, budget);
                    case "intersection" -> Shape.intersection(operands, budget);
                    case "difference" -> operands.getFirst().difference(
                            budget,
                            operands.subList(1, operands.size()).toArray(Shape[]::new)
                    );
                    default -> throw new IllegalStateException("validated operation");
                };
            } catch (GeometryException failure) {
                error(unit, pointer, BuildDiagnostic.Code.VALIDATION_FAILED,
                        "Shape operation failed: " + failure.getMessage(),
                        "Reduce geometry complexity or nested operation depth.");
                return Shape.empty();
            }
        }

        private Box box(JsonArray array) {
            return Box.fromPixels(
                    number(array.get(0)),
                    number(array.get(1)),
                    number(array.get(2)),
                    number(array.get(3)),
                    number(array.get(4)),
                    number(array.get(5))
            );
        }

        private Transform transform(Unit unit, JsonElement value, String pointer) {
            if (value == null) return Transform.identity();
            if (!value.isJsonObject()) {
                error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                        "Shape reference transform must be an object",
                        "Use scale, rotate_y, pivot, and translate.");
                return null;
            }
            JsonObject transform = value.getAsJsonObject();
            for (String key : transform.keySet()) {
                if (!Set.of("scale", "rotate_y", "pivot", "translate").contains(key)) {
                    error(unit, join(pointer, "/" + JsonPointers.escape(key)),
                            BuildDiagnostic.Code.CONTENT_INVALID,
                            "Unknown transform field '" + key + "'",
                            "Use only scale, rotate_y, pivot, and translate.");
                }
            }
            double scaleX = 1.0;
            double scaleY = 1.0;
            double scaleZ = 1.0;
            double[] scale = numbers(unit, transform.get("scale"),
                    join(pointer, "/scale"), 1.0, 1.0, 1.0, "scale");
            if (scale != null) {
                scaleX = scale[0];
                scaleY = scale[1];
                scaleZ = scale[2];
            }
            double[] pivot = numbers(unit, transform.get("pivot"),
                    join(pointer, "/pivot"), 8.0, 0.0, 8.0, "pivot");
            double[] translate = numbers(unit, transform.get("translate"),
                    join(pointer, "/translate"), 0.0, 0.0, 0.0, "translate");
            int rotation = 0;
            JsonElement rotationElement = transform.get("rotate_y");
            if (rotationElement != null) {
                if (!rotationElement.isJsonPrimitive()
                        || !rotationElement.getAsJsonPrimitive().isNumber()) {
                    error(unit, join(pointer, "/rotate_y"), BuildDiagnostic.Code.CONTENT_INVALID,
                            "rotate_y must be one of 0, 90, 180, or 270",
                            "Use an integer quarter turn.");
                } else {
                    double rawRotation = rotationElement.getAsDouble();
                    if (!Double.isFinite(rawRotation)
                            || rawRotation != Math.rint(rawRotation)
                            || rawRotation < Integer.MIN_VALUE
                            || rawRotation > Integer.MAX_VALUE) {
                        error(unit, join(pointer, "/rotate_y"),
                                BuildDiagnostic.Code.CONTENT_INVALID,
                                "rotate_y must be an integer",
                                "Use an integer quarter turn.");
                    } else {
                        rotation = (int) rawRotation;
                    }
                }
            }
            if (pivot == null || translate == null) return null;
            try {
                return Transform.fromPixels(
                        scaleX, scaleY, scaleZ,
                        rotation,
                        pivot[0], pivot[1], pivot[2],
                        translate[0], translate[1], translate[2]
                );
            } catch (GeometryException failure) {
                error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                        "Invalid shape reference transform: " + failure.getMessage(),
                        "Use positive float scales, integral quarter turns, and bounded pixels.");
                return null;
            }
        }

        private double[] numbers(
                Unit unit,
                JsonElement value,
                String pointer,
                double defaultX,
                double defaultY,
                double defaultZ,
                String field
        ) {
            if (value == null) return new double[]{defaultX, defaultY, defaultZ};
            if (!value.isJsonArray() || value.getAsJsonArray().size() != 3) {
                error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                        field + " must be an array of three numbers",
                        "Write [x, y, z].");
                return null;
            }
            JsonArray array = value.getAsJsonArray();
            double[] result = new double[3];
            for (int index = 0; index < array.size(); index++) {
                if (!isNumber(array.get(index))) {
                    error(unit, join(pointer, "/" + index), BuildDiagnostic.Code.CONTENT_INVALID,
                            field + " coordinate must be a JSON number",
                            "Replace it with a finite number.");
                    return null;
                }
                result[index] = number(array.get(index));
            }
            return result;
        }

        private void scanExpression(JsonElement expression, Unit unit, String pointer, int depth) {
            if (depth > MAX_EXPRESSION_DEPTH) {
                error(unit, pointer, BuildDiagnostic.Code.VALIDATION_FAILED,
                        "Shape expression exceeds the maximum depth " + MAX_EXPRESSION_DEPTH,
                        "Reduce expression nesting.");
                return;
            }
            if (expression == null || expression.isJsonNull()) {
                error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                        "Shape expression must not be null",
                        "Use [], a box, a local node name, a ShapeRef, or an Operation.");
                return;
            }
            if (expression.isJsonArray()) {
                JsonArray array = expression.getAsJsonArray();
                if (array.isEmpty()) return;
                if (array.size() != 6) {
                    error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                            "Shape expression array must be empty or contain exactly six numbers",
                            "Use [] for empty or a Box6; operator arrays are retired.");
                    return;
                }
                for (int index = 0; index < array.size(); index++) {
                    if (!isNumber(array.get(index))) {
                        error(unit, join(pointer, "/" + index),
                                BuildDiagnostic.Code.CONTENT_INVALID,
                                "Shape box coordinate must be a JSON number",
                                "Use a finite number, not a boolean or string.");
                        continue;
                    }
                    try {
                        box(array);
                    } catch (GeometryException failure) {
                        error(unit, join(pointer, "/" + index),
                                BuildDiagnostic.Code.CONTENT_INVALID,
                                "Invalid shape box: " + failure.getMessage(),
                                "Use six float32-compatible pixel coordinates with positive extents.");
                    }
                }
                return;
            }
            if (expression.isJsonPrimitive()) {
                Object local = rawString(expression);
                if (!(local instanceof String name) || name.isEmpty()) {
                    error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                            "Shape expression string must name a non-empty local node",
                            "Use a node name, or {\"ref\":\"namespace:path\"} for another shape.");
                }
                return;
            }
            if (!expression.isJsonObject()) {
                error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                        "Unsupported shape expression",
                        "Use [], a box, a local node name, a ShapeRef, or an Operation.");
                return;
            }

            JsonObject object = expression.getAsJsonObject();
            if (object.has("ref")) {
                for (String key : object.keySet()) {
                    if (!Set.of("ref", "transform").contains(key)) {
                        error(unit, join(pointer, "/" + JsonPointers.escape(key)),
                                BuildDiagnostic.Code.CONTENT_INVALID,
                                "ShapeRef does not support field '" + key + "'",
                                "ShapeRef accepts only ref and transform.");
                    }
                }
                requireResourceId(unit, join(pointer, "/ref"), object.get("ref"), "ref");
                transform(unit, object.get("transform"), join(pointer, "/transform"));
                return;
            }

            for (String key : object.keySet()) {
                if (!Set.of("op", "args").contains(key)) {
                    error(unit, join(pointer, "/" + JsonPointers.escape(key)),
                            BuildDiagnostic.Code.CONTENT_INVALID,
                            "Operation does not support field '" + key + "'",
                            "Operation accepts only op and args.");
                }
            }
            String op = requireResourceName(unit, join(pointer, "/op"), object.get("op"), "op");
            if (op != null && !Set.of("union", "intersection", "difference").contains(op)) {
                error(unit, join(pointer, "/op"), BuildDiagnostic.Code.CONTENT_INVALID,
                        "Unknown shape operation '" + op + "'",
                        "Use union, intersection, or difference.");
            }
            JsonElement argsElement = object.get("args");
            if (argsElement == null || !argsElement.isJsonArray()) {
                error(unit, join(pointer, "/args"), BuildDiagnostic.Code.CONTENT_INVALID,
                        "Operation requires an args array",
                        "Provide 2 to 64 expressions.");
                return;
            }
            JsonArray args = argsElement.getAsJsonArray();
            if (args.size() < 2 || args.size() > MAX_OPERATION_ARGS) {
                error(unit, join(pointer, "/args"), BuildDiagnostic.Code.CONTENT_INVALID,
                        "Operation args must contain 2 to " + MAX_OPERATION_ARGS
                                + " expressions, got " + args.size(),
                        "Adjust the number of operands.");
            }
            for (int index = 0; index < args.size(); index++) {
                scanExpression(args.get(index), unit, join(pointer, "/args/" + index), depth + 1);
            }
        }

        private Unit resolve(Unit requester, String id, String pointer) {
            Unit target = byId.get(id);
            if (target == null) {
                error(requester, pointer, BuildDiagnostic.Code.MISSING_REFERENCE,
                        "Shape definition '" + id + "' does not exist",
                        "Add the definition or remove the reference.");
                return null;
            }
            if (requester.context == null) return target;
            DefinitionKey key = new DefinitionKey(TYPE_NAME, id);
            if (requester.context.findAll(key).isEmpty()) {
                error(requester, pointer, BuildDiagnostic.Code.MISSING_REFERENCE,
                        "Shape definition '" + id + "' does not exist",
                        "Add the definition or remove the reference.");
                return null;
            }
            Set<String> visibleOwners = new LinkedHashSet<>();
            visibleOwners.add(requester.owner());
            visibleOwners.addAll(requester.context.directDependencies());
            if (!visibleOwners.contains(target.owner())) {
                error(requester, pointer, BuildDiagnostic.Code.REFERENCE_NOT_DECLARED,
                        "Shape definition '" + id + "' is owned by '" + target.owner()
                                + "' but is not visible to owner '" + requester.owner() + "'",
                        "Declare the referenced owner as a direct dependency.");
                return null;
            }
            return target;
        }

        private String requireResourceId(
                Unit unit,
                String pointer,
                JsonElement value,
                String field
        ) {
            Object raw = rawString(value);
            if (!(raw instanceof String text) || text.isBlank()) {
                error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                        field + " must be a non-empty namespaced string",
                        "Write '<namespace>:<path>'.");
                return null;
            }
            ResourceLocation location = ResourceLocation.tryParse(text);
            if (location == null || !text.contains(":")) {
                error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                        field + " '" + text + "' must be a valid namespaced resource location",
                        "Write '<namespace>:<path>'.");
                return null;
            }
            return text;
        }

        private String requireResourceName(
                Unit unit,
                String pointer,
                JsonElement value,
                String field
        ) {
            Object raw = rawString(value);
            if (!(raw instanceof String text) || text.isEmpty()) {
                error(unit, pointer, BuildDiagnostic.Code.CONTENT_INVALID,
                        field + " must be a non-empty string",
                        "Select a non-empty node name.");
                return null;
            }
            return text;
        }

        private List<String> collectLocalReferences(JsonElement expression) {
            List<String> result = new ArrayList<>();
            collectLocalReferences(expression, result);
            return result;
        }

        private void collectLocalReferences(JsonElement expression, List<String> result) {
            if (expression == null) return;
            if (expression.isJsonPrimitive()) {
                Object value = rawString(expression);
                if (value instanceof String text && !text.isEmpty()) result.add(text);
                return;
            }
            if (expression.isJsonArray() || !expression.isJsonObject()) return;
            JsonObject object = expression.getAsJsonObject();
            if (object.has("ref")) return;
            JsonElement args = object.get("args");
            if (args != null && args.isJsonArray()) {
                for (JsonElement arg : args.getAsJsonArray()) {
                    collectLocalReferences(arg, result);
                }
            }
        }

        private List<RefLocation> collectExternalReferences(JsonElement expression) {
            List<RefLocation> result = new ArrayList<>();
            collectExternalReferences(expression, "", result);
            return result;
        }

        private void collectExternalReferences(
                JsonElement expression,
                String pointer,
                List<RefLocation> result
        ) {
            if (expression == null) return;
            if (expression.isJsonArray()) {
                JsonArray array = expression.getAsJsonArray();
                for (int index = 0; index < array.size(); index++) {
                    collectExternalReferences(array.get(index), pointer + "/" + index, result);
                }
                return;
            }
            if (!expression.isJsonObject()) return;
            JsonObject object = expression.getAsJsonObject();
            if (object.has("ref")) {
                Object ref = rawString(object.get("ref"));
                if (ref instanceof String id) result.add(new RefLocation(id, pointer + "/ref"));
                return;
            }
            JsonElement args = object.get("args");
            if (args != null && args.isJsonArray()) {
                for (int index = 0; index < args.getAsJsonArray().size(); index++) {
                    collectExternalReferences(
                            args.getAsJsonArray().get(index),
                            pointer + "/args/" + index,
                            result
                    );
                }
            }
        }

        private void error(
                Unit unit,
                String relativePointer,
                BuildDiagnostic.Code code,
                String message,
                String suggestion
        ) {
            if (legacy) {
                failures.add(unit.pointer() + relativePointer + ": " + message);
                return;
            }
            unit.context().error(code, relativePointer, message, suggestion);
        }

        private boolean isNumber(JsonElement value) {
            return value != null && value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber();
        }

        private double number(JsonElement value) {
            return value.getAsDouble();
        }

        private String join(String pointer, String relative) {
            return JsonPointers.append(pointer == null || pointer.isEmpty() ? "" : pointer, relative);
        }
    }

    private record Edge(Unit target, Unit origin, String pointer) {
    }

    private record RefLocation(String id, String pointer) {
    }
}
