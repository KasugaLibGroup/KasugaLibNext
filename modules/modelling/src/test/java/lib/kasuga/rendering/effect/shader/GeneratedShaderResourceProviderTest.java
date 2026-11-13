package lib.kasuga.rendering.effect.shader;

import lib.kasuga.core.resource.pack.VfsBackedPackResources;
import lib.kasuga.core.resource.pack.VfsPackResourcesBridge;
import lib.kasuga.core.resource.pack.VfsScopedPackResourcesAdapter;
import lib.kasuga.core.vfs.VfsPath;
import lib.kasuga.rendering.models.mc.source.KsgPackResource;
import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.backend.MinecraftGlsl150Backend;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GeneratedShaderResourceProviderTest {

    @Test
    void exposesBundleAsMinecraftResourcesAndFallsBackForOtherLocations() throws Exception {
        ShaderProgram program = ShaderProgram.fullscreen("generated_test:copy", shader -> {
            var scene = shader.sampler2D("SceneSampler");
            shader.fragmentColor(scene.sample(shader.texCoord()));
        });
        GeneratedShaderResourceProvider generated = new GeneratedShaderResourceProvider(
                MinecraftGlsl150Backend.generate(program)
        );

        ResourceLocation json = id("shaders/core/copy.json");
        ResourceLocation fragment = id("shaders/core/copy.fsh");
        assertEquals(3, generated.locations().size());
        assertTrue(read(generated.getResource(json).orElseThrow()).contains(
                "\"fragment\": \"generated_test:copy\""
        ));
        assertTrue(read(generated.getResource(fragment).orElseThrow()).contains(
                "texture(SceneSampler, texCoord)"
        ));
        assertEquals("kasuga_generated_shaders",
                generated.getResource(json).orElseThrow().sourcePackId());

        Resource fallbackResource = generated.getResource(json).orElseThrow();
        ResourceProvider fallback = ignored -> Optional.of(fallbackResource);
        ResourceProvider overlay = generated.overlay(fallback);
        assertSame(fallbackResource, overlay.getResource(id("not/generated.txt")).orElseThrow());
        assertTrue(read(overlay.getResource(fragment).orElseThrow()).contains("#version 150"));

        assertTrue(generated.packResources() instanceof VfsPackResourcesBridge);
        assertTrue(generated.packResources() instanceof VfsBackedPackResources);
        assertTrue(generated.vfs().isRegularFile(VfsPath.of("assets/generated_test/shaders/core/copy.fsh")));
        assertTrue(generated.packResources().getNamespaces(PackType.CLIENT_RESOURCES).contains("generated_test"));
        var packSupplier = generated.packResources().getResource(PackType.CLIENT_RESOURCES, fragment);
        assertTrue(packSupplier != null);
        try (var input = packSupplier.get()) {
            assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("#version 150"));
        }
    }

    @Test
    void ksgPackResourceImplementsVfsBackedPackResourcesAndSyncsCrud() throws Exception {
        PackLocationInfo info = new PackLocationInfo(
                "ksg_test_pack",
                Component.literal("Ksg Test Pack"),
                PackSource.BUILT_IN,
                Optional.empty()
        );
        try (KsgPackResource<ResourceLocation> pack = new KsgPackResource<>(info) {
            @Override
            public ResourceLocation getUrl(String str) {
                return ResourceLocation.parse(str);
            }

            @Override
            public ResourceLocation getUrl(ResourceLocation location) {
                return location;
            }
        }) {
            assertTrue(pack instanceof VfsBackedPackResources);
            ResourceLocation tex = ResourceLocation.fromNamespaceAndPath("ksg_test", "textures/demo.png");
            ResourceLocation extra = ResourceLocation.fromNamespaceAndPath("ksg_test", "models/demo.json");

            pack.addResource(tex, () -> new ByteArrayInputStream("pngdata".getBytes(StandardCharsets.UTF_8)));
            try (var in = pack.getResource(PackType.CLIENT_RESOURCES, tex).get()) {
                assertEquals("pngdata", new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            assertTrue(pack.getNamespaces(PackType.CLIENT_RESOURCES).contains("ksg_test"));

            List<ResourceLocation> listed = new ArrayList<>();
            pack.listResources(PackType.CLIENT_RESOURCES, "ksg_test", "textures", (loc, supplier) -> listed.add(loc));
            assertEquals(List.of(tex), listed);

            VfsScopedPackResourcesAdapter scoped = pack.asScopedPackResources();
            assertTrue(scoped instanceof VfsScopedPackResourcesAdapter);
            assertTrue(scoped.exists("assets/ksg_test/textures", "demo.png"));

            pack.removeResource(tex);
            assertNull(pack.getResource(PackType.CLIENT_RESOURCES, tex));
            assertFalse(pack.getVfs().exists(VfsPath.of("assets/ksg_test/textures/demo.png")));

            pack.addResource(tex, () -> new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)));
            pack.addResource(extra, () -> new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)));
            pack.clearResources();
            assertEquals(0, pack.getResourceCount());
            assertFalse(pack.getVfs().exists(VfsPath.of("assets/ksg_test/textures/demo.png")));
            assertFalse(pack.getVfs().exists(VfsPath.of("assets/ksg_test/models/demo.json")));
        }
    }

    private static String read(Resource resource) throws Exception {
        try (var input = resource.open()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("generated_test", path);
    }
}
