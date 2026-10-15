package test.kasuga.modelling;

import lib.kasuga.KasugaLib;
import lib.kasuga.rendering.effect.builtin.blackhole.BlackHoleEffect;
import lib.kasuga.rendering.effect.builtin.blackhole.BlackHoleEffects;
import lib.kasuga.rendering.effect.shader.RenderShaderHandle;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/** Development-client demonstration for the generated black-hole post-processing pipeline. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class BlackHoleDemo {
    private static final ResourceLocation SINGLE_ID = id("demo_single");
    private static final ResourceLocation[] ORBIT_IDS = {
            id("demo_orbit_0"), id("demo_orbit_1"), id("demo_orbit_2")
    };

    private static ClientLevel activeLevel;
    private static Mode mode = Mode.OFF;
    private static Vec3 orbitCenter = Vec3.ZERO;
    private static Vec3 orbitRight = new Vec3(1, 0, 0);
    private static Vec3 orbitUp = new Vec3(0, 1, 0);
    private static int autoSpawnDelay;
    private static boolean autoSpawnHandled;

    /** Auto-spawn on world join is DISABLED by default — trigger manually via {@code /kasuga_black_hole demo|orbit|massive}. */
    private static final boolean AUTO_SPAWN = false;

    private BlackHoleDemo() {
    }

    @SubscribeEvent
    public static void registerCommands(RegisterClientCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("kasuga_black_hole")
                .then(Commands.literal("demo").executes(context -> {
                    LocalPlayer player = requirePlayer(context.getSource()::sendFailure);
                    if (player == null) return 0;
                    spawnSingle(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga black-hole demo placed in front of the camera"),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("orbit").executes(context -> {
                    LocalPlayer player = requirePlayer(context.getSource()::sendFailure);
                    if (player == null) return 0;
                    startOrbit(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga three-body black-hole demo started"),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("massive").executes(context -> {
                    LocalPlayer player = requirePlayer(context.getSource()::sendFailure);
                    if (player == null) return 0;
                    spawnMassive(player);
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga massive black hole and accretion disk started"),
                            false
                    );
                    return 1;
                }))
                .then(Commands.literal("clear").executes(context -> {
                    clearDemo();
                    autoSpawnHandled = true;
                    context.getSource().sendSuccess(
                            () -> Component.literal("Kasuga black-hole demo cleared"), false
                    );
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    RenderShaderHandle shader = BlackHoleEffects.shader();
                    context.getSource().sendSuccess(() -> Component.literal(
                            "Kasuga black hole: shader=" + (shader.isReady() ? "ready" : "waiting")
                                    + ", generation=" + shader.generation()
                                    + ", effects=" + BlackHoleEffects.size()
                                    + ", demo=" + mode.name().toLowerCase()
                    ), false);
                    return shader.isReady() ? 1 : 0;
                })));
    }

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        if (level != activeLevel) {
            clearDemo();
            activeLevel = level;
            autoSpawnDelay = 30;
            autoSpawnHandled = false;
            return;
        }
        if (level == null || minecraft.player == null || minecraft.isPaused()) return;

        if (!autoSpawnHandled) {
            if (!AUTO_SPAWN) {
                autoSpawnHandled = true;
                return;
            }
            if (autoSpawnDelay-- > 0 || !BlackHoleEffects.shader().isReady()) return;
            autoSpawnHandled = true;
            spawnSingle(minecraft.player);
            minecraft.player.displayClientMessage(Component.literal(
                    "Kasuga black-hole demo active; use /kasuga_black_hole orbit or clear"
            ), false);
        }

        if (mode == Mode.ORBIT) updateOrbit(level.getGameTime());
    }

    private static LocalPlayer requirePlayer(java.util.function.Consumer<Component> failure) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) failure.accept(Component.literal("Join a world before starting the demo"));
        return player;
    }

    private static void spawnSingle(LocalPlayer player) {
        clearDemo();
        Vec3 position = player.getEyePosition().add(player.getLookAngle().normalize().scale(12.0));
        BlackHoleEffects.put(BlackHoleEffect.builder(SINGLE_ID, position)
                .eventHorizonRadius(1.55f)
                .influenceRadius(4.2f)
                .distortionStrength(0.9f)
                .accretionRadius(1.7f)
                .accretionWidth(0.22f)
                .glowStrength(1.65f)
                .chromaticAberration(0.025f)
                .rotationSpeed(1.0f)
                .glowColor(new BlackHoleEffect.Color(1.0f, 0.24f, 0.035f))
                .depthTest(false)
                .build());
        mode = Mode.SINGLE;
    }

    private static void startOrbit(LocalPlayer player) {
        clearDemo();
        Vec3 forward = player.getLookAngle().normalize();
        orbitCenter = player.getEyePosition().add(forward.scale(15.0));
        Vec3 right = forward.cross(new Vec3(0, 1, 0));
        if (right.lengthSqr() < 1.0e-6) right = new Vec3(1, 0, 0);
        orbitRight = right.normalize();
        orbitUp = orbitRight.cross(forward).normalize();
        mode = Mode.ORBIT;
        updateOrbit(player.level().getGameTime());
    }

    private static void spawnMassive(LocalPlayer player) {
        clearDemo();
        Vec3 position = player.getEyePosition().add(player.getLookAngle().normalize().scale(38.0));
        BlackHoleEffects.put(BlackHoleEffect.builder(SINGLE_ID, position)
                .eventHorizonRadius(7.5f)
                .influenceRadius(4.0f)
                .distortionStrength(1.15f)
                .accretionRadius(2.15f)
                .accretionWidth(0.68f)
                .accretionDiskTilt(0.26f)
                .glowStrength(1.45f)
                .chromaticAberration(0.014f)
                .rotationSpeed(0.42f)
                .glowColor(new BlackHoleEffect.Color(1.0f, 0.16f, 0.018f))
                .depthTest(false)
                .build());
        mode = Mode.MASSIVE;
    }

    private static void updateOrbit(long gameTime) {
        double phase = gameTime * 0.025;
        for (int index = 0; index < ORBIT_IDS.length; index++) {
            double angle = phase + Math.PI * 2.0 * index / ORBIT_IDS.length;
            Vec3 position = orbitCenter
                    .add(orbitRight.scale(Math.cos(angle) * 3.2))
                    .add(orbitUp.scale(Math.sin(angle) * 2.2));
            BlackHoleEffects.put(BlackHoleEffect.builder(ORBIT_IDS[index], position)
                    .eventHorizonRadius(0.72f)
                    .influenceRadius(3.8f)
                    .distortionStrength(0.82f)
                    .accretionRadius(1.65f)
                    .accretionWidth(0.2f)
                    .glowStrength(1.3f)
                    .chromaticAberration(0.018f)
                    .rotationSpeed(index % 2 == 0 ? 1.2f : -0.9f)
                    .glowColor(index == 1
                            ? new BlackHoleEffect.Color(0.2f, 0.55f, 1.0f)
                            : new BlackHoleEffect.Color(1.0f, 0.22f, 0.04f))
                    .depthTest(false)
                    .build());
        }
    }

    private static void clearDemo() {
        BlackHoleEffects.remove(SINGLE_ID);
        for (ResourceLocation id : ORBIT_IDS) BlackHoleEffects.remove(id);
        mode = Mode.OFF;
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("kasuga_demo", path);
    }

    private enum Mode {
        OFF,
        SINGLE,
        ORBIT,
        MASSIVE
    }
}
