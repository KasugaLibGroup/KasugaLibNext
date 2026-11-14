package lib.kasuga.test.registration.data_driven.pipeline;

import com.google.gson.JsonObject;
import lib.kasuga.registration.data_driven.TypeHandler;
import lib.kasuga.registration.data_driven.context.BuildContext;
import lib.kasuga.registration.data_driven.handler.BlockTypeHandler;
import lib.kasuga.registration.data_driven.handler.RegistryGroupHandler;
import lib.kasuga.registration.data_driven.pipeline.BuildDiagnostic;
import lib.kasuga.registration.data_driven.pipeline.BuildOptions;
import lib.kasuga.registration.data_driven.pipeline.DataDrivenBuildPipeline;
import lib.kasuga.registration.data_driven.pipeline.DefinitionKey;
import lib.kasuga.registration.data_driven.pipeline.HandlerCapability;
import lib.kasuga.registration.data_driven.pipeline.RegistryClaim;
import lib.kasuga.registration.data_driven.reload.Decoded;
import lib.kasuga.registration.data_driven.reload.ReloadHandler;
import lib.kasuga.registration.data_driven.reload.ReloadHandlerRegistry;
import lib.kasuga.registration.data_driven.source.ContentSource;
import lib.kasuga.registration.data_driven.source.DirectoryContentSource;
import lib.kasuga.registration.data_driven.source.SourceDescriptor;
import lib.kasuga.registration.data_driven.validation.ValidationContext;
import lib.kasuga.registration.factory.FactoryRegistry;
import lib.kasuga.registration.minecraft.block.BlockReg;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DataDrivenBuildPipelineTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void strictBlockClaimsUseTheSameExplicitNamespaceAsApply() throws Exception {
        FactoryRegistry.register(
                "strict_claim_block",
                (id, params) -> new BlockReg<Block>(
                        id,
                        ignored -> properties -> null
                ),
                true
        );
        Path root = source(
                "strict_claim",
                "strict_claim",
                """
                        {"blocks":[
                          {"id":"minecraft:strict_claim","type":"strict_claim_block"}
                        ]}
                        """
        );

        try (ContentSource source = open(root, "strict-claim", "strict-claim")) {
            var prepared = new DataDrivenBuildPipeline(
                    "example",
                    List.of(new BlockTypeHandler()),
                    List.of()
            ).prepare(List.of(source), BuildOptions.STRICT);

            assertTrue(prepared.diagnostics().isEmpty(), prepared.diagnostics().toString());
            assertEquals(
                    Set.of(
                            new RegistryClaim.RegistryKey(
                                    "minecraft:block",
                                    ResourceLocation.parse("minecraft:strict_claim")
                            ),
                            new RegistryClaim.RegistryKey(
                                    "minecraft:item",
                                    ResourceLocation.parse("minecraft:strict_claim")
                            )
                    ),
                    prepared.claims().stream()
                            .map(RegistryClaim::registryKey)
                            .collect(java.util.stream.Collectors.toSet())
            );
        }
    }

    @Test
    void legacyBlockClaimsUseTheLegacyNamespaceMapping() throws Exception {
        FactoryRegistry.register(
                "legacy_claim_block",
                (id, params) -> new BlockReg<Block>(
                        id,
                        ignored -> properties -> null
                ),
                true
        );
        Path root = source(
                "legacy_claim",
                "legacy_claim",
                """
                        {"blocks":[
                          {"id":"minecraft:legacy_claim","type":"legacy_claim_block"}
                        ]}
                        """
        );

        try (ContentSource source = open(root, "legacy-claim", "legacy-claim")) {
            var prepared = new DataDrivenBuildPipeline(
                    "example",
                    List.of(new BlockTypeHandler()),
                    List.of()
            ).prepare(List.of(source), BuildOptions.LEGACY);

            assertEquals(
                    Set.of(
                            new RegistryClaim.RegistryKey(
                                    "minecraft:block",
                                    ResourceLocation.parse("example:legacy_claim")
                            ),
                            new RegistryClaim.RegistryKey(
                                    "minecraft:item",
                                    ResourceLocation.parse("example:legacy_claim")
                            )
                    ),
                    prepared.claims().stream()
                            .map(RegistryClaim::registryKey)
                            .collect(java.util.stream.Collectors.toSet())
            );
        }
    }

    @Test
    void lowerPrioritySourceIsOverriddenBeforeOneUnifiedBuild() throws Exception {
        Path core = source(
                "core",
                "kuayue_core",
                """
                        {"probes":[
                          {"id":"example:alpha","value":"core"},
                          {"id":"example:beta","value":"core"}
                        ]}
                        """
        );
        Path overlay = source(
                "overlay",
                "example_pack",
                """
                        {"probes":[
                          {"id":"example:alpha","value":"overlay"}
                        ]}
                        """
        );
        RecordingHandler handler = new RecordingHandler();

        try (ContentSource coreSource = open(core, "core", "kuayue_core");
             ContentSource overlaySource = open(overlay, "overlay", "example_pack")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(handler),
                    List.of()
            );

            var prepared = pipeline.prepare(List.of(coreSource, overlaySource), BuildOptions.STRICT);

            assertTrue(prepared.diagnostics().isEmpty(), prepared.diagnostics().toString());
            assertEquals(2, prepared.definitions().size());
            assertEquals("overlay",
                    prepared.definitions().stream()
                            .filter(definition -> definition.key().id().equals("example:alpha"))
                            .findFirst()
                            .orElseThrow()
                            .definition() instanceof ProbeDefinition probe ? probe.value() : null);
            assertEquals(3, prepared.allDefinitions().size(),
                    "losing definitions stay available as provenance");
            assertEquals(2, prepared.reloadFiles().size(),
                    "on_reload paths are aggregated per source without being applied");

            var result = pipeline.build(prepared);

            assertTrue(result.successful(), result.diagnostics().toString());
            assertEquals(List.of("example:beta=core", "example:alpha=overlay"), handler.applied);
        }
    }

    @Test
    void strictPreparationDecodesReloadContentWithoutRegisteringIt() throws Exception {
        Path root = Files.createDirectories(temporaryDirectory.resolve("reload_validation"));
        write(root, "data/example/kasuga_lib/data_driven/index.json", """
                {"on_register":[],"on_reload":["reload.json"]}
                """);
        write(root, "data/example/reload.json", """
                {"reload_probe":[]}
                """);
        List<Object> registered = new ArrayList<>();
        ReloadHandlerRegistry.register(new ReloadHandler<Object>() {
            @Override
            public String typeName() {
                return "reload_probe";
            }

            @Override
            public void clearBucket() {
            }

            @Override
            public Decoded<Object> decode(JsonObject root) {
                return new Decoded<>(List.of(), List.of("invalid reload payload"));
            }

            @Override
            public void register(Object payload) {
                registered.add(payload);
            }
        });

        try (ContentSource source = open(root, "reload-source", "reload-owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(),
                    List.of()
            );
            var prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);

            assertTrue(prepared.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == BuildDiagnostic.Code.CONTENT_INVALID
                            && "invalid reload payload".equals(diagnostic.message())));
            assertTrue(registered.isEmpty(), "strict preparation must not register reload data");
        } finally {
            ReloadHandlerRegistry.clearForTest();
        }
    }

    @Test
    void registryClaimConflictIsRejectedBeforeApply() throws Exception {
        Path core = sourceWithRegister(
                "claim_core",
                "claim_core_owner",
                "claims.json",
                """
                        {
                          "probe_blocks":[{"id":"example:first"}],
                          "bogey_probe_blocks":[{"id":"example:second"}]
                        }
                        """
        );
        ClaimHandler first = new ClaimHandler("probe_blocks");
        ClaimHandler second = new ClaimHandler("bogey_probe_blocks");

        try (ContentSource source = open(core, "claim-core", "claim_core_owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(first, second),
                    List.of()
            );

            var prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);

            assertTrue(prepared.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == BuildDiagnostic.Code.CLAIM_CONFLICT));
            assertEquals("example:shared", prepared.claims().get(0).id().toString());
            assertEquals("claim_core_owner", prepared.claims().get(0).source().ownerId());

            var result = pipeline.build(prepared);

            assertEquals(lib.kasuga.registration.data_driven.pipeline.BuildResult.Status.REJECTED,
                    result.status());
            assertTrue(first.applied.isEmpty());
            assertTrue(second.applied.isEmpty());
        }
    }

    @Test
    void hostReservedRegistryEntryIsRejectedBeforeApply() throws Exception {
        Path sourcePath = sourceWithRegister(
                "reserved_core",
                "reserved_core_owner",
                "claims.json",
                """
                        {"probe_blocks":[{"id":"example:reserved"}]}
                        """
        );
        ClaimHandler handler = new ClaimHandler("probe_blocks");

        try (ContentSource source = open(sourcePath, "reserved-core", "reserved_core_owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(handler),
                    List.of(),
                    lib.kasuga.registration.data_driven.pipeline.RegistrationTarget.standard(),
                    Set.of(),
                    Set.of(new RegistryClaim.RegistryKey(
                            "minecraft:block",
                            ResourceLocation.fromNamespaceAndPath("example", "shared")
                    ))
            );

            var prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);

            assertTrue(prepared.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == BuildDiagnostic.Code.CLAIM_CONFLICT
                            && "reserved_core_owner".equals(diagnostic.ownerId())));
            assertEquals(
                    lib.kasuga.registration.data_driven.pipeline.BuildResult.Status.REJECTED,
                    pipeline.build(prepared).status()
            );
            assertTrue(handler.applied.isEmpty());
        }
    }

    @Test
    void finalizerRunsOnceAfterTheCompleteUnifiedApply() throws Exception {
        Path core = source(
                "finalizer",
                "finalizer_owner",
                """
                        {"probes":[
                          {"id":"example:one","value":"first"},
                          {"id":"example:two","value":"second"}
                        ]}
                        """
        );
        RecordingHandler handler = new RecordingHandler();
        List<Integer> finalizerApplicationCounts = new ArrayList<>();

        try (ContentSource source = open(core, "finalizer", "finalizer_owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(handler),
                    List.of((context, content) -> {
                        finalizerApplicationCounts.add(handler.applied.size());
                        return content.definitions().size();
                    })
            );
            var prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);
            var result = pipeline.build(prepared);

            assertTrue(result.successful(), result.diagnostics().toString());
            assertEquals(List.of(2), finalizerApplicationCounts);
            assertEquals(List.of(2), result.finalizerResults());
        }
    }

    @Test
    void strictParseFailureRejectsTheSessionBeforeAnyApply() throws Exception {
        Path core = source(
                "partial_failure",
                "partial_owner",
                """
                        {"probes":[
                          {"id":"example:valid","value":"ok"},
                          {"id":"example:invalid"}
                        ]}
                        """
        );
        RecordingHandler handler = new RecordingHandler();

        try (ContentSource source = open(core, "partial", "partial_owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(handler),
                    List.of()
            );
            var prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);
            var result = pipeline.build(prepared);

            assertTrue(prepared.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == BuildDiagnostic.Code.PARSE_FAILED));
            assertEquals(lib.kasuga.registration.data_driven.pipeline.BuildResult.Status.REJECTED,
                    result.status());
            assertTrue(handler.applied.isEmpty(), "a partial parse must not apply surviving definitions");
        }
    }

    @Test
    void legacyModeKeepsApplyingParseableDefinitions() throws Exception {
        Path core = source(
                "legacy_partial",
                "legacy_owner",
                """
                        {"probes":[
                          {"id":"example:valid","value":"ok"},
                          {"id":"example:invalid"}
                        ]}
                        """
        );
        RecordingHandler handler = new RecordingHandler();

        try (ContentSource source = open(core, "legacy", "legacy_owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(handler),
                    List.of()
            );
            var prepared = pipeline.prepare(List.of(source), BuildOptions.LEGACY);
            var result = pipeline.build(prepared);

            assertTrue(result.successful(), result.diagnostics().toString());
            assertEquals(List.of("example:valid=ok"), handler.applied);
        }
    }

    @Test
    void strictModeRejectsHandlersWithoutDeclaredPreflightCapabilities() throws Exception {
        Path core = source(
                "not_strict_ready",
                "not_strict_owner",
                """
                        {"plain":[{"id":"example:plain"}]}
                        """
        );
        PlainHandler handler = new PlainHandler();

        try (ContentSource source = open(core, "plain", "not_strict_owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(handler),
                    List.of()
            );
            var prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);
            var result = pipeline.build(prepared);

            assertTrue(prepared.diagnostics().stream().anyMatch(diagnostic ->
                    diagnostic.code() == BuildDiagnostic.Code.HANDLER_NOT_STRICT_READY));
            assertEquals(lib.kasuga.registration.data_driven.pipeline.BuildResult.Status.REJECTED,
                    result.status());
            assertTrue(handler.applied.isEmpty());
        }
    }

    @Test
    void registryGroupParentCycleBlocksStrictBuild() throws Exception {
        Path core = sourceWithRegister(
                "group_cycle",
                "group_cycle_owner",
                "groups.json",
                """
                        {"registry_groups":[
                          {"id":"example:a","parent":"example:b"},
                          {"id":"example:b","parent":"example:a"}
                        ]}
                        """
        );
        RegistryGroupHandler handler = new RegistryGroupHandler();

        try (ContentSource source = open(core, "groups", "group_cycle_owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(handler),
                    List.of()
            );
            var prepared = pipeline.prepare(List.of(source), BuildOptions.STRICT);
            var result = pipeline.build(prepared);

            assertTrue(prepared.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == BuildDiagnostic.Code.GROUP_CYCLE));
            assertEquals(lib.kasuga.registration.data_driven.pipeline.BuildResult.Status.REJECTED,
                    result.status());
        }
    }

    @Test
    void handlerPhasesWinOverHandlerRegistrationOrder() throws Exception {
        Path core = source(
                "phase_order",
                "phase_order_owner",
                """
                        {
                          "late":[{"id":"example:late"}],
                          "early":[{"id":"example:early"}]
                        }
                        """
        );
        List<String> applied = new ArrayList<>();
        OrderHandler late = new OrderHandler("late", TypeHandler.PHASE_EMBEDDED, applied);
        OrderHandler early = new OrderHandler("early", TypeHandler.PHASE_CONTENT, applied);

        try (ContentSource source = open(core, "phase", "phase_order_owner")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(late, early),
                    List.of()
            );
            var result = pipeline.build(pipeline.prepare(List.of(source), BuildOptions.STRICT));

            assertTrue(result.successful(), result.diagnostics().toString());
            assertEquals(List.of("example:early", "example:late"), applied);
        }
    }

    @Test
    void crossOwnerReferencesRequireAnExplicitDirectDependency() throws Exception {
        Path ownerA = source(
                "owner_a",
                "owner_a",
                """
                        {"references":[
                          {"id":"example:a","target":"example:b"}
                        ]}
                        """
        );
        Path ownerB = source(
                "owner_b",
                "owner_b",
                """
                        {"references":[
                          {"id":"example:b","target":null}
                        ]}
                        """
        );
        ReferenceHandler handler = new ReferenceHandler();

        try (ContentSource sourceA = open(ownerA, "source-a", "owner_a");
             ContentSource sourceB = open(ownerB, "source-b", "owner_b")) {
            DataDrivenBuildPipeline pipeline = new DataDrivenBuildPipeline(
                    "kuayue",
                    List.of(handler),
                    List.of()
            );

            var undeclared = pipeline.prepare(
                    List.of(sourceA, sourceB),
                    BuildOptions.STRICT
            );
            var declared = pipeline.prepare(
                    List.of(sourceA, sourceB),
                    BuildOptions.STRICT,
                    Map.of("owner_a", Set.of("owner_b"))
            );

            assertTrue(undeclared.diagnostics().stream()
                    .anyMatch(diagnostic -> diagnostic.code() == BuildDiagnostic.Code.REFERENCE_NOT_DECLARED));
            assertTrue(declared.diagnostics().isEmpty(), declared.diagnostics().toString());
        }
    }

    private Path source(String sourceName, String owner, String content) throws IOException {
        return sourceWithRegister(sourceName, owner, "probes.json", content);
    }

    private Path sourceWithRegister(
            String sourceName,
            String owner,
            String registerFile,
            String content
    ) throws IOException {
        Path root = Files.createDirectories(temporaryDirectory.resolve(sourceName));
        write(root, "data/example/kasuga_lib/data_driven/index.json", """
                {"on_register":["%s"],"on_reload":["reload.json"]}
                """.formatted(registerFile));
        write(root, "data/example/" + registerFile, content);
        write(root, "data/example/reload.json", "{}");
        return root;
    }

    private static ContentSource open(Path root, String sourceId, String owner) throws IOException {
        return DirectoryContentSource.open(
                root,
                SourceDescriptor.of(sourceId, owner, List.of("example"))
        );
    }

    private static void write(Path root, String relativePath, String content) throws IOException {
        Path target = root.resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    record ProbeDefinition(String id, String value) {
    }

    record ClaimDefinition(String id) {
    }

    record ReferenceDefinition(String id, String target) {
    }

    record PlainDefinition(String id) {
    }

    private static final class RecordingHandler implements TypeHandler<ProbeDefinition> {
        private final List<String> applied = new ArrayList<>();

        @Override
        public Set<HandlerCapability> capabilities() {
            return Set.of(HandlerCapability.STRICT_PREFLIGHT, HandlerCapability.NON_REGISTRATION);
        }

        @Override
        public String getTypeName() {
            return "probes";
        }

        @Override
        public int getPhase() {
            return PHASE_CONTENT;
        }

        @Override
        public ProbeDefinition parse(JsonObject json) {
            return new ProbeDefinition(json.get("id").getAsString(), json.get("value").getAsString());
        }

        @Override
        public String resolveIdentity(String hostModId, ProbeDefinition definition) {
            return definition.id();
        }

        @Override
        public void apply(ProbeDefinition definition, BuildContext context) {
            applied.add(definition.id() + "=" + definition.value());
        }
    }

    private static class ClaimHandler implements TypeHandler<ClaimDefinition> {
        private final String typeName;
        private final List<String> applied = new ArrayList<>();

        private ClaimHandler(String typeName) {
            this.typeName = typeName;
        }

        @Override
        public Set<HandlerCapability> capabilities() {
            return Set.of(HandlerCapability.STRICT_PREFLIGHT, HandlerCapability.REGISTRY_CLAIMS);
        }

        @Override
        public String getTypeName() {
            return typeName;
        }

        @Override
        public int getPhase() {
            return PHASE_CONTENT;
        }

        @Override
        public ClaimDefinition parse(JsonObject json) {
            return new ClaimDefinition(json.get("id").getAsString());
        }

        @Override
        public String resolveIdentity(String hostModId, ClaimDefinition definition) {
            return definition.id();
        }

        @Override
        public void validate(ClaimDefinition definition,
                             lib.kasuga.registration.data_driven.validation.ValidationContext context) {
        }

        @Override
        public List<lib.kasuga.registration.data_driven.pipeline.RegistryClaim> claims(
                ClaimDefinition definition,
                lib.kasuga.registration.data_driven.validation.ValidationContext context
        ) {
            return List.of(context.claim(
                    "minecraft:block",
                    ResourceLocation.parse("example:shared")
            ));
        }

        @Override
        public void apply(ClaimDefinition definition, BuildContext context) {
            applied.add(definition.id());
        }
    }

    private static final class ReferenceHandler implements TypeHandler<ReferenceDefinition> {
        @Override
        public Set<HandlerCapability> capabilities() {
            return Set.of(HandlerCapability.STRICT_PREFLIGHT, HandlerCapability.NON_REGISTRATION);
        }

        @Override
        public String getTypeName() {
            return "references";
        }

        @Override
        public int getPhase() {
            return PHASE_CONTENT;
        }

        @Override
        public ReferenceDefinition parse(JsonObject json) {
            return new ReferenceDefinition(
                    json.get("id").getAsString(),
                    json.has("target") && !json.get("target").isJsonNull()
                            ? json.get("target").getAsString()
                            : null
            );
        }

        @Override
        public String resolveIdentity(String hostModId, ReferenceDefinition definition) {
            return definition.id();
        }

        @Override
        public void validate(ReferenceDefinition definition, ValidationContext context) {
            if (definition.target() != null) {
                context.requireDefinition(
                        new DefinitionKey(getTypeName(), definition.target()),
                        "/target"
                );
            }
        }

        @Override
        public void apply(ReferenceDefinition definition, BuildContext context) {
        }
    }

    private static final class PlainHandler implements TypeHandler<PlainDefinition> {
        private final List<String> applied = new ArrayList<>();

        @Override
        public String getTypeName() {
            return "plain";
        }

        @Override
        public int getPhase() {
            return PHASE_CONTENT;
        }

        @Override
        public PlainDefinition parse(JsonObject json) {
            return new PlainDefinition(json.get("id").getAsString());
        }

        @Override
        public String resolveIdentity(String hostModId, PlainDefinition definition) {
            return definition.id();
        }

        @Override
        public void apply(PlainDefinition definition, BuildContext context) {
            applied.add(definition.id());
        }
    }

    private static final class OrderHandler implements TypeHandler<PlainDefinition> {
        private final String typeName;
        private final int phase;
        private final List<String> events;

        private OrderHandler(String typeName, int phase, List<String> events) {
            this.typeName = typeName;
            this.phase = phase;
            this.events = events;
        }

        @Override
        public Set<HandlerCapability> capabilities() {
            return Set.of(HandlerCapability.STRICT_PREFLIGHT, HandlerCapability.NON_REGISTRATION);
        }

        @Override
        public String getTypeName() {
            return typeName;
        }

        @Override
        public int getPhase() {
            return phase;
        }

        @Override
        public PlainDefinition parse(JsonObject json) {
            return new PlainDefinition(json.get("id").getAsString());
        }

        @Override
        public String resolveIdentity(String hostModId, PlainDefinition definition) {
            return definition.id();
        }

        @Override
        public void apply(PlainDefinition definition, BuildContext context) {
            events.add(definition.id());
        }
    }
}
