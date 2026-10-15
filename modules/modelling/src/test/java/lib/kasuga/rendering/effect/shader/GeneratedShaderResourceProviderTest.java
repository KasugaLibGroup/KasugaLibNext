package lib.kasuga.rendering.effect.shader;

import lib.kasuga.shader.ShaderProgram;
import lib.kasuga.shader.backend.MinecraftGlsl150Backend;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceProvider;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
