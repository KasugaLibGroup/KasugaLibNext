package lib.kasuga.rendering.models.mc.util.box_layer;

import lombok.Getter;

import java.util.HashMap;

@Getter
public class FaceUVInfo {

    private final FaceInfo info;

    private final HashMap<FaceInfo.VertexInfo, UVCorner> uv;

    public FaceUVInfo(FaceInfo info) {
        this.info = info;
        this.uv = new HashMap<>();
        for(FaceInfo.VertexInfo vInfo : info.getCorners()) {
            uv.put(vInfo, UVCorner.getCorner(vInfo, info.getDirection()));
        }
    }
}
