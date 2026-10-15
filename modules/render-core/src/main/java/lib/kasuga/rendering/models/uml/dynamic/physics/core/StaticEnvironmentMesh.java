package lib.kasuga.rendering.models.uml.dynamic.physics.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Stable collision handle whose cells can be diffed independently. The native
 * backend compiles the retained cells into one replaceable triangle mesh.
 */
public final class StaticEnvironmentMesh {
    private final Map<Long, ColliderCell> cells = new HashMap<>();
    private final float friction;
    private final float restitution;
    private int solidCount;
    private long revision;

    StaticEnvironmentMesh(float friction, float restitution) {
        this.friction = friction;
        this.restitution = restitution;
    }

    public void putCell(long key, EnvironmentCell cell) {
        EnvironmentCell value = Objects.requireNonNull(cell, "cell");
        ColliderCell previous = cells.get(key);
        if (previous != null && previous.solids.equals(value.solids())) return;
        if (previous != null) solidCount -= previous.solids.size();
        if (value.solids().isEmpty()) {
            if (cells.remove(key) != null) revision++;
            return;
        }
        ColliderCell colliderCell = new ColliderCell(value.solids());
        cells.put(key, colliderCell);
        solidCount += colliderCell.solids.size();
        revision++;
    }

    public void removeCell(long key) {
        ColliderCell removed = cells.remove(key);
        if (removed == null) return;
        solidCount -= removed.solids.size();
        revision++;
    }

    public void clear() {
        if (cells.isEmpty()) return;
        cells.clear();
        solidCount = 0;
        revision++;
    }

    public int cellCount() { return cells.size(); }
    public int solidCount() { return solidCount; }

    long revision() { return revision; }

    float friction() { return friction; }
    float restitution() { return restitution; }

    /**
     * Compiles all retained voxel boxes into one triangle surface. Exact
     * opposing faces are removed, so adjacent full blocks share a continuous
     * top surface instead of producing two independent contact bodies.
     */
    TerrainGeometry geometry() {
        Map<FaceKey, Face> faces = new LinkedHashMap<>();
        for (ColliderCell cell : cells.values()) {
            for (EnvironmentBox box : cell.solids) addBoxFaces(faces, box.minimum(), box.maximum());
        }
        if (faces.isEmpty()) return TerrainGeometry.EMPTY;

        Map<VertexKey, Integer> vertexIndices = new LinkedHashMap<>();
        List<Float> vertices = new ArrayList<>();
        int[] indices = new int[faces.size() * 6];
        int indexOffset = 0;
        for (Face face : faces.values()) {
            int a = vertex(vertexIndices, vertices, face.a);
            int b = vertex(vertexIndices, vertices, face.b);
            int c = vertex(vertexIndices, vertices, face.c);
            int d = vertex(vertexIndices, vertices, face.d);
            indices[indexOffset++] = a; indices[indexOffset++] = b; indices[indexOffset++] = c;
            indices[indexOffset++] = a; indices[indexOffset++] = c; indices[indexOffset++] = d;
        }
        float[] packedVertices = new float[vertices.size()];
        for (int index = 0; index < vertices.size(); index++) packedVertices[index] = vertices.get(index);
        return new TerrainGeometry(packedVertices, indices);
    }

    private static int vertex(Map<VertexKey, Integer> indices, List<Float> vertices, Point point) {
        VertexKey key = new VertexKey(Float.floatToIntBits(point.x),
                Float.floatToIntBits(point.y), Float.floatToIntBits(point.z));
        return indices.computeIfAbsent(key, ignored -> {
            int result = vertices.size() / 3;
            vertices.add(point.x); vertices.add(point.y); vertices.add(point.z);
            return result;
        });
    }

    private static void addBoxFaces(Map<FaceKey, Face> faces,
                                    org.joml.Vector3f minimum, org.joml.Vector3f maximum) {
        float x0 = minimum.x, y0 = minimum.y, z0 = minimum.z;
        float x1 = maximum.x, y1 = maximum.y, z1 = maximum.z;
        addFace(faces, 0, x0, y0, y1, z0, z1, -1,
                p(x0,y0,z0), p(x0,y0,z1), p(x0,y1,z1), p(x0,y1,z0));
        addFace(faces, 0, x1, y0, y1, z0, z1, 1,
                p(x1,y0,z0), p(x1,y1,z0), p(x1,y1,z1), p(x1,y0,z1));
        addFace(faces, 1, y0, x0, x1, z0, z1, -1,
                p(x0,y0,z0), p(x1,y0,z0), p(x1,y0,z1), p(x0,y0,z1));
        addFace(faces, 1, y1, x0, x1, z0, z1, 1,
                p(x0,y1,z0), p(x0,y1,z1), p(x1,y1,z1), p(x1,y1,z0));
        addFace(faces, 2, z0, x0, x1, y0, y1, -1,
                p(x0,y0,z0), p(x0,y1,z0), p(x1,y1,z0), p(x1,y0,z0));
        addFace(faces, 2, z1, x0, x1, y0, y1, 1,
                p(x0,y0,z1), p(x1,y0,z1), p(x1,y1,z1), p(x0,y1,z1));
    }

    private static Point p(float x, float y, float z) { return new Point(x, y, z); }

    private static void addFace(Map<FaceKey, Face> faces, int axis, float plane,
                                float u0, float u1, float v0, float v1, int sign,
                                Point a, Point b, Point c, Point d) {
        FaceKey key = new FaceKey(axis, Float.floatToIntBits(plane),
                Float.floatToIntBits(u0), Float.floatToIntBits(u1),
                Float.floatToIntBits(v0), Float.floatToIntBits(v1));
        Face previous = faces.get(key);
        if (previous != null && previous.sign != sign) faces.remove(key);
        else if (previous == null) faces.put(key, new Face(sign, a, b, c, d));
    }

    static final class ColliderCell {
        final List<EnvironmentBox> solids;

        ColliderCell(List<EnvironmentBox> solids) {
            this.solids = List.copyOf(solids);
        }
    }

    record TerrainGeometry(float[] vertices, int[] indices) {
        static final TerrainGeometry EMPTY = new TerrainGeometry(new float[0], new int[0]);
    }
    private record Point(float x, float y, float z) {}
    private record VertexKey(int x, int y, int z) {}
    private record FaceKey(int axis, int plane, int u0, int u1, int v0, int v1) {}
    private record Face(int sign, Point a, Point b, Point c, Point d) {}
}
