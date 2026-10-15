package lib.kasuga.rendering.models.uml.dynamic.tick_loop;

import lib.kasuga.rendering.models.uml.math.Transform;
import lombok.Getter;
import lombok.Setter;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

@Getter
@Setter
public class PendingTransform {
    private float offsetX, offsetY, offsetZ;
    private float rotationX, rotationY, rotationZ;
    private float scaleX, scaleY, scaleZ;

    public PendingTransform() {
        this.scaleX = 1.0f;
        this.scaleY = 1.0f;
        this.scaleZ = 1.0f;
    }

    /** True when no offset, rotation or scale is pending — alloc-free hot-path check. */
    public boolean isIdentity() {
        return offsetX == 0f && offsetY == 0f && offsetZ == 0f
                && rotationX == 0f && rotationY == 0f && rotationZ == 0f
                && scaleX == 1f && scaleY == 1f && scaleZ == 1f;
    }

    public PendingTransform copy() {
        PendingTransform copy = new PendingTransform();
        copy.offsetX = offsetX;
        copy.offsetY = offsetY;
        copy.offsetZ = offsetZ;
        copy.rotationX = rotationX;
        copy.rotationY = rotationY;
        copy.rotationZ = rotationZ;
        copy.scaleX = scaleX;
        copy.scaleY = scaleY;
        copy.scaleZ = scaleZ;
        return copy;
    }

    public void scale(float x, float y, float z) {
        this.scaleX = x;
        this.scaleY = y;
        this.scaleZ = z;
    }

    public void scale(Vector3f vector) {
        this.scaleX = vector.x;
        this.scaleY = vector.y;
        this.scaleZ = vector.z;
    }

    public void rotate(float x, float y, float z) {
        this.rotationX = x;
        this.rotationY = y;
        this.rotationZ = z;
    }

    public void rotateDeg(float x, float y, float z) {
        this.rotationX = (float) Math.toRadians(x);
        this.rotationY = (float) Math.toRadians(y);
        this.rotationZ = (float) Math.toRadians(z);
    }

    public void rotate(Vector3f vector) {
        this.rotationX = vector.x;
        this.rotationY = vector.y;
        this.rotationZ = vector.z;
    }

    public void rotateDeg(Vector3f vector) {
        this.rotationX = (float) Math.toRadians(vector.x);
        this.rotationY = (float) Math.toRadians(vector.y);
        this.rotationZ = (float) Math.toRadians(vector.z);
    }

    public Transform toTransform() {
        Transform result = new Transform();
        result.translate(offsetX, offsetY, offsetZ);
        result.rotate(rotationX, rotationY, rotationZ, false);
        result.scale(scaleX, scaleY, scaleZ);
        return result;
    }

    public Transform process(Transform input, TransformLimitation limitation) {
        float clampedOffsetX = limitation.getOffsetX().process(this.offsetX);
        float clampedOffsetY = limitation.getOffsetY().process(this.offsetY);
        float clampedOffsetZ = limitation.getOffsetZ().process(this.offsetZ);
        float clampedRotationX = limitation.getRotationX().process(this.rotationX);
        float clampedRotationY = limitation.getRotationY().process(this.rotationY);
        float clampedRotationZ = limitation.getRotationZ().process(this.rotationZ);
        float clampedScaleX = limitation.getScaleX().process(this.scaleX);
        float clampedScaleY = limitation.getScaleY().process(this.scaleY);
        float clampedScaleZ = limitation.getScaleZ().process(this.scaleZ);

        input.translate(clampedOffsetX, clampedOffsetY, clampedOffsetZ);
        input.rotate(clampedRotationX, clampedRotationY, clampedRotationZ, false);
        input.scale(clampedScaleX, clampedScaleY, clampedScaleZ);

        return input;
    }

    public static PendingTransform fromTransform(Transform transform) {
        PendingTransform pt = new PendingTransform();
        Matrix4f mat = transform.transform();

        pt.offsetX = mat.m03();
        pt.offsetY = mat.m13();
        pt.offsetZ = mat.m23();

        Quaternionf q = mat.getNormalizedRotation(new Quaternionf());
        Vector3f euler = q.getEulerAnglesXYZ(new Vector3f());
        pt.rotationX = euler.x;
        pt.rotationY = euler.y;
        pt.rotationZ = euler.z;

        Matrix3f rotMat = new Matrix3f().set(q);
        Matrix3f upperLeft = new Matrix3f();
        mat.get3x3(upperLeft);
        Matrix3f rTranspose = new Matrix3f(rotMat).transpose();
        Matrix3f scaleMat = rTranspose.mul(upperLeft);

        pt.scaleX = scaleMat.m00;
        pt.scaleY = scaleMat.m11;
        pt.scaleZ = scaleMat.m22;

        return pt;
    }
}
