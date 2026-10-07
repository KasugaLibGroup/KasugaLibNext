package lib.kasuga.test.registration.data_driven;

import lib.kasuga.registration.data_driven.dedup.EffectiveId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Table test for {@link EffectiveId}, the pure normalization used to detect duplicate ids before any
 * registration side effect.
 *
 * <p>Rule: an id with no namespace, or with the {@code minecraft} namespace, resolves under the
 * owning mod's namespace ({@code <modId>:<path>}); any other explicit namespace is kept verbatim.
 * This is exactly what the registration pipeline does ({@code JsonTreeBuilder} maps the root group to
 * {@code <modId>:<path>} and {@code RegTypeHandler} only overrides it for a non-{@code minecraft}
 * namespace, with {@code Reg.applyProperties} resolving the parent chain first).
 */
class EffectiveIdTest {

    private static final String MOD = "mymod";

    @Test
    void unnamespacedIdsResolveUnderTheModNamespace() {
        assertEquals("mymod:foo", EffectiveId.of(MOD, "foo"));
        assertEquals("mymod:panel_1", EffectiveId.of(MOD, "panel_1"));
    }

    @Test
    void unnamespacedIdsUseTheOwningModVerbatim() {
        assertEquals("kuayue:foo", EffectiveId.of("kuayue", "foo"));
        assertEquals("kasuga_lib:panel", EffectiveId.of("kasuga_lib", "panel"));
    }

    @Test
    void explicitMinecraftNamespaceResolvesToTheOwningMod() {
        // Blind spot fixed by rule B: "foo" and "minecraft:foo" register the same location.
        assertEquals("mymod:foo", EffectiveId.of(MOD, "minecraft:foo"));
        assertEquals(EffectiveId.of(MOD, "foo"), EffectiveId.of(MOD, "minecraft:foo"));
    }

    @Test
    void otherNamespacesAreKeptVerbatim() {
        assertEquals("kasuga_lib:foo", EffectiveId.of(MOD, "kasuga_lib:foo"));
        assertEquals("minecraft2:foo", EffectiveId.of(MOD, "minecraft2:foo"));
        assertEquals("a:foo", EffectiveId.of(MOD, "a:foo"));
        // An explicit namespace equal to the mod's own id is equal to the bare id (same location).
        assertEquals("mymod:foo", EffectiveId.of(MOD, "mymod:foo"));
        assertEquals(EffectiveId.of(MOD, "foo"), EffectiveId.of(MOD, "mymod:foo"));
    }

    @Test
    void onlyTheFirstColonSeparatesNamespaceFromPath() {
        assertEquals("mymod:foo:bar", EffectiveId.of(MOD, "minecraft:foo:bar"));
        assertEquals("a:b:c", EffectiveId.of(MOD, "a:b:c"));
        assertEquals("mymod:a:b", EffectiveId.of(MOD, "mymod:a:b"));
    }

    @Test
    void modIdIsUsedVerbatimForTheNamespace() {
        assertEquals("a_mod_2:x", EffectiveId.of("a_mod_2", "x"));
    }

    @Test
    void nullRawIdStaysNull() {
        assertNull(EffectiveId.of(MOD, null));
    }
}
