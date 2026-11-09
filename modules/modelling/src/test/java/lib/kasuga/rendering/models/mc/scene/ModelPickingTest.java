package lib.kasuga.rendering.models.mc.scene;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class ModelPickingTest {
    @Test void inversePlacementAndHitPointFollowRotationScaleAndExplosion() {
        var transform = new Matrix4f().translate(4, 8, -2).rotateY((float) Math.PI / 2).scale(2, 3, 4).translate(0, 2, 0);
        var original = new Matrix4f(transform);
        var picker = MinecraftModelSource.placedPicker(transform, (from, to) -> {
            assertEquals(0, from.x, 1e-5); assertEquals(0, from.y, 1e-5); assertEquals(-2, from.z, 1e-5);
            assertEquals(0, to.x, 1e-5); assertEquals(0, to.y, 1e-5); assertEquals(2, to.z, 1e-5);
            // No block registry is required to verify affine picking; production pickers supply the state.
            return Optional.of(new MinecraftModelSource.BlockHit(BlockPos.ZERO, null, Vec3.ZERO, Direction.NORTH));
        });
        var hit = picker.pick(new Vec3(-4, 14, -2), new Vec3(12, 14, -2)).orElseThrow();
        assertEquals(new Vec3(4, 14, -2), hit.location()); assertEquals(BlockPos.ZERO, hit.position());
        assertEquals(original, transform);
    }
    @Test void nearestDisplayedHitWinsAndLegacyPartsAreNotPickable() {
        var bounds = new ModelBounds(0, 0, 0, 1, 1, 1);
        var far = new MinecraftModelSource.Part("far", 0, bounds, context -> {}, (a,b) -> Optional.of(
                new MinecraftModelSource.BlockHit(new BlockPos(0, 0, 8), null, new Vec3(0, 0, 8), Direction.NORTH)));
        var near = new MinecraftModelSource.Part("near", 0, bounds, context -> {}, (a,b) -> Optional.of(
                new MinecraftModelSource.BlockHit(new BlockPos(0, 0, 2), null, new Vec3(0, 0, 2), Direction.NORTH)));
        var frame = new MinecraftModelSource.Snapshot(bounds, List.of(far, near));
        assertEquals(new BlockPos(0, 0, 2), frame.pick(Vec3.ZERO, new Vec3(0, 0, 10)).orElseThrow().position());
        assertTrue(new MinecraftModelSource.Snapshot(bounds, List.of(new MinecraftModelSource.Part("legacy", 0, bounds, context -> {})))
                .pick(Vec3.ZERO, new Vec3(0, 0, 10)).isEmpty());
    }
}
