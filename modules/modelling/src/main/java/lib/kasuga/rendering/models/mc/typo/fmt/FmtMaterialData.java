package lib.kasuga.rendering.models.mc.typo.fmt;

import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;

import java.awt.image.BufferedImage;

/** Alpha contract for FMT's image-only legacy materials. */
record FmtMaterialData(MaterialAlphaMode alphaMode, float alphaCutoff) implements MaterialData {

    private static final float ALPHA_CUTOFF = 0.5f;

    static FmtMaterialData from(BufferedImage image) {
        if (image != null && hasTransparentPixels(image)) {
            // FMT has no blend-material metadata. Its PNG alpha is used for
            // cutout details, so retain the opaque pass and discard holes.
            return new FmtMaterialData(MaterialAlphaMode.MASK, ALPHA_CUTOFF);
        }
        return new FmtMaterialData(MaterialAlphaMode.OPAQUE, ALPHA_CUTOFF);
    }

    private static boolean hasTransparentPixels(BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) != 255) return true;
            }
        }
        return false;
    }
}
