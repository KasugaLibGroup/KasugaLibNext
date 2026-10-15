package lib.kasuga.rendering.models.mc.dynamic.physics;

import lib.kasuga.rendering.models.uml.dynamic.physics.core.BodyShape;
import net.minecraft.world.phys.shapes.Shapes;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftBlockRigidBodyTest {
    @Test
    void voxelBoxesBecomeBlockCenteredCompoundShapes() {
        List<BodyShape> shapes = MinecraftBlockRigidBody.collisionShapes(Shapes.or(
                Shapes.box(0d, 0d, 0d, 1d, 0.5d, 1d),
                Shapes.box(0.25d, 0.5d, 0.25d, 0.75d, 1d, 0.75d)));

        assertEquals(2, shapes.size());
        BodyShape.Box slab = (BodyShape.Box) shapes.getFirst();
        assertEquals(new Vector3f(0f, -0.25f, 0f), slab.center());
        assertEquals(new Vector3f(0.5f, 0.25f, 0.5f), slab.halfExtents());
        BodyShape.Box post = (BodyShape.Box) shapes.get(1);
        assertEquals(new Vector3f(0f, 0.25f, 0f), post.center());
        assertEquals(new Vector3f(0.25f), post.halfExtents());
    }
}
