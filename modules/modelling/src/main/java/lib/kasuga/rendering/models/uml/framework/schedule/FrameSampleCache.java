package lib.kasuga.rendering.models.uml.framework.schedule;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;

/** Render-thread identity cache: several views may share one animation frame token. */
public final class FrameSampleCache<I> {
    private final Set<I> sampled = Collections.newSetFromMap(new IdentityHashMap<>());
    private Object token;

    public void beginFrame(Object frameToken) {
        Objects.requireNonNull(frameToken, "frameToken");
        if (token != frameToken) { sampled.clear(); token = frameToken; }
    }

    /** True only on the first sample across all views and material passes of this frame. */
    public boolean firstSample(I instance) { return sampled.add(Objects.requireNonNull(instance, "instance")); }
    public void forget(I instance) { sampled.remove(instance); }
    public void clear() { sampled.clear(); token = null; }
}
