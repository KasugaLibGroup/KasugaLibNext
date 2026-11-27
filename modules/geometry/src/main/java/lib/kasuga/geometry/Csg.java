package lib.kasuga.geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.List;

final class Csg {
    private Csg() {
    }

    static Shape normalize(Collection<Box> rawBoxes, int depth, GeometryBudget budget) {
        budget.checkDepth(depth);
        GeometryWork work = budget.startWork();
        List<Box> boxes = copyBoxes(rawBoxes, work, budget, "shape normalization");
        if (boxes.isEmpty()) {
            return Shape.emptyAtDepth(depth);
        }
        Grid grid = createGrid(boxes, work, budget);
        BitSet occupied = new BitSet(grid.cellCount);
        markBoxes(boxes, grid, occupied, work);
        return finish(grid, occupied, depth, budget, work);
    }

    static Shape union(List<Shape> shapes, int depth, GeometryBudget budget) {
        budget.checkDepth(depth);
        GeometryWork work = budget.startWork();
        List<List<Box>> operands = operandBoxes(shapes, work);
        int totalBoxes = totalBoxCount(operands, work);
        List<Box> coordinates = concatenate(operands, totalBoxes, work);
        if (coordinates.isEmpty()) {
            return Shape.emptyAtDepth(depth);
        }
        Grid grid = createGrid(coordinates, work, budget);
        BitSet occupied = new BitSet(grid.cellCount);
        for (List<Box> operand : operands) {
            markBoxes(operand, grid, occupied, work);
        }
        return finish(grid, occupied, depth, budget, work);
    }

    static Shape intersection(List<Shape> shapes, int depth, GeometryBudget budget) {
        budget.checkDepth(depth);
        GeometryWork work = budget.startWork();
        List<List<Box>> operands = operandBoxes(shapes, work);
        for (List<Box> operand : operands) {
            if (operand.isEmpty()) {
                return Shape.emptyAtDepth(depth);
            }
        }
        int totalBoxes = totalBoxCount(operands, work);
        List<Box> coordinates = concatenate(operands, totalBoxes, work);
        Grid grid = createGrid(coordinates, work, budget);
        BitSet occupied = new BitSet(grid.cellCount);
        BitSet operandCells = new BitSet(grid.cellCount);
        markBoxes(operands.get(0), grid, occupied, work);
        for (int index = 1; index < operands.size() && !occupied.isEmpty(); index++) {
            operandCells.clear();
            markBoxes(operands.get(index), grid, operandCells, work);
            occupied.and(operandCells);
        }
        return finish(grid, occupied, depth, budget, work);
    }

    static Shape difference(Shape minuend, List<Shape> subtrahends, int depth, GeometryBudget budget) {
        budget.checkDepth(depth);
        GeometryWork work = budget.startWork();
        List<List<Box>> operands = new ArrayList<>(subtrahends.size() + 1);
        operands.add(minuend.boxes());
        for (Shape subtrahend : subtrahends) {
            operands.add(subtrahend.boxes());
        }
        int totalBoxes = totalBoxCount(operands, work);
        List<Box> coordinates = concatenate(operands, totalBoxes, work);
        if (minuend.isEmpty()) {
            return Shape.emptyAtDepth(depth);
        }
        Grid grid = createGrid(coordinates, work, budget);
        BitSet occupied = new BitSet(grid.cellCount);
        markBoxes(minuend.boxes(), grid, occupied, work);
        for (Shape subtrahend : subtrahends) {
            clearBoxes(subtrahend.boxes(), grid, occupied, work);
        }
        return finish(grid, occupied, depth, budget, work);
    }

    private static Shape finish(
            Grid grid,
            BitSet occupied,
            int depth,
            GeometryBudget budget,
            GeometryWork work
    ) {
        if (occupied.isEmpty()) {
            return Shape.emptyAtDepth(depth);
        }
        List<Box> result = collectBoxes(grid, occupied, budget, work);
        Bounds bounds = Bounds.of(result);
        List<BoundaryEdge> edges = BoundaryBuilder.build(grid, occupied, budget, work);
        return Shape.ofNormalized(result, bounds, edges, depth);
    }

