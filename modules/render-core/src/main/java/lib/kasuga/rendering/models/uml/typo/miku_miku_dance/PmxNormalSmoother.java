package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * Prepares PMX corner normals before the model is flattened for rendering.
 *
 * <p>PMX models commonly duplicate a vertex at material and UV seams. The UML
 * loader intentionally merges those duplicates by position and skin binding,
 * while retaining normals per triangle. This pass joins compatible corner
 * normals again so smooth surfaces do not acquire a visible triangle boundary.
 * Geometric face normals and authored corner normals both participate in the
 * crease test, so an intentional hard edge is retained.</p>
 */
public final class PmxNormalSmoother {

    public static final float DEFAULT_CREASE_ANGLE_DEGREES = 60.0f;

    private static final float MIN_LENGTH_SQUARED = 1.0e-12f;

    private PmxNormalSmoother() {}

    public static void smooth(Mesh[] meshes) {
        smooth(meshes, DEFAULT_CREASE_ANGLE_DEGREES);
    }

    public static void smooth(Mesh[] meshes, float creaseAngleDegrees) {
        if (meshes == null || meshes.length == 0) return;
        if (!Float.isFinite(creaseAngleDegrees)
                || creaseAngleDegrees < 0.0f
                || creaseAngleDegrees > 180.0f) {
            throw new IllegalArgumentException("creaseAngleDegrees must be finite and within [0, 180]");
        }

        float creaseCosine = (float) Math.cos(Math.toRadians(creaseAngleDegrees));
        Map<Vertex, List<Corner>> cornersByVertex = new IdentityHashMap<>();

        for (Mesh mesh : meshes) {
            if (mesh == null) continue;
            Vertex[] vertices = mesh.getVertices();
            if (vertices == null || vertices.length != 3) continue;

            Vector3f faceNormal = calculateFaceNormal(vertices);
            orientFaceNormalToAuthoredNormals(faceNormal, vertices, mesh);
            mesh.getNormal().set(faceNormal);

            for (int i = 0; i < 3; i++) {
                Vertex vertex = vertices[i];
                Vector3f authoredNormal = normalizedOrFallback(vertex.getNormal(mesh), faceNormal);
                float cornerWeight = calculateCornerAngle(vertices, i);
                cornersByVertex.computeIfAbsent(vertex, ignored -> new ArrayList<>())
                        .add(new Corner(vertex, mesh, authoredNormal, new Vector3f(faceNormal), cornerWeight));
            }
        }

        for (List<Corner> corners : cornersByVertex.values()) {
            smoothCorners(corners, creaseCosine);
        }
    }

    private static void smoothCorners(List<Corner> corners, float creaseCosine) {
        for (Corner target : corners) {
            Vector3f smoothed = new Vector3f();
            float totalWeight = 0.0f;

            for (Corner candidate : corners) {
                if (!compatible(target.faceNormal(), candidate.faceNormal(), creaseCosine)) continue;
                if (!compatible(target.authoredNormal(), candidate.authoredNormal(), creaseCosine)) continue;

                smoothed.fma(candidate.cornerWeight(), candidate.authoredNormal());
                totalWeight += candidate.cornerWeight();
            }

            if (totalWeight <= 0.0f || !isUsable(smoothed)) {
                smoothed.set(target.authoredNormal());
            } else {
                smoothed.normalize();
            }

            target.vertex().getNormals().put(target.mesh(), smoothed);
        }
    }

    private static boolean compatible(Vector3f first, Vector3f second, float creaseCosine) {
        if (!isUsable(first) || !isUsable(second)) return true;
        return first.dot(second) + 1.0e-6f >= creaseCosine;
    }

    private static Vector3f calculateFaceNormal(Vertex[] vertices) {
        Vector3f edge1 = new Vector3f(vertices[1].getPosition()).sub(vertices[0].getPosition());
        Vector3f edge2 = new Vector3f(vertices[2].getPosition()).sub(vertices[0].getPosition());
        Vector3f result = edge1.cross(edge2);
        return isUsable(result) ? result.normalize() : result.zero();
    }

    private static void orientFaceNormalToAuthoredNormals(Vector3f faceNormal, Vertex[] vertices, Mesh mesh) {
        if (!isUsable(faceNormal)) return;

        Vector3f authoredGuide = new Vector3f();
        for (Vertex vertex : vertices) {
            Vector3f normal = vertex.getNormal(mesh);
            if (isUsable(normal)) authoredGuide.add(new Vector3f(normal).normalize());
        }
        if (isUsable(authoredGuide) && faceNormal.dot(authoredGuide) < 0.0f) {
            faceNormal.negate();
        }
    }

    private static Vector3f normalizedOrFallback(Vector3f normal, Vector3f fallback) {
        if (isUsable(normal)) return new Vector3f(normal).normalize();
        if (isUsable(fallback)) return new Vector3f(fallback);
        return new Vector3f();
    }

    private static float calculateCornerAngle(Vertex[] vertices, int index) {
        Vector3f position = vertices[index].getPosition();
        Vector3f first = new Vector3f(vertices[(index + 1) % 3].getPosition()).sub(position);
        Vector3f second = new Vector3f(vertices[(index + 2) % 3].getPosition()).sub(position);
        if (!isUsable(first) || !isUsable(second)) return 1.0f;

        first.normalize();
        second.normalize();
        float cosine = Math.clamp(first.dot(second), -1.0f, 1.0f);
        float angle = (float) Math.acos(cosine);
        return Float.isFinite(angle) && angle > 0.0f ? angle : 1.0f;
    }

    private static boolean isUsable(Vector3f vector) {
        return Float.isFinite(vector.x)
                && Float.isFinite(vector.y)
                && Float.isFinite(vector.z)
                && vector.lengthSquared() > MIN_LENGTH_SQUARED;
    }

    private record Corner(
            Vertex vertex,
            Mesh mesh,
            Vector3f authoredNormal,
            Vector3f faceNormal,
            float cornerWeight
    ) {}
}
