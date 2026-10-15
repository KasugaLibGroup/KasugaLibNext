package lib.kasuga.rendering.models.uml.math;

import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.structure.Pair;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Vector;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

public class TangentHelper {

    public static record PosUVNormal(Vector3f position, Vector2f uv, Vector3f normal) {}

    public static record TangentResult(Vector vector, Vector4f tangent) {}


    public static void computeTangents(Mesh mesh, Function<Vertex, PosUVNormal> supplierFunc, BiConsumer<Vertex, Vector4f> resultConsumer) {
        Vertex[] vertex = mesh.getVertices();
        if (vertex.length < 3) return;
        Vertex self, edge1, edge2;
        PosUVNormal inputSelf, inputEdge1, inputEdge2;
        Vector4f result;
        for (int i = 0; i < vertex.length; i++) {
            self = vertex[i];
            edge1 = vertex[(i + vertex.length - 1) % vertex.length];
            edge2 = vertex[(i + 1) % vertex.length];
            inputSelf = supplierFunc.apply(self);
            inputEdge1 = supplierFunc.apply(edge1);
            inputEdge2 = supplierFunc.apply(edge2);
            result = computeTangent(
                    inputSelf.normal, inputSelf.position,
                    inputEdge1.position, inputEdge2.position,
                    inputSelf.uv, inputEdge1.uv, inputEdge2.uv
            );
            resultConsumer.accept(self, result);
        }
    }

    public static Vector4f computeTangent(Supplier<PosUVNormal> normalPosSupplier,
                                          Supplier<PosUVNormal> edge1Supplier,
                                          Supplier<PosUVNormal> edge2Supplier) {
        PosUVNormal posUVNormal = normalPosSupplier.get();
        PosUVNormal edge1 = edge1Supplier.get();
        PosUVNormal edge2 = edge2Supplier.get();
        return computeTangent(
                posUVNormal.normal, posUVNormal.position,
                edge1.position, edge2.position,
                posUVNormal.uv, edge1.uv, edge2.uv
        );
    }

    public static Vector4f computeTangent(Vector3f normal, Vector3f positionSelf,
                                          Vector3f positionEdge1, Vector3f positionEdge2,
                                          Vector2f uvSelf, Vector2f uvEdge1, Vector2f uvEdge2) {
        Vector3f edge1 = new Vector3f(positionEdge1).sub(positionSelf);
        Vector3f edge2 = new Vector3f(positionEdge2).sub(positionSelf);
        Vector2f deltaUV1 = new Vector2f(uvEdge1).sub(uvSelf);
        Vector2f deltaUV2 = new Vector2f(uvEdge2).sub(uvSelf);
        float fdenom = deltaUV1.x() * deltaUV2.y() - deltaUV2.x() * deltaUV1.y();
        float f;
        if ((double) fdenom == (double) 0.0F) {
            f = 1.0f;
        } else {
            f = 1.0f / fdenom;
        }

        Vector3f tangent = new Vector3f();
        tangent.x = f * (deltaUV2.y * edge1.x - deltaUV1.y * edge2.x);
        tangent.y = f * (deltaUV2.y * edge1.y - deltaUV1.y * edge2.y);
        tangent.z = f * (deltaUV2.y * edge1.z - deltaUV1.y * edge2.z);
        tangent.normalize();

        if (tangent.length() == 0.0f) {
            return new Vector4f();
        }

        float biTangentX = f * (tangent.y() * normal.z() - tangent.z() * normal.y());
        float biTangentY = f * (tangent.z() * normal.x() - tangent.x() * normal.z());
        float biTangentZ = f * (tangent.x() * normal.y() - tangent.y() * normal.x());
        tangent.set(biTangentX, biTangentY, biTangentZ);
        float dot = tangent.length();
        tangent.mul(1 / dot);
        float tangentW = dot < 0.0f ? -1.0f : 1.0f;

        return new Vector4f(tangent, tangentW);
    }
}
