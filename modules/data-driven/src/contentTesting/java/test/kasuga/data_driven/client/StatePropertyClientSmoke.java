package test.kasuga.data_driven.client;

import com.mojang.logging.LogUtils;
import lib.kasuga.KasugaLib;
import lib.kasuga.registration.data_driven.state.StateProperty;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Opt-in single-player smoke: observe the same server state update on the real client. */
@EventBusSubscriber(modid = KasugaLib.MODID, value = Dist.CLIENT)
public final class StatePropertyClientSmoke {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("kasuga.testStatePropertyClient");
    private static final long START = System.nanoTime();
    private static final AtomicInteger SERVER_EVENTS = new AtomicInteger();
    private static final AtomicInteger CLIENT_EVENTS = new AtomicInteger();
    private static final AtomicReference<Throwable> FAILURE = new AtomicReference<>();
    private static StateProperty.Subscription serverListener;
    private static StateProperty.Subscription clientListener;
    private static StateProperty<Boolean> active;
    private static BlockPos target;
    private static int stage;
    private static boolean finished;

    private StatePropertyClientSmoke() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (!ENABLED || finished) return;
        Minecraft minecraft = Minecraft.getInstance();
        try {
            if (FAILURE.get() != null) throw new IllegalStateException("server update failed", FAILURE.get());
            if (System.nanoTime() - START > 120_000_000_000L) {
                throw new IllegalStateException("timed out waiting for world or client event at stage " + stage);
            }
            if (minecraft.level == null || minecraft.player == null || minecraft.getSingleplayerServer() == null) return;
            MinecraftServer server = minecraft.getSingleplayerServer();
            if (stage == 0) {
                Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kasuga_lib:stateful_fixture"));
                if (block == Blocks.AIR) throw new IllegalStateException("stateful JSON fixture was not registered");
                active = StateProperty.of(block, "active", Boolean.class);
                target = minecraft.player.blockPosition().offset(2, 1, 0).immutable();
                clientListener = active.onChange(StateProperty.Side.CLIENT, change -> {
                    if (change.pos().equals(target) && change.oldValue() && !change.newValue()) CLIENT_EVENTS.incrementAndGet();
                });
                serverListener = active.onChange(change -> {
                    if (change.pos().equals(target) && change.oldValue() && !change.newValue()) SERVER_EVENTS.incrementAndGet();
                });
                var dimension = minecraft.level.dimension();
                server.execute(() -> {
                    try {
                        var level = server.getLevel(dimension);
                        if (level == null) throw new IllegalStateException("test dimension is unavailable");
                        level.setBlock(target, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
                        if (!level.setBlock(target, block.defaultBlockState(), Block.UPDATE_ALL)) {
                            throw new IllegalStateException("could not place the test block");
                        }
                    } catch (Throwable t) {
                        FAILURE.set(t);
                    }
                });
                stage = 1;
            } else if (stage == 1 && minecraft.level.getBlockState(target).is(active.block())
                    && active.get(minecraft.level, target)) {
                var dimension = minecraft.level.dimension();
                server.execute(() -> {
                    try {
                        var level = server.getLevel(dimension);
                        if (level == null || !active.set(level, target, false)) {
                            throw new IllegalStateException("server did not apply the state change");
                        }
                    } catch (Throwable t) {
                        FAILURE.set(t);
                    }
                });
                stage = 2;
            } else if (stage == 2 && SERVER_EVENTS.get() == 1 && CLIENT_EVENTS.get() == 1
                    && minecraft.level.getBlockState(target).is(active.block()) && !active.get(minecraft.level, target)) {
                LOGGER.info("STATE_PROPERTY_CLIENT_SMOKE_PASS server={} client={} pos={}", SERVER_EVENTS.get(), CLIENT_EVENTS.get(), target);
                finish(minecraft);
            }
        } catch (Throwable failure) {
            LOGGER.error("STATE_PROPERTY_CLIENT_SMOKE_FAIL", failure);
            finish(minecraft);
        }
    }

    private static void finish(Minecraft minecraft) {
        if (finished) return;
        finished = true;
        if (serverListener != null) serverListener.close();
        if (clientListener != null) clientListener.close();
        minecraft.stop();
    }
}
