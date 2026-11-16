package lib.kasuga.create.content.train.device;

import lib.kasuga.create.compat.createconsist.TrainAssemblyChanged;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * I-05：按车厢 UUID 重映射的索引语义（拆分 / 合并 / 重排 / 幂等），以及身份缺失时拒绝猜映射。
 */
class TrainAssemblyChangedTest {

    private static final UUID A = UUID.nameUUIDFromBytes("A".getBytes());
    private static final UUID B = UUID.nameUUIDFromBytes("B".getBytes());
    private static final UUID C = UUID.nameUUIDFromBytes("C".getBytes());
    private static final UUID TRAIN = UUID.nameUUIDFromBytes("train".getBytes());

    @Test
    void splittingRemovesTheTailCarAndKeepsTheRestInPlace() {
        TrainAssemblyChanged change = TrainAssemblyChanged.of(TRAIN, List.of(A, B, C), List.of(A, B));

        assertArrayEquals(new int[]{0, 1, -1}, change.oldToNewIndex(), "被拆出去的车厢索引为 -1");
        assertArrayEquals(new int[]{0, 1}, change.newToOldIndex());
        assertEquals(List.of(C), change.movedOut());
        assertEquals(List.of(), change.movedIn());
        assertFalse(change.isNoop());
    }

    @Test
    void splittingTheHeadKeepsTheSurvivingCarsMappedByUuid() {
        TrainAssemblyChanged change = TrainAssemblyChanged.of(TRAIN, List.of(A, B, C), List.of(B, C));

        assertArrayEquals(new int[]{-1, 0, 1}, change.oldToNewIndex(), "车厢跟 UUID 走，不跟序号走");
        assertEquals(List.of(A), change.movedOut());
    }

    @Test
    void mergingAppendsNewCarsWithoutDuplicatingExistingOnes() {
        TrainAssemblyChanged change = TrainAssemblyChanged.of(TRAIN, List.of(A, B), List.of(A, B, C));

        assertArrayEquals(new int[]{0, 1}, change.oldToNewIndex());
        assertArrayEquals(new int[]{0, 1, -1}, change.newToOldIndex(), "新车厢没有旧索引");
        assertEquals(List.of(), change.movedOut());
        assertEquals(List.of(C), change.movedIn());
    }

    @Test
    void reorderingSwapsIndexesButKeepsCarsAddressedByUuid() {
        TrainAssemblyChanged change = TrainAssemblyChanged.of(TRAIN, List.of(A, B), List.of(B, A));

        assertArrayEquals(new int[]{1, 0}, change.oldToNewIndex());
        assertArrayEquals(new int[]{1, 0}, change.newToOldIndex());
        assertEquals(List.of(), change.movedOut());
        assertEquals(List.of(), change.movedIn());
        assertFalse(change.isNoop());
    }

    @Test
    void identicalConsistIsANoop() {
        TrainAssemblyChanged change = TrainAssemblyChanged.of(TRAIN, List.of(A, B), List.of(A, B));

        assertTrue(change.isNoop(), "编组没变时不能触发设备回调（幂等）");
    }

    @Test
    void initialisingFromAnEmptyBaselineIsNotANoop() {
        TrainAssemblyChanged change = TrainAssemblyChanged.of(TRAIN, List.of(), List.of(A));

        assertFalse(change.isNoop());
        assertEquals(List.of(A), change.movedIn());
        assertArrayEquals(new int[]{-1}, change.newToOldIndex());
    }

    @Test
    void missingCarriageIdentityIsRejectedInsteadOfGuessed() {
        assertThrows(IllegalArgumentException.class,
                () -> TrainAssemblyChanged.of(TRAIN, List.of(A), java.util.Arrays.asList(B, null)),
                "有车厢没有身份时必须拒绝重映射，不能猜一个 UUID 出来");
    }

    @Test
    void duplicatedIdentitiesAreRejectedInsteadOfSilentlyMerged() {
        assertThrows(IllegalArgumentException.class,
                () -> TrainAssemblyChanged.of(TRAIN, List.of(A, A), List.of(A)),
                "两节车厢身份相同说明身份有问题，直接拒绝，不允许把状态串到一起");
    }
}
