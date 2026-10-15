package lib.kasuga.rendering.models.mc.typo.gltf_entry;

import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GltfModelManifestTest {
    @Test
    void resolvesTheSidecarWithoutDependingOnAModelName() {
        ResourceLocation model = ResourceLocation.fromNamespaceAndPath(
                "example", "models/characters/arbitrary.glb");
        assertEquals(ResourceLocation.fromNamespaceAndPath(
                        "example", "models/characters/arbitrary.gltf.json"),
                GltfModelManifest.locationFor(model));
    }

    @Test
    void modelScaleIsDefensivelyOwned() {
        Vector3f input = new Vector3f(1.3f);
        GltfModelManifest manifest = new GltfModelManifest(input,
                ResourceLocation.fromNamespaceAndPath("example", "ragdolls/character.json"));
        input.set(9f);
        Vector3f returned = manifest.modelScale();
        returned.set(7f);
        assertEquals(new Vector3f(1.3f), manifest.modelScale());
    }
}
