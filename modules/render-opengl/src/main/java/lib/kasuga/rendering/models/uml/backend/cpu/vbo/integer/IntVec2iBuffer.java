package lib.kasuga.rendering.models.uml.backend.cpu.vbo.integer;

import org.joml.Vector2i;

public abstract class IntVec2iBuffer extends IntegerBuffer<Vector2i> {

    public IntVec2iBuffer(int dataSize) {
        super(dataSize, Vector2i.class);
    }

    public IntVec2iBuffer(Vector2i[] data) {
        super(data, Vector2i.class);
    }
}
