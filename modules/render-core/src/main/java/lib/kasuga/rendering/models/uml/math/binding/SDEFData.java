package lib.kasuga.rendering.models.uml.math.binding;

import org.joml.Vector3f;

import java.util.Objects;

public record SDEFData(Vector3f c, Vector3f r0, Vector3f r1) {
    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof SDEFData(Vector3f c1, Vector3f r2, Vector3f r3))) return false;
        return c.equals(c1) && r0.equals(r2) && r1.equals(r3);
    }

    @Override
    public int hashCode() {
        return Objects.hash(c, r0, r1);
    }
}
