package test.kasuga.modelling.fsm;

import lib.kasuga.rendering.models.mc.dynamic.collision.MinecraftModelCollisions;
import lib.kasuga.rendering.models.mc.dynamic.collision.ServerCollisionModels;
import lib.kasuga.rendering.models.uml.dynamic.SkeletonInstance;
import lib.kasuga.rendering.models.uml.dynamic.physics.BoneCollisionBounds;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder("kasuga_lib")
@PrefixGameTestTemplate(false)
public final class ModelCollisionGameTest {
    private ModelCollisionGameTest() {}

    @GameTest(templateNamespace = "kasuga_lib", template = "fsmblockgametest.empty")
    public static void animatedBoneBlocksMovementOnDedicatedServer(GameTestHelper helper) {
        BlockPos hostPos = new BlockPos(1, 3, 1);
        helper.setBlock(hostPos, Blocks.FURNACE);
        BlockEntity host = helper.getBlockEntity(hostPos);
        ServerCollisionModels.Asset asset = ServerCollisionModels.find(helper.getLevel(),
                ResourceLocation.parse("kasuga_lib:models/collision_test.bbmodel")).orElseThrow();
        BlockPos world = helper.absolutePos(hostPos);
        ArmorStand stand = EntityType.ARMOR_STAND.create(helper.getLevel());
        stand.setNoGravity(true);
        stand.setPos(world.getX() + 3.0, world.getY(), world.getZ() - 1.0);
        helper.getLevel().addFreshEntity(stand);
        double startZ = stand.getZ();
        try (MinecraftModelCollisions.Handle handle = MinecraftModelCollisions.attach(host, asset)) {
            SkeletonInstance skeleton = handle.instance().getSkeletonInstance();
            BoneCollisionBounds.WorldSampler sampler = asset.bounds().newWorldSampler();
            List<BoneCollisionBounds.Box> bindBounds = sampler.sample(skeleton);
            handle.tick(1f / 20f);
            helper.assertTrue(bindBounds == sampler.sample(skeleton), "Stationary boxes should reuse their snapshot");
            stand.move(MoverType.SELF, new Vec3(0, 0, 2));
            helper.assertTrue(stand.getZ() < startZ + 1, "The bind-pose box should stop movement");
            helper.assertTrue(handle.instance().getPosing().play("retract", false), "Server clip loaded");
            handle.tick(1f);
            helper.assertTrue(!bindBounds.equals(sampler.sample(skeleton)), "Moving the bone should refresh its bounds");
            stand.setPos(stand.getX(), stand.getY(), startZ);
            stand.move(MoverType.SELF, new Vec3(0, 0, 2));
            helper.assertTrue(stand.getZ() > startZ + 1.9, "Animation should move collision out of the old path");
            stand.setPos(stand.getX() + 2, stand.getY(), startZ);
            stand.move(MoverType.SELF, new Vec3(0, 0, 2));
            helper.assertTrue(stand.getZ() < startZ + 1, "The translated bone should stop movement");
        }
        stand.setPos(stand.getX(), stand.getY(), startZ);
        stand.move(MoverType.SELF, new Vec3(0, 0, 2));
        helper.assertTrue(stand.getZ() > startZ + 1.9, "Closing releases the translated box immediately");
        stand.discard();
        helper.setBlock(hostPos, Blocks.AIR);
        helper.succeed();
    }
}
