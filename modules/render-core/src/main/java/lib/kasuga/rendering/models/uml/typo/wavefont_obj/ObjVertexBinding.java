package lib.kasuga.rendering.models.uml.typo.wavefont_obj;

import java.util.Objects;

public record ObjVertexBinding(int vertexIndex, int textureIndex, int normalIndex) {

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;

        ObjVertexBinding that = (ObjVertexBinding) obj;

        return vertexIndex == that.vertexIndex &&
                textureIndex == that.textureIndex &&
                normalIndex == that.normalIndex;
    }

    @Override
    public int hashCode() {
        return Objects.hash(vertexIndex, textureIndex, normalIndex);
    }
}
