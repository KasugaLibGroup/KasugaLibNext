package lib.kasuga.rendering.models.uml.backend;
import java.nio.FloatBuffer;
import org.joml.Matrix3fc;
import org.joml.Matrix4fc;

/** The production samplerBuffer ABI: 3 absolute, 3 inverse, 3 normal RGBA texels per bone. */
public final class BonePalettePacker {
    public static final int FLOATS_PER_BONE = 36;
    private BonePalettePacker() {}
    public static void put(FloatBuffer target, Matrix4fc absolute, Matrix4fc inverse, Matrix3fc normal) {
        putAffine(target, absolute); putAffine(target, inverse);
        target.put(normal.m00()).put(normal.m01()).put(normal.m02()).put(0);
        target.put(normal.m10()).put(normal.m11()).put(normal.m12()).put(0);
        target.put(normal.m20()).put(normal.m21()).put(normal.m22()).put(0);
    }
    private static void putAffine(FloatBuffer target, Matrix4fc matrix) {
        target.put(matrix.m00()).put(matrix.m01()).put(matrix.m02()).put(matrix.m30());
        target.put(matrix.m10()).put(matrix.m11()).put(matrix.m12()).put(matrix.m31());
        target.put(matrix.m20()).put(matrix.m21()).put(matrix.m22()).put(matrix.m32());
    }
}
