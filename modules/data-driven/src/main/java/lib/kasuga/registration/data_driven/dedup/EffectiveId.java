package lib.kasuga.registration.data_driven.dedup;

/**
 * Normalizes a data-driven definition's raw id to the {@code ResourceLocation} it will ultimately be
 * registered under, so duplicates can be detected before any registration side effect happens.
 *
 * <p>The normalization mirrors the registration pipeline:
 * <ul>
 *   <li>{@code JsonTreeBuilder.buildForMod} attaches to the mod's root group a mapping
 *       {@code loc -> fromNamespaceAndPath(modId, loc.getPath())}, so every id ends up under the
 *       owning mod's namespace by default;</li>
 *   <li>{@code RegTypeHandler} only attaches its own {@code ResourceLocation} mapping when the raw id
 *       carries a namespace other than {@code minecraft}.</li>
 * </ul>
 *
 * <p>{@code Reg.applyProperties} resolves the parent chain first and the registration's own mappings
 * last, so the two cases above combine to:
 * <pre>
 *   "foo"             -&gt; "&lt;modId&gt;:foo"
 *   "minecraft:foo"   -&gt; "&lt;modId&gt;:foo"
 *   "kasuga_lib:foo"  -&gt; "kasuga_lib:foo"
 * </pre>
 *
 * <p>This closes a namespace blind spot where {@code "foo"} and {@code "minecraft:foo"} were treated
 * as distinct ids although both register the same {@code ResourceLocation}.
 *
 * <p>This class is a pure, allocation-light function of its inputs (no Minecraft types), so its table
 * can be locked down by a plain JVM test.
 */
public final class EffectiveId {

    /**
     * Returns the effective id for {@code rawId} under mod {@code modId}.
     *
     * @param modId  the owning mod's id, used when {@code rawId} carries no usable namespace
     * @param rawId  the raw id from JSON, with or without a {@code namespace:path} prefix
     * @return the effective id, or {@code null} when {@code rawId} is {@code null}
     */
    public static String of(String modId, String rawId) {
        if (rawId == null) {
            return null;
        }
        int colon = rawId.indexOf(':');
        if (colon < 0) {
            return modId + ":" + rawId;
        }
        String namespace = rawId.substring(0, colon);
        if ("minecraft".equals(namespace)) {
            return modId + ":" + rawId.substring(colon + 1);
        }
        return rawId;
    }

    private EffectiveId() {}
}
