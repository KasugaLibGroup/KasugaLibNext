package lib.kasuga.rendering.lighting;

import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Six nonnegative world-axis diffuse lobes (+X,-X,+Y,-Y,+Z,-Z).
 * Squared direction cosines reconstruct a continuous irradiance field. */
public final class DiffuseIrradiance {
    public static final DiffuseIrradiance ZERO = new DiffuseIrradiance(new Vector3f(), new Vector3f(),
            new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f());
    private final float[] values = new float[18];
    public DiffuseIrradiance(Vector3fc... lobes) {
        if (lobes.length != 6) throw new IllegalArgumentException("Diffuse irradiance needs six lobes");
        for (int i = 0; i < 6; i++) {
            var v = lobes[i];
            if (v == null || !v.isFinite() || v.x() < 0 || v.y() < 0 || v.z() < 0)
                throw new IllegalArgumentException("Irradiance must be finite and nonnegative");
            values[i * 3] = v.x(); values[i * 3 + 1] = v.y(); values[i * 3 + 2] = v.z();
        }
    }
    public Vector3f lobe(int index) { return new Vector3f(values[index * 3], values[index * 3 + 1], values[index * 3 + 2]); }
    public Vector3f sample(Vector3fc normal) {
        if (!normal.isFinite() || normal.lengthSquared() < 1e-12f) throw new IllegalArgumentException("Invalid irradiance normal");
        var n = new Vector3f(normal).normalize();
        return lobe(n.x < 0 ? 1 : 0).mul(n.x * n.x)
                .fma(n.y * n.y, lobe(n.y < 0 ? 3 : 2)).fma(n.z * n.z, lobe(n.z < 0 ? 5 : 4));
    }
    public Vector3f average() {
        var result = new Vector3f();
        for (int i = 0; i < 6; i++) result.add(lobe(i));
        return result.div(6);
    }
}
