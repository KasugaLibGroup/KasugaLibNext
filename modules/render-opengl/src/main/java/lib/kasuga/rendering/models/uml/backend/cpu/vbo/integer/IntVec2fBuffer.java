package lib.kasuga.rendering.models.uml.backend.cpu.vbo.integer;

import org.joml.Vector2f;

public abstract class IntVec2fBuffer extends IntegerBuffer<Vector2f> {

    public IntVec2fBuffer(int dataSize) {
        super(dataSize, Vector2f.class);
    }

    public IntVec2fBuffer(Vector2f[] data) {
        super(data, Vector2f.class);
    }
}
