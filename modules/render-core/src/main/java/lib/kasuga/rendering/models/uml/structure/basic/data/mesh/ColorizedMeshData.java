package lib.kasuga.rendering.models.uml.structure.basic.data.mesh;

import org.joml.Vector4f;

public interface ColorizedMeshData extends MeshData {

    Vector4f getColor();

    default int getPackedColorARGB() {
        Vector4f color = getColor();
        int r = (int) (color.x * 255) & 0xFF;
        int g = (int) (color.y * 255) & 0xFF;
        int b = (int) (color.z * 255) & 0xFF;
        int a = (int) (color.w * 255) & 0xFF;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    default int getPackedColorRGBA() {
        Vector4f color = getColor();
        int r = (int) (color.x * 255) & 0xFF;
        int g = (int) (color.y * 255) & 0xFF;
        int b = (int) (color.z * 255) & 0xFF;
        int a = (int) (color.w * 255) & 0xFF;
        return (r << 24) | (g << 16) | (b << 8) | a;
    }
}
