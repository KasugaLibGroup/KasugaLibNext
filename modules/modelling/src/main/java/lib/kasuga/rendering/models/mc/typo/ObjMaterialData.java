package lib.kasuga.rendering.models.mc.typo;

import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;

/**
 * Alpha contract inferred from an OBJ material and its diffuse texture.
 *
 * <p>OBJ/MTL commonly omits {@code d} even when the PNG uses alpha as a cutout.
 * Treating that combination as opaque makes transparent texels render black.
 */
record ObjMaterialData(MaterialAlphaMode alphaMode, float alphaCutoff) implements MaterialData {

    static final float DEFAULT_ALPHA_CUTOFF = 0.5f;

    static ObjMaterialData from(
            float dissolve,
            boolean hasTransparentPixels,
            boolean hasPartialTransparency
    ) {
        if ((Float.isFinite(dissolve) && dissolve < 1f) || hasPartialTransparency) {
            return new ObjMaterialData(MaterialAlphaMode.BLEND, DEFAULT_ALPHA_CUTOFF);
        }
        if (hasTransparentPixels) {
            return new ObjMaterialData(MaterialAlphaMode.MASK, DEFAULT_ALPHA_CUTOFF);
        }
        return new ObjMaterialData(MaterialAlphaMode.OPAQUE, DEFAULT_ALPHA_CUTOFF);
    }
}
