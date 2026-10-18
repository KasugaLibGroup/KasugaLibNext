package lib.kasuga.rendering.models.uml.backend.gpu;
import org.lwjgl.opengl.*;
import java.util.Map;

/** Shared production attribute contract for the independent GL host and Minecraft adapter. */
public final class SkinningAttributes {
    public static final Map<String,Integer> LOCATIONS = Map.of("Position",0,"Normal",5,"Tangent",7,
            "BoneBindingType",8,"BoneIndices",9,"BoneWeights",10,"sdefR0",11,"sdefR1",12,"sdefC",13);
    private SkinningAttributes() {}
    public static void configure(int stride, int position, int normal, int tangent, int binding,
                                 int indices, int weights, int r0, int r1, int center) {
        floats(0,3,stride,position);
        GL20.glEnableVertexAttribArray(5);
        GL20.glVertexAttribPointer(5,3,GL11.GL_BYTE,true,stride,normal);
        floats(7,4,stride,tangent);
        bindingType(8, stride, binding);
        floats(9,4,stride,indices); floats(10,4,stride,weights);
        floats(11,3,stride,r0); floats(12,3,stride,r1); floats(13,3,stride,center);
    }
    /** Minecraft's GENERIC setup uses a float pointer even for INT elements. */
    public static void bindingType(int location, int stride, int offset) {
        GL20.glEnableVertexAttribArray(location);
        GL30.glVertexAttribIPointer(location,1,GL11.GL_INT,stride,offset);
    }
    private static void floats(int location, int size, int stride, int offset) {
        GL20.glEnableVertexAttribArray(location);
        GL20.glVertexAttribPointer(location,size,GL11.GL_FLOAT,false,stride,offset);
    }
}
