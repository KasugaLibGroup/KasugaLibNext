package lib.kasuga.rendering.output.mc;

import lib.kasuga.rendering.output.camera.CameraTarget;
import net.minecraft.world.entity.Entity;

import java.util.Objects;
import java.util.function.Supplier;

/** Entity adapters for the common camera target. Samples the source level, before any view-world swap. */
public final class MinecraftCameraTargets {
    private MinecraftCameraTargets() {}

    public static CameraTarget entity(Entity entity) { Objects.requireNonNull(entity); return entity(() -> entity); }

    /** A live supplier can reacquire its entity after respawn or a dimension change. */
    public static CameraTarget entity(Supplier<? extends Entity> entity) {
        Objects.requireNonNull(entity);
        return partialTick -> {
            Entity target = entity.get();
            if (target == null || target.isRemoved() || target.level() != MinecraftWorldViews.sourceLevel()) return null;
            var position = target.getEyePosition(partialTick);
            return new CameraTarget.Pose(position.x, position.y, position.z,
                    target.getViewYRot(partialTick), target.getViewXRot(partialTick), 0);
        };
    }
}
