package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.mesh;

import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.ColorizedMeshData;
import lib.kasuga.rendering.models.uml.structure.basic.data.mesh.MeshData;
import lombok.Getter;
import lombok.Setter;
import org.joml.Vector4f;

public class PmxMesh implements MeshData, ColorizedMeshData {

    @Getter
    private final Number vertex1, vertex2, vertex3;

    @Getter
    @Setter
    private Vector4f meshColor;

    public PmxMesh(Number vertex1, Number vertex2, Number vertex3) {
        this.vertex1 = vertex1;
        this.vertex2 = vertex2;
        this.vertex3 = vertex3;
        meshColor = new Vector4f(1, 1, 1, 1);
    }

    @Override
    public Vector4f getColor() {
        return meshColor;
    }
}
