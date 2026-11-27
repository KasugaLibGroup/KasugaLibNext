package lib.kasuga.rendering.models.mc.shape;

import lib.kasuga.geometry.Box;
import lib.kasuga.geometry.Shape;
import lib.kasuga.registration.minecraft.block.shape.MinecraftShapeAdapter;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ShapeOutlineGeometryTest {
    @Test
    void holeAndDisconnectedBoundaryMatchTheVoxelEdgesWithoutInternalSeams() {
        Shape frame = Shape.of(Box.fromPixels(0, 0, 0, 16, 16, 2))
                .difference(Shape.of(Box.fromPixels(4, 0, 0, 12, 12, 2)));
        Shape disconnected = frame.union(
                Shape.of(Box.fromPixels(20, 0, 0, 24, 4, 4))
        );

        List<ShapeOutlineGeometry.Segment> local = ShapeOutlineGeometry.local(disconnected);
        List<ShapeOutlineGeometry.Segment> voxel = voxelEdges(
                MinecraftShapeAdapter.toVoxelShape(disconnected));

        assertFalse(disconnected.contains(0.5f, 0.5f, 0.0625f));
        assertTrue(disconnected.contains(0.125f, 0.5f, 0.0625f));
        assertTrue(disconnected.contains(1.25f, 0.125f, 0.125f));
        assertFalse(disconnected.contains(0.625f, 0.5f, 0.0625f));
        assertTrue(local.size() > 12);
        assertEquals(local.size(), voxel.size());
        assertEquals(new HashSet<>(local), new HashSet<>(voxel));
        assertFalse(local.contains(new ShapeOutlineGeometry.Segment(
                0.5, 0.0, 0.0,
                0.5, 0.6875, 0.0
        )));
    }

    @Test
    void worldOutlineRaisesFloatLocalCoordinatesBeforeAddingDoubleOrigin() {
        Shape shape = Shape.of(Box.fromPixels(1, 2, 3, 15, 14, 13));
        BlockPos origin = new BlockPos(30_000_000, -64, -29_999_999);

        List<ShapeOutlineGeometry.Segment> segments = ShapeOutlineGeometry.at(shape, origin);

        assertFalse(segments.isEmpty());
        for (ShapeOutlineGeometry.Segment segment : segments) {
            assertTrue(segment.startX() == (double) origin.getX() + (double) 0.0625f
                    || segment.startX() == (double) origin.getX() + (double) 0.9375f);
            assertTrue(segment.endX() == (double) origin.getX() + (double) 0.0625f
                    || segment.endX() == (double) origin.getX() + (double) 0.9375f);
        }
    }

    private static List<ShapeOutlineGeometry.Segment> voxelEdges(VoxelShape shape) {
        List<ShapeOutlineGeometry.Segment> result = new ArrayList<>();
        shape.forAllEdges((x1, y1, z1, x2, y2, z2) -> result.add(
                new ShapeOutlineGeometry.Segment(x1, y1, z1, x2, y2, z2)
        ));
        return List.copyOf(result);
    }
}
