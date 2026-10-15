package lib.kasuga.rendering.models.uml.backend;

import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;

public interface VersionedBackendRenderable {

    void updateForVersion(ModelInstance modelInstance, Bridge<?> bridge);
}
