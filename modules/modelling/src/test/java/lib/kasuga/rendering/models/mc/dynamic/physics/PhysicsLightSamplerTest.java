package lib.kasuga.rendering.models.mc.dynamic.physics;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import org.joml.Quaternionf;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PhysicsLightSamplerTest {
    @Test void normalSpaceKeepsTheDirectVanillaLightUnchanged() {
        int direct = LightTexture.pack(3, 11);
        var choice = PhysicsLightSampler.choose(direct, LightTexture.pack(15, 15), 0,
                List.of(LightTexture.pack(15, 15)), false);
        assertEquals(direct, choice.packedLight());
        assertEquals(0f, choice.indirectStrength());
        assertFalse(choice.fallback());
    }

    @Test void embeddedObjectsUseCurrentSurfaceLightBeforeTheOriginalAnchor() {
        int direct = LightTexture.pack(0, 0);
        int anchor = LightTexture.pack(2, 7);
        int neighbour = LightTexture.pack(5, 4);
        var choice = PhysicsLightSampler.choose(direct, anchor, 0, List.of(neighbour), true);
        assertEquals(5, PhysicsLightSampler.lightBlock(choice.packedLight()));
        assertEquals(4, PhysicsLightSampler.lightSky(choice.packedLight()));
        assertTrue(choice.indirectStrength() > 0f);
        assertTrue(choice.indirectStrength() <= .24f);
    }

    @Test void embeddedObjectsKeepValidDirectLightAsTheBaseline() {
        int direct = LightTexture.pack(9, 12);
        var choice = PhysicsLightSampler.choose(direct, LightTexture.pack(1, 1), 0,
                List.of(LightTexture.pack(2, 2)), true);
        assertEquals(9, PhysicsLightSampler.lightBlock(choice.packedLight()));
        assertEquals(12, PhysicsLightSampler.lightSky(choice.packedLight()));
        assertTrue(choice.indirectStrength() < .08f,
                "bright direct light must not receive a visible extra bounce");
    }

    @Test void zeroEnergyScenesDoNotInventSkyOrBounceLight() {
        var choice = PhysicsLightSampler.choose(0, 0, 0, List.of(), true);
        assertEquals(0, choice.packedLight());
        assertEquals(0, choice.indirectStrength());
    }

    @Test void previousValidLightIsTheLastFallbackWhenTheWallHasNoOpenNeighbour() {
        int previous = LightTexture.pack(6, 9);
        var choice = PhysicsLightSampler.choose(0, 0, previous, List.of(), true);
        assertEquals(previous, choice.packedLight());
        assertTrue(choice.fallback());
    }

    @Test void currentSurfaceProbesBeatAStalePreviousLight() {
        int previous = LightTexture.pack(9, 9);
        int currentSurface = LightTexture.pack(2, 2);
        var choice = PhysicsLightSampler.choose(0, 0, previous, List.of(currentSurface), true);
        assertEquals(2, PhysicsLightSampler.lightBlock(choice.packedLight()));
        assertEquals(2, PhysicsLightSampler.lightSky(choice.packedLight()));
    }

    @Test void packedLightChannelsAreClampedAndFinite() {
        int packed = PhysicsLightSampler.choose(-1, Integer.MAX_VALUE, Integer.MIN_VALUE,
                List.of(Integer.MAX_VALUE, -1), true).packedLight();
        assertTrue(PhysicsLightSampler.lightBlock(packed) >= 0);
        assertTrue(PhysicsLightSampler.lightBlock(packed) <= 15);
        assertTrue(PhysicsLightSampler.lightSky(packed) >= 0);
        assertTrue(PhysicsLightSampler.lightSky(packed) <= 15);
    }

    @Test void orientedProbeOverlapTracksRotationInsteadOfAnAxisAlignedGuess() {
        var rotation = new Quaternionf().rotateY((float) Math.toRadians(45));
        assertTrue(PhysicsLightSampler.overlapsBlock(
                new Vec3(1.12, .5, .5), new Vec3(.65, .12, .12), rotation,
                new BlockPos(1, 0, 0)));
        assertFalse(PhysicsLightSampler.overlapsBlock(
                new Vec3(2.4, .5, .5), new Vec3(.2, .12, .12), rotation,
                new BlockPos(1, 0, 0)));
    }

    @Test void touchingAStaticBlockDoesNotEnterTheEmbeddedFallback() {
        var identity = new Quaternionf();
        assertFalse(PhysicsLightSampler.overlapsBlock(
                new Vec3(.5, .5, .5), new Vec3(.5, .5, .5), identity,
                new BlockPos(1, 0, 0)),
                "a prop resting against a wall is touching, not embedded");
        assertTrue(PhysicsLightSampler.overlapsBlock(
                new Vec3(.51, .5, .5), new Vec3(.5, .5, .5), identity,
                new BlockPos(1, 0, 0)),
                "a prop pushed into a wall must still enter the embedded path");
    }
}
