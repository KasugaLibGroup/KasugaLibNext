package lib.kasuga.create.content.contraption;

import net.minecraft.core.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Downsamples occupied blocks to a bounded grid and picks each cell's most common block state. */
public record ContraptionLodMesh(int scale, List<Cell> cells) {
    static final int MAX_CELLS = 2_048;
    static final int MAX_SCALE = 64;

    public record Cell(BlockPos position, int state) {}

    public ContraptionLodMesh {
        cells = List.copyOf(cells);
    }

    static ContraptionLodMesh simplify(List<Cell> blocks) {
        for (int scale = 2; scale <= MAX_SCALE; scale *= 2) {
            Map<BlockPos, Map<Integer, Integer>> counts = new HashMap<>();
            for (Cell block : blocks) {
                BlockPos pos = block.position();
                BlockPos cell = new BlockPos(Math.floorDiv(pos.getX(), scale),
                        Math.floorDiv(pos.getY(), scale), Math.floorDiv(pos.getZ(), scale));
                counts.computeIfAbsent(cell, ignored -> new HashMap<>()).merge(block.state(), 1, Integer::sum);
                if (counts.size() > MAX_CELLS) break;
            }
            if (counts.size() > MAX_CELLS) continue;
            List<Cell> cells = new ArrayList<>(counts.size());
            for (Map.Entry<BlockPos, Map<Integer, Integer>> entry : counts.entrySet()) {
                int state = Integer.MAX_VALUE;
                int highestCount = 0;
                for (Map.Entry<Integer, Integer> candidate : entry.getValue().entrySet()) {
                    int count = candidate.getValue();
                    if (count > highestCount || count == highestCount && candidate.getKey() < state) {
                        state = candidate.getKey();
                        highestCount = count;
                    }
                }
                cells.add(new Cell(entry.getKey(), state));
            }
            cells.sort(Comparator.comparingLong(cell -> cell.position().asLong()));
            return new ContraptionLodMesh(scale, cells);
        }
        return new ContraptionLodMesh(MAX_SCALE, List.of());
    }
}
