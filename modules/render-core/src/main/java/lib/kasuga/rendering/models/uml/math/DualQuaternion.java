package lib.kasuga.rendering.models.uml.math;

import lib.kasuga.structure.Pair;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.Collection;
import java.util.Objects;

@Getter
@Setter
public class DualQuaternion {

    private Quaternionf real, dual;

    public DualQuaternion() {
        this.real = new Quaternionf().identity();
        this.dual = new Quaternionf();
    }

    public DualQuaternion(Quaternionf real, Quaternionf dual) {
        this.real = real;
        this.dual = dual;
    }

    public DualQuaternion(Quaternionf real, Vector3f translation) {
        this.real = real;
        calculateDual(translation);
    }

    public DualQuaternion(Matrix4f m) {
        Quaternionf r = new Quaternionf();
        m.getUnnormalizedRotation(r);
        r.normalize();

        Vector3f t = m.getTranslation(new Vector3f());
        this.real = r;
        calculateDual(t);
    }

    public DualQuaternion(DualQuaternion other) {
        this.real = new Quaternionf(other.real);
        this.dual = new Quaternionf(other.dual);
    }

    private void calculateDual(Vector3f input) {
        this.dual = new Quaternionf(
                input.x * .5f,
                input.y * .5f,
                input.z * .5f, 0
        ).mul(real);
    }

    public DualQuaternion add(DualQuaternion other) {
        this.real.add(other.real);
        this.dual.add(other.dual);
        return this;
    }

    public DualQuaternion mul(float scalar) {
        this.real.mul(scalar);
        this.dual.mul(scalar);
        return this;
    }

    public DualQuaternion mul(DualQuaternion other) {
        Quaternionf newReal = new Quaternionf(this.real).mul(other.real);
        Quaternionf term1 = new Quaternionf(this.real).mul(other.dual);
        Quaternionf term2 = new Quaternionf(this.dual).mul(other.real);
        Quaternionf newDual = term1.add(term2);
        this.real.set(newReal);
        this.dual.set(newDual);
        return this;
    }

    public DualQuaternion conjugate() {
        this.real.conjugate();
        this.dual.conjugate();
        return this;
    }

    public DualQuaternion nomalize() {
        float norm = (float) Math.sqrt(
                real.x() * real.x() +
                real.y() * real.y() +
                real.z() * real.z() +
                real.w() * real.w()
        );
        if (norm < 1e-6f) {
            this.real.identity();
            this.dual.set(0, 0, 0, 0);
            return this;
        }
        real.mul(1 / norm);
        dual.mul(1 / norm);

        float dot =
                real.x() * dual.x() +
                real.y() * dual.y() +
                real.z() * dual.z() +
                real.w() * dual.w();
        dual.set(
                dual.x() - real.x() * dot,
                dual.y() - real.y() * dot,
                dual.z() - real.z() * dot,
                dual.w() - real.w() * dot
        );
        return this;
    }

    public Vector3f transformPoint(Vector3f point) {
        Quaternionf pQuat = new Quaternionf(point.x(), point.y(), point.z(), 0);
        Quaternionf rotated = new Quaternionf(real).mul(pQuat).mul(new Quaternionf(real).conjugate());
        Quaternionf dualConj = new Quaternionf(dual).mul(new Quaternionf(real).conjugate());
        Quaternionf translation = new Quaternionf(dualConj).mul(2);
        point.set(
                rotated.x() + translation.x(),
                rotated.y() + translation.y(),
                rotated.z() + translation.z()
        );
        return point;
    }

    public Matrix4f toMatrix() {
        Matrix4f matrix4f = new Matrix4f();
        matrix4f.set(this.real);

        Quaternionf conjReal = new Quaternionf(this.real).conjugate();
        Quaternionf transQuat = new Quaternionf(this.dual).mul(conjReal).mul(2.0f);
        matrix4f.m30(transQuat.x());
        matrix4f.m31(transQuat.y());
        matrix4f.m32(transQuat.z());
        matrix4f.m33(1f);
        return matrix4f;
    }

    public void set(Quaternionf real, Vector3f translation) {
        this.real.set(real);
        calculateDual(translation);
    }

    public static DualQuaternion blend(Collection<Pair<DualQuaternion, Float>> quaternions) {
        DualQuaternion blended = new DualQuaternion();
        blended.real.set(0, 0, 0, 0);
        blended.dual.set(0, 0, 0, 0);
        Quaternionf reference = null;
        for (Pair<DualQuaternion, Float> pair : quaternions) {
            DualQuaternion dq = pair.getFirst();
            float weight = pair.getSecond();
            if (weight == 0f) continue;
            if (reference == null) reference = dq.real;
            if (reference.dot(dq.real) < 0f) weight = -weight;
            blended.real.set(blended.real.x + dq.real.x * weight, blended.real.y + dq.real.y * weight,
                    blended.real.z + dq.real.z * weight, blended.real.w + dq.real.w * weight);
            blended.dual.set(blended.dual.x + dq.dual.x * weight, blended.dual.y + dq.dual.y * weight,
                    blended.dual.z + dq.dual.z * weight, blended.dual.w + dq.dual.w * weight);
        }
        blended.nomalize();
        return blended;
    }

    @Override
    public String toString() {
        return "DualQuaternion{" +
                "real=" + real +
                ", dual=" + dual +
                '}';
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (!(obj instanceof DualQuaternion other)) return false;
        return real.equals(other.real) && dual.equals(other.dual);
    }

    @Override
    public int hashCode() {
        return Objects.hash(this.real, this.dual);
    }
}
