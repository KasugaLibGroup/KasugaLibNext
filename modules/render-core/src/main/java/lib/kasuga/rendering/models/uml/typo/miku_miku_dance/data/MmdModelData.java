package lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data;

import lib.kasuga.rendering.models.uml.structure.data.ModelData;
import lib.kasuga.rendering.models.uml.typo.miku_miku_dance.data.header.PmxHeader;
import org.joml.Vector3f;

import java.util.Objects;

/** PMX/converted-PMD metadata retained on each built model for UI and physics backends. */
public record MmdModelData(PmxHeader header, PmxTail tail, Vector3f modelScale,
                           int pmxBoneCount) implements ModelData {
    public MmdModelData(PmxHeader header, PmxTail tail) {
        this(header, tail, new Vector3f(1f), -1);
    }

    public MmdModelData(PmxHeader header, PmxTail tail, Vector3f modelScale) {
        this(header, tail, modelScale, -1);
    }

    public MmdModelData {
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(tail, "tail");
        modelScale = new Vector3f(Objects.requireNonNull(modelScale, "modelScale"));
        if (pmxBoneCount < -1) throw new IllegalArgumentException("pmxBoneCount must be -1 or non-negative");
    }

    @Override
    public Vector3f modelScale() {
        return new Vector3f(modelScale);
    }

    @Override
    public boolean isMeshTriangles() {
        return true;
    }
}
