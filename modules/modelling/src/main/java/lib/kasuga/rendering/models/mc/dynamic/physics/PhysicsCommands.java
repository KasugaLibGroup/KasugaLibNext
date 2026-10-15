package lib.kasuga.rendering.models.mc.dynamic.physics;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import lib.kasuga.rendering.models.uml.dynamic.physics.box3d.NativeBox3D;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client commands for exercising the generic physics engine in game:
 * spawning rigid-body Minecraft blocks and managing configured ragdolls.
 */
public final class PhysicsCommands {
    /** Default demo package; mirrors the built-in test MMD deployment. */
    static final ResourceLocation DEFAULT_MODEL_RESOURCE = ResourceLocation
            .fromNamespaceAndPath("kasuga_lib", "models/pmx/test3.mmd.zip");
    static final String DEFAULT_MODEL_NAME = "tda bunny miku 2.0.pmx";
    static final ResourceLocation DEFAULT_CONFIG = ResourceLocation
            .fromNamespaceAndPath("kasuga_lib", "ragdolls/tda_bunny_miku.json");

    private static final AtomicLong INSTANCE_COUNTER = new AtomicLong();

    private PhysicsCommands() {}

    public static LiteralArgumentBuilder<CommandSourceStack> physicsCommand() {
        return Commands.literal("kasuga_physics")
                .then(Commands.literal("block")
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 32))
                                .executes(context -> spawnBlocks(context,
                                        net.minecraft.world.level.block.Blocks.STONE, IntegerArgumentType.getInteger(context, "count"))))
                        .executes(context -> spawnBlocks(context,
                                net.minecraft.world.level.block.Blocks.STONE, 1)))
                .then(Commands.literal("clear").executes(context -> {
                    int removed = MinecraftBlockPhysics.clear();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Cleared " + removed + " physics blocks"), false);
                    return removed;
                }))
                .then(Commands.literal("status").executes(context -> {
                    int active = MinecraftBlockPhysics.activeCount();
                    context.getSource().sendSuccess(() -> Component.literal(
                            active + " physics block(s) simulated"), false);
                    return active;
                }));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> ragdollCommand() {
        return Commands.literal("kasuga_ragdoll")
                .then(Commands.literal("deploy")
                        .then(Commands.argument("model", ResourceLocationArgument.id())
                                .executes(context -> deploy(context,
                                        ResourceLocationArgument.getId(context, "model"), "", null)))
                        .executes(PhysicsCommands::deployDefault))
                .then(Commands.literal("list").executes(PhysicsCommands::list))
                .then(Commands.literal("remove")
                        .then(Commands.literal("all").executes(context -> {
                            int removed = MinecraftRagdollDeployments.removeAll();
                            context.getSource().sendSuccess(() -> Component.literal(
                                    "Removed " + removed + " ragdoll(s)"), false);
                            return removed;
                        })));
    }

    // ------------------------------------------------------------------
    // Physics blocks
    // ------------------------------------------------------------------

    private static int spawnBlocks(CommandContext<CommandSourceStack> context, Block block, int count) {
        CommandSourceStack source = context.getSource();
        if (!NativeBox3D.availableOrWarn()) {
            source.sendFailure(Component.literal("Box3D native library is unavailable; physics is disabled"));
            return 0;
        }
        Entity player = source.getEntity();
        if (!(player != null && player.level() instanceof net.minecraft.client.multiplayer.ClientLevel level)) {
            source.sendFailure(Component.literal("physics blocks require a client level"));
            return 0;
        }
        Vec3 look = player.getLookAngle();
        Vec3 base = player.getEyePosition().add(look.scale(2.5));
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Vec3 center = base.add(
                    (i % 4) * 1.1 - 1.65 + 0.5,
                    (i / 4) * 1.1 + 0.5,
                    ((i / 4) % 3) * 0.35);
            var spawned_prop = MinecraftBlockPhysics.spawn(level, center, block.defaultBlockState());
            if (spawned_prop.isPresent()) spawned++;
        }
        int total = MinecraftBlockPhysics.activeCount();
        int finalSpawned = spawned;
        context.getSource().sendSuccess(() -> Component.literal(
                "Spawned " + finalSpawned + " physics block(s); " + total + " active"), false);
        return spawned;
    }

    // ------------------------------------------------------------------
    // Ragdoll deployments
    // ------------------------------------------------------------------

    private static int deployDefault(CommandContext<CommandSourceStack> context) {
        return deploy(context, DEFAULT_MODEL_RESOURCE, DEFAULT_MODEL_NAME, DEFAULT_CONFIG);
    }

    private static int deploy(CommandContext<CommandSourceStack> context,
                              ResourceLocation modelResource, String modelName,
                              ResourceLocation configResource) {
        if (!NativeBox3D.availableOrWarn()) {
            context.getSource().sendFailure(Component.literal(
                    "Box3D native library is unavailable; ragdoll physics is disabled"));
            return 0;
        }
        Entity player = context.getSource().getEntity();
        if (!(player != null && player.level() instanceof net.minecraft.client.multiplayer.ClientLevel)) {
            context.getSource().sendFailure(Component.literal("ragdolls require a client level"));
            return 0;
        }
        ResourceLocation instanceId = ResourceLocation.fromNamespaceAndPath("kasuga_lib",
                "command_" + INSTANCE_COUNTER.incrementAndGet());
        Vec3 position = player.position();
        var request = new MinecraftRagdollDeployments.Request(
                modelResource,
                modelName,
                instanceId,
                configResource,
                new lib.kasuga.rendering.models.uml.math.Transform()
                        .translate((float) position.x, (float) position.y + 2f, (float) position.z),
                true,
                new Vector3d(position.x, position.y + 2.0, position.z));
        try {
            var deployment = MinecraftRagdollDeployments.deploy(request);
            if (deployment.isEmpty()) {
                context.getSource().sendFailure(Component.literal(
                        "Model package not published yet; retry after resource load"));
                return 0;
            }
            context.getSource().sendSuccess(() -> Component.literal(
                    "Deployed ragdoll " + instanceId), false);
            return 1;
        } catch (Exception exception) {
            context.getSource().sendFailure(Component.literal(
                    "Deployment failed: " + exception.getMessage()));
            return 0;
        }
    }

    private static int list(CommandContext<CommandSourceStack> context) {
        List<RagdollDeployment> deployments = MinecraftRagdollDeployments.active();
        for (RagdollDeployment deployment : deployments) {
            context.getSource().sendSuccess(() -> Component.literal(
                    deployment.instanceId() + "  model=" + deployment.modelResource()
                            + "#" + deployment.modelName()
                            + (deployment.anchoredEntity() != null ? "  anchored" : "")), false);
        }
        context.getSource().sendSuccess(() -> Component.literal(
                deployments.size() + " ragdoll(s) deployed"), false);
        return deployments.size();
    }
}
