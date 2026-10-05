package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.diagnostics.Diagnostics;
import lib.kasuga.registration.data_driven.reload.ReloadOrchestrator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The reload domain's diagnostics must be re-derived per cycle, never accumulated. Two reloads of the
 * same broken file must leave exactly one diagnostic, and that diagnostic must live in the
 * {@link Diagnostics.Domain#RELOAD_DATA} source dimension — not in the registration side's mod bucket,
 * which is never cleared by a reload.
 *
 * <p>A plain JVM test over an in-memory pack stack; no handler is registered, so the cycle is exercised
 * purely through the index reader and the per-file read guard.
 */
class ReloadDiagnosticsAccumulationTest {

    private static final String NS = "reload_accum_test";

    private static final String BAD_PATH = "content/bad.json";

    @AfterEach
    void clearDomain() {
        Diagnostics.clear(Diagnostics.Domain.RELOAD_DATA);
        Diagnostics.clear(NS);
    }

    @Test
    void twoReloadsOfTheSameBadFileReportOneError() {
        ReloadOrchestrator orchestrator = new ReloadOrchestrator();
        ResourceManager pack = new StubResourceManager()
                .add(NS, "kasuga_lib/data_driven/index.json",
                        "{ \"on_reload\": [ \"" + BAD_PATH + "\" ] }")
                .add(NS, BAD_PATH, "{ this is not json }");

        orchestrator.reload(pack);
        assertEquals(1, Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).size(),
                "the first reload reports the broken file once: "
                        + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));

        orchestrator.reload(pack);
        assertEquals(1, Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).size(),
                "a second reload must replace the first cycle's diagnostics, not append to them: "
                        + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));
        assertTrue(Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS).get(0).getMessage()
                        .contains("data/" + NS + "/" + BAD_PATH),
                "the diagnostic must still locate the offending file: "
                        + Diagnostics.errors(Diagnostics.Domain.RELOAD_DATA, NS));

        assertEquals(List.of(), Diagnostics.errors(NS),
                "reload diagnostics belong to the RELOAD_DATA source dimension, not the mod bucket");
    }

    /** In-memory pack stack with just enough surface for the index reader and the content read guard. */
    private static final class StubResourceManager implements ResourceManager {

        private final Map<ResourceLocation, String> files = new LinkedHashMap<>();

        private StubResourceManager add(String namespace, String path, String content) {
            files.put(ResourceLocation.fromNamespaceAndPath(namespace, path), content);
            return this;
        }

        @Override
        public Set<String> getNamespaces() {
            return files.keySet().stream().map(ResourceLocation::getNamespace)
                    .collect(Collectors.toCollection(TreeSet::new));
        }

        @Override
        public Optional<Resource> getResource(ResourceLocation location) {
            String content = files.get(location);
            return content == null ? Optional.empty() : Optional.of(resource(content));
        }

        @Override
        public List<Resource> getResourceStack(ResourceLocation location) {
            return getResource(location).map(List::of).orElseGet(List::of);
        }

        @Override
        public Map<ResourceLocation, Resource> listResources(String path, Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, Resource> result = new TreeMap<>();
            files.forEach((loc, content) -> {
                if (loc.getPath().startsWith(path + "/") && filter.test(loc)) {
                    result.put(loc, resource(content));
                }
            });
            return result;
        }

        @Override
        public Map<ResourceLocation, List<Resource>> listResourceStacks(String path,
                                                                       Predicate<ResourceLocation> filter) {
            Map<ResourceLocation, List<Resource>> result = new TreeMap<>();
            listResources(path, filter).forEach((loc, resource) -> result.put(loc, List.of(resource)));
            return result;
        }

        @Override
        public Stream<PackResources> listPacks() {
            return Stream.of();
        }

        private static Resource resource(String content) {
            return new Resource(null, () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
