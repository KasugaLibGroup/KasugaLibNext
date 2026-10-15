package lib.kasuga.rendering.output.mc;

import lib.kasuga.rendering.output.WorldCameraView;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;

/** A detached camera; never changes the player's entity or the main camera. */
final class WorldViewCamera extends Camera {
    private WorldCameraView view;
    void configure(BlockGetter level, Entity anchor, float partialTick, WorldCameraView view) {
        this.view = view;
        // Initialize the level/entity/partial-tick state, then replace the pose.
        super.setup(level, anchor, false, false, partialTick);
        setPosition(view.x(), view.y(), view.z());
        setRotation(view.yaw(), view.pitch(), view.roll());
    }

    @Override public boolean isDetached() { return true; }

    // Vanilla constructs NearPlane using the window aspect/FOV. The small
    // constructor accessor keeps fluid fog sampling consistent with this view.
    @Override public NearPlane getNearPlane() {
        if (view == null) return super.getNearPlane();
        double halfHeight = Math.tan(Math.toRadians(view.verticalFov()) / 2) * 0.05;
        return lib.kasuga.mixins.modelling.CameraNearPlaneAccessor.kasuga$create(
                new net.minecraft.world.phys.Vec3(getLookVector()).scale(0.05),
                new net.minecraft.world.phys.Vec3(getLeftVector()).scale(halfHeight * view.aspectRatio()),
                new net.minecraft.world.phys.Vec3(getUpVector()).scale(halfHeight));
    }
}
