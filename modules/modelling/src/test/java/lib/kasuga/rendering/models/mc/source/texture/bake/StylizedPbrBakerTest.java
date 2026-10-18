package lib.kasuga.rendering.models.mc.source.texture.bake;

import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterial;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material.PmxMaterialFlags;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class StylizedPbrBakerTest {

    @Test
    void packsGeneratedMapsIntoLabPbrChannels() {
        BufferedImage source = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        source.setRGB(0, 0, 0xff000000);
        source.setRGB(1, 0, 0xffffffff);
        source.setRGB(0, 1, 0xff808080);
        source.setRGB(1, 1, 0xff202020);
        PbrBakeProfile profile = new PbrBakeProfile(0.5f, 10, 0.0f, 0.08f, 0.0f);

        PbrBakeResult result = new StylizedPbrBaker().bake(source, profile);

        assertEquals(2, result.normalMap().getWidth());
        assertEquals(2, result.normalMap().getHeight());
        int normal = result.normalMap().getRGB(0, 0);
        assertEquals(255, normal >>> 24);
        assertEquals(255, normal & 0xff); // AO

        int specular = result.specularMap().getRGB(0, 0);
        assertEquals(0, specular >>> 24); // emission
        assertEquals(128, (specular >>> 16) & 0xff); // smoothness
        assertEquals(10, (specular >>> 8) & 0xff); // dielectric F0
        assertEquals(0, specular & 0xff); // porosity/SSS
    }

    @Test
    void automaticProfileDoesNotUseMaterialNames() {
        PbrBakeProfile namedMetal = PbrBakeProfile.from(material("gold metal skin glow"));
        PbrBakeProfile arbitrary = PbrBakeProfile.from(material("材质_07"));

        assertEquals(arbitrary, namedMetal);
    }

    @Test
    void atlasVariantLocationTracksTheCompleteProfile() {
        ResourceLocation source = ResourceLocation.fromNamespaceAndPath("kasuga_lib", "textures/pmx/shared.png");
        PbrBakeProfile first = new PbrBakeProfile(0.5f, 10, 0.0f, 0.08f, 0.0f);
        PbrBakeProfile same = new PbrBakeProfile(0.5f, 10, 0.0f, 0.08f, 0.0f);
        PbrBakeProfile different = new PbrBakeProfile(0.8f, 10, 0.0f, 0.08f, 0.0f);

        assertEquals(first.variantLocation(source), same.variantLocation(source));
        assertNotEquals(first.variantLocation(source), different.variantLocation(source));
        assertEquals(source.getNamespace(), first.variantLocation(source).getNamespace());
    }

    private static PmxMaterial material(String name) {
        return new PmxMaterial(
                name, name,
                new Vector4f(1.0f), new Vector3f(0.04f), 32.0f, new Vector3f(0.2f),
                new PmxMaterialFlags(new boolean[8]), new Vector4f(), 0.0f,
                0, -1, (byte) 0, (byte) 0, -1, name, 3
        );
    }
}
