package lib.kasuga.rendering.models.uml.structure.material.data;

/**
 * Format-neutral alpha classification consumed by the Minecraft renderer.
 *
 * <p>Keeping this contract in the model layer lets loaders retain their
 * format-specific metadata while the backend applies one consistent pass
 * policy.</p>
 */
public enum MaterialAlphaMode {
    /** The material is treated as fully opaque regardless of sampled alpha. */
    OPAQUE,
    /** Alpha is tested against {@link MaterialData#alphaCutoff()} and retained fragments write depth. */
    MASK,
    /** Conventional source-over blending; geometry is rendered back-to-front. */
    BLEND
}
