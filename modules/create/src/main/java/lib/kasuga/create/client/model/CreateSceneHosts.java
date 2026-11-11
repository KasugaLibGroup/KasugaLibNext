package lib.kasuga.create.client.model;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import lib.kasuga.scene.SceneHost;
import lib.kasuga.scene.ScenePose;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;

/** Client-only hosts for ordinary Create contraptions. Sable/Aeronautics sublevels are a different host. */
public final class CreateSceneHosts {
    private CreateSceneHosts() {}

    /** Borrows this exact entity/contraption pair; disassembly, removal or replacement invalidates it. */
    public static SceneHost contraption(AbstractContraptionEntity entity) {
        Objects.requireNonNull(entity);
        if (!entity.level().isClientSide() || entity.getContraption() == null || entity.isRemoved()) {
            throw new IllegalArgumentException("A live client contraption is required");
        }
        var contraption = entity.getContraption();
        var level = entity.level();
        return new SceneHost() {
            @Override public boolean isAlive() {
                return !entity.isRemoved() && entity.level() == level && entity.getContraption() == contraption;
            }

            @Override public ScenePose pose(float partialTick) {
                if (!Float.isFinite(partialTick)) throw new IllegalArgumentException("Invalid partial tick");
                if (!isAlive()) return null;
                // Same interpolation and local pose as ContraptionMatrices, retaining double translation.
                Vec3 origin = new Vec3(Mth.lerp(partialTick, entity.xOld, entity.getX()),
                        Mth.lerp(partialTick, entity.yOld, entity.getY()), Mth.lerp(partialTick, entity.zOld, entity.getZ()));
                var pose = new PoseStack();
                entity.applyLocalTransforms(pose, partialTick);
                return new ScenePose(origin, pose.last().pose());
            }
        };
    }
}
