package lib.kasuga.rendering.models.mc.java_and_bedrock.data;

import lib.kasuga.rendering.models.mc.util.Direction;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.ColorizedMeshData;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector4f;

@Getter
@Setter
public class MCMeshData implements ColorizedMeshData {

    private boolean visible, emissive, shade, ambientOcclusion;
    private Direction direction;
    private Vector4f color;

    public MCMeshData(boolean visible, boolean emissive,
                      boolean shade, boolean ambientOcclusion,
                      Direction direction, Vector4f color) {
        this.visible = visible;
        this.emissive = emissive;
        this.shade = shade;
        this.ambientOcclusion = ambientOcclusion;
        this.direction = direction;
        this.color = color;
    }
}
