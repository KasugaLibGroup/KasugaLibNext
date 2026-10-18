package lib.kasuga.rendering.models.mc.backend;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Pixel-level reference for mixed opaque/transparent PMX textures. */
class OitOpaqueCoverageTest {

    @Test
    void opaqueAndTranslucentCoverageAreDisjointWithoutLosingSoftAlpha() {
        for (int alpha = 0; alpha <= 255; alpha++) {
            float value = alpha / 255f;
            assertFalse(OitMath.writesOpaqueCoverage(value) && OitMath.contributesToOit(value));
            assertEquals(alpha > 1,
                    OitMath.writesOpaqueCoverage(value) || OitMath.contributesToOit(value));
        }
        assertTrue(OitMath.contributesToOit(0.25f));
        assertTrue(OitMath.contributesToOit(Math.nextDown(1f)));
        assertFalse(OitMath.contributesToOit(1f));
    }

    @Test
    void materialOrVertexFadeDoesNotLeaveAnOpaqueDepthOccluder() {
        float textureAlpha = 1f;
        float materialAlpha = 0.4f;
        float vertexAlpha = 0.5f;
        float effectiveAlpha = textureAlpha * materialAlpha * vertexAlpha;
        assertFalse(OitMath.writesOpaqueCoverage(effectiveAlpha));
        assertTrue(OitMath.contributesToOit(effectiveAlpha));
    }

    @Test
    void solidFaceOccludesRearHairInEitherSubmissionOrder() {
        List<Fragment> fragments = List.of(
                new Fragment(0.2f, 0.8f, 1f),
                new Fragment(0.4f, 0.1f, 1f),
                new Fragment(0.3f, 0.2f, 0.5f));
        assertEquals(0.8f, render(fragments), 1e-6f);
        assertEquals(0.8f, render(fragments.reversed()), 1e-6f);
    }

    @Test
    void sheerLayerInFrontOfSolidClothStillBlends() {
        List<Fragment> fragments = List.of(
                new Fragment(0.4f, 0.8f, 1f),
                new Fragment(0.2f, 0.2f, 0.25f),
                new Fragment(0.6f, 0f, 0.8f));
        // 0.2 * 0.25 + 0.8 * 0.75, with the hidden rear layer rejected.
        assertEquals(0.65f, render(fragments), 1e-6f);
        assertEquals(0.65f, render(fragments.reversed()), 1e-6f);
    }

    @Test
    void transparentTexelsDoNotOccludeTheBackground() {
        assertEquals(0.6f, render(List.of(new Fragment(0.2f, 0f, 0f))), 1e-6f);
        assertEquals(0.6f, render(List.of()), 1e-6f);
    }

    private static float render(List<Fragment> fragments) {
        float depth = 1f;
        float sceneColor = 0.6f;
        for (Fragment fragment : fragments) {
            if (OitMath.writesOpaqueCoverage(fragment.alpha()) && fragment.depth() <= depth) {
                depth = fragment.depth();
                sceneColor = fragment.color();
            }
        }
        float color = 0f, weight = 0f, revealage = 1f;
        for (Fragment fragment : fragments) {
            if (!OitMath.contributesToOit(fragment.alpha()) || fragment.depth() > depth) continue;
            float w = OitMath.depthWeight(fragment.depth());
            color = OitMath.accumulateColor(color, fragment.color(), fragment.alpha(), w);
            weight = OitMath.accumulateWeight(weight, fragment.alpha(), w);
            revealage = OitMath.multiplyRevealage(revealage, fragment.alpha());
        }
        return OitMath.sourceOver(sceneColor, OitMath.resolveColor(color, weight),
                OitMath.resolveAlpha(revealage));
    }

    private record Fragment(float depth, float color, float alpha) {}
}
