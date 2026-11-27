package lib.kasuga.rendering.models.mc.source.model;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelProxyConfigLoaderTest {
    private static final ResourceLocation CONFIG = id("models/model_proxy.json");
    private static final ResourceLocation FRAME = id("models/block/bogey/jy290/jy290_frame.obj");
    private static final ResourceLocation DOOR = id("models/block/jy_maintenance/jy_maintenance_door.bbmodel");
    private static final String BOGEYS = """
            {"kuayue":["models/block/bogey/*.obj","models/block/bogey/**/*.obj"]}
            """;
    private static final String MAINTENANCE = """
            {"kuayue":["models/block/jy_maintenance/*.bbmodel"]}
            """;

    @Test
    void sameNamespacePackContributionKeepsExistingBogeyModelsDiscoverable() throws Exception {
        try (var resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(
                pack("running-gear", Map.of(CONFIG, BOGEYS, FRAME, "o frame")),
                pack("jy-maintenance", Map.of(CONFIG, MAINTENANCE, DOOR, "{}"))))) {
            assertEquals(2, resources.getResourceStack(CONFIG).size());
            try (var reader = resources.getResourceOrThrow(CONFIG).openAsReader()) {
                assertEquals(MAINTENANCE.strip(), reader.readLine());
            }
            assertTrue(resources.getResource(FRAME).isPresent(), "the disappearing model still exists");

            var config = ModelProxyConfigLoader.loadConfig(resources);
            assertNotNull(config);
            var selected = new KasugaModelScanner(config).scan(resources);
            assertEquals(Set.of(FRAME, DOOR), Set.copyOf(selected),
                    "the higher same-namespace BBModel contribution must not erase bogey discovery");
        }
    }

    @Test
    void malformedHigherContributionDoesNotHideValidLowerModels() {
        try (var resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(
                pack("running-gear", Map.of(CONFIG, BOGEYS, FRAME, "o frame")),
                pack("malformed", Map.of(CONFIG, "{"))))) {
            var config = ModelProxyConfigLoader.loadConfig(resources);
            assertNotNull(config, "a rejected overlay must not erase the valid lower declaration");
            assertEquals(List.of(FRAME), new KasugaModelScanner(config).scan(resources));
        }
    }

    @Test
    void exclusionsStillApplyWhenDeclarationsAreMerged() {
        try (var resources = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, List.of(
                pack("running-gear", Map.of(CONFIG, BOGEYS, FRAME, "o frame")),
                pack("exclusions", Map.of(CONFIG, """
                        {"kuayue":["!models/block/bogey/jy290/*.obj","models/block/jy_maintenance/*.bbmodel"]}
                        """, DOOR, "{}"))))) {
            var config = ModelProxyConfigLoader.loadConfig(resources);
            assertNotNull(config);
            assertFalse(config.matches(FRAME));
            assertTrue(config.matches(DOOR));
            assertEquals(List.of(DOOR), new KasugaModelScanner(config).scan(resources));
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kuayue", path);
    }

    /** Separate native pack providers, preserving the real getResource/getResourceStack behavior. */
    private static PackResources pack(String name, Map<ResourceLocation, String> files) {
        return new PackResources() {
            @Override public IoSupplier<InputStream> getRootResource(String... elements) { return null; }
            @Override public IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
                String content = files.get(id);
                return type != PackType.CLIENT_RESOURCES || content == null ? null
                        : () -> new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
            }
            @Override public void listResources(PackType type, String namespace, String prefix, ResourceOutput output) {
                files.keySet().stream().filter(id -> id.getNamespace().equals(namespace)
                                && id.getPath().startsWith(prefix + "/"))
                        .forEach(id -> output.accept(id, getResource(type, id)));
            }
            @Override public Set<String> getNamespaces(PackType type) {
                return type == PackType.CLIENT_RESOURCES
                        ? files.keySet().stream().map(ResourceLocation::getNamespace).collect(Collectors.toSet())
                        : Set.of();
            }
            @Override public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) { return null; }
            @Override public PackLocationInfo location() {
                return new PackLocationInfo(name, Component.literal(name), PackSource.BUILT_IN, Optional.empty());
            }
            @Override public void close() {}
        };
    }
}
