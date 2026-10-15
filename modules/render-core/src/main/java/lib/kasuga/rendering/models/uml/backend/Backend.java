package lib.kasuga.rendering.models.uml.backend;

import lib.kasuga.rendering.models.uml.bridge.Bridge;
import lib.kasuga.rendering.models.uml.dynamic.ModelInstance;
import lib.kasuga.rendering.models.uml.framework.render.RenderBackend;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Default thread-owned mount registry; drawing and host context creation are extension points. */
public abstract class Backend<T extends Bridge<R>, R, Q, E>
        implements RenderBackend<T, BackendContext<T, R, Q, E>, Q> {
    private final Map<Object, BackendContext<T, R, Q, E>> renderingObjects = new HashMap<>();
    private final Map<Object, BackendContext<T, R, Q, E>> registryView = Collections.unmodifiableMap(renderingObjects);
    private boolean closed;

    @Override
    public Map<Object, BackendContext<T, R, Q, E>> getRenderingObjects() {
        return registryView;
    }

    /**
     * Compatibility fallback for legacy bridges. Typed backends override this
     * method so the backend, rather than the bridge registry, supplies services.
     * The legacy wildcard cannot express the host frame/transform types.
     */
    @SuppressWarnings("unchecked")
    protected BackendContext<T, R, Q, E> createContext(T bridge, ModelInstance instance) {
        return (BackendContext<T, R, Q, E>) bridge.getBackendContext(instance);
    }

    @Override
    public void add(Object key, T bridge, ModelInstance instance) {
        checkOpen();
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(bridge, "bridge");
        Objects.requireNonNull(instance, "instance");
        BackendContext<T, R, Q, E> context = Objects.requireNonNull(createContext(bridge, instance), "context");
        try {
            context.apply();
        } catch (RuntimeException | Error failure) {
            try { context.close(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        BackendContext<T, R, Q, E> replaced = renderingObjects.put(key, context);
        if (replaced != null && replaced != context) release(replaced);
    }

    @Override
    public boolean contains(Object key) {
        return renderingObjects.containsKey(key);
    }

    @Override
    public boolean remove(Object key) {
        BackendContext<T, R, Q, E> removed = renderingObjects.remove(key);
        if (removed == null) return false;
        release(removed);
        return true;
    }

    private void release(BackendContext<T, R, Q, E> context) {
        RuntimeException failure = null;
        try { context.close(); }
        catch (Exception cleanup) { failure = new IllegalStateException("Could not release render context", cleanup); }
        try { onContextReleased(context); }
        catch (RuntimeException cleanup) {
            if (failure == null) failure = cleanup;
            else failure.addSuppressed(cleanup);
        }
        if (failure != null) throw failure;
    }

    /** Called after replacement/removal even if resource release fails; the registry is already updated. */
    protected void onContextReleased(BackendContext<T, R, Q, E> context) {}

    @Override
    public void renderAllObjects(Q renderContext) {
        checkOpen();
        for (BackendContext<T, R, Q, E> renderable : new ArrayList<>(renderingObjects.values())) {
            if (!renderable.isRender() || renderable.isClosed()) continue;
            render(renderable, renderContext);
        }
    }

    protected final void checkOpen() {
        if (closed) throw new IllegalStateException("Backend is closed");
    }

    @Override
    public boolean isClosed() { return closed; }

    @Override
    public void close() throws Exception {
        if (closed) return;
        closed = true;
        RuntimeException failure = null;
        for (Object key : new ArrayList<>(renderingObjects.keySet())) {
            try { remove(key); }
            catch (RuntimeException cleanup) {
                if (failure == null) failure = cleanup;
                else failure.addSuppressed(cleanup);
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public abstract void render(BackendContext<T, R, Q, E> renderable, Q renderContext);
}
