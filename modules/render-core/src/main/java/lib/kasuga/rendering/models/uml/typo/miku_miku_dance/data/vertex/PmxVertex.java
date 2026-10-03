package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.vertex;

import lib.kasuga.rendering.models.uml.math.binding.BoneBindingFunc;
import lib.kasuga.rendering.models.uml.math.binding.SDEFData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.SDEFBoneBindingData;
import lib.kasuga.rendering.models.uml.structure.basic.data.vertex.VertexData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.bone.PmxBoneBinding;
import org.joml.Vector2f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.Objects;

public class PmxVertex implements VertexData, SDEFBoneBindingData {

    public final Vector3f position;
    public final Vector3f normal;
    public final Vector2f uv;
    public final Vector4f[] additionalVec4f;
    public final byte bindingType;
    public final PmxBoneBinding binding;
    public final float edgeScale;

    public PmxVertex(Vector3f position, Vector3f normal, Vector2f uv, Vector4f[] additionalVec4f, byte bindingType, PmxBoneBinding binding, float edgeScale) {
        this.position = position;
        this.normal = normal;
        this.uv = uv;
        this.additionalVec4f = additionalVec4f;
        this.bindingType = bindingType;
        this.binding = binding;
        this.edgeScale = edgeScale;
    }

    @Override
    public int hashCode() {
        return Objects.hash(position, bindingType, binding);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof PmxVertex v)) return false;
        return Objects.equals(position, v.position) &&
                bindingType == v.bindingType &&
                Objects.equals(binding, v.binding);
    }

    @Override
    public SDEFData getSDEFData() {
        return binding.getSDEFData();
    }
}
