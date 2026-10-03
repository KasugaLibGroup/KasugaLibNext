package lib.kasuga.rendering.models.uml.backend;

import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.framework.render.RenderContext;
import lib.kasuga.rendering.models.uml.framework.render.RenderableFactory;
import lombok.Getter;
import lombok.Setter;

import java.util.Objects;

/** Cached render resource owned by one backend mount; pose updates are backend-owned. */
public abstract class BackendContext<
        BridgeType extends Bridge<BackendRenderableType>,
        BackendRenderableType,
        BackendContextType,
        BackendTransformType>
        implements RenderContext<BackendRenderableType, BackendContextType, BackendTransformType> {
    @Getter
    private final BridgeType bridge;
    @Getter
    private final ModelInstance modelInstance;
    private final RenderableFactory<? extends BackendRenderableType> factory;
    private BackendRenderableType cache;
    @Getter
    @Setter
    private boolean render = true;
    @Getter
    private boolean closed;

    /** Compatibility constructor using the bridge's legacy resource factory. */
    public BackendContext(BridgeType bridge, ModelInstance modelInstance) {
        this(bridge, modelInstance, bridge);
    }

    public BackendContext(BridgeType bridge, ModelInstance modelInstance,
                          RenderableFactory<? extends BackendRenderableType> factory) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.modelInstance = Objects.requireNonNull(modelInstance, "modelInstance");
        this.factory = Objects.requireNonNull(factory, "factory");
    }

    @Override
    public BackendRenderableType apply() {
        if (closed) throw new IllegalStateException("Render context is closed");
        if (cache == null) cache = Objects.requireNonNull(factory.createRenderable(modelInstance), "renderable");
        return cache;
    }

    @Override
    public abstract BackendTransformType beforeRender(BackendContextType context);

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        BackendRenderableType retired = cache;
        cache = null;
        if (retired instanceof AutoCloseable closeable) closeable.close();
    }
}
