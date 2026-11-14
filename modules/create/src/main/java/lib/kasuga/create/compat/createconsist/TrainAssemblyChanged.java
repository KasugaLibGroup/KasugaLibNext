package lib.kasuga.create.compat.createconsist;

import java.util.*;

/**
 * 一次编组变化：把「车厢 UUID 序列」翻译成索引映射与进出列表，设备据此重排自己的状态表。
 *
 * <p>纯数据 + 纯逻辑，不依赖 Minecraft，可单测。</p>
 *
 * @param trainId       列车 id
 * @param before        变化前的车厢 UUID 序列（顺序 = 旧 carriageIndex）
 * @param after         变化后的车厢 UUID 序列（顺序 = 新 carriageIndex）
 * @param oldToNewIndex 旧索引 → 新索引，{@code -1} 表示该车厢已离开本列车
 * @param newToOldIndex 新索引 → 旧索引，{@code -1} 表示新增车厢
 * @param movedOut      离开本列车的车厢
 * @param movedIn       新加入的车厢
 */
public record TrainAssemblyChanged(
        UUID trainId,
        List<UUID> before,
        List<UUID> after,
        int[] oldToNewIndex,
        int[] newToOldIndex,
        List<UUID> movedOut,
        List<UUID> movedIn
) {

    public TrainAssemblyChanged {
        before = before == null ? List.of() : List.copyOf(before);
        after = after == null ? List.of() : List.copyOf(after);
        movedOut = movedOut == null ? List.of() : List.copyOf(movedOut);
        movedIn = movedIn == null ? List.of() : List.copyOf(movedIn);
    }

    public static TrainAssemblyChanged of(UUID trainId, List<UUID> before, List<UUID> after) {
        List<UUID> safeBefore = before == null ? List.of() : before;
        List<UUID> safeAfter = after == null ? List.of() : after;

        Map<UUID, Integer> beforeIndex = new HashMap<>();
        for (int i = 0; i < safeBefore.size(); i++) {
            beforeIndex.putIfAbsent(safeBefore.get(i), i);
        }
        Map<UUID, Integer> afterIndex = new HashMap<>();
        for (int i = 0; i < safeAfter.size(); i++) {
            afterIndex.putIfAbsent(safeAfter.get(i), i);
        }

        // 一个目标索引只允许被认领一次：出现重复 UUID 时，第二次出现视为「已消失 / 新增」，
        // 避免两节车厢的状态被映射到同一个槽位。
        int[] oldToNew = new int[safeBefore.size()];
        Arrays.fill(oldToNew, -1);
        boolean[] claimedAfter = new boolean[safeAfter.size()];
        for (int i = 0; i < safeBefore.size(); i++) {
            Integer target = afterIndex.get(safeBefore.get(i));
            if (target != null && !claimedAfter[target]) {
                oldToNew[i] = target;
                claimedAfter[target] = true;
            }
        }

        int[] newToOld = new int[safeAfter.size()];
        Arrays.fill(newToOld, -1);
        boolean[] claimedBefore = new boolean[safeBefore.size()];
        for (int i = 0; i < safeAfter.size(); i++) {
            Integer source = beforeIndex.get(safeAfter.get(i));
            if (source != null && !claimedBefore[source]) {
                newToOld[i] = source;
                claimedBefore[source] = true;
            }
        }

        List<UUID> movedOut = new ArrayList<>();
        for (UUID id : safeBefore) {
            if (!afterIndex.containsKey(id)) {
                movedOut.add(id);
            }
        }
        List<UUID> movedIn = new ArrayList<>();
        for (UUID id : safeAfter) {
            if (!beforeIndex.containsKey(id)) {
                movedIn.add(id);
            }
        }
        return new TrainAssemblyChanged(trainId, safeBefore, safeAfter, oldToNew, newToOld, movedOut, movedIn);
    }

    /** 编组完全没变（含「从未初始化 → 仍是空」的情况）。 */
    public boolean isNoop() {
        return before.equals(after);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof TrainAssemblyChanged that)) {
            return false;
        }
        return Objects.equals(trainId, that.trainId)
                && before.equals(that.before)
                && after.equals(that.after)
                && Arrays.equals(oldToNewIndex, that.oldToNewIndex)
                && Arrays.equals(newToOldIndex, that.newToOldIndex);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(trainId, before, after);
        result = 31 * result + Arrays.hashCode(oldToNewIndex);
        result = 31 * result + Arrays.hashCode(newToOldIndex);
        return result;
    }

    @Override
    public String toString() {
        return "TrainAssemblyChanged{" + trainId + ", before=" + before.size() + " 节, after=" + after.size()
                + " 节, movedOut=" + movedOut.size() + ", movedIn=" + movedIn.size() + '}';
    }
}
