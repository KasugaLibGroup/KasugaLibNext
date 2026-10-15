package lib.kasuga.rendering.output.mc.stream;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;

/** Actual transformed entities; called only by the opt-in motion regression. */
public final class CameraEntityVisualsChecks {
    private CameraEntityVisualsChecks() {}
    public static void verifyStableBounds(ClientLevel camera, ClientLevel main, Entity replica) {
        var bounds = replica.getBoundingBox();
        CameraEntityVisuals.synchronize(camera, main, false);
        if (replica.getBoundingBox() != bounds)
            throw new IllegalStateException("Unchanged visual synchronization rebuilt entity bounds");
    }
}
