package lib.kasuga.rendering.models.mc.backend;

import lib.kasuga.rendering.models.uml.structure.material.Material;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialAlphaMode;
import lib.kasuga.rendering.models.uml.structure.material.data.MaterialData;

/**
 * The three material passes used by the world renderer.
 *
 * <p>BLEND intentionally uses a small rejection threshold. It removes
 * effectively invisible fragments without changing the alpha used by source-
 * over blending; the native backend may route the surviving fragments through
 * weighted-blended OIT.</p>
 */
public enum ModelRenderPass {
    OPAQUE(0),
    MASK(1),
    TRANSLUCENT(2);

    /** One 8-bit alpha step: small enough not to erase visible translucent pixels. */
    public static final float BLEND_ALPHA_CUTOFF = 1.0f / 255.0f;

    private final int shaderAlphaMode;

    ModelRenderPass(int shaderAlphaMode) {
        this.shaderAlphaMode = shaderAlphaMode;
    }

    /** 0 = opaque, 1 = mask, 2 = conventional blend. */
    public int shaderAlphaMode() {
        return shaderAlphaMode;
    }

    public boolean matches(Material material) {
        return classify(material) == this;
    }

    public static ModelRenderPass classify(Material material) {
        MaterialData data = material == null ? null : material.getData();
        return from(data == null ? MaterialAlphaMode.OPAQUE : data.alphaMode());
    }

    public static ModelRenderPass from(MaterialAlphaMode mode) {
        if (mode == null) return OPAQUE;
        return switch (mode) {
            case OPAQUE -> OPAQUE;
            case MASK -> MASK;
            case BLEND -> TRANSLUCENT;
        };
    }

    /**
     * Returns the per-vertex cutoff consumed by the fragment shader. MASK
     * preserves a valid source cutoff; BLEND uses the fixed near-zero cutoff.
     */
    public static float alphaCutoff(Material material) {
        ModelRenderPass pass = classify(material);
        if (pass == TRANSLUCENT) return BLEND_ALPHA_CUTOFF;
        if (pass != MASK || material == null || material.getData() == null) return 0f;

        float cutoff = material.getData().alphaCutoff();
        return Float.isFinite(cutoff) ? Math.clamp(cutoff, 0f, 1f) : 0.5f;
    }
}
