package lib.kasuga.rendering.models.uml.backend.cpu.vbo.integer;

import org.joml.Vector4f;

public abstract class IntVec4fBuffer extends IntegerBuffer<Vector4f> {
    public IntVec4fBuffer(int dataSize) {
        super(dataSize, Vector4f.class);
    }

    public IntVec4fBuffer(Vector4f[] data) {
        super(data, Vector4f.class);
    }
}
