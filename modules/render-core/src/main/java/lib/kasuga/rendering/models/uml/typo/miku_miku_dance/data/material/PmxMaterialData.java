package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material;

import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;

import java.awt.image.BufferedImage;

/** Renderer-facing alpha metadata inferred from the PMX diffuse material/texture. */
public record PmxMaterialData(MaterialAlphaMode alphaMode, float alphaCutoff) implements MaterialData {

    public static PmxMaterialData from(PmxMaterial material, BufferedImage image) {
        if (material.diffuseColor.w < 0.999f) {
            return new PmxMaterialData(MaterialAlphaMode.BLEND, 0.5f);
        }
        if (image != null && hasPartialAlpha(image)) {
            return new PmxMaterialData(MaterialAlphaMode.BLEND, 0.5f);
        }
        if (image != null && hasTransparentPixels(image)) {
            return new PmxMaterialData(MaterialAlphaMode.MASK, 0.5f);
        }
        return new PmxMaterialData(MaterialAlphaMode.OPAQUE, 0.5f);
    }

    private static boolean hasTransparentPixels(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) == 0) return true;
            }
        }
        return false;
    }

    private static boolean hasPartialAlpha(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha > 0 && alpha < 255) return true;
            }
        }
        return false;
    }
}