    private static List<Box> collectBoxes(
            Grid grid,
            BitSet occupied,
            GeometryBudget budget,
            GeometryWork work
    ) {
        BitSet consumed = new BitSet(grid.cellCount);
        List<Box> result = new ArrayList<>();
        work.advance(grid.cellCount);

        for (int z = 0; z < grid.cellZ(); z++) {
            for (int y = 0; y < grid.cellY(); y++) {
                int x = 0;
                while (x < grid.cellX()) {
                    int index = grid.index(x, y, z);
                    if (!occupied.get(index) || consumed.get(index)) {
                        x++;
                        continue;
                    }

                    int endX = x + 1;
                    while (endX < grid.cellX()) {
                        int candidate = grid.index(endX, y, z);
                        if (!occupied.get(candidate) || consumed.get(candidate)) break;
                        endX++;
                    }

                    int endY = y + 1;
                    while (endY < grid.cellY() && rangeFree(grid, consumed, occupied, x, endX, endY, z)) {
                        endY++;
                    }

                    int endZ = z + 1;
                    while (endZ < grid.cellZ()
                            && volumeFree(grid, consumed, occupied, x, endX, y, endY, endZ)) {
                        endZ++;
                    }

                    for (int zz = z; zz < endZ; zz++) {
                        for (int yy = y; yy < endY; yy++) {
                            int from = grid.index(x, yy, zz);
                            int to = grid.index(endX, yy, zz);
                            consumed.set(from, to);
                        }
                    }

                    budget.checkFragments(result.size() + 1);
                    result.add(new Box(
                            grid.x[x], grid.y[y], grid.z[z],
                            grid.x[endX], grid.y[endY], grid.z[endZ]
                    ));
                    x = endX;
                }
            }
        }
        validateDisjoint(result);
        return result;
    }

    private static boolean rangeFree(
            Grid grid,
            BitSet consumed,
            BitSet occupied,
            int xFrom,
            int xTo,
            int y,
            int z
    ) {
        for (int x = xFrom; x < xTo; x++) {
            int index = grid.index(x, y, z);
            if (!occupied.get(index) || consumed.get(index)) return false;
        }
        return true;
    }

    private static boolean volumeFree(
            Grid grid,
            BitSet consumed,
            BitSet occupied,
            int xFrom,
            int xTo,
            int yFrom,
            int yTo,
            int z
    ) {
        for (int y = yFrom; y < yTo; y++) {
            if (!rangeFree(grid, consumed, occupied, xFrom, xTo, y, z)) return false;
        }
        return true;
    }

    private static void validateDisjoint(List<Box> boxes) {
        for (int left = 0; left < boxes.size(); left++) {
            for (int right = left + 1; right < boxes.size(); right++) {
                if (boxes.get(left).overlapsVolume(boxes.get(right))) {
                    throw new GeometryException("CSG produced overlapping result fragments");
                }
            }
        }
    }

    private static Grid createGrid(List<Box> boxes, GeometryWork work, GeometryBudget budget) {
        if (boxes.size() > Integer.MAX_VALUE / 2) {
            throw new GeometryException("too many input boxes for coordinate arrays");
        }
        int capacity = boxes.size() * 2;
        work.ensureAdditional(boxes.size() * 2L);
        float[] xs = new float[capacity];
        float[] ys = new float[capacity];
        float[] zs = new float[capacity];
        int cursor = 0;
        for (Box box : boxes) {
            xs[cursor] = box.minX();
            xs[cursor + 1] = box.maxX();
            ys[cursor] = box.minY();
            ys[cursor + 1] = box.maxY();
            zs[cursor] = box.minZ();
            zs[cursor + 1] = box.maxZ();
            cursor += 2;
        }
        xs = uniqueSorted(xs);
        ys = uniqueSorted(ys);
        zs = uniqueSorted(zs);

        int cellX = xs.length - 1;
        int cellY = ys.length - 1;
        int cellZ = zs.length - 1;
        long cellCount = (long) cellX * cellY * cellZ;
        if (cellCount > Integer.MAX_VALUE) {
            throw new GeometryException("compressed CSG grid exceeds the JVM array index range");
        }

        long markingVolume = 0L;
        for (Box box : boxes) {
            markingVolume = saturatingAdd(markingVolume, boundedVolume(box, xs, ys, zs));
        }
        work.ensureAdditional((long) boxes.size() + cellCount + markingVolume);
        return new Grid(xs, ys, zs, (int) cellCount);
    }

    private static long boundedVolume(
            Box box,
            float[] xs,
            float[] ys,
            float[] zs
    ) {
        long width = indexOf(xs, box.maxX()) - indexOf(xs, box.minX());
        long height = indexOf(ys, box.maxY()) - indexOf(ys, box.minY());
        long depth = indexOf(zs, box.maxZ()) - indexOf(zs, box.minZ());
        return saturatingMultiply(width, height, depth);
    }

