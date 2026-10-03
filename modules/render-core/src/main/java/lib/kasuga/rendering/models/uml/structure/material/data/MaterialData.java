package lib.kasuga.rendering.models.uml.structure.material.data;

public interface MaterialData {

    /**
     * The renderer-facing alpha mode. Legacy/custom material data defaults to
     * opaque so an absent contract cannot accidentally enter the translucent
     * path.
     */
    default MaterialAlphaMode alphaMode() {
        return MaterialAlphaMode.OPAQUE;
    }

    /** The MASK threshold. Ignored for OPAQUE and BLEND materials. */
    default float alphaCutoff() {
        return 0.5f;
    }
}
