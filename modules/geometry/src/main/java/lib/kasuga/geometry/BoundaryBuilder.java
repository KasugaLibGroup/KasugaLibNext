package lib.kasuga.geometry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

final class BoundaryBuilder {
    private BoundaryBuilder() {
    }

    static List<BoundaryEdge> build(
            Csg.Grid grid,
            BitSet occupied,
            GeometryBudget budget,
            GeometryWork work
    ) {
        if (occupied.isEmpty()) {
            return List.of();
        }

        // Face-cell enumeration and 2D perimeter extraction are bounded by this conservative
        // preflight. It runs before the face map and perimeter segment lists allocate.
        work.ensureAdditional((long) grid.cellCount() * 24L);
        TreeMap<FaceKey, List<FaceRect>> faces = new TreeMap<>();
        for (int z = 0; z < grid.cellZ(); z++) {
            for (int y = 0; y < grid.cellY(); y++) {
                for (int x = 0; x < grid.cellX(); x++) {
                    if (!grid.occupied(occupied, x, y, z)) continue;
                    addExposedFaces(grid, occupied, faces, x, y, z);
                }
            }
        }

        List<BoundaryEdge> perimeter = new ArrayList<>();
        for (Map.Entry<FaceKey, List<FaceRect>> entry : faces.entrySet()) {
            extractFacePerimeter(entry.getKey(), entry.getValue(), work, budget, perimeter);
        }
        return mergeEdges(perimeter, work);
    }

    private static void addExposedFaces(
            Csg.Grid grid,
            BitSet occupied,
            TreeMap<FaceKey, List<FaceRect>> faces,
            int x,
            int y,
            int z
    ) {
        if (x == 0 || !grid.occupied(occupied, x - 1, y, z)) {
            addFace(faces, new FaceKey(Axis.X, false, grid.x()[x]),
                    new FaceRect(grid.y()[y], grid.z()[z], grid.y()[y + 1], grid.z()[z + 1]));
        }
        if (x + 1 == grid.cellX() || !grid.occupied(occupied, x + 1, y, z)) {
            addFace(faces, new FaceKey(Axis.X, true, grid.x()[x + 1]),
                    new FaceRect(grid.y()[y], grid.z()[z], grid.y()[y + 1], grid.z()[z + 1]));
        }
        if (y == 0 || !grid.occupied(occupied, x, y - 1, z)) {
            addFace(faces, new FaceKey(Axis.Y, false, grid.y()[y]),
                    new FaceRect(grid.x()[x], grid.z()[z], grid.x()[x + 1], grid.z()[z + 1]));
        }
        if (y + 1 == grid.cellY() || !grid.occupied(occupied, x, y + 1, z)) {
            addFace(faces, new FaceKey(Axis.Y, true, grid.y()[y + 1]),
                    new FaceRect(grid.x()[x], grid.z()[z], grid.x()[x + 1], grid.z()[z + 1]));
        }
        if (z == 0 || !grid.occupied(occupied, x, y, z - 1)) {
            addFace(faces, new FaceKey(Axis.Z, false, grid.z()[z]),
                    new FaceRect(grid.x()[x], grid.y()[y], grid.x()[x + 1], grid.y()[y + 1]));
        }
        if (z + 1 == grid.cellZ() || !grid.occupied(occupied, x, y, z + 1)) {
            addFace(faces, new FaceKey(Axis.Z, true, grid.z()[z + 1]),
                    new FaceRect(grid.x()[x], grid.y()[y], grid.x()[x + 1], grid.y()[y + 1]));
        }
    }

    private static void addFace(
            TreeMap<FaceKey, List<FaceRect>> faces,
            FaceKey key,
            FaceRect rectangle
    ) {
        faces.computeIfAbsent(key, ignored -> new ArrayList<>()).add(rectangle);
    }

    private static void extractFacePerimeter(
            FaceKey face,
            List<FaceRect> rectangles,
            GeometryWork work,
            GeometryBudget budget,
            List<BoundaryEdge> output
    ) {
        float[] us = uniqueCoordinates(rectangles, true);
        float[] vs = uniqueCoordinates(rectangles, false);
        int cellU = us.length - 1;
        int cellV = vs.length - 1;
        long cellCount = (long) cellU * cellV;
        if (cellCount > Integer.MAX_VALUE) {
            throw new GeometryException("compressed face grid exceeds the JVM array index range");
        }
        work.ensureAdditional(cellCount);
        BitSet cells = new BitSet((int) cellCount);
        for (FaceRect rectangle : rectangles) {
            int u0 = indexOf(us, rectangle.uMin);
            int u1 = indexOf(us, rectangle.uMax);
            int v0 = indexOf(vs, rectangle.vMin);
            int v1 = indexOf(vs, rectangle.vMax);
            work.advance((long) (u1 - u0) * (v1 - v0));
            for (int v = v0; v < v1; v++) {
                int from = v * cellU + u0;
                int to = v * cellU + u1;
                cells.set(from, to);
            }
        }

        work.ensureAdditional(cellCount);
        work.advance(cellCount);
        for (int u = 0; u < us.length; u++) {
            for (int v = 0; v < cellV; v++) {
                boolean left = u > 0 && cells.get(v * cellU + u - 1);
                boolean right = u < cellU && cells.get(v * cellU + u);
                if (left != right) {
                    output.add(edgeFor(face, us[u], vs[v], us[u], vs[v + 1]));
                }
            }
        }
        for (int v = 0; v < vs.length; v++) {
            for (int u = 0; u < cellU; u++) {
                boolean lower = v > 0 && cells.get((v - 1) * cellU + u);
                boolean upper = v < cellV && cells.get(v * cellU + u);
                if (lower != upper) {
                    output.add(edgeFor(face, us[u], vs[v], us[u + 1], vs[v]));
                }
            }
        }
    }