    private static void markBoxes(
            List<Box> boxes,
            Grid grid,
            BitSet target,
            GeometryWork work
    ) {
        for (Box box : boxes) {
            int x0 = indexOf(grid.x, box.minX());
            int x1 = indexOf(grid.x, box.maxX());
            int y0 = indexOf(grid.y, box.minY());
            int y1 = indexOf(grid.y, box.maxY());
            int z0 = indexOf(grid.z, box.minZ());
            int z1 = indexOf(grid.z, box.maxZ());
            long volume = saturatingMultiply(x1 - x0, y1 - y0, z1 - z0);
            work.advance(volume);
            for (int z = z0; z < z1; z++) {
                for (int y = y0; y < y1; y++) {
                    int from = grid.index(x0, y, z);
                    int to = grid.index(x1, y, z);
                    target.set(from, to);
                }
            }
        }
    }

    private static void clearBoxes(
            List<Box> boxes,
            Grid grid,
            BitSet target,
            GeometryWork work
    ) {
        for (Box box : boxes) {
            int x0 = indexOf(grid.x, box.minX());
            int x1 = indexOf(grid.x, box.maxX());
            int y0 = indexOf(grid.y, box.minY());
            int y1 = indexOf(grid.y, box.maxY());
            int z0 = indexOf(grid.z, box.minZ());
            int z1 = indexOf(grid.z, box.maxZ());
            work.advance(saturatingMultiply(x1 - x0, y1 - y0, z1 - z0));
            for (int z = z0; z < z1; z++) {
                for (int y = y0; y < y1; y++) {
                    int from = grid.index(x0, y, z);
                    int to = grid.index(x1, y, z);
                    target.clear(from, to);
                }
            }
        }
    }

    private static List<List<Box>> operandBoxes(List<Shape> shapes, GeometryWork work) {
        work.ensureAdditional(shapes.size());
        List<List<Box>> operands = new ArrayList<>(shapes.size());
        for (Shape shape : shapes) {
            operands.add(shape.boxes());
        }
        return operands;
    }

    private static int totalBoxCount(List<List<Box>> operands, GeometryWork work) {
        long count = 0L;
        for (List<Box> operand : operands) {
            count = saturatingAdd(count, operand.size());
        }
        if (count > Integer.MAX_VALUE) {
            throw new GeometryException("too many input boxes for one CSG operation");
        }
        work.ensureAdditional(count);
        return (int) count;
    }

    private static List<Box> concatenate(
            List<List<Box>> operands,
            int totalBoxes,
            GeometryWork work
    ) {
        work.ensureAdditional(totalBoxes);
        List<Box> result = new ArrayList<>(totalBoxes);
        for (List<Box> operand : operands) {
            result.addAll(operand);
        }
        return result;
    }

    private static List<Box> copyBoxes(
            Collection<Box> boxes,
            GeometryWork work,
            GeometryBudget budget,
            String operation
    ) {
        work.ensureAdditional(boxes.size());
        if (boxes.isEmpty()) {
            return List.of();
        }
        List<Box> copy = new ArrayList<>(boxes.size());
        for (Box box : boxes) {
            copy.add(java.util.Objects.requireNonNull(box, operation + " box"));
        }
        return copy;
    }

    private static float[] uniqueSorted(float[] values) {
        Arrays.sort(values);
        int size = 0;
        for (float value : values) {
            if (size == 0 || Float.compare(values[size - 1], value) != 0) {
                values[size++] = value;
            }
        }
        return Arrays.copyOf(values, size);
    }

    private static int indexOf(float[] values, float value) {
        int index = Arrays.binarySearch(values, value);
        if (index < 0) {
            throw new IllegalStateException("grid coordinate missing: " + value);
        }
        return index;
    }

    private static long saturatingAdd(long left, long right) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingMultiply(long first, long second, long third) {
        try {
            return Math.multiplyExact(Math.multiplyExact(first, second), third);
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }

    record Grid(float[] x, float[] y, float[] z, int cellCount) {
        int cellX() {
            return x.length - 1;
        }

        int cellY() {
            return y.length - 1;
        }

        int cellZ() {
            return z.length - 1;
        }

        int index(int xIndex, int yIndex, int zIndex) {
            return (zIndex * cellY() + yIndex) * cellX() + xIndex;
        }

        boolean occupied(BitSet cells, int xIndex, int yIndex, int zIndex) {
            return cells.get(index(xIndex, yIndex, zIndex));
        }
    }
}
