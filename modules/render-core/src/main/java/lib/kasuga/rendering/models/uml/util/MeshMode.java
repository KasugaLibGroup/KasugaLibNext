package lib.kasuga.rendering.models.uml.util;

import org.jetbrains.annotations.Nullable;

public enum MeshMode {

    LINES(2),
    TRIANGLES(3),
    QUADS(4),
    MIXED(4);

    public final int vertexCount;

    MeshMode(int vertexCount) {
        this.vertexCount = vertexCount;
    }

    public static @Nullable MeshMode getByVertexCount(int vertexCount) {
        for (MeshMode m : values()) {
            if (m.vertexCount == vertexCount) {
                return m;
            }
        }
        return null;
    }
}
