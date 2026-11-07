package test.kasuga.modelling.demo.physics;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.models.mc.dynamic.physics.PhysicsCommands;
import net.minecraft.commands.Commands;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * Development-only demo commands for deploying built-in test ragdolls.
 */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class RagdollDemoCommands {

    public static final ResourceLocation DEFAULT_MODEL_RESOURCE = ResourceLocation
            .fromNamespaceAndPath("kasuga_lib", "models/pmx/test3.mmd.zip");
    public static final String DEFAULT_MODEL_NAME = "tda bunny miku 2.0.pmx";
    public static final ResourceLocation DEFAULT_CONFIG = ResourceLocation
            .fromNamespaceAndPath("kasuga_lib", "ragdolls/tda_bunny_miku.json");

    private RagdollDemoCommands() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        // Register /kasuga_ragdoll demo subcommand for development test convenience
        event.getDispatcher().register(Commands.literal("kasuga_ragdoll")
                .then(Commands.literal("demo").executes(context ->
                        PhysicsCommands.deploy(context, DEFAULT_MODEL_RESOURCE, DEFAULT_MODEL_NAME, DEFAULT_CONFIG))));

        // Also register /ksglib_demo ragdoll deploy
        event.getDispatcher().register(Commands.literal("ksglib_demo")
                .then(Commands.literal("ragdoll")
                        .then(Commands.literal("deploy").executes(context ->
                                PhysicsCommands.deploy(context, DEFAULT_MODEL_RESOURCE, DEFAULT_MODEL_NAME, DEFAULT_CONFIG)))));
    }
}
