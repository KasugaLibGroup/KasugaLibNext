package lib.kasuga.test.registration.data_driven.shape;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import lib.kasuga.geometry.Box;
import lib.kasuga.geometry.Shape;
import lib.kasuga.registration.data_driven.builder.JsonTreeBuilder;
import lib.kasuga.registration.data_driven.handler.ShapeTypeHandler;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic;
import lib.kasuga.registration.data_driven.pipeline.BuildOptions;
import lib.kasuga.registration.data_driven.pipeline.BuildResult;
import lib.kasuga.registration.data_driven.pipeline.DataDrivenBuildPipeline;
import lib.kasuga.registration.data_driven.pipeline.PreparedContent;
import lib.kasuga.registration.data_driven.shape.ShapeCatalog;
import lib.kasuga.registration.data_driven.shape.ShapeDefinition;
import lib.kasuga.registration.data_driven.shape.ShapeDomainCompiler;
import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.DirectoryContentSource;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeDomainCompilerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void compilesKeywordsAnonymousBoxesOperationsAndTransforms() throws Exception {
        var prepared = prepare("""
                {"shapes":[
                  {
                    "id":"example:base",
                    "nodes":{
                      "union":[0.0,0.0,0.0,16.0,16.0,16.0],
                      "intersection":[8.0,0.0,0.0,24.0,16.0,16.0],
                      "difference":[4.0,4.0,4.0,12.0,12.0,12.0],
                      "op":[0.0,0.0,0.0,4.0,4.0,4.0],
                      "args":[20.0,0.0,0.0,24.0,4.0,4.0],
                      "result":{"op":"difference","args":[
                        {"op":"union","args":["op","args"]},
                        {"ref":"example:other","transform":{
                          "scale":[2.0,1.0,1.0],"rotate_y":90,
                          "pivot":[8.0,0.0,8.0],"translate":[16.0,0.0,0.0]
                        }}
                      ]}
                    },
                    "output":"result"
                  },
                  {
                    "id":"example:other",
                    "nodes":{"empty":[]},
                    "output":"empty"
                  }
                ]}
                """, Map.of(), "example");

        assertTrue(prepared.diagnostics().isEmpty(), prepared.diagnostics().toString());
        ShapeCatalog catalog = prepared.artifacts().get(ShapeCatalog.class);
        assertNotNull(catalog);
        assertEquals(Shape.empty(), catalog.get("example:other"));
        assertNotNull(catalog.get("example:base"));
        assertFalse(catalog.get("example:base").isEmpty());
    }

    @Test
    void inheritsOutputAndMergesNodesByWholeExpression() throws Exception {
        var prepared = prepare("""
                {"shapes":[
                  {
                    "id":"example:parent",
                    "nodes":{
                      "outer":[0.0,0.0,0.0,32.0,32.0,4.0],
                      "opening":[8.0,0.0,0.0,24.0,30.0,4.0],
                      "frame":{"op":"difference","args":["outer","opening"]}
                    },
                    "output":"frame"
                  },
                  {
                    "id":"example:child",
                    "parent":"example:parent",
                    "nodes":{"opening":[4.0,0.0,0.0,28.0,30.0,4.0]}
                  }
                ]}
                """, Map.of(), "example");

        assertTrue(prepared.diagnostics().isEmpty(), prepared.diagnostics().toString());
        ShapeCatalog catalog = prepared.artifacts().get(ShapeCatalog.class);
        assertEquals("frame", catalog.entry("example:child").output());
        assertEquals(3, catalog.get("example:child").boxes().size());
        assertTrue(catalog.get("example:child").volume() < catalog.get("example:parent").volume());
    }

    @Test
    void resolvesEachReferenceOutputInTheTargetsOwnScope() throws Exception {
        var prepared = prepare("""
                {"shapes":[
                  {
                    "id":"example:target",
                    "nodes":{
                      "same":[0.0,0.0,0.0,16.0,16.0,16.0],
                      "chosen":[0.0,0.0,0.0,8.0,8.0,8.0]
                    },
                    "output":"chosen"
                  },
                  {
                    "id":"example:caller",
                    "nodes":{
                      "same":[100.0,100.0,100.0,116.0,116.0,116.0],
                      "result":{"ref":"example:target"}
                    },
                    "output":"result"
                  }
                ]}
                """, Map.of(), "example");

        assertTrue(prepared.diagnostics().isEmpty(), prepared.diagnostics().toString());
        ShapeCatalog catalog = prepared.artifacts().get(ShapeCatalog.class);
        assertEquals(Shape.of(Box.fromPixels(0, 0, 0, 8, 8, 8)), catalog.get("example:caller"));
    }

    @Test
    void reportsRetiredArraysAndUnknownOperationFieldsAtPrecisePointers() throws Exception {
        var prepared = prepare("""
                {"shapes":[
                  {
                    "id":"example:bad",
                    "nodes":{
                      "retired":["union",[0.0,0.0,0.0,1.0,1.0,1.0],[2.0,2.0,2.0,3.0,3.0,3.0]],
                      "unknown":{"op":"union","args":[[0.0,0.0,0.0,1.0,1.0,1.0],[2.0,2.0,2.0,3.0,3.0,3.0]],"x":1}
                    },
                    "output":"unknown"
                  }
                ]}
                """, Map.of(), "example");

        assertTrue(has(prepared.diagnostics(), "/shapes/0/nodes/retired"));
        assertTrue(has(prepared.diagnostics(), "/shapes/0/nodes/unknown/x"));
        assertEquals(BuildResult.Status.REJECTED,
                new DataDrivenBuildPipeline("example", List.of(new ShapeTypeHandler()), List.of())
                        .build(prepared).status());
    }

    @Test
    void validatesLocalParentExternalAndMixedCyclesBeforeApply() throws Exception {
        var prepared = prepare("""
                {"shapes":[
                  {"id":"example:local","nodes":{"a":"b","b":"a"},"output":"a"},
                  {"id":"example:left","nodes":{"x":{"ref":"example:right"}},"output":"x"},
                  {"id":"example:right","nodes":{"y":{"ref":"example:left"}},"output":"y"},
                  {"id":"example:base","nodes":{"x":[]},"output":"x"},
                  {"id":"example:derived","parent":"example:base",
                   "nodes":{"y":{"ref":"example:derived"}},"output":"y"}
                ]}
                """, Map.of(), "example");

        assertTrue(hasCode(prepared.diagnostics(), BuildDiagnostic.Code.GROUP_CYCLE));
        assertEquals(BuildResult.Status.REJECTED,
                new DataDrivenBuildPipeline("example", List.of(new ShapeTypeHandler()), List.of())
                        .build(prepared).status());
    }

    @Test
    void rejectsExternalReferencesWithoutADeclaredDependency() throws Exception {
        Path visibleRoot = writeSource("visible", """
                {"shapes":[{"id":"example:target","nodes":{"x":[]},"output":"x"}]}
                """, "example");
        Path callerRoot = writeSource("caller", """
                {"shapes":[{"id":"example:caller","nodes":{"x":{"ref":"example:target"}},"output":"x"}]}
                """, "example");

        try (ContentSource visible = open(visibleRoot, "visible", "visible");
             ContentSource caller = open(callerRoot, "caller", "caller")) {
            var pipeline = new DataDrivenBuildPipeline(
                    "example",
                    List.of(new ShapeTypeHandler()),
                    List.of()
            );
            var undeclared = pipeline.prepare(List.of(visible, caller), BuildOptions.STRICT);
            assertTrue(hasCode(undeclared.diagnostics(), BuildDiagnostic.Code.REFERENCE_NOT_DECLARED),
                    undeclared.diagnostics().toString());
        }

        try (ContentSource visible = open(visibleRoot, "visible", "visible");
             ContentSource caller = open(callerRoot, "caller", "caller")) {
            var prepared = new DataDrivenBuildPipeline(
                    "example",
                    List.of(new ShapeTypeHandler()),
                    List.of()
            ).prepare(
                    List.of(visible, caller),
                    BuildOptions.STRICT,
                    Map.of("caller", Set.of("visible"))
            );
            assertTrue(prepared.diagnostics().isEmpty(), prepared.diagnostics().toString());
        }
    }

    @Test
    void resolvesForwardReferencesAcrossContentFiles() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("cross-file"));
        write(root, "data/example/kasuga_lib/data_driven/index.json",
                "{\"on_register\":[\"caller.json\",\"target.json\"]}");
        write(root, "data/example/caller.json", """
                {"shapes":[{
                  "id":"example:caller",
                  "nodes":{"result":{"ref":"example:target"}},
                  "output":"result"
                }]}
                """);
        write(root, "data/example/target.json", """
                {"shapes":[{"id":"example:target","nodes":{"empty":[]},"output":"empty"}]}
                """);

        try (ContentSource source = open(root, "cross-file", "example")) {
            var prepared = new DataDrivenBuildPipeline(
                    "example",
                    List.of(new ShapeTypeHandler()),
                    List.of()
            ).prepare(List.of(source), BuildOptions.STRICT);
            assertTrue(prepared.diagnostics().isEmpty(), prepared.diagnostics().toString());
            assertEquals(Shape.empty(),
                    prepared.artifacts().get(ShapeCatalog.class).get("example:caller"));
        }
    }

    @Test
    void invalidTargetDiagnosticsKeepTheTargetOwnerAndPointer() throws Exception {
        Path callerRoot = writeSource("owner-caller", """
                {"shapes":[{
                  "id":"example:caller",
                  "nodes":{"result":{"ref":"example:target"}},
                  "output":"result"
                }]}
                """, "example");
        Path targetRoot = writeSource("owner-target", """
                {"shapes":[{
                  "id":"example:target",
                  "nodes":{"empty":[]},
                  "output":"missing"
                }]}
                """, "example");

        try (ContentSource caller = open(callerRoot, "owner-caller", "caller");
             ContentSource target = open(targetRoot, "owner-target", "target")) {
            var prepared = new DataDrivenBuildPipeline(
                    "example",
                    List.of(new ShapeTypeHandler()),
                    List.of()
            ).prepare(
                    List.of(caller, target),
                    BuildOptions.STRICT,
                    Map.of("caller", Set.of("target"))
            );
            assertTrue(prepared.diagnostics().stream().anyMatch(diagnostic ->
                    "target".equals(diagnostic.ownerId())
                            && "/shapes/0/output".equals(diagnostic.jsonPointer())
                            && diagnostic.message().contains("missing")));
        }
    }

    @Test
    void legacyParserAndCompilerRejectRetiredSyntaxInsteadOfIgnoringIt() {
        JsonObject document = JsonParser.parseString("""
                {"shapes":[{
                  "id":"example:legacy",
                  "nodes":{"bad":["union",[],[]]},
                  "output":"bad"
                }]}
                """).getAsJsonObject();
        Map<String, List<Object>> parsed = JsonTreeBuilder.parseContentBody(
                "example",
                "legacy.json",
                document
        );
        List<ShapeDefinition> definitions = parsed.get("shapes").stream()
                .map(ShapeDefinition.class::cast)
                .toList();
        IllegalArgumentException failure = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ShapeDomainCompiler.compileLegacy("example", definitions)
        );
        assertTrue(failure.getMessage().contains("/shapes/0/nodes/bad"));
    }

    @Test
    void bothPipelinePoliciesExposeTheSameCatalog() throws Exception {
        String content = """
                {"shapes":[{"id":"example:a","nodes":{"x":[0.0,0.0,0.0,16.0,16.0,16.0]},"output":"x"}]}
                """;
        var strict = prepare(content, Map.of(), "example");
        var legacy = prepare(content, Map.of(), "example", BuildOptions.LEGACY);
        Shape strictShape = strict.artifacts().get(ShapeCatalog.class).get("example:a");
        Shape legacyShape = legacy.artifacts().get(ShapeCatalog.class).get("example:a");
        assertEquals(strictShape, legacyShape);
    }

    @Test
    void applyContextReceivesThePreparedCatalogWithoutRecompiling() throws Exception {
        var prepared = prepare("""
                {"shapes":[{
                  "id":"example:context",
                  "nodes":{"x":[0.0,0.0,0.0,16.0,16.0,16.0]},
                  "output":"x"
                }]}
                """, Map.of(), "example");
        AtomicReference<ShapeCatalog> seen = new AtomicReference<>();
        var result = new DataDrivenBuildPipeline(
                "example",
                List.of(new ShapeTypeHandler()),
                List.of((context, content) -> {
                    seen.set(context.artifact(ShapeCatalog.class));
                    return null;
                })
        ).build(prepared);

        assertTrue(result.successful(), result.diagnostics().toString());
        assertEquals(prepared.artifacts().get(ShapeCatalog.class), seen.get());
    }

    private PreparedContent prepare(
            String content,
            Map<String, ? extends java.util.Collection<String>> dependencies,
            String owner
    ) throws IOException {
        return prepare(content, dependencies, owner, BuildOptions.STRICT);
    }

    private PreparedContent prepare(
            String content,
            Map<String, ? extends java.util.Collection<String>> dependencies,
            String owner,
            BuildOptions options
    ) throws IOException {
        Path root = writeSource(owner, content, "example");
        try (ContentSource source = open(root, owner, owner)) {
            var prepared = new DataDrivenBuildPipeline(
                    "example",
                    List.of(new ShapeTypeHandler()),
                    List.of()
            ).prepare(List.of(source), options, dependencies);
            return prepared;
        }
    }

    private Path writeSource(String name, String content, String namespace) throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve(name));
        write(root, "data/" + namespace + "/kasuga_lib/data_driven/index.json",
                "{\"on_register\":[\"shapes.json\"]}");
        write(root, "data/" + namespace + "/shapes.json", content);
        return root;
    }

    private static void write(Path root, String path, String content) throws IOException {
        Path target = root.resolve(path);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private static ContentSource open(Path root, String sourceId, String owner) throws IOException {
        return DirectoryContentSource.open(
                root,
                SourceDescriptor.of(sourceId, owner, List.of("example"))
        );
    }

    private static boolean has(List<BuildDiagnostic> diagnostics, String pointer) {
        return diagnostics.stream().anyMatch(diagnostic -> pointer.equals(diagnostic.jsonPointer()));
    }

    private static boolean hasCode(List<BuildDiagnostic> diagnostics, BuildDiagnostic.Code code) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.code() == code);
    }

}
