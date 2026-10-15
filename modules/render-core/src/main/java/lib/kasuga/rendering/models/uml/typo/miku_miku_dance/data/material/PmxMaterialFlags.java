package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.material;

public class PmxMaterialFlags {

    public final boolean
            noCull, groundShadow, drawShadow,
            receiveShadow, hasEdge, vertexColor,
            pointDrawing, lineDrawing;


    public PmxMaterialFlags(boolean[] flags) {
        noCull = flags[0];
        groundShadow = flags[1];
        drawShadow = flags[2];
        receiveShadow = flags[3];
        hasEdge = flags[4];
        vertexColor = flags[5];
        pointDrawing = flags[6];
        lineDrawing = flags[7];
    }

}
