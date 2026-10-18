package lib.kasuga.rendering.models.mc.backend.record;

import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public record VertexRecord(Vector3f position,
                           Vector3f normal,
                           Vector4f color,
                           Vector4f tangent,
                           Vector2f[] uvs) {}
