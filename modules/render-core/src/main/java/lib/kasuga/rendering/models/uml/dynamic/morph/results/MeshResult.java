package lib.kasuga.rendering.models.uml.dynamic.morph.results;

import lib.kasuga.rendering.models.uml.structure.basic.Mesh;
import lombok.Getter;

/**
 * Morph result for a {@link Mesh}. Stores mesh-level morph outputs.
 * Currently a placeholder as mesh morph types are not yet defined.
 */
@Getter
public class MeshResult implements IMorphResult<Mesh> {

    private final Mesh original;

    public MeshResult(Mesh original) {
        this.original = original;
    }

    @Override
    public void reset() {
    }

    @Override
    public boolean isEmpty() {
        return true;
    }
}
