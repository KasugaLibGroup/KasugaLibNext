package lib.kasuga.rendering.output.mc;

import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/** Exact geometric invalidation; compiled mesh contents do not change section bounds. */
public final class ViewFrustumCache {
    private final Matrix4f view = new Matrix4f(), projection = new Matrix4f();
    private double x, y, z;
    private Object grid;
    private boolean initialized;

    public boolean update(Vec3 position, Matrix4f nextView, Matrix4f nextProjection, Object nextGrid) {
        if (initialized && grid == nextGrid && x == position.x && y == position.y && z == position.z
                && view.equals(nextView) && projection.equals(nextProjection)) return false;
        initialized = true;
        grid = nextGrid;
        x = position.x; y = position.y; z = position.z;
        view.set(nextView); projection.set(nextProjection);
        return true;
    }
}
