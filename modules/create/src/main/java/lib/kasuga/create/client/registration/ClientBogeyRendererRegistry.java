package lib.kasuga.create.client.registration;

import com.simibubi.create.content.trains.bogey.BogeyRenderer;
import com.simibubi.create.content.trains.bogey.BogeyVisualizer;
import io.micronaut.context.annotation.Context;
import lib.kasuga.create.CreateModBeans;
import lib.kasuga.create.registration.bogey.BogeyRendererHandle;
import lib.kasuga.inject.class_loader.BeanOnlyIn;

import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Supplier;

@Context
@CreateModBeans
@BeanOnlyIn.Client
public final class ClientBogeyRendererRegistry {
    public record Definition(
            Supplier<? extends BogeyRenderer> renderer,
            BogeyVisualizer visualizer
    ) {
    }

    private final WeakHashMap<BogeyRendererHandle, Definition> renderers = new WeakHashMap<>();

    public void register(
            BogeyRendererHandle handle,
            Supplier<? extends BogeyRenderer> renderer,
            BogeyVisualizer visualizer
    ) {
        synchronized (renderers) {
            Definition previous = renderers.putIfAbsent(handle, new Definition(renderer, visualizer));
            if (previous != null) {
                throw new IllegalStateException("Duplicate client bogey renderer: " + handle.id());
            }
        }
    }

    public Optional<Definition> get(BogeyRendererHandle handle) {
        synchronized (renderers) {
            return Optional.ofNullable(renderers.get(handle));
        }
    }

    public void remove(BogeyRendererHandle handle) {
        synchronized (renderers) {
            renderers.remove(handle);
        }
    }

    public void clear() {
        synchronized (renderers) {
            renderers.clear();
        }
    }
}
