package lib.kasuga.rendering.models.mc.backend.data_type.buffer;

import lib.kasuga.rendering.models.uml.backend.cpu.vbo.integer.IntVec4fBuffer;
import org.joml.Vector4f;

public class PackedColorBuffer extends IntVec4fBuffer {

    public PackedColorBuffer(int dataSize) {
        super(dataSize);
    }

    public PackedColorBuffer(Vector4f[] data) {
        super(data);
    }

    /**
     * Must be ABGR color
     * @param value color input
     * @return packed color as int
     */
    @Override
    public int convertToInt(Vector4f value) {
        int a = (int) (value.x * 255) & 0xFF;
        int b = (int) (value.y * 255) & 0xFF;
        int g = (int) (value.z * 255) & 0xFF;
        int r = (int) (value.w * 255) & 0xFF;
        int color = (a << 24) | (b << 16) | (g << 8) | r;
        return isLittleEndian() ? color : Integer.reverseBytes(color);
    }

    /**
     * returns color in ABGR format
     * @param value packed color as int
     * @return color output
     */
    @Override
    public Vector4f convertFromInt(int value) {
        if (isBigEndian()) value = Integer.reverseBytes(value);
        int a = (value >> 24) & 0xFF;
        int b = (value >> 16) & 0xFF;
        int g = (value >> 8) & 0xFF;
        int r = value & 0xFF;
        return new Vector4f(a / 255f, b / 255f, g / 255f, r / 255f);
    }

    public static Vector4f rgbaToAbgr(Vector4f rgba) {
        return new Vector4f(rgba.w, rgba.z, rgba.y, rgba.x);
    }

    public static Vector4f argbToAbgr(Vector4f argb) {
        return new Vector4f(argb.x, argb.z, argb.y, argb.w);
    }

    public static Vector4f abgrToRgba(Vector4f abgr) {
        return new Vector4f(abgr.w, abgr.z, abgr.y, abgr.x);
    }

    public static Vector4f abgrToArgb(Vector4f abgr) {
        return new Vector4f(abgr.x, abgr.z, abgr.y, abgr.w);
    }
}
