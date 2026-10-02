package lib.kasuga.rendering.output.mc.stream;

import lib.kasuga.mixins.modelling.CameraWalkAnimationAccessor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.LivingEntity;

/** Keep independent replicas, but use the existing client's tick history for already tracked entities. */
final class CameraEntityVisuals {
    static void synchronize(ClientLevel camera, ClientLevel main) {
        for (var replica : camera.entitiesForRendering()) {
            var source = main.getEntity(replica.getId());
            if (source == null || !source.getUUID().equals(replica.getUUID())) continue;
            replica.setPos(source.getX(), source.getY(), source.getZ());
            replica.xo = source.xo; replica.yo = source.yo; replica.zo = source.zo;
            replica.xOld = source.xOld; replica.yOld = source.yOld; replica.zOld = source.zOld;
            replica.setYRot(source.getYRot()); replica.setXRot(source.getXRot());
            replica.yRotO = source.yRotO; replica.xRotO = source.xRotO;
            replica.setDeltaMovement(source.getDeltaMovement());
            replica.setOnGround(source.onGround()); replica.setPose(source.getPose());
            replica.setShiftKeyDown(source.isShiftKeyDown()); replica.setSprinting(source.isSprinting());
            replica.tickCount = source.tickCount;
            if (replica instanceof LivingEntity target && source instanceof LivingEntity living) {
                target.yBodyRot = living.yBodyRot; target.yBodyRotO = living.yBodyRotO;
                target.yHeadRot = living.yHeadRot; target.yHeadRotO = living.yHeadRotO;
                target.attackAnim = living.attackAnim; target.oAttackAnim = living.oAttackAnim;
                target.hurtTime = living.hurtTime; target.deathTime = living.deathTime;
                var from = (CameraWalkAnimationAccessor) living.walkAnimation;
                var to = (CameraWalkAnimationAccessor) target.walkAnimation;
                to.kasuga$previousSpeed(from.kasuga$previousSpeed());
                to.kasuga$speed(from.kasuga$speed()); to.kasuga$position(from.kasuga$position());
            }
        }
    }
}
