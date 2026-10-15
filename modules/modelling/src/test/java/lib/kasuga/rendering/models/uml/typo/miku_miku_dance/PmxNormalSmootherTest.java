package lib.kasuga.rendering.models.uml.typo.miku_miku_dance;

import lib.kasuga.rendering.models.uml.math.Transform;
import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lib.kasuga.rendering.models.uml.structure.basic.Vertex;
import lib.kasuga.rendering.models.uml.structure.material.Material;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PmxNormalSmootherTest {

    private static final float EPSILON = 1.0e-5f;

    @Test
    void smoothsCompatibleNormalsAcrossTriangleBoundaries() {
        Vertex shared0 = vertex(0, 0, 0);
        Vertex shared1 = vertex(1, 0, 0);
        Mesh first = triangle(shared0, shared1, vertex(0, 1, 0));
        Mesh second = triangle(shared1, shared0, vertex(0, 1, 0.5f));

        Vector3f firstNormal = new Vector3f(0, 0, 1);
        Vector3f secondNormal = new Vector3f(0, 0.4472136f, 0.8944272f);
        setNormals(first, firstNormal);
        setNormals(second, secondNormal);

        PmxNormalSmoother.smooth(new Mesh[]{first, second});

        Vector3f shared0Normal = shared0.getNormal(first);
        Vector3f shared1Normal = shared1.getNormal(first);
        assertDirection(shared0Normal, shared0.getNormal(second));
        assertDirection(shared1Normal, shared1.getNormal(second));
        assertBetween(firstNormal, secondNormal, shared0Normal);
        assertBetween(firstNormal, secondNormal, shared1Normal);
        assertTrue(first.getNormal().lengthSquared() > 0.99f);
        assertTrue(second.getNormal().lengthSquared() > 0.99f);
    }

    @Test
    void retainsAnAuthoredHardEdge() {
        Vertex shared0 = vertex(0, 0, 0);
        Vertex shared1 = vertex(1, 0, 0);
        Mesh front = triangle(shared0, shared1, vertex(0, 1, 0));
        Mesh top = triangle(shared1, shared0, vertex(0, 0, 1));

        Vector3f frontNormal = new Vector3f(0, 0, 1);
        Vector3f topNormal = new Vector3f(0, 1, 0);
        setNormals(front, frontNormal);
        setNormals(top, topNormal);

        PmxNormalSmoother.smooth(new Mesh[]{front, top});

        assertDirection(frontNormal, shared0.getNormal(front));
        assertDirection(topNormal, shared0.getNormal(top));
        assertDirection(frontNormal, shared1.getNormal(front));
        assertDirection(topNormal, shared1.getNormal(top));
    }

    @Test
    void fallsBackToTheFaceNormalForInvalidCornerNormals() {
        Mesh mesh = triangle(vertex(0, 0, 0), vertex(1, 0, 0), vertex(0, 1, 0));
        setNormals(mesh, new Vector3f());

        PmxNormalSmoother.smooth(new Mesh[]{mesh});

        for (Vertex vertex : mesh.getVertices()) {
            assertDirection(new Vector3f(0, 0, 1), vertex.getNormal(mesh));
        }
    }

    private static Vertex vertex(float x, float y, float z) {
        return new Vertex(new Vector3f(x, y, z), null);
    }

    private static Mesh triangle(Vertex first, Vertex second, Vertex third) {
        return new Mesh(
                new Vertex[]{first, second, third},
                new Vector3f(),
                new Transform(),
                new Material[0],
                null
        );
    }

    private static void setNormals(Mesh mesh, Vector3f normal) {
        for (Vertex vertex : mesh.getVertices()) {
            vertex.getNormals().put(mesh, new Vector3f(normal));
        }
    }

    private static void assertDirection(Vector3f expected, Vector3f actual) {
        assertEquals(1.0f, actual.length(), EPSILON);
        assertTrue(expected.dot(actual) > 1.0f - EPSILON,
                () -> "Expected " + expected + " but was " + actual);
    }

    private static void assertBetween(Vector3f first, Vector3f second, Vector3f actual) {
        assertEquals(1.0f, actual.length(), EPSILON);
        assertTrue(actual.dot(first) > first.dot(second));
        assertTrue(actual.dot(second) > first.dot(second));
    }
}
