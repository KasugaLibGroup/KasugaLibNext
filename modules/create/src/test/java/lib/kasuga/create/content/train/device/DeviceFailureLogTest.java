package lib.kasuga.create.content.train.device;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeviceFailureLogTest {

    @Test
    void firstFailureIsLoggedThenSuppressedUntilWindowElapses() {
        DeviceFailureLog log = new DeviceFailureLog(60_000L);

        assertTrue(log.shouldLog("a#tick", 1_000L), "首次异常必须记录完整栈");
        assertFalse(log.shouldLog("a#tick", 2_000L), "窗口内的重复异常不能刷屏");
        assertFalse(log.shouldLog("a#tick", 60_999L), "窗口边界内仍然抑制");
        assertTrue(log.shouldLog("a#tick", 61_000L), "窗口外重新放行一条");
    }

    @Test
    void differentDeviceOrCallbackKeepsItsOwnWindow() {
        DeviceFailureLog log = new DeviceFailureLog(60_000L);

        assertTrue(log.shouldLog("a#tick", 1_000L));
        assertTrue(log.shouldLog("a#earlyTick", 1_000L));
        assertTrue(log.shouldLog("b#tick", 1_000L));
        assertFalse(log.shouldLog("a#tick", 1_500L));
    }

    @Test
    void clearResetsEveryWindow() {
        DeviceFailureLog log = new DeviceFailureLog(60_000L);

        assertTrue(log.shouldLog("a#tick", 1_000L));
        assertFalse(log.shouldLog("a#tick", 1_500L));
        log.clear();
        assertTrue(log.shouldLog("a#tick", 1_600L));
    }
}
