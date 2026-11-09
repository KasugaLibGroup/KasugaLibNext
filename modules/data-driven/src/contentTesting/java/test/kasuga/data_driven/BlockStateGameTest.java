package test.kasuga.data_driven;

import lib.kasuga.registration.data_driven.state.StateProperty;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.common.util.BlockSnapshot;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.neoforged.neoforge.gametest.GameTestHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@GameTestHolder("kasuga_lib")
public final class BlockStateGameTest {
    private BlockStateGameTest() {}

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void registeredJsonStateCanBeChangedInTheWorld(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kasuga_lib:stateful_fixture"));
        if (block == Blocks.AIR) {
            helper.fail("stateful fixture was not registered from its JSON index");
            return;
        }
        IntegerProperty level = (IntegerProperty) block.getStateDefinition().getProperty("level");
        BlockPos pos = new BlockPos(1, 1, 1);
        helper.setBlock(pos, block.defaultBlockState());
        BlockState initial = helper.getBlockState(pos);
        if (initial.getValue(BlockStateProperties.OPEN) || initial.getValue(level) != 2) {
            helper.fail("wrong JSON-defined default state: " + initial);
        }
        helper.setBlock(pos, initial.setValue(BlockStateProperties.OPEN, true).setValue(level, 3));
        helper.runAfterDelay(2, () -> {
            BlockState updated = helper.getBlockState(pos);
            if (!updated.getValue(BlockStateProperties.OPEN) || updated.getValue(level) != 3) {
                helper.fail("world did not retain the updated state: " + updated);
            }
            helper.succeed();
        });
    }

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void javaSetAndNativeUpdatesDispatchChanges(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kasuga_lib:stateful_fixture"));
        StateProperty<Boolean> active = StateProperty.of(block, "active", Boolean.class);
        StateProperty<Integer> levelProperty = StateProperty.of(block, "level", Integer.class);
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(relative);
        List<StateProperty.Change<Boolean>> changes = new ArrayList<>();
        AtomicInteger clientChanges = new AtomicInteger();
        AtomicInteger rejectedWrites = new AtomicInteger();
        AtomicInteger afterFailure = new AtomicInteger();
        try (StateProperty.Subscription listener = active.onChange(changes::add);
             StateProperty.Subscription clientOnly = active.onChange(StateProperty.Side.CLIENT, change -> clientChanges.incrementAndGet());
             StateProperty.Subscription failing = active.onChange(change -> { throw new IllegalStateException("intentional listener test"); });
             StateProperty.Subscription after = active.onChange(change -> afterFailure.incrementAndGet());
             StateProperty.Subscription reentrant = active.onChange(change -> {
                 try {
                     active.set(helper.getLevel(), absolute, true);
                 } catch (IllegalStateException expected) {
                     rejectedWrites.incrementAndGet();
                 }
             })) {
            helper.setBlock(relative, block);
            if (!changes.isEmpty()) helper.fail("placement must not count as an attribute change");
            if (!active.set(helper.getLevel(), absolute, false)) helper.fail("API set did not change the world");
            if (active.get(helper.getLevel(), absolute)) helper.fail("API set wrote the wrong value");
            if (changes.size() != 1 || !changes.get(0).oldValue() || changes.get(0).newValue()
                    || !changes.get(0).pos().equals(absolute) || changes.get(0).level() != helper.getLevel()) {
                helper.fail("wrong API change event: " + changes);
            }
            if (afterFailure.get() != 1) helper.fail("a failed listener prevented later listeners");
            failing.close();
            if (active.set(helper.getLevel(), absolute, false)) helper.fail("no-op set must return false");
            helper.setBlock(relative, levelProperty.with(helper.getBlockState(relative), 3));
            if (changes.size() != 1) helper.fail("unrelated property triggered the listener");
            helper.setBlock(relative, active.with(helper.getBlockState(relative), true));
            if (changes.size() != 2 || changes.get(1).oldValue() || !changes.get(1).newValue()) {
                helper.fail("native setBlock did not deliver exactly one change: " + changes);
            }
            if (clientChanges.get() != 0 || rejectedWrites.get() != 2 || afterFailure.get() != 2) {
                helper.fail("wrong side, reentrant guard, or listener failure isolation");
            }
            listener.close();
            listener.close();
            helper.setBlock(relative, active.with(helper.getBlockState(relative), false));
            if (changes.size() != 2) helper.fail("closed listener still ran");
            helper.setBlock(relative, Blocks.STONE);
            if (changes.size() != 2) helper.fail("removal must not count as an attribute change");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void customStatefulBlockForwardsTheChangeHook(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kasuga_lib:stateful_custom_fixture"));
        StateProperty<Boolean> active = StateProperty.of(block, "active", Boolean.class);
        BlockPos relative = new BlockPos(1, 1, 1);
        AtomicInteger changes = new AtomicInteger();
        helper.setBlock(relative, block);
        try (StateProperty.Subscription listener = active.onChange(change -> changes.incrementAndGet())) {
            helper.setBlock(relative, active.with(helper.getBlockState(relative), false));
            if (changes.get() != 1) helper.fail("custom block failed to forward its state change hook");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void snapshotCommitAndRollbackRespectChangeNotifications(GameTestHelper helper) {
        ServerLevel world = helper.getLevel();
        if (world.captureBlockSnapshots || world.restoringBlockSnapshots) {
            helper.fail("another snapshot operation is already in progress");
            return;
        }
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kasuga_lib:stateful_fixture"));
        StateProperty<Boolean> active = StateProperty.of(block, "active", Boolean.class);
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(relative);
        helper.setBlock(relative, block);
        List<StateProperty.Change<Boolean>> changes = new ArrayList<>();
        int originalCount = world.capturedBlockSnapshots.size();
        try (StateProperty.Subscription listener = active.onChange(changes::add)) {
            world.captureBlockSnapshots = true;
            try {
                active.set(world, absolute, false);
            } finally {
                world.captureBlockSnapshots = false;
            }
            if (!changes.isEmpty() || world.capturedBlockSnapshots.size() != originalCount + 1) {
                helper.fail("uncommitted snapshot emitted a change or was not captured");
            }
            BlockSnapshot commit = world.capturedBlockSnapshots.remove(originalCount);
            world.markAndNotifyBlock(absolute, world.getChunkAt(absolute), commit.getState(),
                    world.getBlockState(absolute), Block.UPDATE_ALL, 512);
            if (changes.size() != 1 || !changes.get(0).oldValue() || changes.get(0).newValue()) {
                helper.fail("snapshot commit did not emit the confirmed transition");
            }
            world.captureBlockSnapshots = true;
            try {
                active.set(world, absolute, true);
            } finally {
                world.captureBlockSnapshots = false;
            }
            BlockSnapshot rollback = world.capturedBlockSnapshots.remove(originalCount);
            world.restoringBlockSnapshots = true;
            try {
                rollback.restore();
            } finally {
                world.restoringBlockSnapshots = false;
            }
            if (changes.size() != 1 || active.get(world, absolute)) {
                helper.fail("snapshot rollback emitted a change or failed to restore the state");
            }
        } finally {
            world.captureBlockSnapshots = false;
            world.restoringBlockSnapshots = false;
            while (world.capturedBlockSnapshots.size() > originalCount) {
                world.capturedBlockSnapshots.remove(world.capturedBlockSnapshots.size() - 1);
            }
        }
        helper.succeed();
    }

    @GameTest(template = "empty", templateNamespace = "kasuga_lib", timeoutTicks = 100)
    public static void multiplePropertiesAndClosingDuringDispatch(GameTestHelper helper) {
        Block block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse("kasuga_lib:stateful_fixture"));
        StateProperty<Boolean> active = StateProperty.of(block, "active", Boolean.class);
        StateProperty<Integer> levelProperty = StateProperty.of(block, "level", Integer.class);
        BlockPos relative = new BlockPos(1, 1, 1);
        BlockPos absolute = helper.absolutePos(relative);
        helper.setBlock(relative, block);
        AtomicInteger firstCalls = new AtomicInteger();
        AtomicInteger removedCalls = new AtomicInteger();
        List<StateProperty.Change<Integer>> levelChanges = new ArrayList<>();
        AtomicReference<StateProperty.Subscription> removedRef = new AtomicReference<>();
        try (StateProperty.Subscription first = active.onChange(StateProperty.Side.BOTH, change -> {
                 firstCalls.incrementAndGet();
                 removedRef.get().close();
             });
             StateProperty.Subscription removed = active.onChange(change -> removedCalls.incrementAndGet());
             StateProperty.Subscription levelListener = levelProperty.onChange(levelChanges::add)) {
            removedRef.set(removed);
            BlockState updated = levelProperty.with(active.with(helper.getBlockState(relative), false), 1);
            if (!helper.getLevel().setBlock(absolute, updated, 0)) helper.fail("world refused the combined update");
            if (firstCalls.get() != 1 || removedCalls.get() != 0) {
                helper.fail("closing a listener during dispatch did not take effect");
            }
            if (levelChanges.size() != 1 || levelChanges.get(0).oldValue() != 2 || levelChanges.get(0).newValue() != 1) {
                helper.fail("multi-property update lost or duplicated a property event");
            }
        }
        helper.succeed();
    }
}
