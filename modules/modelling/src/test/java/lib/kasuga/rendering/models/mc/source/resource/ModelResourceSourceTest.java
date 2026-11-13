package lib.kasuga.rendering.models.mc.source.resource;

import lib.kasuga.core.resource.pack.ApprovedResourcePacks;
import lib.kasuga.core.resource.routing.ResourceRouting;
import lib.kasuga.core.resource.source.*;
import lib.kasuga.core.resource.view.ResourceCatalog;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.core.vfs.backend.InMemoryVfs;
import lib.kasuga.rendering.models.mc.source.model.KasugaModelScanner;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.resources.MultiPackResourceManager;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelResourceSourceTest {
    @org.junit.jupiter.api.BeforeAll static void initializePackVersion() { net.minecraft.SharedConstants.tryDetectVersion(); }
    private String model() throws IOException {
        var png = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), "png", png);
        String encoded = Base64.getEncoder().encodeToString(png.toByteArray());
        return "{\"textures\":[{\"uuid\":\"first\",\"name\":\"same\",\"source\":\"data:image/png;base64," + encoded
                + "\"},{\"uuid\":\"second\",\"name\":\"same\",\"source\":\"data:image/png;base64," + encoded + "\"}]}";
    }
    @Test void modelAdaptationPreservesOriginalMetadataAndAlreadyDerivedIdentity() throws Exception {
        var descriptor = new ResourceSourceDescriptor("demo", "demo", "main", 1, ResourceSourceDescriptor.Origin.EXTERNAL);
        var parent = lib.kasuga.core.resource.identity.ResourceUri.file("demo", "main", "列车.bbmodel");
        var child = lib.kasuga.core.resource.identity.ResourceUri.file("demo", "main", "bundle.dat").child("thumbnail");
        ResourceSource input = new ResourceSource() {
            public ResourceSourceDescriptor descriptor() { return descriptor; }
            public ResourceSnapshot capture() throws IOException {
                try (var builder = new ResourceSnapshot.Builder(descriptor, InputPolicy.DEFAULT)) {
                    builder.add(parent, "列车.bbmodel", "model", "bbmodel", Set.of("host-tag"), () -> new ByteArrayInputStream("{}".getBytes(StandardCharsets.UTF_8)));
                    builder.add(child, "derived/thumbnail.txt", "text", "", Set.of("derived-tag"), () -> new ByteArrayInputStream(new byte[]{65}));
                    return builder.build();
                }
            }
            public void close() {}
        };
        try (var source = new ModelResourceSource(input, InputPolicy.DEFAULT, 16, true); var snapshot = source.capture()) {
            var originals = snapshot.files().stream().collect(java.util.stream.Collectors.toMap(f -> f.descriptor().uri(), f -> f.descriptor()));
            assertEquals(Set.of("host-tag"), originals.get(parent).tags());
            assertTrue(originals.containsKey(child), "a derived input must retain its exact identity");
            assertEquals(Set.of("derived-tag"), originals.get(child).tags());
        }
    }
    @Test void unicodeModelAndDuplicateTextureNamesHaveTraceableDerivedIdentities() throws Exception {
        try (var vfs = new InMemoryVfs("model")) {
            String json = model(); vfs.writeString(VfsPath.of("models/列车 A.bbmodel"), json);
            var descriptor = new ResourceSourceDescriptor("demo", "demo", "main", 1, ResourceSourceDescriptor.Origin.EXTERNAL);
            try (var source = new ModelResourceSource(ResourceSources.vfs(descriptor, vfs, false, "", "", InputPolicy.DEFAULT), InputPolicy.DEFAULT, 16_777_216, true);
                 var snapshot = source.capture()) {
                assertEquals(4, snapshot.files().size());
                assertEquals(2, snapshot.files().stream().filter(f -> f.descriptor().type().equals("texture")).count());
                var parent = snapshot.files().stream().filter(f -> f.descriptor().uri().childKey().isEmpty()).findFirst().orElseThrow();
                assertEquals(json, new String(parent.readBytes(), StandardCharsets.UTF_8));
                assertTrue(snapshot.files().stream().filter(f -> !f.descriptor().uri().childKey().isEmpty())
                        .allMatch(f -> f.descriptor().parentUri().orElseThrow().equals(parent.descriptor().uri())));
                var catalog = new ResourceCatalog("scope", 1, List.of(snapshot));
                try (var input = new ResourceModelInput(catalog, parent)) {
                    assertEquals(json, ResourceModelSources.text().getInput(input).orElseThrow());
                    assertEquals(parent.descriptor().uri(), lib.kasuga.core.resource.identity.MinecraftNameCodec.decode(input.minecraftId(".bbmodel").toString(), ".bbmodel"));
                    assertThrows(IllegalArgumentException.class, () -> input.sibling("../../escape", "model"));
                }
                vfs.delete(VfsPath.of("models/列车 A.bbmodel"));
                try (var removed = source.capture()) { assertTrue(removed.files().isEmpty()); }
            }
        }
    }
    @Test @org.junit.jupiter.api.Tag("minecraft-resource-integration")
    void generatedModelAndTexturesAreVisibleTogetherInTheFirstMcView() throws Exception {
        try (var vfs = new InMemoryVfs("model"); var packs = new ApprovedResourcePacks()) {
            vfs.writeString(VfsPath.of("models/列车.bbmodel"), model());
            var descriptor = new ResourceSourceDescriptor("demo", "demo", "main", 1, ResourceSourceDescriptor.Origin.EXTERNAL);
            try (var source = new ModelResourceSource(ResourceSources.vfs(descriptor, vfs, false, "", "", InputPolicy.DEFAULT), InputPolicy.DEFAULT, 16_777_216, true)) {
                var routing = ResourceRouting.decode("{\"format_version\":1,\"bindings\":[{\"id\":\"root\",\"source_id\":\"demo\",\"schema_id\":\"minecraft_resource_schema\"}]}", ResourceRouting.builtinTemplates(), Set.of("demo"));
                packs.register("models", List.of(source), routing);
                List<Pack> discovered = new ArrayList<>(); packs.repositorySource(PackType.CLIENT_RESOURCES).loadPacks(discovered::add);
                try (var manager = new MultiPackResourceManager(PackType.CLIENT_RESOURCES, discovered.stream().map(Pack::open).toList())) {
                    var models = new KasugaModelScanner().scan(manager);
                    assertEquals(1, models.size(), "generated models do not require legacy proxy config");
                    String json;
                    try (var input = manager.getResourceOrThrow(models.getFirst()).open()) { json = new String(input.readAllBytes(), StandardCharsets.UTF_8); }
                    var transformed = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    for (var texture : transformed.getAsJsonArray("textures")) {
                        var id = ResourceLocation.parse(texture.getAsJsonObject().get("source").getAsString());
                        try (var image = manager.getResourceOrThrow(id).open()) { assertEquals(1, ImageIO.read(image).getWidth()); }
                    }
                }
            }
        }
    }
}
