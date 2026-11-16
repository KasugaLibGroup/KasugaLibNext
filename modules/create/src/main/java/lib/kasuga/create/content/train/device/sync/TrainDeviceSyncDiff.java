package lib.kasuga.create.content.train.device.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * 设备同步快照的字段级 diff：只把变化的字段和消失的字段名交出去。
 *
 * <p>纯逻辑，不依赖 Minecraft 运行时之外的任何东西，便于单测。</p>
 */
public record TrainDeviceSyncDiff(CompoundTag changed, List<String> removedKeys) {

    public static final TrainDeviceSyncDiff EMPTY = new TrainDeviceSyncDiff(new CompoundTag(), List.of());

    public boolean isEmpty() {
        return changed.isEmpty() && removedKeys.isEmpty();
    }

    /**
     * @param snapshot 本轮快照
     * @param previous 上次已发给该玩家的快照（{@code null} 表示还没发过，调用方应发 FULL）
     */
    public static TrainDeviceSyncDiff between(CompoundTag snapshot, CompoundTag previous) {
        if (previous == null) {
            return new TrainDeviceSyncDiff(snapshot.copy(), List.of());
        }
        CompoundTag changed = new CompoundTag();
        for (String key : snapshot.getAllKeys()) {
            Tag now = snapshot.get(key);
            Tag before = previous.get(key);
            if (now != null && !now.equals(before)) {
                changed.put(key, now.copy());
            }
        }
        Set<String> missing = new TreeSet<>();
        for (String key : previous.getAllKeys()) {
            if (!snapshot.contains(key)) {
                missing.add(key);
            }
        }
        List<String> removedKeys = new ArrayList<>(missing);
        return new TrainDeviceSyncDiff(changed, removedKeys);
    }

    /** 在客户端把增量合并进已有 tag（就地修改并返回同一个 tag）。 */
    public static CompoundTag apply(CompoundTag target, TrainDeviceSyncDiff diff) {
        CompoundTag result = target == null ? new CompoundTag() : target;
        for (String key : diff.removedKeys()) {
            result.remove(key);
        }
        for (String key : diff.changed().getAllKeys()) {
            Tag value = diff.changed().get(key);
            if (value != null) {
                result.put(key, value.copy());
            }
        }
        return result;
    }
}
