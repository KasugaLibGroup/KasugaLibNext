package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Alpha classification tests for native Blockbench textures.
 */
class BbModelMaterialDataTest {

    @Test
    void classifiesOpaqueCutoutAndBlendedImages() {
        BufferedImage opaque = image(0xFFFFFFFF, 0xFF112233);
        BufferedImage cutout = image(0xFFFFFFFF, 0x00000000);
        BufferedImage blended = image(0xFFFFFFFF, 0x80112233);

        assertEquals(MaterialAlphaMode.OPAQUE, BbModelMaterialData.from(opaque).alphaMode());
        assertEquals(MaterialAlphaMode.MASK, BbModelMaterialData.from(cutout).alphaMode());
        assertEquals(MaterialAlphaMode.BLEND, BbModelMaterialData.from(blended).alphaMode());
    }

    @Test
    void detectsTransparentPixelsInTheRealFanTexture() throws Exception {
        String input = Files.readString(findFanAsset());
        BbModelDefinition definition = BbModelDefinition.parse(input);
        BufferedImage image = KsgBbModelLoader.decodeImage(
                definition.textures().getFirst().source(),
                ResourceLocation.fromNamespaceAndPath("kasuga_lib", "models/be/test_fan_be.bbmodel")
        );

        assertEquals(MaterialAlphaMode.MASK, BbModelMaterialData.from(image).alphaMode());
    }

    private static BufferedImage image(int first, int second) {
        BufferedImage image = new BufferedImage(2, 1, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, first);
        image.setRGB(1, 0, second);
        return image;
    }

    private static Path findFanAsset() {
        Path relative = Path.of(
                "modules/modelling/src/contentTesting/resources/assets/kasuga_lib/models/be/test_fan_be.bbmodel");
        for (Path dir = Path.of(System.getProperty("user.dir")); dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("cannot locate test_fan_be.bbmodel");
    }
}
