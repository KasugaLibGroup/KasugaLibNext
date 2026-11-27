package lib.kasuga.registration.minecraft.block.shape;

import lib.kasuga.geometry.Box;
import lib.kasuga.geometry.Shape;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.Objects;

/** Converts immutable float block-local geometry to one immutable Minecraft VoxelShape. */
public final class MinecraftShapeAdapter {
    private MinecraftShapeAdapter() {
    }

    public static VoxelShape toVoxelShape(Shape shape) {
        Objects.requireNonNull(shape, "shape");
        if (shape.isEmpty()) return Shapes.empty();

        VoxelShape result = Shapes.empty();
        for (Box box : shape.boxes()) {
            VoxelShape part = Shapes.create(
                    box.minX(),
                    box.minY(),
                    box.minZ(),
                    box.maxX(),
                    box.maxY(),
                    box.maxZ()
            );
            if (part.isEmpty()) {
                throw new IllegalArgumentException(
                        "Minecraft cannot represent shape fragment " + box
                                + " at its 1e-7 host precision");
            }
            result = Shapes.joinUnoptimized(result, part, BooleanOp.OR);
        }
        return result.optimize();
    }
}
