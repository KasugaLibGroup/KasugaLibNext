package lib.kasuga.rendering.models.uml.backend.cpu.vbo.integer;

import org.joml.Vector3f;

public abstract class IntVec3fBuffer extends IntegerBuffer<Vector3f> {

    public IntVec3fBuffer(int dataSize) {
        super(dataSize, Vector3f.class);
    }

    public IntVec3fBuffer(Vector3f[] data) {
        super(data, Vector3f.class);
    }
}
