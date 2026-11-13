package lib.kasuga.rendering.models.mc.typo.bbmodel;

import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;

/**
 * Alpha contract inferred from a Blockbench texture.
 *
 * <p>Native BBModel texture entries commonly leave {@code render_mode} at {@code default}, so that
 * field cannot distinguish opaque and cutout content. Inspect the decoded image instead: transparent
 * texels must discard or blend or they render as black holes.
 *
 * @param alphaMode renderer-facing alpha mode
 * @param alphaCutoff cutout threshold
 */
record BbModelMaterialData(MaterialAlphaMode alphaMode, float alphaCutoff) implements MaterialData {

    static final float DEFAULT_ALPHA_CUTOFF = 0.5f;

    static BbModelMaterialData from(@Nullable BufferedImage image) {
        if (image == null) {
            return new BbModelMaterialData(MaterialAlphaMode.OPAQUE, DEFAULT_ALPHA_CUTOFF);
        }
        boolean transparent = false;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int alpha = image.getRGB(x, y) >>> 24;
                if (alpha == 255) continue;
                transparent = true;
                if (alpha != 0) {
                    return new BbModelMaterialData(MaterialAlphaMode.BLEND, DEFAULT_ALPHA_CUTOFF);
                }
            }
        }
        return new BbModelMaterialData(
                transparent ? MaterialAlphaMode.MASK : MaterialAlphaMode.OPAQUE,
                DEFAULT_ALPHA_CUTOFF
        );
    }
}