    private static BoundaryEdge edgeFor(
            FaceKey face,
            float u0,
            float v0,
            float u1,
            float v1
    ) {
        return switch (face.axis) {
            case X -> new BoundaryEdge(
                    face.plane, u0, v0,
                    face.plane, u1, v1
            );
            case Y -> new BoundaryEdge(
                    u0, face.plane, v0,
                    u1, face.plane, v1
            );
            case Z -> new BoundaryEdge(
                    u0, v0, face.plane,
                    u1, v1, face.plane
            );
        };
    }

    private static List<BoundaryEdge> mergeEdges(
            List<BoundaryEdge> rawEdges,
            GeometryWork work
    ) {
        work.ensureAdditional(rawEdges.size());
        TreeMap<EdgeLine, List<Interval>> lines = new TreeMap<>();
        for (BoundaryEdge edge : rawEdges) {
            Axis axis = edge.axis();
            EdgeLine line = switch (axis) {
                case X -> new EdgeLine(axis, edge.startY(), edge.startZ());
                case Y -> new EdgeLine(axis, edge.startX(), edge.startZ());
                case Z -> new EdgeLine(axis, edge.startX(), edge.startY());
            };
            float start = switch (axis) {
                case X -> edge.startX();
                case Y -> edge.startY();
                case Z -> edge.startZ();
            };
            float end = switch (axis) {
                case X -> edge.endX();
                case Y -> edge.endY();
                case Z -> edge.endZ();
            };
            lines.computeIfAbsent(line, ignored -> new ArrayList<>()).add(new Interval(start, end));
        }

        List<BoundaryEdge> merged = new ArrayList<>();
        for (Map.Entry<EdgeLine, List<Interval>> entry : lines.entrySet()) {
            List<Interval> intervals = entry.getValue();
            intervals.sort(Comparator.comparingDouble(interval -> interval.start));
            Interval current = intervals.get(0);
            for (int index = 1; index < intervals.size(); index++) {
                Interval next = intervals.get(index);
                if (next.start <= current.end) {
                    current = new Interval(current.start, Math.max(current.end, next.end));
                } else {
                    merged.add(edgeFor(entry.getKey(), current));
                    current = next;
                }
            }
            merged.add(edgeFor(entry.getKey(), current));
        }

        merged.sort(Comparator
                .comparing(BoundaryEdge::axis)
                .thenComparing(BoundaryEdge::startX)
                .thenComparing(BoundaryEdge::startY)
                .thenComparing(BoundaryEdge::startZ)
                .thenComparing(BoundaryEdge::endX)
                .thenComparing(BoundaryEdge::endY)
                .thenComparing(BoundaryEdge::endZ));
        work.advance(merged.size());
        return List.copyOf(merged);
    }

    private static BoundaryEdge edgeFor(EdgeLine line, Interval interval) {
        return switch (line.axis) {
            case X -> new BoundaryEdge(
                    interval.start, line.first, line.second,
                    interval.end, line.first, line.second
            );
            case Y -> new BoundaryEdge(
                    line.first, interval.start, line.second,
                    line.first, interval.end, line.second
            );
            case Z -> new BoundaryEdge(
                    line.first, line.second, interval.start,
                    line.first, line.second, interval.end
            );
        };
    }

    private static float[] uniqueCoordinates(List<FaceRect> rectangles, boolean useU) {
        float[] values = new float[rectangles.size() * 2];
        int cursor = 0;
        for (FaceRect rectangle : rectangles) {
            values[cursor++] = useU ? rectangle.uMin : rectangle.vMin;
            values[cursor++] = useU ? rectangle.uMax : rectangle.vMax;
        }
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
            throw new IllegalStateException("face coordinate missing: " + value);
        }
        return index;
    }

    private record FaceKey(Axis axis, boolean positive, float plane)
            implements Comparable<FaceKey> {
        @Override
        public int compareTo(FaceKey other) {
            int axisOrder = axis.compareTo(other.axis);
            if (axisOrder != 0) return axisOrder;
            int sideOrder = Boolean.compare(positive, other.positive);
            if (sideOrder != 0) return sideOrder;
            return Float.compare(plane, other.plane);
        }
    }

    private record FaceRect(float uMin, float vMin, float uMax, float vMax) {
    }

    private record Interval(float start, float end) {
        Interval {
            if (!(start < end)) {
                throw new GeometryException("boundary interval is degenerate");
            }
        }
    }

    private record EdgeLine(Axis axis, float first, float second)
            implements Comparable<EdgeLine> {
        @Override
        public int compareTo(EdgeLine other) {
            int axisOrder = axis.compareTo(other.axis);
            if (axisOrder != 0) return axisOrder;
            int firstOrder = Float.compare(first, other.first);
            if (firstOrder != 0) return firstOrder;
            return Float.compare(second, other.second);
        }
    }
}
