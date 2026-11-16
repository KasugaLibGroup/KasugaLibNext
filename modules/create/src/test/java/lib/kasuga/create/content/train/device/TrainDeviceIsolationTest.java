package lib.kasuga.create.content.train.device;

import com.simibubi.create.content.trains.graph.TrackNode;
import com.simibubi.create.content.trains.signal.TrackEdgePoint;
import lib.kasuga.structure.Pair;
import net.createmod.catnip.data.Couple;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-01：设备回调抛异常必须被捕获，不影响其它设备，也不外抛到 Create 的 Train.tick。
 */
class TrainDeviceIsolationTest {

    /** 记录调用次数；{@code failing} 为真时每个回调都抛异常。 */
    static class FakeDevice extends TrainDeviceSystem {
        int ticks;
        int notifyCalls;
        int slowSlowdownCalls;
        boolean failing;
        double speed = 1.5;

        FakeDevice(TrainDeviceManager manager) {
            super(manager);
        }

        @Override
        public void tick(Level level) {
            ticks++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void earlyTick(Level level) {
            notifyCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void notifySpeed(double speed) {
            notifyCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void notifyDistance(double distance) {
            notifyCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void notifyDisassemble(Direction assemblyDirection, BlockPos pos) {
            notifyCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void notifySignalCollection() {
            notifyCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void onSegmentUpdated(ResourceLocation segmentType) {
            notifyCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public boolean notifySignalFront(Double distance, Pair<TrackEdgePoint, Couple<TrackNode>> pair) {
            notifyCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
            return false;
        }

        @Override
        public boolean notifySignalBack(Double distance, Pair<TrackEdgePoint, Couple<TrackNode>> pair) {
            notifyCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
            return false;
        }

        @Override
        public Optional<Double> beforeSpeed() {
            if (failing) {
                throw new IllegalStateException("boom");
            }
            return Optional.of(speed);
        }

        @Override
        public boolean cancelSlowdown() {
            slowSlowdownCalls++;
            if (failing) {
                throw new IllegalStateException("boom");
            }
            return true;
        }
    }

    private static int counter;

    private static TrainDeviceSystemType<FakeDevice> type(String name) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("kasuga_tds_test", name + "_" + (counter++));
        TrainDeviceSystemType<FakeDevice> type = TrainDeviceSystemType.of(FakeDevice::new);
        TrainDeviceRegistry.register(id, type);
        return type;
    }

    @Test
    void oneFailingDeviceDoesNotStopTheOthers() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        TrainDeviceSystemType<FakeDevice> failingType = type("failing");
        TrainDeviceSystemType<FakeDevice> healthyType = type("healthy");

        FakeDevice failing = manager.getOrCreateSystem(failingType);
        FakeDevice healthy = manager.getOrCreateSystem(healthyType);
        failing.failing = true;

        assertDoesNotThrow(() -> manager.tick(null));
        assertDoesNotThrow(() -> manager.tick(null));

        assertEquals(2, failing.ticks, "抛异常的设备本身仍然被调用（只是异常被捕获）");
        assertEquals(2, healthy.ticks, "其它设备必须照常 tick");
    }

    @Test
    void notificationHooksAreIsolated() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        TrainDeviceSystemType<FakeDevice> failingType = type("failing_notify");
        FakeDevice failing = manager.getOrCreateSystem(failingType);
        failing.failing = true;

        assertDoesNotThrow(() -> {
            manager.earlyTick(null);
            manager.notifySpeed(1.0);
            manager.notifyDistance(2.0);
            manager.notifyDisassemble(Direction.NORTH, BlockPos.ZERO);
            manager.notifySignalCollection();
            manager.onSegmentUpdated(ResourceLocation.fromNamespaceAndPath("kasuga_tds_test", "segment"));
        });

        assertEquals(6, failing.notifyCalls, "六个通知类回调都应该被调用到，且异常不外抛");
    }

    @Test
    void signalHooksTreatExceptionsAsNoInterception() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        TrainDeviceSystemType<FakeDevice> failingType = type("failing_signal");
        TrainDeviceSystemType<FakeDevice> healthyType = type("healthy_signal");
        manager.getOrCreateSystem(failingType).failing = true;
        FakeDevice healthy = manager.getOrCreateSystem(healthyType);

        assertFalse(manager.notifySignalFront(1.0, null), "设备异常不能被当作拦截信号");
        assertFalse(manager.notifySignalBack(1.0, null));
        assertEquals(2, healthy.notifyCalls, "异常设备之后的健康设备仍要收到信号回调");
    }

    @Test
    void beforeSpeedFallsThroughToTheNextDevice() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        TrainDeviceSystemType<FakeDevice> failingType = type("failing_speed");
        TrainDeviceSystemType<FakeDevice> healthyType = type("healthy_speed");
        manager.getOrCreateSystem(failingType).failing = true;
        FakeDevice healthy = manager.getOrCreateSystem(healthyType);
        healthy.speed = 4.25;

        assertEquals(Optional.of(4.25), manager.beforeSpeed(), "抛异常的设备的车速建议被忽略，继续问下一个");
    }

    @Test
    void cancelSlowdownIgnoresTheFailingDevice() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        TrainDeviceSystemType<FakeDevice> failingType = type("failing_slowdown");
        manager.getOrCreateSystem(failingType).failing = true;

        assertDoesNotThrow(manager::updateCache);
        assertFalse(manager.cancelSlowdown(), "异常设备不该被当成「取消减速」");
    }

    @Test
    void singleFailureDoesNotDisableTheDevice() {
        TrainDeviceManager manager = new TrainDeviceManager(null);
        TrainDeviceSystemType<FakeDevice> failingType = type("flaky");
        FakeDevice flaky = manager.getOrCreateSystem(failingType);
        flaky.failing = true;

        for (int i = 0; i < 20; i++) {
            manager.tick(null);
        }

        flaky.failing = false;
        manager.tick(null);

        assertEquals(21, flaky.ticks, "设备不会被停用：异常只记日志，调用照常");
        assertTrue(manager.getSystem(failingType) == flaky);
    }
}
