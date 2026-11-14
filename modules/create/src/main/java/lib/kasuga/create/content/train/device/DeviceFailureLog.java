package lib.kasuga.create.content.train.device;

import java.util.HashMap;
import java.util.Map;

/**
 * 设备回调异常日志的限流器：同一个 key（设备类型 + 回调名）在时间窗口内只放行一条日志，
 * 其余重复异常降级为 debug。窗口外再放行一条，避免设备每 tick 抛异常时刷屏。
 *
 * <p>独立成类是为了能脱离 Minecraft 运行时单测时间窗口语义。</p>
 */
final class DeviceFailureLog {

    private final long intervalMillis;
    private final Map<String, Long> lastLogTimes = new HashMap<>();

    DeviceFailureLog(long intervalMillis) {
        this.intervalMillis = intervalMillis;
    }

    /**
     * @param key      设备类型 + 回调名
     * @param nowMillis 当前时间戳（毫秒）
     * @return 本次是否应当写出 warn 级别的完整日志
     */
    boolean shouldLog(String key, long nowMillis) {
        Long last = lastLogTimes.get(key);
        if (last != null && nowMillis - last < intervalMillis) {
            return false;
        }
        lastLogTimes.put(key, nowMillis);
        return true;
    }

    void clear() {
        lastLogTimes.clear();
    }

    long intervalMillis() {
        return intervalMillis;
    }
}
